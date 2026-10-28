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
import dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.eventstream.AggregateType;
import dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.observability.EventStoreSubscriptionObserver.NoOpEventStoreSubscriptionObserver;
import dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.persistence.table_per_aggregate_type.*;
import dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.processor.EventProcessorIT;
import dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.serializer.json.EssentialsJSONEventSerializers;
import dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.test_data.*;
import dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.transaction.*;
import dk.trustworks.essentials.components.foundation.fencedlock.*;
import dk.trustworks.essentials.components.foundation.test.EssentialsTestContainers;
import dk.trustworks.essentials.components.foundation.types.SubscriberId;
import org.awaitility.Awaitility;
import org.jdbi.v3.core.Jdbi;
import org.jdbi.v3.postgres.PostgresPlugin;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.testcontainers.junit.jupiter.*;
import org.testcontainers.postgresql.PostgreSQLContainer;

import java.time.Duration;
import java.util.Optional;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * An in-transaction subscription registers its handler on the event store's local event bus. Its stop - and, for the
 * exclusive one, the release of its fenced lock - removed it with a new method reference, which the bus does not find,
 * so the handler stayed registered: a stopped or unsubscribed subscription kept handling events, an exclusive one kept
 * handling them after another instance took its lock, and every re-acquisition of the lock registered it once more.
 * The exclusive subscription's stop also never released its lock.
 */
@Testcontainers
class InTransactionSubscriptionStopIT {
    private static final AggregateType ORDERS        = AggregateType.of("Orders");
    private static final SubscriberId  SUBSCRIBER_ID = SubscriberId.of("OrdersInTransaction");
    private static final Duration      WAIT          = Duration.ofSeconds(15);

    @Container
    private static final PostgreSQLContainer postgreSQLContainer = EssentialsTestContainers.postgres("in-transaction-subscription-stop-db");

    private final AtomicInteger             eventsHandled = new AtomicInteger();
    private final BlockingQueue<FencedLock> locksAcquired = new LinkedBlockingQueue<>();

    private EventStoreUnitOfWorkFactory<EventStoreUnitOfWork>                       unitOfWorkFactory;
    private PostgresqlEventStore<SeparateTablePerAggregateEventStreamConfiguration> eventStore;
    private FencedLockManager                                                       fencedLockManager;
    private EventStoreSubscriptionManager                                           subscriptionManager;

    @BeforeEach
    void setup() {
        var jdbi = Jdbi.create(postgreSQLContainer.getJdbcUrl(), postgreSQLContainer.getUsername(), postgreSQLContainer.getPassword());
        jdbi.installPlugin(new PostgresPlugin());
        // The container is shared by the class, so start every test from an empty database
        jdbi.useHandle(handle -> handle.execute("DROP SCHEMA public CASCADE; CREATE SCHEMA public"));

        unitOfWorkFactory = new EventStoreManagedUnitOfWorkFactory(jdbi);
        var persistenceStrategy = new SeparateTablePerAggregateTypePersistenceStrategy(jdbi,
                                                                                       unitOfWorkFactory,
                                                                                       new EventProcessorIT.TestPersistableEventMapper(),
                                                                                       SeparateTablePerAggregateTypeEventStreamConfigurationFactory.defaultConfiguration(
                                                                                               EssentialsJSONEventSerializers.create()));
        eventStore = new PostgresqlEventStore<>(unitOfWorkFactory, persistenceStrategy, new NoOpEventStoreSubscriptionObserver() {
            @Override
            public void lockAcquired(FencedLock lock, EventStoreSubscription eventStoreSubscription) {
                locksAcquired.add(lock);
            }
        });
        eventStore.addAggregateEventStreamConfiguration(ORDERS, OrderId.class);

        fencedLockManager = PostgresqlFencedLockManager.builder()
                                                       .setJdbi(jdbi)
                                                       .setUnitOfWorkFactory(unitOfWorkFactory)
                                                       .setLockManagerInstanceId("InTransaction")
                                                       .setLockTimeOut(Duration.ofSeconds(3))
                                                       .setLockConfirmationInterval(Duration.ofMillis(500))
                                                       .build();
        subscriptionManager = EventStoreSubscriptionManager.builder()
                                                           .setEventStore(eventStore)
                                                           .setEventStorePollingBatchSize(10)
                                                           .setEventStorePollingInterval(Duration.ofMillis(100))
                                                           .setFencedLockManager(fencedLockManager)
                                                           .setSnapshotResumePointsEvery(Duration.ofSeconds(1))
                                                           .setDurableSubscriptionRepository(new PostgresqlDurableSubscriptionRepository(jdbi, eventStore))
                                                           .build();
        subscriptionManager.start();
    }

    @AfterEach
    void tearDown() {
        if (subscriptionManager != null) {
            subscriptionManager.stop();
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void an_unsubscribed_in_transaction_subscription_no_longer_handles_events(boolean exclusive) {
        var subscription = subscribe(exclusive);
        appendAnOrder();
        assertThat(eventsHandled).hasValue(1);

        subscription.unsubscribe();
        appendAnOrder();

        assertThat(eventsHandled).hasValue(1);
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void a_stopped_in_transaction_subscription_no_longer_handles_events(boolean exclusive) {
        var subscription = subscribe(exclusive);
        appendAnOrder();
        assertThat(eventsHandled).hasValue(1);

        subscription.stop();
        appendAnOrder();

        assertThat(eventsHandled).hasValue(1);
        assertThat(subscription.isStarted()).isFalse();
    }

    @Test
    void a_stopped_exclusive_in_transaction_subscription_releases_its_lock() {
        var subscription = (ExclusiveSubscription) subscribe(true);
        assertThat(fencedLockManager.isLockedByThisLockManagerInstance(subscription.lockName())).isTrue();

        ((EventStoreSubscription) subscription).stop();

        assertThat(fencedLockManager.isLockedByThisLockManagerInstance(subscription.lockName())).isFalse();
        assertThat(((EventStoreSubscription) subscription).isActive()).isFalse();
    }

    @Test
    void an_exclusive_in_transaction_subscription_whose_lock_was_released_and_acquired_again_handles_each_event_once() throws InterruptedException {
        var subscription = subscribe(true);
        var firstLock    = locksAcquired.poll(WAIT.toSeconds(), TimeUnit.SECONDS);
        assertThat(firstLock).isNotNull();

        firstLock.release();
        // The lock manager keeps trying to acquire it, and acquires it again on its next attempt
        var secondLock = locksAcquired.poll(WAIT.toSeconds(), TimeUnit.SECONDS);
        assertThat(secondLock).isNotNull();
        assertThat(subscription.isActive()).isTrue();

        appendAnOrder();

        assertThat(eventsHandled).hasValue(1);
    }

    private EventStoreSubscription subscribe(boolean exclusive) {
        TransactionalPersistedEventHandler handler = (event, unitOfWork) -> eventsHandled.incrementAndGet();
        var subscription = exclusive ?
                           subscriptionManager.exclusivelySubscribeToAggregateEventsInTransaction(SUBSCRIBER_ID, ORDERS, Optional.empty(), handler) :
                           subscriptionManager.subscribeToAggregateEventsInTransaction(SUBSCRIBER_ID, ORDERS, handler);
        Awaitility.await()
                  .atMost(WAIT)
                  .until(subscription::isActive);
        return subscription;
    }

    private void appendAnOrder() {
        var orderId = OrderId.random();
        unitOfWorkFactory.usingUnitOfWork(uow -> eventStore.appendToStream(ORDERS, orderId, new OrderEvent.OrderAdded(orderId, CustomerId.random(), 1)));
    }
}
