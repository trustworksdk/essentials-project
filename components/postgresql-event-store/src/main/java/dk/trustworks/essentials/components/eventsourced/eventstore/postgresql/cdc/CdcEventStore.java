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
import dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.transaction.*;
import dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.types.GlobalEventOrder;
import dk.trustworks.essentials.components.foundation.types.*;
import io.micrometer.core.instrument.*;
import dk.trustworks.essentials.reactive.EventBus;
import dk.trustworks.essentials.types.LongRange;
import io.micrometer.core.instrument.Timer;
import org.reactivestreams.Subscription;
import org.slf4j.*;
import reactor.core.publisher.*;

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
 * Public Constructors:
 * - CdcEventStore(EventStore delegate, EventStoreUnitOfWorkFactory<? extends EventStoreUnitOfWork> unitOfWorkFactory,
 *   EventStreamGapHandler<?> eventStreamGapHandler, CdcEventBus cdcBus, CdcProperties cdcProperties,
 *   CdcAvailability availability)
 * - CdcEventStore(EventStore delegate, EventStoreUnitOfWorkFactory<? extends EventStoreUnitOfWork> unitOfWorkFactory,
 *   EventStreamGapHandler<?> eventStreamGapHandler, CdcEventBus cdcBus, CdcProperties cdcProperties,
 *   CdcAvailability availability, Optional<MeterRegistry> meterRegistry)
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
public class CdcEventStore implements EventStore {

    private static final Logger log = LoggerFactory.getLogger(CdcEventStore.class);

    private final EventStore                                                  eventStore;
    private final EventStoreUnitOfWorkFactory<? extends EventStoreUnitOfWork> unitOfWorkFactory;
    private final EventStreamGapHandler<?>                                    eventStreamGapHandler;
    private final CdcEventBus                                                 cdcBus;
    private final CdcProperties.CdcEventBusProperties                         eventBusProperties;
    private final int                                                         backfillBatchSize;
    private final CdcAvailability                                             availability;
    private final MeterRegistry                                               meterRegistry;
    private final Counter                                                     fallbackPollCounter;
    private final DistributionSummary                                         backfillLoadedSummary;
    private final DistributionSummary                                         backfillQueryRangeSummary;
    private final Counter                                                     liveEventsCounter;
    private final Timer                                                       backfillPageTimer;
    private final Timer                                                       backfillToLiveTransitionTimer;

    public CdcEventStore(EventStore delegate,
                         EventStoreUnitOfWorkFactory<? extends EventStoreUnitOfWork> unitOfWorkFactory,
                         EventStreamGapHandler<?> eventStreamGapHandler,
                         CdcEventBus cdcBus,
                         CdcProperties cdcProperties,
                         CdcAvailability availability) {
        this(delegate, unitOfWorkFactory, eventStreamGapHandler, cdcBus, cdcProperties, availability, Optional.empty());
    }

    public CdcEventStore(EventStore delegate,
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
        this.meterRegistry = meterRegistry.orElse(null);
        if (this.meterRegistry != null) {
            fallbackPollCounter = Counter.builder("essentials.cdc.eventstore.fallback.poll.count").register(this.meterRegistry);
            backfillLoadedSummary = DistributionSummary.builder("essentials.cdc.eventstore.backfill.loaded").register(this.meterRegistry);
            backfillQueryRangeSummary = DistributionSummary.builder("essentials.cdc.eventstore.backfill.query_range").register(this.meterRegistry);
            liveEventsCounter = Counter.builder("essentials.cdc.eventstore.live.events").register(this.meterRegistry);
            backfillPageTimer = io.micrometer.core.instrument.Timer.builder("essentials.cdc.eventstore.backfill.page.latency")
                                                                   .register(this.meterRegistry);
            backfillToLiveTransitionTimer = io.micrometer.core.instrument.Timer.builder("essentials.cdc.eventstore.backfill_to_live.transition.latency")
                                                                               .register(this.meterRegistry);
        } else {
            fallbackPollCounter = null;
            backfillLoadedSummary = null;
            backfillQueryRangeSummary = null;
            liveEventsCounter = null;
            backfillPageTimer = null;
            backfillToLiveTransitionTimer = null;
        }
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
            availability.fallbackUsed();
            if (fallbackPollCounter != null) fallbackPollCounter.increment();
            return eventStore.pollEvents(aggregateType,
                                         fromInclusiveGlobalOrder,
                                         loadEventsByGlobalOrderBatchSize,
                                         pollingInterval,
                                         onlyIncludeEventIfItBelongsToTenant,
                                         subscriptionId,
                                         eventStorePollingOptimizerFactory);
        }

        var resume = GlobalEventOrder.of(fromInclusiveGlobalOrder);

        // "head snapshot": highest persisted at subscription start
        var head = unitOfWorkFactory.withUnitOfWork(() -> eventStore.findHighestGlobalEventOrderPersisted(aggregateType))
                                    .orElse(GlobalEventOrder.of(fromInclusiveGlobalOrder - 1));

        int pageSize = loadEventsByGlobalOrderBatchSize.orElse(backfillBatchSize);

        log.debug("[{}] CDC poll for starting from '{}' (head snapshot: '{}' with batch size '{}')", aggregateType, resume, head, backfillBatchSize);

        Optional<SubscriptionGapHandler> gapHandler =
                subscriptionId.map(eventStreamGapHandler::gapHandlerFor);

        Flux<PersistedEvent> backfill = backfillFlux(
                aggregateType,
                resume,
                head,
                pageSize,
                onlyIncludeEventIfItBelongsToTenant,
                gapHandler
                                                    );

        Flux<PersistedEvent> live = cdcBus.fluxForAggregate(aggregateType)
                                          .filter(e -> e.globalEventOrder().longValue() > head.longValue())
                                          .doOnNext(e -> {
                                              if (liveEventsCounter != null) liveEventsCounter.increment();
                                          })
                                          .filter(e -> onlyIncludeEventIfItBelongsToTenant
                                                  .map(t -> e.tenant()
                                                             .map(tt -> tt.toString().equals(t.toString()))
                                                             .orElse(false))
                                                  .orElse(true));

        return new BackfillThenLiveOrdered(backfillToLiveTransitionTimer, eventBusProperties).ordered(
                backfill,
                live,
                head.longValue()
                                                    );
    }

    private Flux<PersistedEvent> backfillFlux(
            AggregateType aggregateType,
            GlobalEventOrder fromInclusive,
            GlobalEventOrder headInclusive,
            int pageSize,
            Optional<Tenant> tenant,
            Optional<SubscriptionGapHandler> gapHandler
                                             ) {
        return Flux.create(sink -> {
            var  next = new AtomicLong(fromInclusive.longValue());
            long head = headInclusive.longValue();

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
        return Optional.empty();
    }

    @Override
    public EventStoreUnitOfWorkFactory<EventStoreUnitOfWork> getUnitOfWorkFactory() {
        return eventStore.getUnitOfWorkFactory();
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

        private final Timer                             backfillToLiveTransitionTimer;
        private final CdcProperties.CdcEventBusProperties eventBusProperties;

        private BackfillThenLiveOrdered(Timer backfillToLiveTransitionTimer,
                                        CdcProperties.CdcEventBusProperties eventBusProperties) {
            this.backfillToLiveTransitionTimer = backfillToLiveTransitionTimer;
            this.eventBusProperties = requireNonNull(eventBusProperties, "eventBusProperties");
        }

        static Flux<PersistedEvent> orderedWithoutMetrics(Flux<PersistedEvent> backfill,
                                                          Flux<PersistedEvent> live,
                                                          long headInclusive,
                                                          CdcProperties.CdcEventBusProperties eventBusProperties) {
            return new BackfillThenLiveOrdered(null, eventBusProperties).ordered(backfill, live, headInclusive);
        }

        Flux<PersistedEvent> ordered(
                Flux<PersistedEvent> backfill,
                Flux<PersistedEvent> live,
                long headInclusive
                                    ) {
            requireNonNull(backfill, "backfill");
            requireNonNull(live, "live");

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

                AtomicLong    expectedNext          = new AtomicLong(headInclusive + 1);
                AtomicBoolean backfillDone          = new AtomicBoolean(false);
                AtomicBoolean liveDone              = new AtomicBoolean(false);
                long          backfillToLiveStartNs = System.nanoTime();
                AtomicBoolean transitionRecorded    = new AtomicBoolean(false);

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
                        request(bufferSize);
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

                        buffer.put(go, ev);
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

                Flux<PersistedEvent> backfillWithGate =
                        backfill.doOnComplete(() -> {
                            backfillDone.set(true);
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
