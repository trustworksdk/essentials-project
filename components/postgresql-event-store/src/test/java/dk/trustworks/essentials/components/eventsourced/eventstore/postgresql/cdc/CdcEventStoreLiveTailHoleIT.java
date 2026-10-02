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

import dk.trustworks.essentials.components.distributed.fencedlock.postgresql.PostgresqlFencedLockManager;
import dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.PostgresqlEventStore;
import dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.gap.PostgresqlEventStreamGapHandler;
import dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.persistence.table_per_aggregate_type.*;
import dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.processor.EventProcessorIT;
import dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.serializer.json.*;
import dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.subscription.*;
import dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.test_data.*;
import dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.types.*;
import dk.trustworks.essentials.components.foundation.transaction.UnitOfWork;
import dk.trustworks.essentials.components.foundation.types.SubscriberId;
import dk.trustworks.essentials.types.LongRange;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.*;

import java.time.Duration;
import java.util.*;
import java.util.concurrent.CopyOnWriteArrayList;

import static dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.processor.EventProcessorIT.createObjectMapper;
import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

/**
 * A hole in the live tail of a subscription started while CDC is ACTIVE ({@code BackfillThenLiveOrdered}) must not hold
 * back the events after it.
 * <p>
 * While the subscription is live, a {@code global_event_order} value is taken but never committed (a {@code nextval}
 * burn - the deterministic equivalent of a rolled-back {@code IDENTITY}), leaving a hole that never reaches the bus. The
 * next committed event arrives on the bus <i>above</i> that hole. The drain used to advance strictly by one past the
 * head, so it parked on the hole until {@code eventBus.liveDrainStallThreshold} (three minutes by default) raised
 * {@link CdcLiveDrainStalledException} and re-subscribed the subscription through its gap-handler-aware backfill. Now the
 * event is delivered as soon as it arrives - with the threshold left at its default - and no stall is detected.
 * <p>
 * The CDC bus is fed directly (exactly as {@code CdcDispatcher} would, via {@link CdcEventBus#publish}) and availability
 * is forced ACTIVE, so the test is deterministic and does not depend on WAL-replication timing - the WAL
 * tailer/dispatcher path is covered by the other {@code *Wal2JsonIT} tests. A rolled-back transaction committing out of
 * order with a late one is covered by {@link CdcEventStoreCommitOrderIT}.
 */
class CdcEventStoreLiveTailHoleIT extends AbstractLogicalReplicationPostgresIT {

    private PostgresqlEventStore<SeparateTablePerAggregateEventStreamConfiguration> eventStore;
    private CdcEventStore<SeparateTablePerAggregateEventStreamConfiguration> cdcEventStore;
    private CdcEventBus                                                             cdcBus;
    private CdcAvailability                                                         availability;
    private MeterRegistry                                                           meterRegistry;
    private EventStoreSubscriptionManager                                           eventStoreSubscriptionManager;
    private DurableSubscriptionRepository                                           durableSubscriptionRepository;

    @BeforeEach
    void setup() {
        var serializer  = EssentialsJSONEventSerializers.create();
        var eventMapper = new EventProcessorIT.TestPersistableEventMapper();

        var persistenceStrategy = new SeparateTablePerAggregateTypePersistenceStrategy(
                jdbi,
                unitOfWorkFactory,
                eventMapper,
                SeparateTablePerAggregateTypeEventStreamConfigurationFactory.defaultConfiguration(serializer)
        );
        persistenceStrategy.addAggregateEventStreamConfiguration(ORDERS, OrderId.class);

        eventStore = new PostgresqlEventStore<>(unitOfWorkFactory, persistenceStrategy);

        cdcBus        = new CdcEventBus();
        availability  = new CdcAvailability();
        meterRegistry = new SimpleMeterRegistry();

        // The live-drain stall threshold left at its default (three minutes): the hole must not wait for it
        var cdcProperties = new CdcProperties();

        cdcEventStore = new CdcEventStore<>(
                eventStore,
                unitOfWorkFactory,
                new PostgresqlEventStreamGapHandler<>(unitOfWorkFactory),
                cdcBus,
                cdcProperties,
                availability,
                Optional.of(meterRegistry)
        );

        // Force ACTIVE so pollEvents takes the BackfillThenLiveOrdered path (CDC bus as live source)
        availability.active("it-live-drain-stall-slot");

        durableSubscriptionRepository = new PostgresqlDurableSubscriptionRepository(jdbi, cdcEventStore);
        eventStoreSubscriptionManager = EventStoreSubscriptionManager.createFor(
                cdcEventStore,
                50,
                Duration.ofMillis(50),
                PostgresqlFencedLockManager.builder()
                                           .setJdbi(jdbi)
                                           .setUnitOfWorkFactory(unitOfWorkFactory)
                                           .setLockManagerInstanceId("node-1")
                                           .setLockTimeOut(Duration.ofSeconds(3))
                                           .setLockConfirmationInterval(Duration.ofMillis(500))
                                           .build(),
                Duration.ofSeconds(1),
                durableSubscriptionRepository
        );
        eventStoreSubscriptionManager.start();
    }

    @AfterEach
    void cleanup() {
        unitOfWorkFactory.getCurrentUnitOfWork().ifPresent(UnitOfWork::rollback);
        assertThat(unitOfWorkFactory.getCurrentUnitOfWork()).isEmpty();
        if (eventStoreSubscriptionManager != null) {
            eventStoreSubscriptionManager.stop();
        }
    }

    @Test
    void a_permanent_hole_in_the_live_tail_does_not_hold_back_the_event_after_it() {
        var received     = new CopyOnWriteArrayList<Long>();
        var subscriberId = SubscriberId.of("orders-live-tail-hole");

        // 1) Pre-hole events are committed BEFORE subscribing so they fall in the backfill range. In the
        //    ACTIVE path the live source is the CDC bus (no DB polling), so only ≤ head events are served
        //    by backfill — events committed after subscription must arrive via the bus (step 3).
        appendOrders(3);
        long head = unitOfWorkFactory.withUnitOfWork(() -> eventStore.findHighestGlobalEventOrderPersisted(ORDERS))
                                     .orElseThrow()
                                     .longValue();   // == 3 on a fresh container

        var subscription = eventStoreSubscriptionManager.subscribeToAggregateEventsAsynchronously(
                subscriberId,
                ORDERS,
                GlobalEventOrder.FIRST_GLOBAL_EVENT_ORDER,
                Optional.empty(),
                event -> received.add(event.globalEventOrder().longValue())
        );

        // Subscription catches up via backfill and goes live.
        await().atMost(Duration.ofSeconds(15)).untilAsserted(() ->
                assertThat(received).containsExactlyElementsOf(sequence(1L, head)));

        // 2) Burn the next global_event_order value (== head+1) without committing an event — a permanent
        //    hole in the live tail, right after the head the backfill read.
        long hole = head + 1;
        burnNextGlobalEventOrderValue();

        // 3) Commit a real event AFTER the hole and feed it to the live bus, as the dispatcher would.
        appendOrders(1);
        long postHoleOrder = unitOfWorkFactory.withUnitOfWork(() -> eventStore.findHighestGlobalEventOrderPersisted(ORDERS))
                                              .orElseThrow()
                                              .longValue();   // == head+2
        assertThat(postHoleOrder).isEqualTo(hole + 1);
        var postHoleEvent = unitOfWorkFactory.withUnitOfWork(() ->
                eventStore.loadEventsByGlobalOrder(ORDERS, LongRange.between(postHoleOrder, postHoleOrder), List.of()).toList());
        assertThat(postHoleEvent).hasSize(1);
        cdcBus.publish(postHoleEvent);

        // 4) Delivered at once - well within the stall threshold - past the hole
        await().atMost(Duration.ofSeconds(5)).untilAsserted(() ->
                assertThat(received).contains(postHoleOrder));

        assertThat(received).as("the burned/never-committed hole must never be delivered").doesNotContain(hole);
        var expected = sequence(1L, head);
        expected.add(postHoleOrder);
        assertThat(received).as("each event once").containsExactlyElementsOf(expected);
        assertThat(stallDetectedCount()).as("no stall to recover from").isZero();

        subscription.stop();
        await().atMost(Duration.ofSeconds(5)).untilAsserted(() -> assertThat(subscription.isActive()).isFalse());
    }

    private double stallDetectedCount() {
        var counter = meterRegistry.find("essentials.cdc.backfill_live.stall_detected").counter();
        return counter == null ? 0.0 : counter.count();
    }

    /** Consume the next value of the aggregate-type's global-order sequence without inserting a row. */
    private void burnNextGlobalEventOrderValue() {
        var sequenceName = unitOfWorkFactory.withUnitOfWork(uow ->
                eventStore.getPersistenceStrategy()
                          .resolveGlobalEventOrderSequenceName(uow, ORDERS)
                          .orElseThrow());
        unitOfWorkFactory.usingUnitOfWork(uow ->
                uow.handle().createQuery("SELECT nextval(:seq)")
                   .bind("seq", sequenceName)
                   .mapTo(Long.class)
                   .one());
    }

    private void appendOrders(int count) {
        var orderId = OrderId.random();
        unitOfWorkFactory.usingUnitOfWork(() -> {
            var events = new ArrayList<OrderEvent.OrderAdded>();
            for (int i = 1; i <= count; i++) {
                events.add(new OrderEvent.OrderAdded(orderId, CustomerId.random(), i));
            }
            eventStore.appendToStream(ORDERS, orderId, EventOrder.NO_EVENTS_PREVIOUSLY_PERSISTED, events);
        });
    }

    private static List<Long> sequence(long fromInclusive, long toInclusive) {
        var values = new ArrayList<Long>();
        for (long i = fromInclusive; i <= toInclusive; i++) {
            values.add(i);
        }
        return values;
    }
}
