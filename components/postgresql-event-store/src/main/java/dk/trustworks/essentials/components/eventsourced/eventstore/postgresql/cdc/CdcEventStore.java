/*
 *  Copyright 2021-2025 the original author or authors.
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
import dk.trustworks.essentials.reactive.EventBus;
import dk.trustworks.essentials.types.LongRange;
import org.slf4j.*;
import reactor.core.Disposable;
import reactor.core.publisher.*;

import java.time.Duration;
import java.util.*;
import java.util.concurrent.ConcurrentSkipListMap;
import java.util.concurrent.atomic.*;
import java.util.function.*;
import java.util.stream.Stream;

import static dk.trustworks.essentials.shared.FailFast.*;

public class CdcEventStore<CONFIG> implements EventStore {

    private static final Logger log = LoggerFactory.getLogger(CdcEventStore.class);

    private final EventStore                                                  eventStore;
    private final EventStoreUnitOfWorkFactory<? extends EventStoreUnitOfWork> unitOfWorkFactory;
    private final EventStreamGapHandler<?>                                    eventStreamGapHandler;
    private final CdcEventBus                                                 cdcBus;
    private final int                                                         backfillBatchSize;

    public CdcEventStore(EventStore delegate,
                         EventStoreUnitOfWorkFactory<? extends EventStoreUnitOfWork> unitOfWorkFactory,
                         EventStreamGapHandler<?> eventStreamGapHandler,
                         CdcEventBus cdcBus,
                         CdcProperties cdcProperties) {
        this.eventStore = requireNonNull(delegate, "delegate eventStore must not be null");
        this.unitOfWorkFactory = requireNonNull(unitOfWorkFactory, "unitOfWorkFactory must not be null");
        this.eventStreamGapHandler = requireNonNull(eventStreamGapHandler, "eventStreamGapHandler must not be null");
        this.cdcBus = requireNonNull(cdcBus, "cdcBus must not be null");
        requireNonNull(cdcProperties, "cdcProperties must not be null");
        requireTrue(cdcProperties.getCdcEventStoreBackfillBatchSize() >= 1, "backfillBatchSize must be >= 1");
        this.backfillBatchSize = cdcProperties.getCdcEventStoreBackfillBatchSize();
    }

    @Override
    public Flux<PersistedEvent> pollEvents(AggregateType aggregateType,
                                           long fromInclusiveGlobalOrder,
                                           Optional<Integer> loadEventsByGlobalOrderBatchSize,
                                           Optional<Duration> pollingInterval,
                                           Optional<Tenant> onlyIncludeEventIfItBelongsToTenant,
                                           Optional<SubscriberId> subscriptionId,
                                           Optional<Function<String, EventStorePollingOptimizer>> eventStorePollingOptimizerFactory) {

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
                                          .filter(e -> onlyIncludeEventIfItBelongsToTenant
                                                  .map(t -> e.tenant()
                                                             .map(tt -> tt.toString().equals(t.toString()))
                                                             .orElse(false))
                                                  .orElse(true));

        return BackfillThenLiveOrdered.ordered(
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
    final class BackfillThenLiveOrdered {

        static Flux<PersistedEvent> ordered(
                Flux<PersistedEvent> backfill,
                Flux<PersistedEvent> live,
                long headInclusive
                                           ) {
            requireNonNull(backfill, "backfill");
            requireNonNull(live, "live");

            return Flux.defer(() -> {
                // Buffers live events by global order while backfill is running
                NavigableMap<Long, PersistedEvent> buffer = new ConcurrentSkipListMap<>();

                AtomicLong    expectedNext = new AtomicLong(headInclusive + 1);
                AtomicBoolean backfillDone = new AtomicBoolean(false);
                AtomicBoolean liveDone     = new AtomicBoolean(false);

                // Do NOT use replay().all() here (unbounded memory). We only start emitting after backfill is done.
                Sinks.Many<PersistedEvent> orderedLiveSink = Sinks.many().unicast().onBackpressureBuffer();

                Runnable drain = () -> {
                    if (!backfillDone.get()) return;

                    long next = expectedNext.get();
                    while (true) {
                        PersistedEvent ev = buffer.remove(next);
                        if (ev == null) break;

                        orderedLiveSink.tryEmitNext(ev);
                        next++;
                        expectedNext.set(next);
                    }

                    // Only complete AFTER backfill is done and live has completed
                    if (liveDone.get() && buffer.isEmpty()) {
                        orderedLiveSink.tryEmitComplete();
                    }
                };

                // Subscribe to live immediately so we don't miss anything during backfill
                Disposable liveSub = live.subscribe(
                        ev -> {
                            long go  = ev.globalEventOrder().longValue();
                            long exp = expectedNext.get();

                            // drop duplicates/old events
                            if (go < exp) return;

                            buffer.put(go, ev);
                            drain.run();
                        },
                        err -> {
                            // error can be forwarded immediately (concat will see it once subscribed)
                            orderedLiveSink.tryEmitError(err);
                        },
                        () -> {
                            liveDone.set(true);
                            // Don't complete sink yet unless backfill is done (and we drained).
                            drain.run();
                        }
                                                   );

                Flux<PersistedEvent> backfillWithGate =
                        backfill.doOnComplete(() -> {
                            backfillDone.set(true);
                            drain.run(); // may also complete sink if liveDone
                        });

                Flux<PersistedEvent> orderedLiveFlux = orderedLiveSink.asFlux();

                return Flux.concat(backfillWithGate, orderedLiveFlux)
                           .doFinally(sig -> {
                               liveSub.dispose();
                               // Ensure completion on teardown
                               orderedLiveSink.tryEmitComplete();
                           });
            });
        }

        private BackfillThenLiveOrdered() {
        }
    }

    public CdcEventBus getCdcBus() {
        return cdcBus;
    }
}
