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
import dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.internal.*;
import dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.observability.EventStoreSubscriptionObserver;
import dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.operations.*;
import dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.persistence.AggregateEventStreamConfiguration;
import dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.serializer.AggregateIdSerializer;
import dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.serializer.TenantSerializer;
import dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.transaction.*;
import dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.types.GlobalEventOrder;
import dk.trustworks.essentials.components.foundation.transaction.*;
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
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.*;
import java.util.stream.*;

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
public class CdcEventStore<CONFIG extends AggregateEventStreamConfiguration> implements ConfigurableEventStore<CONFIG>, AwaitedGapInspector, DeferredGapRecording {

    private static final Logger log = LoggerFactory.getLogger(CdcEventStore.class);

    /**
     * How long a subscription waits before retrying a catch-up onto the CDC bus that failed, typically because the
     * database was unreachable (see {@link #buildAdaptiveLiveSource})
     */
    private static final Duration CATCH_UP_RETRY_DELAY = Duration.ofSeconds(1);
    /**
     * Falling behind the bus under a burst is routine and heals itself in milliseconds; each occurrence is logged at
     * DEBUG, and at most once per this interval a subscription logs an INFO line counting them.
     */
    private static final Duration OVERFLOW_SUMMARY_INTERVAL = Duration.ofMinutes(1);

    /**
     * A catch-up or back-fill loads the events within every range a subscription still waits for (see
     * {@link CdcDeliveryTracker#awaitedRanges}): ranges at most this wide are listed order by order, at most this many
     * orders per statement ({@code IN} list); a wider one - the middle of a wide gap - is read as a range, one statement
     * each. So a statement per this many awaited orders, never one per order
     */
    static final int MAX_AWAITED_ORDERS_PER_STATEMENT = 1_000;

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
     * after an interruption are debounced, never the first activation after boot.
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
     * The middles of wide gaps the subscriptions' delivery trackers await in memory only, handed from a subscription that
     * ends to the next one of the same subscriber and aggregate type - see {@code newDeliveryTracker}
     */
    private final GapMiddlesAwaitedAcrossSubscribes                           gapMiddlesAwaitedAcrossSubscribes = new GapMiddlesAwaitedAcrossSubscribes();
    /**
     * The delivery trackers of the subscriptions running now, per subscriber and aggregate type - asked by
     * {@link #isAwaitedAsGap}, as nothing else knows about the middle of a wide gap a running subscription found itself.
     * Added when a subscription starts handing events on, removed when it ends ({@code DeliveryGate}); a set, so a new
     * subscribe of the subscriber that starts before the old one's end has run cannot hide either tracker
     */
    private final ConcurrentMap<SubscriberAndAggregateType, Set<CdcDeliveryTracker>> runningDeliveryTrackers = new ConcurrentHashMap<>();
    /**
     * The subscribers whose resume points are saved only after {@link #beforeResumePointSave} - see {@link DeferredGapRecording}
     */
    private final Set<SubscriberAndAggregateType>                                     gapRecordingDeferredFor   = ConcurrentHashMap.newKeySet();
    /**
     * Per subscriber, the resume point saves in progress - while one is, gates record the gaps they open at once
     */
    private final ConcurrentMap<SubscriberAndAggregateType, AtomicInteger>            resumePointSavesInProgress = new ConcurrentHashMap<>();
    /**
     * The delivery gates of the running subscriptions, whose deferred gaps {@link #beforeResumePointSave} records
     */
    private final ConcurrentMap<SubscriberAndAggregateType, Set<DeliveryGate>>         runningDeliveryGates      = new ConcurrentHashMap<>();
    /**
     * How long a thread with a unit of work current waits for a gate's deferred gap recording, or for the gate lock that
     * recording holds - {@code DeliveryGate.DEFERRED_GAPS_RECORDING_WAIT}, unless a test shortens it
     */
    private volatile Duration                                                         deferredGapsRecordingWait = DeliveryGate.DEFERRED_GAPS_RECORDING_WAIT;

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
                   .description("Deprecated, always 0: the BackfillThenLiveOrdered live-tail drain no longer waits for a missing global order, so it cannot stall on one. Kept for compatibility; planned for removal in the next major release")
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
        validateDeprecatedLiveDrainStallThreshold(eventBusProperties);
        warnIfDelegateIsNoAwaitedGapInspector(delegate);
    }

    /**
     * While CDC is not active the delegate serves the subscriptions, and only the delegate knows about the middles of
     * wide gaps its polls await in memory only: if it cannot be asked ({@link AwaitedGapInspector}), {@link #isAwaitedAsGap}
     * cannot tell about those, so {@code awaitProcessed} can return early - logged once, when this event store is built,
     * in the words of the processor's own start-time WARN
     */
    private static void warnIfDelegateIsNoAwaitedGapInspector(ConfigurableEventStore<?> delegate) {
        if (!(delegate instanceof AwaitedGapInspector)) {
            log.warn("If you call awaitProcessed on a processor over this CdcEventStore, it can report an event as processed before the processor handled it while CDC is not active: " +
                             "the event store '{}' that this CdcEventStore decorates does not implement {}, so awaitProcessed cannot tell whether an event a subscription moved past " +
                             "while it was uncommitted (a gap) is still to be handled. To fix it, have the EventStore that decorates the PostgresqlEventStore implement {} " +
                             "and forward to its delegate. An application that never calls awaitProcessed can ignore this",
                     delegate.getClass().getName(), AwaitedGapInspector.class.getSimpleName(), AwaitedGapInspector.class.getSimpleName());
        }
    }

    /**
     * The threshold is no longer read (see {@link CdcProperties.CdcEventBusProperties#getLiveDrainStallThreshold()}),
     * but a negative value is still rejected as before, so a configuration that used to fail at startup still does
     */
    @SuppressWarnings("removal")
    private static void validateDeprecatedLiveDrainStallThreshold(CdcProperties.CdcEventBusProperties eventBusProperties) {
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
        return pollEvents(aggregateType,
                          fromInclusiveGlobalOrder,
                          loadEventsByGlobalOrderBatchSize,
                          pollingInterval,
                          onlyIncludeEventIfItBelongsToTenant,
                          subscriptionId,
                          eventStorePollingOptimizerFactory,
                          Optional.empty());
    }

    /**
     * Honours the acknowledgement: a gap fill's transient gap is resolved when the subscriber acknowledges the event, in
     * its unit of work, rather than once the event was handed on - on every path: the bus leg and its catch-ups, the
     * polling leg, and {@link BackfillThenLiveOrdered}. See {@link DeliveryGate}.
     */
    @Override
    public Flux<PersistedEvent> pollEvents(AggregateType aggregateType,
                                           long fromInclusiveGlobalOrder,
                                           Optional<Integer> loadEventsByGlobalOrderBatchSize,
                                           Optional<Duration> pollingInterval,
                                           Optional<Tenant> onlyIncludeEventIfItBelongsToTenant,
                                           Optional<SubscriberId> subscriptionId,
                                           Optional<Function<String, EventStorePollingOptimizer>> eventStorePollingOptimizerFactory,
                                           SubscriberAcknowledgement acknowledgement) {
        return pollEvents(aggregateType,
                          fromInclusiveGlobalOrder,
                          loadEventsByGlobalOrderBatchSize,
                          pollingInterval,
                          onlyIncludeEventIfItBelongsToTenant,
                          subscriptionId,
                          eventStorePollingOptimizerFactory,
                          Optional.of(requireNonNull(acknowledgement, "No acknowledgement provided")));
    }

    private Flux<PersistedEvent> pollEvents(AggregateType aggregateType,
                                            long fromInclusiveGlobalOrder,
                                            Optional<Integer> loadEventsByGlobalOrderBatchSize,
                                            Optional<Duration> pollingInterval,
                                            Optional<Tenant> onlyIncludeEventIfItBelongsToTenant,
                                            Optional<SubscriberId> subscriptionId,
                                            Optional<Function<String, EventStorePollingOptimizer>> eventStorePollingOptimizerFactory,
                                            Optional<SubscriberAcknowledgement> acknowledgement) {
        int pageSize = loadEventsByGlobalOrderBatchSize.orElse(backfillBatchSize);
        // Per call, not per subscribe: a subscribe of this flux again (retry(), repeat()) disposes the registration of the
        // one before it
        var acknowledgementRegistrations = acknowledgement.map(AcknowledgementRegistrations::new);
        if (!availability.isActive()) {
            log.debug("Cdc is not active, using polling fallback");
            availability.fallbackUsed();
            if (fallbackPollCounter != null) fallbackPollCounter.increment();
            // One delivery gate - tracker, gap handler and gap fills awaiting acknowledgement - per subscription, so per
            // subscribe, not per call
            return Flux.defer(() -> {
                var deliveryGate = newDeliveryGate(aggregateType, fromInclusiveGlobalOrder, subscriptionId);
                return handOn(buildAdaptiveLiveSource(aggregateType,
                                                      deliveryGate,
                                                      // Records what it delivers itself; and should CDC already be ACTIVE by the time the source
                                                      // subscribes, nothing has covered the backlog, so its first move onto the bus catches up too
                                                      false,
                                                      pageSize,
                                                      pollingInterval,
                                                      subscriptionId,
                                                      eventStorePollingOptimizerFactory),
                              aggregateType,
                              deliveryGate,
                              onlyIncludeEventIfItBelongsToTenant,
                              acknowledgementRegistrations);
            });
        }

        return Flux.defer(() -> orderedBackfillThenLive(aggregateType,
                                                        fromInclusiveGlobalOrder,
                                                        pageSize,
                                                        newDeliveryGate(aggregateType, fromInclusiveGlobalOrder, subscriptionId),
                                                        onlyIncludeEventIfItBelongsToTenant,
                                                        pollingInterval,
                                                        subscriptionId,
                                                        eventStorePollingOptimizerFactory,
                                                        acknowledgementRegistrations));
    }

    /**
     * The {@link DeliveryGate} of one subscription: its {@link CdcDeliveryTracker} (see {@code newDeliveryTracker}), the
     * subscriber's gap handler - one instance for the whole subscription, whichever source it is on - and the
     * acknowledgement it passes on to the delegate's polls
     */
    private DeliveryGate newDeliveryGate(AggregateType aggregateType, long fromInclusiveGlobalOrder, Optional<SubscriberId> subscriptionId) {
        var gapHandler = subscriptionId.map(eventStreamGapHandler::gapHandlerFor);
        return new DeliveryGate(newDeliveryTracker(aggregateType, fromInclusiveGlobalOrder, subscriptionId, gapHandler),
                                aggregateType,
                                subscriptionId,
                                gapHandler);
    }

    private record SubscriberAndAggregateType(SubscriberId subscriberId, AggregateType aggregateType) {
    }

    /**
     * See {@link DeferredGapRecording#deferGapRecordingUntilResumePointSave}: subscriptions of the subscriber that start
     * from now on keep the narrow gaps they open in memory until a resume point save records them. Only with a
     * {@link PostgresqlEventStreamGapHandler}, the gap handler that can record a gap without the event above it
     * ({@link SubscriptionGapHandler#addTransientGaps}); with any other, gaps are recorded at once, as before
     */
    @Override
    public void deferGapRecordingUntilResumePointSave(SubscriberId subscriberId, AggregateType aggregateType) {
        requireNonNull(subscriberId, "No subscriberId provided");
        requireNonNull(aggregateType, "No aggregateType provided");
        if (eventStreamGapHandler instanceof PostgresqlEventStreamGapHandler) {
            gapRecordingDeferredFor.add(new SubscriberAndAggregateType(subscriberId, aggregateType));
        }
    }

    /**
     * See {@link DeferredGapRecording#beforeResumePointSave}: from now on until the save is closed, the subscriber's gates
     * record a gap the moment it opens; then the gaps they kept in memory are recorded, each gate's in a unit of work of
     * its own, committed before this returns - never in a unit of work current on the calling thread, which could roll
     * back after the gates forgot the gaps. With such a unit of work, or a Spring transaction it would join, each gate
     * records on a virtual thread of its own, and the calling thread waits for the gates' recordings at most
     * {@code DeliveryGate.DEFERRED_GAPS_RECORDING_WAIT} in total for this call, not per gate: a gate with gaps still to
     * record, reached once less than {@code DeliveryGate.DEFERRED_GAPS_RECORDING_WAIT_FLOOR} (or a tenth of the wait, when
     * that is less) is left of that wait, throws at once without starting its recording; a gate with nothing to record
     * returns at once, also while another thread holds its lock. In that order, so a gap
     * is either kept by a gate when this records its gaps, or recorded when it opens - whichever event the resume point
     * read afterwards has moved past
     *
     * @throws IllegalStateException the wait for the gates' recordings ran out, or the calling thread was interrupted while
     *                               waiting - the caller must not save the resume point, and should give the other resume
     *                               points of the same save no wait
     *                               ({@link #beforeResumePointSave(SubscriberId, AggregateType, long)} with a deadline that
     *                               has passed): with its unit of work still current, their recordings may be blocked the
     *                               same way
     */
    @Override
    public ResumePointSave beforeResumePointSave(SubscriberId subscriberId, AggregateType aggregateType) {
        return beforeResumePointSave(subscriberId, aggregateType, System.nanoTime() + deferredGapsRecordingWait.toNanos());
    }

    /**
     * {@link #beforeResumePointSave(SubscriberId, AggregateType)}, with the gates' recordings waited for until
     * {@code waitEndsAt} (a {@link System#nanoTime()} value) instead of {@code DeliveryGate.DEFERRED_GAPS_RECORDING_WAIT}
     * from now. When it has passed, or less than {@code DeliveryGate.DEFERRED_GAPS_RECORDING_WAIT_FLOOR} (or a tenth of
     * the wait, when that is less) is left of it, a gate with nothing to record, or with no unit of work current on the
     * calling thread (which records on the calling thread, not bounded by {@code waitEndsAt}), is unaffected, and a gate
     * whose recording would be waited for throws at once
     */
    @Override
    public ResumePointSave beforeResumePointSave(SubscriberId subscriberId, AggregateType aggregateType, long waitEndsAt) {
        requireNonNull(subscriberId, "No subscriberId provided");
        requireNonNull(aggregateType, "No aggregateType provided");
        var key   = new SubscriberAndAggregateType(subscriberId, aggregateType);
        var saves = resumePointSavesInProgress.computeIfAbsent(key, k -> new AtomicInteger());
        saves.incrementAndGet();
        ResumePointSave save = () -> saves.decrementAndGet();
        try {
            var gates = runningDeliveryGates.get(key);
            if (gates != null) {
                // One wait for the whole save, shared by its gates - not one wait per gate
                for (var gate : List.copyOf(gates)) {
                    gate.recordDeferredGapsCommitted(waitEndsAt);
                }
            }
            return save;
        } catch (InterruptedException e) {
            save.close();
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted while recording the gaps of " + subscriberId + " for " + aggregateType + " kept in memory, before a resume point save", e);
        } catch (TimeoutException e) {
            save.close();
            throw new IllegalStateException("Recording the gaps of " + subscriberId + " for " + aggregateType + " kept in memory did not finish within the wait " +
                                                    "left for this resume point save (at most " + deferredGapsRecordingWait + ")", e);
        } catch (RuntimeException e) {
            save.close();
            throw e;
        }
    }

    /**
     * For tests: how long a thread with a unit of work current waits for a gate's deferred gap recording, or for the gate
     * lock that recording holds - {@code DeliveryGate.DEFERRED_GAPS_RECORDING_WAIT} by default
     */
    void deferredGapsRecordingWait(Duration wait) {
        deferredGapsRecordingWait = requireNonNull(wait, "No wait provided");
    }

    /**
     * @return the least wait left for which a resume point save with a unit of work current starts a gate's recording on a
     * virtual thread: {@code DeliveryGate.DEFERRED_GAPS_RECORDING_WAIT_FLOOR}, or a tenth of
     * {@code deferredGapsRecordingWait} when that is less - so a test that shortens the wait keeps a floor below it
     */
    Duration deferredGapsRecordingWaitFloor() {
        var tenth = deferredGapsRecordingWait.dividedBy(10);
        return tenth.compareTo(DeliveryGate.DEFERRED_GAPS_RECORDING_WAIT_FLOOR) < 0 ? tenth : DeliveryGate.DEFERRED_GAPS_RECORDING_WAIT_FLOOR;
    }

    /**
     * Runs {@code action} on a virtual thread named {@code threadName} and waits for it at most {@code wait}. On time, what
     * it threw is thrown here; past the wait or an interrupt it goes on, unwaited for, and should it then fail, that
     * failure is logged at WARN (once, by whichever of the two threads sees both the failure and the waiter gone), as no
     * caller sees it
     *
     * @throws InterruptedException the calling thread was interrupted while waiting; {@code action} goes on
     * @throws TimeoutException     {@code action} did not end within {@code wait}
     */
    static void runOnAVirtualThreadWaitingAtMost(String threadName, Duration wait, Runnable action) throws InterruptedException, TimeoutException {
        var failure = new AtomicReference<Throwable>();
        // RUNNING, then FAILED (set by the virtual thread) and/or WAITER_GAVE_UP (set by the waiter): the side that
        // finds the other's mark already set logs the failure no caller sees, so it is logged exactly once
        var outcome = new AtomicInteger(RUNNING);
        var thread = Thread.ofVirtual().name(threadName).start(() -> {
            try {
                action.run();
            } catch (Throwable e) {
                failure.set(e);
                if (outcome.getAndSet(FAILED) == WAITER_GAVE_UP) {
                    logFailureNoCallerSees(threadName, wait, e);
                }
            }
        });
        boolean ended;
        try {
            ended = thread.join(wait);
        } catch (InterruptedException e) {
            if (outcome.getAndSet(WAITER_GAVE_UP) == FAILED) {
                logFailureNoCallerSees(threadName, wait, failure.get());
            }
            throw e;
        }
        if (!ended) {
            if (outcome.getAndSet(WAITER_GAVE_UP) == FAILED) {
                logFailureNoCallerSees(threadName, wait, failure.get());
            }
            throw new TimeoutException(threadName + " did not end within " + wait);
        }
        switch (failure.get()) {
            case null -> {
            }
            case RuntimeException e -> throw e;
            case Error e -> throw e;
            default -> throw new IllegalStateException(failure.get());
        }
    }

    private static final int RUNNING        = 0;
    private static final int FAILED         = 1;
    private static final int WAITER_GAVE_UP = 2;

    private static void logFailureNoCallerSees(String threadName, Duration wait, Throwable failure) {
        log.warn("'{}' failed after its caller stopped waiting for it (the wait of {} ran out or the caller was interrupted), so no caller sees this failure", threadName, wait, failure);
    }

    /**
     * @return whether a resume point save of the subscriber is in progress - see {@link #beforeResumePointSave}
     */
    private boolean isResumePointSaveInProgress(SubscriberAndAggregateType key) {
        var saves = resumePointSavesInProgress.get(key);
        return saves != null && saves.get() > 0;
    }

    /**
     * {@code tracker}'s subscription of {@code subscriberId} to {@code aggregateType} hands events on from now on, until
     * {@link #deliveryTrackerEnded} - {@link #isAwaitedAsGap} asks it meanwhile
     */
    void deliveryTrackerRunning(SubscriberId subscriberId, AggregateType aggregateType, CdcDeliveryTracker tracker) {
        // Added inside compute, so an end of the key's last other tracker cannot forget the set in between
        runningDeliveryTrackers.compute(new SubscriberAndAggregateType(subscriberId, aggregateType), (key, trackers) -> {
            var running = trackers != null ? trackers : ConcurrentHashMap.<CdcDeliveryTracker>newKeySet();
            running.add(tracker);
            return running;
        });
    }

    /**
     * {@code tracker}'s subscription ended - see {@link #deliveryTrackerRunning}. Forgets the entry with its last tracker
     */
    void deliveryTrackerEnded(SubscriberId subscriberId, AggregateType aggregateType, CdcDeliveryTracker tracker) {
        runningDeliveryTrackers.computeIfPresent(new SubscriberAndAggregateType(subscriberId, aggregateType), (key, trackers) -> {
            trackers.remove(tracker);
            return trackers.isEmpty() ? null : trackers;
        });
    }

    /**
     * @return how many subscriber and aggregate type pairs a running subscription's tracker is kept for
     */
    int runningDeliveryTrackerCount() {
        return runningDeliveryTrackers.size();
    }

    /**
     * @return the middles of wide gaps handed from a subscription that ends to the next - for tests
     */
    GapMiddlesAwaitedAcrossSubscribes gapMiddlesAwaitedAcrossSubscribes() {
        return gapMiddlesAwaitedAcrossSubscribes;
    }

    /**
     * What the subscription's {@code source} hands its subscriber, after the delivery gate. A gap fill's gap is resolved
     * (see {@link DeliveryGate}):
     * <ul>
     *     <li>with an {@code acknowledgement}: when the subscriber acknowledges the event - in the unit of work it
     *     acknowledges it in, so atomically with the handling. Another tenant's event, which the tenant filter drops, is
     *     done with when it is dropped.</li>
     *     <li>without one: once the downstream took the event ({@link #reportingHandedOn}) - before the tenant filter,
     *     which hands another tenant's fill on by dropping it.</li>
     * </ul>
     */
    private Flux<PersistedEvent> handOn(Flux<PersistedEvent> source,
                                        AggregateType aggregateType,
                                        DeliveryGate deliveryGate,
                                        Optional<Tenant> onlyIncludeEventIfItBelongsToTenant,
                                        Optional<AcknowledgementRegistrations> acknowledgement) {
        var handedOn = handOnToTheSubscriber(source, aggregateType, deliveryGate, onlyIncludeEventIfItBelongsToTenant, acknowledgement)
                .doFinally(signal -> deliveryGate.subscriptionEnded());
        // Only once the end that undoes it is in place - called while subscribing, so it does run
        deliveryGate.subscriptionStarted();
        return handedOn;
    }

    private Flux<PersistedEvent> handOnToTheSubscriber(Flux<PersistedEvent> source,
                                                       AggregateType aggregateType,
                                                       DeliveryGate deliveryGate,
                                                       Optional<Tenant> onlyIncludeEventIfItBelongsToTenant,
                                                       Optional<AcknowledgementRegistrations> acknowledgement) {
        if (acknowledgement.isEmpty()) {
            return filterByTenant(reportingHandedOn(source, deliveryGate), aggregateType, onlyIncludeEventIfItBelongsToTenant);
        }
        var belongsToTenant = belongsToTenant(aggregateType, onlyIncludeEventIfItBelongsToTenant);
        var toTheSubscriber = onlyIncludeEventIfItBelongsToTenant.isEmpty()
                              ? source
                              : source.filter(event -> {
                                  if (belongsToTenant.test(event)) {
                                      return true;
                                  }
                                  deliveryGate.acknowledged(List.of(event));
                                  return false;
                              });
        return acknowledgement.get().registeredOnEverySubscribe(() -> new AcknowledgementRegistrations.PerSubscribe(deliveryGate::acknowledged, toTheSubscriber));
    }

    /**
     * The tracker of what one subscription has delivered (see {@link CdcDeliveryTracker}), seeded with the transient
     * gaps its gap handler still has recorded. Those can lie below where the subscription starts: its resume point
     * advances past a gap - a gap-filled event is delivered after later ones - so an event filling one of them after a
     * restart (or a fenced-lock hand-over to another node) must still get through. Read on the subscribing thread, once
     * per subscription; failing to read them only costs that, so it is logged and the subscription goes ahead.
     * <p>
     * Seeded too with the middles of wide gaps, awaited in memory only, that the subscriber's previous subscription on
     * this instance still awaited when it ended ({@link GapMiddlesAwaitedAcrossSubscribes}) - the resume after a
     * {@code SubscriptionErrorPolicy} stop starts above them. No row records them, so a restart loses them.
     */
    private CdcDeliveryTracker newDeliveryTracker(AggregateType aggregateType,
                                                  long fromInclusiveGlobalOrder,
                                                  Optional<SubscriberId> subscriptionId,
                                                  Optional<SubscriptionGapHandler> gapHandler) {
        var name    = subscriptionId.map(Object::toString).orElse("NoSubscriberId") + "-" + aggregateType;
        // Gives up on a gap when the subscriber's gap handler would: its own threshold if it states one, else the default
        var gapTimeout = CdcDeliveryTracker.gapTimeoutFor(gapHandler);
        var tracker = CdcDeliveryTracker.startingAfter(name, fromInclusiveGlobalOrder - 1, gapTimeout);
        subscriptionId.ifPresent(subscriberId -> tracker.seedEarlierMiddles(gapMiddlesAwaitedAcrossSubscribes.subscribe(subscriberId, aggregateType, fromInclusiveGlobalOrder, gapTimeout)));
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
                                                         DeliveryGate deliveryGate,
                                                         Optional<Tenant> onlyIncludeEventIfItBelongsToTenant,
                                                         Optional<Duration> pollingInterval,
                                                         Optional<SubscriberId> subscriptionId,
                                                         Optional<Function<String, EventStorePollingOptimizer>> eventStorePollingOptimizerFactory,
                                                         Optional<AcknowledgementRegistrations> acknowledgement) {
        var tracker = deliveryGate.tracker;
        var resume  = GlobalEventOrder.of(fromInclusiveGlobalOrder);

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
                deliveryGate,
                tracker::awaitedRanges);

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
                deliveryGate,
                // BackfillThenLiveOrdered attaches it before reading head, back-fills up to that head, and records
                // what it delivers in the tracker
                true,
                pageSize,
                pollingInterval,
                subscriptionId,
                eventStorePollingOptimizerFactory);

        Flux<PersistedEvent> ordered = new BackfillThenLiveOrdered(backfillToLiveTransitionTimer, eventBusProperties, backfillLiveBufferSize, deliveryGate).ordered(
                backfill,
                live,
                headSnapshot);

        // BackfillThenLiveOrdered records an event as it emits it - possibly into its ordered live buffer - so a gap
        // fill's gap is resolved only once the subscriber is done with it, as it leaves the ordered output (see handOn)
        return handOn(ordered, aggregateType, deliveryGate, onlyIncludeEventIfItBelongsToTenant, acknowledgement);
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
     * Only a recovery is debounced: until CDC has been interrupted once ({@link CdcAvailability#interruptions()}), an
     * ACTIVE is the first activation after boot, and a subscription that started on polling during warm-up moves onto
     * the bus at once - debounced, every subscription started during boot polled for the whole window.
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
     * @param deliveryGate               the subscription's delivery gate - what it has delivered (the source starts after
     *                                   its tracker's watermark), its gap handler, and the gap fills awaiting
     *                                   acknowledgement. Events from the delegate's polls are acknowledged to it through
     *                                   the gate ({@link DeliveryGate#newDelegateAcknowledgement()}), so the delegate
     *                                   leaves their gaps to the gate
     * @param forBackfillThenLiveOrdered true when the caller is {@link BackfillThenLiveOrdered}, which attaches this source
     *                                   before reading its own head, delivers everything up to that head itself, and
     *                                   records what it hands downstream in the tracker: the first move onto the bus then
     *                                   skips the catch-up (every later one catches up regardless), and this source only
     *                                   drops what the tracker already holds, without recording anything
     * @return every tenant's events, past the gate - see "Tenants": the caller filters by tenant ({@link #handOn})
     */
    private Flux<PersistedEvent> buildAdaptiveLiveSource(
            AggregateType aggregateType,
            DeliveryGate deliveryGate,
            boolean forBackfillThenLiveOrdered,
            int pageSize,
            Optional<Duration> pollingInterval,
            Optional<SubscriberId> subscriptionId,
            Optional<Function<String, EventStorePollingOptimizer>> eventStorePollingOptimizerFactory
                                                        ) {
        var tracker = deliveryGate.tracker;
        Flux<CdcAvailability.State> rawStates = availability.stateChanges().distinctUntilChanged();
        var firstEmission = new AtomicBoolean(true);
        Flux<CdcAvailability.State> gatedStates = rawStates
                .switchMap(state -> {
                    if (firstEmission.compareAndSet(true, false)) {
                        return Mono.just(state);
                    }
                    // Only a recovery is debounced. Before CDC was ever interrupted, an ACTIVE is its first activation
                    // after boot (warm-up): debounced, every subscription started during boot polled for the whole window
                    return state == CdcAvailability.State.ACTIVE && availability.interruptions().count() > 0
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
                                                        deliveryGate,
                                                        catchUp,
                                                        pageSize,
                                                        cdcDeliveryScheduler);
                                      }

                                      long resumeFrom = tracker.resumeFromInclusive();
                                      log.debug("[{}] Adaptive live source switching to polling (resumeFrom={}, state={})",
                                                aggregateType, resumeFrom, state);
                                      // All tenants: see "Tenants" in the javadoc. Acknowledged by the gate, so the delegate
                                      // resolves no gap fill's gap on its own once it handed it on - the gate resolves it
                                      return eventStore.pollEvents(aggregateType,
                                                                   resumeFrom,
                                                                   Optional.of(pageSize),
                                                                   pollingInterval,
                                                                   Optional.empty(),
                                                                   subscriptionId,
                                                                   eventStorePollingOptimizerFactory,
                                                                   deliveryGate.newDelegateAcknowledgement());
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
                                          ? deliveryGate::isNotDeliveredYet
                                          : deliveryGate::deliver),
                                  // No tenant gate here: the caller filters by tenant after the gate (see handOn), as the
                                  // tracker has to see every global order
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
                                        DeliveryGate deliveryGate,
                                        boolean catchUpOnFirstAttach,
                                        int pageSize,
                                        Scheduler cdcDeliveryScheduler) {
        var tracker                = deliveryGate.tracker;
        var catchUpOnAttach        = new AtomicBoolean(catchUpOnFirstAttach);
        var recoveringFromOverflow = new AtomicBoolean(false);
        var overflowsSinceSummary  = new AtomicLong();
        var lastOverflowSummaryAt  = new AtomicLong(System.nanoTime() - OVERFLOW_SUMMARY_INTERVAL.toNanos());
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
                                                                      deliveryGate,
                                                                      // Every gap still awaited, kept in memory or recorded: a selection
                                                                      // (lowest first, newest rows first) missed a fill for good
                                                                      tracker::awaitedRanges,
                                                                      cdcDeliveryScheduler)
                               .doOnComplete(() -> {
                                   if (recoveringFromOverflow.compareAndSet(true, false)) {
                                       log.debug("[{}-{}] Caught up after falling behind the CDC bus - back on the bus after global order {}",
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
                           // Routine under bursts, and self-healing: DEBUG each time, one INFO a minute counting them
                           log.debug("[{}-{}] Subscription fell more than {} events behind the CDC bus - catching up from global order {} and rejoining the bus",
                                     subscriberIdForLog, aggregateType, pageSize, resumeFrom);
                           var overflows = overflowsSinceSummary.incrementAndGet();
                           var now       = System.nanoTime();
                           var last      = lastOverflowSummaryAt.get();
                           if (now - last >= OVERFLOW_SUMMARY_INTERVAL.toNanos() && lastOverflowSummaryAt.compareAndSet(last, now)) {
                               overflowsSinceSummary.addAndGet(-overflows);
                               log.info("[{}-{}] Fell more than {} events behind the CDC bus {} time(s) since the last report (latest from global order {}); " +
                                                "each time it caught up from the event store and rejoined the bus. Counted in the live-source overflow metric",
                                        subscriberIdForLog, aggregateType, pageSize, overflows, resumeFrom);
                           }
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
            // handOver is an unsafe sink: BusHandOver is its only producer, and the Reactive Streams rules (the bus sink
            // signals one subscriber serially) make every emit below - next, error, complete - happen one at a time, so
            // the sink cannot see concurrent emission and cannot answer FAIL_NON_SERIALIZED. Nothing else emits on it:
            // dispose() only cancels
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
     * - and records with the subscriber's gap handler a gap it opens or fills. One per subscription, whichever source it
     * is on.
     * <p>
     * Why the gap handler: a subscriber's resume point advances past a gap (gap-filled events are delivered late, so it
     * only ever moves forward), which is safe only while the gap is recorded durably, as a transient gap the next
     * subscription of that subscriber re-queries and its tracker waits for (see {@code newDeliveryTracker}). The polling
     * path and every back-fill page record the gaps in what they load, but an event from the bus is not loaded: without
     * this, a gap the bus revealed was lost at a restart - or a fenced-lock hand-over - before its event arrived. So an
     * event that opens a gap records it, synchronously and before the event is handed to the subscriber, in a unit of
     * work of its own, reconciled as a back-fill page over {@code [gap .. event]} holding just the event would.
     * <p>
     * An event that fills a gap resolves it (else the gap handler would later promote a gap whose event exists to
     * permanent) - but only once the subscriber is done with it: the transient gap is the only durable record that the
     * fill is still owed, as the resume point lies above it, so resolved first, a subscription stopped (or a process that
     * died) before the fill was handled resumed above it and never asked for it again. Done is
     * <ul>
     *     <li>for a subscriber that acknowledges ({@link SubscriberAcknowledgement}): when it acknowledges the event
     *     ({@link #acknowledged}) - in the unit of work it acknowledges it in, atomically with the handling, and forgotten
     *     here only once that unit of work committed (a rollback leaves the gap open, and the fill waiting). Until then the
     *     tracker still has the fill as delivered, so this subscription does not deliver it again - however often a
     *     back-fill page or a poll reads it while its gap is open - and a restarted one, whose tracker is seeded with the
     *     open gaps, is handed it again. That covers a fill waiting for a batch, an I/O retry, or demand.</li>
     *     <li>for any other subscriber: once the downstream took it ({@link #handedOn}, via {@link #reportingHandedOn}).</li>
     * </ul>
     * The fills awaiting that are kept in a {@link GapFillsAwaitingAcknowledgement}, the protocol the delegate's
     * acknowledged polls follow too. A fill whose subscription is cancelled before it is done with keeps its gap, and the
     * next subscription waits for it again. Back-fill and catch-up pages leave the gaps they fill open for the same reason
     * - every event they load passes through the gate - and, like every reconciliation here, are given the fills still
     * awaiting acknowledgement, so the gap handler never promotes their gaps.
     * <p>
     * A gap the tracker gives up on after waiting the gap handler's give-up threshold for it is recorded with the gap
     * handler too ({@link #recordGivenUpGaps(boolean)}), so the transient gaps a later subscription waits for agree with what
     * this one stopped waiting for: an event that fills a given-up gap late is dropped by both. Recorded with the next
     * event delivered, and when the subscription ends; a gap the tracker gives up on because it waits for too many at
     * once is not, as its transaction may still be in flight - a later subscription waits for it again.
     * <p>
     * The delegate's polls (the polling leg) are handed an acknowledgement ({@link #newDelegateAcknowledgement()}) which
     * the gate acknowledges every event the subscriber is done with to, and every event it drops ({@link #dropped}): the
     * delegate then leaves the gaps of the fills it hands on to the gate, rather than resolving them once it handed them
     * into this pipeline, and is never left waiting for a fill the gate dropped.
     * <p>
     * Only when there is a subscriber id and a gap handler that records gaps, and only for events that open or fill a
     * gap - an in-order event costs nothing. A failure outside the subscriber's unit of work is logged and the event
     * delivered regardless: it only weakens the restart guarantee for that gap (or, for a fill, leaves the gap open, so a
     * later subscription is handed the event again). Calls to the gap handler hold its monitor, never across a commit:
     * the subscriber acknowledges on its own thread, concurrently with the delivering one.
     * <p>
     * Runs on the delivering thread - the subscription's {@code Cdc-*}, {@code Publish-*} or back-fill thread, never the
     * bus's - except {@link #acknowledged}, which runs on the subscriber's.
     */
    private final class DeliveryGate implements DeliveryRecorder {
        /**
         * The widest gap kept in memory rather than recorded when it opens - concurrent appenders committing out of order
         * open gaps of a few orders; a wider one is recorded at once
         */
        static final int MAX_DEFERRED_ORDERS_PER_GAP          = 1_000;
        /**
         * The most orders one subscription keeps in memory as gaps - beyond, a gap is recorded at once. Also bounds what a
         * catch-up loads by order on their account (see {@link CdcDeliveryTracker#awaitedRanges})
         */
        static final int MAX_DEFERRED_ORDERS_PER_SUBSCRIPTION = 1_000;
        /**
         * How long a thread with a unit of work current waits for the gaps kept in memory to be recorded on a virtual thread
         * of their own (see {@link #recordDeferredGapsCommitted()}): one short unit of work, unless a lock the waiting
         * thread's unit of work holds blocks it - then the wait ends instead of hanging the save or the subscription's end.
         * Also the longest an acknowledgement ({@link #withoutFillsOfUnrecordedGaps}), the delivering thread's checks of an
         * opened gap, a fill and a dropped event - once per stall, not once per event ({@link #lockedOnDeliveringThread})
         * - and the subscription's end recording the gaps given up on wait for {@link #deferredGapsLock} - see there for
         * what each does when the wait runs out. {@link #isAwaitedAsGap}'s check of a held fill
         * ({@link #isAwaitedAsFillOfAnUnrecordedGap}) does not wait for it at all
         */
        static final Duration DEFERRED_GAPS_RECORDING_WAIT = Duration.ofSeconds(10);
        /**
         * The least wait left for which a resume point save with a unit of work current starts a gate's recording on a
         * virtual thread - or a tenth of {@code deferredGapsRecordingWait}, when that is less (see
         * {@link CdcEventStore#deferredGapsRecordingWaitFloor()}). With less left, the save would almost certainly stop waiting before
         * that recording's unit of work commits, and the recording would go on holding {@link #deferredGapsLock}, making
         * the gate's acknowledgements and checks fall back for nothing: the gate throws at once instead
         */
        static final Duration DEFERRED_GAPS_RECORDING_WAIT_FLOOR = Duration.ofMillis(100);

        /**
         * @return {@code orders} as runs of consecutive orders, ascending
         */
        private static List<LongRange> runsOf(SortedSet<Long> orders) {
            var  runs  = new ArrayList<LongRange>();
            long from  = -1;
            long to    = -1;
            for (long order : orders) {
                if (from >= 0 && order == to + 1) {
                    to = order;
                    continue;
                }
                if (from >= 0) runs.add(LongRange.between(from, to));
                from = order;
                to = order;
            }
            if (from >= 0) runs.add(LongRange.between(from, to));
            return runs;
        }

        private final CdcDeliveryTracker                        tracker;
        private final AggregateType                             aggregateType;
        private final Optional<SubscriberId>                    subscriberId;
        private final Optional<SubscriptionGapHandler>          gapHandler;
        /**
         * The gap fills let through whose gap is resolved once the subscriber is done with them - the protocol the
         * delegate's acknowledged polls follow too. Present with a gap handler that records gaps
         */
        private final Optional<GapFillsAwaitingAcknowledgement> gapFillsBeingHandedOn;
        /**
         * Handed to the delegate's current poll (see {@link #newDelegateAcknowledgement()}); acknowledges to it every event
         * the subscriber is done with, and every event the gate drops
         */
        private volatile SubscriberAcknowledgement              delegateAcknowledgement = SubscriberAcknowledgement.create();
        /**
         * Whether the narrow gaps this subscription opens are kept in memory until a resume point save of the subscriber
         * records them ({@link #recordDeferredGaps()}) - see {@link DeferredGapRecording}
         */
        private final boolean                                   defersGapRecording;
        /**
         * Guards the two collections below; held across {@link #recordDeferredGaps()}'s commit, so an acknowledgement
         * never resolves a fill whose gap that is still recording. Taken before the gap handler's monitor, never after.
         * A recording that outlasted {@link #DEFERRED_GAPS_RECORDING_WAIT} may hold it - and the gap handler's monitor -
         * while it waits for a database lock a unit of work on another thread holds, a wait PostgreSQL cannot see as a
         * deadlock; and that thread may wait for this subscription. So only {@link #recordDeferredGaps()}, which runs on a
         * thread with no unit of work, waits for it without a limit. Every other path either does not wait for it, or waits
         * at most {@code deferredGapsRecordingWait} and then falls back:
         * <ul>
         *     <li>a save's emptiness check only tries it, and else reads {@link #keepsUnrecorded} and the three queues
         *     below without it: nothing kept, it returns; else it leaves the check to a recording;</li>
         *     <li>the subscription's end sets {@link #ended} without it;</li>
         *     <li>an acknowledgement ({@link #withoutFillsOfUnrecordedGaps}) leaves its events to the recording to settle,
         *     once its unit of work commits, and returns;</li>
         *     <li>{@link #isAwaitedAsGap}'s check of a held fill ({@link #isAwaitedAsFillOfAnUnrecordedGap}) only tries
         *     it, and else answers "awaited";</li>
         *     <li>on the delivering thread, which waits once per stall rather than once per event
         *     ({@link #lockedOnDeliveringThread}: after one wait ran out, its later checks only try the lock until a
         *     recording lets go of it or a check takes it): an opened gap ({@link #keptUnrecorded}) is kept without the
         *     lock, in {@link #gapsKeptWhileLockBusy}; a fill ({@link #filledAnUnrecordedGap}) is taken for the fill of a
         *     recorded gap; a dropped event ({@link #dropped}) goes to {@link #droppedWhileLockBusy}, and the lock's next
         *     holder with no unit of work current acknowledges it to the delegate's poll unless it is a fill the subscriber
         *     is not done with; and gaps
         *     given up on ({@link #withoutUnrecordedGaps}) only try it, and else go back to the tracker for the next
         *     event;</li>
         *     <li>the subscription's end, recording the gaps given up on ({@link #withoutUnrecordedGaps}), waits for it at
         *     most {@code deferredGapsRecordingWait} - only tries it while the delivering thread's wait is known to have
         *     run out - and else hands them back to the tracker, for a later subscription to wait for again.</li>
         * </ul>
         * The fallbacks do not wait for the gap handler's monitor either - except an opened gap that a save in progress,
         * the subscription's end or the per-subscription limit makes {@link #keptWhileLockBusy} refuse: that one is
         * recorded at once, as without the fallback.
         */
        private final ReentrantLock                             deferredGapsLock        = new ReentrantLock();
        /**
         * The orders of the gaps opened and kept in memory - not recorded with the gap handler, not delivered, not given up
         */
        private final TreeSet<Long>                             unrecordedGaps          = new TreeSet<>();
        /**
         * The fills of gaps kept in memory, handed on and not done with: recorded as gaps by a save, unless the subscriber
         * is done with them first
         */
        private final TreeMap<Long, PersistedEvent>             unrecordedFillsNotDoneWith = new TreeMap<>();
        /**
         * Whether {@link #unrecordedGaps} or {@link #unrecordedFillsNotDoneWith} may hold anything - false only when both
         * are empty. Written under {@link #deferredGapsLock}, and only where they gain or lose entries, never per event;
         * read without it by a save whose {@code tryLock} failed ({@link #recordDeferredGapsCommitted(long)}), so a lock
         * held for a moment by a delivering thread or an acknowledgement does not make an empty gate's save wait or fail.
         * Set before the entries are added ({@link #keptUnrecorded}, {@link #takeInGapsKeptWhileLockBusy()}), so a save
         * that read false either finds the gap in {@link #gapsKeptWhileLockBusy} or was seen by the keeper's check of
         * {@link #isResumePointSaveInProgress} - see {@link #recordDeferredGapsCommitted(long)}
         */
        private volatile boolean                                keepsUnrecorded;
        /**
         * Once the subscription ended, every gap is recorded at once. Read under {@link #deferredGapsLock}, written without
         * it (see {@link #recordDeferredGapsAtEnd()})
         */
        private volatile boolean                                ended;
        /**
         * The gaps {@link #keptUnrecorded} kept while {@link #deferredGapsLock} was not free within
         * {@code deferredGapsRecordingWait} - written without the lock, moved into {@link #unrecordedGaps} by the next
         * holder ({@link #takeInGapsKeptWhileLockBusy()}). Empty but for that rare case
         */
        private final Queue<LongRange>                          gapsKeptWhileLockBusy   = new ConcurrentLinkedQueue<>();
        /**
         * How many orders {@link #gapsKeptWhileLockBusy} holds - bounded by {@link #MAX_DEFERRED_ORDERS_PER_SUBSCRIPTION}
         */
        private final AtomicLong                                ordersKeptWhileLockBusy = new AtomicLong();
        /**
         * The events acknowledged while {@link #deferredGapsLock} was not free within {@code deferredGapsRecordingWait}
         * (see {@link #withoutFillsOfUnrecordedGaps}) - written without the lock, and only once the acknowledging unit of work
         * committed ({@link #acknowledgedWhileLockBusyOnceCommitted}): a rollback leaves the gaps of its fills open. Also the
         * events of a committed acknowledgement whose commit callback found the lock busy, or whose held fill a recording
         * recorded before the commit ({@link #doneWithOnceCommitted}). Settled by the next acknowledgement or recording
         * that holds the lock ({@link #settleAcknowledgedWhileLockBusy()}). Empty but for those rare cases
         */
        private final Queue<PersistedEvent>                     acknowledgedWhileLockBusy = new ConcurrentLinkedQueue<>();
        /**
         * Adds an acknowledgement's events to {@link #acknowledgedWhileLockBusy} once its unit of work committed (see
         * {@link #acknowledgedWhileLockBusyOnceCommitted})
         */
        private final AcknowledgedWhileLockBusyOnceCommitted    queuedOnceCommitted     = new AcknowledgedWhileLockBusyOnceCommitted();
        /**
         * The events {@link #dropped} while {@link #deferredGapsLock} was not free - written without the lock, settled by
         * the lock's next holder with no unit of work current ({@link #settleDroppedWhileLockBusy()}): acknowledged to the delegate's poll unless a fill
         * the subscriber is not done with. Empty but for that rare case
         */
        private final Queue<PersistedEvent>                     droppedWhileLockBusy    = new ConcurrentLinkedQueue<>();
        /**
         * Set once a wait of the delivering thread for {@link #deferredGapsLock} ran out (or was interrupted): its later
         * checks then only try the lock, rather than each waiting {@code deferredGapsRecordingWait} again behind the same
         * blocked recording. Cleared by a recording as it lets go of the lock, and by a delivering-thread check that takes
         * it. See {@link #lockedOnDeliveringThread()}
         */
        private volatile boolean                                deliveringThreadStalled;

        private DeliveryGate(CdcDeliveryTracker tracker, AggregateType aggregateType, Optional<SubscriberId> subscriberId, Optional<SubscriptionGapHandler> gapHandler) {
            this.tracker = tracker;
            this.aggregateType = aggregateType;
            this.subscriberId = subscriberId;
            this.gapHandler = gapHandler.filter(handler -> recordsGaps());
            this.gapFillsBeingHandedOn = this.gapHandler.map(handler -> new GapFillsAwaitingAcknowledgement(handler,
                                                                                                         aggregateType,
                                                                                                         unitOfWorkFactory,
                                                                                                         eventStore.getEventStoreSubscriptionObserver(),
                                                                                                         handler.subscriberId() + "-" + aggregateType));
            if (this.gapHandler.isPresent()) {
                tracker.collectTimedOutGaps();
            }
            this.defersGapRecording = this.gapHandler.isPresent() && subscriberId.map(id -> gapRecordingDeferredFor.contains(new SubscriberAndAggregateType(id, aggregateType)))
                                                                                    .orElse(false);
        }

        @Override
        public boolean deliver(PersistedEvent event) {
            long globalOrder = event.globalEventOrder().longValue();
            var  delivery    = tracker.markDelivered(globalOrder);
            recordGivenUpGaps(false);
            switch (delivery.kind()) {
                case OPENED_GAP -> {
                    var awaitedGaps = delivery.durablyAwaitedGapsBelow(globalOrder);
                    if (!keptUnrecorded(awaitedGaps)) {
                        recordOpenedGap(event, awaitedGaps);
                    }
                }
                case FILLED_GAP -> {
                    if (!filledAnUnrecordedGap(event)) {
                        gapFillsBeingHandedOn.ifPresent(gapFills -> gapFills.awaitAcknowledgement(List.of(event)));
                    }
                }
                case DUPLICATE -> dropped(event);
                default -> {
                }
            }
            return delivery.isNew();
        }

        /**
         * The gate drops {@code event} - delivered before, or given up on: acknowledged to the delegate's polls, which
         * would otherwise keep a gap fill they handed on waiting for an acknowledgement that never comes - its gap neither
         * resolved nor promoted for as long as the poll lives, as an awaiting fill's gap is never promoted. Not a gap fill
         * the gate let through earlier that the subscriber is not done with: its gap is the record that it is still owed,
         * and the subscriber's acknowledgement reaches the delegate too. Should the gate lock not be free on the delivering
         * thread, the event is acknowledged by the lock's next holder instead (see
         * {@link #isFillOfAnUnrecordedGapNotDoneWith}).
         */
        @Override
        public void dropped(PersistedEvent event) {
            if (gapFillsBeingHandedOn.isPresent() && gapFillsBeingHandedOn.get().isAwaiting(event)) {
                return;
            }
            if (isFillOfAnUnrecordedGapNotDoneWith(event)) {
                return;
            }
            delegateAcknowledgement.acknowledge(event);
        }

        /**
         * For a subscriber that does not acknowledge: the downstream took {@code event}, so it is done with. Called from
         * the downstream's {@code onNext}, so a failure is logged rather than thrown - it would end the subscription. One
         * can only happen inside a unit of work that is current on the delivering thread, and leaves the gap open, so a
         * later subscription is handed the event again
         */
        @Override
        public void handedOn(PersistedEvent event) {
            try {
                acknowledged(List.of(event));
            } catch (RuntimeException e) {
                log.warn("[{}] Could not resolve the gap of the gap fill with global order {} - it stays a transient gap, so a later subscription delivers the event again",
                         aggregateType, event.globalEventOrder(), e);
            }
        }

        /**
         * The subscriber is done with {@code events} - the {@link SubscriberAcknowledgement} listener; also called for
         * another tenant's event the tenant filter drops, and for an event a subscriber that does not acknowledge was
         * handed. Resolves the gaps of the gap fills among them (see {@link GapFillsAwaitingAcknowledgement#acknowledged}),
         * and acknowledges them to the delegate's polls - also when resolving them failed, so the delegate is never left
         * waiting for a fill it handed on. Never waits for {@link #deferredGapsLock} longer than
         * {@code deferredGapsRecordingWait}: the subscriber's unit of work may hold a lock a recording that holds it waits
         * for. Past that wait it returns as usual, having acknowledged {@code events} to the delegate's polls, and leaves
         * the gaps of the fills among them to that recording to settle - once the caller's unit of work commits, so a
         * rollback leaves them open (see {@link #withoutFillsOfUnrecordedGaps}).
         *
         * @throws RuntimeException a failure to resolve them inside the caller's unit of work - the gaps stay open
         */
        void acknowledged(List<PersistedEvent> events) {
            try {
                // A fill of a gap kept in memory: done with, nothing was recorded - nothing to resolve
                var fillsOfRecordedGaps = withoutFillsOfUnrecordedGaps(events);
                gapFillsBeingHandedOn.ifPresent(gapFills -> gapFills.acknowledged(fillsOfRecordedGaps));
            } catch (RuntimeException e) {
                try {
                    delegateAcknowledgement.acknowledge(events);
                } catch (RuntimeException alsoFailed) {
                    e.addSuppressed(alsoFailed);
                }
                throw e;
            }
            // A middle fill among them - awaited in memory only - is no longer owed to the next subscription
            tracker.doneWith(events);
            delegateAcknowledgement.acknowledge(events);
        }

        @Override
        public boolean isDelivered(PersistedEvent event) {
            return tracker.isDelivered(event.globalEventOrder().longValue());
        }

        /**
         * The live source's filter in front of {@link BackfillThenLiveOrdered}, which records what it delivers itself
         *
         * @return true unless {@code event} was delivered before or given up on - then it is {@link #dropped}
         */
        boolean isNotDeliveredYet(PersistedEvent event) {
            if (!isDelivered(event)) {
                return true;
            }
            dropped(event);
            return false;
        }

        /**
         * A new acknowledgement for the delegate's poll the subscription switches to, from now on the one events are
         * acknowledged to: an event store keeps its registration with an acknowledgement for as long as the
         * acknowledgement lives, and one shared by every poll would collect a registration per switch to polling. A gap
         * fill an earlier poll handed on is the gate's to resolve anyway
         */
        SubscriberAcknowledgement newDelegateAcknowledgement() {
            var acknowledgement = SubscriberAcknowledgement.create();
            delegateAcknowledgement = acknowledgement;
            return acknowledgement;
        }

        /**
         * The subscription starts handing events on: until it ends, {@link #isAwaitedAsGap} asks its tracker too - about
         * the middles of wide gaps it finds itself, which only the tracker knows about while it runs. Only a subscription
         * with a subscriber id, as only one can be asked about
         */
        void subscriptionStarted() {
            subscriberId.ifPresent(id -> deliveryTrackerRunning(id, aggregateType, tracker));
            if (defersGapRecording) {
                runningDeliveryGates.computeIfAbsent(key(), k -> ConcurrentHashMap.newKeySet()).add(this);
            }
        }

        private SubscriberAndAggregateType key() {
            return new SubscriberAndAggregateType(subscriberId.orElseThrow(), aggregateType);
        }

        /**
         * Keeps the gap below an event that opened it in memory instead of recording it - when this subscription defers
         * gap recording, the gap is narrow (no middle awaited in memory only, at most {@link #MAX_DEFERRED_ORDERS_PER_GAP}
         * orders), no more than {@link #MAX_DEFERRED_ORDERS_PER_SUBSCRIPTION} orders are kept, and no resume point save of
         * the subscriber is in progress: the resume point that save reads may already be past this gap's event.
         * <p>
         * Runs on the delivering thread: waits for {@link #deferredGapsLock} at most {@code deferredGapsRecordingWait}, once
         * per stall ({@link #lockedOnDeliveringThread()}). When the lock is not taken, the gap is kept without it, in
         * {@link #gapsKeptWhileLockBusy}, which the lock's next holder takes in - not recorded at once: that would wait for
         * the gap handler's monitor, which a recording blocked inside {@code addTransientGaps} holds as well. Kept that
         * way, the gaps of one subscription may add up to twice {@link #MAX_DEFERRED_ORDERS_PER_SUBSCRIPTION}.
         *
         * @return true if kept, false if the gap is to be recorded now
         */
        private boolean keptUnrecorded(List<LongRange> awaitedGaps) {
            if (!defersGapRecording || awaitedGaps.size() != 1) return false;
            var  gap   = awaitedGaps.getFirst();
            long width = gap.toInclusive - gap.fromInclusive + 1;
            if (width > MAX_DEFERRED_ORDERS_PER_GAP) return false;
            if (!lockedOnDeliveringThread()) {
                return keptWhileLockBusy(gap, width);
            }
            try {
                takeInGapsKeptWhileLockBusy();
                // Before the checks of a save or the end below: a save that reads it unset without the lock raised its
                // counter (or the end set ended) before that read, so the checks below see it and refuse
                if (!keepsUnrecorded) keepsUnrecorded = true;
                if (ended ||
                        unrecordedGaps.size() + unrecordedFillsNotDoneWith.size() + width > MAX_DEFERRED_ORDERS_PER_SUBSCRIPTION ||
                        isResumePointSaveInProgress(key())) {
                    noteWhatIsKept();
                    return false;
                }
                gap.stream().forEach(unrecordedGaps::add);
                return true;
            } finally {
                deferredGapsLock.unlock();
            }
        }

        /**
         * {@link #keptUnrecorded} without {@link #deferredGapsLock}: the same checks, read without it, and checked again
         * once the gap is in {@link #gapsKeptWhileLockBusy} - a save or the subscription's end that began meanwhile either
         * takes the gap in under the lock (its recording does, before it records), or the gap is taken back out here and
         * recorded now
         *
         * @return true if kept, false if the gap is to be recorded now
         */
        private boolean keptWhileLockBusy(LongRange gap, long width) {
            if (ended || isResumePointSaveInProgress(key())) return false;
            if (ordersKeptWhileLockBusy.addAndGet(width) > MAX_DEFERRED_ORDERS_PER_SUBSCRIPTION) {
                ordersKeptWhileLockBusy.addAndGet(-width);
                return false;
            }
            gapsKeptWhileLockBusy.add(gap);
            if ((ended || isResumePointSaveInProgress(key())) && gapsKeptWhileLockBusy.remove(gap)) {
                ordersKeptWhileLockBusy.addAndGet(-width);
                return false;
            }
            log.debug("[{}-{}] Kept the gap {} in memory without the gate lock, which was not free within {}",
                      subscriberId.orElseThrow(), aggregateType, gap, deferredGapsRecordingWait);
            return true;
        }

        /**
         * Moves the gaps kept without {@link #deferredGapsLock} into {@link #unrecordedGaps}. Called holding the lock
         */
        private void takeInGapsKeptWhileLockBusy() {
            if (gapsKeptWhileLockBusy.isEmpty()) return;
            // Before a gap leaves the queue: a save reading both without the lock never finds it in neither
            if (!keepsUnrecorded) keepsUnrecorded = true;
            for (var gap = gapsKeptWhileLockBusy.poll(); gap != null; gap = gapsKeptWhileLockBusy.poll()) {
                ordersKeptWhileLockBusy.addAndGet(-(gap.toInclusive - gap.fromInclusive + 1));
                gap.stream().forEach(unrecordedGaps::add);
            }
        }

        /**
         * Sets {@link #keepsUnrecorded} to what {@link #unrecordedGaps} and {@link #unrecordedFillsNotDoneWith} hold - written
         * only when that changes. Called holding {@link #deferredGapsLock}, after they lost entries
         */
        private void noteWhatIsKept() {
            var keeps = !unrecordedGaps.isEmpty() || !unrecordedFillsNotDoneWith.isEmpty();
            if (keeps != keepsUnrecorded) keepsUnrecorded = keeps;
        }

        /**
         * Settles the events acknowledged without {@link #deferredGapsLock}: a fill of a gap kept in memory among them is
         * done with from now on, as {@link #withoutFillsOfUnrecordedGaps} does with the lock. Called holding the lock
         *
         * @return the others - their gaps, if any, are recorded and to be resolved
         * ({@link GapFillsAwaitingAcknowledgement#acknowledged})
         */
        private List<PersistedEvent> settleAcknowledgedWhileLockBusy() {
            if (acknowledgedWhileLockBusy.isEmpty()) return List.of();
            var others = new ArrayList<PersistedEvent>();
            for (var event = acknowledgedWhileLockBusy.poll(); event != null; event = acknowledgedWhileLockBusy.poll()) {
                if (unrecordedFillsNotDoneWith.remove(event.globalEventOrder().longValue()) == null) {
                    others.add(event);
                }
            }
            noteWhatIsKept();
            return others;
        }

        /**
         * Runs on the delivering thread: waits for {@link #deferredGapsLock} at most {@code deferredGapsRecordingWait}, once
         * per stall ({@link #lockedOnDeliveringThread()}). When the lock is not taken, {@code fill} is taken for the fill
         * of a recorded gap - and handed to {@link #gapFillsBeingHandedOn} by the caller. Only a recording holds the lock
         * that long, and it records the gap of an order still in {@link #unrecordedGaps}, so the subscriber's
         * acknowledgement resolves that gap. Should the recording fail, or the gap be one kept without the lock
         * ({@link #gapsKeptWhileLockBusy}), the order stays kept, and a later recording records a gap whose event was
         * delivered, so a later subscription delivers it again.
         *
         * @return true if {@code fill} filled a gap kept in memory - then nothing is to be resolved when the subscriber is
         * done with it, unless a save records its gap meanwhile
         */
        private boolean filledAnUnrecordedGap(PersistedEvent fill) {
            if (!defersGapRecording) return false;
            long order = fill.globalEventOrder().longValue();
            if (!lockedOnDeliveringThread()) return false;
            try {
                takeInGapsKeptWhileLockBusy();
                if (!unrecordedGaps.remove(order)) return false;
                unrecordedFillsNotDoneWith.put(order, fill);
                return true;
            } finally {
                deferredGapsLock.unlock();
            }
        }

        /**
         * For {@link #dropped}, on the delivering thread: waits for {@link #deferredGapsLock} as
         * {@link #lockedOnDeliveringThread()} does. When the lock is not taken, {@code event} goes to
         * {@link #droppedWhileLockBusy} - neither acknowledged to the delegate's poll now nor forgotten: the lock's next
         * holder with no unit of work current acknowledges it unless it is a fill the subscriber is not done with ({@link #settleDroppedWhileLockBusy()}).
         * The lock is tried once more right after, for a recording that let go of it meanwhile, having settled the earlier
         * ones only.
         *
         * @return true if {@code event} is the fill of a gap kept in memory that was handed on and is not done with, or
         * was left to the lock's next holder - either way not to be acknowledged to the delegate's poll by the caller
         */
        private boolean isFillOfAnUnrecordedGapNotDoneWith(PersistedEvent event) {
            if (!defersGapRecording) return false;
            boolean held;
            if (lockedOnDeliveringThread()) {
                held = unrecordedFillsNotDoneWith.containsKey(event.globalEventOrder().longValue());
            } else {
                droppedWhileLockBusy.add(event);
                if (!deferredGapsLock.tryLock()) return true;
                held = true;
            }
            List<PersistedEvent> notHeld;
            try {
                notHeld = settleDroppedWhileLockBusy();
            } finally {
                deferredGapsLock.unlock();
            }
            acknowledgeDroppedToDelegate(notHeld);
            return held;
        }

        /**
         * Takes the events {@link #dropped} without {@link #deferredGapsLock}, and leaves out those the subscriber is not
         * done with: a fill of a gap kept in memory ({@link #unrecordedFillsNotDoneWith}), or one of a recorded gap
         * ({@link #gapFillsBeingHandedOn}) - the subscriber's own acknowledgement reaches the delegate's poll for those.
         * Called holding the lock; the caller acknowledges the result after letting go of it
         * ({@link #acknowledgeDroppedToDelegate})
         *
         * @return the dropped events to acknowledge to the delegate's poll
         */
        private List<PersistedEvent> settleDroppedWhileLockBusy() {
            if (droppedWhileLockBusy.isEmpty()) return List.of();
            var notHeld = new ArrayList<PersistedEvent>();
            for (var event = droppedWhileLockBusy.poll(); event != null; event = droppedWhileLockBusy.poll()) {
                if (unrecordedFillsNotDoneWith.containsKey(event.globalEventOrder().longValue())) continue;
                if (gapFillsBeingHandedOn.isPresent() && gapFillsBeingHandedOn.get().isAwaiting(event)) continue;
                notHeld.add(event);
            }
            return notHeld;
        }

        /**
         * {@link #settleDroppedWhileLockBusy()}, but only on a thread with no unit of work or Spring transaction current
         * ({@link TransactionProbe#isUnitOfWorkActive}): the delegate's acknowledgement resolves the gaps the dropped events
         * fill in the current unit of work, which belongs to the caller - its rollback would lose them, already taken off
         * {@link #droppedWhileLockBusy}, and a failure to resolve them could fail the caller's commit. With one current,
         * they are left for a holder of the lock with none (a dropped check on the delivering thread, or a recording).
         * Called holding the lock; costs one {@code isEmpty()} check when nothing was dropped without it
         *
         * @return the dropped events to acknowledge to the delegate's poll; empty when a unit of work is current
         */
        private List<PersistedEvent> settleDroppedWhileLockBusyWithNoUnitOfWorkCurrent() {
            if (droppedWhileLockBusy.isEmpty() || TransactionProbe.isUnitOfWorkActive(unitOfWorkFactory)) return List.of();
            return settleDroppedWhileLockBusy();
        }

        /**
         * Acknowledges the events {@link #settleDroppedWhileLockBusy()} returned to the delegate's poll, not holding
         * {@link #deferredGapsLock}. A failure is logged rather than thrown: the caller is settling events another call
         * dropped, and the delegate's poll is left waiting for them - as it was before they were settled
         */
        private void acknowledgeDroppedToDelegate(List<PersistedEvent> notHeld) {
            if (notHeld.isEmpty()) return;
            try {
                delegateAcknowledgement.acknowledge(notHeld);
            } catch (RuntimeException e) {
                log.warn("[{}-{}] Could not acknowledge the events {} dropped while the gate lock was busy to the delegate's poll",
                         subscriberId.orElseThrow(), aggregateType, notHeld.stream().map(event -> event.globalEventOrder().longValue()).toList(), e);
            }
        }

        /**
         * Takes {@link #deferredGapsLock} for a check on the delivering thread: waits for it at most
         * {@code deferredGapsRecordingWait} - once per stall, not once per event. When that wait runs out, or the thread
         * is interrupted waiting, {@link #deliveringThreadStalled} is set, and the delivering thread's later checks only
         * try the lock, falling back at once, until a recording lets go of it ({@link #recordDeferredGaps()} clears the
         * mark) or one of these checks takes it. Only a recording blocked by a database lock holds the gate lock that
         * long, so every event that opens a gap, fills one or is dropped would otherwise wait the whole
         * {@code deferredGapsRecordingWait} again behind the same recording. Costs one volatile read when the lock is
         * free.
         *
         * @return true if taken
         */
        private boolean lockedOnDeliveringThread() {
            if (deliveringThreadStalled) {
                if (!deferredGapsLock.tryLock()) return false;
                deliveringThreadStalled = false;
                return true;
            }
            if (tryLockOrRestoreInterrupt(deferredGapsRecordingWait)) return true;
            deliveringThreadStalled = true;
            return false;
        }

        /**
         * {@link #isFillOfAnUnrecordedGapNotDoneWith} for {@link #isAwaitedAsGap}, which {@code awaitProcessed} may ask
         * inside a unit of work: only tries {@link #deferredGapsLock}, and never waits for it - when it is busy, the order
         * is answered as awaited, and {@code awaitProcessed} asks again after its poll interval, which a wait here would
         * only hold past the timeout its caller asked for
         *
         * @return true if {@code order} is the fill of a gap kept in memory that was handed on and is not done with - and
         * also when the lock was not free: still awaited, as far as the gate can tell, so the caller asks again later
         */
        boolean isAwaitedAsFillOfAnUnrecordedGap(long order) {
            if (!defersGapRecording) return false;
            if (!deferredGapsLock.tryLock()) return true;
            try {
                return unrecordedFillsNotDoneWith.containsKey(order);
            } finally {
                deferredGapsLock.unlock();
            }
        }

        /**
         * Takes {@link #deferredGapsLock} at once when it is free, whatever the thread's interrupt flag - else waits for it
         * at most {@code wait}
         *
         * @return true if taken
         * @throws InterruptedException the lock was not free, and the thread was interrupted while waiting for it
         */
        private boolean tryLockWithin(Duration wait) throws InterruptedException {
            return deferredGapsLock.tryLock() || deferredGapsLock.tryLock(wait.toNanos(), TimeUnit.NANOSECONDS);
        }

        /**
         * {@link #tryLockWithin}, for a caller that falls back when the lock is not taken: an interrupt while waiting
         * counts as not taken, and the thread's interrupt flag is set again
         *
         * @return true if taken
         */
        private boolean tryLockOrRestoreInterrupt(Duration wait) {
            try {
                return tryLockWithin(wait);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return false;
            }
        }

        /**
         * Runs on the subscriber's thread, possibly inside its unit of work: waits for {@link #deferredGapsLock} at most
         * {@code deferredGapsRecordingWait}. Never throws into the subscriber's handler: when the lock is not free within
         * the wait, or the thread is interrupted waiting for it, and a last try of it fails too, none of {@code events} is
         * resolved now - resolving waits for the gap handler's monitor, which a recording blocked inside
         * {@code addTransientGaps} holds as well. They go to {@link #acknowledgedWhileLockBusy} once the subscriber's unit
         * of work commits, or at once with none current ({@link #acknowledgedWhileLockBusyOnceCommitted}): its rollback
         * leaves the gaps of the fills among them open, so the fills are still owed after a restart. Only a recording holds
         * the lock that long, and it has already taken in every fill it records, so the recording settles them once it
         * ends: a fill of a gap kept in memory is then done with if the recording failed, and else its recorded gap is
         * resolved, in the recording's own unit of work. Events queued as the recording let go of the lock - a commit
         * after it did, say - are settled by the next acknowledgement or recording that takes it. Holding the lock inside
         * a unit of work or a Spring transaction, it leaves the fills of gaps kept in memory held until that unit of work
         * commits ({@link #doneWithOnceCommitted}), so a rollback leaves them owed, as for a recorded gap. Holding the
         * lock with no unit of work current, it also settles the events dropped
         * without it ({@link #settleDroppedWhileLockBusyWithNoUnitOfWorkCurrent()}); inside the subscriber's unit of
         * work it leaves them to a dropped check on the delivering thread or a recording.
         *
         * @return {@code events} without the fills of gaps kept in memory, which are done with from now on - or, inside a
         * unit of work, once it commits - plus the events acknowledged earlier without the lock that fill no gap kept in
         * memory; empty when the lock was not taken
         */
        private List<PersistedEvent> withoutFillsOfUnrecordedGaps(List<PersistedEvent> events) {
            if (!defersGapRecording) return events;
            // The recording may have let go of the lock as the wait ran out: tried once more, then settled as below
            if (!tryLockOrRestoreInterrupt(deferredGapsRecordingWait) && !deferredGapsLock.tryLock()) {
                acknowledgedWhileLockBusyOnceCommitted(events);
                return List.of();
            }
            List<PersistedEvent> fillsOfRecordedGaps;
            List<PersistedEvent> droppedNotHeld;
            List<PersistedEvent> heldFillsDoneWithOnceCommitted = List.of();
            try {
                var acknowledgedEarlier = settleAcknowledgedWhileLockBusy();
                List<PersistedEvent> notFillsOfUnrecordedGaps;
                if (unrecordedFillsNotDoneWith.isEmpty()) {
                    notFillsOfUnrecordedGaps = events;
                } else {
                    var notHeld = new ArrayList<PersistedEvent>(events.size());
                    var held    = new ArrayList<PersistedEvent>();
                    for (var event : events) {
                        (unrecordedFillsNotDoneWith.containsKey(event.globalEventOrder().longValue()) ? held : notHeld).add(event);
                    }
                    notFillsOfUnrecordedGaps = notHeld;
                    if (!held.isEmpty()) {
                        if (TransactionProbe.isUnitOfWorkActive(unitOfWorkFactory)) {
                            // Held until the acknowledging unit of work commits: its rollback must leave the fill owed
                            heldFillsDoneWithOnceCommitted = held;
                        } else {
                            held.forEach(event -> unrecordedFillsNotDoneWith.remove(event.globalEventOrder().longValue()));
                            noteWhatIsKept();
                        }
                    }
                }
                // After the fills just done with are forgotten: a dropped duplicate of one is no longer held
                droppedNotHeld = settleDroppedWhileLockBusyWithNoUnitOfWorkCurrent();
                if (acknowledgedEarlier.isEmpty()) {
                    fillsOfRecordedGaps = notFillsOfUnrecordedGaps;
                } else {
                    acknowledgedEarlier.addAll(notFillsOfUnrecordedGaps);
                    fillsOfRecordedGaps = acknowledgedEarlier;
                }
            } finally {
                deferredGapsLock.unlock();
            }
            if (!heldFillsDoneWithOnceCommitted.isEmpty()) {
                doneWithOnceCommitted(heldFillsDoneWithOnceCommitted);
            }
            acknowledgeDroppedToDelegate(droppedNotHeld);
            return fillsOfRecordedGaps;
        }

        /**
         * {@link #withoutFillsOfUnrecordedGaps} could not take {@link #deferredGapsLock}: {@code events} go to
         * {@link #acknowledgedWhileLockBusy} once the unit of work current on the acknowledging thread commits, and are
         * left out of it should that unit of work roll back - their recorded gaps then stay open, the transient gap being
         * the only durable record that a fill is still owed, and the subscriber's retry acknowledges them again. A Spring
         * transaction with no unit of work bound yet counts as one: a unit of work that joins it is bound for the callback
         * ({@link #unitOfWorkTheAcknowledgementCommitsWith()}). With neither current, they go there at once. Only on this
         * fallback, never per event: one lifecycle callback registration per acknowledgement that falls back, and no wait -
         * after the commit, the events are settled as {@link AcknowledgedWhileLockBusyOnceCommitted#settleCommitted} says.
         */
        private void acknowledgedWhileLockBusyOnceCommitted(List<PersistedEvent> events) {
            var unitOfWork = unitOfWorkTheAcknowledgementCommitsWith();
            if (unitOfWork.isPresent()) {
                unitOfWork.get().registerLifecycleCallbackForResource(List.copyOf(events), queuedOnceCommitted);
            } else {
                acknowledgedWhileLockBusy.addAll(events);
            }
            log.debug("[{}-{}] The gate lock was not free within {} - the acknowledged events {} are settled by the next acknowledgement or recording that holds it{}",
                      subscriberId.orElseThrow(), aggregateType, deferredGapsRecordingWait, events.stream().map(event -> event.globalEventOrder().longValue()).toList(),
                      unitOfWork.isPresent() ? ", once the acknowledging unit of work commits" : "");
        }

        /**
         * {@link #withoutFillsOfUnrecordedGaps} held the lock, inside a unit of work or a Spring transaction: the fills of
         * gaps kept in memory among the acknowledged events stay in {@link #unrecordedFillsNotDoneWith} until that unit of
         * work commits, so its rollback leaves them held - a save then records their gaps, and a restart delivers them
         * again. Only for an acknowledgement that holds such a fill: one lifecycle callback registration, no wait
         */
        private void doneWithOnceCommitted(List<PersistedEvent> heldFills) {
            var unitOfWork = unitOfWorkTheAcknowledgementCommitsWith();
            if (unitOfWork.isPresent()) {
                unitOfWork.get().registerLifecycleCallbackForResource(heldFills, queuedOnceCommitted);
            } else {
                queuedOnceCommitted.settleCommitted(heldFills);
            }
        }

        /**
         * @return the unit of work current on the acknowledging thread; under a Spring transaction with none bound yet, the
         * one {@code getOrCreateNewUnitOfWork()} binds to join it; empty with neither
         */
        private Optional<? extends UnitOfWork> unitOfWorkTheAcknowledgementCommitsWith() {
            var current = unitOfWorkFactory.getCurrentUnitOfWork();
            if (current.isPresent() || !TransactionProbe.isUnitOfWorkActive(unitOfWorkFactory)) return current;
            return Optional.of(unitOfWorkFactory.getOrCreateNewUnitOfWork());
        }

        /**
         * Settles the events an acknowledgement left to its unit of work's commit (see
         * {@link #acknowledgedWhileLockBusyOnceCommitted} and {@link #doneWithOnceCommitted}); a rollback leaves them out,
         * their gaps open and their fills held
         */
        private final class AcknowledgedWhileLockBusyOnceCommitted implements UnitOfWorkLifecycleCallback<List<PersistedEvent>> {
            @Override
            public BeforeCommitProcessingStatus beforeCommit(UnitOfWork unitOfWork, List<List<PersistedEvent>> associatedResources) {
                return BeforeCommitProcessingStatus.COMPLETED;
            }

            @Override
            public void afterCommit(UnitOfWork unitOfWork, List<List<PersistedEvent>> associatedResources) {
                associatedResources.forEach(this::settleCommitted);
            }

            /**
             * Only tries {@link #deferredGapsLock}, never waits for it: when taken, a fill among {@code events} still held
             * in {@link #unrecordedFillsNotDoneWith} is done with from now on; every other event - and all of them when the
             * lock is busy - goes to {@link #acknowledgedWhileLockBusy}, for the next acknowledgement or recording that
             * holds the lock, which a resume point save counts as work. No unit of work is used here: one that resolves a
             * recorded gap is that holder's
             */
            void settleCommitted(List<PersistedEvent> events) {
                if (!deferredGapsLock.tryLock()) {
                    acknowledgedWhileLockBusy.addAll(events);
                    return;
                }
                try {
                    for (var event : events) {
                        if (unrecordedFillsNotDoneWith.remove(event.globalEventOrder().longValue()) == null) {
                            acknowledgedWhileLockBusy.add(event);
                        }
                    }
                    noteWhatIsKept();
                } finally {
                    deferredGapsLock.unlock();
                }
            }

            @Override
            public void beforeRollback(UnitOfWork unitOfWork, List<List<PersistedEvent>> associatedResources, Throwable causeOfTheRollback) {
            }

            @Override
            public void afterRollback(UnitOfWork unitOfWork, List<List<PersistedEvent>> associatedResources, Throwable causeOfTheRollback) {
                log.debug("[{}-{}] The unit of work that acknowledged the events {} rolled back - their gaps stay open, and their fills owed",
                          subscriberId.orElseThrow(), aggregateType,
                          associatedResources.stream().flatMap(List::stream).map(event -> event.globalEventOrder().longValue()).toList());
            }

            /**
             * No pending change, as {@link GapFillsAwaitingAcknowledgement} reports for its resolutions: nothing is queued
             * before the unit of work commits
             */
            @Override
            public boolean hasPendingChanges(List<PersistedEvent> resource) {
                return false;
            }
        }

        /**
         * Records the gaps kept in memory ({@link #recordDeferredGaps()}) in a unit of work of its own, committed when this
         * returns - never in a unit of work current on the calling thread, which the gate does not own: it could roll back
         * after the gate forgot the gaps, and a resume point saved past them later would then have nothing that makes a
         * restart wait for their events. With such a unit of work, or a Spring transaction it would join
         * ({@link TransactionProbe#isUnitOfWorkActive}), the gaps are recorded on a virtual thread of their own, waited
         * for at most {@link #DEFERRED_GAPS_RECORDING_WAIT}. Recording them in that unit of work and forgetting them only
         * once it commits, as {@link GapFillsAwaitingAcknowledgement} forgets its fills, was not taken: the save window
         * closes before the caller commits, so gaps kept from then on and a fill acknowledged meanwhile would have to be
         * told apart from the recorded gaps - and the manager's saves are not expected to run inside a unit of work.
         * <p>
         * With a unit of work current, the calling thread never waits for {@link #deferredGapsLock} itself: a recording
         * that outlasted an earlier wait may still hold it, blocked by a lock that unit of work holds. If another thread
         * holds the lock, the calling thread tells an empty gate from its lock-free state
         * ({@link #recordDeferredGapsCommitted(long)}); otherwise the virtual thread finds out what there is to record, and
         * the calling thread waits for it no longer than for any recording.
         *
         * @throws InterruptedException the calling thread was interrupted while waiting - the recording goes on
         * @throws TimeoutException     the recording did not end within {@link #DEFERRED_GAPS_RECORDING_WAIT}, for example
         *                              blocked by a lock the calling thread's unit of work holds, or behind a recording
         *                              that is - it goes on, and the gaps stay kept until it commits; should it fail
         *                              instead, that is logged at WARN and the gaps stay kept for the next call
         * @throws RuntimeException     the gaps could not be recorded - they stay kept, for the next call
         */
        void recordDeferredGapsCommitted() throws InterruptedException, TimeoutException {
            recordDeferredGapsCommitted(System.nanoTime() + deferredGapsRecordingWait.toNanos());
        }

        /**
         * {@link #recordDeferredGapsCommitted()}, with the wait on a virtual thread ending at {@code waitEndsAt} (a
         * {@link System#nanoTime()} value) rather than {@link #DEFERRED_GAPS_RECORDING_WAIT} from now - so a resume point
         * save waits for all of its gates within one wait. Should less than {@link CdcEventStore#deferredGapsRecordingWaitFloor()} be
         * left of it, no recording is started: it would almost certainly outlast the wait, and go on holding
         * {@link #deferredGapsLock}.
         * <p>
         * Whether there is anything to record is checked under {@link #deferredGapsLock} when it is free. When it is not -
         * held for a moment by a delivering thread or an acknowledgement, or by a recording - the check reads
         * {@link #keepsUnrecorded} and the queues of what was kept, acknowledged or dropped without the lock, all without
         * it: all empty, this returns at once, starting no recording and throwing nothing, however little is left of the
         * wait. That read is as safe as the locked check: a resume point save raises the subscriber's save counter before
         * it calls the gates, and {@link #keptUnrecorded} sets {@link #keepsUnrecorded} before it checks that counter, so
         * a gap kept after this read is refused and recorded at once by its keeper; one kept without the lock is in
         * {@link #gapsKeptWhileLockBusy} until the lock's holder has set {@link #keepsUnrecorded}. The other holders only
         * take entries away, and settle what they take from the queues themselves. The subscription's end sets
         * {@link #ended} before it calls this, with the same effect.
         *
         * @throws TimeoutException the recording did not end before {@code waitEndsAt}, or less than
         *                          {@link CdcEventStore#deferredGapsRecordingWaitFloor()} was left of it - the gaps stay kept until a
         *                          recording commits
         */
        void recordDeferredGapsCommitted(long waitEndsAt) throws InterruptedException, TimeoutException {
            if (!defersGapRecording) return;
            if (!deferredGapsLock.tryLock()) {
                // The queue first: a holder sets keepsUnrecorded before it takes a gap out of it
                if (gapsKeptWhileLockBusy.isEmpty() && !keepsUnrecorded && acknowledgedWhileLockBusy.isEmpty() && droppedWhileLockBusy.isEmpty()) {
                    return;
                }
            } else {
                List<PersistedEvent> droppedNotHeld;
                boolean              nothingToRecord;
                try {
                    takeInGapsKeptWhileLockBusy();
                    droppedNotHeld = settleDroppedWhileLockBusyWithNoUnitOfWorkCurrent();
                    // An acknowledgement made without the lock is settled by the recording, which may resolve gaps; so is
                    // an event dropped without it that was left in the queue because a unit of work is current here
                    nothingToRecord = unrecordedGaps.isEmpty() && unrecordedFillsNotDoneWith.isEmpty() && acknowledgedWhileLockBusy.isEmpty()
                                      && droppedWhileLockBusy.isEmpty();
                } finally {
                    deferredGapsLock.unlock();
                }
                acknowledgeDroppedToDelegate(droppedNotHeld);
                if (nothingToRecord) return;
            }
            // The probe also sees a Spring transaction with no unit of work bound yet, which withUnitOfWork would join
            if (TransactionProbe.isUnitOfWorkActive(unitOfWorkFactory)) {
                var waitLeft = waitEndsAt - System.nanoTime();
                var floor    = deferredGapsRecordingWaitFloor();
                if (waitLeft < floor.toNanos()) {
                    throw new TimeoutException("Less than " + floor + " was left to record the gaps of " + aggregateType + " kept in memory");
                }
                runOnAVirtualThreadWaitingAtMost("Cdc-RecordDeferredGaps-" + aggregateType, Duration.ofNanos(waitLeft), this::recordDeferredGaps);
            } else {
                recordDeferredGaps();
            }
        }

        /**
         * Records the gaps kept in memory with the gap handler ({@link SubscriptionGapHandler#addTransientGaps}, one call per
         * run of consecutive orders), in a unit of work of its own, and hands the fills of them that are not done with to
         * {@link #gapFillsBeingHandedOn}, so the subscriber's acknowledgement resolves them as any other. Holds
         * {@link #deferredGapsLock} until committed. Only called on a thread with no unit of work or transaction to join (see
         * {@link #recordDeferredGapsCommitted()}), so the unit of work is committed before the gaps are forgotten - and the
         * one path that waits for the lock without a limit: such a thread holds no database lock a recording that holds the
         * gate lock could be waiting for.
         * <p>
         * Takes in the gaps kept and settles the events acknowledged without the lock first, so a fill done with is not
         * recorded; and again once done, recorded or not, as an acknowledgement that waited for the lock past
         * {@code deferredGapsRecordingWait} meanwhile left its events to this recording, once its unit of work committed: a fill whose gap this recorded is
         * resolved here, in a unit of work of its own. Then it settles the events dropped without the lock
         * ({@link #settleDroppedWhileLockBusy()}), clears {@link #deliveringThreadStalled} and lets go of the lock.
         *
         * @throws RuntimeException the gaps could not be recorded - they stay kept, for the next call
         */
        private void recordDeferredGaps() {
            if (!defersGapRecording) return;
            deferredGapsLock.lock();
            List<PersistedEvent> acknowledgedBefore = List.of();
            try {
                takeInGapsKeptWhileLockBusy();
                acknowledgedBefore = settleAcknowledgedWhileLockBusy();
                if (unrecordedGaps.isEmpty() && unrecordedFillsNotDoneWith.isEmpty()) return;
                var orders = new TreeSet<>(unrecordedGaps);
                orders.addAll(unrecordedFillsNotDoneWith.keySet());
                var runs    = runsOf(orders);
                var handler = gapHandler.get();
                var reconciliation = unitOfWorkFactory.withUnitOfWork(uow -> {
                    synchronized (handler) {
                        var recorded = GapReconciliation.NONE;
                        for (var run : runs) {
                            recorded = recorded.plus(handler.addTransientGaps(aggregateType, run));
                        }
                        return recorded;
                    }
                });
                var fills = List.copyOf(unrecordedFillsNotDoneWith.values());
                unrecordedGaps.clear();
                unrecordedFillsNotDoneWith.clear();
                keepsUnrecorded = false;
                if (!fills.isEmpty()) {
                    gapFillsBeingHandedOn.ifPresent(gapFills -> gapFills.awaitAcknowledgement(fills));
                }
                log.debug("[{}-{}] Recorded the gap(s) {} kept in memory, before a resume point save", handler.subscriberId(), aggregateType, runs);
                if (!reconciliation.isEmpty()) {
                    eventStore.getEventStoreSubscriptionObserver().gapReconciliationOutcome(handler.subscriberId(), aggregateType, reconciliation);
                }
            } finally {
                List<PersistedEvent> droppedNotHeld;
                try {
                    takeInGapsKeptWhileLockBusy();
                    var acknowledged = new ArrayList<>(acknowledgedBefore);
                    acknowledged.addAll(settleAcknowledgedWhileLockBusy());
                    if (!acknowledged.isEmpty()) {
                        // No unit of work on this thread: resolved in one of its own, a failure logged and the gaps left open
                        gapFillsBeingHandedOn.ifPresent(gapFills -> gapFills.acknowledged(acknowledged));
                    }
                    // After the acknowledgements: a held fill acknowledged meanwhile is no longer held when its dropped
                    // duplicate is settled
                    droppedNotHeld = settleDroppedWhileLockBusy();
                } finally {
                    // The delivering thread's stall, if any, ends with this recording
                    deliveringThreadStalled = false;
                    deferredGapsLock.unlock();
                }
                acknowledgeDroppedToDelegate(droppedNotHeld);
            }
        }

        /**
         * The subscription ended: the gaps kept in memory are recorded now - a later save of the resume point, which may be
         * past them, finds this gate gone. In a unit of work of its own ({@link #recordDeferredGapsCommitted()}): with a unit
         * of work, or a Spring transaction it would join, current on the ending thread, on a virtual thread of its own,
         * waited for at most {@link #DEFERRED_GAPS_RECORDING_WAIT} - the ending thread's unit of work may hold a lock that
         * recording needs, a wait PostgreSQL cannot see as a deadlock. A failure, or a wait that runs out, is logged and
         * the subscription ends: a restart from a resume point saved past those gaps before they are recorded then does
         * not wait for them - as when recording an opened gap fails. A recording that outlasts the wait goes on, and
         * records them when it commits. {@link #ended} is set without {@link #deferredGapsLock}, which a recording that
         * outlasted an earlier wait may hold: a gap kept before is recorded here, as this takes that lock afterwards, and
         * one about to be kept is refused
         */
        private void recordDeferredGapsAtEnd() {
            ended = true;
            try {
                recordDeferredGapsCommitted();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                log.warn("[{}-{}] Interrupted while recording the gaps kept in memory as the subscription ended - a restart from a resume point saved past them will not wait for them",
                         subscriberId.orElseThrow(), aggregateType, e);
            } catch (TimeoutException e) {
                log.warn("[{}-{}] Recording the gaps kept in memory as the subscription ended did not finish within {} - ending the subscription; " +
                                 "a restart from a resume point saved past them before they are recorded will not wait for them",
                         subscriberId.orElseThrow(), aggregateType, deferredGapsRecordingWait, e);
            } catch (RuntimeException e) {
                log.warn("[{}-{}] Could not record the gaps kept in memory as the subscription ended - a restart from a resume point saved past them will not wait for them",
                         subscriberId.orElseThrow(), aggregateType, e);
            }
        }

        /**
         * The subscription ends - cancelled, completed or failed: the gaps the tracker gave up on since the last event
         * delivered are recorded now ({@link #recordGivenUpGaps(boolean)}), or a subscription stopped while idle left them to a
         * later one to wait for again. Runs on the thread that ends it - for a cancel, the one that stops the
         * subscription. Should a unit of work be current there, they are left unrecorded rather than written in a unit
         * of work the gate does not own: a later subscription then waits for those gaps once more, which is safe.
         * <p>
         * First, and always, the middles of wide gaps the tracker still awaits in memory only - and the fills from them
         * the subscriber is not done with - are left for the subscriber's next subscription on this instance
         * ({@link CdcDeliveryTracker#handOverMiddlesAwaitedInMemoryOnly()}), which starts at the resume point, above them.
         * A cancel runs this before the subscription that is stopped or resumed subscribes again. Only after the hand-over
         * does {@link #isAwaitedAsGap} stop asking the tracker, so there is no moment neither of them answers for a middle.
         */
        void subscriptionEnded() {
            if (defersGapRecording) {
                try {
                    recordDeferredGapsAtEnd();
                } finally {
                    runningDeliveryGates.computeIfPresent(key(), (key, gates) -> {
                        gates.remove(this);
                        return gates.isEmpty() ? null : gates;
                    });
                }
            }
            try {
                // In memory only, so whatever the thread: the middles of wide gaps still awaited, for the next subscription
                tracker.handOverMiddlesAwaitedInMemoryOnly();
                gapMiddlesAwaitedAcrossSubscribes.subscribeEnded(tracker.earlierMiddles());
            } finally {
                subscriberId.ifPresent(id -> deliveryTrackerEnded(id, aggregateType, tracker));
            }
            if (gapHandler.isEmpty()) return;
            if (unitOfWorkFactory.getCurrentUnitOfWork().isPresent()) {
                log.debug("[{}-{}] Subscription ended inside a unit of work - not recording the gaps given up on since the last event delivered",
                          gapHandler.get().subscriberId(), aggregateType);
                return;
            }
            // No unit of work current here, so no database lock of this thread's can block a recording that holds the gate
            // lock: this may wait for it, at most deferredGapsRecordingWait, as there is no next event to try again
            recordGivenUpGaps(true);
        }

        /**
         * The gaps the tracker gave up on after waiting {@code gapTimeout} for them, recorded with the gap handler
         * ({@link SubscriptionGapHandler#giveUpTransientGaps}): the transient gap is what a later subscription of the
         * subscriber waits for (see {@code newDeliveryTracker}). Left open, a restarted subscription waited for a gap this
         * one had given up on - and delivered its event, should its transaction commit late, although this subscription
         * dropped it. Only gaps waited for that long - the gap handler's {@code transientGapGiveUpThreshold()}, which the
         * tracker's {@code gapTimeout} follows - as giving a gap up makes it a permanent gap for every subscriber of the
         * aggregate type; a gap the tracker gives up on because of its cap is never drained (see
         * {@link CdcDeliveryTracker}). Every reconciliation here and in the delegate's polls is given the gap fills
         * awaiting acknowledgement, and a given-up order is none, so this never promotes a gap the subscriber is still
         * owed. In a unit of work of its own, holding the gap handler's monitor, never across the commit; a failure is
         * logged and the orders are handed back to the tracker, so the next call tries them again.
         * <p>
         * The middles of wide gaps, awaited in memory only, are drained too, and nothing is written for them: they were
         * never recorded as transient gaps (see {@link CdcDeliveryTracker#MAX_AWAITED_ORDERS_PER_GAP_END}).
         *
         * @param mayWait whether {@link #withoutUnrecordedGaps} may wait for {@link #deferredGapsLock}: false on the
         *                delivering thread, true at the subscription's end
         */
        private void recordGivenUpGaps(boolean mayWait) {
            if (gapHandler.isEmpty()) return;
            var givenUpInMemoryOnly = tracker.drainTimedOutGapsAwaitedInMemoryOnly();
            if (!givenUpInMemoryOnly.isEmpty()) {
                log.debug("[{}-{}] Gave up the gap(s) {}, awaited in memory only - nothing to record",
                          gapHandler.get().subscriberId(), aggregateType, givenUpInMemoryOnly);
            }
            // Ranges, one per gap end: the gap handler promotes each range in one go, not order by order
            var givenUp = withoutUnrecordedGaps(tracker.drainTimedOutGaps(), mayWait);
            if (givenUp.isEmpty()) return;
            var handler = gapHandler.get();
            try {
                var reconciliation = unitOfWorkFactory.withUnitOfWork(uow -> {
                    synchronized (handler) {
                        return handler.giveUpTransientGapRanges(aggregateType, givenUp);
                    }
                });
                if (!reconciliation.isEmpty()) {
                    eventStore.getEventStoreSubscriptionObserver().gapReconciliationOutcome(handler.subscriberId(), aggregateType, reconciliation);
                }
            } catch (RuntimeException e) {
                tracker.requeueTimedOutGaps(givenUp);
                log.warn("[{}-{}] Could not record with the gap handler that the gap(s) {} were given up on - trying again with the next event delivered, else a later subscription waits for them again",
                         handler.subscriberId(), aggregateType, givenUp, e);
            }
        }

        /**
         * Forgets the given-up orders kept in memory: never recorded, there is nothing to give up - a resume point is never
         * saved past a gap that is not recorded, so a restart reads its orders again. Leaves out a range kept in memory
         * whole, so giving it up costs no statement. On the delivering thread ({@code mayWait} false) it never waits for
         * {@link #deferredGapsLock}: when it is taken, {@code givenUp} goes back to the tracker
         * ({@link CdcDeliveryTracker#requeueTimedOutGaps}) and nothing is given up now - the next event delivered, or the
         * subscription's end, tries again. At the subscription's end ({@code mayWait} true) there is no next event, so it
         * waits for the lock at most {@code deferredGapsRecordingWait} - an acknowledgement or a check holds it only
         * briefly - and only tries it while {@link #deliveringThreadStalled} says a blocked recording holds it. Not taken,
         * {@code givenUp} goes back to the tracker, and a later subscription waits for those gaps again.
         *
         * @param mayWait false on the delivering thread, true at the subscription's end, with no unit of work current
         */
        private List<LongRange> withoutUnrecordedGaps(List<LongRange> givenUp, boolean mayWait) {
            if (!defersGapRecording || givenUp.isEmpty()) return givenUp;
            var taken = mayWait && !deliveringThreadStalled
                        ? tryLockOrRestoreInterrupt(deferredGapsRecordingWait)
                        : deferredGapsLock.tryLock();
            if (!taken) {
                tracker.requeueTimedOutGaps(givenUp);
                return List.of();
            }
            try {
                takeInGapsKeptWhileLockBusy();
                if (unrecordedGaps.isEmpty()) return givenUp;
                var toRecord = new ArrayList<LongRange>(givenUp.size());
                for (var range : givenUp) {
                    var kept  = unrecordedGaps.subSet(range.fromInclusive, true, range.toInclusive, true);
                    long width = range.toInclusive - range.fromInclusive + 1;
                    var allKept = kept.size() == width;
                    kept.clear();
                    if (!allKept) toRecord.add(range);
                }
                noteWhatIsKept();
                return toRecord;
            } finally {
                deferredGapsLock.unlock();
            }
        }

        /**
         * No query asked for any transient gap - the event came from the bus or a poll, not from a query of this gap
         * handler's - so none is passed as included in one: the gap handler promotes a gap only when a query that asked
         * for it did not get it, and asked for nothing, it changes no other gap either (it resolves the gaps a query asked
         * for and got). The gaps the tracker gives up on after its timeout are given up through {@link #recordGivenUpGaps(boolean)}
         * instead.
         * <p>
         * Only the orders the tracker awaits durably are recorded ({@code awaitedGaps}, see
         * {@link CdcDeliveryTracker#MAX_AWAITED_ORDERS_PER_GAP_END}): the gap right below the event, through the
         * reconciliation, and - for a gap so wide the tracker awaits its middle in memory only - its lower end through
         * {@link SubscriptionGapHandler#addTransientGaps}. The middle is recorded nowhere: the subscriber's resume point
         * moves past it with this event, so a restart within the gap timeout does not wait for it - the same as for a gap
         * the tracker gives up because of its cap.
         */
        private void recordOpenedGap(PersistedEvent event, List<LongRange> awaitedGaps) {
            if (gapHandler.isEmpty()) return;
            var handler   = gapHandler.get();
            long order    = event.globalEventOrder().longValue();
            var  belowIt  = awaitedGaps.getLast();
            var  lowerEnd = awaitedGaps.size() > 1 ? Optional.of(awaitedGaps.getFirst()) : Optional.<LongRange>empty();
            var  range    = LongRange.between(belowIt.fromInclusive, order);
            try {
                var reconciliation = unitOfWorkFactory.withUnitOfWork(uow -> {
                    synchronized (handler) {
                        var recorded = handler.reconcileGapsAndReport(aggregateType, range, withAwaitingAcknowledgement(List.of(event)), List.of());
                        return lowerEnd.map(gaps -> recorded.plus(handler.addTransientGaps(aggregateType, gaps))).orElse(recorded);
                    }
                });
                if (!reconciliation.isEmpty()) {
                    eventStore.getEventStoreSubscriptionObserver().gapReconciliationOutcome(handler.subscriberId(), aggregateType, reconciliation);
                }
            } catch (RuntimeException e) {
                log.warn("[{}-{}] Could not record the gap {} with the gap handler - delivering global order {} regardless; a restart before the gap is filled will not wait for it",
                         handler.subscriberId(), aggregateType, awaitedGaps, event.globalEventOrder(), e);
            }
        }

        /**
         * {@code events}, plus the gap fills awaiting acknowledgement that are not among them - for a reconciliation, so
         * it does not promote their gaps (see {@link GapFillsAwaitingAcknowledgement#withAwaitingEvents})
         */
        List<PersistedEvent> withAwaitingAcknowledgement(List<PersistedEvent> events) {
            return gapFillsBeingHandedOn.map(gapFills -> gapFills.withAwaitingEvents(events)).orElse(events);
        }
    }

    /**
     * Tenant predicate mirroring the base store's SQL "({tenantColumn} IS NULL OR {tenantColumn} =
     * :tenant)", as {@link PostgresqlEventStore}'s in-memory tenant filter does: a tenant-less event belongs to every
     * tenant (absent event-tenant ⇒ kept), and an event's tenant matches when it serializes to the same column value as
     * the wanted one under the aggregate type's configured {@link TenantSerializer} - so the CDC paths deliver the same
     * events as polling for a custom serializer. An absent subscriber tenant filter keeps everything. See
     * {@link TenantFilter} for an aggregate type that has no configuration (yet).
     */
    private Predicate<PersistedEvent> belongsToTenant(AggregateType aggregateType, Optional<Tenant> onlyIncludeEventIfItBelongsToTenant) {
        if (onlyIncludeEventIfItBelongsToTenant.isEmpty()) {
            return event -> true;
        }
        return new TenantFilter(aggregateType, onlyIncludeEventIfItBelongsToTenant.get());
    }

    /**
     * {@link #belongsToTenant} for one wanted tenant. The aggregate type's {@link TenantSerializer} is looked up when the
     * first event with a tenant is tested, not when the subscription is built, and kept once found - with the wanted
     * tenant's column value, serialized once. An aggregate type can be configured at runtime, and the CDC bus delivers
     * an aggregate type's events whether or not it is configured on this node. While it is not, the tenants'
     * {@code toString()} are compared - what the default {@code TenantIdSerializer} writes - and a WARN naming the
     * aggregate type is logged once. Not thrown as the polling path's lookup does: in the filter, that would end the
     * subscription rather than let it pick up the configuration once it is added.
     */
    private final class TenantFilter implements Predicate<PersistedEvent> {
        /**
         * The aggregate type's tenant serializer and the wanted tenant's column value under it
         */
        @SuppressWarnings("rawtypes")
        private record Resolved(TenantSerializer tenantSerializer, String wantedColumnValue) {
            @SuppressWarnings("unchecked")
            boolean matches(Tenant eventTenant) {
                return Objects.equals(tenantSerializer.serialize(eventTenant), wantedColumnValue);
            }
        }

        private final    AggregateType aggregateType;
        private final    Tenant        wantedTenant;
        private final    String        wantedTenantToString;
        private final    AtomicBoolean noConfigurationLogged = new AtomicBoolean();
        /** Null until the aggregate type's configuration was found */
        private volatile Resolved      resolved;

        private TenantFilter(AggregateType aggregateType, Tenant wantedTenant) {
            this.aggregateType = aggregateType;
            this.wantedTenant = wantedTenant;
            this.wantedTenantToString = wantedTenant.toString();
        }

        @Override
        public boolean test(PersistedEvent event) {
            var eventTenant = event.tenant();
            if (eventTenant.isEmpty()) {
                return true;
            }
            var resolved = resolve();
            return resolved != null
                   ? resolved.matches(eventTenant.get())
                   : Objects.equals(eventTenant.get().toString(), wantedTenantToString);
        }

        /**
         * @return null while the aggregate type has no configuration
         */
        @SuppressWarnings({"unchecked", "rawtypes"})
        private Resolved resolve() {
            var current = resolved;
            if (current != null) {
                return current;
            }
            var configuration = eventStore.findAggregateEventStreamConfiguration(aggregateType);
            if (configuration.isEmpty()) {
                if (noConfigurationLogged.compareAndSet(false, true)) {
                    log.warn("[{}] No AggregateEventStreamConfiguration for the aggregate type - filtering the events of tenant '{}' by comparing the tenants' toString() until one is added, which a custom TenantSerializer may not agree with",
                             aggregateType, wantedTenant);
                }
                return null;
            }
            TenantSerializer tenantSerializer = configuration.get().tenantSerializer;
            current = new Resolved(tenantSerializer, tenantSerializer.serialize(wantedTenant));
            resolved = current;
            return current;
        }
    }

    /**
     * Apply the subscriber tenant filter to a stream. Used on the ordered output of the CDC ACTIVE path:
     * filtering must happen AFTER ordering, because the delivery tracker behind BackfillThenLiveOrdered has to see
     * every global order - an event removed upstream of it would be a gap it records and waits for.
     */
    private Flux<PersistedEvent> filterByTenant(Flux<PersistedEvent> source, AggregateType aggregateType, Optional<Tenant> onlyIncludeEventIfItBelongsToTenant) {
        if (onlyIncludeEventIfItBelongsToTenant.isEmpty()) {
            return source;
        }
        return source.filter(belongsToTenant(aggregateType, onlyIncludeEventIfItBelongsToTenant));
    }

    /**
     * {@code source}, telling {@code recorder} about each event once the downstream took it: after the downstream's
     * {@code onNext} returned (see {@link DeliveryRecorder#handedOn}). For a subscriber that does not acknowledge what it
     * handled (see {@link #handOn}): one that handles an event synchronously in its {@code onNext} has handled it by then.
     * Not fusable, so a fusing downstream cannot take events past it.
     */
    static Flux<PersistedEvent> reportingHandedOn(Flux<PersistedEvent> source, DeliveryRecorder recorder) {
        requireNonNull(recorder, "No recorder provided");
        // hide(): lifted over a fuseable source, the operator would be fuseable too, and a fusing downstream would cast the
        // HandedOnReporter - which is no QueueSubscription - to one
        return source.hide().transform(Operators.<PersistedEvent, PersistedEvent>lift((scannable, actual) -> new HandedOnReporter(actual, recorder)));
    }

    private static final class HandedOnReporter implements CoreSubscriber<PersistedEvent>, Subscription {
        private final CoreSubscriber<? super PersistedEvent> actual;
        private final DeliveryRecorder                       recorder;
        private       Subscription                           upstream;
        /**
         * Once cancelled, an event the downstream is still taking may not get handled - its gap stays open
         */
        private volatile boolean                             cancelled;

        private HandedOnReporter(CoreSubscriber<? super PersistedEvent> actual, DeliveryRecorder recorder) {
            this.actual = actual;
            this.recorder = recorder;
        }

        @Override
        public reactor.util.context.Context currentContext() {
            return actual.currentContext();
        }

        @Override
        public void onSubscribe(Subscription subscription) {
            if (Operators.validate(upstream, subscription)) {
                upstream = subscription;
                // This, not the upstream subscription: a fusing downstream must not poll events past onNext below
                actual.onSubscribe(this);
            }
        }

        @Override
        public void onNext(PersistedEvent event) {
            actual.onNext(event);
            if (!cancelled) {
                recorder.handedOn(event);
            }
        }

        @Override
        public void onError(Throwable throwable) {
            actual.onError(throwable);
        }

        @Override
        public void onComplete() {
            actual.onComplete();
        }

        @Override
        public void request(long n) {
            upstream.request(n);
        }

        @Override
        public void cancel() {
            cancelled = true;
            upstream.cancel();
        }
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
            DeliveryGate deliveryGate,
            Supplier<List<LongRange>> awaitedOnFirstPage
                                             ) {
        return Flux.using(() -> Schedulers.newSingle("CDC-Backfill-" + aggregateType, true),
                          scheduler -> backfillFlux(aggregateType, fromInclusive, headInclusive, pageSize, tenant, deliveryGate, awaitedOnFirstPage, scheduler),
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
     * @param awaitedOnFirstPage  the ranges below {@code fromInclusive} a subscription still waits for (see
     *                            {@link CdcDeliveryTracker#awaitedRanges}) - every event within them is loaded before the
     *                            first page ({@link #loadEventsWithin}) and handed out first. Read on the worker. Loaded
     *                            even when there is nothing past {@code fromInclusive} up to the head
     */
    private Flux<PersistedEvent> backfillFlux(
            AggregateType aggregateType,
            GlobalEventOrder fromInclusive,
            LongSupplier headInclusive,
            int pageSize,
            Optional<Tenant> tenant,
            DeliveryGate deliveryGate,
            Supplier<List<LongRange>> awaitedOnFirstPage,
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
                            loaded.addAll(loadEventsWithin(aggregateType, awaitedOnFirstPage.get()));
                            if (!loaded.isEmpty()) {
                                continue;
                            }
                        }
                        if (start > head[0]) {
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
                                        deliveryGate,
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
     * Every event within {@code awaited} - the gaps a subscription still waits for - all tenants, ascending: an event that
     * filled one of them while the subscription was off the bus is only in the database. Not one statement per order:
     * ranges up to {@link #MAX_AWAITED_ORDERS_PER_STATEMENT} wide are listed, that many orders per statement; a wider one
     * is read as a range. Awaited orders hold no event but a late commit's, so what is read is what is loaded. In one unit
     * of work, joining one current on the calling thread
     */
    List<PersistedEvent> loadEventsWithin(AggregateType aggregateType, List<LongRange> awaited) {
        requireNonNull(aggregateType, "No aggregateType provided");
        requireNonNull(awaited, "No awaited provided");
        if (awaited.isEmpty()) return List.of();
        return unitOfWorkFactory.withUnitOfWork(uow -> {
            var events = new TreeMap<Long, PersistedEvent>();
            var orders = new ArrayList<GlobalEventOrder>(MAX_AWAITED_ORDERS_PER_STATEMENT);
            Runnable loadListed = () -> {
                if (orders.isEmpty()) return;
                eventStore.loadEventsByGlobalOrder(aggregateType, LongRange.only(orders.getFirst().longValue()), List.copyOf(orders), Optional.<Tenant>empty())
                          .forEach(event -> events.put(event.globalEventOrder().longValue(), event));
                orders.clear();
            };
            for (var range : awaited) {
                long width = range.toInclusive - range.fromInclusive + 1;
                if (width > MAX_AWAITED_ORDERS_PER_STATEMENT) {
                    eventStore.loadEventsByGlobalOrder(aggregateType, range, List.of(), Optional.<Tenant>empty())
                              .forEach(event -> events.put(event.globalEventOrder().longValue(), event));
                    continue;
                }
                for (long order = range.fromInclusive; order <= range.toInclusive; order++) {
                    orders.add(GlobalEventOrder.of(order));
                    if (orders.size() == MAX_AWAITED_ORDERS_PER_STATEMENT) loadListed.run();
                }
            }
            loadListed.run();
            return List.copyOf(events.values());
        });
    }

    /**
     * Load one page {@code [fromInclusive .. min(head, fromInclusive + pageSize - 1)]}, plus the transient gaps the gap
     * handler includes and {@code alsoLoad}, by order, and reconcile the gaps in it with the subscription's gap handler -
     * except the transient gaps its events fill, which the {@link DeliveryGate} the events pass resolves once the
     * subscriber is done with them.
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
            DeliveryGate deliveryGate,
            List<GlobalEventOrder> alsoLoad,
            Consumer<PersistedEvent> emit
                                                 ) {
        var gapHandler = deliveryGate.gapHandler;
        long toInclusive = Math.min(headInclusive, fromInclusive + pageSize - 1);
        var  range       = LongRange.between(fromInclusive, toInclusive);
        long startNs     = System.nanoTime();

        var gapReconciliation = new AtomicReference<>(GapReconciliation.NONE);
        List<PersistedEvent> loaded =
                unitOfWorkFactory.withUnitOfWork(uow -> {
                    List<GlobalEventOrder> transientGaps =
                            gapHandler.map(h -> {
                                          synchronized (h) {
                                              return h.findTransientGapsToIncludeInQuery(aggregateType, range);
                                          }
                                      })
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
                    // ... except that the gaps its events fill stay open: the page is handed on later, as the downstream
                    // asks, and every event passes the DeliveryGate, which resolves a gap once the subscriber is done with
                    // its fill. Given the fills still awaiting that too, so their gaps are not promoted
                    var loadedGlobalOrders = events.stream().map(PersistedEvent::globalEventOrder).collect(Collectors.toSet());
                    var gapsNotFilled      = transientGaps.stream().filter(gap -> !loadedGlobalOrders.contains(gap)).toList();
                    gapHandler.ifPresent(h -> {
                        synchronized (h) {
                            gapReconciliation.set(h.reconcileGapsAndReport(aggregateType, range, deliveryGate.withAwaitingAcknowledgement(eventsForGapHandler), gapsNotFilled));
                        }
                    });
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
    public Optional<PersistedEvent> findEvent(EventId eventId) {
        return eventStore.findEvent(eventId);
    }

    @Override
    public List<PersistedEvent> loadEventsCausedBy(LoadEventsCausedBy operation) {
        return eventStore.loadEventsCausedBy(operation);
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

    /**
     * Forgets the middles of wide gaps the subscriber's CDC subscriptions await in memory only, and those of the delegate's
     * polls - see {@link GapMiddlesAwaitedAcrossSubscribes}
     */
    @Override
    public void forgetGapMiddlesAwaitedInMemory(SubscriberId subscriberId, AggregateType aggregateType) {
        gapMiddlesAwaitedAcrossSubscribes.forget(subscriberId, aggregateType);
        eventStore.forgetGapMiddlesAwaitedInMemory(subscriberId, aggregateType);
    }

    /**
     * True when {@code globalEventOrder} lies in the middle of a wide gap a CDC subscription of the subscriber took over
     * from an earlier one, or left for the next ({@link GapMiddlesAwaitedAcrossSubscribes}); when the subscriber's running
     * CDC subscription still awaits it - a gap its {@link CdcDeliveryTracker} waits for, the middle of a wide gap it found
     * itself and awaits in memory only among them, or a fill from such a middle the subscriber is not done with
     * ({@link CdcDeliveryTracker#isAwaited}); when it is one of the subscriber's transient gaps recorded by this event
     * store's gap handler, the one its CDC subscriptions record gaps with; or when the delegate - which serves the
     * subscription while CDC is not active - is an {@link AwaitedGapInspector} that awaits it. A delegate that is no
     * {@link AwaitedGapInspector} is reported with a WARN when this event store is built: then the middles of wide gaps
     * its polls await in memory only go unanswered, so the answer is complete only while CDC is active.
     * <p>
     * The transient gaps are read once per call: when the delegate reports this event store's own gap handler as the one
     * its answer reads them from ({@link AwaitedGapInspector#transientGapsReadFrom()}, compared by identity) - a
     * {@link PostgresqlEventStore} as wired by default, or a decorator that forwards to one - this one does not read them
     * too. A delegate that reports another gap handler, or none (the default), has them read here as well: the answer is
     * the same, only the read may be duplicated.
     * <p>
     * Asks a running subscription's tracker under the tracker's monitor, which its delivering threads take for every event
     * - briefly, and never the bus thread's. A running subscription's held fills are checked under its delivery gate's lock,
     * which is only tried, never waited for: {@code awaitProcessed} checks its timeout only between calls, so a wait here
     * would hold its caller past that timeout. When a gate's lock is not free (a deferred-gap recording may hold it, or a
     * delivering thread or an acknowledgement for a moment), the order is answered as still awaited, so
     * {@code awaitProcessed} reports it as not yet processed and asks again after its poll interval. That answer is
     * conservative: while a recording holds the lock, every order of the subscriber and aggregate type is answered as
     * awaited - also one no gap or held fill is about - until that recording ends.
     */
    @Override
    public boolean isAwaitedAsGap(SubscriberId subscriberId, AggregateType aggregateType, GlobalEventOrder globalEventOrder) {
        requireNonNull(subscriberId, "No subscriberId provided");
        requireNonNull(aggregateType, "No aggregateType provided");
        requireNonNull(globalEventOrder, "No globalEventOrder provided");
        return gapMiddlesAwaitedAcrossSubscribes.isAwaited(subscriberId, aggregateType, globalEventOrder.longValue()) ||
                isAwaitedByARunningSubscription(subscriberId, aggregateType, globalEventOrder.longValue()) ||
                (recordsGaps() && !delegateReadsTheSameTransientGaps() &&
                        eventStreamGapHandler.gapHandlerFor(subscriberId).getTransientGapsFor(aggregateType).contains(globalEventOrder)) ||
                (eventStore instanceof AwaitedGapInspector delegate && delegate.isAwaitedAsGap(subscriberId, aggregateType, globalEventOrder));
    }

    /**
     * @return whether the delegate's {@link #isAwaitedAsGap} answer already covers the transient gaps of this event store's
     * own gap handler - asked on every call, as a decorating delegate forwards the question to whatever it decorates
     */
    private boolean delegateReadsTheSameTransientGaps() {
        return eventStore instanceof AwaitedGapInspector delegate &&
                delegate.transientGapsReadFrom().filter(gapHandler -> gapHandler == eventStreamGapHandler).isPresent();
    }

    /**
     * @return this event store's gap handler, when it records gaps - {@link #isAwaitedAsGap} answers from its transient
     * gaps, read here or by the delegate - else whatever the delegate reports, as {@link #isAwaitedAsGap} forwards to it
     */
    @Override
    public Optional<EventStreamGapHandler<?>> transientGapsReadFrom() {
        if (recordsGaps()) {
            return Optional.of(eventStreamGapHandler);
        }
        return eventStore instanceof AwaitedGapInspector delegate ? delegate.transientGapsReadFrom() : Optional.empty();
    }

    private boolean isAwaitedByARunningSubscription(SubscriberId subscriberId, AggregateType aggregateType, long globalEventOrder) {
        var key      = new SubscriberAndAggregateType(subscriberId, aggregateType);
        var trackers = runningDeliveryTrackers.get(key);
        if (trackers != null && trackers.stream().anyMatch(tracker -> tracker.isAwaited(globalEventOrder))) {
            return true;
        }
        // A fill of a gap kept in memory, handed on and not done with - no transient gap records it. Each gate's lock is
        // only tried, never waited for
        var gates = runningDeliveryGates.get(key);
        if (gates == null) return false;
        return gates.stream().anyMatch(gate -> gate.isAwaitedAsFillOfAnUnrecordedGap(globalEventOrder));
    }

    @Override
    public Flux<PersistedEvent> unboundedPollForEvents(AggregateType aggregateType, long fromInclusiveGlobalOrder, Optional<Integer> loadEventsByGlobalOrderBatchSize, Optional<Duration> pollingInterval, Optional<Tenant> onlyIncludeEventIfItBelongsToTenant, Optional<SubscriberId> subscriptionId) {
        return eventStore.unboundedPollForEvents(aggregateType, fromInclusiveGlobalOrder, loadEventsByGlobalOrderBatchSize, pollingInterval, onlyIncludeEventIfItBelongsToTenant, subscriptionId);
    }

    /**
     * Polls the delegate, as {@link #unboundedPollForEvents(AggregateType, long, Optional, Optional, Optional, Optional)}
     * does, which honours the acknowledgement if the delegate does
     */
    @Override
    public Flux<PersistedEvent> unboundedPollForEvents(AggregateType aggregateType, long fromInclusiveGlobalOrder, Optional<Integer> loadEventsByGlobalOrderBatchSize, Optional<Duration> pollingInterval, Optional<Tenant> onlyIncludeEventIfItBelongsToTenant, Optional<SubscriberId> subscriptionId,
                                                       SubscriberAcknowledgement acknowledgement) {
        return eventStore.unboundedPollForEvents(aggregateType, fromInclusiveGlobalOrder, loadEventsByGlobalOrderBatchSize, pollingInterval, onlyIncludeEventIfItBelongsToTenant, subscriptionId, acknowledgement);
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
         * Called once the downstream took {@code event} - its {@code onNext} returned - after {@link #deliver} let it
         * through. Not called for an event the subscription was cancelled under before it got there
         */
        default void handedOn(PersistedEvent event) {
        }

        /**
         * Called for an event left out because it was handed downstream before (or given up on) - after
         * {@link #isDelivered} said so; {@link #deliver} sees to it itself when it returns false
         */
        default void dropped(PersistedEvent event) {
        }

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
                // Everything that is emitted on orderedLiveSink - events, the completion and an error - is emitted by the
                // one thread that is draining, so the sink (which rejects concurrent emission with FAIL_NON_SERIALIZED)
                // never sees two emitters. An error used to be emitted straight from liveSub's thread, racing a drain
                // running on the backfill's: the rejected tryEmitError was ignored, and the error - and with it the
                // subscription's end - was lost. Now it is only recorded here, and the drain emits it, whoever gets to.
                AtomicReference<Throwable> liveFailure = new AtomicReference<>();
                AtomicBoolean              closed      = new AtomicBoolean(false);
                AtomicBoolean              terminated  = new AtomicBoolean(false);
                Runnable drain = () -> {
                    if (drainsMissed.getAndIncrement() != 0) return;
                    int missed = 1;
                    while (true) {
                        if (!terminated.get()) {
                            var failure = liveFailure.get();
                            if (failure != null) {
                                terminated.set(true);
                                buffer.clear();
                                if (bufferSizeGauge != null) bufferSizeGauge.set(0);
                                var result = orderedLiveSink.tryEmitError(failure);
                                if (result.isFailure() && result != Sinks.EmitResult.FAIL_TERMINATED && result != Sinks.EmitResult.FAIL_CANCELLED) {
                                    LOG.error("Could not pass the live source's failure on to the subscription ({})", result, failure);
                                }
                            } else if (closed.get()) {
                                terminated.set(true);
                                orderedLiveSink.tryEmitComplete();
                            } else if (backfillDone.get()) {
                                Map.Entry<Long, PersistedEvent> next;
                                while ((next = buffer.pollFirstEntry()) != null) {
                                    if (bufferSizeGauge != null) bufferSizeGauge.decrementAndGet();
                                    emitUnlessDelivered.accept(next.getValue());
                                }
                                if (liveDone.get() && buffer.isEmpty()) {
                                    terminated.set(true);
                                    orderedLiveSink.tryEmitComplete();
                                }
                            }
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
                            deliveries.dropped(ev);
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
                        // Emitted by the drain, never from here - see drain
                        liveFailure.compareAndSet(null, err);
                        drain.run();
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
                               // Through the drain, which may be emitting an event on another thread right now
                               closed.set(true);
                               drain.run();
                           });
            });
        }

    }

    public CdcEventBus getCdcBus() {
        return cdcBus;
    }
}
