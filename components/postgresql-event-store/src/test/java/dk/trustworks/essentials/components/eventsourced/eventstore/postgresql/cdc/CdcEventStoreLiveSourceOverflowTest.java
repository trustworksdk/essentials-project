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
import dk.trustworks.essentials.components.foundation.types.SubscriberId;
import dk.trustworks.essentials.shared.functional.CheckedSupplier;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import reactor.core.Disposable;
import reactor.core.publisher.Flux;

import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.*;
import java.util.stream.LongStream;

import static org.assertj.core.api.Assertions.*;
import static org.awaitility.Awaitility.await;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.*;

/**
 * Container-free test of the per-subscription overflow of {@code CdcEventStore}'s adaptive live source: a subscription
 * whose handler stalls must neither back-pressure the shared {@link CdcEventBus} sink (which would hold up the
 * dispatcher and every other subscription of the aggregate type) nor lose or reorder an event. Once its own hand-over
 * buffer of one polling page overflows it leaves the bus and continues on polling from the last event it was handed.
 * <p>
 * The bus is configured so that any backpressure on it fails the publish at once ({@code FAIL_FAST} with no overflow
 * retries and a small sink buffer). Polling is a fake event store over the events "persisted" so far, so the test can
 * see where the stalled subscription resumes polling.
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
    void a_stalled_subscription_leaves_the_bus_for_polling_without_holding_up_the_bus_or_the_other_subscriptions(boolean subscribedWhileActive) {
        var props = new CdcProperties();
        props.getHealthCheck().setActiveCutbackDebounce(Duration.ofMillis(50));
        props.getEventBus().setBackpressureBufferSize(8);
        props.getEventBus().setOverflowMaxRetries(0);
        props.getEventBus().setOverflowPolicy(CdcProperties.CdcOverflowPolicy.FAIL_FAST);
        var availability  = new CdcAvailability();
        var bus           = new CdcEventBus(props.getEventBus());
        var meterRegistry = new SimpleMeterRegistry();
        var store         = new FakePollingStore();
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

        mayHandleStallingEvent.countDown();

        await().atMost(Duration.ofSeconds(10)).untilAsserted(() -> assertThat(stalledReceived).containsExactlyElementsOf(globalOrders(1, EVENTS)));
        // It buffered exactly one page past #2 (#3..#7), was handed all of it, and then polled from right after it
        assertThat(store.pollsFrom("stalled")).last().isEqualTo(STALLING_EVENT + PAGE_SIZE + 1);
        assertThat(meterRegistry.counter(OVERFLOWS).count()).isEqualTo(1);
        // The healthy subscription stayed on the bus throughout: at most the warm-up poll from before CDC was active
        assertThat(store.pollsFrom("healthy")).isEqualTo(subscribedWhileActive ? List.of() : List.of(1L));
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
    private static CdcEventStore<SeparateTablePerAggregateEventStreamConfiguration> cdcEventStore(FakePollingStore store,
                                                                                                  CdcEventBus bus,
                                                                                                  CdcProperties props,
                                                                                                  CdcAvailability availability,
                                                                                                  SimpleMeterRegistry meterRegistry) {
        ConfigurableEventStore<SeparateTablePerAggregateEventStreamConfiguration> delegate   = mock(ConfigurableEventStore.class);
        EventStoreUnitOfWorkFactory<? extends EventStoreUnitOfWork>               uowFactory = mock(EventStoreUnitOfWorkFactory.class);
        when(uowFactory.withUnitOfWork(any(CheckedSupplier.class))).thenAnswer(inv -> ((CheckedSupplier<?>) inv.getArgument(0)).get());
        // Nothing to back-fill: every event is published after the subscriptions started
        when(delegate.findHighestGlobalEventOrderPersisted(any())).thenReturn(Optional.of(GlobalEventOrder.of(0)));
        when(delegate.pollEvents(any(), anyLong(), any(), any(), any(), any(), any()))
                .thenAnswer(inv -> store.pollFrom(((Optional<SubscriberId>) inv.getArgument(5)).orElseThrow().toString(), inv.getArgument(1)));
        return new CdcEventStore<>(delegate,
                                   uowFactory,
                                   mock(EventStreamGapHandler.class),
                                   bus,
                                   props,
                                   availability,
                                   Optional.of(meterRegistry));
    }

    private static List<Long> globalOrders(long fromInclusive, long toInclusive) {
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
     * Stands in for the polling event store: each poll returns the events persisted so far from its resume point, and
     * records where each subscriber resumed polling
     */
    private static final class FakePollingStore {
        private final List<PersistedEvent>                persisted = new CopyOnWriteArrayList<>();
        private final ConcurrentMap<String, List<Long>> pollsFrom = new ConcurrentHashMap<>();

        PersistedEvent persist(long globalOrder) {
            var event = mock(PersistedEvent.class);
            when(event.globalEventOrder()).thenReturn(GlobalEventOrder.of(globalOrder));
            when(event.aggregateType()).thenReturn(ORDERS);
            when(event.tenant()).thenReturn(Optional.empty());
            persisted.add(event);
            return event;
        }

        Flux<PersistedEvent> pollFrom(String subscriberId, long fromInclusive) {
            pollsFrom.computeIfAbsent(subscriberId, id -> new CopyOnWriteArrayList<>()).add(fromInclusive);
            return Flux.defer(() -> Flux.fromIterable(persisted.stream()
                                                               .filter(event -> event.globalEventOrder().longValue() >= fromInclusive)
                                                               .toList()));
        }

        List<Long> pollsFrom(String subscriberId) {
            return pollsFrom.getOrDefault(subscriberId, List.of());
        }
    }
}
