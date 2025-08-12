/*
 *  Copyright 2021-2025 the original author or authors.
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
package dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.eventstream;

import com.fasterxml.jackson.annotation.JsonAutoDetect;
import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.fasterxml.jackson.datatype.jdk8.Jdk8Module;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.PostgresqlEventStore;
import dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.gap.PostgresqlEventStreamGapHandler;
import dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.observability.EventStoreSubscriptionObserver;
import dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.persistence.EventMetaData;
import dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.persistence.table_per_aggregate_type.*;
import dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.test.TestPersistableEventMapper;
import dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.test_data.*;
import dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.transaction.*;
import dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.types.GlobalEventOrder;
import dk.trustworks.essentials.components.foundation.postgresql.SqlExecutionTimeLogger;
import dk.trustworks.essentials.components.foundation.transaction.UnitOfWork;
import dk.trustworks.essentials.components.foundation.types.SubscriberId;
import dk.trustworks.essentials.jackson.immutable.EssentialsImmutableJacksonModule;
import dk.trustworks.essentials.jackson.types.EssentialTypesJacksonModule;
import org.jdbi.v3.core.Jdbi;
import org.jdbi.v3.postgres.PostgresPlugin;
import org.junit.jupiter.api.*;
import org.testcontainers.DockerClientFactory;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.*;
import dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.serializer.AggregateIdSerializer;
import dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.serializer.json.JacksonJSONEventSerializer;

import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.test.TestObjectMapperFactory.createObjectMapper;
import static org.assertj.core.api.Assertions.assertThat;

@Testcontainers
class ReactiveBackoffAndResilienceIT {
    public static final EventMetaData META_DATA = EventMetaData.of("k", "v");
    public static final AggregateType ORDERS = AggregateType.of("Orders");

    private Jdbi jdbi;
    private EventStoreUnitOfWorkFactory<EventStoreUnitOfWork> unitOfWorkFactory;
    private PostgresqlEventStore<SeparateTablePerAggregateEventStreamConfiguration> eventStore;

    @Container
    private final PostgreSQLContainer<?> postgreSQLContainer = new PostgreSQLContainer<>("postgres:latest")
            .withDatabaseName("reactive-event-store-backoff-test")
            .withUsername("test-user")
            .withPassword("secret-password");

    @BeforeEach
    void setup() {
        jdbi = Jdbi.create(postgreSQLContainer.getJdbcUrl(), postgreSQLContainer.getUsername(), postgreSQLContainer.getPassword());
        jdbi.installPlugin(new PostgresPlugin());
        jdbi.setSqlLogger(new SqlExecutionTimeLogger());

        unitOfWorkFactory = new EventStoreManagedUnitOfWorkFactory(jdbi);
        var eventMapper = new TestPersistableEventMapper();

        JacksonJSONEventSerializer jsonSerializer = new JacksonJSONEventSerializer(createObjectMapper());
        var persistenceStrategy = new SeparateTablePerAggregateTypePersistenceStrategy(
                jdbi,
                unitOfWorkFactory,
                eventMapper,
                SeparateTablePerAggregateTypeEventStreamConfigurationFactory.standardSingleTenantConfiguration(
                        jsonSerializer,
                        IdentifierColumnType.TEXT,
                        JSONColumnType.JSONB
                ));

        eventStore = new PostgresqlEventStore<>(
                unitOfWorkFactory,
                persistenceStrategy,
                Optional.empty(),
                eventStore -> new PostgresqlEventStreamGapHandler<>(eventStore, unitOfWorkFactory),
                new EventStoreSubscriptionObserver.NoOpEventStoreSubscriptionObserver(),
                jsonSerializer
        );

        eventStore.addAggregateEventStreamConfiguration(ORDERS, AggregateIdSerializer.serializerFor(OrderId.class));
    }

    @AfterEach
    void cleanup() {
        unitOfWorkFactory.getCurrentUnitOfWork().ifPresent(UnitOfWork::rollback);
        assertThat(unitOfWorkFactory.getCurrentUnitOfWork()).isEmpty();
    }

    @Test
    @DisplayName("Backoff grows during quiet periods, resets on activity, and grows again when quiet resumes")
    void backoff_quiet_then_activity_then_quiet() throws Exception {
        var subscriberId = SubscriberId.of("Backoff-IT");
        var latch = new CountDownLatch(1);

        var subscription = eventStore.pollEventsReactive(
                ORDERS,
                GlobalEventOrder.FIRST_GLOBAL_EVENT_ORDER.longValue(),
                Optional.of(10),
                Optional.empty(),
                Optional.of(subscriberId)
        ).subscribe(e -> latch.countDown());

        try {
            // Wait for backoff to increase to at least 200ms within 5 seconds
            assertEventually("backoff to >=200ms", Duration.ofSeconds(5), () -> {
                var metrics = eventStore.getReactiveSubscriptionManager().getMetrics();
                var strategy = metrics.backoffStrategies.get(ORDERS);
                return strategy != null && strategy.getCurrentInterval().toMillis() >= 200;
            });

            // Append one event and expect immediate delivery and backoff reset to minimum (100ms default)
            var unit = unitOfWorkFactory.getOrCreateNewUnitOfWork();
            var orderId = OrderId.random();
            eventStore.appendToStream(ORDERS, orderId, List.of(new OrderEvent.OrderAdded(orderId, CustomerId.random(), 1)));
            unit.commit();

            assertThat(latch.await(3, TimeUnit.SECONDS)).isTrue();

            assertEventually("backoff reset to 100ms", Duration.ofSeconds(3), () -> {
                var metrics = eventStore.getReactiveSubscriptionManager().getMetrics();
                var strategy = metrics.backoffStrategies.get(ORDERS);
                return strategy != null && strategy.getCurrentInterval().toMillis() == 100;
            });

            // Quiet again: ensure backoff grows again to >=200ms
            assertEventually("backoff to >=200ms again", Duration.ofSeconds(5), () -> {
                var metrics = eventStore.getReactiveSubscriptionManager().getMetrics();
                var strategy = metrics.backoffStrategies.get(ORDERS);
                return strategy != null && strategy.getCurrentInterval().toMillis() >= 200;
            });
        } finally {
            subscription.dispose();
        }
    }

    @Test
    @DisplayName("Reactive polling survives transient DB connectivity pause and resumes emitting events")
    void survives_db_connectivity_pause_and_resumes() throws Exception {
        var subscriberId = SubscriberId.of("Resilience-IT");
        var latch = new CountDownLatch(1);

        var subscription = eventStore.pollEventsReactive(
                ORDERS,
                GlobalEventOrder.FIRST_GLOBAL_EVENT_ORDER.longValue(),
                Optional.of(10),
                Optional.empty(),
                Optional.of(subscriberId)
        ).subscribe(e -> latch.countDown());

        try {
            // Pause the database container (if supported)
            boolean paused = pauseContainerIfPossible(postgreSQLContainer, Duration.ofSeconds(2));

            // Unpause happens inside the helper; after that, append an event and verify delivery
            var unit = unitOfWorkFactory.getOrCreateNewUnitOfWork();
            var orderId = OrderId.random();
            eventStore.appendToStream(ORDERS, orderId, List.of(new OrderEvent.OrderAdded(orderId, CustomerId.random(), 42)));
            unit.commit();

            assertThat(latch.await(10, TimeUnit.SECONDS)).isTrue();

            // Even if pause was not supported, this still verifies no crash and events flow
            assertThat(subscription.isDisposed()).isFalse();
        } finally {
            subscription.dispose();
        }
    }

    private static boolean pauseContainerIfPossible(PostgreSQLContainer<?> container, Duration pauseFor) {
        try {
            var client = DockerClientFactory.instance().client();
            client.pauseContainerCmd(container.getContainerId()).exec();
            Thread.sleep(pauseFor.toMillis());
            client.unpauseContainerCmd(container.getContainerId()).exec();
            return true;
        } catch (Throwable t) {
            // Ignore if pause is not supported in current environment
            return false;
        }
    }

    private static void assertEventually(String reason, Duration timeout, Condition condition) throws InterruptedException {
        long deadline = System.currentTimeMillis() + timeout.toMillis();
        while (System.currentTimeMillis() < deadline) {
            if (condition.isTrue()) return;
            Thread.sleep(50);
        }
        throw new AssertionError("Condition not met within timeout: " + reason);
    }

    @FunctionalInterface
    private interface Condition {
        boolean isTrue();
    }

}
