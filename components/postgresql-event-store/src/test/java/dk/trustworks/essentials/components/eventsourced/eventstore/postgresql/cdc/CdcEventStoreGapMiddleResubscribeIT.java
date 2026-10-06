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
import dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.*;
import dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.eventstream.PersistedEvent;
import dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.gap.PostgresqlEventStreamGapHandler;
import dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.persistence.table_per_aggregate_type.*;
import dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.processor.EventProcessorIT;
import dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.serializer.json.EssentialsJSONEventSerializers;
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
import java.util.concurrent.atomic.*;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

/**
 * The CDC counterpart of {@link dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.PollingGapMiddleResubscribeIT}:
 * the middle of a wide gap a CDC subscription's delivery tracker awaits in memory only (see
 * {@link CdcEventStoreSequenceJumpIT}) outlives the subscription, so the subscriber's next one on the same event store
 * instance - the resume after the {@link SubscriptionErrorPolicy} stopped it, which starts above the gap - delivers a late
 * commit into the middle, once. A {@code resetFrom} past the gap drops it.
 * <p>
 * The CDC bus is fed directly, as {@link CdcEventStoreSequenceJumpIT} does, so the test does not depend on WAL timing.
 */
class CdcEventStoreGapMiddleResubscribeIT extends AbstractLogicalReplicationPostgresIT {
    private static final long JUMP = 1_000_000;

    private PostgresqlEventStore<SeparateTablePerAggregateEventStreamConfiguration> eventStore;
    private CdcEventBus                                                             cdcBus;
    private EventStoreSubscriptionManager                                           eventStoreSubscriptionManager;
    private ExecutorService                                                         inFlightWriter;
    private final CopyOnWriteArrayList<Long>                                        received   = new CopyOnWriteArrayList<>();
    /**
     * The global order whose first handling fails - none until a test sets it
     */
    private final AtomicLong                                                        failOnceAt = new AtomicLong(-1);
    private final AtomicInteger                                                     failures   = new AtomicInteger();

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
        availability.active("it-gap-middle-resubscribe-slot");

        eventStoreSubscriptionManager = EventStoreSubscriptionManager.builder()
                                                                     .setEventStore(cdcEventStore)
                                                                     .setEventStorePollingBatchSize(50)
                                                                     .setEventStorePollingInterval(Duration.ofMillis(50))
                                                                     .setFencedLockManager(PostgresqlFencedLockManager.builder()
                                                                                                                      .setJdbi(jdbi)
                                                                                                                      .setUnitOfWorkFactory(unitOfWorkFactory)
                                                                                                                      .setLockManagerInstanceId("node-1")
                                                                                                                      .setLockTimeOut(Duration.ofSeconds(3))
                                                                                                                      .setLockConfirmationInterval(Duration.ofMillis(500))
                                                                                                                      .build())
                                                                     .setSnapshotResumePointsEvery(Duration.ofSeconds(1))
                                                                     .setDurableSubscriptionRepository(new PostgresqlDurableSubscriptionRepository(jdbi, cdcEventStore))
                                                                     // Stop at a failed event, and resume 200 ms later - the default policy resumes after 10 s
                                                                     .setSubscriptionErrorPolicy(SubscriptionErrorPolicy.stop()
                                                                                                                        .withAutoResume(SubscriptionErrorPolicy.AutoResume.unlimited(Duration.ofMillis(200),
                                                                                                                                                                                     Duration.ofMillis(200))))
                                                                     .build();
        eventStoreSubscriptionManager.start();
        inFlightWriter = Executors.newFixedThreadPool(2);
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
    void the_middle_of_a_wide_gap_survives_the_resume_after_the_error_policy_stopped_the_subscription_and_is_delivered_once() throws Exception {
        var subscription = subscribe(SubscriberId.of("orders-cdc-middle-error-policy-resume"));
        var gap          = openAWideGapWithAnInFlightWriterInItsMiddle();

        long failing = gap.jumped() + 1;
        failOnceAt.set(failing);
        appendOrders(1);
        publish(failing);
        // Stopped at it, resumed from it - by a new subscription, which starts above the gap: it back-fills the event,
        // after attaching to the bus
        await().atMost(Duration.ofSeconds(15)).untilAsserted(() -> assertThat(received).contains(failing));
        assertThat(failures).hasValue(1);

        // Committed late, within the gap timeout: the resumed subscription still awaits the middle
        gap.mayCommit().countDown();
        gap.inMiddle().get(10, TimeUnit.SECONDS);
        publish(gap.inMiddleOrder());
        await().atMost(Duration.ofSeconds(15)).untilAsserted(() -> assertThat(received).contains(gap.inMiddleOrder()));

        appendOrders(1);
        long after = highestPersisted();
        publish(after);
        await().atMost(Duration.ofSeconds(15)).untilAsserted(() -> assertThat(received).contains(after));
        assertThat(received).as("each event once").containsExactly(1L, 2L, 3L, gap.jumped(), failing, gap.inMiddleOrder(), after);
        assertThat(subscription.isStoppedByErrorPolicy()).isFalse();
        subscription.stop();
    }

    @Test
    void a_reset_past_the_middle_of_a_wide_gap_drops_it() throws Exception {
        var subscription = subscribe(SubscriberId.of("orders-cdc-middle-reset"));
        var gap          = openAWideGapWithAnInFlightWriterInItsMiddle();

        // The resume point moves deliberately, past the gap: what lies below it is not owed to the subscriber any more
        subscription.resetFrom(GlobalEventOrder.of(gap.jumped() + 1), resetFrom -> {
        });
        appendOrders(1);
        long afterReset = highestPersisted();
        publish(afterReset);
        await().atMost(Duration.ofSeconds(15)).untilAsserted(() -> assertThat(received).contains(afterReset));

        gap.mayCommit().countDown();
        gap.inMiddle().get(10, TimeUnit.SECONDS);
        publish(gap.inMiddleOrder());
        appendOrders(1);
        long after = highestPersisted();
        publish(after);
        await().atMost(Duration.ofSeconds(15)).untilAsserted(() -> assertThat(received).contains(after));

        assertThat(received).as("the middle is not delivered after the reset").containsExactly(1L, 2L, 3L, gap.jumped(), afterReset, after);
        subscription.stop();
    }

    private EventStoreSubscription subscribe(SubscriberId subscriberId) {
        appendOrders(3);
        var subscription = eventStoreSubscriptionManager.subscribeToAggregateEventsAsynchronously(subscriberId,
                                                                                                  ORDERS,
                                                                                                  GlobalEventOrder.FIRST_GLOBAL_EVENT_ORDER,
                                                                                                  Optional.empty(),
                                                                                                  this::handle);
        await().atMost(Duration.ofSeconds(15)).untilAsserted(() -> assertThat(received).containsExactly(1L, 2L, 3L));
        return subscription;
    }

    private void handle(PersistedEvent event) {
        long order = event.globalEventOrder().longValue();
        if (order == failOnceAt.get() && failures.compareAndSet(0, 1)) {
            throw new IllegalStateException("Handling global order " + order + " fails once");
        }
        received.add(order);
    }

    /**
     * @param inMiddleOrder the order the in-flight writer holds, in the middle of the gap
     * @param inMiddle      the writer, which commits once {@code mayCommit} is counted down
     * @param jumped        the event right above the gap - delivered once this returns
     */
    private record WideGap(long inMiddleOrder, CountDownLatch mayCommit, Future<?> inMiddle, long jumped) {
    }

    /**
     * A writer takes an order in the middle of what becomes a gap of a million orders and commits only when told to; the
     * sequence moves past it, and one event is appended above the gap and delivered
     */
    private WideGap openAWideGapWithAnInFlightWriterInItsMiddle() throws Exception {
        long head          = highestPersisted();
        var  mayCommit     = new CountDownLatch(1);
        long inMiddleOrder = head + 1 + JUMP / 2;
        moveSequenceTo(inMiddleOrder - 1);
        var inMiddle = holdAnAppendOpen(mayCommit);
        moveSequenceTo(head + JUMP);
        appendOrders(1);
        long jumped = highestPersisted();
        assertThat(jumped).isEqualTo(head + JUMP + 1);
        publish(jumped);
        await().atMost(Duration.ofSeconds(15)).untilAsserted(() -> assertThat(received).contains(jumped));
        return new WideGap(inMiddleOrder, mayCommit, inMiddle, jumped);
    }

    /**
     * Appends one event on a thread of its own, and keeps its transaction open until {@code mayCommit}: returns once the
     * event took its global order
     */
    private Future<?> holdAnAppendOpen(CountDownLatch mayCommit) throws InterruptedException {
        var tookItsOrder = new CountDownLatch(1);
        var appending = inFlightWriter.submit(() -> unitOfWorkFactory.usingUnitOfWork(() -> {
            appendOrder();
            tookItsOrder.countDown();
            if (!mayCommit.await(30, TimeUnit.SECONDS)) {
                throw new IllegalStateException("Never allowed to commit");
            }
        }));
        assertThat(tookItsOrder.await(10, TimeUnit.SECONDS)).isTrue();
        return appending;
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
