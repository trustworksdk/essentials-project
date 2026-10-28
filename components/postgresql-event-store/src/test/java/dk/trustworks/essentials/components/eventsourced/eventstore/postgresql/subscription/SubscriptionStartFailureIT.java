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
import dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.persistence.table_per_aggregate_type.*;
import dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.processor.EventProcessorIT;
import dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.serializer.json.EssentialsJSONEventSerializers;
import dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.test_data.*;
import dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.transaction.*;
import dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.types.GlobalEventOrder;
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

import java.lang.reflect.*;
import java.time.Duration;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.*;

/**
 * A non-exclusive asynchronous subscription marked itself started before it looked its resume point up. When the
 * lookup failed - a database error, or an interrupt from an event processor stopping while its lock callback was still
 * starting the subscription - it stayed marked started with nothing polling: every later start was skipped as "already
 * started", the subscription never delivered another event, and its stop threw "No resumePoint provided", which failed
 * the subscription manager's stop as well
 */
@Testcontainers
class SubscriptionStartFailureIT {
    private static final AggregateType ORDERS        = AggregateType.of("Orders");
    private static final SubscriberId  SUBSCRIBER_ID = SubscriberId.of("OrdersStartFailure");
    private static final Duration      WAIT          = Duration.ofSeconds(15);

    @Container
    private static final PostgreSQLContainer postgreSQLContainer = EssentialsTestContainers.postgres("subscription-start-failure-db");

    private final AtomicInteger lookupsToFail = new AtomicInteger(1);
    private final AtomicInteger eventsHandled = new AtomicInteger();

    private EventStoreUnitOfWorkFactory<EventStoreUnitOfWork>                       unitOfWorkFactory;
    private PostgresqlEventStore<SeparateTablePerAggregateEventStreamConfiguration> eventStore;
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
        eventStore = new PostgresqlEventStore<>(unitOfWorkFactory, persistenceStrategy);
        eventStore.addAggregateEventStreamConfiguration(ORDERS, OrderId.class);

        subscriptionManager = EventStoreSubscriptionManager.builder()
                                                           .setEventStore(eventStore)
                                                           .setEventStorePollingBatchSize(10)
                                                           .setEventStorePollingInterval(Duration.ofMillis(100))
                                                           .setFencedLockManager(PostgresqlFencedLockManager.builder()
                                                                                                            .setJdbi(jdbi)
                                                                                                            .setUnitOfWorkFactory(unitOfWorkFactory)
                                                                                                            .setLockManagerInstanceId("StartFailure")
                                                                                                            .setLockTimeOut(Duration.ofSeconds(3))
                                                                                                            .setLockConfirmationInterval(Duration.ofSeconds(1))
                                                                                                            .build())
                                                           .setSnapshotResumePointsEvery(Duration.ofSeconds(1))
                                                           .setDurableSubscriptionRepository(failingTheFirstLookup(new PostgresqlDurableSubscriptionRepository(jdbi, eventStore)))
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
    void a_subscription_whose_resume_point_lookup_failed_is_not_started_and_starts_on_the_next_attempt(boolean batched) {
        assertThatThrownBy(() -> subscribe(batched)).hasMessage("Simulated resume point lookup failure");
        var subscription = subscriptionManager.getSubscription(SUBSCRIBER_ID, ORDERS).orElseThrow();
        assertThat(subscription.isStarted()).isFalse();

        subscription.start();
        appendAnOrder();

        Awaitility.await()
                  .atMost(WAIT)
                  .untilAsserted(() -> assertThat(eventsHandled).hasValue(1));
        assertThatCode(subscription::stop).doesNotThrowAnyException();
    }

    private EventStoreSubscription subscribe(boolean batched) {
        if (batched) {
            return subscriptionManager.batchSubscribeToAggregateEventsAsynchronously(SUBSCRIBER_ID,
                                                                                     ORDERS,
                                                                                     GlobalEventOrder.FIRST_GLOBAL_EVENT_ORDER,
                                                                                     Optional.empty(),
                                                                                     10,
                                                                                     Duration.ofMillis(100),
                                                                                     events -> {
                                                                                         eventsHandled.addAndGet(events.size());
                                                                                         return events.size();
                                                                                     });
        }
        return subscriptionManager.subscribeToAggregateEventsAsynchronously(SUBSCRIBER_ID,
                                                                            ORDERS,
                                                                            GlobalEventOrder.FIRST_GLOBAL_EVENT_ORDER,
                                                                            event -> eventsHandled.incrementAndGet());
    }

    private DurableSubscriptionRepository failingTheFirstLookup(DurableSubscriptionRepository repository) {
        return (DurableSubscriptionRepository) Proxy.newProxyInstance(getClass().getClassLoader(),
                                                                      new Class<?>[]{DurableSubscriptionRepository.class},
                                                                      (proxy, method, args) -> {
                                                                          if (method.getName().equals("getOrCreateResumePoint") && lookupsToFail.getAndDecrement() > 0) {
                                                                              throw new IllegalStateException("Simulated resume point lookup failure");
                                                                          }
                                                                          try {
                                                                              return method.invoke(repository, args);
                                                                          } catch (InvocationTargetException e) {
                                                                              throw e.getCause();
                                                                          }
                                                                      });
    }

    private void appendAnOrder() {
        var orderId = OrderId.random();
        unitOfWorkFactory.usingUnitOfWork(uow -> eventStore.appendToStream(ORDERS, orderId, new OrderEvent.OrderAdded(orderId, CustomerId.random(), 1)));
    }
}
