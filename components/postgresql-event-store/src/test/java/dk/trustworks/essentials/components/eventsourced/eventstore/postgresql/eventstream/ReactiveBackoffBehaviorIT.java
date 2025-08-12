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
import dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.serializer.AggregateIdSerializer;
import dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.serializer.json.JacksonJSONEventSerializer;
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
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.*;

import java.util.*;
import java.util.concurrent.*;

import static org.assertj.core.api.Assertions.assertThat;

@Testcontainers
class ReactiveBackoffBehaviorIT {
    public static final EventMetaData META_DATA = EventMetaData.of("Key1", "Value1", "Key2", "Value2");
    public static final AggregateType ORDERS    = AggregateType.of("Orders");

    private Jdbi                                                                    jdbi;
    private EventStoreUnitOfWorkFactory<EventStoreUnitOfWork>                       unitOfWorkFactory;
    private TestPersistableEventMapper                                              eventMapper;
    private PostgresqlEventStore<SeparateTablePerAggregateEventStreamConfiguration> eventStore;

    @Container
    private final PostgreSQLContainer<?> postgreSQLContainer = new PostgreSQLContainer<>("postgres:latest")
            .withDatabaseName("reactive-event-store-backoff-it")
            .withUsername("test-user")
            .withPassword("secret-password");

    @BeforeEach
    void setup() {
        jdbi = Jdbi.create(postgreSQLContainer.getJdbcUrl(),
                           postgreSQLContainer.getUsername(),
                           postgreSQLContainer.getPassword());
        jdbi.installPlugin(new PostgresPlugin());
        jdbi.setSqlLogger(new SqlExecutionTimeLogger());

        unitOfWorkFactory = new EventStoreManagedUnitOfWorkFactory(jdbi);
        eventMapper = new TestPersistableEventMapper();

        JacksonJSONEventSerializer jsonSerializer = new JacksonJSONEventSerializer(createObjectMapper());
        var persistenceStrategy = new SeparateTablePerAggregateTypePersistenceStrategy(jdbi,
                                                                                       unitOfWorkFactory,
                                                                                       eventMapper,
                                                                                       SeparateTablePerAggregateTypeEventStreamConfigurationFactory.standardSingleTenantConfiguration(jsonSerializer,
                                                                                                                                                                                  IdentifierColumnType.TEXT,
                                                                                                                                                                                  JSONColumnType.JSONB));

        eventStore = new PostgresqlEventStore<>(unitOfWorkFactory,
                                                persistenceStrategy,
                                                Optional.empty(),
                                                eventStore -> new PostgresqlEventStreamGapHandler<>(eventStore, unitOfWorkFactory),
                                                new EventStoreSubscriptionObserver.NoOpEventStoreSubscriptionObserver(),
                                                jsonSerializer);

        eventStore.addAggregateEventStreamConfiguration(ORDERS, AggregateIdSerializer.serializerFor(OrderId.class));
    }

    @AfterEach
    void cleanup() {
        unitOfWorkFactory.getCurrentUnitOfWork().ifPresent(UnitOfWork::rollback);
        assertThat(unitOfWorkFactory.getCurrentUnitOfWork()).isEmpty();
    }

    @Test
    @DisplayName("Backoff increases during quiet periods, resets on activity, and increases again after quiet")
    void backoff_quiet_then_activity_then_quiet() throws Exception {
        var subscriberId = SubscriberId.of("BackoffBehavior");
        var latch = new CountDownLatch(1);

        var subscription = eventStore.pollEventsReactive(
                ORDERS,
                GlobalEventOrder.FIRST_GLOBAL_EVENT_ORDER.longValue(),
                Optional.of(10),
                Optional.empty(),
                Optional.of(subscriberId)
        ).subscribe(e -> latch.countDown());

        try {
            // Wait up to 8 seconds for interval to backoff to at least 200ms
            long deadline = System.currentTimeMillis() + 8000;
            boolean backedOff = false;
            while (System.currentTimeMillis() < deadline) {
                var metrics = eventStore.getReactiveSubscriptionManager().getMetrics();
                var strategy = metrics.backoffStrategies.get(ORDERS);
                if (strategy != null && strategy.getCurrentInterval().toMillis() >= 200) {
                    backedOff = true;
                    break;
                }
                Thread.sleep(50);
            }
            assertThat(backedOff).isTrue();

            // Append an event and ensure we receive it and backoff resets to min (100ms default)
            var unitOfWork = unitOfWorkFactory.getOrCreateNewUnitOfWork();
            var orderId = OrderId.random();
            eventStore.appendToStream(ORDERS, orderId, List.of(new OrderEvent.OrderAdded(orderId, CustomerId.random(), 123)));
            unitOfWork.commit();

            assertThat(latch.await(5, TimeUnit.SECONDS)).isTrue();

            deadline = System.currentTimeMillis() + 4000;
            boolean reset = false;
            while (System.currentTimeMillis() < deadline) {
                var metrics = eventStore.getReactiveSubscriptionManager().getMetrics();
                var strategy = metrics.backoffStrategies.get(ORDERS);
                if (strategy != null && strategy.getCurrentInterval().toMillis() == 100) {
                    reset = true;
                    break;
                }
                Thread.sleep(50);
            }
            assertThat(reset).isTrue();

            // Quiet again: ensure backoff grows again to >= 200ms
            deadline = System.currentTimeMillis() + 8000;
            boolean backedOffAgain = false;
            while (System.currentTimeMillis() < deadline) {
                var metrics = eventStore.getReactiveSubscriptionManager().getMetrics();
                var strategy = metrics.backoffStrategies.get(ORDERS);
                if (strategy != null && strategy.getCurrentInterval().toMillis() >= 200) {
                    backedOffAgain = true;
                    break;
                }
                Thread.sleep(50);
            }
            assertThat(backedOffAgain).isTrue();
        } finally {
            subscription.dispose();
        }
    }

    @Test
    @Disabled("DB connectivity pause/resume is environment-dependent; kept for local execution/documentation")
    @DisplayName("Reactive polling survives transient DB connectivity issues (paused container)")
    void resilience_db_pause_disabled() {
        // For local runs: pause the Postgres container using Docker and verify that
        // the stream does not terminate and continues after unpause.
    }

    private ObjectMapper createObjectMapper() {
        var objectMapper = JsonMapper.builder()
                                     .disable(MapperFeature.AUTO_DETECT_GETTERS)
                                     .disable(MapperFeature.AUTO_DETECT_IS_GETTERS)
                                     .disable(MapperFeature.AUTO_DETECT_SETTERS)
                                     .disable(MapperFeature.DEFAULT_VIEW_INCLUSION)
                                     .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
                                     .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
                                     .disable(SerializationFeature.FAIL_ON_EMPTY_BEANS)
                                     .enable(MapperFeature.AUTO_DETECT_CREATORS)
                                     .enable(MapperFeature.AUTO_DETECT_FIELDS)
                                     .enable(MapperFeature.PROPAGATE_TRANSIENT_MARKER)
                                     .addModule(new Jdk8Module())
                                     .addModule(new JavaTimeModule())
                                     .addModule(new EssentialTypesJacksonModule())
                                     .addModule(new EssentialsImmutableJacksonModule())
                                     .build();

        objectMapper.setVisibility(objectMapper.getSerializationConfig().getDefaultVisibilityChecker()
                                               .withGetterVisibility(JsonAutoDetect.Visibility.NONE)
                                               .withSetterVisibility(JsonAutoDetect.Visibility.NONE)
                                               .withFieldVisibility(JsonAutoDetect.Visibility.ANY)
                                               .withCreatorVisibility(JsonAutoDetect.Visibility.ANY));
        return objectMapper;
    }
}
