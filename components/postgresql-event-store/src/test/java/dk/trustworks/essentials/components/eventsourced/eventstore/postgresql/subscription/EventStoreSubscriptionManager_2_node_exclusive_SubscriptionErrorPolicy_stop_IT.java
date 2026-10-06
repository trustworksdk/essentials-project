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

package dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.subscription;

import dk.trustworks.essentials.components.distributed.fencedlock.postgresql.PostgresqlFencedLockManager;
import dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.*;
import dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.eventstream.*;
import dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.persistence.*;
import dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.persistence.table_per_aggregate_type.*;
import dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.serializer.json.EssentialsJSONEventSerializers;
import dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.test_data.*;
import dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.transaction.EventStoreManagedUnitOfWorkFactory;
import dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.types.*;
import dk.trustworks.essentials.components.foundation.fencedlock.*;
import dk.trustworks.essentials.components.foundation.transaction.UnitOfWork;
import dk.trustworks.essentials.components.foundation.types.*;
import org.awaitility.Awaitility;
import org.jdbi.v3.core.Jdbi;
import org.jdbi.v3.postgres.PostgresPlugin;
import org.junit.jupiter.api.*;
import org.testcontainers.junit.jupiter.*;
import org.testcontainers.postgresql.PostgreSQLContainer;

import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Pins what {@link SubscriptionErrorPolicy#stop()} does to an <b>exclusive</b> asynchronous subscription shared by two
 * nodes (see {@link EventStoreSubscriptionManager#exclusivelySubscribeToAggregateEventsAsynchronously}):
 * <ul>
 *     <li>the stopped subscription keeps its fenced lock - the lock manager keeps confirming it, and the other node does
 *     not take the subscription over, so it does not flap between nodes,</li>
 *     <li>releasing the lock (here: stopping the node's subscription manager) disposes the stopped subscriber cleanly
 *     and persists the resume point at the failed event, not past it,</li>
 *     <li>the next lock holder resumes at the failed event and handles it and what follows.</li>
 * </ul>
 * Three events are appended (global orders 1, 2 and 3); node A's handler fails on #2, node B's handler never fails.
 */
@Testcontainers
class EventStoreSubscriptionManager_2_node_exclusive_SubscriptionErrorPolicy_stop_IT {
    private static final long          FAILING_EVENT              = 2;
    private static final Duration      LOCK_TIME_OUT              = Duration.ofSeconds(3);
    private static final Duration      LOCK_CONFIRMATION_INTERVAL = Duration.ofSeconds(1);
    private static final AggregateType ORDERS                     = AggregateType.of("StopPolicyExclusiveOrders");
    private static final SubscriberId  SUBSCRIBER_ID              = SubscriberId.of("StopPolicyExclusiveOrdersSubscriber");

    @Container
    private static final PostgreSQLContainer postgreSQLContainer = new PostgreSQLContainer("postgres:18.4").withDatabaseName("event-store")
                                                                                                                .withUsername("test-user")
                                                                                                                .withPassword("secret-password");

    private Node nodeA;
    private Node nodeB;

    @BeforeEach
    void setup() {
        nodeA = createNode("nodeA");
        nodeB = createNode("nodeB");
    }

    @AfterEach
    void cleanup() {
        for (var node : List.of(nodeA, nodeB)) {
            node.unitOfWorkFactory.getCurrentUnitOfWork().ifPresent(UnitOfWork::rollback);
            node.subscriptionManager.stop();
        }
    }

    @Test
    void a_stopped_exclusive_subscription_keeps_its_lock_and_the_next_lock_holder_resumes_at_the_failed_event() {
        // Node A subscribes first and takes the lock; node B stands by
        var subscriberA = subscribe(nodeA, () -> true);
        Awaitility.waitAtMost(Duration.ofSeconds(10)).until(subscriberA.subscription::isActive);
        var subscriberB = subscribe(nodeB, () -> false);
        var lockName    = ((ExclusiveSubscription) subscriberA.subscription).lockName();
        assertThat(subscriberB.subscription.isActive()).isFalse();
        assertThat(subscriberA.resumePointsOnLockAcquired).containsExactly(GlobalEventOrder.FIRST_GLOBAL_EVENT_ORDER.longValue());

        appendThreeEvents(nodeA);

        // Node A handles #1 and stops at #2
        Awaitility.waitAtMost(Duration.ofSeconds(10)).until(subscriberA.subscription::isStoppedByErrorPolicy);
        assertThat(subscriberA.handled).containsExactly(1L);
        assertThat(subscriberA.attempts.get(FAILING_EVENT).get()).isEqualTo(1);
        assertThat(subscriberA.attempts).doesNotContainKey(3L);
        // The resume point held in memory, and the one the manager checkpoints, stay at the failed event
        assertThat(nodeA.subscriptionManager.getCurrentEventOrder(SUBSCRIBER_ID, ORDERS)).hasValue(GlobalEventOrder.of(FAILING_EVENT));
        awaitDurableResumePoint(FAILING_EVENT);

        // A STOP keeps the lock - and keeps confirming it - so node B does not take over, across more than a full lock
        // time-out. A sleep-free bounded check: the condition must hold for the whole window
        var lockConfirmedBeforeWindow = lookupLock(nodeB, lockName).getLockLastConfirmedTimestamp();
        Awaitility.await()
                  .during(LOCK_TIME_OUT.plus(LOCK_CONFIRMATION_INTERVAL.multipliedBy(2)))
                  .atMost(LOCK_TIME_OUT.plus(LOCK_CONFIRMATION_INTERVAL.multipliedBy(4)))
                  .pollInterval(Duration.ofMillis(200))
                  .untilAsserted(() -> {
                      assertThat(subscriberA.subscription.isActive()).isTrue();
                      assertThat(subscriberA.subscription.isStoppedByErrorPolicy()).isTrue();
                      assertThat(nodeA.fencedLockManager.isLockedByThisLockManagerInstance(lockName)).isTrue();
                      assertThat(nodeB.fencedLockManager.isLockedByThisLockManagerInstance(lockName)).isFalse();
                      assertThat(subscriberB.subscription.isActive()).isFalse();
                      assertThat(subscriberB.resumePointsOnLockAcquired).isEmpty();
                  });
        var lockAfterWindow = lookupLock(nodeB, lockName);
        assertThat(lockAfterWindow.getLockedByLockManagerInstanceId()).isEqualTo(nodeA.fencedLockManager.getLockManagerInstanceId());
        assertThat(lockAfterWindow.getLockLastConfirmedTimestamp()).isAfter(lockConfirmedBeforeWindow);
        // Still stopped at #2: no retry, nothing handled past it on either node
        assertThat(subscriberA.handled).containsExactly(1L);
        assertThat(subscriberA.attempts.get(FAILING_EVENT).get()).isEqualTo(1);
        assertThat(subscriberA.attempts).doesNotContainKey(3L);
        assertThat(subscriberB.attempts).isEmpty();
        awaitDurableResumePoint(FAILING_EVENT);

        // Stopping node A's manager releases the lock: the stopped subscriber is disposed and its resume point persisted
        nodeA.subscriptionManager.stop();
        Awaitility.waitAtMost(Duration.ofSeconds(10))
                  .untilAsserted(() -> {
                      assertThat(subscriberA.subscription.isActive()).isFalse();
                      assertThat(subscriberA.subscription.isStoppedByErrorPolicy()).isFalse();
                      assertThat(subscriberA.lockReleasedCount.get()).isEqualTo(1);
                  });

        // Node B takes the lock over, resumes at the failed event - not past it - and handles #2 and #3
        Awaitility.waitAtMost(Duration.ofSeconds(15)).until(subscriberB.subscription::isActive);
        assertThat(subscriberB.resumePointsOnLockAcquired).containsExactly(FAILING_EVENT);
        Awaitility.waitAtMost(Duration.ofSeconds(10))
                  .untilAsserted(() -> assertThat(subscriberB.handled).containsExactly(2L, 3L));
        assertThat(subscriberB.attempts.get(FAILING_EVENT).get()).isEqualTo(1);
        assertThat(subscriberB.subscription.isStoppedByErrorPolicy()).isFalse();
        awaitDurableResumePoint(4);
        // Node A did nothing more after it lost the lock
        assertThat(subscriberA.handled).containsExactly(1L);
        assertThat(subscriberA.attempts.get(FAILING_EVENT).get()).isEqualTo(1);
        assertThat(subscriberA.attempts).doesNotContainKey(3L);
    }

    // ------------------------------------------------------------------------------------------------------- Helpers

    private Node createNode(String nodeName) {
        var jdbi = Jdbi.create(postgreSQLContainer.getJdbcUrl(),
                               postgreSQLContainer.getUsername(),
                               postgreSQLContainer.getPassword());
        jdbi.installPlugin(new PostgresPlugin());

        var unitOfWorkFactory = new EventStoreManagedUnitOfWorkFactory(jdbi);
        var persistenceStrategy = new SeparateTablePerAggregateTypePersistenceStrategy(jdbi,
                                                                                       unitOfWorkFactory,
                                                                                       new TestPersistableEventMapper(),
                                                                                       SeparateTablePerAggregateTypeEventStreamConfigurationFactory.standardSingleTenantConfiguration(EssentialsJSONEventSerializers.create(),
                                                                                                                                                                                      IdentifierColumnType.UUID,
                                                                                                                                                                                      JSONColumnType.JSONB));
        var eventStore = new PostgresqlEventStore<>(unitOfWorkFactory, persistenceStrategy);
        eventStore.addAggregateEventStreamConfiguration(ORDERS, OrderId.class);

        var durableSubscriptionRepository = new PostgresqlDurableSubscriptionRepository(jdbi, eventStore);
        var fencedLockManager = PostgresqlFencedLockManager.builder()
                                                           .setJdbi(jdbi)
                                                           .setUnitOfWorkFactory(unitOfWorkFactory)
                                                           .setLockManagerInstanceId(nodeName)
                                                           .setLockTimeOut(LOCK_TIME_OUT)
                                                           .setLockConfirmationInterval(LOCK_CONFIRMATION_INTERVAL)
                                                           .build();
        var subscriptionManager = EventStoreSubscriptionManager.builder()
                                                               .setEventStore(eventStore)
                                                               .setEventStorePollingBatchSize(10)
                                                               .setEventStorePollingInterval(Duration.ofMillis(50))
                                                               .setFencedLockManager(fencedLockManager)
                                                               .setSnapshotResumePointsEvery(Duration.ofMillis(200))
                                                               .setDurableSubscriptionRepository(durableSubscriptionRepository)
                                                               .setSubscriptionErrorPolicy(SubscriptionErrorPolicy.stop().withoutAutoResume())
                                                               .build();
        subscriptionManager.start();
        return new Node(unitOfWorkFactory, eventStore, durableSubscriptionRepository, fencedLockManager, subscriptionManager);
    }

    /**
     * @param failEvent whether an attempt at #2 fails, asked after the attempt has been counted
     */
    private RecordingSubscriber subscribe(Node node, java.util.function.BooleanSupplier failEvent) {
        var handled                    = new CopyOnWriteArrayList<Long>();
        var attempts                   = new ConcurrentHashMap<Long, AtomicInteger>();
        var resumePointsOnLockAcquired = new CopyOnWriteArrayList<Long>();
        var lockReleasedCount          = new AtomicInteger();
        var subscription = node.subscriptionManager.exclusivelySubscribeToAggregateEventsAsynchronously(
                SUBSCRIBER_ID,
                ORDERS,
                GlobalEventOrder.FIRST_GLOBAL_EVENT_ORDER,
                Optional.empty(),
                new FencedLockAwareSubscriber() {
                    @Override
                    public void onLockAcquired(FencedLock fencedLock, SubscriptionResumePoint resumeFromAndIncluding) {
                        // Captured as a value: the resume point object is advanced by the subscriber afterwards
                        resumePointsOnLockAcquired.add(resumeFromAndIncluding.getResumeFromAndIncluding().longValue());
                    }

                    @Override
                    public void onLockReleased(FencedLock fencedLock) {
                        lockReleasedCount.incrementAndGet();
                    }
                },
                (PersistedEventHandler) event -> {
                    var globalOrder = event.globalEventOrder().longValue();
                    attempts.computeIfAbsent(globalOrder, order -> new AtomicInteger()).incrementAndGet();
                    if (globalOrder == FAILING_EVENT && failEvent.getAsBoolean()) {
                        throw new IllegalStateException("Intentional failure handling event #" + globalOrder);
                    }
                    handled.add(globalOrder);
                });
        return new RecordingSubscriber(subscription, handled, attempts, resumePointsOnLockAcquired, lockReleasedCount);
    }

    private void appendThreeEvents(Node node) {
        node.unitOfWorkFactory.usingUnitOfWork(uow -> {
            for (int i = 0; i < 3; i++) {
                var orderId = OrderId.random();
                node.eventStore.appendToStream(ORDERS, orderId, List.of(new OrderEvent.OrderAccepted(orderId)));
            }
        });
    }

    private static FencedLock lookupLock(Node node, LockName lockName) {
        return node.fencedLockManager.lookupLock(lockName)
                                     .orElseThrow(() -> new AssertionError("No fenced lock found with name " + lockName));
    }

    private void awaitDurableResumePoint(long expectedResumeFromAndIncluding) {
        Awaitility.waitAtMost(Duration.ofSeconds(10))
                  .untilAsserted(() -> assertThat(nodeB.durableSubscriptionRepository.getResumePoint(SUBSCRIBER_ID, ORDERS))
                          .hasValueSatisfying(resumePoint -> assertThat(resumePoint.getResumeFromAndIncluding().longValue()).isEqualTo(expectedResumeFromAndIncluding)));
    }

    private record Node(EventStoreManagedUnitOfWorkFactory unitOfWorkFactory,
                        PostgresqlEventStore<SeparateTablePerAggregateEventStreamConfiguration> eventStore,
                        DurableSubscriptionRepository durableSubscriptionRepository,
                        FencedLockManager fencedLockManager,
                        EventStoreSubscriptionManager subscriptionManager) {
    }

    /**
     * @param handled                    global orders handled successfully, in handling order
     * @param attempts                   handling attempts per global order
     * @param resumePointsOnLockAcquired the resume point handed to {@link FencedLockAwareSubscriber#onLockAcquired} on each acquisition
     * @param lockReleasedCount          how many times {@link FencedLockAwareSubscriber#onLockReleased} was called
     */
    private record RecordingSubscriber(EventStoreSubscription subscription,
                                       CopyOnWriteArrayList<Long> handled,
                                       ConcurrentHashMap<Long, AtomicInteger> attempts,
                                       CopyOnWriteArrayList<Long> resumePointsOnLockAcquired,
                                       AtomicInteger lockReleasedCount) {
    }

    private static class TestPersistableEventMapper implements PersistableEventMapper {
        private final CorrelationId correlationId   = CorrelationId.random();
        private final EventId       causedByEventId = EventId.random();

        @Override
        public PersistableEvent map(Object aggregateId, AggregateEventStreamConfiguration aggregateEventStreamConfiguration, Object event, EventOrder eventOrder) {
            return PersistableEvent.from(EventId.random(),
                                         aggregateEventStreamConfiguration.aggregateType,
                                         aggregateId,
                                         EventTypeOrName.with(event.getClass()),
                                         event,
                                         eventOrder,
                                         EventRevision.of(1),
                                         EventMetaData.of("Key1", "Value1"),
                                         OffsetDateTime.now(),
                                         causedByEventId,
                                         correlationId,
                                         null);
        }
    }
}
