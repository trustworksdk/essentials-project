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
import dk.trustworks.essentials.components.foundation.transaction.UnitOfWork;
import dk.trustworks.essentials.components.foundation.types.SubscriberId;
import org.jdbi.v3.core.Jdbi;
import org.jdbi.v3.postgres.PostgresPlugin;
import org.junit.jupiter.api.*;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.*;

import java.time.Duration;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

/**
 * The opt-in {@code snapshotResumePointsAfterEvents} early save, with the periodic {@code snapshotResumePointsEvery}
 * save pushed out of the test's reach so that only the early save can move the persisted resume point
 */
@Testcontainers
class EventStoreSubscriptionManager_snapshotResumePointsAfterEvents_IT {
    private static final AggregateType ORDERS               = AggregateType.of("Orders");
    private static final SubscriberId  SUBSCRIBER_ID        = SubscriberId.of("OrdersSub1");
    private static final int           NUMBER_OF_EVENTS     = 50;
    private static final int           SAVE_AFTER_EVENTS    = 10;
    private static final Duration      PERIODIC_SAVE_NEVER  = Duration.ofHours(1);

    @Container
    private static final PostgreSQLContainer<?> postgreSQLContainer = new PostgreSQLContainer<>("postgres:18.4").withDatabaseName("event-store")
                                                                                                          .withUsername("test-user")
                                                                                                          .withPassword("secret-password");

    private Jdbi                                                                    jdbi;
    private EventStoreUnitOfWorkFactory<EventStoreUnitOfWork>                       unitOfWorkFactory;
    private PostgresqlEventStore<SeparateTablePerAggregateEventStreamConfiguration> eventStore;
    private DurableSubscriptionRepository                                           durableSubscriptionRepository;
    private EventStoreSubscriptionManager                                           eventStoreSubscriptionManager;

    @BeforeEach
    void setup() {
        jdbi = Jdbi.create(postgreSQLContainer.getJdbcUrl(),
                           postgreSQLContainer.getUsername(),
                           postgreSQLContainer.getPassword());
        jdbi.installPlugin(new PostgresPlugin());
        // The container is shared by the class: start each test without the previous test's resume point and events
        jdbi.useHandle(handle -> handle.execute("DROP TABLE IF EXISTS " + PostgresqlDurableSubscriptionRepository.DEFAULT_DURABLE_SUBSCRIPTIONS_TABLE_NAME + ", orders_events"));

        unitOfWorkFactory = new EventStoreManagedUnitOfWorkFactory(jdbi);
        var persistenceStrategy = new SeparateTablePerAggregateTypePersistenceStrategy(jdbi,
                                                                                       unitOfWorkFactory,
                                                                                       new EventProcessorIT.TestPersistableEventMapper(),
                                                                                       SeparateTablePerAggregateTypeEventStreamConfigurationFactory.defaultConfiguration(
                                                                                               EssentialsJSONEventSerializers.create()));
        eventStore = new PostgresqlEventStore<>(unitOfWorkFactory, persistenceStrategy);
        eventStore.addAggregateEventStreamConfiguration(ORDERS, OrderId.class);
        durableSubscriptionRepository = new PostgresqlDurableSubscriptionRepository(jdbi, eventStore);
    }

    @AfterEach
    void cleanup() {
        unitOfWorkFactory.getCurrentUnitOfWork().ifPresent(UnitOfWork::rollback);
        if (eventStoreSubscriptionManager != null) {
            eventStoreSubscriptionManager.stop();
        }
    }

    @Test
    void test_a_resume_point_that_advanced_past_the_threshold_is_saved_before_the_periodic_save() {
        startSubscriptionManager(SAVE_AFTER_EVENTS);

        var received = subscribeAndHandleAllEvents();

        // After all events the in-memory resume point is NUMBER_OF_EVENTS + 1; the early save leaves it less than
        // SAVE_AFTER_EVENTS positions ahead of the persisted value
        await().atMost(Duration.ofSeconds(5))
               .untilAsserted(() -> assertThat(persistedResumePoint())
                       .isGreaterThan(NUMBER_OF_EVENTS + 1 - SAVE_AFTER_EVENTS));
        assertThat(received.get()).isEqualTo(NUMBER_OF_EVENTS);
    }

    @Test
    void test_without_the_threshold_nothing_is_saved_before_the_periodic_save() throws InterruptedException {
        startSubscriptionManager(0);

        subscribeAndHandleAllEvents();

        // Several early-save check intervals' worth of time: nothing may have been written
        Thread.sleep(500);
        assertThat(persistedResumePoint()).isEqualTo(GlobalEventOrder.FIRST_GLOBAL_EVENT_ORDER.longValue());
    }

    private void startSubscriptionManager(int snapshotResumePointsAfterEvents) {
        eventStoreSubscriptionManager = EventStoreSubscriptionManager.builder()
                                                                     .setEventStore(eventStore)
                                                                     .setEventStorePollingInterval(Duration.ofMillis(50))
                                                                     .setFencedLockManager(PostgresqlFencedLockManager.builder()
                                                                                                                      .setJdbi(jdbi)
                                                                                                                      .setUnitOfWorkFactory(unitOfWorkFactory)
                                                                                                                      .setLockTimeOut(Duration.ofSeconds(3))
                                                                                                                      .setLockConfirmationInterval(Duration.ofSeconds(1))
                                                                                                                      .build())
                                                                     .setSnapshotResumePointsEvery(PERIODIC_SAVE_NEVER)
                                                                     .setSnapshotResumePointsAfterEvents(snapshotResumePointsAfterEvents)
                                                                     .setDurableSubscriptionRepository(durableSubscriptionRepository)
                                                                     .build();
        eventStoreSubscriptionManager.start();
    }

    private AtomicInteger subscribeAndHandleAllEvents() {
        var received = new AtomicInteger();
        eventStoreSubscriptionManager.subscribeToAggregateEventsAsynchronously(SUBSCRIBER_ID,
                                                                               ORDERS,
                                                                               GlobalEventOrder.FIRST_GLOBAL_EVENT_ORDER,
                                                                               Optional.empty(),
                                                                               new PersistedEventHandler() {
                                                                                   @Override
                                                                                   public void onResetFrom(EventStoreSubscription eventStoreSubscription, GlobalEventOrder globalEventOrder) {
                                                                                   }

                                                                                   @Override
                                                                                   public void handle(PersistedEvent event) {
                                                                                       received.incrementAndGet();
                                                                                   }
                                                                               });

        var orderId = OrderId.random();
        unitOfWorkFactory.usingUnitOfWork(uow -> eventStore.appendToStream(ORDERS,
                                                                          orderId,
                                                                          IntStream.range(0, NUMBER_OF_EVENTS)
                                                                                   .mapToObj(i -> new OrderEvent.OrderAdded(orderId, CustomerId.random(), i))
                                                                                   .toList()));

        await().atMost(Duration.ofSeconds(10)).untilAsserted(() -> assertThat(received.get()).isEqualTo(NUMBER_OF_EVENTS));
        return received;
    }

    /**
     * Read straight from the table: the repository's in-memory {@link SubscriptionResumePoint} is the one the
     * subscription advances, so it says nothing about what was written
     */
    private long persistedResumePoint() {
        return jdbi.withHandle(handle -> handle.createQuery("SELECT resume_from_and_including_global_eventorder FROM " + PostgresqlDurableSubscriptionRepository.DEFAULT_DURABLE_SUBSCRIPTIONS_TABLE_NAME +
                                                                    " WHERE subscriber_id = :subscriber_id AND aggregate_type = :aggregate_type")
                                               .bind("subscriber_id", SUBSCRIBER_ID.toString())
                                               .bind("aggregate_type", ORDERS.toString())
                                               .mapTo(Long.class)
                                               .one());
    }
}
