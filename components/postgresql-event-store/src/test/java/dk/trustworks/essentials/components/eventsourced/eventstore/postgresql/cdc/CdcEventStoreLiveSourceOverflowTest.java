/*
 * Copyright 2021-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.cdc;

import dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.*;
import dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.eventstream.*;
import dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.gap.EventStreamGapHandler;
import dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.persistence.table_per_aggregate_type.SeparateTablePerAggregateEventStreamConfiguration;
import dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.transaction.*;
import dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.types.GlobalEventOrder;
import dk.trustworks.essentials.components.foundation.types.*;
import dk.trustworks.essentials.shared.functional.*;
import dk.trustworks.essentials.types.LongRange;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import reactor.core.Disposable;
import reactor.core.publisher.Flux;

import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.*;
import java.util.stream.*;

import static org.assertj.core.api.Assertions.*;
import static org.awaitility.Awaitility.await;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.*;

/**
 * Container-free test of the per-subscription overflow of {@code CdcEventStore}'s adaptive live source: a subscription
 * whose handler stalls must neither back-pressure the shared {@link CdcEventBus} sink (which would hold up the
 * dispatcher and every other subscription of the aggregate type) nor lose or reorder an event. Once its own hand-over
 * buffer of one polling page overflows it leaves the bus, is handed what it buffered, catches up from the event after
 * the last one it was handed, and rejoins the bus.
 * <p>
 * The bus is configured so that any backpressure on it fails the publish at once ({@code FAIL_FAST} with no overflow
 * retries and a small sink buffer). The event store is a fake over the events "persisted" so far, so the test can see
 * where the stalled subscription catches up from, and that it does not poll.
 */
class CdcEventStoreLiveSourceOverflowTest {
    private static final AggregateType ORDERS         = AggregateType.of("orders");
    private static final int           PAGE_SIZE      = 5;
    private static final long          EVENTS         = 40;
    private static final long          STALLING_EVENT = 2;
    private static final String        OVERFLOWS      = "essentials.cdc.eventstore.live_source.overflow.count";

    private final List<Disposable> subscriptions = new CopyOnWriteArrayList<>();

    @AfterEach
    void cleanup() {
        subscriptions.forEach(Disposable::dispose);
    }

    @ParameterizedTest(name = "subscribed while CDC active: {0}")
    @ValueSource(booleans = {true, false})
    void a_stalled_subscription_leaves_the_bus_without_holding_up_the_bus_or_the_other_subscriptions_and_rejoins_it_once_caught_up(boolean subscribedWhileActive) {
        var props = new CdcProperties();
        props.getHealthCheck().setActiveCutbackDebounce(Duration.ofMillis(50));
        props.getEventBus().setBackpressureBufferSize(8);
        props.getEventBus().setOverflowMaxRetries(0);
        props.getEventBus().setOverflowPolicy(CdcProperties.CdcOverflowPolicy.FAIL_FAST);
        var availability  = new CdcAvailability();
        var bus           = new CdcEventBus(props.getEventBus());
        var meterRegistry = new SimpleMeterRegistry();
        var store         = new FakeEventStore();
        var cdcEventStore = cdcEventStore(store, bus, props, availability, meterRegistry);

        if (subscribedWhileActive) {
            // Served by BackfillThenLiveOrdered, with the adaptive live source as its live tail
            availability.active("slot");
        }
        var mayHandleStallingEvent = new CountDownLatch(1);
        var stalledReceived        = new CopyOnWriteArrayList<Long>();
        var healthyReceived        = new CopyOnWriteArrayList<Long>();
        subscribe(cdcEventStore, "stalled", globalOrder -> {
            if (globalOrder == STALLING_EVENT) {
                awaitQuietly(mayHandleStallingEvent);
            }
            stalledReceived.add(globalOrder);
        });
        subscribe(cdcEventStore, "healthy", healthyReceived::add);
        if (!subscribedWhileActive) {
            // Started on polling (warm-up) and switched to the bus by the adaptive live source itself
            availability.active("slot");
        }

        // #1 proves both are on the bus. Republished until both bus legs are attached - repeats are filtered out
        var first = store.persist(1);
        await().atMost(Duration.ofSeconds(5)).untilAsserted(() -> {
            bus.publish(List.of(first));
            assertThat(stalledReceived).containsExactly(1L);
            assertThat(healthyReceived).containsExactly(1L);
        });

        // The "dispatcher" publishes #2..#40 at the healthy subscription's pace while the stalled one is blocked on #2.
        // Any backpressure on the bus would make a publish throw
        for (long globalOrder = STALLING_EVENT; globalOrder <= EVENTS; globalOrder++) {
            var event = store.persist(globalOrder);
            assertThatCode(() -> bus.publish(List.of(event))).doesNotThrowAnyException();
            var published = globalOrder;
            await().pollDelay(Duration.ZERO).pollInterval(Duration.ofMillis(2)).atMost(Duration.ofSeconds(5)).until(() -> healthyReceived.contains(published));
        }
        assertThat(healthyReceived).containsExactlyElementsOf(globalOrders(1, EVENTS));
        assertThat(stalledReceived).containsExactly(1L);
        // The overflow is signalled only once the stalled subscription has taken everything it buffered
        assertThat(meterRegistry.counter(OVERFLOWS).count()).isZero();
        // At warm-up both moved onto the bus with a catch-up of their own
        var loadsBeforeTheStallEnded = store.catchUpLoadsFrom().size();

        mayHandleStallingEvent.countDown();

        await().atMost(Duration.ofSeconds(10)).untilAsserted(() -> assertThat(stalledReceived).containsExactlyElementsOf(globalOrders(1, EVENTS)));
        // It buffered exactly one page past #2 (#3..#7), was handed all of it, and then caught up from right after it
        assertThat(store.catchUpLoadsFrom().get(loadsBeforeTheStallEnded)).isEqualTo(STALLING_EVENT + PAGE_SIZE + 1);
        assertThat(meterRegistry.counter(OVERFLOWS).count()).isEqualTo(1);

        // Back on the bus: an event that only the bus has (never "persisted", so no catch-up or poll could return it)
        // reaches both subscriptions
        var onlyOnTheBus = store.event(EVENTS + 1);
        bus.publish(List.of(onlyOnTheBus));
        await().atMost(Duration.ofSeconds(5)).untilAsserted(() -> {
            assertThat(stalledReceived).containsExactlyElementsOf(globalOrders(1, EVENTS + 1));
            assertThat(healthyReceived).containsExactlyElementsOf(globalOrders(1, EVENTS + 1));
        });
        assertThat(meterRegistry.counter(OVERFLOWS).count()).isEqualTo(1);
        // Neither polled after its warm-up: the stalled subscription caught up and rejoined the bus instead
        var warmUpPolls = subscribedWhileActive ? List.<Long>of() : List.of(1L);
        assertThat(store.pollsFrom("healthy")).isEqualTo(warmUpPolls);
        assertThat(store.pollsFrom("stalled")).isEqualTo(warmUpPolls);
        // A slow subscriber is not a CDC fallback
        assertThat(availability.getFallbackCount()).isZero();
    }

    private void subscribe(CdcEventStore<SeparateTablePerAggregateEventStreamConfiguration> cdcEventStore, String subscriberId, LongConsumer handler) {
        subscriptions.add(cdcEventStore.pollEvents(ORDERS,
                                                   1L,
                                                   Optional.of(PAGE_SIZE),
                                                   Optional.of(Duration.ofMillis(50)),
                                                   Optional.empty(),
                                                   Optional.of(SubscriberId.of(subscriberId)),
                                                   Optional.of((Function<String, EventStorePollingOptimizer>) name -> null))
                                       .subscribe(event -> handler.accept(event.globalEventOrder().longValue())));
    }

    @SuppressWarnings("unchecked")
    static CdcEventStore<SeparateTablePerAggregateEventStreamConfiguration> cdcEventStore(FakeEventStore store,
                                                                                          CdcEventBus bus,
                                                                                          CdcProperties props,
                                                                                          CdcAvailability availability,
                                                                                          SimpleMeterRegistry meterRegistry) {
        ConfigurableEventStore<SeparateTablePerAggregateEventStreamConfiguration> delegate   = mock(ConfigurableEventStore.class);
        EventStoreUnitOfWorkFactory<? extends EventStoreUnitOfWork>               uowFactory = mock(EventStoreUnitOfWorkFactory.class);
        when(uowFactory.withUnitOfWork(any(CheckedSupplier.class))).thenAnswer(inv -> ((CheckedSupplier<?>) inv.getArgument(0)).get());
        when(uowFactory.withUnitOfWork(any(CheckedFunction.class))).thenAnswer(inv -> ((CheckedFunction<Object, ?>) inv.getArgument(0)).apply(null));
        when(delegate.findHighestGlobalEventOrderPersisted(any())).thenAnswer(inv -> store.head());
        when(delegate.loadEventsByGlobalOrder(any(), any(LongRange.class), anyList(), (Tenant) any()))
                .thenAnswer(inv -> store.load(inv.getArgument(1), inv.getArgument(3)));
        when(delegate.pollEvents(any(), anyLong(), any(), any(), any(), any(), any(), any(SubscriberAcknowledgement.class)))
                .thenAnswer(inv -> store.pollFrom(((Optional<SubscriberId>) inv.getArgument(5)).orElseThrow().toString(), inv.getArgument(1)));
        return new CdcEventStore<>(delegate,
                                   uowFactory,
                                   mock(EventStreamGapHandler.class),
                                   bus,
                                   props,
                                   availability,
                                   Optional.of(meterRegistry));
    }

    static List<Long> globalOrders(long fromInclusive, long toInclusive) {
        return LongStream.rangeClosed(fromInclusive, toInclusive).boxed().toList();
    }

    private static void awaitQuietly(CountDownLatch latch) {
        try {
            latch.await(30, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    /**
     * Stands in for the event store: the events "persisted" so far, the head, page loads by global order (a catch-up or
     * a backfill), and polls. Records where catch-ups loaded from and where each subscriber resumed polling
     */
    static final class FakeEventStore {
        private final List<PersistedEvent>              persisted        = new CopyOnWriteArrayList<>();
        private final List<Long>                        catchUpLoadsFrom = new CopyOnWriteArrayList<>();
        private final List<Optional<Tenant>>            loadTenants      = new CopyOnWriteArrayList<>();
        private final ConcurrentMap<String, List<Long>> pollsFrom        = new ConcurrentHashMap<>();
        private final AtomicInteger                     failingLoads     = new AtomicInteger();

        PersistedEvent persist(long globalOrder) {
            return persist(event(globalOrder));
        }

        PersistedEvent persist(PersistedEvent event) {
            persisted.add(event);
            return event;
        }

        PersistedEvent event(long globalOrder) {
            return event(globalOrder, Optional.empty());
        }

        PersistedEvent event(long globalOrder, Optional<Tenant> tenant) {
            var event = mock(PersistedEvent.class);
            when(event.globalEventOrder()).thenReturn(GlobalEventOrder.of(globalOrder));
            when(event.aggregateType()).thenReturn(ORDERS);
            // doReturn avoids the wildcard-capture mismatch on Optional<? extends Tenant>
            doReturn(tenant).when(event).tenant();
            return event;
        }

        /**
         * The next {@code loads} page loads fail, as when the database is unreachable
         */
        void failNextLoads(int loads) {
            failingLoads.set(loads);
        }

        Optional<GlobalEventOrder> head() {
            return persisted.stream().map(PersistedEvent::globalEventOrder).max(Comparator.naturalOrder());
        }

        Stream<PersistedEvent> load(LongRange range, Tenant tenant) {
            catchUpLoadsFrom.add(range.getFromInclusive());
            loadTenants.add(Optional.ofNullable(tenant));
            if (failingLoads.getAndUpdate(remaining -> Math.max(0, remaining - 1)) > 0) {
                throw new IllegalStateException("Simulated: the database is unreachable");
            }
            // As the SQL does: an event without a tenant belongs to every tenant
            return persisted.stream()
                            .filter(event -> range.covers(event.globalEventOrder().longValue()))
                            .filter(event -> tenant == null || event.tenant().map(eventTenant -> eventTenant.toString().equals(tenant.toString())).orElse(true))
                            .sorted(Comparator.comparing(PersistedEvent::globalEventOrder))
                            .toList()
                            .stream();
        }

        List<Optional<Tenant>> loadTenants() {
            return loadTenants;
        }

        Flux<PersistedEvent> pollFrom(String subscriberId, long fromInclusive) {
            pollsFrom.computeIfAbsent(subscriberId, id -> new CopyOnWriteArrayList<>()).add(fromInclusive);
            return Flux.defer(() -> Flux.fromIterable(persisted.stream()
                                                               .filter(event -> event.globalEventOrder().longValue() >= fromInclusive)
                                                               .toList()));
        }

        List<Long> catchUpLoadsFrom() {
            return catchUpLoadsFrom;
        }

        List<Long> pollsFrom(String subscriberId) {
            return pollsFrom.getOrDefault(subscriberId, List.of());
        }
    }
}
