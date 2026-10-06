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

import dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.EventStorePollingOptimizer;
import dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.cdc.CdcEventStoreLiveSourceOverflowTest.FakeEventStore;
import dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.eventstream.*;
import dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.persistence.table_per_aggregate_type.SeparateTablePerAggregateEventStreamConfiguration;
import dk.trustworks.essentials.components.foundation.types.*;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.*;
import reactor.core.Disposable;

import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.Function;
import java.util.stream.*;

import static dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.cdc.CdcEventStoreLiveSourceOverflowTest.globalOrders;
import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

/**
 * Container-free tests of the gap-free move onto the CDC bus in {@code CdcEventStore}'s adaptive live source. The bus
 * replays nothing to a late subscriber, so a subscription that attached to it without catching up lost every event the
 * bus had published before the attach that its previous source - polling, during warm-up or an outage - had not
 * delivered yet, and the next bus event moved it past them for good.
 * <p>
 * A bystander subscription stays on the bus throughout, as the other subscriptions of an aggregate type do. Without one
 * the bus sink retains what is published before anyone subscribes and hands it to its first subscriber, which would
 * hide the loss.
 */
class CdcEventStoreBusCatchUpTest {
    private static final AggregateType ORDERS    = AggregateType.of("orders");
    private static final int           PAGE_SIZE = 5;
    private static final Duration      DEBOUNCE  = Duration.ofMillis(100);
    private static final String        OVERFLOWS = "essentials.cdc.eventstore.live_source.overflow.count";

    private final List<Disposable> subscriptions = new CopyOnWriteArrayList<>();

    private CdcAvailability                                                  availability;
    private CdcEventBus                                                      bus;
    private SimpleMeterRegistry                                              meterRegistry;
    private FakeEventStore                                                   store;
    private CdcEventStore<SeparateTablePerAggregateEventStreamConfiguration> cdcEventStore;

    @BeforeEach
    void setup() {
        var props = new CdcProperties();
        props.getHealthCheck().setActiveCutbackDebounce(DEBOUNCE);
        availability = new CdcAvailability();
        bus = new CdcEventBus(props.getEventBus());
        meterRegistry = new SimpleMeterRegistry();
        store = new FakeEventStore();
        cdcEventStore = CdcEventStoreLiveSourceOverflowTest.cdcEventStore(store, bus, props, availability, meterRegistry);
        subscriptions.add(bus.fluxForAggregate(ORDERS).subscribe());
    }

    @AfterEach
    void cleanup() {
        subscriptions.forEach(Disposable::dispose);
    }

    /**
     * Warm-up: the subscription starts on polling while CDC is INACTIVE and moves onto the bus once ACTIVE has lasted
     * the debounce. Everything the bus published before that move - after polling's last fetch, and during the
     * debounce - comes from the catch-up, holes in the global order included.
     */
    @Test
    void a_subscription_moving_onto_the_bus_at_warm_up_catches_up_on_what_polling_had_not_delivered() {
        persistAndPublish(1, 2);
        var received = new CopyOnWriteArrayList<Long>();
        var threads  = new CopyOnWriteArrayList<String>();
        subscribe("warm-up", Optional.empty(), (globalOrder, thread) -> {
            received.add(globalOrder);
            threads.add(thread);
        });
        // Polling's last fetch
        await().atMost(Duration.ofSeconds(5)).until(() -> received.size() == 2);

        // Published on the bus while the subscription is still polling: 3 is a rolled-back IDENTITY value, a hole
        persistAndPublish(4, 5);
        availability.active("slot");
        // ... and during the debounce
        persistAndPublish(6);
        awaitBusLegAttached();
        // The bus, once the subscription is on it; 7 is another hole, right at the end of the catch-up
        persistAndPublish(8, 9);

        await().atMost(Duration.ofSeconds(5)).untilAsserted(() -> assertThat(received).containsExactly(1L, 2L, 4L, 5L, 6L, 8L, 9L));
        assertThat(store.catchUpLoadsFrom()).first().isEqualTo(3L);
        // Everything after polling's fetch was handled on the subscription's own CDC delivery thread
        assertThat(threads.subList(2, threads.size())).allMatch(thread -> thread.startsWith("Cdc-warm-up-orders"));
        assertThat(meterRegistry.counter(OVERFLOWS).count()).isZero();
        assertThat(availability.getFallbackCount()).isZero();
    }

    /**
     * Recovery: a subscription started while CDC is ACTIVE ({@code BackfillThenLiveOrdered} with the adaptive live
     * source as its live tail) polls through an outage and moves back onto the bus. The backlog the dispatcher
     * publishes on recovery, before the subscription is back on the bus, comes from the catch-up. Before, it was lost to
     * the live source, and the ordered drain stalled on it until the live-drain stall threshold.
     */
    @Test
    void a_subscription_moving_back_onto_the_bus_after_an_outage_catches_up_on_what_polling_had_not_delivered() {
        availability.active("slot");
        var received = new CopyOnWriteArrayList<Long>();
        subscribe("recovery", Optional.empty(), (globalOrder, thread) -> received.add(globalOrder));
        awaitBusLegAttached();
        persistAndPublishUntilReceived(received, 1);
        persistAndPublish(2, 3);
        await().atMost(Duration.ofSeconds(5)).until(() -> received.size() == 3);

        // Committed as the outage begins, and fetched by polling (the fake polls once, when the switch subscribes it)
        store.persist(4);
        availability.failed("slot", "simulated outage");
        await().atMost(Duration.ofSeconds(5)).until(() -> received.size() == 4);
        // Committed during the outage after polling's last fetch, and published as the backlog once CDC is back
        store.persist(5);
        store.persist(6);
        availability.active("slot");
        bus.publish(List.of(store.event(5), store.event(6)));
        // Live again, once the subscription is back on the bus
        awaitBusLegAttached();
        persistAndPublish(7, 8);

        await().atMost(Duration.ofSeconds(5)).untilAsserted(() -> assertThat(received).containsExactlyElementsOf(globalOrders(1, 8)));
        assertThat(availability.getFallbackCount()).isEqualTo(1);
        assertThat(meterRegistry.counter(OVERFLOWS).count()).isZero();
    }

    /**
     * A catch-up that the bus outpaces overflows the hand-over buffer again; the next one starts where it got to. A slow
     * handler during a long catch-up therefore still gets every event exactly once and in order, and ends up on the bus.
     */
    @Test
    void a_catch_up_outpaced_by_the_bus_restarts_from_where_it_got_to_and_ends_up_on_the_bus() {
        persistAndPublish(1);
        var received = new CopyOnWriteArrayList<Long>();
        subscribe("slow", Optional.empty(), (globalOrder, thread) -> {
            received.add(globalOrder);
            sleepQuietly(Duration.ofMillis(3));
        });
        await().atMost(Duration.ofSeconds(5)).until(() -> received.size() == 1);

        // A backlog of ~300 for the catch-up (about a second at this handler's pace), and while the handler is still on
        // it the bus publishes far more than the hand-over buffer holds
        for (long globalOrder = 2; globalOrder <= 300; globalOrder++) {
            persistAndPublish(globalOrder);
        }
        availability.active("slot");
        awaitBusLegAttached();
        assertThat(received).last().matches(globalOrder -> globalOrder < 300, "still catching up");
        for (long globalOrder = 301; globalOrder <= 360; globalOrder++) {
            persistAndPublish(globalOrder);
        }

        await().atMost(Duration.ofSeconds(15)).untilAsserted(() -> assertThat(received).containsExactlyElementsOf(globalOrders(1, 360)));
        assertThat(meterRegistry.counter(OVERFLOWS).count()).isEqualTo(1);
        // The hand-over buffer held 301..305 when 306 did not fit; once handed those, the next catch-up started at 306
        assertThat(store.catchUpLoadsFrom()).contains(301L + PAGE_SIZE);

        // On the bus: an event only the bus has reaches the subscription
        bus.publish(List.of(store.event(361)));
        await().atMost(Duration.ofSeconds(5)).untilAsserted(() -> assertThat(received).containsExactlyElementsOf(globalOrders(1, 361)));
    }

    /**
     * The database being unreachable while CDC recovers fails the catch-up. That must not end the subscription: it is
     * retried from where it got to.
     */
    @Test
    void a_failed_catch_up_is_retried_instead_of_ending_the_subscription() {
        persistAndPublish(1);
        var received = new CopyOnWriteArrayList<Long>();
        subscribe("retrying", Optional.empty(), (globalOrder, thread) -> received.add(globalOrder));
        await().atMost(Duration.ofSeconds(5)).until(() -> received.size() == 1);

        persistAndPublish(2, 3);
        store.failNextLoads(1);
        availability.active("slot");
        awaitBusLegAttached();
        persistAndPublish(4);

        await().atMost(Duration.ofSeconds(5)).untilAsserted(() -> assertThat(received).containsExactlyElementsOf(globalOrders(1, 4)));
        // The failed load, then the retry, both from right after the last event delivered
        assertThat(store.catchUpLoadsFrom()).startsWith(2L, 2L);
    }

    /**
     * Tenant filtering: the catch-up loads every tenant's events, as the bus delivers them, and both are filtered by the
     * subscriber's tenant on the way out. Filtered in SQL instead, another tenant's events would be gaps in the global
     * order the subscription's delivery tracker waits for. An event without a tenant belongs to every tenant.
     */
    @Test
    void a_tenant_filtered_subscription_catches_up_on_its_own_tenants_events_only() {
        var tenant      = new TestTenant("example");
        var otherTenant = new TestTenant("other");
        store.persist(store.event(1, Optional.of(tenant)));
        var received = new CopyOnWriteArrayList<Long>();
        subscribe("tenant", Optional.of(tenant), (globalOrder, thread) -> received.add(globalOrder));
        await().atMost(Duration.ofSeconds(5)).until(() -> received.size() == 1);

        persistAndPublish(store.event(2, Optional.of(otherTenant)), store.event(3, Optional.empty()), store.event(4, Optional.of(tenant)));
        availability.active("slot");
        awaitBusLegAttached();
        persistAndPublish(store.event(5, Optional.of(otherTenant)), store.event(6, Optional.of(tenant)));

        await().atMost(Duration.ofSeconds(5)).untilAsserted(() -> assertThat(received).containsExactly(1L, 3L, 4L, 6L));
        assertThat(store.loadTenants()).isNotEmpty().allMatch(Optional::isEmpty);
    }

    private void subscribe(String subscriberId, Optional<Tenant> tenant, Handler handler) {
        subscriptions.add(cdcEventStore.pollEvents(ORDERS,
                                                   1L,
                                                   Optional.of(PAGE_SIZE),
                                                   Optional.of(Duration.ofMillis(50)),
                                                   tenant,
                                                   Optional.of(SubscriberId.of(subscriberId)),
                                                   Optional.of((Function<String, EventStorePollingOptimizer>) name -> null))
                                       .subscribe(event -> handler.handle(event.globalEventOrder().longValue(), Thread.currentThread().getName())));
    }

    /**
     * Once the debounce has passed, the subscription is on the bus - a move onto the bus attaches synchronously
     */
    private void awaitBusLegAttached() {
        await().pollDelay(DEBOUNCE.multipliedBy(3)).atMost(Duration.ofSeconds(5)).until(() -> true);
    }

    private void persistAndPublish(long... globalOrders) {
        persistAndPublish(LongStream.of(globalOrders).mapToObj(store::event).toArray(PersistedEvent[]::new));
    }

    private void persistAndPublish(PersistedEvent... events) {
        for (var event : events) {
            store.persist(event);
            bus.publish(List.of(event));
        }
    }

    /**
     * Republished until the subscription has it - the repeats are filtered out
     */
    private void persistAndPublishUntilReceived(List<Long> received, long globalOrder) {
        var event = store.persist(globalOrder);
        await().atMost(Duration.ofSeconds(5)).untilAsserted(() -> {
            bus.publish(List.of(event));
            assertThat(received).contains(globalOrder);
        });
    }

    private static void sleepQuietly(Duration duration) {
        try {
            Thread.sleep(duration);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    @FunctionalInterface
    private interface Handler {
        void handle(long globalOrder, String thread);
    }

    private record TestTenant(String id) implements Tenant {
        @Override
        public String toString() {
            return id;
        }
    }
}
