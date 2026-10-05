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

package dk.trustworks.essentials.components.eventsourced.eventstore.postgresql;

import dk.trustworks.essentials.components.distributed.fencedlock.postgresql.PostgresqlFencedLockManager;
import dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.eventstream.AggregateType;
import dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.gap.*;
import dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.gap.PostgresqlEventStreamGapHandler.ResolveTransientGapsToPermanentGapsPromotionStrategy;
import dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.persistence.table_per_aggregate_type.*;
import dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.processor.EventProcessorIT;
import dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.serializer.json.EssentialsJSONEventSerializers;
import dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.subscription.*;
import dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.test_data.*;
import dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.transaction.EventStoreManagedUnitOfWorkFactory;
import dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.types.*;
import dk.trustworks.essentials.components.foundation.transaction.UnitOfWork;
import dk.trustworks.essentials.components.foundation.types.*;
import dk.trustworks.essentials.shared.functional.tuple.Pair;
import dk.trustworks.essentials.types.LongRange;
import org.jdbi.v3.core.Jdbi;
import org.jdbi.v3.postgres.PostgresPlugin;
import org.junit.jupiter.api.*;
import org.testcontainers.junit.jupiter.*;
import org.testcontainers.postgresql.PostgreSQLContainer;

import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

import static dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.gap.PostgresqlEventStreamGapHandler.*;
import static dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.gap.SubscriptionGapHandler.MAX_AWAITED_ORDERS_PER_GAP_END;
import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

/**
 * The event table's global order sequence is moved a million forward ({@code setval}) under a running <b>polling</b>
 * subscription - as a restore or a manual fix of the sequence would - and the next poll that reaches the event above the
 * jump finds a hole of a million orders. The {@link PostgresqlEventStreamGapHandler} records only the orders at its two
 * ends as transient gaps - those a transaction still in flight normally holds,
 * {@link SubscriptionGapHandler#MAX_AWAITED_ORDERS_PER_GAP_END} at each - so there are no million transient-gap rows for
 * every later poll to load, and none promoted to permanent ones. The subscription awaits the middle in memory only, until
 * the gap handler's give-up threshold has passed, re-querying it on every poll: a transaction holding an order there that
 * commits within that window is delivered once, like any gap fill, and one that commits later is not delivered at all.
 * <p>
 * The polling counterpart of {@link dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.cdc.CdcEventStoreSequenceJumpIT}.
 */
@Testcontainers
class PollingSequenceJumpIT {
    private static final long          JUMP                   = 1_000_000;
    private static final int           BATCH_SIZE             = 1_000;
    private static final AtomicInteger AGGREGATE_TYPE_COUNTER = new AtomicInteger();

    @Container
    private static final PostgreSQLContainer postgreSQLContainer = new PostgreSQLContainer("postgres:18.4")
            .withDatabaseName("event-store")
            .withUsername("test-user")
            .withPassword("secret-password");

    private          Jdbi                                                                    jdbi;
    private          AggregateType                                                           aggregateType;
    private          EventStoreManagedUnitOfWorkFactory                                      unitOfWorkFactory;
    private          PostgresqlEventStore<SeparateTablePerAggregateEventStreamConfiguration> eventStore;
    private          EventStoreSubscriptionManager                                           eventStoreSubscriptionManager;
    private          ExecutorService                                                         inFlightWriters;
    /**
     * What the gap handler promotes transient gaps with - and how long a subscription awaits a middle in memory, read when
     * it subscribes
     */
    private volatile ResolveTransientGapsToPermanentGapsPromotionStrategy                    promotionStrategy;

    @BeforeEach
    void setup() {
        jdbi = Jdbi.create(postgreSQLContainer.getJdbcUrl(),
                           postgreSQLContainer.getUsername(),
                           postgreSQLContainer.getPassword());
        jdbi.installPlugin(new PostgresPlugin());
        // A fresh aggregate type per test: own event table, own sequence, own gap rows
        aggregateType = AggregateType.of("JumpOrders" + AGGREGATE_TYPE_COUNTER.incrementAndGet());
        unitOfWorkFactory = new EventStoreManagedUnitOfWorkFactory(jdbi);
        var persistenceStrategy = new SeparateTablePerAggregateTypePersistenceStrategy(jdbi,
                                                                                       unitOfWorkFactory,
                                                                                       new EventProcessorIT.TestPersistableEventMapper(),
                                                                                       SeparateTablePerAggregateTypeEventStreamConfigurationFactory.defaultConfiguration(EssentialsJSONEventSerializers.create()));
        persistenceStrategy.addAggregateEventStreamConfiguration(aggregateType, OrderId.class);
        // The default promotion: transient gaps promoted - and a middle given up - after 120 s
        promotionStrategy = ResolveTransientGapsToPermanentGapsPromotionStrategy.thresholdBased(120);
        eventStore = PostgresqlEventStore.<SeparateTablePerAggregateEventStreamConfiguration>builder()
                                         .setUnitOfWorkFactory(unitOfWorkFactory)
                                         .setPersistenceStrategy(persistenceStrategy)
                                         .setEventStreamGapHandlerFactory(store -> new PostgresqlEventStreamGapHandler<>(unitOfWorkFactory,
                                                                                                                         Duration.ofSeconds(60),
                                                                                                                         ResolveTransientGapsToIncludeInQueryStrategy.defaultSelection(),
                                                                                                                         new ResolveTransientGapsToPermanentGapsPromotionStrategy() {
                                                                                                                             @Override
                                                                                                                             public Optional<Duration> permanentGapThreshold() {
                                                                                                                                 return promotionStrategy.permanentGapThreshold();
                                                                                                                             }

                                                                                                                             @Override
                                                                                                                             public List<GlobalEventOrder> resolveTransientGapsReadyToBePromotedToPermanentGaps(AggregateType forAggregateType,
                                                                                                                                                                                                                List<Pair<GlobalEventOrder, OffsetDateTime>> allTransientGaps) {
                                                                                                                                 return promotionStrategy.resolveTransientGapsReadyToBePromotedToPermanentGaps(forAggregateType, allTransientGaps);
                                                                                                                             }
                                                                                                                         }))
                                         .build();

        eventStoreSubscriptionManager = EventStoreSubscriptionManager.builder()
                                                                     .setEventStore(eventStore)
                                                                     .setEventStorePollingBatchSize(BATCH_SIZE)
                                                                     .setEventStorePollingInterval(Duration.ofMillis(5))
                                                                     // No back-off: an empty poll is followed by the next after the polling interval
                                                                     .setEventStorePollingOptimizerFactory(eventStreamLogName -> EventStorePollingOptimizer.None())
                                                                     .setFencedLockManager(PostgresqlFencedLockManager.builder()
                                                                                                                      .setJdbi(jdbi)
                                                                                                                      .setUnitOfWorkFactory(unitOfWorkFactory)
                                                                                                                      .setLockManagerInstanceId("node-1")
                                                                                                                      .setLockTimeOut(Duration.ofSeconds(3))
                                                                                                                      .setLockConfirmationInterval(Duration.ofMillis(500))
                                                                                                                      .build())
                                                                     .setSnapshotResumePointsEvery(Duration.ofSeconds(1))
                                                                     .setDurableSubscriptionRepository(new PostgresqlDurableSubscriptionRepository(jdbi, eventStore))
                                                                     .build();
        eventStoreSubscriptionManager.start();
        inFlightWriters = Executors.newCachedThreadPool();
    }

    @AfterEach
    void cleanup() {
        inFlightWriters.shutdownNow();
        unitOfWorkFactory.getCurrentUnitOfWork().ifPresent(UnitOfWork::rollback);
        assertThat(unitOfWorkFactory.getCurrentUnitOfWork()).isEmpty();
        if (eventStoreSubscriptionManager != null) {
            eventStoreSubscriptionManager.stop();
        }
    }

    @Test
    void a_sequence_moved_a_million_forward_records_only_the_ends_and_delivers_late_commits_at_an_end_and_in_the_middle_once() throws Exception {
        var received     = new CopyOnWriteArrayList<Long>();
        var subscriberId = SubscriberId.of("orders-polling-sequence-jump");
        appendOrders(3);
        long head = highestPersisted();
        var subscription = eventStoreSubscriptionManager.subscribeToAggregateEventsAsynchronously(subscriberId,
                                                                                                  aggregateType,
                                                                                                  GlobalEventOrder.FIRST_GLOBAL_EVENT_ORDER,
                                                                                                  Optional.empty(),
                                                                                                  event -> received.add(event.globalEventOrder().longValue()));
        await().atMost(Duration.ofSeconds(15)).untilAsserted(() -> assertThat(received).containsExactly(head - 2, head - 1, head));

        // A writer takes the next order (head + 1) before the sequence moves; another one takes an order in the middle
        // of what becomes the gap (a large append would). Both commit only after the event above the jump
        long lowerEndOrder = head + 1;
        var  lowerEnd      = inFlightWriter();
        long middleOrder   = lowerEndOrder + JUMP / 2;
        moveSequenceTo(middleOrder - 1);
        var middle = inFlightWriter();

        moveSequenceTo(lowerEndOrder + JUMP);
        appendOrders(1);
        long jumped = highestPersisted();
        assertThat(jumped).isEqualTo(lowerEndOrder + JUMP + 1);

        await().atMost(Duration.ofSeconds(90)).untilAsserted(() -> assertThat(received).contains(jumped));
        // Reconciled before the event was handed on: only the two ends of the gap, nothing permanent
        assertThat(transientGapCount(subscriberId)).isEqualTo(2L * MAX_AWAITED_ORDERS_PER_GAP_END);
        assertThat(transientGapCount(subscriberId, lowerEndOrder + MAX_AWAITED_ORDERS_PER_GAP_END, jumped - 1 - MAX_AWAITED_ORDERS_PER_GAP_END))
                .as("the middle of the gap is not recorded")
                .isZero();
        assertThat(permanentGapCount()).isZero();

        // The middle commits: awaited in memory, so delivered
        middle.commit();
        await().atMost(Duration.ofSeconds(30)).untilAsserted(() -> assertThat(received).contains(middleOrder));
        // The lower end commits: a transient gap, so delivered
        lowerEnd.commit();
        await().atMost(Duration.ofSeconds(30)).untilAsserted(() -> assertThat(received).contains(lowerEndOrder));

        // And later events keep coming
        appendOrders(1);
        long after = jumped + 1;
        assertThat(highestPersisted()).isEqualTo(after);
        await().atMost(Duration.ofSeconds(30)).untilAsserted(() -> assertThat(received).contains(after));
        // A few more polls, in which a late commit would show up again if it were read again
        Thread.sleep(500);

        assertThat(received).as("each event once").containsExactly(head - 2, head - 1, head, jumped, middleOrder, lowerEndOrder, after);
        assertThat(subscription.currentResumePoint().orElseThrow().getResumeFromAndIncluding().longValue())
                .as("the resume point never moved back to a late commit")
                .isGreaterThan(after);
        assertThat(transientGapCount(subscriberId)).isLessThanOrEqualTo(2L * MAX_AWAITED_ORDERS_PER_GAP_END);
        assertThat(permanentGapCount()).isZero();
        subscription.stop();
    }

    @Test
    void the_middle_of_a_wide_gap_is_given_up_after_the_gap_timeout_without_writing_anything() throws Exception {
        var gapTimeout = Duration.ofSeconds(3);
        promotionStrategy = ResolveTransientGapsToPermanentGapsPromotionStrategy.thresholdBased((int) gapTimeout.toSeconds());
        var received     = new CopyOnWriteArrayList<Long>();
        var subscriberId = SubscriberId.of("orders-polling-sequence-jump-timeout");
        appendOrders(3);
        long head = highestPersisted();
        var subscription = eventStoreSubscriptionManager.subscribeToAggregateEventsAsynchronously(subscriberId,
                                                                                                  aggregateType,
                                                                                                  GlobalEventOrder.FIRST_GLOBAL_EVENT_ORDER,
                                                                                                  Optional.empty(),
                                                                                                  event -> received.add(event.globalEventOrder().longValue()));
        await().atMost(Duration.ofSeconds(15)).untilAsserted(() -> assertThat(received).containsExactly(head - 2, head - 1, head));

        long middleOrder = head + 1 + JUMP / 2;
        moveSequenceTo(middleOrder - 1);
        var middle = inFlightWriter();
        moveSequenceTo(head + 1 + JUMP);
        appendOrders(1);
        long jumped = highestPersisted();
        await().atMost(Duration.ofSeconds(90)).untilAsserted(() -> assertThat(received).contains(jumped));

        // Past the gap timeout the middle is no longer awaited: its late commit is not delivered
        Thread.sleep(gapTimeout.plusSeconds(1).toMillis());
        middle.commit();
        appendOrders(1);
        long after = jumped + 1;
        await().atMost(Duration.ofSeconds(30)).untilAsserted(() -> assertThat(received).contains(after));
        Thread.sleep(500);

        assertThat(received).containsExactly(head - 2, head - 1, head, jumped, after);
        long middleFrom = head + 1 + MAX_AWAITED_ORDERS_PER_GAP_END;
        long middleTo   = jumped - 1 - MAX_AWAITED_ORDERS_PER_GAP_END;
        assertThat(transientGapCount(subscriberId, middleFrom, middleTo)).as("no transient gap in the middle").isZero();
        assertThat(permanentGapCount(middleFrom, middleTo)).as("no permanent gap in the middle").isZero();
        subscription.stop();
    }

    @Test
    void the_lowest_global_order_persisted_within_a_range_is_found() {
        appendOrders(3);
        moveSequenceTo(1_000);
        appendOrders(1);
        var persistenceStrategy = eventStore.getPersistenceStrategy();

        unitOfWorkFactory.usingUnitOfWork(uow -> {
            assertThat(persistenceStrategy.findLowestGlobalEventOrderPersisted(uow, aggregateType, LongRange.from(1))).contains(GlobalEventOrder.of(1));
            assertThat(persistenceStrategy.findLowestGlobalEventOrderPersisted(uow, aggregateType, LongRange.from(4))).contains(GlobalEventOrder.of(1_001));
            assertThat(persistenceStrategy.findLowestGlobalEventOrderPersisted(uow, aggregateType, LongRange.between(2, 3))).contains(GlobalEventOrder.of(2));
            assertThat(persistenceStrategy.findLowestGlobalEventOrderPersisted(uow, aggregateType, LongRange.between(4, 1_000))).isEmpty();
            assertThat(persistenceStrategy.findLowestGlobalEventOrderPersisted(uow, aggregateType, LongRange.from(1_002))).isEmpty();
        });
    }

    /**
     * A transaction, on a thread of its own, that appends one event - taking the next global order - and commits only when
     * told to
     */
    private InFlightWriter inFlightWriter() throws Exception {
        var tookItsOrder = new CountDownLatch(1);
        var mayCommit    = new CountDownLatch(1);
        var writer = inFlightWriters.submit(() -> unitOfWorkFactory.usingUnitOfWork(() -> {
            appendOrder();
            tookItsOrder.countDown();
            if (!mayCommit.await(120, TimeUnit.SECONDS)) {
                throw new IllegalStateException("Never allowed to commit");
            }
        }));
        assertThat(tookItsOrder.await(10, TimeUnit.SECONDS)).isTrue();
        return new InFlightWriter(mayCommit, writer);
    }

    private record InFlightWriter(CountDownLatch mayCommit, Future<?> writer) {
        void commit() throws Exception {
            mayCommit.countDown();
            writer.get(10, TimeUnit.SECONDS);
        }
    }

    private long highestPersisted() {
        return unitOfWorkFactory.withUnitOfWork(() -> eventStore.findHighestGlobalEventOrderPersisted(aggregateType)).orElseThrow().longValue();
    }

    /**
     * {@code setval}: the next order handed out is {@code lastValue + 1}
     */
    private void moveSequenceTo(long lastValue) {
        var sequenceName = unitOfWorkFactory.withUnitOfWork(uow -> eventStore.getPersistenceStrategy()
                                                                             .resolveGlobalEventOrderSequenceName(uow, aggregateType)
                                                                             .orElseThrow());
        unitOfWorkFactory.usingUnitOfWork(uow -> uow.handle().createQuery("SELECT setval(:seq, :value)")
                                                    .bind("seq", sequenceName)
                                                    .bind("value", lastValue)
                                                    .mapTo(Long.class)
                                                    .one());
    }

    private long transientGapCount(SubscriberId subscriberId) {
        return transientGapCount(subscriberId, Long.MIN_VALUE, Long.MAX_VALUE);
    }

    private long transientGapCount(SubscriberId subscriberId, long fromInclusive, long toInclusive) {
        return unitOfWorkFactory.withUnitOfWork(uow -> uow.handle()
                                                          .createQuery("SELECT count(*) FROM " + TRANSIENT_SUBSCRIBER_GAPS_TABLE_NAME +
                                                                               " WHERE subscriber_id = :subscriber_id AND gap_global_event_order BETWEEN :from_inclusive AND :to_inclusive")
                                                          .bind("subscriber_id", subscriberId.toString())
                                                          .bind("from_inclusive", fromInclusive)
                                                          .bind("to_inclusive", toInclusive)
                                                          .mapTo(Long.class)
                                                          .one());
    }

    private long permanentGapCount() {
        return permanentGapCount(Long.MIN_VALUE, Long.MAX_VALUE);
    }

    private long permanentGapCount(long fromInclusive, long toInclusive) {
        return unitOfWorkFactory.withUnitOfWork(uow -> uow.handle()
                                                          .createQuery("SELECT count(*) FROM " + PERMANENT_GAPS_TABLE_NAME +
                                                                               " WHERE aggregate_type = :aggregate_type AND gap_global_event_order BETWEEN :from_inclusive AND :to_inclusive")
                                                          .bind("aggregate_type", aggregateType.toString())
                                                          .bind("from_inclusive", fromInclusive)
                                                          .bind("to_inclusive", toInclusive)
                                                          .mapTo(Long.class)
                                                          .one());
    }

    /**
     * Appends one event inside the current unit of work
     */
    private void appendOrder() {
        var orderId = OrderId.random();
        eventStore.appendToStream(aggregateType, orderId, EventOrder.NO_EVENTS_PREVIOUSLY_PERSISTED, List.of(new OrderEvent.OrderAdded(orderId, CustomerId.random(), 1)));
    }

    private void appendOrders(int count) {
        var orderId = OrderId.random();
        unitOfWorkFactory.usingUnitOfWork(() -> {
            var events = new ArrayList<OrderEvent.OrderAdded>();
            for (int i = 1; i <= count; i++) {
                events.add(new OrderEvent.OrderAdded(orderId, CustomerId.random(), i));
            }
            eventStore.appendToStream(aggregateType, orderId, EventOrder.NO_EVENTS_PREVIOUSLY_PERSISTED, events);
        });
    }
}
