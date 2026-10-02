/*
 *  Copyright 2021-2026 the original author or authors.
 *
 *  Licensed under the Apache License, Version 2.0 (the "License");
 *  you may not use this file except in compliance with the License.
 *  You may obtain a copy of the License at
 *
 *       https://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 */

package dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.cdc;

import dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.*;
import dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.eventstream.*;
import dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.gap.*;
import dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.interceptor.EventStoreInterceptor;
import dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.observability.EventStoreSubscriptionObserver;
import dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.operations.*;
import dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.persistence.AggregateEventStreamConfiguration;
import dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.serializer.AggregateIdSerializer;
import dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.transaction.*;
import dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.types.GlobalEventOrder;
import dk.trustworks.essentials.components.foundation.types.*;
import io.micrometer.core.instrument.*;
import dk.trustworks.essentials.reactive.EventBus;
import dk.trustworks.essentials.types.LongRange;
import io.micrometer.core.instrument.Timer;
import org.reactivestreams.Subscription;
import org.slf4j.*;
import reactor.core.*;
import reactor.core.publisher.*;
import reactor.core.scheduler.*;
import reactor.util.retry.Retry;

import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import java.util.function.*;
import java.util.stream.Stream;

import static dk.trustworks.essentials.shared.FailFast.*;

/**
 * The CdcEventStore class is responsible for managing the event sourcing mechanics
 * while incorporating Change Data Capture (CDC) functionalities. It serves as a decorator
 * over the base EventStore implementation, adding support for backfills, event gap handling,
 * and advanced features for capturing data changes.
 * <p>
 * <p>
 * It decorates a {@link ConfigurableEventStore} and is itself a {@link ConfigurableEventStore}: the CDC store is
 * registered as the {@code @Primary} bean under the {@link ConfigurableEventStore} type, so every injection point —
 * whether it asks for {@link EventStore} or {@link ConfigurableEventStore} — receives this one decorator rather than
 * the store it wraps. Implementing the whole configuration contract is what makes that single identity possible, and
 * it is also what keeps {@code AbstractEventProcessor} working: it narrows the injected {@code EventStore} to
 * {@link ConfigurableEventStore} to look up an {@link AggregateIdSerializer}. Were the decorator to expose only
 * {@link EventStore}, applications would hold two different stores and the {@link ConfigurableEventStore}-typed one
 * would silently poll without CDC. The mutators return {@code this} so a caller that configures through the decorator
 * keeps hold of the decorator.
 * <p>
 * Public Constructors:
 * - CdcEventStore(ConfigurableEventStore&lt;CONFIG&gt; delegate, EventStoreUnitOfWorkFactory&lt;? extends EventStoreUnitOfWork&gt; unitOfWorkFactory,
 *   EventStreamGapHandler&lt;?&gt; eventStreamGapHandler, CdcEventBus cdcBus, CdcProperties cdcProperties,
 *   CdcAvailability availability)
 * - CdcEventStore(ConfigurableEventStore&lt;CONFIG&gt; delegate, EventStoreUnitOfWorkFactory&lt;? extends EventStoreUnitOfWork&gt; unitOfWorkFactory,
 *   EventStreamGapHandler&lt;?&gt; eventStreamGapHandler, CdcEventBus cdcBus, CdcProperties cdcProperties,
 *   CdcAvailability availability, Optional&lt;MeterRegistry&gt; meterRegistry)
 * <p>
 * Public Methods:
 * - pollEvents: Polls a stream of persisted events based on the provided aggregate type and filtering criteria.
 * - findHighestGlobalEventOrderPersisted: Finds the highest global event order that has been persisted for a given aggregate type.
 * - findLowestGlobalEventOrderPersisted: Finds the lowest global event order that has been persisted for a given aggregate type.
 * - getUnitOfWorkFactory: Retrieves the factory for creating event store units of work.
 * - localEventBus: Returns a local event bus instance for handling in-memory event operations.
 * - getEventStoreSubscriptionObserver: Retrieves the subscription observer for monitoring event store activities.
 * - getEventStoreInterceptors: Provides a list of configured interceptors for the event store.
 * - appendToStream: Appends a batch of events to a specific stream identified by the aggregate type and ID.
 * - loadLastPersistedEventRelatedTo: Loads the last persisted event related to a specific aggregate ID.
 * - loadEvent: Loads a specific event based on the provided criteria.
 * - loadEvents: Loads multiple events based on provided query parameters.
 * - fetchStream: Fetches an aggregate event stream based on the provided fetch operation.
 * - inMemoryProjection: Computes an in-memory projection based on the specified aggregate type, ID, and projection type.
 * - loadEventsByGlobalOrder: Loads a stream of events ordered globally based on specified criteria.
 * - unboundedPollForEvents: Polls for events without any bounded stopping condition, with optional filters and polling intervals.
 * - getCdcBus: Retrieves the CDC event bus associated with this store.
 * <p>
 * Additional Private and Overridable Methods:
 * - backfillFlux: Generates a flux of events during backfill, supporting pagination and optional gap handling.
 * - backfillOnePageAndEmit: Processes a single page of backfill operations, with support for emitting events to a consumer.
 */
public class CdcEventStore<CONFIG extends AggregateEventStreamConfiguration> implements ConfigurableEventStore<CONFIG> {

    private static final Logger log = LoggerFactory.getLogger(CdcEventStore.class);

    /**
     * How long a subscription waits before retrying a catch-up onto the CDC bus that failed, typically because the
     * database was unreachable (see {@link #buildAdaptiveLiveSource})
     */
    private static final Duration CATCH_UP_RETRY_DELAY = Duration.ofSeconds(1);

    /**
     * At most this many of the global orders a subscription still waits for (see {@link CdcDeliveryTracker#awaitedGaps})
     * are asked for by order in one catch-up or back-fill - it bounds the {@code IN} list of that query
     */
    private static final int MAX_AWAITED_GAPS_TO_REQUERY = 1_000;

    private final ConfigurableEventStore<CONFIG>                              eventStore;
    private final EventStoreUnitOfWorkFactory<? extends EventStoreUnitOfWork> unitOfWorkFactory;
    private final EventStreamGapHandler<?>                                    eventStreamGapHandler;
    private final CdcEventBus                                                 cdcBus;
    private final CdcProperties.CdcEventBusProperties                         eventBusProperties;
    private final int                                                         backfillBatchSize;
    private final CdcAvailability                                             availability;
    /**
     * How long availability must remain ACTIVE before an in-flight live subscription currently
     * consuming from polling switches back to the CDC bus. See
     * {@link CdcProperties.CdcHealthCheckProperties#getActiveCutbackDebounce()} for the full
     * rationale. FAILED/INACTIVE transitions switch to polling immediately; only ACTIVE cutbacks
     * are debounced.
     */
    private final Duration                                                    activeCutbackDebounce;
    private final MeterRegistry                                               meterRegistry;
    private final Counter                                                     fallbackPollCounter;
    /**
     * Counts every time an in-flight adaptive-live subscription switches its source between the
     * CDC bus and classic polling. High values during normal operation indicate availability
     * thrashing and likely mean the underlying CDC pipeline is unstable.
     */
    private final Counter                                                     liveSourceSwitchCounter;
    /**
     * Counts every time a subscription fell so far behind the CDC bus that its own hand-over buffer overflowed, so it
     * left the bus and continued on polling (see "Backpressure" on {@link #buildAdaptiveLiveSource}). Not CDC health:
     * CDC is fine, one subscriber is slow or stalled. Not counted by {@link #liveSourceSwitchCounter}, which tracks
     * availability-driven switches.
     */
    private final Counter                                                     liveSourceOverflowCounter;
    private final DistributionSummary                                         backfillLoadedSummary;
    private final DistributionSummary                                         backfillQueryRangeSummary;
    private final Counter                                                     liveEventsCounter;
    private final Timer                                                       backfillPageTimer;
    private final Timer                                                       backfillToLiveTransitionTimer;
    /**
     * Live size of the in-memory live-event buffer inside the currently-running
     * {@link BackfillThenLiveOrdered} pipeline. Updated by BackfillThenLiveOrdered as events flow
     * through its ordering buffer, so operators can observe pressure in real time and perf-lab /
     * backpressure tests can assert the bound holds. Multiple concurrent subscriptions share this
     * gauge (last-writer-wins aggregation) — acceptable for the expected single-subscription-per-
     * aggregate case.
     */
    private final AtomicInteger                                               backfillLiveBufferSize = new AtomicInteger(0);

    /**
     * Create a {@link CdcEventStoreBuilder} that names every argument and accepts both plain values and
     * {@link Optional}s.
     *
     * @param <CONFIG> the event-stream configuration type
     * @return the builder
     */
    public static <CONFIG extends AggregateEventStreamConfiguration> CdcEventStoreBuilder<CONFIG> builder() {
        return new CdcEventStoreBuilder<>();
    }

    /**
     * @param delegate              the {@link ConfigurableEventStore} being decorated
     * @param unitOfWorkFactory     the unit-of-work factory
     * @param eventStreamGapHandler the gap handler
     * @param cdcBus                the in-memory CDC fan-out bus
     * @param cdcProperties         the CDC configuration
     * @param availability          the shared CDC availability tracker
     */
    CdcEventStore(ConfigurableEventStore<CONFIG> delegate,
           EventStoreUnitOfWorkFactory<? extends EventStoreUnitOfWork> unitOfWorkFactory,
           EventStreamGapHandler<?> eventStreamGapHandler,
           CdcEventBus cdcBus,
           CdcProperties cdcProperties,
           CdcAvailability availability) {
        this(delegate, unitOfWorkFactory, eventStreamGapHandler, cdcBus, cdcProperties, availability, Optional.empty());
    }

    /**
     * @param delegate              the {@link ConfigurableEventStore} being decorated
     * @param unitOfWorkFactory     the unit-of-work factory
     * @param eventStreamGapHandler the gap handler
     * @param cdcBus                the in-memory CDC fan-out bus
     * @param cdcProperties         the CDC configuration
     * @param availability          the shared CDC availability tracker
     * @param meterRegistry         optional {@link MeterRegistry} — when empty, no CDC event-store metrics are recorded
     */
    CdcEventStore(ConfigurableEventStore<CONFIG> delegate,
                         EventStoreUnitOfWorkFactory<? extends EventStoreUnitOfWork> unitOfWorkFactory,
                         EventStreamGapHandler<?> eventStreamGapHandler,
                         CdcEventBus cdcBus,
                         CdcProperties cdcProperties,
                         CdcAvailability availability,
                         Optional<MeterRegistry> meterRegistry) {
        this.eventStore = requireNonNull(delegate, "delegate eventStore must not be null");
        this.unitOfWorkFactory = requireNonNull(unitOfWorkFactory, "unitOfWorkFactory must not be null");
        this.eventStreamGapHandler = requireNonNull(eventStreamGapHandler, "eventStreamGapHandler must not be null");
        this.cdcBus = requireNonNull(cdcBus, "cdcBus must not be null");
        requireNonNull(cdcProperties, "cdcProperties must not be null");
        this.eventBusProperties = requireNonNull(cdcProperties.getEventBus(), "cdcProperties.eventBus must not be null");
        requireTrue(eventBusProperties.getBackpressureBufferSize() > 0, "eventBus.backpressureBufferSize must be > 0");
        requireTrue(eventBusProperties.getNonSerializedMaxRetries() > 0, "eventBus.nonSerializedMaxRetries must be > 0");
        requireTrue(eventBusProperties.getOverflowMaxRetries() >= 0, "eventBus.overflowMaxRetries must be >= 0");
        this.availability = requireNonNull(availability, "availability must not be null");
        requireTrue(cdcProperties.getCdcEventStoreBackfillBatchSize() >= 1, "backfillBatchSize must be >= 1");
        this.backfillBatchSize = cdcProperties.getCdcEventStoreBackfillBatchSize();
        this.activeCutbackDebounce = requireNonNull(cdcProperties.getHealthCheck(), "cdcProperties.healthCheck must not be null")
                .getActiveCutbackDebounce();
        requireNonNull(this.activeCutbackDebounce, "cdcProperties.healthCheck.activeCutbackDebounce must not be null");
        requireTrue(!this.activeCutbackDebounce.isNegative(), "cdcProperties.healthCheck.activeCutbackDebounce must not be negative");
        this.meterRegistry = meterRegistry.orElse(null);
        if (this.meterRegistry != null) {
            fallbackPollCounter = Counter.builder("essentials.cdc.eventstore.fallback.poll.count").register(this.meterRegistry);
            liveSourceSwitchCounter = Counter.builder("essentials.cdc.eventstore.live_source.switch.count").register(this.meterRegistry);
            liveSourceOverflowCounter = Counter.builder("essentials.cdc.eventstore.live_source.overflow.count")
                                               .description("Number of times a subscription fell further behind the CDC bus than its hand-over buffer holds and continued on polling")
                                               .register(this.meterRegistry);
            backfillLoadedSummary = DistributionSummary.builder("essentials.cdc.eventstore.backfill.loaded").register(this.meterRegistry);
            backfillQueryRangeSummary = DistributionSummary.builder("essentials.cdc.eventstore.backfill.query_range").register(this.meterRegistry);
            liveEventsCounter = Counter.builder("essentials.cdc.eventstore.live.events").register(this.meterRegistry);
            backfillPageTimer = io.micrometer.core.instrument.Timer.builder("essentials.cdc.eventstore.backfill.page.latency")
                                                                   .register(this.meterRegistry);
            backfillToLiveTransitionTimer = io.micrometer.core.instrument.Timer.builder("essentials.cdc.eventstore.backfill_to_live.transition.latency")
                                                                               .register(this.meterRegistry);
            // Still registered, so dashboards and alerts built on it keep resolving, but it stays at 0: the live-tail
            // drain of BackfillThenLiveOrdered no longer waits for a missing global order, so there is no stall to
            // detect (see "Why not in global order past the head" on BackfillThenLiveOrdered)
            Counter.builder("essentials.cdc.backfill_live.stall_detected")
                   .description("Always 0: the BackfillThenLiveOrdered live-tail drain no longer waits for a missing global order, so it cannot stall on one. Kept for compatibility")
                   .register(this.meterRegistry);
            Gauge.builder("essentials.cdc.backfill_live.buffer.size", backfillLiveBufferSize, AtomicInteger::get)
                 .description("Current size of the in-memory live-event buffer inside BackfillThenLiveOrdered; bounded by eventBus.backpressureBufferSize")
                 .register(this.meterRegistry);
        } else {
            fallbackPollCounter = null;
            liveSourceSwitchCounter = null;
            liveSourceOverflowCounter = null;
            backfillLoadedSummary = null;
            backfillQueryRangeSummary = null;
            liveEventsCounter = null;
            backfillPageTimer = null;
            backfillToLiveTransitionTimer = null;
        }
        // No longer read (see CdcEventBusProperties.getLiveDrainStallThreshold), still validated as before
        requireTrue(!eventBusProperties.getLiveDrainStallThreshold().isNegative(),
                    "eventBus.liveDrainStallThreshold must not be negative");
    }

    @Override
    public Flux<PersistedEvent> pollEvents(AggregateType aggregateType,
                                           long fromInclusiveGlobalOrder,
                                           Optional<Integer> loadEventsByGlobalOrderBatchSize,
                                           Optional<Duration> pollingInterval,
                                           Optional<Tenant> onlyIncludeEventIfItBelongsToTenant,
                                           Optional<SubscriberId> subscriptionId,
                                           Optional<Function<String, EventStorePollingOptimizer>> eventStorePollingOptimizerFactory) {
        int                              pageSize   = loadEventsByGlobalOrderBatchSize.orElse(backfillBatchSize);
        Optional<SubscriptionGapHandler> gapHandler = subscriptionId.map(eventStreamGapHandler::gapHandlerFor);
        if (!availability.isActive()) {
            log.debug("Cdc is not active, using polling fallback");
            availability.fallbackUsed();
            if (fallbackPollCounter != null) fallbackPollCounter.increment();
            // One delivery tracker per subscription - so per subscribe, not per call
            return Flux.defer(() -> buildAdaptiveLiveSource(
                    aggregateType,
                    newDeliveryTracker(aggregateType, fromInclusiveGlobalOrder, subscriptionId, gapHandler),
                    // Records what it delivers itself; and should CDC already be ACTIVE by the time the source
                    // subscribes, nothing has covered the backlog, so its first move onto the bus catches up too
                    false,
                    pageSize,
                    onlyIncludeEventIfItBelongsToTenant,
                    pollingInterval,
                    subscriptionId,
                    eventStorePollingOptimizerFactory));
        }

        return Flux.defer(() -> orderedBackfillThenLive(aggregateType,
                                                        fromInclusiveGlobalOrder,
                                                        pageSize,
                                                        newDeliveryTracker(aggregateType, fromInclusiveGlobalOrder, subscriptionId, gapHandler),
                                                        gapHandler,
                                                        onlyIncludeEventIfItBelongsToTenant,
                                                        pollingInterval,
                                                        subscriptionId,
                                                        eventStorePollingOptimizerFactory));
    }

    /**
     * The tracker of what one subscription has delivered (see {@link CdcDeliveryTracker}), seeded with the transient
     * gaps its gap handler still has recorded. Those can lie below where the subscription starts: its resume point
     * advances past a gap - a gap-filled event is delivered after later ones - so an event filling one of them after a
     * restart (or a fenced-lock hand-over to another node) must still get through. Read on the subscribing thread, once
     * per subscription; failing to read them only costs that, so it is logged and the subscription goes ahead.
     */
    private CdcDeliveryTracker newDeliveryTracker(AggregateType aggregateType,
                                                  long fromInclusiveGlobalOrder,
                                                  Optional<SubscriberId> subscriptionId,
                                                  Optional<SubscriptionGapHandler> gapHandler) {
        var name    = subscriptionId.map(Object::toString).orElse("NoSubscriberId") + "-" + aggregateType;
        var tracker = CdcDeliveryTracker.startingAfter(name, fromInclusiveGlobalOrder - 1);
        if (gapHandler.isPresent() && recordsGaps()) {
            try {
                tracker.seedEarlierGaps(gapHandler.get().getTransientGapsFor(aggregateType));
            } catch (RuntimeException e) {
                log.warn("[{}] Could not read the transient gaps recorded for the subscriber - an event filling one of them below global order {} will not be delivered",
                         name, fromInclusiveGlobalOrder, e);
            }
        }
        return tracker;
    }

    /**
     * Whether the configured gap handler records gaps at all - a {@link NoEventStreamGapHandler} does nothing, so there is
     * no point in opening a unit of work for it
     */
    private boolean recordsGaps() {
        return !(eventStreamGapHandler instanceof NoEventStreamGapHandler);
    }

    /**
     * The ACTIVE path of {@link #pollEvents}: {@link BackfillThenLiveOrdered} over a back-fill up to the head and the
     * adaptive live source as its live tail
     */
    private Flux<PersistedEvent> orderedBackfillThenLive(AggregateType aggregateType,
                                                         long fromInclusiveGlobalOrder,
                                                         int pageSize,
                                                         CdcDeliveryTracker tracker,
                                                         Optional<SubscriptionGapHandler> gapHandler,
                                                         Optional<Tenant> onlyIncludeEventIfItBelongsToTenant,
                                                         Optional<Duration> pollingInterval,
                                                         Optional<SubscriberId> subscriptionId,
                                                         Optional<Function<String, EventStorePollingOptimizer>> eventStorePollingOptimizerFactory) {
        var deliveryGate = new DeliveryGate(tracker, aggregateType, gapHandler);
        var resume       = GlobalEventOrder.of(fromInclusiveGlobalOrder);

        // CDC race-safety: the "head" snapshot MUST be read only AFTER the live CDC-bus subscription
        // has been established — never before. The per-aggregate bus sink is a hot multicast that does
        // not replay history to late subscribers, so any event published in the window between a head
        // snapshot and the live attach would be delivered by neither backfill (capped at head) nor the
        // bus. By deferring this read until BackfillThenLiveOrdered has subscribed the live source (see
        // ordered(...)), we guarantee:
        //   - any event published BEFORE the attach is already persisted, hence ≤ head and covered by
        //     backfill (whose upper bound is this same late head);
        //   - any event published AFTER the attach is captured by the live subscription.
        // Read once per subscription (pollEvents defers this method per subscribe), memoized for the backfill.
        long         noHead       = Long.MIN_VALUE;
        AtomicLong   headBox      = new AtomicLong(noHead);
        LongSupplier headSnapshot = () -> {
            long existing = headBox.get();
            if (existing != noHead) return existing;
            long read = unitOfWorkFactory.withUnitOfWork(() -> eventStore.findHighestGlobalEventOrderPersisted(aggregateType))
                                         .map(GlobalEventOrder::longValue)
                                         .orElse(fromInclusiveGlobalOrder - 1);
            headBox.compareAndSet(noHead, read);
            long head = headBox.get();
            log.debug("[{}] CDC poll starting from '{}' (head snapshot: '{}' with batch size '{}')", aggregateType, resume, head, pageSize);
            return head;
        };

        // All tenants, like the live source below: the delivery tracker must see every global order, or another
        // tenant's events would look like gaps to wait for. Filtered by tenant on the ordered output instead
        Flux<PersistedEvent> backfill = backfillFlux(
                aggregateType,
                resume,
                headSnapshot,
                pageSize,
                Optional.empty(),
                gapHandler,
                () -> tracker.awaitedGaps(MAX_AWAITED_GAPS_TO_REQUERY));

        // Dedup is the delivery tracker's: BackfillThenLiveOrdered records each event it hands downstream, and the
        // live source only drops what the tracker says was delivered already. The live source may therefore hand
        // over an event at or below head: one the backfill also loads (dropped once one of them was delivered), or
        // one that committed after the backfill read past it - a lower global order committing after a higher one,
        // delivered late and out of global order rather than lost.
        //
        // Tenant filtering is applied to the ORDERED OUTPUT below, NOT to this live source: the delivery tracker
        // behind BackfillThenLiveOrdered must see every global order, or an other-tenant event sitting between two
        // events this subscriber wants would look like a gap - recorded with the gap handler and waited for (in a
        // multi-tenant deployment, tenants interleave in global_event_order, so this is the common case). The live
        // source therefore delivers the all-tenant stream (the bus is all-tenant anyway, and its polling and
        // catch-ups load all tenants) and we filter once on the ordered output.
        Flux<PersistedEvent> live = buildAdaptiveLiveSource(
                aggregateType,
                tracker,
                // BackfillThenLiveOrdered attaches it before reading head, back-fills up to that head, and records
                // what it delivers in the tracker
                true,
                pageSize,
                Optional.empty(),
                pollingInterval,
                subscriptionId,
                eventStorePollingOptimizerFactory);

        Flux<PersistedEvent> ordered = new BackfillThenLiveOrdered(backfillToLiveTransitionTimer, eventBusProperties, backfillLiveBufferSize, deliveryGate).ordered(
                backfill,
                live,
                headSnapshot);

        return filterByTenant(ordered, onlyIncludeEventIfItBelongsToTenant);
    }

    /**
     * Build the live-event source for an in-flight CDC subscription. The source transparently
     * switches between the CDC bus (while {@link CdcAvailability} is {@link CdcAvailability.State#ACTIVE
     * ACTIVE}) and classic polling (while availability is not ACTIVE), so that subscribers
     * established during healthy CDC continue to receive events even when CDC dies mid-stream, and
     * subscribers that started on polling (CDC warming up, or down) move onto the bus once it is up.
     * <p>
     * Dedup: the subscription's {@link CdcDeliveryTracker} records every global order handed downstream, so any overlap
     * between the outgoing source and the incoming one is dropped rather than double-delivered. It is gap-aware, not a
     * high-water mark: the bus delivers events in <b>commit</b> order, and a transaction that took a lower global order
     * can commit after one that took a higher order. A {@code > lastSeen} filter dropped that lower event for good - on
     * the bus, and when polling's gap handler fetched it late. The tracker lets an order through until it was delivered,
     * waits for the gaps below the highest order delivered until they are older than the permanent-gap window, and the
     * event that fills one is delivered when it arrives - after higher ones, out of global order, as the polling path
     * delivers gap-filled events (a subscriber's resume point only ever advances for that reason). An event that opens or
     * fills a gap is also recorded with the subscriber's gap handler ({@link DeliveryGate}), so a gap the resume point
     * has moved past survives a restart. Polling starts from {@link CdcDeliveryTracker#resumeFromInclusive()} - right
     * after the contiguous watermark, so it reads the gaps again.
     * <p>
     * Gap-free move onto the bus: the bus is a hot multicast that replays nothing to a late subscriber. Attaching to it
     * on its own used to lose every event it had published before the attach that the previous source had not delivered
     * yet - committed after polling's last fetch, during the {@link #activeCutbackDebounce} window, or while the switch
     * waited for an event still in the handler or its retry backoff. So every move onto the bus - the switch to ACTIVE at
     * warm-up, the switch back after an outage, and the recovery from an overflow (see Backpressure) - catches up first
     * ({@link #busLeg}):
     * <ol>
     *     <li>attach to the bus, holding what it delivers in the subscription's bounded hand-over buffer;</li>
     *     <li>only then read the highest persisted global order, the head;</li>
     *     <li>load everything after the highest order delivered up to the head page by page, as backfill does (gap
     *     handler included), and on the first page also the gaps the tracker still waits for, by order;</li>
     *     <li>then drain the hand-over buffer and carry on from the bus.</li>
     * </ol>
     * An event the bus published before the attach was committed before it, so it is at or below a head read after the
     * attach, or fills a gap, and the catch-up loads it; one published after the attach is in the hand-over buffer.
     * Anything both hold is dropped by the tracker. The catch-up ends at the head rather than at "the first buffered bus
     * event" because global order is not contiguous: a rolled-back {@code IDENTITY} value is a hole that is never
     * persisted nor published, so reaching a given order cannot be told from the events seen. This is the
     * read-head-after-attach argument {@link BackfillThenLiveOrdered} makes for a subscription started while CDC is
     * ACTIVE - which is why the first source of such a subscription skips the catch-up
     * ({@code forBackfillThenLiveOrdered}): the caller attaches this source before it reads its own head and back-fills up
     * to it. An event committed after the head read with an order at or below it (a transaction that took its
     * {@code IDENTITY} value earlier) is a gap the tracker waits for, and the bus delivers it once it commits.
     * <p>
     * Cutback debounce: FAILED/INACTIVE transitions cut to polling <b>immediately</b> so
     * subscribers don't stall. Transitions back to ACTIVE are held for
     * {@link #activeCutbackDebounce} with availability staying ACTIVE throughout; if availability
     * flips non-ACTIVE again during the debounce window the pending cutback is cancelled. This
     * prevents thrash when the underlying CDC pipeline oscillates (e.g. pgoutput intermittently
     * stalling). Polling keeps delivering during the window; the catch-up covers whatever it had not fetched yet.
     * <p>
     * Tenants: polling and the catch-up load every tenant's events, and {@code onlyIncludeEventIfItBelongsToTenant} is
     * applied on the way out, after the tracker - whichever source is current, callers see one consistent stream. The
     * tracker has to see every global order: filtered in SQL, another tenant's events would be gaps it waits for.
     * <p>
     * Delivery thread: the CDC bus emits on the shared {@code cdc-dispatcher-<slot>} thread (or the tailer's, in
     * {@code DIRECT} mode), and everything downstream of it runs synchronously — the
     * {@link BackfillThenLiveOrdered} drain and the subscriber's handler, including the synchronous
     * {@code SubscriptionErrorPolicy} retry backoff. Handing the bus leg over to a single thread per subscription
     * ({@code Cdc-<subscriber>-<aggregateType>}) keeps one slow or retrying subscription from holding every other CDC
     * subscription on the slot, just as polling delivers on a {@code Publish-<subscriber>-<aggregateType>} thread per
     * subscription. The catch-up loads its pages and delivers them on that same thread, and recording a gap with the gap
     * handler happens there too. A single thread, so events stay in order; it is disposed when the subscription is
     * cancelled or terminates, and disposing it interrupts a handler in its backoff, as on the polling path. The bus
     * thread itself only ever reads the tracker without a lock ({@link BusHandOver}).
     * <p>
     * Backpressure: a subscription never back-pressures the shared multicast bus sink. That sink is paced by its slowest
     * subscriber, so a subscription stalled in its handler (a retry backoff, a slow call) used to fill the sink's buffer
     * and turn every later emit into {@code FAIL_OVERFLOW} for all subscriptions of the aggregate type - dropped for
     * the healthy ones too under {@code LOG_AND_DROP}, a {@link CdcBusOverflowException} for the dispatcher for as long
     * as the stall lasted under {@code FAIL_FAST}. Instead, each subscription's bus leg requests unbounded demand from
     * the bus and holds what its handler has not taken yet in a hand-over buffer of its own, bounded to
     * {@code pageSize} (one polling page) - which also bounds its memory to one page rather than
     * {@code eventBus.backpressureBufferSize}. When that buffer overflows, only this subscription leaves the bus: its
     * handler is handed what was buffered, and the subscription then catches up from the event after the highest one
     * delivered (and the gaps below it) and rejoins the bus, exactly as above. A catch-up that the bus outpaces overflows
     * the buffer again and the next one starts where it got to; each delivers everything up to its head, and loads a page
     * only when the handler asks for more, so a subscription that cannot keep up with the bus is in effect polling, at
     * its own pace, until it can. The Reactor semantics this relies on, all in {@link #busLeg}:
     * <ul>
     *     <li>{@link BusHandOver} requests {@code Long.MAX_VALUE} from the bus and offers each event to a unicast sink
     *     over a queue of {@code pageSize}. On the first event that does not fit it cancels the bus subscription at once
     *     and terminates the sink with an overflow error. A unicast sink delivers its terminal signal only once its
     *     queue is drained, and {@code publishOn} fuses with it (ASYNC), so that queue is the only one on the leg.</li>
     *     <li>{@code publishOn(..)} (delayError) delivers that error only once the queue is empty, on the delivery
     *     thread, after the last buffered event went through the tracker below.</li>
     *     <li>The {@code retryWhen} sits after {@code publishOn}, inside the switch, so it reads the tracker once
     *     every event the bus delivered has been handed downstream, and re-subscribes on the delivery thread: the next
     *     catch-up starts exactly at the overflowing event. A retry re-subscribes the same chain rather than nesting a
     *     new one, so repeated overflows do not grow the pipeline.</li>
     * </ul>
     * Logged at WARN once per overflow, and counted in {@code essentials.cdc.eventstore.live_source.overflow.count};
     * the catch-up that follows logs at INFO once it is back on the bus. Not a CDC fallback: CDC is healthy, so
     * {@link CdcAvailability#fallbackUsed()} is not called, and not an availability switch, so
     * {@code live_source.switch.count} is not incremented either.
     * <p>
     * A catch-up that fails - the database unreachable while CDC recovers - is retried from where it got to after
     * {@link #CATCH_UP_RETRY_DELAY}, logged at WARN, rather than ending the subscription.
     *
     * @param tracker                    what the subscription has delivered; the source starts after its watermark
     * @param forBackfillThenLiveOrdered true when the caller is {@link BackfillThenLiveOrdered}, which attaches this source
     *                                   before reading its own head, delivers everything up to that head itself, and
     *                                   records what it hands downstream in the tracker: the first move onto the bus then
     *                                   skips the catch-up (every later one catches up regardless), and this source only
     *                                   drops what the tracker already holds, without recording anything
     */
    private Flux<PersistedEvent> buildAdaptiveLiveSource(
            AggregateType aggregateType,
            CdcDeliveryTracker tracker,
            boolean forBackfillThenLiveOrdered,
            int pageSize,
            Optional<Tenant> onlyIncludeEventIfItBelongsToTenant,
            Optional<Duration> pollingInterval,
            Optional<SubscriberId> subscriptionId,
            Optional<Function<String, EventStorePollingOptimizer>> eventStorePollingOptimizerFactory
                                                        ) {
        Flux<CdcAvailability.State> rawStates = availability.stateChanges().distinctUntilChanged();
        var firstEmission = new AtomicBoolean(true);
        Flux<CdcAvailability.State> gatedStates = rawStates
                .switchMap(state -> {
                    if (firstEmission.compareAndSet(true, false)) {
                        return Mono.just(state);
                    }
                    return state == CdcAvailability.State.ACTIVE
                            ? Mono.just(state).delayElement(activeCutbackDebounce)
                            : Mono.just(state);
                })
                .distinctUntilChanged();

        // The source this subscription was last on. A switch off the CDC bus is a fallback in the same sense as
        // starting on polling after CDC had been active (pollEvents records that case): without recording it, a
        // running subscription that polled through a dropped replication connection left fallbackCount at zero.
        var previousState = new AtomicReference<CdcAvailability.State>(null);
        // Only the very first source may skip the catch-up, and only when the caller covers the backlog (see javadoc)
        var catchUpBeforeTheBus = new AtomicBoolean(!forBackfillThenLiveOrdered);
        var gapHandler          = subscriptionId.map(eventStreamGapHandler::gapHandlerFor);
        var deliveryGate        = new DeliveryGate(tracker, aggregateType, gapHandler);
        var subscriberIdForLog  = subscriptionId.map(Object::toString).orElse("NoSubscriberId");
        return Flux.using(() -> Schedulers.newSingle("Cdc-" + subscriberIdForLog + "-" + aggregateType, true),
                          cdcDeliveryScheduler -> gatedStates
                                  .switchMap(state -> {
                                      if (liveSourceSwitchCounter != null) liveSourceSwitchCounter.increment();
                                      if (previousState.getAndSet(state) == CdcAvailability.State.ACTIVE && state != CdcAvailability.State.ACTIVE) {
                                          availability.fallbackUsed();
                                          if (fallbackPollCounter != null) fallbackPollCounter.increment();
                                      }
                                      boolean catchUp = catchUpBeforeTheBus.getAndSet(true);
                                      if (state == CdcAvailability.State.ACTIVE) {
                                          log.debug("[{}] Adaptive live source switching to CDC bus ({}, catchUp={})",
                                                    aggregateType, tracker, catchUp);
                                          return busLeg(aggregateType,
                                                        subscriberIdForLog,
                                                        tracker,
                                                        catchUp,
                                                        pageSize,
                                                        gapHandler,
                                                        cdcDeliveryScheduler);
                                      }

                                      long resumeFrom = tracker.resumeFromInclusive();
                                      log.debug("[{}] Adaptive live source switching to polling (resumeFrom={}, state={})",
                                                aggregateType, resumeFrom, state);
                                      // All tenants: see "Tenants" in the javadoc
                                      return eventStore.pollEvents(aggregateType,
                                                                   resumeFrom,
                                                                   Optional.of(pageSize),
                                                                   pollingInterval,
                                                                   Optional.empty(),
                                                                   subscriptionId,
                                                                   eventStorePollingOptimizerFactory);
                                  })
                                  // Drop what was delivered already. Protects against:
                                  //  - events already delivered via the previous source showing up in the new one
                                  //    (CDC bus may still have buffered events after a cut-over)
                                  //  - the catch-up and the hand-over buffer both holding an event
                                  //  - polling reading the gaps again from right after the watermark
                                  // ... and lets through an event that fills a gap, whichever source it comes from.
                                  // BackfillThenLiveOrdered records delivery itself, as it emits - here it only drops
                                  // what it already delivered
                                  .filter(forBackfillThenLiveOrdered
                                          ? event -> !tracker.isDelivered(event.globalEventOrder().longValue())
                                          : deliveryGate::deliver)
                                  // Tenant gate (see eventBelongsToTenant), after the tracker. A no-op when this source
                                  // feeds the BackfillThenLiveOrdered drain: pollEvents passes Optional.empty() then and
                                  // filters the ordered OUTPUT instead, as its tracker has to see every global order.
                                  .filter(e -> eventBelongsToTenant(e, onlyIncludeEventIfItBelongsToTenant)),
                          Scheduler::dispose,
                          // Dispose only after the terminal signal was delivered - it is delivered on the scheduler's own thread
                          false);
    }

    /**
     * The bus leg of {@link #buildAdaptiveLiveSource}: attach to the bus, catch up from the event after the highest one
     * delivered to a head read after the attach - and the gaps the tracker still waits for - then deliver from the bus;
     * and after an overflow of the hand-over buffer, or a failed catch-up, do it again from where it got to. See
     * "Gap-free move onto the bus" and "Backpressure" there.
     *
     * @param catchUpOnFirstAttach false only when the caller covers the backlog of the first attach (see
     *                             {@code forBackfillThenLiveOrdered}); every retry catches up
     */
    private Flux<PersistedEvent> busLeg(AggregateType aggregateType,
                                        String subscriberIdForLog,
                                        CdcDeliveryTracker tracker,
                                        boolean catchUpOnFirstAttach,
                                        int pageSize,
                                        Optional<SubscriptionGapHandler> gapHandler,
                                        Scheduler cdcDeliveryScheduler) {
        var catchUpOnAttach        = new AtomicBoolean(catchUpOnFirstAttach);
        var recoveringFromOverflow = new AtomicBoolean(false);
        return Flux.defer(() -> {
                       boolean catchUp    = catchUpOnAttach.getAndSet(true);
                       long    resumeFrom = tracker.highestDeliveredExclusive();
                       // 1. Attach first: from here on, everything the bus publishes is in the hand-over buffer. Unsafe
                       // (unserialized) sink: BusHandOver is its only producer, and it runs serially on the bus's thread
                       Sinks.Many<PersistedEvent> handOver = Sinks.unsafe()
                                                                  .many()
                                                                  .unicast()
                                                                  .onBackpressureBuffer(new ArrayBlockingQueue<>(pageSize));
                       var busHandOver = new BusHandOver(handOver, tracker, pageSize, liveEventsCounter);
                       cdcBus.fluxForAggregate(aggregateType).subscribe(busHandOver);
                       // Off the shared dispatcher thread - see "Delivery thread". Fuses with the hand-over buffer, the only queue on the leg
                       Flux<PersistedEvent> fromTheBus = handOver.asFlux().publishOn(cdcDeliveryScheduler, pageSize);
                       if (!catchUp) {
                           return fromTheBus.doFinally(signal -> busHandOver.dispose());
                       }
                       // 2. + 3. The head is read after the attach - by the catch-up's first page load, on the delivery
                       // thread - and everything after the highest order delivered up to it is loaded from the event
                       // store, plus the gaps below it the tracker still waits for: one filled while the subscription
                       // was off the bus is only in the database. All tenants - see "Tenants" on buildAdaptiveLiveSource
                       LongSupplier headAfterAttach = () -> unitOfWorkFactory.withUnitOfWork(() -> eventStore.findHighestGlobalEventOrderPersisted(aggregateType))
                                                                              .map(GlobalEventOrder::longValue)
                                                                              .orElse(resumeFrom - 1);
                       Flux<PersistedEvent> catchingUp = backfillFlux(aggregateType,
                                                                      GlobalEventOrder.of(resumeFrom),
                                                                      headAfterAttach,
                                                                      pageSize,
                                                                      Optional.empty(),
                                                                      gapHandler,
                                                                      () -> tracker.awaitedGaps(MAX_AWAITED_GAPS_TO_REQUERY),
                                                                      cdcDeliveryScheduler)
                               .doOnComplete(() -> {
                                   if (recoveringFromOverflow.compareAndSet(true, false)) {
                                       log.info("[{}-{}] Caught up after falling behind the CDC bus - back on the bus after global order {}",
                                                subscriberIdForLog, aggregateType, tracker.highestDeliveredExclusive() - 1);
                                   } else {
                                       log.debug("[{}-{}] Caught up from global order {} to {} - continuing from the CDC bus",
                                                 subscriberIdForLog, aggregateType, resumeFrom, tracker.highestDeliveredExclusive() - 1);
                                   }
                               });
                       // 4. Then the bus, starting with what it delivered meanwhile
                       return Flux.concat(catchingUp, fromTheBus)
                                  .doFinally(signal -> busHandOver.dispose());
                   })
                   // After publishOn, so the tracker holds every event handed downstream - see "Backpressure"
                   .retryWhen(Retry.from(retrySignals -> retrySignals.concatMap(retrySignal -> {
                       Throwable failure    = retrySignal.failure();
                       long      resumeFrom = tracker.highestDeliveredExclusive();
                       if (Exceptions.isOverflow(failure)) {
                           recoveringFromOverflow.set(true);
                           log.warn("[{}-{}] Subscription fell more than {} events behind the CDC bus - catching up from global order {} and rejoining the bus",
                                    subscriberIdForLog, aggregateType, pageSize, resumeFrom);
                           if (liveSourceOverflowCounter != null) liveSourceOverflowCounter.increment();
                           return Mono.just(retrySignal);
                       }
                       // Without the stack trace: the backfill that failed has logged it
                       log.warn("[{}-{}] Catching up with the CDC bus from global order {} failed - retrying in {}: {}",
                                subscriberIdForLog, aggregateType, resumeFrom, CATCH_UP_RETRY_DELAY, failure.toString());
                       return Mono.delay(CATCH_UP_RETRY_DELAY, cdcDeliveryScheduler).thenReturn(retrySignal);
                   })));
    }

    /**
     * Takes a subscription's events off the shared {@link CdcEventBus} sink without ever back-pressuring it: requests
     * unbounded demand and offers each event to the subscription's own bounded hand-over buffer. The first event that
     * does not fit cancels the bus subscription at once and terminates the buffer with an overflow error, which the
     * buffer delivers only after everything it holds (see "Backpressure" on {@link #buildAdaptiveLiveSource}).
     */
    private static final class BusHandOver extends BaseSubscriber<PersistedEvent> {
        private final Sinks.Many<PersistedEvent> handOver;
        private final CdcDeliveryTracker         tracker;
        private final int                        capacity;
        /** May be null when no {@link MeterRegistry} is configured */
        private final Counter                    liveEventsCounter;

        private BusHandOver(Sinks.Many<PersistedEvent> handOver, CdcDeliveryTracker tracker, int capacity, Counter liveEventsCounter) {
            this.handOver = handOver;
            this.tracker = tracker;
            this.capacity = capacity;
            this.liveEventsCounter = liveEventsCounter;
        }

        @Override
        protected void hookOnSubscribe(Subscription subscription) {
            requestUnbounded();
        }

        @Override
        protected void hookOnNext(PersistedEvent event) {
            if (liveEventsCounter != null) liveEventsCounter.increment();
            // Can never be delivered again, so it need not take up room. Also keeps the events a fresh bus sink retained
            // before its first subscriber from overflowing the buffer on attach. Lock-free: this is the shared bus thread
            if (tracker.isAtOrBelowWatermarkAndNotAwaited(event.globalEventOrder().longValue())) {
                return;
            }
            if (handOver.tryEmitNext(event).isFailure()) {
                cancel();
                handOver.tryEmitError(Exceptions.failWithOverflow("The CDC bus hand-over buffer of " + capacity + " events is full"));
            }
        }

        @Override
        protected void hookOnError(Throwable throwable) {
            handOver.tryEmitError(throwable);
        }

        @Override
        protected void hookOnComplete() {
            handOver.tryEmitComplete();
        }
    }

    /**
     * Hands an event downstream only if the subscription's {@link CdcDeliveryTracker} has not seen it - recording it there
     * - and records with the subscriber's gap handler a gap it opens or fills.
     * <p>
     * Why the gap handler: a subscriber's resume point advances past a gap (gap-filled events are delivered late, so it
     * only ever moves forward), which is safe only while the gap is recorded durably, as a transient gap the next
     * subscription of that subscriber re-queries and its tracker waits for (see {@code newDeliveryTracker}). The polling
     * path and every back-fill page record the gaps in what they load, but an event from the bus is not loaded: without
     * this, a gap the bus revealed was lost at a restart - or a fenced-lock hand-over - before its event arrived. So an
     * event that opens a gap records it, synchronously and before the event is handed to the subscriber, and one that
     * fills a gap resolves it (else the gap handler would later promote a gap whose event exists to permanent). This
     * reconciles as a back-fill page over {@code [gap .. event]} holding just the event would, in a unit of work of its
     * own, and is reported to the observer like every other gap reconciliation. Only when there is a subscriber id and a
     * gap handler that records gaps, and only for events that open or fill a gap - an in-order event costs nothing. A
     * failure is logged and the event delivered regardless: it only weakens the restart guarantee for that gap.
     * <p>
     * Runs on the delivering thread - the subscription's {@code Cdc-*}, {@code Publish-*} or back-fill thread, never the
     * bus's.
     */
    private final class DeliveryGate implements DeliveryRecorder {
        private final CdcDeliveryTracker               tracker;
        private final AggregateType                    aggregateType;
        private final Optional<SubscriptionGapHandler> gapHandler;

        private DeliveryGate(CdcDeliveryTracker tracker, AggregateType aggregateType, Optional<SubscriptionGapHandler> gapHandler) {
            this.tracker = tracker;
            this.aggregateType = aggregateType;
            this.gapHandler = gapHandler.filter(handler -> recordsGaps());
        }

        @Override
        public boolean deliver(PersistedEvent event) {
            long globalOrder = event.globalEventOrder().longValue();
            var  delivery    = tracker.markDelivered(globalOrder);
            switch (delivery.kind()) {
                case OPENED_GAP -> recordWithGapHandler(event, LongRange.between(delivery.gapFromInclusive(), globalOrder), false);
                case FILLED_GAP -> recordWithGapHandler(event, LongRange.only(globalOrder), true);
                default -> {
                }
            }
            return delivery.isNew();
        }

        @Override
        public boolean isDelivered(PersistedEvent event) {
            return tracker.isDelivered(event.globalEventOrder().longValue());
        }

        private void recordWithGapHandler(PersistedEvent event, LongRange range, boolean filledGap) {
            if (gapHandler.isEmpty()) return;
            var handler = gapHandler.get();
            try {
                var reconciliation = unitOfWorkFactory.withUnitOfWork(uow -> {
                    var transientGaps = new ArrayList<>(handler.findTransientGapsToIncludeInQuery(aggregateType, range));
                    if (filledGap && !transientGaps.contains(event.globalEventOrder())) {
                        // The bus "returned" the gap's event: resolving it is what a query including the gap would do
                        transientGaps.add(event.globalEventOrder());
                    }
                    return handler.reconcileGapsAndReport(aggregateType, range, List.of(event), transientGaps);
                });
                if (!reconciliation.isEmpty()) {
                    eventStore.getEventStoreSubscriptionObserver().gapReconciliationOutcome(handler.subscriberId(), aggregateType, reconciliation);
                }
            } catch (RuntimeException e) {
                log.warn("[{}-{}] Could not {} the gap {} with the gap handler - delivering global order {} regardless; a restart before the gap is filled will not wait for it",
                         handler.subscriberId(), aggregateType, filledGap ? "resolve" : "record", range, event.globalEventOrder(), e);
            }
        }
    }

    /**
     * Tenant predicate mirroring the base store's SQL "({tenantColumn} IS NULL OR {tenantColumn} =
     * :tenant)": a tenant-less event belongs to every tenant (absent event-tenant ⇒ kept), and an absent
     * subscriber tenant filter keeps everything.
     */
    private static boolean eventBelongsToTenant(PersistedEvent e, Optional<Tenant> onlyIncludeEventIfItBelongsToTenant) {
        return onlyIncludeEventIfItBelongsToTenant
                .map(t -> e.tenant()
                           .map(tt -> tt.toString().equals(t.toString()))
                           .orElse(true))
                .orElse(true);
    }

    /**
     * Apply the subscriber tenant filter to a stream. Used on the ordered output of the CDC ACTIVE path:
     * filtering must happen AFTER ordering, because the delivery tracker behind BackfillThenLiveOrdered has to see
     * every global order - an event removed upstream of it would be a gap it records and waits for.
     */
    private static Flux<PersistedEvent> filterByTenant(Flux<PersistedEvent> source, Optional<Tenant> onlyIncludeEventIfItBelongsToTenant) {
        if (onlyIncludeEventIfItBelongsToTenant.isEmpty()) {
            return source;
        }
        return source.filter(e -> eventBelongsToTenant(e, onlyIncludeEventIfItBelongsToTenant));
    }

    /**
     * Backfill on a thread of its own ({@code CDC-Backfill-<aggregateType>}), disposed once the backfill terminates or
     * is cancelled - it used to be disposed only on cancel, so every backfill that completed left an idle thread behind.
     */
    private Flux<PersistedEvent> backfillFlux(
            AggregateType aggregateType,
            GlobalEventOrder fromInclusive,
            LongSupplier headInclusive,
            int pageSize,
            Optional<Tenant> tenant,
            Optional<SubscriptionGapHandler> gapHandler,
            Supplier<List<GlobalEventOrder>> alsoLoadOnFirstPage
                                             ) {
        return Flux.using(() -> Schedulers.newSingle("CDC-Backfill-" + aggregateType, true),
                          scheduler -> backfillFlux(aggregateType, fromInclusive, headInclusive, pageSize, tenant, gapHandler, alsoLoadOnFirstPage, scheduler),
                          Scheduler::dispose,
                          // Dispose only after the terminal signal was delivered - it is delivered on the scheduler's own thread
                          false);
    }

    /**
     * Load {@code [fromInclusive .. head]} page by page, only as far as the downstream has asked, on a worker of the
     * given scheduler - which also delivers the events. Completes once past the head. Cancelling it from another thread
     * interrupts a page load, or a handler running on that worker, as disposing a polling scheduler does.
     * <p>
     * A page may load more than was asked for - the transient gaps the gap handler includes, the orders asked for on the
     * first page - so what is loaded is held on the worker and handed out only as the downstream asks.
     *
     * @param headInclusive       read once, by the first page load on the scheduler - never on the subscribing thread.
     *                            Callers rely on it being read only after they attached their live source; it may be
     *                            memoized
     * @param alsoLoadOnFirstPage global orders below {@code fromInclusive} to load by order along with the first page -
     *                            the gaps a subscription still waits for (see {@link CdcDeliveryTracker#awaitedGaps}).
     *                            Read on the worker. The first page is loaded for them even when there is nothing past
     *                            {@code fromInclusive} up to the head
     */
    private Flux<PersistedEvent> backfillFlux(
            AggregateType aggregateType,
            GlobalEventOrder fromInclusive,
            LongSupplier headInclusive,
            int pageSize,
            Optional<Tenant> tenant,
            Optional<SubscriptionGapHandler> gapHandler,
            Supplier<List<GlobalEventOrder>> alsoLoadOnFirstPage,
            Scheduler scheduler
                                             ) {
        return Flux.create(sink -> {
            var next = new AtomicLong(fromInclusive.longValue());
            // Page loads run one at a time on the worker, so plain holders suffice
            long                       noHead    = Long.MIN_VALUE;
            long[]                     head      = {noHead};
            boolean[]                  firstPage = {true};
            ArrayDeque<PersistedEvent> loaded    = new ArrayDeque<>();
            var                        worker    = scheduler.createWorker();

            sink.onRequest(demand -> worker.schedule(() -> {
                long remaining = demand;

                try {
                    if (head[0] == noHead) {
                        head[0] = headInclusive.getAsLong();
                    }
                    while (remaining > 0 && !sink.isCancelled()) {
                        if (!loaded.isEmpty()) {
                            sink.next(loaded.poll());
                            remaining--;
                            continue;
                        }
                        long                   start    = next.get();
                        List<GlobalEventOrder> alsoLoad = List.of();
                        if (firstPage[0]) {
                            firstPage[0] = false;
                            alsoLoad = alsoLoadOnFirstPage.get();
                        }
                        if (start > head[0] && alsoLoad.isEmpty()) {
                            sink.complete();
                            return;
                        }

                        long batch = Math.min(pageSize, remaining);

                        BackfillResult result =
                                backfillOnePageAndEmit(
                                        aggregateType,
                                        start,
                                        head[0],
                                        batch,
                                        tenant,
                                        gapHandler,
                                        alsoLoad,
                                        loaded::add
                                                      );
                        log.debug("[{}] Backfill result: next='{}', emitted='{}'", aggregateType, result.next(), result.emitted());

                        // Never backwards: a page whose range is empty (past the head) only loaded what it asked for by order
                        next.set(Math.max(result.next(), start));

                        // if we scanned but emitted nothing, we must still progress
                        if (result.emitted() == 0 && next.get() == start) {
                            next.incrementAndGet();
                        }
                    }
                } catch (Throwable t) {
                    log.warn("[{}] Backfill failed", aggregateType, t);
                    sink.error(t);
                }
            }));

            // On termination too: the worker of a shared scheduler must not keep pending page loads around
            sink.onDispose(worker);
        }, FluxSink.OverflowStrategy.ERROR);
    }

    /**
     * Load one page {@code [fromInclusive .. min(head, fromInclusive + pageSize - 1)]}, plus the transient gaps the gap
     * handler includes and {@code alsoLoad}, by order, and reconcile the gaps in it with the gap handler.
     *
     * @return where the next page starts - right after the highest order loaded <b>within the range</b> (a transient gap
     * or an order in {@code alsoLoad} lies below it, and must not move the next page back), or after the range when it
     * held nothing - and how many events were emitted
     */
    private BackfillResult backfillOnePageAndEmit(
            AggregateType aggregateType,
            long fromInclusive,
            long headInclusive,
            long pageSize,
            Optional<Tenant> tenant,
            Optional<SubscriptionGapHandler> gapHandler,
            List<GlobalEventOrder> alsoLoad,
            Consumer<PersistedEvent> emit
                                                 ) {
        long toInclusive = Math.min(headInclusive, fromInclusive + pageSize - 1);
        var  range       = LongRange.between(fromInclusive, toInclusive);
        long startNs     = System.nanoTime();

        var gapReconciliation = new AtomicReference<>(GapReconciliation.NONE);
        List<PersistedEvent> loaded =
                unitOfWorkFactory.withUnitOfWork(uow -> {
                    List<GlobalEventOrder> transientGaps =
                            gapHandler.map(h -> h.findTransientGapsToIncludeInQuery(aggregateType, range))
                                      .orElse(List.of());
                    List<GlobalEventOrder> loadByOrder = transientGaps;
                    if (!alsoLoad.isEmpty()) {
                        var union = new LinkedHashSet<>(transientGaps);
                        union.addAll(alsoLoad);
                        loadByOrder = List.copyOf(union);
                    }

                    var events =
                            eventStore.loadEventsByGlobalOrder(
                                    aggregateType,
                                    range,
                                    loadByOrder,
                                    tenant.orElse(null)
                                                              ).toList();

                    // Reconciled as the query the gap handler knows about - the range and its own transient gaps - would be
                    var eventsForGapHandler = alsoLoad.isEmpty()
                                              ? events
                                              : events.stream()
                                                      .filter(event -> range.covers(event.globalEventOrder().longValue()) || transientGaps.contains(event.globalEventOrder()))
                                                      .toList();
                    gapHandler.ifPresent(h -> gapReconciliation.set(h.reconcileGapsAndReport(aggregateType, range, eventsForGapHandler, transientGaps)));
                    return events;
                });
        // Reported after the unit of work commits, as the polling path does. Backfill is invisible to the polling
        // statistics, so without this the gaps a CDC subscription finds while catching up would be counted nowhere.
        if (gapHandler.isPresent() && !gapReconciliation.get().isEmpty()) {
            eventStore.getEventStoreSubscriptionObserver()
                      .gapReconciliationOutcome(gapHandler.get().subscriberId(), aggregateType, gapReconciliation.get());
        }
        if (backfillPageTimer != null) backfillPageTimer.record(System.nanoTime() - startNs, TimeUnit.NANOSECONDS);
        if (backfillLoadedSummary != null) backfillLoadedSummary.record(loaded.size());
        if (backfillQueryRangeSummary != null) backfillQueryRangeSummary.record(Math.max(0, toInclusive - fromInclusive + 1));
        log.debug("[{}] Backfill loaded '{}' events", aggregateType, loaded.size());

        loaded.forEach(emit);

        long highestInRange = loaded.stream()
                                    .mapToLong(event -> event.globalEventOrder().longValue())
                                    .filter(range::covers)
                                    .max()
                                    .orElse(toInclusive);
        return new BackfillResult(highestInRange + 1, loaded.size());
    }

    @Override
    public Optional<GlobalEventOrder> findHighestGlobalEventOrderPersisted(AggregateType aggregateType) {
        return eventStore.findHighestGlobalEventOrderPersisted(aggregateType);
    }

    @Override
    public Optional<GlobalEventOrder> findLowestGlobalEventOrderPersisted(AggregateType aggregateType) {
        return eventStore.findLowestGlobalEventOrderPersisted(aggregateType);
    }

    @Override
    public EventStoreUnitOfWorkFactory<EventStoreUnitOfWork> getUnitOfWorkFactory() {
        return eventStore.getUnitOfWorkFactory();
    }

    /**
     * The {@link EventStore} this instance decorates.
     * <p>
     * {@link CdcEventStore} only implements {@link EventStore}, not the wider
     * {@link dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.ConfigurableEventStore}
     * that the delegate normally implements, so callers needing the configuration side of the delegate
     * (aggregate event stream configurations, in-memory projectors, interceptor registration) have to reach it
     * through here rather than casting the decorator.
     *
     * @return the decorated {@link EventStore}
     */
    public EventStore getDelegate() {
        return eventStore;
    }

    @Override
    public EventBus localEventBus() {
        return eventStore.localEventBus();
    }

    @Override
    public EventStoreSubscriptionObserver getEventStoreSubscriptionObserver() {
        return eventStore.getEventStoreSubscriptionObserver();
    }

    @Override
    public List<EventStoreInterceptor> getEventStoreInterceptors() {
        return eventStore.getEventStoreInterceptors();
    }

    @Override
    public <ID> AggregateEventStream<ID> appendToStream(AppendToStream<ID> operation) {
        return eventStore.appendToStream(operation);
    }

    @Override
    public <ID> Optional<PersistedEvent> loadLastPersistedEventRelatedTo(LoadLastPersistedEventRelatedTo<ID> operation) {
        return eventStore.loadLastPersistedEventRelatedTo(operation);
    }

    @Override
    public Optional<PersistedEvent> loadEvent(LoadEvent operation) {
        return eventStore.loadEvent(operation);
    }

    @Override
    public List<PersistedEvent> loadEvents(LoadEvents operation) {
        return eventStore.loadEvents(operation);
    }

    @Override
    public <ID> Optional<AggregateEventStream<ID>> fetchStream(FetchStream<ID> operation) {
        return eventStore.fetchStream(operation);
    }

    @Override
    public <ID, PROJECTION> Optional<PROJECTION> inMemoryProjection(AggregateType aggregateType, ID aggregateId, Class<PROJECTION> projectionType) {
        return eventStore.inMemoryProjection(aggregateType, aggregateId, projectionType);
    }

    @Override
    public <ID, PROJECTION> Optional<PROJECTION> inMemoryProjection(AggregateType aggregateType, ID aggregateId, Class<PROJECTION> projectionType, InMemoryProjector inMemoryProjector) {
        return eventStore.inMemoryProjection(aggregateType, aggregateId, projectionType, inMemoryProjector);
    }

    @Override
    public Stream<PersistedEvent> loadEventsByGlobalOrder(LoadEventsByGlobalOrder operation) {
        return eventStore.loadEventsByGlobalOrder(operation);
    }

    @Override
    public Flux<PersistedEvent> unboundedPollForEvents(AggregateType aggregateType, long fromInclusiveGlobalOrder, Optional<Integer> loadEventsByGlobalOrderBatchSize, Optional<Duration> pollingInterval, Optional<Tenant> onlyIncludeEventIfItBelongsToTenant, Optional<SubscriberId> subscriptionId) {
        return eventStore.unboundedPollForEvents(aggregateType, fromInclusiveGlobalOrder, loadEventsByGlobalOrderBatchSize, pollingInterval, onlyIncludeEventIfItBelongsToTenant, subscriptionId);
    }

    // ------------------------------------------------------------------------------------------------------------
    // ConfigurableEventStore — configuration lives on the wrapped store; the mutators return this decorator so a
    // caller that configures through it does not silently end up holding the undecorated store.
    // ------------------------------------------------------------------------------------------------------------

    @Override
    public ConfigurableEventStore<CONFIG> addAggregateEventStreamConfiguration(CONFIG eventStreamConfiguration) {
        eventStore.addAggregateEventStreamConfiguration(eventStreamConfiguration);
        return this;
    }

    @Override
    public ConfigurableEventStore<CONFIG> addAggregateEventStreamConfiguration(AggregateType aggregateType,
                                                                               AggregateIdSerializer aggregateIdSerializer) {
        eventStore.addAggregateEventStreamConfiguration(aggregateType, aggregateIdSerializer);
        return this;
    }

    @Override
    public ConfigurableEventStore<CONFIG> addAggregateEventStreamConfiguration(AggregateType aggregateType,
                                                                               Class<?> aggregateIdType) {
        eventStore.addAggregateEventStreamConfiguration(aggregateType, aggregateIdType);
        return this;
    }

    @Override
    public CONFIG getAggregateEventStreamConfiguration(AggregateType aggregateType) {
        return eventStore.getAggregateEventStreamConfiguration(aggregateType);
    }

    @Override
    public Optional<CONFIG> findAggregateEventStreamConfiguration(AggregateType aggregateType) {
        return eventStore.findAggregateEventStreamConfiguration(aggregateType);
    }

    @Override
    public ConfigurableEventStore<CONFIG> addGenericInMemoryProjector(InMemoryProjector inMemoryProjector) {
        eventStore.addGenericInMemoryProjector(inMemoryProjector);
        return this;
    }

    @Override
    public ConfigurableEventStore<CONFIG> removeGenericInMemoryProjector(InMemoryProjector inMemoryProjector) {
        eventStore.removeGenericInMemoryProjector(inMemoryProjector);
        return this;
    }

    @Override
    public ConfigurableEventStore<CONFIG> addSpecificInMemoryProjector(Class<?> projectionType, InMemoryProjector inMemoryProjector) {
        eventStore.addSpecificInMemoryProjector(projectionType, inMemoryProjector);
        return this;
    }

    @Override
    public ConfigurableEventStore<CONFIG> removeSpecificInMemoryProjector(Class<?> projectionType) {
        eventStore.removeSpecificInMemoryProjector(projectionType);
        return this;
    }

    @Override
    public ConfigurableEventStore<CONFIG> addEventStoreInterceptor(EventStoreInterceptor eventStoreInterceptor) {
        eventStore.addEventStoreInterceptor(eventStoreInterceptor);
        return this;
    }

    @Override
    public ConfigurableEventStore<CONFIG> removeEventStoreInterceptor(EventStoreInterceptor eventStoreInterceptor) {
        eventStore.removeEventStoreInterceptor(eventStoreInterceptor);
        return this;
    }

    record BackfillResult(long next, long emitted) {
    }

    /**
     * Records which events a subscription handed downstream - {@link DeliveryGate} in production, over the subscription's
     * {@link CdcDeliveryTracker}
     */
    interface DeliveryRecorder {
        /**
         * @return true when the event may be handed downstream, which it is then recorded as; false when it was before
         */
        boolean deliver(PersistedEvent event);

        /**
         * @return whether the event was handed downstream before (or given up on), without recording anything
         */
        boolean isDelivered(PersistedEvent event);

        /**
         * Only the tracker - no gap handler
         */
        static DeliveryRecorder tracking(CdcDeliveryTracker tracker) {
            requireNonNull(tracker, "No tracker provided");
            return new DeliveryRecorder() {
                @Override
                public boolean deliver(PersistedEvent event) {
                    return tracker.markDelivered(event.globalEventOrder().longValue()).isNew();
                }

                @Override
                public boolean isDelivered(PersistedEvent event) {
                    return tracker.isDelivered(event.globalEventOrder().longValue());
                }
            };
        }
    }

    /**
     * ⚠️ CRITICAL ORDERING COMPONENT
     * <p>
     * Hands a subscription started while CDC is ACTIVE everything up to the head first, then its live tail:
     * <ul>
     *     <li>every back-filled event (polling, up to a head read after the live source was attached - see
     *     {@code headSnapshot} on {@link #ordered}) is handed on before any live event;</li>
     *     <li>the live events that arrived while the backfill ran are held and handed on in global order once it is
     *     done;</li>
     *     <li>after that, live events are handed on as the live source delivers them - in commit order, the order the CDC
     *     bus delivers them in, exactly as on every other CDC path ({@code buildAdaptiveLiveSource}).</li>
     * </ul>
     * Do NOT simplify buffering, gating, or drain logic.
     * <p>
     * Why not in global order past the head: global order has holes that never fill - an {@code IDENTITY} value a
     * rolled-back transaction took writes no WAL, so it never reaches the bus - and a hole cannot be told from a
     * transaction that took a lower order and has not committed yet. The drain used to advance strictly by one past the
     * head, so it parked on every such hole, holding back every later event, until {@code eventBus.liveDrainStallThreshold}
     * (three minutes by default) raised a {@link CdcLiveDrainStalledException} and re-subscribed the subscription through
     * its backfill. Rollbacks are routine - an optimistic concurrency conflict is one - so that stalled such subscriptions
     * for minutes at a time. It had to be strict while dedup was a high-water mark, as moving past a hole then lost the
     * event that filled it for good. The {@link DeliveryRecorder} is gap-aware instead: the event that fills a gap is
     * delivered when it arrives - after higher ones, out of global order, as the polling path delivers a gap-filled event
     * (a subscriber's resume point only ever advances for that reason) - and in production it records each gap with the
     * subscriber's gap handler before the event that opens it is handed on, so the gap survives a restart. Nothing waits
     * for a hole any more, so the stall threshold no longer plays a part. Ordering within one aggregate is unaffected:
     * an aggregate's next event is appended by a transaction that read the previous one committed, so it commits - and
     * reaches the bus - after it, and takes a higher global order too.
     * <p>
     * Demand: it passes its subscriber's backpressure on to the live source - live events held, queued for the
     * subscriber and asked for never exceed {@code eventBus.backpressureBufferSize}, and one more is asked for only as
     * the subscriber takes one (see "Demand" on {@code liveSub}). A subscriber that stops asking (a busy batch handler,
     * a handler in a retry backoff on another thread) therefore holds the live source back, whose bus leg then catches
     * up on its own (see {@code buildAdaptiveLiveSource}), instead of overflowing the ordered hand-over queue and ending
     * the subscription's flux with a {@link CdcBusOverflowException}.
     * <p>
     * Dedup and late commits: every event it hands downstream - back-filled or live - is recorded with the
     * subscription's {@link DeliveryRecorder}, and one recorded before is dropped. An event at or below the head can
     * only come from the live source when it was not visible to the backfill - a transaction holding a lower global
     * order committed after the backfill read past it - and it is then delivered once the backfill is done, late and out
     * of global order, rather than dropped as "already back-filled". Its live source drops only what the recorder already
     * holds.
     */
    static final class BackfillThenLiveOrdered {
        private static final Logger LOG = LoggerFactory.getLogger(BackfillThenLiveOrdered.class);

        private final Timer                               backfillToLiveTransitionTimer;
        private final CdcProperties.CdcEventBusProperties eventBusProperties;
        /** Observable gauge backing {@code essentials.cdc.backfill_live.buffer.size}. May be null in tests. */
        private final AtomicInteger                       bufferSizeGauge;
        private final DeliveryRecorder                    deliveries;

        private BackfillThenLiveOrdered(Timer backfillToLiveTransitionTimer,
                                        CdcProperties.CdcEventBusProperties eventBusProperties,
                                        AtomicInteger bufferSizeGauge,
                                        DeliveryRecorder deliveries) {
            this.backfillToLiveTransitionTimer = backfillToLiveTransitionTimer;
            this.eventBusProperties = requireNonNull(eventBusProperties, "eventBusProperties");
            this.bufferSizeGauge = bufferSizeGauge;
            this.deliveries = requireNonNull(deliveries, "deliveries");
        }

        static Flux<PersistedEvent> orderedWithoutMetrics(Flux<PersistedEvent> backfill,
                                                          Flux<PersistedEvent> live,
                                                          long headInclusive,
                                                          CdcProperties.CdcEventBusProperties eventBusProperties) {
            return orderedWithoutMetrics(backfill, live, () -> headInclusive, eventBusProperties);
        }

        /** Test seam: drive {@link #ordered} with a deferred head supplier to assert read-after-attach ordering. */
        static Flux<PersistedEvent> orderedWithoutMetrics(Flux<PersistedEvent> backfill,
                                                          Flux<PersistedEvent> live,
                                                          LongSupplier headSnapshot,
                                                          CdcProperties.CdcEventBusProperties eventBusProperties) {
            return orderedWithoutMetrics(backfill,
                                         live,
                                         headSnapshot,
                                         eventBusProperties,
                                         DeliveryRecorder.tracking(CdcDeliveryTracker.startingAfter("BackfillThenLiveOrdered", 0)));
        }

        /** Test seam: as above, recording deliveries with the given recorder */
        static Flux<PersistedEvent> orderedWithoutMetrics(Flux<PersistedEvent> backfill,
                                                          Flux<PersistedEvent> live,
                                                          LongSupplier headSnapshot,
                                                          CdcProperties.CdcEventBusProperties eventBusProperties,
                                                          DeliveryRecorder deliveries) {
            return new BackfillThenLiveOrdered(null, eventBusProperties, null, deliveries).ordered(backfill, live, headSnapshot);
        }

        /**
         * @param headSnapshot supplies the backfill→live boundary — the highest global order persisted at
         *                     subscription start, which the backfill reads up to. Invoked exactly once, and deliberately
         *                     only AFTER the live source has been subscribed (i.e. the CDC bus is attached). This
         *                     ordering is the race fix: the bus is a hot multicast with no history replay for late
         *                     subscribers, so reading head before attaching would let an event published between the two
         *                     reach neither the backfill nor the live source. See {@code pollEvents}.
         */
        Flux<PersistedEvent> ordered(
                Flux<PersistedEvent> backfill,
                Flux<PersistedEvent> live,
                LongSupplier headSnapshot
                                    ) {
            requireNonNull(backfill, "backfill");
            requireNonNull(live, "live");
            requireNonNull(headSnapshot, "headSnapshot");

            int bufferSize              = eventBusProperties.getBackpressureBufferSize();
            int nonSerializedMaxRetries = eventBusProperties.getNonSerializedMaxRetries();
            int overflowMaxRetries      = eventBusProperties.getOverflowMaxRetries();
            // Dropping events in the pipeline would silently lose them. Honor retry counts from the bus config, but
            // always fail-fast on terminal overflow.
            CdcProperties.CdcOverflowPolicy effectivePolicy = CdcProperties.CdcOverflowPolicy.FAIL_FAST;

            return Flux.defer(() -> {
                // Holds live events by global order until the backfill is done, and hands them on in that order then;
                // after that an event passes straight through it. Bounded by the BaseSubscriber demand contract below:
                // outstanding-demand + buffer.size() <= bufferSize.
                NavigableMap<Long, PersistedEvent> buffer = new ConcurrentSkipListMap<>();

                AtomicBoolean backfillDone          = new AtomicBoolean(false);
                AtomicBoolean liveDone              = new AtomicBoolean(false);
                long          backfillToLiveStartNs = System.nanoTime();
                AtomicBoolean transitionRecorded    = new AtomicBoolean(false);
                // Serializes the drain - see drain below
                AtomicInteger drainsMissed          = new AtomicInteger();

                // Bounded queue, and it cannot overflow: liveSub only asks the live source for an event once the
                // downstream has taken one out of this sink (see "Demand" on liveSub below), so the live events held by
                // the buffer, this queue and liveSub's outstanding demand together never exceed bufferSize. Should it
                // overflow nonetheless, the shared CdcSinkEmitter backs off and then fails fast.
                Sinks.Many<PersistedEvent> orderedLiveSink = Sinks.many()
                                                                  .unicast()
                                                                  .onBackpressureBuffer(new ArrayBlockingQueue<>(bufferSize));

                // The drain runs before liveSub exists; it asks the live source for one more event for each one it drops
                AtomicReference<BaseSubscriber<PersistedEvent>> liveSubRef = new AtomicReference<>();
                Consumer<PersistedEvent> emitUnlessDelivered = ev -> {
                    if (deliveries.deliver(ev)) {
                        CdcSinkEmitter.tryEmit(orderedLiveSink,
                                               ev,
                                               nonSerializedMaxRetries,
                                               overflowMaxRetries,
                                               effectivePolicy,
                                               "BackfillThenLiveOrdered",
                                               LOG);
                    } else {
                        // Left the pipeline without reaching the downstream - compensate like any other duplicate
                        liveSubRef.get().request(1);
                    }
                };

                // Once the backfill is done: hands on everything held, lowest global order first - the live events that
                // arrived while the backfill ran, then each one as it arrives - and drops what was delivered already (by
                // the backfill, or an event handed over twice). An event at or below the head arrives only when it was
                // not visible to the backfill - it committed after the backfill read past it - and is delivered late
                // and out of global order, not lost. Nothing waits for a global order that has not arrived: see "Why not
                // in global order past the head" on the class.
                //
                // Called from the backfill's completion and from the live source's thread, possibly at once, and
                // re-entered when handing on an event makes a synchronous live source deliver the next one. Only one
                // caller drains at a time - the others count a missed drain, and the one draining goes round again for
                // it - so the events reach orderedLiveSink one at a time and in the order they leave the buffer.
                Runnable drain = () -> {
                    if (!backfillDone.get()) return;
                    if (drainsMissed.getAndIncrement() != 0) return;
                    int missed = 1;
                    while (true) {
                        Map.Entry<Long, PersistedEvent> next;
                        while ((next = buffer.pollFirstEntry()) != null) {
                            if (bufferSizeGauge != null) bufferSizeGauge.decrementAndGet();
                            emitUnlessDelivered.accept(next.getValue());
                        }
                        if (liveDone.get() && buffer.isEmpty()) {
                            orderedLiveSink.tryEmitComplete();
                        }
                        missed = drainsMissed.addAndGet(-missed);
                        if (missed == 0) return;
                    }
                };

                // Demand: liveSub passes the downstream's backpressure on to the live source. It requests bufferSize
                // up front and then one more event for each live event that leaves the pipeline - taken out of
                // orderedLiveSink by the downstream (see orderedLiveFlux below), or dropped here as already emitted -
                // so live events in `buffer`, in orderedLiveSink's queue and still outstanding never add up to more
                // than bufferSize. It used to refill demand as soon as drain() moved events into orderedLiveSink,
                // consumed or not: a subscriber that paused its demand (a batched subscriber's busy batch handler, a
                // handler in a retry backoff on another thread) let the live source fill that queue until it
                // overflowed, and the CdcBusOverflowException ended the subscription's flux - or, before the merge
                // below had subscribed the sink, the events were dropped as FAIL_ZERO_SUBSCRIBER.
                // While backfill runs nothing leaves, so the live source is held at bufferSize events: its bus leg then
                // fills its own bounded buffer and, once that overflows, catches up from where it got to (see
                // buildAdaptiveLiveSource). It never back-pressures the shared bus sink.
                BaseSubscriber<PersistedEvent> liveSub = new BaseSubscriber<PersistedEvent>() {
                    @Override
                    protected void hookOnSubscribe(Subscription subscription) {
                        // Deliberately request nothing here. We must attach to the live source (so the bus
                        // starts retaining events for us) BEFORE the head snapshot is taken. The initial
                        // bufferSize demand is released right after the head read, below. Until then the live
                        // source's bus leg holds events in its own bounded buffer, and moves to polling from the
                        // last event it delivered should that overflow — nothing is lost.
                    }

                    @Override
                    protected void hookOnNext(PersistedEvent ev) {
                        if (deliveries.isDelivered(ev)) {
                            // Duplicate / already-emitted (by the backfill or the drain) — does not occupy buffer,
                            // compensate immediately.
                            request(1);
                            return;
                        }

                        // buffer.put replaces on duplicate key; only count truly-new entries. A replaced one left the
                        // pipeline, so it is compensated like any other duplicate. An event at or below the head is held
                        // too: while the backfill runs it may still deliver it, so only the drain decides (see drain)
                        if (buffer.put(ev.globalEventOrder().longValue(), ev) == null) {
                            if (bufferSizeGauge != null) bufferSizeGauge.incrementAndGet();
                        } else {
                            request(1);
                        }
                        // No request for what this moves into orderedLiveSink: that is requested once the downstream
                        // has taken it out (see "Demand" above)
                        drain.run();
                    }

                    @Override
                    protected void hookOnError(Throwable err) {
                        orderedLiveSink.tryEmitError(err);
                    }

                    @Override
                    protected void hookOnComplete() {
                        liveDone.set(true);
                        drain.run();
                    }
                };
                liveSubRef.set(liveSub);
                live.subscribe(liveSub);

                // The live source (CDC bus) is now attached. Only now is it safe to snapshot head: any
                // event the bus delivers from here on is captured by liveSub, and any event published
                // before this attach is already persisted, hence ≤ head and covered by backfill. Reading
                // head before the attach would open a window in which an event reaches neither source.
                long head;
                try {
                    head = headSnapshot.getAsLong();
                } catch (RuntimeException headReadFailure) {
                    liveSub.dispose();
                    return Flux.error(headReadFailure);
                }
                LOG.trace("Back-filling up to head {} before handing on live events", head);
                // Release the initial demand now that the head is read (see hookOnSubscribe).
                liveSub.request(bufferSize);

                // Each back-filled event is recorded as it is handed on; one the live source already handed on - it
                // committed between the attach and the head read, and the drain moved it on - is dropped
                Flux<PersistedEvent> backfillWithGate =
                        backfill.filter(deliveries::deliver)
                                .doOnComplete(() -> {
                                    backfillDone.set(true);
                                    drain.run();
                                    if (backfillToLiveTransitionTimer != null && transitionRecorded.compareAndSet(false, true)) {
                                        backfillToLiveTransitionTimer.record(System.nanoTime() - backfillToLiveStartNs, java.util.concurrent.TimeUnit.NANOSECONDS);
                                    }
                                });

                // Each live event the downstream takes out of orderedLiveSink makes room for one more from the live
                // source (see "Demand" on liveSub)
                Flux<PersistedEvent> orderedLiveFlux = orderedLiveSink.asFlux()
                                                                      .doOnNext(ev -> liveSub.request(1));

                // Use merge (not concat) so the sink has a subscriber attached upfront. With a bounded sink queue,
                // concat would race: backfill.doOnComplete -> drain emits to sink -> queue fills before concat
                // subscribes to orderedLiveFlux -> next emit hits FAIL_OVERFLOW. Merge attaches B immediately, so
                // tryEmitNext flows to the subscriber in real time. Ordering is preserved because drain is gated
                // on backfillDone, so B emits nothing until after all backfill items have been delivered.
                return Flux.merge(backfillWithGate, orderedLiveFlux)
                           .doFinally(sig -> {
                               liveSub.dispose();
                               orderedLiveSink.tryEmitComplete();
                           });
            });
        }

    }

    public CdcEventBus getCdcBus() {
        return cdcBus;
    }
}
