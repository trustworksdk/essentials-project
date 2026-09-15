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
import reactor.core.Disposable;
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
    private final DistributionSummary                                         backfillLoadedSummary;
    private final DistributionSummary                                         backfillQueryRangeSummary;
    private final Counter                                                     liveEventsCounter;
    private final Timer                                                       backfillPageTimer;
    private final Timer                                                       backfillToLiveTransitionTimer;
    /**
     * Counts how often the live-tail drain in {@link BackfillThenLiveOrdered} was detected as stalled on
     * a missing {@code global_event_order} and recovered via re-subscription (see
     * {@link CdcLiveDrainStalledException}). Any non-zero value means a permanent (or very-long-lived)
     * live-tail gap was hit — previously a silent, self-perpetuating stall.
     */
    private final Counter                                                     stallDetectedCounter;
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
     * @deprecated Use {@link #builder()}. This constructor declares an {@code Optional} parameter and/or more than five parameters; the builder names every argument and accepts both plain values and {@code Optional}s. It is unchanged and remains the implementation the builder delegates to.
     */
    @Deprecated(forRemoval = true, since = "0.40.x")
    public CdcEventStore(ConfigurableEventStore<CONFIG> delegate,
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
     * @deprecated Use {@link #builder()}. This constructor declares an {@code Optional} parameter and/or more than five parameters; the builder names every argument and accepts both plain values and {@code Optional}s. It is unchanged and remains the implementation the builder delegates to.
     */
    @Deprecated(forRemoval = true, since = "0.40.x")
    public CdcEventStore(ConfigurableEventStore<CONFIG> delegate,
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
            backfillLoadedSummary = DistributionSummary.builder("essentials.cdc.eventstore.backfill.loaded").register(this.meterRegistry);
            backfillQueryRangeSummary = DistributionSummary.builder("essentials.cdc.eventstore.backfill.query_range").register(this.meterRegistry);
            liveEventsCounter = Counter.builder("essentials.cdc.eventstore.live.events").register(this.meterRegistry);
            backfillPageTimer = io.micrometer.core.instrument.Timer.builder("essentials.cdc.eventstore.backfill.page.latency")
                                                                   .register(this.meterRegistry);
            backfillToLiveTransitionTimer = io.micrometer.core.instrument.Timer.builder("essentials.cdc.eventstore.backfill_to_live.transition.latency")
                                                                               .register(this.meterRegistry);
            stallDetectedCounter = Counter.builder("essentials.cdc.backfill_live.stall_detected")
                                          .description("Number of times the BackfillThenLiveOrdered live-tail drain was detected stalled on a missing global order and recovered via re-subscription")
                                          .register(this.meterRegistry);
            Gauge.builder("essentials.cdc.backfill_live.buffer.size", backfillLiveBufferSize, AtomicInteger::get)
                 .description("Current size of the in-memory live-event buffer inside BackfillThenLiveOrdered; bounded by eventBus.backpressureBufferSize")
                 .register(this.meterRegistry);
        } else {
            fallbackPollCounter = null;
            liveSourceSwitchCounter = null;
            backfillLoadedSummary = null;
            backfillQueryRangeSummary = null;
            liveEventsCounter = null;
            backfillPageTimer = null;
            backfillToLiveTransitionTimer = null;
            stallDetectedCounter = null;
        }
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
        if (!availability.isActive()) {
            log.debug("Cdc is not active, using polling fallback");
            availability.fallbackUsed();
            if (fallbackPollCounter != null) fallbackPollCounter.increment();
            return buildAdaptiveLiveSource(
                    aggregateType,
                    fromInclusiveGlobalOrder - 1,
                    loadEventsByGlobalOrderBatchSize.orElse(backfillBatchSize),
                    onlyIncludeEventIfItBelongsToTenant,
                    pollingInterval,
                    subscriptionId,
                    eventStorePollingOptimizerFactory);
        }

        int pageSize = loadEventsByGlobalOrderBatchSize.orElse(backfillBatchSize);

        Optional<SubscriptionGapHandler> gapHandler =
                subscriptionId.map(eventStreamGapHandler::gapHandlerFor);

        // Tier-1 live-tail stall recovery (cdc-improvements.md §P10): the drain in BackfillThenLiveOrdered
        // advances expectedNext strictly by +1 and cannot skip a global order that never arrives on the
        // live bus (most commonly a rolled-back IDENTITY value that produces no data WAL). When the drain
        // parks on such a hole past eventBus.liveDrainStallThreshold it raises CdcLiveDrainStalledException;
        // the retryWhen below filters on it and re-subscribes, routing the hole through the gap-handler-
        // aware backfill, which is the only path proven to classify+heal it. resumeCursor carries the
        // resume point across restarts: it starts at the caller's fromInclusiveGlobalOrder and is advanced
        // to the stalled expectedNext before each retry — everything below it has already been emitted
        // contiguously, so resuming there re-loads only the un-emitted tail (minimal re-delivery).
        AtomicLong resumeCursor = new AtomicLong(fromInclusiveGlobalOrder);

        Flux<PersistedEvent> ordered = Flux.defer(() -> {
            long resumeFrom = resumeCursor.get();
            var  resume     = GlobalEventOrder.of(resumeFrom);

            // CDC race-safety: the "head" snapshot MUST be read only AFTER the live CDC-bus subscription
            // has been established — never before. The per-aggregate bus sink is a hot multicast that does
            // not replay history to late subscribers, so any event published in the window between a head
            // snapshot and the live attach would be delivered by neither backfill (capped at head) nor the
            // bus, stalling BackfillThenLiveOrdered forever on expectedNext. By deferring this read until
            // BackfillThenLiveOrdered has subscribed the live source (see ordered(...)), we guarantee:
            //   - any event published BEFORE the attach is already persisted, hence ≤ head and covered by
            //     backfill (whose upper bound is this same late head);
            //   - any event published AFTER the attach is captured by the live subscription.
            // The read is memoized PER (re)subscription. On a stall-recovery restart this re-reads head, so
            // the new head is now > the stalled hole and the hole falls inside the gap-handler-aware
            // backfill range — classified there (transient → wait/recover, permanent → skip) instead of
            // stalling the live tail again.
            long         noHead       = Long.MIN_VALUE;
            AtomicLong   headBox      = new AtomicLong(noHead);
            LongSupplier headSnapshot = () -> {
                long existing = headBox.get();
                if (existing != noHead) return existing;
                long read = unitOfWorkFactory.withUnitOfWork(() -> eventStore.findHighestGlobalEventOrderPersisted(aggregateType))
                                             .map(GlobalEventOrder::longValue)
                                             .orElse(resumeFrom - 1);
                headBox.compareAndSet(noHead, read);
                long head = headBox.get();
                log.debug("[{}] CDC poll starting from '{}' (head snapshot: '{}' with batch size '{}')", aggregateType, resume, head, pageSize);
                return head;
            };

            Flux<PersistedEvent> backfill = backfillFlux(
                    aggregateType,
                    resume,
                    headSnapshot,
                    pageSize,
                    onlyIncludeEventIfItBelongsToTenant,
                    gapHandler
                                                        );

            // The live source's high-water mark starts at resume-1 (not head) because head is not yet
            // known at assembly. BackfillThenLiveOrdered's expectedNext (= head+1) is the authoritative
            // backfill→live boundary and drops any bus event ≤ head as a duplicate, so the lower base only
            // means a marginally wider dedup window — never double-delivery.
            //
            // Tenant filtering is applied to the ORDERED OUTPUT below, NOT to this live source. The drain in
            // BackfillThenLiveOrdered advances expectedNext strictly by 1, so any event removed upstream of
            // it — e.g. an other-tenant event sitting between two events this subscriber wants — would punch
            // a hole the drain waits on forever (in a multi-tenant deployment, tenants interleave in
            // global_event_order, so this is the common case). The live source must therefore deliver the
            // contiguous all-tenant stream; we pass Optional.empty() here (bus is all-tenant anyway, and its
            // polling-fallback now loads all-tenant) and filter once on the ordered output. Backfill above
            // keeps its efficient SQL-level tenant filter because it bypasses the drain (emitted straight
            // through the merge), and the output filter is idempotent on it.
            Flux<PersistedEvent> live = buildAdaptiveLiveSource(
                    aggregateType,
                    resumeFrom - 1,
                    pageSize,
                    Optional.empty(),
                    pollingInterval,
                    subscriptionId,
                    eventStorePollingOptimizerFactory);

            return new BackfillThenLiveOrdered(backfillToLiveTransitionTimer, eventBusProperties, backfillLiveBufferSize).ordered(
                    backfill,
                    live,
                    headSnapshot
                                                                                                                                );
        }).retryWhen(liveDrainStallRecovery(aggregateType, resumeCursor));

        return filterByTenant(ordered, onlyIncludeEventIfItBelongsToTenant);
    }

    /**
     * Retry spec backing Tier-1 live-tail stall recovery (see {@link CdcLiveDrainStalledException} and
     * {@code cdc/cdc-improvements.md} §P10). It filters strictly on {@link CdcLiveDrainStalledException}
     * — every other error propagates unchanged — and, on a stall, advances {@code resumeCursor} to the
     * stalled {@code expectedNext} before re-subscribing so the gap-handler-aware backfill re-runs from
     * the hole.
     * <p>
     * A short fixed settle precedes each re-subscribe so a pathological repeat-stall (e.g. a
     * {@code NoEventStreamGapHandler} is configured, so a permanent gap is never promoted/skipped) backs
     * off rather than hot-looping; backfill's own DB round-trip already prevents a tight CPU loop.
     */
    private Retry liveDrainStallRecovery(AggregateType aggregateType, AtomicLong resumeCursor) {
        Duration restartBackoff = Duration.ofSeconds(1);
        return Retry.from(companion -> companion.flatMap(retrySignal -> {
            Throwable failure = retrySignal.failure();
            if (!(failure instanceof CdcLiveDrainStalledException)) {
                return Mono.error(failure);
            }
            CdcLiveDrainStalledException stall = (CdcLiveDrainStalledException) failure;
            resumeCursor.set(stall.stalledAtGlobalOrder());
            if (stallDetectedCounter != null) stallDetectedCounter.increment();
            log.warn("[{}] CDC live-tail drain stalled on missing globalOrder '{}' for >= '{}'; re-subscribing and resuming backfill from there to classify the gap (transient → recover, permanent → skip)",
                     aggregateType, stall.stalledAtGlobalOrder(), eventBusProperties.getLiveDrainStallThreshold());
            return Mono.delay(restartBackoff);
        }));
    }

    /**
     * Build the live-event source for an in-flight CDC subscription. The source transparently
     * switches between the CDC bus (while {@link CdcAvailability} is {@link CdcAvailability.State#ACTIVE
     * ACTIVE}) and classic polling (while availability is not ACTIVE), so that subscribers
     * established during healthy CDC continue to receive events even when CDC dies mid-stream.
     * <p>
     * Ordering + dedup: an {@link AtomicLong} tracks the highest {@code globalEventOrder} the
     * subscriber has received. On every source cut-over polling resumes from {@code lastSeen+1};
     * each downstream event is then gated by a {@code > lastSeen} filter so any overlap between
     * the outgoing source and the incoming one is dropped rather than double-delivered. The CDC
     * bus is inherently monotonic per aggregate (events published in order), and classic polling
     * queries {@code global_event_order ≥ resume} — both sources preserve order, and the filter
     * guarantees no regressions at the boundary.
     * <p>
     * Cutback debounce: FAILED/INACTIVE transitions cut to polling <b>immediately</b> so
     * subscribers don't stall. Transitions back to ACTIVE are held for
     * {@link #activeCutbackDebounce} with availability staying ACTIVE throughout; if availability
     * flips non-ACTIVE again during the debounce window the pending cutback is cancelled. This
     * prevents thrash when the underlying CDC pipeline oscillates (e.g. pgoutput intermittently
     * stalling).
     * <p>
     * The {@code onlyIncludeEventIfItBelongsToTenant} and other downstream filters are applied
     * uniformly to whichever source is currently active — callers see one consistent stream.
     */
    private Flux<PersistedEvent> buildAdaptiveLiveSource(
            AggregateType aggregateType,
            long headInclusive,
            int pageSize,
            Optional<Tenant> onlyIncludeEventIfItBelongsToTenant,
            Optional<Duration> pollingInterval,
            Optional<SubscriberId> subscriptionId,
            Optional<Function<String, EventStorePollingOptimizer>> eventStorePollingOptimizerFactory
                                                        ) {
        AtomicLong lastSeen = new AtomicLong(headInclusive);

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

        return gatedStates
                .switchMap(state -> {
                    if (liveSourceSwitchCounter != null) liveSourceSwitchCounter.increment();
                    long resumeFrom = lastSeen.get() + 1;
                    if (state == CdcAvailability.State.ACTIVE) {
                        log.debug("[{}] Adaptive live source switching to CDC bus (resumeFrom={})",
                                  aggregateType, resumeFrom);
                        return cdcBus.fluxForAggregate(aggregateType)
                                     .doOnNext(e -> {
                                         if (liveEventsCounter != null) liveEventsCounter.increment();
                                     });
                    }

                    log.debug("[{}] Adaptive live source switching to polling (resumeFrom={}, state={})",
                              aggregateType, resumeFrom, state);
                    return eventStore.pollEvents(aggregateType,
                                                 resumeFrom,
                                                 Optional.of(pageSize),
                                                 pollingInterval,
                                                 onlyIncludeEventIfItBelongsToTenant,
                                                 subscriptionId,
                                                 eventStorePollingOptimizerFactory);
                })
                // Drop anything at or below the high-water mark. Protects against:
                //  - events already delivered via the previous source showing up in the new one
                //    (CDC bus may still have buffered events after a cut-over)
                //  - polling returning events ≤ headInclusive on the very first query
                .filter(e -> e.globalEventOrder().longValue() > lastSeen.get())
                .doOnNext(e -> {
                    long go = e.globalEventOrder().longValue();
                    lastSeen.updateAndGet(cur -> Math.max(cur, go));
                })
                // Tenant gate (see eventBelongsToTenant). Safe to apply in-line here only because the
                // pollEvents ACTIVE path passes Optional.empty() when this source feeds the
                // BackfillThenLiveOrdered drain (it filters the ordered OUTPUT instead). This gate
                // therefore only ever fires on the polling-fallback return path, where dropping events
                // cannot stall any downstream strict-contiguity ordering.
                .filter(e -> eventBelongsToTenant(e, onlyIncludeEventIfItBelongsToTenant));
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
     * filtering must happen AFTER ordering, because removing events upstream of BackfillThenLiveOrdered's
     * strict-contiguity drain would punch holes it waits on forever.
     */
    private static Flux<PersistedEvent> filterByTenant(Flux<PersistedEvent> source, Optional<Tenant> onlyIncludeEventIfItBelongsToTenant) {
        if (onlyIncludeEventIfItBelongsToTenant.isEmpty()) {
            return source;
        }
        return source.filter(e -> eventBelongsToTenant(e, onlyIncludeEventIfItBelongsToTenant));
    }

    private Flux<PersistedEvent> backfillFlux(
            AggregateType aggregateType,
            GlobalEventOrder fromInclusive,
            LongSupplier headInclusive,
            int pageSize,
            Optional<Tenant> tenant,
            Optional<SubscriptionGapHandler> gapHandler
                                             ) {
        return Flux.create(sink -> {
            var  next = new AtomicLong(fromInclusive.longValue());
            // Read at subscription time: by the time backfill is subscribed (via the merge inside
            // BackfillThenLiveOrdered.ordered), the head snapshot has already been taken AFTER the
            // live bus attach, and is memoized — so backfill and the ordering boundary agree.
            long head = headInclusive.getAsLong();

            var scheduler = reactor.core.scheduler.Schedulers
                    .newSingle("CDC-Backfill-" + aggregateType, true);

            sink.onRequest(demand -> scheduler.schedule(() -> {
                long remaining = demand;

                try {
                    while (remaining > 0 && !sink.isCancelled()) {
                        long start = next.get();
                        if (start > head) {
                            sink.complete();
                            return;
                        }

                        long batch = Math.min(pageSize, remaining);

                        BackfillResult result =
                                backfillOnePageAndEmit(
                                        aggregateType,
                                        start,
                                        head,
                                        batch,
                                        tenant,
                                        gapHandler,
                                        sink::next
                                                      );
                        log.debug("[{}] Backfill result: next='{}', emitted='{}'", aggregateType, result.next(), result.emitted());

                        next.set(result.next());
                        remaining -= result.emitted();

                        // if we scanned but emitted nothing, we must still progress
                        if (result.emitted() == 0 && result.next() == start) {
                            next.incrementAndGet();
                        }
                    }
                } catch (Throwable t) {
                    log.warn("[{}] Backfill failed", aggregateType, t);
                    sink.error(t);
                }
            }));

            sink.onCancel(scheduler);
        }, FluxSink.OverflowStrategy.ERROR);
    }

    private BackfillResult backfillOnePageAndEmit(
            AggregateType aggregateType,
            long fromInclusive,
            long headInclusive,
            long pageSize,
            Optional<Tenant> tenant,
            Optional<SubscriptionGapHandler> gapHandler,
            Consumer<PersistedEvent> emit
                                                 ) {
        long toInclusive = Math.min(headInclusive, fromInclusive + pageSize - 1);
        var  range       = LongRange.between(fromInclusive, toInclusive);
        long startNs     = System.nanoTime();

        List<PersistedEvent> loaded =
                unitOfWorkFactory.withUnitOfWork(uow -> {
                    List<GlobalEventOrder> transientGaps =
                            gapHandler.map(h -> h.findTransientGapsToIncludeInQuery(aggregateType, range))
                                      .orElse(List.of());

                    var events =
                            eventStore.loadEventsByGlobalOrder(
                                    aggregateType,
                                    range,
                                    transientGaps,
                                    tenant.orElse(null)
                                                              ).toList();

                    gapHandler.ifPresent(h -> h.reconcileGaps(aggregateType, range, events, transientGaps));
                    return events;
                });
        if (backfillPageTimer != null) backfillPageTimer.record(System.nanoTime() - startNs, TimeUnit.NANOSECONDS);
        if (backfillLoadedSummary != null) backfillLoadedSummary.record(loaded.size());
        if (backfillQueryRangeSummary != null) backfillQueryRangeSummary.record(toInclusive - fromInclusive + 1);
        log.debug("[{}] Backfill loaded '{}' events", aggregateType, loaded.size());

        loaded.forEach(emit);

        if (loaded.isEmpty()) {
            return new BackfillResult(toInclusive + 1, 0);
        }

        return new BackfillResult(
                loaded.get(loaded.size() - 1).globalEventOrder().longValue() + 1,
                loaded.size()
        );
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
     * ⚠️ CRITICAL ORDERING COMPONENT
     * <p>
     * This class ensures strict global ordering between:
     * - backfill (polling)
     * - live CDC events
     * <p>
     * Do NOT simplify buffering, gating, or drain logic.
     * See: cdc/cdc-eventstore.md
     */
    static final class BackfillThenLiveOrdered {
        private static final Logger LOG = LoggerFactory.getLogger(BackfillThenLiveOrdered.class);

        private final Timer                               backfillToLiveTransitionTimer;
        private final CdcProperties.CdcEventBusProperties eventBusProperties;
        /** Observable gauge backing {@code essentials.cdc.backfill_live.buffer.size}. May be null in tests. */
        private final AtomicInteger                       bufferSizeGauge;

        private BackfillThenLiveOrdered(Timer backfillToLiveTransitionTimer,
                                        CdcProperties.CdcEventBusProperties eventBusProperties,
                                        AtomicInteger bufferSizeGauge) {
            this.backfillToLiveTransitionTimer = backfillToLiveTransitionTimer;
            this.eventBusProperties = requireNonNull(eventBusProperties, "eventBusProperties");
            this.bufferSizeGauge = bufferSizeGauge;
        }

        static Flux<PersistedEvent> orderedWithoutMetrics(Flux<PersistedEvent> backfill,
                                                          Flux<PersistedEvent> live,
                                                          long headInclusive,
                                                          CdcProperties.CdcEventBusProperties eventBusProperties) {
            return new BackfillThenLiveOrdered(null, eventBusProperties, null).ordered(backfill, live, () -> headInclusive);
        }

        /** Test seam: drive {@link #ordered} with a deferred head supplier to assert read-after-attach ordering. */
        static Flux<PersistedEvent> orderedWithoutMetrics(Flux<PersistedEvent> backfill,
                                                          Flux<PersistedEvent> live,
                                                          LongSupplier headSnapshot,
                                                          CdcProperties.CdcEventBusProperties eventBusProperties) {
            return new BackfillThenLiveOrdered(null, eventBusProperties, null).ordered(backfill, live, headSnapshot);
        }

        /**
         * @param headSnapshot supplies the backfill→live boundary — the highest global order persisted at
         *                     subscription start. Invoked exactly once, and deliberately only AFTER the live
         *                     source has been subscribed (i.e. the CDC bus is attached). This ordering is the
         *                     race fix: the bus is a hot multicast with no history replay for late subscribers,
         *                     so reading head before attaching would let an event slip through the gap between
         *                     the two and stall the pipeline forever on expectedNext. See {@code pollEvents}.
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
            // Dropping events in the ordered pipeline would silently violate the strict-ordering contract.
            // Honor retry counts from the bus config, but always fail-fast on terminal overflow.
            CdcProperties.CdcOverflowPolicy effectivePolicy = CdcProperties.CdcOverflowPolicy.FAIL_FAST;

            return Flux.defer(() -> {
                // Buffers live events by global order while backfill is running.
                // Bounded by the BaseSubscriber demand contract below: outstanding-demand + buffer.size() <= bufferSize.
                NavigableMap<Long, PersistedEvent> buffer = new ConcurrentSkipListMap<>();

                // Initialised from the head snapshot below, AFTER the live source is attached. No live
                // event can be observed before then (initial demand is held at 0 until that point), so
                // the placeholder is never used in an ordering decision.
                AtomicLong    expectedNext          = new AtomicLong();
                AtomicBoolean backfillDone          = new AtomicBoolean(false);
                AtomicBoolean liveDone              = new AtomicBoolean(false);
                long          backfillToLiveStartNs = System.nanoTime();
                AtomicBoolean transitionRecorded    = new AtomicBoolean(false);

                // Live-tail stall detection (cdc-improvements.md §P10). The drain advances expectedNext
                // strictly by +1; a global order that never arrives on the bus (a rolled-back IDENTITY
                // value) would park it forever. Once parked, upstream demand drains to 0 and hookOnNext
                // stops firing — so detection cannot be event-driven; a timer below watches for it.
                // lastProgressNs marks the last time the drain emitted at least one event (or the
                // backfill→live flip); stallSignalled ensures we raise the recovery error exactly once.
                long          stallThresholdNs = eventBusProperties.getLiveDrainStallThreshold().toNanos();
                AtomicLong    lastProgressNs   = new AtomicLong(System.nanoTime());
                AtomicBoolean stallSignalled   = new AtomicBoolean(false);

                // Bounded queue → tryEmitNext returns FAIL_OVERFLOW when a slow downstream consumer can't keep up.
                // The shared CdcSinkEmitter then backs off and eventually fails fast per policy.
                Sinks.Many<PersistedEvent> orderedLiveSink = Sinks.many()
                                                                  .unicast()
                                                                  .onBackpressureBuffer(new ArrayBlockingQueue<>(bufferSize));

                IntSupplier drain = () -> {
                    if (!backfillDone.get()) return 0;

                    int drained = 0;
                    long next = expectedNext.get();
                    while (true) {
                        PersistedEvent ev = buffer.remove(next);
                        if (ev == null) break;
                        if (bufferSizeGauge != null) bufferSizeGauge.decrementAndGet();

                        CdcSinkEmitter.tryEmit(orderedLiveSink,
                                               ev,
                                               nonSerializedMaxRetries,
                                               overflowMaxRetries,
                                               effectivePolicy,
                                               "BackfillThenLiveOrdered",
                                               LOG);
                        next++;
                        expectedNext.set(next);
                        drained++;
                    }

                    if (drained > 0) {
                        // Forward progress resets the stall clock — the drain is not parked on a hole.
                        lastProgressNs.set(System.nanoTime());
                    }

                    if (liveDone.get() && buffer.isEmpty()) {
                        orderedLiveSink.tryEmitComplete();
                    }
                    return drained;
                };

                // BaseSubscriber participates in upstream backpressure: we request at most `bufferSize` outstanding
                // demand from the CDC bus. If backfill hasn't completed, drain() is a no-op and we do NOT refill
                // demand — upstream is backpressured via the bus's own overflow policy.
                BaseSubscriber<PersistedEvent> liveSub = new BaseSubscriber<PersistedEvent>() {
                    @Override
                    protected void hookOnSubscribe(Subscription subscription) {
                        // Deliberately request nothing here. We must attach to the live source (so the bus
                        // starts retaining events for us) BEFORE the head snapshot is taken, but must not let
                        // events flow until expectedNext is initialised from that snapshot. The initial
                        // bufferSize demand is released right after the head read, below. Until then the bus
                        // holds events in its own bounded backpressure buffer — nothing is lost.
                    }

                    @Override
                    protected void hookOnNext(PersistedEvent ev) {
                        long go  = ev.globalEventOrder().longValue();
                        long exp = expectedNext.get();

                        if (go < exp) {
                            // Duplicate / already-emitted — does not occupy buffer, compensate immediately.
                            request(1);
                            return;
                        }

                        // buffer.put replaces on duplicate key; only count truly-new entries.
                        if (buffer.put(go, ev) == null && bufferSizeGauge != null) {
                            bufferSizeGauge.incrementAndGet();
                        }
                        int drained = drain.getAsInt();
                        if (drained > 0) {
                            request(drained);
                        }
                    }

                    @Override
                    protected void hookOnError(Throwable err) {
                        orderedLiveSink.tryEmitError(err);
                    }

                    @Override
                    protected void hookOnComplete() {
                        liveDone.set(true);
                        drain.getAsInt();
                    }
                };
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
                expectedNext.set(head + 1);
                // Release the initial demand now that expectedNext is established (see hookOnSubscribe).
                liveSub.request(bufferSize);

                // Timer-driven stall watch (see stall-detection notes above). Fires the retryable
                // CdcLiveDrainStalledException on the ordered sink when, post-backfill, the buffer holds a
                // run that cannot be drained because its lowest order is strictly above expectedNext (a
                // hole) and no forward progress has happened for the threshold. Disabled when the threshold
                // is ZERO (restores strict-contiguity-only behaviour). Disposed in doFinally below.
                Disposable stallWatch = stallThresholdNs <= 0
                        ? null
                        : Schedulers.parallel().schedulePeriodically(() -> {
                            if (stallSignalled.get()) return;
                            if (!backfillDone.get() || liveDone.get()) return;
                            Map.Entry<Long, PersistedEvent> first = buffer.firstEntry();
                            if (first == null) return;                                              // buffer empty → no hole
                            long exp = expectedNext.get();
                            if (first.getKey() <= exp) return;                                      // contiguous head present → drain handles it
                            if (System.nanoTime() - lastProgressNs.get() < stallThresholdNs) return; // not parked long enough
                            CdcLiveDrainStalledException stall = new CdcLiveDrainStalledException(
                                    exp,
                                    "CDC live-tail drain parked on missing global_event_order " + exp
                                    + " (lowest buffered " + first.getKey() + ") for >= " + eventBusProperties.getLiveDrainStallThreshold());
                            if (orderedLiveSink.tryEmitError(stall) == Sinks.EmitResult.OK) {
                                stallSignalled.set(true);
                                LOG.warn("BackfillThenLiveOrdered live-tail drain stalled on missing globalOrder {} (lowest buffered {}); signalling recovery", exp, first.getKey());
                            }
                            // Non-OK (raced with concurrent drain progress, or sink already terminated):
                            // leave unsignalled; the next tick re-evaluates against fresh state.
                        }, stallThresholdNs, stallThresholdNs, TimeUnit.NANOSECONDS);

                Flux<PersistedEvent> backfillWithGate =
                        backfill.doOnComplete(() -> {
                            backfillDone.set(true);
                            // Start the live-phase stall clock here, not at defer time: backfill itself may
                            // legitimately run longer than the stall threshold, and only now can the drain
                            // begin parking on a live-tail hole.
                            lastProgressNs.set(System.nanoTime());
                            int drained = drain.getAsInt();
                            if (drained > 0) {
                                liveSub.request(drained);
                            }
                            if (backfillToLiveTransitionTimer != null && transitionRecorded.compareAndSet(false, true)) {
                                backfillToLiveTransitionTimer.record(System.nanoTime() - backfillToLiveStartNs, java.util.concurrent.TimeUnit.NANOSECONDS);
                            }
                        });

                Flux<PersistedEvent> orderedLiveFlux = orderedLiveSink.asFlux();

                // Use merge (not concat) so the sink has a subscriber attached upfront. With a bounded sink queue,
                // concat would race: backfill.doOnComplete -> drain emits to sink -> queue fills before concat
                // subscribes to orderedLiveFlux -> next emit hits FAIL_OVERFLOW. Merge attaches B immediately, so
                // tryEmitNext flows to the subscriber in real time. Ordering is preserved because drain is gated
                // on backfillDone, so B emits nothing until after all backfill items have been delivered.
                return Flux.merge(backfillWithGate, orderedLiveFlux)
                           .doFinally(sig -> {
                               if (stallWatch != null) stallWatch.dispose();
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
