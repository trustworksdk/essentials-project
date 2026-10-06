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
import dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.eventstream.*;
import dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.gap.*;
import dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.gap.PostgresqlEventStreamGapHandler.ResolveTransientGapsToPermanentGapsPromotionStrategy;
import dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.persistence.table_per_aggregate_type.*;
import dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.processor.EventProcessorIT;
import dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.serializer.json.EssentialsJSONEventSerializers;
import dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.subscription.*;
import dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.test_data.*;
import dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.transaction.EventStoreManagedUnitOfWorkFactory;
import dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.types.*;
import dk.trustworks.essentials.components.foundation.fencedlock.FencedLock;
import dk.trustworks.essentials.components.foundation.transaction.UnitOfWork;
import dk.trustworks.essentials.components.foundation.types.SubscriberId;
import org.jdbi.v3.core.Jdbi;
import org.jdbi.v3.postgres.PostgresPlugin;
import org.junit.jupiter.api.*;
import org.testcontainers.junit.jupiter.*;
import org.testcontainers.postgresql.PostgreSQLContainer;

import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;

import static dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.gap.PostgresqlEventStreamGapHandler.*;
import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

/**
 * The middle of a wide gap - a global order sequence moved a million forward under a running <b>polling</b>
 * subscription, as in {@link PollingSequenceJumpIT} - is awaited in memory only, and must outlive the subscribe that
 * found it: a re-subscribe of the same subscriber on the same event store instance starts at the saved resume point,
 * above the gap, and a late commit into the middle reaches the subscriber only if the new subscribe still awaits it.
 * <p>
 * With the default {@link SubscriptionErrorPolicy} a transient handler failure is such a re-subscribe - the subscription
 * stops at the failed event and resumes by itself seconds later, well inside the middle's timeout - so before the middles
 * were kept across subscribes an ordinary handler failure after a wide gap lost the middle, without a restart. A
 * {@code resetFrom} moves the resume point deliberately, and drops the middle.
 */
@Testcontainers
class PollingGapMiddleResubscribeIT {
    private static final long          JUMP                   = 1_000_000;
    private static final int           BATCH_SIZE             = 1_000;
    private static final AtomicInteger AGGREGATE_TYPE_COUNTER = new AtomicInteger();

    @Container
    private static final PostgreSQLContainer postgreSQLContainer = new PostgreSQLContainer("postgres:18.4")
            .withDatabaseName("event-store")
            .withUsername("test-user")
            .withPassword("secret-password");

    private Jdbi                                                                    jdbi;
    private AggregateType                                                           aggregateType;
    private EventStoreManagedUnitOfWorkFactory                                      unitOfWorkFactory;
    private PostgresqlEventStore<SeparateTablePerAggregateEventStreamConfiguration> eventStore;
    private EventStoreSubscriptionManager                                           eventStoreSubscriptionManager;
    private ExecutorService                                                         inFlightWriters;
    private CopyOnWriteArrayList<Long>                                              received;
    /**
     * The global order whose first handling fails - none until a test sets it
     */
    private AtomicLong                                                              failOnceAt;
    private AtomicInteger                                                           failures;

    @BeforeEach
    void setup() {
        jdbi = Jdbi.create(postgreSQLContainer.getJdbcUrl(),
                           postgreSQLContainer.getUsername(),
                           postgreSQLContainer.getPassword());
        jdbi.installPlugin(new PostgresPlugin());
        // A fresh aggregate type per test: own event table, own sequence, own gap rows
        aggregateType = AggregateType.of("ResubscribeOrders" + AGGREGATE_TYPE_COUNTER.incrementAndGet());
        unitOfWorkFactory = new EventStoreManagedUnitOfWorkFactory(jdbi);
        var persistenceStrategy = new SeparateTablePerAggregateTypePersistenceStrategy(jdbi,
                                                                                       unitOfWorkFactory,
                                                                                       new EventProcessorIT.TestPersistableEventMapper(),
                                                                                       SeparateTablePerAggregateTypeEventStreamConfigurationFactory.defaultConfiguration(EssentialsJSONEventSerializers.create()));
        persistenceStrategy.addAggregateEventStreamConfiguration(aggregateType, OrderId.class);
        eventStore = PostgresqlEventStore.<SeparateTablePerAggregateEventStreamConfiguration>builder()
                                         .setUnitOfWorkFactory(unitOfWorkFactory)
                                         .setPersistenceStrategy(persistenceStrategy)
                                         // The default promotion: transient gaps promoted - and a middle given up - after 120 s
                                         .setEventStreamGapHandlerFactory(store -> new PostgresqlEventStreamGapHandler<>(unitOfWorkFactory,
                                                                                                                         Duration.ofSeconds(60),
                                                                                                                         ResolveTransientGapsToIncludeInQueryStrategy.defaultSelection(),
                                                                                                                         ResolveTransientGapsToPermanentGapsPromotionStrategy.thresholdBased(120)))
                                         .build();

        eventStoreSubscriptionManager = EventStoreSubscriptionManager.builder()
                                                                     .setEventStore(eventStore)
                                                                     .setEventStorePollingBatchSize(BATCH_SIZE)
                                                                     .setEventStorePollingInterval(Duration.ofMillis(5))
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
                                                                     // Stop at a failed event, and resume 200 ms later - the default policy resumes after 10 s
                                                                     .setSubscriptionErrorPolicy(SubscriptionErrorPolicy.stop()
                                                                                                                        .withAutoResume(SubscriptionErrorPolicy.AutoResume.unlimited(Duration.ofMillis(200),
                                                                                                                                                                                     Duration.ofMillis(200))))
                                                                     .build();
        eventStoreSubscriptionManager.start();
        inFlightWriters = Executors.newCachedThreadPool();
        received = new CopyOnWriteArrayList<>();
        failOnceAt = new AtomicLong(-1);
        failures = new AtomicInteger();
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
    void the_middle_of_a_wide_gap_survives_the_resume_after_the_error_policy_stopped_the_subscription_and_is_delivered_once() throws Exception {
        var subscription = eventStoreSubscriptionManager.subscribeToAggregateEventsAsynchronously(SubscriberId.of("orders-middle-error-policy-resume"),
                                                                                                  aggregateType,
                                                                                                  GlobalEventOrder.FIRST_GLOBAL_EVENT_ORDER,
                                                                                                  Optional.empty(),
                                                                                                  this::handle);
        failsOnceAfterAWideGapResumesAndDeliversTheMiddleOnce(subscription);
    }

    @Test
    void the_middle_of_a_wide_gap_survives_the_resume_of_an_exclusive_subscription_that_keeps_its_lock() throws Exception {
        var subscription = eventStoreSubscriptionManager.exclusivelySubscribeToAggregateEventsAsynchronously(SubscriberId.of("orders-middle-exclusive-resume"),
                                                                                                             aggregateType,
                                                                                                             GlobalEventOrder.FIRST_GLOBAL_EVENT_ORDER,
                                                                                                             Optional.empty(),
                                                                                                             new FencedLockAwareSubscriber() {
                                                                                                                 @Override
                                                                                                                 public void onLockAcquired(FencedLock fencedLock, SubscriptionResumePoint resumeFromAndIncluding) {
                                                                                                                 }

                                                                                                                 @Override
                                                                                                                 public void onLockReleased(FencedLock fencedLock) {
                                                                                                                 }
                                                                                                             },
                                                                                                             this::handle);
        failsOnceAfterAWideGapResumesAndDeliversTheMiddleOnce(subscription);
    }

    @Test
    void the_middle_of_a_wide_gap_survives_a_stop_and_start_of_the_subscription() throws Exception {
        var subscription = eventStoreSubscriptionManager.subscribeToAggregateEventsAsynchronously(SubscriberId.of("orders-middle-stop-start"),
                                                                                                  aggregateType,
                                                                                                  GlobalEventOrder.FIRST_GLOBAL_EVENT_ORDER,
                                                                                                  Optional.empty(),
                                                                                                  this::handle);
        appendOrders(3);
        long head = highestPersisted();
        await().atMost(Duration.ofSeconds(15)).untilAsserted(() -> assertThat(received).containsExactly(head - 2, head - 1, head));
        var hole = openAWideGapWithAnInFlightWriterInItsMiddle();
        await().atMost(Duration.ofSeconds(30)).untilAsserted(() -> assertThat(received).contains(hole.jumped()));

        subscription.stop();
        subscription.start();
        appendOrders(1);
        long afterRestart = hole.jumped() + 1;
        await().atMost(Duration.ofSeconds(15)).untilAsserted(() -> assertThat(received).contains(afterRestart));

        // Committed late, within the gap timeout: the restarted subscription still awaits the middle
        hole.middle().commit();
        await().atMost(Duration.ofSeconds(15)).untilAsserted(() -> assertThat(received).contains(hole.middleOrder()));

        appendOrders(1);
        long after = afterRestart + 1;
        await().atMost(Duration.ofSeconds(15)).untilAsserted(() -> assertThat(received).contains(after));
        // A few more polls, in which a late commit would show up again if it were read again
        Thread.sleep(500);
        assertThat(received).as("each event once").containsExactly(head - 2, head - 1, head, hole.jumped(), afterRestart, hole.middleOrder(), after);
        subscription.stop();
    }

    @Test
    void a_reset_past_the_middle_of_a_wide_gap_drops_it() throws Exception {
        var subscription = eventStoreSubscriptionManager.subscribeToAggregateEventsAsynchronously(SubscriberId.of("orders-middle-reset"),
                                                                                                  aggregateType,
                                                                                                  GlobalEventOrder.FIRST_GLOBAL_EVENT_ORDER,
                                                                                                  Optional.empty(),
                                                                                                  this::handle);
        appendOrders(3);
        long head = highestPersisted();
        await().atMost(Duration.ofSeconds(15)).untilAsserted(() -> assertThat(received).containsExactly(head - 2, head - 1, head));
        var hole = openAWideGapWithAnInFlightWriterInItsMiddle();
        await().atMost(Duration.ofSeconds(30)).untilAsserted(() -> assertThat(received).contains(hole.jumped()));

        // The resume point moves deliberately, past the gap: what lies below it is not owed to the subscriber any more
        subscription.resetFrom(GlobalEventOrder.of(hole.jumped() + 1), resetFrom -> {
        });
        hole.middle().commit();
        appendOrders(1);
        long after = hole.jumped() + 1;
        await().atMost(Duration.ofSeconds(15)).untilAsserted(() -> assertThat(received).contains(after));
        // Polls in which the middle would be read, were it still awaited
        Thread.sleep(1_000);

        assertThat(received).as("the middle is not delivered after the reset").containsExactly(head - 2, head - 1, head, hole.jumped(), after);
        subscription.stop();
    }

    /**
     * Three events, a wide gap with a writer holding an order in its middle, and the event above the gap; then an event
     * that fails once - the error policy stops the subscription at it and resumes it 200 ms later, from it. The middle
     * then commits, and is delivered once
     */
    private void failsOnceAfterAWideGapResumesAndDeliversTheMiddleOnce(EventStoreSubscription subscription) throws Exception {
        appendOrders(3);
        long head = highestPersisted();
        await().atMost(Duration.ofSeconds(15)).untilAsserted(() -> assertThat(received).containsExactly(head - 2, head - 1, head));
        var hole = openAWideGapWithAnInFlightWriterInItsMiddle();
        await().atMost(Duration.ofSeconds(30)).untilAsserted(() -> assertThat(received).contains(hole.jumped()));

        long failing = hole.jumped() + 1;
        failOnceAt.set(failing);
        appendOrders(1);
        // Stopped at it, resumed from it - by a new subscribe, which starts above the gap
        await().atMost(Duration.ofSeconds(15)).untilAsserted(() -> assertThat(received).contains(failing));
        assertThat(failures).hasValue(1);

        // Committed late, within the gap timeout: the resumed subscription still awaits the middle
        hole.middle().commit();
        await().atMost(Duration.ofSeconds(15)).untilAsserted(() -> assertThat(received).contains(hole.middleOrder()));

        appendOrders(1);
        long after = failing + 1;
        await().atMost(Duration.ofSeconds(15)).untilAsserted(() -> assertThat(received).contains(after));
        // A few more polls, in which a late commit would show up again if it were read again
        Thread.sleep(500);
        assertThat(received).as("each event once").containsExactly(head - 2, head - 1, head, hole.jumped(), failing, hole.middleOrder(), after);
        assertThat(subscription.isStoppedByErrorPolicy()).isFalse();
        subscription.stop();
    }

    private void handle(PersistedEvent event) {
        long order = event.globalEventOrder().longValue();
        if (order == failOnceAt.get() && failures.compareAndSet(0, 1)) {
            throw new IllegalStateException("Handling global order " + order + " fails once");
        }
        received.add(order);
    }

    /**
     * @param middleOrder the order the in-flight writer holds, in the middle of the gap
     * @param middle      the writer, which commits when told to
     * @param jumped      the event right above the gap
     */
    private record WideGap(long middleOrder, InFlightWriter middle, long jumped) {
    }

    /**
     * A writer takes an order in the middle of what becomes a gap of a million orders - a large append would - and the
     * sequence moves past it; then one event is appended above the gap
     */
    private WideGap openAWideGapWithAnInFlightWriterInItsMiddle() throws Exception {
        long head        = highestPersisted();
        long middleOrder = head + 1 + JUMP / 2;
        moveSequenceTo(middleOrder - 1);
        var middle = inFlightWriter();
        moveSequenceTo(head + JUMP);
        appendOrders(1);
        long jumped = highestPersisted();
        assertThat(jumped).isEqualTo(head + JUMP + 1);
        return new WideGap(middleOrder, middle, jumped);
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
