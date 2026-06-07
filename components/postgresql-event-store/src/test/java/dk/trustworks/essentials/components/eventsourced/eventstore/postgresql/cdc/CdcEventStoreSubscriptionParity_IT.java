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
import dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.EventStoreSubscription;
import dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.PostgresqlEventStore;
import dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.eventstream.PersistedEvent;
import dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.gap.PostgresqlEventStreamGapHandler;
import dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.persistence.table_per_aggregate_type.*;
import dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.processor.EventProcessorIT;
import dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.serializer.json.JacksonJSONEventSerializer;
import dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.subscription.*;
import dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.test_data.*;
import dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.types.*;
import dk.trustworks.essentials.components.foundation.fencedlock.FencedLock;
import dk.trustworks.essentials.components.foundation.transaction.UnitOfWork;
import dk.trustworks.essentials.components.foundation.types.SubscriberId;
import org.junit.jupiter.api.*;

import java.time.Duration;
import java.util.*;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ConcurrentLinkedDeque;

import static dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.processor.EventProcessorIT.createObjectMapper;
import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

class CdcEventStoreSubscriptionParity_IT extends AbstractLogicalReplicationPostgresIT {

    private PostgresqlEventStore<SeparateTablePerAggregateEventStreamConfiguration> eventStore;
    private CdcEventStore                                                           cdcEventStore;
    private EventStoreSubscriptionManager                                           eventStoreSubscriptionManager;
    private DurableSubscriptionRepository durableSubscriptionRepository;

    @BeforeEach
    void setup() {
        var serializer = new JacksonJSONEventSerializer(createObjectMapper());
        var eventMapper = new EventProcessorIT.TestPersistableEventMapper();

        var persistenceStrategy = new SeparateTablePerAggregateTypePersistenceStrategy(
                jdbi,
                unitOfWorkFactory,
                eventMapper,
                SeparateTablePerAggregateTypeEventStreamConfigurationFactory.defaultConfiguration(serializer)
        );
        persistenceStrategy.addAggregateEventStreamConfiguration(ORDERS, OrderId.class);

        eventStore = new PostgresqlEventStore<>(unitOfWorkFactory, persistenceStrategy);

        var availability = new CdcAvailability(); // INACTIVE => fallback-to-polling path
        cdcEventStore = new CdcEventStore(
                eventStore,
                unitOfWorkFactory,
                new PostgresqlEventStreamGapHandler<>(unitOfWorkFactory),
                new CdcEventBus(),
                new CdcProperties(),
                availability
        );

        durableSubscriptionRepository = new PostgresqlDurableSubscriptionRepository(jdbi, cdcEventStore);

        eventStoreSubscriptionManager = EventStoreSubscriptionManager.createFor(
                cdcEventStore,
                50,
                Duration.ofMillis(50),
                new PostgresqlFencedLockManager(
                        jdbi,
                        unitOfWorkFactory,
                        Optional.of("node-1"),
                        Duration.ofSeconds(3),
                        Duration.ofMillis(500),
                        false
                ),
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
    void non_exclusive_async_subscription_behaves_like_polling_subscription() {
        var received = new CopyOnWriteArrayList<PersistedEvent>();
        var subscriberId = SubscriberId.of("orders-non-exclusive-parity");

        var subscription = eventStoreSubscriptionManager.subscribeToAggregateEventsAsynchronously(
                subscriberId,
                ORDERS,
                GlobalEventOrder.FIRST_GLOBAL_EVENT_ORDER,
                Optional.empty(),
                received::add
        );

        appendOrderEvents(3);

        await().atMost(Duration.ofSeconds(10)).untilAsserted(() -> {
            assertThat(received).hasSize(3);
            assertThat(received.stream().map(e -> e.globalEventOrder().longValue()).toList())
                    .containsExactly(1L, 2L, 3L);
        });

        subscription.stop();
        await().atMost(Duration.ofSeconds(5)).untilAsserted(() -> assertThat(subscription.isActive()).isFalse());

        assertThat(subscription.currentResumePoint()).isPresent();
        assertThat(subscription.currentResumePoint().get().getResumeFromAndIncluding()).isEqualTo(GlobalEventOrder.of(4));
        assertThat(durableSubscriptionRepository.getResumePoint(subscriberId, ORDERS))
                .isPresent()
                .get()
                .extracting(SubscriptionResumePoint::getResumeFromAndIncluding)
                .isEqualTo(GlobalEventOrder.of(4));
    }

    @Test
    void exclusive_async_subscription_behaves_like_polling_subscription() {
        var received = new CopyOnWriteArrayList<PersistedEvent>();
        var subscriberId = SubscriberId.of("orders-exclusive-parity");

        var subscription = eventStoreSubscriptionManager.exclusivelySubscribeToAggregateEventsAsynchronously(
                subscriberId,
                ORDERS,
                GlobalEventOrder.FIRST_GLOBAL_EVENT_ORDER,
                Optional.empty(),
                new FencedLockAwareSubscriber() {
                    @Override
                    public void onLockAcquired(FencedLock fencedLock, SubscriptionResumePoint subscriptionResumePoint) {
                    }

                    @Override
                    public void onLockReleased(FencedLock fencedLock) {
                    }
                },
                received::add
        );

        await().atMost(Duration.ofSeconds(10)).untilAsserted(() -> assertThat(subscription.isActive()).isTrue());

        appendOrderEvents(3);

        await().atMost(Duration.ofSeconds(10)).untilAsserted(() -> {
            assertThat(received).hasSize(3);
            assertThat(received.stream().map(e -> e.globalEventOrder().longValue()).toList())
                    .containsExactly(1L, 2L, 3L);
        });

        subscription.stop();
        await().atMost(Duration.ofSeconds(5)).untilAsserted(() -> assertThat(subscription.isActive()).isFalse());

        assertThat(subscription.currentResumePoint()).isPresent();
        assertThat(subscription.currentResumePoint().get().getResumeFromAndIncluding()).isEqualTo(GlobalEventOrder.of(4));
        assertThat(durableSubscriptionRepository.getResumePoint(subscriberId, ORDERS))
                .isPresent()
                .get()
                .extracting(SubscriptionResumePoint::getResumeFromAndIncluding)
                .isEqualTo(GlobalEventOrder.of(4));
    }

    @Test
    void batched_async_subscription_behaves_like_polling_subscription() {
        var receivedEvents = new ConcurrentLinkedDeque<PersistedEvent>();
        var receivedBatches = new ConcurrentLinkedDeque<List<PersistedEvent>>();
        var subscriberId = SubscriberId.of("orders-batched-parity");
        int maxBatchSize = 10;

        var subscription = eventStoreSubscriptionManager.batchSubscribeToAggregateEventsAsynchronously(
                subscriberId,
                ORDERS,
                GlobalEventOrder.FIRST_GLOBAL_EVENT_ORDER,
                Optional.empty(),
                maxBatchSize,
                Duration.ofMillis(50),
                new BatchedPersistedEventHandler() {
                    @Override
                    public int handleBatch(List<PersistedEvent> events) {
                        receivedBatches.add(new ArrayList<>(events));
                        receivedEvents.addAll(events);
                        return maxBatchSize;
                    }

                    @Override
                    public void onResetFrom(EventStoreSubscription subscription, GlobalEventOrder subscribeFromAndIncludingGlobalOrder) {
                    }
                }
        );

        appendOrderEvents(25);

        await().atMost(Duration.ofSeconds(10)).untilAsserted(() -> {
            assertThat(receivedEvents).hasSize(25);
            assertThat(receivedEvents.stream().map(e -> e.globalEventOrder().longValue()).toList())
                    .containsExactlyElementsOf(sequence(1L, 25L));
        });

        assertThat(receivedBatches).allMatch(batch -> batch.size() <= maxBatchSize);

        subscription.stop();
        await().atMost(Duration.ofSeconds(5)).untilAsserted(() -> assertThat(subscription.isActive()).isFalse());

        assertThat(durableSubscriptionRepository.getResumePoint(subscriberId, ORDERS))
                .isPresent()
                .get()
                .extracting(SubscriptionResumePoint::getResumeFromAndIncluding)
                .isEqualTo(GlobalEventOrder.of(26L));
    }

    @Test
    void batched_async_subscription_resumes_from_durable_resume_point_after_restart() {
        var subscriberId = SubscriberId.of("orders-batched-restart-parity");
        var firstRunEvents = new CopyOnWriteArrayList<PersistedEvent>();
        var firstRun = eventStoreSubscriptionManager.batchSubscribeToAggregateEventsAsynchronously(
                subscriberId,
                ORDERS,
                GlobalEventOrder.FIRST_GLOBAL_EVENT_ORDER,
                Optional.empty(),
                10,
                Duration.ofMillis(50),
                new BatchedPersistedEventHandler() {
                    @Override
                    public int handleBatch(List<PersistedEvent> events) {
                        firstRunEvents.addAll(events);
                        return 10;
                    }

                    @Override
                    public void onResetFrom(EventStoreSubscription subscription, GlobalEventOrder subscribeFromAndIncludingGlobalOrder) {
                    }
                }
        );

        appendOrderEvents(5);
        await().atMost(Duration.ofSeconds(10)).untilAsserted(() -> assertThat(firstRunEvents).hasSize(5));

        firstRun.unsubscribe();
        await().atMost(Duration.ofSeconds(5)).untilAsserted(() -> assertThat(firstRun.isActive()).isFalse());
        await().atMost(Duration.ofSeconds(5)).untilAsserted(() -> assertThat(durableSubscriptionRepository.getResumePoint(subscriberId, ORDERS))
                .isPresent()
                .get()
                .extracting(SubscriptionResumePoint::getResumeFromAndIncluding)
                .isEqualTo(GlobalEventOrder.of(6)));

        appendOrderEvents(3);

        var secondRunEvents = new CopyOnWriteArrayList<PersistedEvent>();
        var secondRun = eventStoreSubscriptionManager.batchSubscribeToAggregateEventsAsynchronously(
                subscriberId,
                ORDERS,
                GlobalEventOrder.FIRST_GLOBAL_EVENT_ORDER,
                Optional.empty(),
                10,
                Duration.ofMillis(50),
                new BatchedPersistedEventHandler() {
                    @Override
                    public int handleBatch(List<PersistedEvent> events) {
                        secondRunEvents.addAll(events);
                        return 10;
                    }

                    @Override
                    public void onResetFrom(EventStoreSubscription subscription, GlobalEventOrder subscribeFromAndIncludingGlobalOrder) {
                    }
                }
        );

        await().atMost(Duration.ofSeconds(10)).untilAsserted(() -> {
            assertThat(secondRunEvents).hasSize(3);
            assertThat(secondRunEvents.stream().map(e -> e.globalEventOrder().longValue()).toList())
                    .containsExactly(6L, 7L, 8L);
        });

        secondRun.stop();
        await().atMost(Duration.ofSeconds(5)).untilAsserted(() -> assertThat(secondRun.isActive()).isFalse());
    }

    @Test
    void batched_async_subscription_rewinds_after_poison_reset_and_persists_resume_point() {
        var subscriberId = SubscriberId.of("orders-batched-poison-parity");
        var received = new CopyOnWriteArrayList<Long>();
        var resets = new CopyOnWriteArrayList<Long>();

        var subscription = eventStoreSubscriptionManager.batchSubscribeToAggregateEventsAsynchronously(
                subscriberId,
                ORDERS,
                GlobalEventOrder.FIRST_GLOBAL_EVENT_ORDER,
                Optional.empty(),
                10,
                Duration.ofMillis(50),
                new BatchedPersistedEventHandler() {
                    @Override
                    public int handleBatch(List<PersistedEvent> events) {
                        events.forEach(event -> received.add(event.globalEventOrder().longValue()));
                        return 10;
                    }

                    @Override
                    public void onResetFrom(EventStoreSubscription subscription, GlobalEventOrder subscribeFromAndIncludingGlobalOrder) {
                        resets.add(subscribeFromAndIncludingGlobalOrder.longValue());
                    }
                }
        );

        appendOrderEvents(10);
        await().atMost(Duration.ofSeconds(10)).untilAsserted(() -> assertThat(received).hasSizeGreaterThanOrEqualTo(10));
        await().atMost(Duration.ofSeconds(10)).untilAsserted(() -> assertThat(durableSubscriptionRepository.getResumePoint(subscriberId, ORDERS))
                .isPresent()
                .get()
                .extracting(rp -> rp.getResumeFromAndIncluding().longValue())
                .isEqualTo(11L));

        var notifier = new SubscriptionResetOnPoisonNotifier(eventStoreSubscriptionManager, durableSubscriptionRepository);
        notifier.onPoison(ORDERS, List.of(GlobalEventOrder.of(5L)), "it-poison-batched");

        // pollDelay(ZERO): the poison reset rewinds the durable resume point via a *synchronous*
        // saveResumePoint inside overrideResumePoint, so the DB holds the reset value the instant
        // the reset completes. Re-processing then re-advances the resume point, and the periodic
        // (1s) snapshot persists that higher value shortly after. We must therefore observe the
        // rewind promptly — awaitility's default 100ms pollDelay can miss the window when
        // re-delivery is fast (e.g. the CDC-backed subscription's adaptive source). Checking from
        // t=0 catches the synchronously-persisted reset value deterministically.
        await().pollDelay(Duration.ZERO).atMost(Duration.ofSeconds(10)).untilAsserted(() -> assertThat(resets).contains(5L));
        await().pollDelay(Duration.ZERO).atMost(Duration.ofSeconds(10)).untilAsserted(() -> {
            var resume = durableSubscriptionRepository.getResumePoint(subscriberId, ORDERS).orElseThrow().getResumeFromAndIncluding().longValue();
            assertThat(resume).isLessThanOrEqualTo(5L);
        });
        await().atMost(Duration.ofSeconds(10)).untilAsserted(() -> {
            long countFive = received.stream().filter(go -> go == 5L).count();
            assertThat(countFive).isGreaterThanOrEqualTo(2L);
        });

        subscription.stop();
    }

    @Test
    void in_transaction_subscription_behaves_like_polling_subscription() {
        var received = new CopyOnWriteArrayList<PersistedEvent>();
        var subscriberId = SubscriberId.of("orders-in-tx-parity");

        var subscription = eventStoreSubscriptionManager.subscribeToAggregateEventsInTransaction(
                subscriberId,
                ORDERS,
                Optional.empty(),
                new TransactionalPersistedEventHandler() {
                    @Override
                    public void handle(PersistedEvent event, UnitOfWork unitOfWork) {
                        received.add(event);
                    }
                }
        );

        appendOrderEvents(6);

        await().atMost(Duration.ofSeconds(10)).untilAsserted(() -> {
            assertThat(received).hasSize(6);
            assertThat(received.stream().map(e -> e.globalEventOrder().longValue()).toList())
                    .containsExactlyElementsOf(sequence(1L, 6L));
        });

        subscription.stop();
    }

    @Test
    void in_transaction_subscription_restart_only_receives_live_events_and_does_not_store_resume_point() {
        var subscriberId = SubscriberId.of("orders-in-tx-restart-parity");
        var firstRunEvents = new CopyOnWriteArrayList<PersistedEvent>();

        var firstRun = eventStoreSubscriptionManager.subscribeToAggregateEventsInTransaction(
                subscriberId,
                ORDERS,
                Optional.empty(),
                (event, unitOfWork) -> firstRunEvents.add(event)
        );

        appendOrderEvents(3);
        await().atMost(Duration.ofSeconds(10)).untilAsserted(() -> assertThat(firstRunEvents).hasSize(3));

        firstRun.unsubscribe();
        appendOrderEvents(2);

        var secondRunEvents = new CopyOnWriteArrayList<PersistedEvent>();
        var secondRun = eventStoreSubscriptionManager.subscribeToAggregateEventsInTransaction(
                subscriberId,
                ORDERS,
                Optional.empty(),
                (event, unitOfWork) -> secondRunEvents.add(event)
        );

        appendOrderEvents(1);
        await().atMost(Duration.ofSeconds(10)).untilAsserted(() -> {
            assertThat(secondRunEvents).hasSize(1);
            assertThat(secondRunEvents.getFirst().globalEventOrder()).isEqualTo(GlobalEventOrder.of(6));
        });

        secondRun.stop();
        assertThat(durableSubscriptionRepository.getResumePoint(subscriberId, ORDERS)).isEmpty();
    }

    @Test
    void in_transaction_subscription_ignores_poison_reset_notifications() {
        var subscriberId = SubscriberId.of("orders-in-tx-poison-parity");
        var received = new CopyOnWriteArrayList<Long>();

        var subscription = eventStoreSubscriptionManager.subscribeToAggregateEventsInTransaction(
                subscriberId,
                ORDERS,
                Optional.empty(),
                (event, unitOfWork) -> received.add(event.globalEventOrder().longValue())
        );

        appendOrderEvents(5);
        await().atMost(Duration.ofSeconds(10)).untilAsserted(() -> assertThat(received).containsExactly(1L, 2L, 3L, 4L, 5L));

        new SubscriptionResetOnPoisonNotifier(eventStoreSubscriptionManager, durableSubscriptionRepository)
                .onPoison(ORDERS, List.of(GlobalEventOrder.of(3L)), "it-poison-in-tx");

        appendOrderEvents(2);
        await().atMost(Duration.ofSeconds(10)).untilAsserted(() -> assertThat(received).containsExactly(1L, 2L, 3L, 4L, 5L, 6L, 7L));
        assertThat(received.stream().filter(go -> go == 3L).count()).isEqualTo(1L);
        assertThat(durableSubscriptionRepository.getResumePoint(subscriberId, ORDERS)).isEmpty();

        subscription.stop();
    }

    @Test
    void exclusive_subscription_with_large_initial_gap_behaves_like_polling_subscription() {
        var sequenceName = unitOfWorkFactory.withUnitOfWork(uow ->
                eventStore.getPersistenceStrategy()
                          .resolveGlobalEventOrderSequenceName(uow, ORDERS)
                          .orElseThrow()
        );

        long initialGap = 1_000_000L;
        unitOfWorkFactory.usingUnitOfWork(uow ->
                uow.handle().createUpdate("SELECT setval(:sequenceName, :sequenceValue, false)")
                           .bind("sequenceName", sequenceName)
                           .bind("sequenceValue", initialGap)
                           .execute()
        );

        appendOrderEvents(8);

        var received = new CopyOnWriteArrayList<PersistedEvent>();
        var subscription = eventStoreSubscriptionManager.exclusivelySubscribeToAggregateEventsAsynchronously(
                SubscriberId.of("orders-large-gap-parity"),
                ORDERS,
                GlobalEventOrder.of(initialGap),
                Optional.empty(),
                new FencedLockAwareSubscriber() {
                    @Override
                    public void onLockAcquired(FencedLock fencedLock, SubscriptionResumePoint subscriptionResumePoint) {
                    }

                    @Override
                    public void onLockReleased(FencedLock fencedLock) {
                    }
                },
                received::add
        );

        await().atMost(Duration.ofSeconds(10)).untilAsserted(() -> {
            assertThat(received).hasSize(8);
            assertThat(received.getFirst().globalEventOrder().longValue()).isGreaterThanOrEqualTo(initialGap);
            assertThat(received.stream().map(e -> e.globalEventOrder().longValue()).toList())
                    .containsExactlyElementsOf(sequence(initialGap, initialGap + 7));
        });

        subscription.stop();
        await().atMost(Duration.ofSeconds(5)).untilAsserted(() -> assertThat(subscription.isActive()).isFalse());
    }

    private void appendOrderEvents(int count) {
        var orderId = OrderId.random();
        unitOfWorkFactory.usingUnitOfWork(() -> {
            var events = new ArrayList<OrderEvent.OrderAdded>();
            for (int i = 1; i <= count; i++) {
                events.add(new OrderEvent.OrderAdded(orderId, CustomerId.random(), i));
            }
            eventStore.appendToStream(
                    ORDERS,
                    orderId,
                    EventOrder.NO_EVENTS_PREVIOUSLY_PERSISTED,
                    events
            );
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
