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

import dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.EventStore;
import dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.EventStorePollingOptimizer;
import dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.eventstream.AggregateType;
import dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.eventstream.PersistedEvent;
import dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.gap.EventStreamGapHandler;
import dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.transaction.EventStoreUnitOfWork;
import dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.transaction.EventStoreUnitOfWorkFactory;
import dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.types.GlobalEventOrder;
import dk.trustworks.essentials.components.foundation.types.SubscriberId;
import dk.trustworks.essentials.shared.functional.CheckedSupplier;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Sinks;

import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Function;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Exercises the transparent live-source switch introduced in {@code CdcEventStore}: when a
 * subscription has been established while CDC was ACTIVE and availability later flips to
 * FAILED/INACTIVE mid-stream, the subscriber must see events continue to flow via classic
 * polling without needing to re-subscribe. Cutbacks ACTIVE→CDC are debounced so pgoutput
 * oscillation doesn't thrash the source.
 * <p>
 * Uses a real {@link CdcEventBus} + real {@link CdcAvailability} + mocked polling-side
 * {@code eventStore.pollEvents} so every assertion can be driven deterministically.
 */
class CdcEventStoreAdaptiveLiveSourceTest {

    private static final AggregateType ORDERS = AggregateType.of("orders");

    @Test
    void delivers_live_events_from_cdc_bus_while_availability_active() {
        var fx = fixture(Duration.ofMillis(100));
        fx.availability.active("slot");

        var received = subscribe(fx);

        fx.bus.publish(List.of(event(1), event(2), event(3)));

        await().atMost(Duration.ofSeconds(2)).until(() -> received.size() >= 3);
        assertThat(globalOrders(received)).containsExactly(1L, 2L, 3L);
    }

    @Test
    void switches_to_polling_when_availability_flips_failed_mid_stream() {
        var fx = fixture(Duration.ofMillis(100));
        fx.availability.active("slot");

        // Polling source, used after the FAILED flip, returns 4..6 once the switchMap subscribes
        // to it. Built eagerly but only actually subscribed after the availability flip.
        var pollingSink = Sinks.many().unicast().<PersistedEvent>onBackpressureBuffer();
        stubPollingSource(fx, pollingSink.asFlux());

        var received = subscribe(fx);

        // CDC delivers 1..3 live.
        fx.bus.publish(List.of(event(1), event(2), event(3)));
        await().atMost(Duration.ofSeconds(2)).until(() -> received.size() >= 3);

        // Mid-stream: flip to FAILED. Adaptive source should cancel the cdcBus subscription and
        // subscribe to the polling source (resumeFrom = lastSeen + 1 = 4).
        fx.availability.failed("slot", "simulated stall");
        pollingSink.tryEmitNext(event(4));
        pollingSink.tryEmitNext(event(5));
        pollingSink.tryEmitNext(event(6));

        await().atMost(Duration.ofSeconds(2)).until(() -> received.size() >= 6);
        assertThat(globalOrders(received)).containsExactly(1L, 2L, 3L, 4L, 5L, 6L);
    }

    @Test
    void deduplicates_overlap_between_cdc_bus_and_polling() {
        var fx = fixture(Duration.ofMillis(100));
        fx.availability.active("slot");

        // Polling source returns 3..6 — events 3 already came via cdcBus. The adaptive source's
        // `globalOrder > lastSeen` filter must drop those.
        var pollingSink = Sinks.many().unicast().<PersistedEvent>onBackpressureBuffer();
        stubPollingSource(fx, pollingSink.asFlux());

        var received = subscribe(fx);

        fx.bus.publish(List.of(event(1), event(2), event(3)));
        await().atMost(Duration.ofSeconds(2)).until(() -> received.size() >= 3);

        fx.availability.failed("slot", "simulated stall");
        pollingSink.tryEmitNext(event(3)); // duplicate — must be dropped
        pollingSink.tryEmitNext(event(2)); // duplicate — must be dropped
        pollingSink.tryEmitNext(event(4));
        pollingSink.tryEmitNext(event(5));
        pollingSink.tryEmitNext(event(6));

        await().atMost(Duration.ofSeconds(2)).until(() -> received.size() >= 6);
        assertThat(globalOrders(received)).containsExactly(1L, 2L, 3L, 4L, 5L, 6L);
    }

    @Test
    void cuts_back_to_cdc_after_debounce_with_sustained_active() throws Exception {
        Duration debounce = Duration.ofMillis(200);
        var fx = fixture(debounce);
        fx.availability.active("slot");

        var pollingSink = Sinks.many().unicast().<PersistedEvent>onBackpressureBuffer();
        stubPollingSource(fx, pollingSink.asFlux());

        var received = subscribe(fx);

        fx.bus.publish(List.of(event(1)));
        await().atMost(Duration.ofSeconds(2)).until(() -> received.size() >= 1);

        // Fall to polling.
        fx.availability.failed("slot", "stall");
        pollingSink.tryEmitNext(event(2));
        pollingSink.tryEmitNext(event(3));
        await().atMost(Duration.ofSeconds(2)).until(() -> received.size() >= 3);

        // Availability recovers and stays ACTIVE long enough for the cutback to fire.
        fx.availability.active("slot");
        Thread.sleep(debounce.toMillis() + 150);

        // After the cutback, new events must arrive via cdcBus again.
        fx.bus.publish(List.of(event(4), event(5)));
        await().atMost(Duration.ofSeconds(2)).until(() -> received.size() >= 5);
        assertThat(globalOrders(received)).containsExactly(1L, 2L, 3L, 4L, 5L);
    }

    @Test
    void cancels_pending_cutback_when_availability_flips_failed_within_debounce_window() throws Exception {
        Duration debounce = Duration.ofMillis(400);
        var fx = fixture(debounce);
        fx.availability.active("slot");

        var pollingSink = Sinks.many().unicast().<PersistedEvent>onBackpressureBuffer();
        stubPollingSource(fx, pollingSink.asFlux());

        var received = subscribe(fx);

        fx.bus.publish(List.of(event(1)));
        await().atMost(Duration.ofSeconds(2)).until(() -> received.size() >= 1);

        // FAILED → polling takes over.
        fx.availability.failed("slot", "stall");
        pollingSink.tryEmitNext(event(2));
        await().atMost(Duration.ofSeconds(2)).until(() -> received.size() >= 2);

        // Brief ACTIVE that gets cancelled before debounce fires.
        fx.availability.active("slot");
        Thread.sleep(debounce.toMillis() / 2);
        fx.availability.failed("slot", "stall again");

        // Polling should still be the live source — push 3 through polling and verify delivery.
        pollingSink.tryEmitNext(event(3));
        await().atMost(Duration.ofSeconds(2)).until(() -> received.size() >= 3);

        // Meanwhile, cdcBus events should NOT be delivered because the cutback was cancelled.
        fx.bus.publish(List.of(event(99))); // would be visible only if we wrongly switched back
        Thread.sleep(debounce.toMillis() + 150);
        assertThat(globalOrders(received)).containsExactly(1L, 2L, 3L);
    }

    // -------- fixture plumbing --------

    private static PersistedEvent event(long globalOrder) {
        var e = mock(PersistedEvent.class);
        when(e.globalEventOrder()).thenReturn(GlobalEventOrder.of(globalOrder));
        when(e.aggregateType()).thenReturn(ORDERS);
        when(e.tenant()).thenReturn(Optional.empty());
        return e;
    }

    @SuppressWarnings("unchecked")
    private static Fixture fixture(Duration cutbackDebounce) {
        EventStore delegate = mock(EventStore.class);
        EventStoreUnitOfWorkFactory<? extends EventStoreUnitOfWork> uowFactory = mock(EventStoreUnitOfWorkFactory.class);
        EventStreamGapHandler<?> gapHandler = mock(EventStreamGapHandler.class);

        // withUnitOfWork(CheckedSupplier) must invoke the supplier directly — the default
        // UnitOfWork machinery isn't relevant to this test, but the head-snapshot lookup inside
        // pollEvents walks through it. Without this, mockito returns null and pollEvents NPEs.
        when(uowFactory.withUnitOfWork(any(CheckedSupplier.class)))
                .thenAnswer(inv -> ((CheckedSupplier<?>) inv.getArgument(0)).get());

        // Head snapshot — start from 0 so every event we push has globalOrder > head and is
        // eligible for delivery via the adaptive source.
        when(delegate.findHighestGlobalEventOrderPersisted(any()))
                .thenReturn(Optional.of(GlobalEventOrder.of(0)));

        var props = new CdcProperties();
        props.getHealthCheck().setActiveCutbackDebounce(cutbackDebounce);

        var availability = new CdcAvailability();
        var bus = new CdcEventBus();

        var cdcEventStore = new CdcEventStore(
                delegate,
                uowFactory,
                gapHandler,
                bus,
                props,
                availability);

        return new Fixture(delegate, cdcEventStore, bus, availability);
    }

    private static void stubPollingSource(Fixture fx, Flux<PersistedEvent> source) {
        when(fx.delegate.pollEvents(any(), anyLong(), any(), any(), any(), any(), any()))
                .thenReturn(source);
    }

    private static List<PersistedEvent> subscribe(Fixture fx) {
        var received = new CopyOnWriteArrayList<PersistedEvent>();
        fx.cdcEventStore.pollEvents(
                ORDERS,
                1L, // fromInclusive — head snapshot above returns 0, so live filter starts at > 0
                Optional.empty(),
                Optional.of(Duration.ofMillis(50)),
                Optional.empty(),
                Optional.of(SubscriberId.of("test-sub")),
                Optional.of((Function<String, EventStorePollingOptimizer>) name -> null)
        ).subscribe(received::add);
        return received;
    }

    private static List<Long> globalOrders(List<PersistedEvent> events) {
        return events.stream().map(e -> e.globalEventOrder().longValue()).toList();
    }

    private record Fixture(EventStore delegate,
                           CdcEventStore cdcEventStore,
                           CdcEventBus bus,
                           CdcAvailability availability) {
    }
}
