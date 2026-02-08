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

package dk.trustworks.essentials.components.eventsourced.aggregates.projection;

import com.fasterxml.jackson.annotation.JsonAutoDetect;
import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.fasterxml.jackson.datatype.jdk8.Jdk8Module;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import dk.trustworks.essentials.components.eventsourced.aggregates.*;
import dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.PostgresqlEventStore;
import dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.eventstream.*;
import dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.persistence.table_per_aggregate_type.*;
import dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.serializer.json.JacksonJSONEventSerializer;
import dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.transaction.EventStoreManagedUnitOfWorkFactory;
import dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.types.EventOrder;
import dk.trustworks.essentials.components.foundation.postgresql.SqlExecutionTimeLogger;
import dk.trustworks.essentials.components.foundation.transaction.UnitOfWork;
import dk.trustworks.essentials.jackson.immutable.EssentialsImmutableJacksonModule;
import dk.trustworks.essentials.jackson.types.EssentialTypesJacksonModule;
import org.jdbi.v3.core.Jdbi;
import org.jdbi.v3.postgres.PostgresPlugin;
import org.junit.jupiter.api.*;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.*;

import java.util.*;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Integration tests for {@link AnnotationBasedInMemoryProjector} using a containerized PostgreSQL database.
 */
@Testcontainers
class AnnotationBasedInMemoryProjectorIT {
    public static final AggregateType ORDERS = AggregateType.of("Orders");

    @Container
    private final PostgreSQLContainer<?> postgreSQLContainer = new PostgreSQLContainer<>("postgres:latest")
            .withDatabaseName("event-store")
            .withUsername("test-user")
            .withPassword("secret-password");

    private Jdbi                               jdbi;
    private EventStoreManagedUnitOfWorkFactory unitOfWorkFactory;

    private PostgresqlEventStore<SeparateTablePerAggregateEventStreamConfiguration> eventStore;

    @BeforeEach
    void setup() {
        jdbi = Jdbi.create(postgreSQLContainer.getJdbcUrl(),
                           postgreSQLContainer.getUsername(),
                           postgreSQLContainer.getPassword());
        jdbi.installPlugin(new PostgresPlugin());
        jdbi.setSqlLogger(new SqlExecutionTimeLogger());

        unitOfWorkFactory = new EventStoreManagedUnitOfWorkFactory(jdbi);
        var aggregateEventStreamConfigurationFactory = SeparateTablePerAggregateTypeEventStreamConfigurationFactory.standardSingleTenantConfiguration(
                new JacksonJSONEventSerializer(createObjectMapper()),
                IdentifierColumnType.TEXT,
                JSONColumnType.JSONB);
        eventStore = new PostgresqlEventStore<>(unitOfWorkFactory,
                                                new SeparateTablePerAggregateTypePersistenceStrategy(jdbi,
                                                                                                     unitOfWorkFactory,
                                                                                                     new TestPersistableEventMapper(),
                                                                                                     aggregateEventStreamConfigurationFactory));

        // Add the aggregate type configuration for ORDERS
        eventStore.addAggregateEventStreamConfiguration(ORDERS, OrderId.class);

        // Register the annotation-based projector
        eventStore.addGenericInMemoryProjector(new AnnotationBasedInMemoryProjector());
    }

    @AfterEach
    void cleanup() {
        unitOfWorkFactory.getCurrentUnitOfWork().ifPresent(UnitOfWork::rollback);
        assertThat(unitOfWorkFactory.getCurrentUnitOfWork()).isEmpty();
    }

    @Test
    void projectEvents_with_real_event_store() {
        // Given
        var orderId = OrderId.random();
        var customerId = CustomerId.random();
        var productId1 = ProductId.random();
        var productId2 = ProductId.random();

        // Persist events
        unitOfWorkFactory.usingUnitOfWork(() -> {
            eventStore.appendToStream(ORDERS,
                                      orderId,
                                      EventOrder.NO_EVENTS_PREVIOUSLY_PERSISTED,
                                      List.of(
                                              new OrderCreatedEvent(orderId, customerId, 1234),
                                              new ProductAddedEvent(productId1, 5),
                                              new ProductAddedEvent(productId2, 3),
                                              new OrderAcceptedEvent()
                                      ));
        });

        // When - perform in-memory projection
        var projectionResult = unitOfWorkFactory.withUnitOfWork(() ->
                eventStore.inMemoryProjection(ORDERS, orderId, OrderSummaryProjection.class)
        );

        // Then
        assertThat(projectionResult).isPresent();
        var projection = projectionResult.get();
        assertThat((CharSequence) projection.getOrderId()).isEqualTo(orderId);
        assertThat((CharSequence) projection.getCustomerId()).isEqualTo(customerId);
        assertThat(projection.getOrderNumber()).isEqualTo(1234);
        assertThat(projection.getProducts()).hasSize(2);
        assertThat(projection.getProducts()).containsKeys(productId1, productId2);
        assertThat(projection.getProducts().get(productId1)).isEqualTo(5);
        assertThat(projection.getProducts().get(productId2)).isEqualTo(3);
        assertThat(projection.isAccepted()).isTrue();
    }

    @Test
    void projectEvents_returns_empty_for_non_existent_aggregate() {
        // Given
        var nonExistentOrderId = OrderId.random();

        // When
        var projectionResult = unitOfWorkFactory.withUnitOfWork(() ->
                eventStore.inMemoryProjection(ORDERS, nonExistentOrderId, OrderSummaryProjection.class)
        );

        // Then
        assertThat(projectionResult).isEmpty();
    }

    @Test
    void projectEvents_creates_new_projection_for_each_call() {
        // Given
        var orderId = OrderId.random();
        var customerId = CustomerId.random();

        unitOfWorkFactory.usingUnitOfWork(() -> {
            eventStore.appendToStream(ORDERS,
                                      orderId,
                                      EventOrder.NO_EVENTS_PREVIOUSLY_PERSISTED,
                                      List.of(new OrderCreatedEvent(orderId, customerId, 1234)));
        });

        // When - project twice
        var projection1 = unitOfWorkFactory.withUnitOfWork(() ->
                eventStore.inMemoryProjection(ORDERS, orderId, OrderSummaryProjection.class)
        );
        var projection2 = unitOfWorkFactory.withUnitOfWork(() ->
                eventStore.inMemoryProjection(ORDERS, orderId, OrderSummaryProjection.class)
        );

        // Then - should be different instances with same values
        assertThat(projection1).isPresent();
        assertThat(projection2).isPresent();
        assertThat(projection1.get()).isNotSameAs(projection2.get());
        assertThat((CharSequence) projection1.get().getOrderId()).isEqualTo(orderId);
        assertThat((CharSequence) projection2.get().getOrderId()).isEqualTo(orderId);
    }

    // ================================= Test Events =================================

    public record OrderCreatedEvent(OrderId orderId, CustomerId customerId, long orderNumber) {}
    public record ProductAddedEvent(ProductId productId, int quantity) {}
    public record OrderAcceptedEvent() {}

    // ================================= Test Projection =================================

    /**
     * A projection class that uses @EventHandler annotations to handle OrderEvents.
     * This demonstrates the AnnotationBasedInMemoryProjector with real domain events.
     */
    public static class OrderSummaryProjection {
        private OrderId orderId;
        private CustomerId customerId;
        private long orderNumber;
        private final Map<ProductId, Integer> products = new HashMap<>();
        private boolean accepted;

        public OrderSummaryProjection() {
            // Required no-arg constructor
        }

        @EventHandler
        private void on(OrderCreatedEvent event) {
            this.orderId = event.orderId();
            this.customerId = event.customerId();
            this.orderNumber = event.orderNumber();
        }

        @EventHandler
        private void on(ProductAddedEvent event) {
            products.merge(event.productId(), event.quantity(), Integer::sum);
        }

        @EventHandler
        private void on(OrderAcceptedEvent event) {
            this.accepted = true;
        }

        // Getters
        public OrderId getOrderId() { return orderId; }
        public CustomerId getCustomerId() { return customerId; }
        public long getOrderNumber() { return orderNumber; }
        public Map<ProductId, Integer> getProducts() { return products; }
        public boolean isAccepted() { return accepted; }
    }

    // ================================= Helper Methods =================================

    static ObjectMapper createObjectMapper() {
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
