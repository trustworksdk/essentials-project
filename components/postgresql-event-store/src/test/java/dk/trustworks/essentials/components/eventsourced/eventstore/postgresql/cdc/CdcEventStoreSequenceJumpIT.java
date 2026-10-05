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
import org.junit.jupiter.api.*;

import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;

import static dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.gap.PostgresqlEventStreamGapHandler.*;
import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

/**
 * The event table's global order sequence is moved a million forward ({@code setval}) under a running CDC subscription -
 * as a restore or a manual fix of the sequence would. The next event opens a gap of a million orders. The subscription
 * waits only for the orders at its two ends - those a transaction still in flight can hold - so it records a bounded
 * number of transient gaps before handing the event on, gives up the middle at once without making it a permanent gap,
 * and keeps delivering: the event a transaction that took its order before the jump commits afterwards included.
 * <p>
 * The CDC bus is fed directly, as {@link CdcEventStoreLiveTailHoleIT} does, so the test does not depend on WAL timing.
 */
class CdcEventStoreSequenceJumpIT extends AbstractLogicalReplicationPostgresIT {
    private static final long JUMP = 1_000_000;

    private PostgresqlEventStore<SeparateTablePerAggregateEventStreamConfiguration> eventStore;
    private CdcEventBus                                                             cdcBus;
    private EventStoreSubscriptionManager                                           eventStoreSubscriptionManager;
    private ExecutorService                                                         inFlightWriter;

    @BeforeEach
    void setup() {
        var persistenceStrategy = new SeparateTablePerAggregateTypePersistenceStrategy(jdbi,
                                                                                       unitOfWorkFactory,
                                                                                       new EventProcessorIT.TestPersistableEventMapper(),
                                                                                       SeparateTablePerAggregateTypeEventStreamConfigurationFactory.defaultConfiguration(EssentialsJSONEventSerializers.create()));
        persistenceStrategy.addAggregateEventStreamConfiguration(ORDERS, OrderId.class);
        eventStore = new PostgresqlEventStore<>(unitOfWorkFactory, persistenceStrategy);

        cdcBus = new CdcEventBus();
        var availability = new CdcAvailability();
        var cdcEventStore = new CdcEventStore<>(eventStore,
                                                unitOfWorkFactory,
                                                new PostgresqlEventStreamGapHandler<>(unitOfWorkFactory),
                                                cdcBus,
                                                new CdcProperties(),
                                                availability);
        // The CDC bus is the live source
        availability.active("it-sequence-jump-slot");

        eventStoreSubscriptionManager = EventStoreSubscriptionManager.createFor(cdcEventStore,
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
                                                                                new PostgresqlDurableSubscriptionRepository(jdbi, cdcEventStore));
        eventStoreSubscriptionManager.start();
        inFlightWriter = Executors.newSingleThreadExecutor();
    }

    @AfterEach
    void cleanup() {
        inFlightWriter.shutdownNow();
        unitOfWorkFactory.getCurrentUnitOfWork().ifPresent(UnitOfWork::rollback);
        assertThat(unitOfWorkFactory.getCurrentUnitOfWork()).isEmpty();
        if (eventStoreSubscriptionManager != null) {
            eventStoreSubscriptionManager.stop();
        }
    }

    @Test
    void a_sequence_moved_a_million_forward_records_a_bounded_number_of_transient_gaps_and_keeps_delivering() throws Exception {
        var received     = new CopyOnWriteArrayList<Long>();
        var subscriberId = SubscriberId.of("orders-sequence-jump");
        appendOrders(3);
        long head = highestPersisted();
        var subscription = eventStoreSubscriptionManager.subscribeToAggregateEventsAsynchronously(subscriberId,
                                                                                                  ORDERS,
                                                                                                  GlobalEventOrder.FIRST_GLOBAL_EVENT_ORDER,
                                                                                                  Optional.empty(),
                                                                                                  event -> received.add(event.globalEventOrder().longValue()));
        await().atMost(Duration.ofSeconds(15)).untilAsserted(() -> assertThat(received).containsExactly(1L, 2L, 3L));

        // A writer takes the next order (head + 1) before the sequence moves, and commits only after the event above the jump
        var tookItsOrder = new CountDownLatch(1);
        var mayCommit    = new CountDownLatch(1);
        var inFlight = inFlightWriter.submit(() -> unitOfWorkFactory.usingUnitOfWork(() -> {
            appendOrder();
            tookItsOrder.countDown();
            if (!mayCommit.await(30, TimeUnit.SECONDS)) {
                throw new IllegalStateException("Never allowed to commit");
            }
        }));
        assertThat(tookItsOrder.await(10, TimeUnit.SECONDS)).isTrue();
        long inFlightOrder = head + 1;

        moveSequenceTo(inFlightOrder + JUMP);
        appendOrders(1);
        long jumped = highestPersisted();
        assertThat(jumped).isEqualTo(inFlightOrder + JUMP + 1);
        publish(jumped);

        await().atMost(Duration.ofSeconds(15)).untilAsserted(() -> assertThat(received).contains(jumped));
        // Recorded before the event was handed on: only the two ends of the gap, nothing given up as permanent
        assertThat(transientGapCount(subscriberId)).isPositive()
                                                   .isLessThanOrEqualTo(2L * CdcDeliveryTracker.MAX_AWAITED_ORDERS_PER_GAP_END);
        assertThat(permanentGapCount()).isZero();

        // The in-flight writer commits: its order lies at the lower end of the gap, which is waited for
        mayCommit.countDown();
        inFlight.get(10, TimeUnit.SECONDS);
        publish(inFlightOrder);
        await().atMost(Duration.ofSeconds(15)).untilAsserted(() -> assertThat(received).contains(inFlightOrder));

        // And later events keep coming
        appendOrders(1);
        long after = highestPersisted();
        publish(after);
        await().atMost(Duration.ofSeconds(15)).untilAsserted(() -> assertThat(received).contains(after));

        assertThat(received).as("each event once").containsExactly(1L, 2L, 3L, jumped, inFlightOrder, after);
        subscription.stop();
    }

    private void publish(long globalOrder) {
        var events = unitOfWorkFactory.withUnitOfWork(() -> eventStore.loadEventsByGlobalOrder(ORDERS, LongRange.only(globalOrder), List.of()).toList());
        assertThat(events).hasSize(1);
        cdcBus.publish(events);
    }

    private long highestPersisted() {
        return unitOfWorkFactory.withUnitOfWork(() -> eventStore.findHighestGlobalEventOrderPersisted(ORDERS)).orElseThrow().longValue();
    }

    /**
     * {@code setval}: the next order handed out is {@code lastValue + 1}
     */
    private void moveSequenceTo(long lastValue) {
        var sequenceName = unitOfWorkFactory.withUnitOfWork(uow -> eventStore.getPersistenceStrategy()
                                                                             .resolveGlobalEventOrderSequenceName(uow, ORDERS)
                                                                             .orElseThrow());
        unitOfWorkFactory.usingUnitOfWork(uow -> uow.handle().createQuery("SELECT setval(:seq, :value)")
                                                    .bind("seq", sequenceName)
                                                    .bind("value", lastValue)
                                                    .mapTo(Long.class)
                                                    .one());
    }

    private long transientGapCount(SubscriberId subscriberId) {
        return unitOfWorkFactory.withUnitOfWork(uow -> uow.handle()
                                                          .createQuery("SELECT count(*) FROM " + TRANSIENT_SUBSCRIBER_GAPS_TABLE_NAME + " WHERE subscriber_id = :subscriber_id")
                                                          .bind("subscriber_id", subscriberId.toString())
                                                          .mapTo(Long.class)
                                                          .one());
    }

    private long permanentGapCount() {
        return unitOfWorkFactory.withUnitOfWork(uow -> uow.handle()
                                                          .createQuery("SELECT count(*) FROM " + PERMANENT_GAPS_TABLE_NAME)
                                                          .mapTo(Long.class)
                                                          .one());
    }

    /**
     * Appends one event inside the current unit of work
     */
    private void appendOrder() {
        var orderId = OrderId.random();
        eventStore.appendToStream(ORDERS, orderId, EventOrder.NO_EVENTS_PREVIOUSLY_PERSISTED, List.of(new OrderEvent.OrderAdded(orderId, CustomerId.random(), 1)));
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
}
