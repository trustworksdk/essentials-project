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
import dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.persistence.table_per_aggregate_type.*;
import dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.processor.EventProcessorIT;
import dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.serializer.json.EssentialsJSONEventSerializers;
import dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.test_data.*;
import dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.transaction.*;
import dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.types.GlobalEventOrder;
import dk.trustworks.essentials.components.foundation.fencedlock.FencedLock;
import dk.trustworks.essentials.components.foundation.types.SubscriberId;
import org.awaitility.Awaitility;
import org.jdbi.v3.core.Jdbi;
import org.jdbi.v3.postgres.PostgresPlugin;
import org.junit.jupiter.api.*;
import org.testcontainers.junit.jupiter.*;
import org.testcontainers.postgresql.PostgreSQLContainer;

import java.time.Duration;
import java.util.*;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;
import java.util.stream.*;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link EventStoreSubscription#resetFrom(GlobalEventOrder, Consumer)} on the lock holder of an exclusive subscription while
 * another instance waits for the lock. Resetting releases the lock: the reset must be stored before that, so the instance
 * that takes the lock over reads it and resumes from the reset point. Saved after the release, the new holder could read the
 * row first and resume from where the previous holder stopped - and its first save, with its newer lock token, would then
 * overwrite the reset.
 */
@Testcontainers
class EventStoreSubscriptionManager_2_node_exclusive_resetFrom_IT {
    private static final AggregateType ORDERS        = AggregateType.of("Orders");
    private static final SubscriberId  SUBSCRIBER_ID = SubscriberId.of("OrdersResetSub");
    private static final int           EVENTS        = 10;

    @Container
    private static final PostgreSQLContainer postgreSQLContainer = new PostgreSQLContainer("postgres:18.4").withDatabaseName("event-store")
                                                                                                                 .withUsername("test-user")
                                                                                                                 .withPassword("secret-password");

    private final List<EventStoreSubscriptionManager> managers = new ArrayList<>();
    private       Jdbi                                jdbi;

    @BeforeEach
    void setup() {
        jdbi = Jdbi.create(postgreSQLContainer.getJdbcUrl(), postgreSQLContainer.getUsername(), postgreSQLContainer.getPassword());
        jdbi.installPlugin(new PostgresPlugin());
        // The container is shared by the class: every test starts from an empty database
        jdbi.useHandle(handle -> handle.execute("DROP SCHEMA public CASCADE; CREATE SCHEMA public"));
    }

    @AfterEach
    void cleanup() {
        managers.forEach(EventStoreSubscriptionManager::stop);
        managers.clear();
    }

    @Test
    void a_reset_on_the_lock_holder_is_where_the_next_lock_holder_resumes() {
        a_reset_on_the_lock_holder_is_where_the_next_lock_holder_resumes(false);
    }

    @Test
    void a_batched_reset_on_the_lock_holder_is_where_the_next_lock_holder_resumes() {
        a_reset_on_the_lock_holder_is_where_the_next_lock_holder_resumes(true);
    }

    private void a_reset_on_the_lock_holder_is_where_the_next_lock_holder_resumes(boolean batched) {
        var nodeA = createEventStoreSubscriptionManager("nodeA");
        appendEvents(nodeA.getEventStore());

        var receivedByA = new CopyOnWriteArrayList<Long>();
        var receivedByB = new CopyOnWriteArrayList<Long>();
        var subscriptionA = subscribe(nodeA, batched, receivedByA);
        Awaitility.waitAtMost(Duration.ofSeconds(30)).untilAsserted(() -> assertThat(receivedByA).hasSize(EVENTS));
        // Every event handled is saved before the reset
        Awaitility.waitAtMost(Duration.ofSeconds(30)).untilAsserted(() -> assertThat(storedResumePoint()).isEqualTo(EVENTS + 1));

        var nodeB         = createEventStoreSubscriptionManager("nodeB");
        var subscriptionB = subscribe(nodeB, batched, receivedByB);
        assertThat(subscriptionB.isActive()).as("nodeB waits for the lock").isFalse();

        subscriptionA.resetFrom(GlobalEventOrder.FIRST_GLOBAL_EVENT_ORDER,
                                // Called once nodeA no longer holds the lock: wait for nodeB to take it over, so nodeA's
                                // restart cannot take it back
                                resetFrom -> Awaitility.waitAtMost(Duration.ofSeconds(30)).until(subscriptionB::isActive));

        assertThat(subscriptionA.isActive()).isFalse();
        Awaitility.waitAtMost(Duration.ofSeconds(30))
                  .untilAsserted(() -> assertThat(receivedByB).as("nodeB resumes from the reset point")
                                                              .containsExactlyElementsOf(LongStream.rangeClosed(1, EVENTS).boxed().toList()));
        Awaitility.waitAtMost(Duration.ofSeconds(30)).untilAsserted(() -> assertThat(storedResumePoint()).isEqualTo(EVENTS + 1));
    }

    private EventStoreSubscription subscribe(EventStoreSubscriptionManager manager, boolean batched, List<Long> received) {
        FencedLockAwareSubscriber lockAware = new FencedLockAwareSubscriber() {
            @Override
            public void onLockAcquired(FencedLock fencedLock, SubscriptionResumePoint resumeFromAndIncluding) {
            }

            @Override
            public void onLockReleased(FencedLock fencedLock) {
            }
        };
        if (batched) {
            return manager.exclusivelyBatchSubscribeToAggregateEventsAsynchronously(SUBSCRIBER_ID,
                                                                                    ORDERS,
                                                                                    aggregateType -> GlobalEventOrder.FIRST_GLOBAL_EVENT_ORDER,
                                                                                    Optional.empty(),
                                                                                    3,
                                                                                    Duration.ofMillis(50),
                                                                                    lockAware,
                                                                                    events -> {
                                                                                        events.forEach(event -> received.add(event.globalEventOrder().longValue()));
                                                                                        return events.size();
                                                                                    });
        }
        return manager.exclusivelySubscribeToAggregateEventsAsynchronously(SUBSCRIBER_ID,
                                                                           ORDERS,
                                                                           GlobalEventOrder.FIRST_GLOBAL_EVENT_ORDER,
                                                                           Optional.empty(),
                                                                           lockAware,
                                                                           new PersistedEventHandler() {
                                                                               @Override
                                                                               public void onResetFrom(EventStoreSubscription eventStoreSubscription, GlobalEventOrder globalEventOrder) {
                                                                                   received.clear();
                                                                               }

                                                                               @Override
                                                                               public void handle(PersistedEvent event) {
                                                                                   received.add(event.globalEventOrder().longValue());
                                                                               }
                                                                           });
    }

    private void appendEvents(EventStore eventStore) {
        eventStore.getUnitOfWorkFactory().usingUnitOfWork(() -> IntStream.rangeClosed(1, EVENTS).forEach(i -> {
            var orderId = OrderId.random();
            eventStore.appendToStream(ORDERS, orderId, List.of(new OrderEvent.OrderAdded(orderId, CustomerId.random(), i)));
        }));
    }

    private long storedResumePoint() {
        return jdbi.withHandle(handle -> handle.createQuery("SELECT resume_from_and_including_global_eventorder FROM durable_subscriptions WHERE subscriber_id = :subscriber_id")
                                               .bind("subscriber_id", SUBSCRIBER_ID.toString())
                                               .mapTo(Long.class)
                                               .one());
    }

    private EventStoreSubscriptionManager createEventStoreSubscriptionManager(String nodeName) {
        var nodeJdbi = Jdbi.create(postgreSQLContainer.getJdbcUrl(), postgreSQLContainer.getUsername(), postgreSQLContainer.getPassword());
        nodeJdbi.installPlugin(new PostgresPlugin());
        var unitOfWorkFactory = new EventStoreManagedUnitOfWorkFactory(nodeJdbi);
        var persistenceStrategy = new SeparateTablePerAggregateTypePersistenceStrategy(nodeJdbi,
                                                                                       unitOfWorkFactory,
                                                                                       new EventProcessorIT.TestPersistableEventMapper(),
                                                                                       SeparateTablePerAggregateTypeEventStreamConfigurationFactory.defaultConfiguration(
                                                                                               EssentialsJSONEventSerializers.create()));
        var eventStore = new PostgresqlEventStore<>(unitOfWorkFactory, persistenceStrategy);
        eventStore.addAggregateEventStreamConfiguration(ORDERS, OrderId.class);
        var manager = EventStoreSubscriptionManager.createFor(eventStore,
                                                              20,
                                                              Duration.ofMillis(100),
                                                              PostgresqlFencedLockManager.builder()
                                                                                         .setJdbi(nodeJdbi)
                                                                                         .setUnitOfWorkFactory(unitOfWorkFactory)
                                                                                         .setLockManagerInstanceId(nodeName)
                                                                                         .setLockTimeOut(Duration.ofSeconds(3))
                                                                                         // nodeB tries for the lock often, so it takes it over before nodeA's release has finished
                                                                                         .setLockConfirmationInterval(Duration.ofMillis(100))
                                                                                         .build(),
                                                              Duration.ofSeconds(1),
                                                              new PostgresqlDurableSubscriptionRepository(nodeJdbi, eventStore));
        manager.start();
        managers.add(manager);
        return manager;
    }
}
