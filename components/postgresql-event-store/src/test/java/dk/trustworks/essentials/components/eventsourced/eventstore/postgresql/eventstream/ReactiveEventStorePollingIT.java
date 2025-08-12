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
import dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.processor.EventProcessorIT;
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
import dk.trustworks.essentials.shared.MessageFormatter;
import org.jdbi.v3.core.Jdbi;
import org.jdbi.v3.postgres.PostgresPlugin;
import org.junit.jupiter.api.*;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.*;
import reactor.test.StepVerifier;

import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Integration test for the reactive event store polling mechanism.
 * Tests the new pollEventsReactive() method which combines PostgreSQL Listen/Notify
 * with intelligent fallback polling for high-performance event streaming.
 */
@Testcontainers
class ReactiveEventStorePollingIT {
    public static final EventMetaData META_DATA = EventMetaData.of("Key1", "Value1", "Key2", "Value2");
    public static final AggregateType PRODUCTS  = AggregateType.of("Products");
    public static final AggregateType ORDERS    = AggregateType.of("Orders");

    private Jdbi                                                                    jdbi;
    private EventStoreUnitOfWorkFactory<EventStoreUnitOfWork>                       unitOfWorkFactory;
    private TestPersistableEventMapper                                              eventMapper;
    private PostgresqlEventStore<SeparateTablePerAggregateEventStreamConfiguration> eventStore;

    @Container
    private final PostgreSQLContainer<?> postgreSQLContainer = new PostgreSQLContainer<>("postgres:latest")
            .withDatabaseName("reactive-event-store-test")
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
        var persistenceStrategy =
                new SeparateTablePerAggregateTypePersistenceStrategy(jdbi,
                                                                     unitOfWorkFactory,
                                                                     eventMapper,
                                                                     SeparateTablePerAggregateTypeEventStreamConfigurationFactory.standardSingleTenantConfiguration(jsonSerializer,
                                                                                                                                                                    IdentifierColumnType.TEXT,
                                                                                                                                                                    JSONColumnType.JSONB));

        eventStore = new PostgresqlEventStore<>(
                unitOfWorkFactory,
                persistenceStrategy,
                Optional.empty(),
                eventStore -> new PostgresqlEventStreamGapHandler<>(eventStore, unitOfWorkFactory),
                new EventStoreSubscriptionObserver.NoOpEventStoreSubscriptionObserver(),
                jsonSerializer
        );

        // Configure aggregate types for testing
        eventStore.addAggregateEventStreamConfiguration(ORDERS, AggregateIdSerializer.serializerFor(OrderId.class));
        eventStore.addAggregateEventStreamConfiguration(
                SeparateTablePerAggregateEventStreamConfiguration.standardSingleTenantConfiguration(
                        PRODUCTS,
                        jsonSerializer,
                        AggregateIdSerializer.serializerFor(ProductId.class),
                        IdentifierColumnType.TEXT,
                        JSONColumnType.JSON));
    }

    @AfterEach
    void cleanup() {
        unitOfWorkFactory.getCurrentUnitOfWork().ifPresent(UnitOfWork::rollback);
        assertThat(unitOfWorkFactory.getCurrentUnitOfWork()).isEmpty();
    }

    @Test
    @DisplayName("Reactive polling receives events immediately via Listen/Notify")
    void test_reactive_polling_immediate_notification() throws InterruptedException {
        // Given
        var eventsReceived = new ConcurrentLinkedQueue<PersistedEvent>();
        var subscriberId   = SubscriberId.of("ReactiveTest1");
        var eventLatch     = new CountDownLatch(5);

        // When - start reactive polling
        var subscription = eventStore.pollEventsReactive(
                ORDERS,
                GlobalEventOrder.FIRST_GLOBAL_EVENT_ORDER.longValue(),
                Optional.of(10),
                Optional.empty(),
                Optional.of(subscriberId)
                                                        ).subscribe(event -> {
            eventsReceived.add(event);
            eventLatch.countDown();
            System.out.println("Received reactive event: " + event.eventId());
        });

        // Persist events after subscription is active
        var orderId    = OrderId.random();
        var customerId = CustomerId.random();

        var unitOfWork = unitOfWorkFactory.getOrCreateNewUnitOfWork();
        var eventStream = eventStore.appendToStream(ORDERS, orderId, List.of(
                new OrderEvent.OrderAdded(orderId, customerId, 100),
                new OrderEvent.ProductAddedToOrder(orderId, ProductId.random(), 2),
                new OrderEvent.ProductOrderQuantityAdjusted(orderId, ProductId.random(), 3),
                new OrderEvent.ProductRemovedFromOrder(orderId, ProductId.random()),
                new OrderEvent.OrderAccepted(orderId)
                                                                            ));
        unitOfWork.commit();

        // Then - events should be received quickly via Listen/Notify
        var eventsReceivedWithinTimeout = eventLatch.await(5, TimeUnit.SECONDS);
        assertThat(eventsReceivedWithinTimeout).isTrue();
        assertThat(eventsReceived).hasSize(5);

        // Verify events are in order
        var eventsList = new ArrayList<>(eventsReceived);
        for (int i = 1; i < eventsList.size(); i++) {
            assertThat(eventsList.get(i).globalEventOrder().longValue())
                    .isGreaterThan(eventsList.get(i - 1).globalEventOrder().longValue());
        }

        subscription.dispose();
    }

    @Test
    @DisplayName("Reactive polling handles multiple aggregate types independently")
    void test_reactive_polling_multiple_aggregate_types() throws InterruptedException {
        // Given
        var orderEventsReceived   = new ConcurrentLinkedQueue<PersistedEvent>();
        var productEventsReceived = new ConcurrentLinkedQueue<PersistedEvent>();
        var orderLatch            = new CountDownLatch(3);
        var productLatch          = new CountDownLatch(2);

        // When - start reactive polling for both aggregate types
        var orderSubscription = eventStore.pollEventsReactive(
                ORDERS,
                GlobalEventOrder.FIRST_GLOBAL_EVENT_ORDER.longValue(),
                Optional.of(10),
                Optional.empty(),
                Optional.of(SubscriberId.of("OrdersReactive"))
                                                             ).subscribe(event -> {
            orderEventsReceived.add(event);
            orderLatch.countDown();
        });

        var productSubscription = eventStore.pollEventsReactive(
                PRODUCTS,
                GlobalEventOrder.FIRST_GLOBAL_EVENT_ORDER.longValue(),
                Optional.of(10),
                Optional.empty(),
                Optional.of(SubscriberId.of("ProductsReactive"))
                                                               ).subscribe(event -> {
            productEventsReceived.add(event);
            productLatch.countDown();
        });

        // Persist events to both aggregate types
        var unitOfWork = unitOfWorkFactory.getOrCreateNewUnitOfWork();

        var orderId = OrderId.random();
        eventStore.appendToStream(ORDERS, orderId, List.of(
                new OrderEvent.OrderAdded(orderId, CustomerId.random(), 100),
                new OrderEvent.ProductAddedToOrder(orderId, ProductId.random(), 2),
                new OrderEvent.OrderAccepted(orderId)
                                                          ));

        var productId = ProductId.random();
        eventStore.appendToStream(PRODUCTS, productId, List.of(
                new ProductEvent.ProductAdded(productId),
                new ProductEvent.ProductDiscontinued(productId)
                                                              ));

        unitOfWork.commit();

        // Then - both aggregate types should receive their respective events
        assertThat(orderLatch.await(5, TimeUnit.SECONDS)).isTrue();
        assertThat(productLatch.await(5, TimeUnit.SECONDS)).isTrue();

        assertThat(orderEventsReceived).hasSize(3);
        assertThat(productEventsReceived).hasSize(2);

        // Verify aggregate type isolation
        orderEventsReceived.forEach(event ->
                                            assertThat((CharSequence) event.aggregateType()).isEqualTo(ORDERS));
        productEventsReceived.forEach(event ->
                                              assertThat((CharSequence) event.aggregateType()).isEqualTo(PRODUCTS));

        orderSubscription.dispose();
        productSubscription.dispose();
    }

    @Test
    @DisplayName("Reactive polling performance comparison with traditional polling")
    void test_reactive_vs_traditional_polling_performance() throws InterruptedException {
        // Given
        var reactiveLatencies    = new ArrayList<Long>();
        var traditionalLatencies = new ArrayList<Long>();
        var numberOfTests        = 10;

        for (int i = 0; i < numberOfTests; i++) {
            // Test reactive polling latency
            var reactiveStartTime = new AtomicLong();
            var reactiveLatch     = new CountDownLatch(1);

            var reactiveSubscription = eventStore.pollEventsReactive(
                    ORDERS,
                    GlobalEventOrder.FIRST_GLOBAL_EVENT_ORDER.longValue(),
                    Optional.of(10),
                    Optional.empty(),
                    Optional.of(SubscriberId.of("ReactivePerf" + i))
                                                                    ).subscribe(event -> {
                var latency = System.nanoTime() - reactiveStartTime.get();
                reactiveLatencies.add(latency);
                reactiveLatch.countDown();
            });

            // Persist event and measure time
            reactiveStartTime.set(System.nanoTime());
            var unitOfWork = unitOfWorkFactory.getOrCreateNewUnitOfWork();
            eventStore.appendToStream(ORDERS, OrderId.random(),
                                      List.of(new OrderEvent.OrderAdded(OrderId.random(), CustomerId.random(), i)));
            unitOfWork.commit();

            assertThat(reactiveLatch.await(2, TimeUnit.SECONDS)).isTrue();
            reactiveSubscription.dispose();

            // Test traditional polling latency
            var traditionalStartTime = new AtomicLong();
            var traditionalLatch     = new CountDownLatch(1);

            var traditionalSubscription = eventStore.pollEvents(
                    ORDERS,
                    GlobalEventOrder.FIRST_GLOBAL_EVENT_ORDER,
                    Optional.of(10),
                    Optional.of(Duration.ofMillis(100)),
                    Optional.empty(),
                    Optional.of(SubscriberId.of("TraditionalPerf" + i))
                                                               ).subscribe(event -> {
                if (traditionalStartTime.get() > 0) {  // Only measure after we start timing
                    var latency = System.nanoTime() - traditionalStartTime.get();
                    traditionalLatencies.add(latency);
                    traditionalLatch.countDown();
                }
            });

            // Wait for polling to start, then persist event
            Thread.sleep(200);
            traditionalStartTime.set(System.nanoTime());
            unitOfWork = unitOfWorkFactory.getOrCreateNewUnitOfWork();
            eventStore.appendToStream(ORDERS, OrderId.random(),
                                      List.of(new OrderEvent.OrderAdded(OrderId.random(), CustomerId.random(), i + 1000)));
            unitOfWork.commit();

            assertThat(traditionalLatch.await(5, TimeUnit.SECONDS)).isTrue();
            traditionalSubscription.dispose();

            // Clear events between tests
            Thread.sleep(100);
        }

        // Then - reactive polling should be significantly faster
        var avgReactiveLatency    = reactiveLatencies.stream().mapToLong(Long::longValue).average().orElse(0);
        var avgTraditionalLatency = traditionalLatencies.stream().mapToLong(Long::longValue).average().orElse(0);

        System.out.println(MessageFormatter.msg("Average reactive latency: {} ns ({} ms)",
                                                avgReactiveLatency, avgReactiveLatency / 1_000_000));
        System.out.println(MessageFormatter.msg("Average traditional latency: {} ns ({} ms)",
                                                avgTraditionalLatency, avgTraditionalLatency / 1_000_000));

        // Reactive should be at least 50% faster than traditional polling
        assertThat(avgReactiveLatency).isLessThan(avgTraditionalLatency * 0.5);
    }

    @Test
    @DisplayName("Reactive polling handles backpressure correctly")
    void test_reactive_polling_backpressure() {
        // Given - create many events to test backpressure
        var orderId    = OrderId.random();
        var unitOfWork = unitOfWorkFactory.getOrCreateNewUnitOfWork();

        var events = new ArrayList<OrderEvent>();
        for (int i = 0; i < 100; i++) {
            events.add(new OrderEvent.OrderAdded(OrderId.random(), CustomerId.random(), i));
        }

        eventStore.appendToStream(ORDERS, orderId, events);
        unitOfWork.commit();

        // When - use StepVerifier to test backpressure
        StepVerifier.create(
                            eventStore.pollEventsReactive(
                                    ORDERS,
                                    GlobalEventOrder.FIRST_GLOBAL_EVENT_ORDER.longValue(),
                                    Optional.of(10),
                                    Optional.empty(),
                                    Optional.of(SubscriberId.of("BackpressureTest"))
                                                         ).take(50), 2) // Request only 2 items at a time
                    .expectNextCount(2)
                    .thenRequest(10)
                    .expectNextCount(10)
                    .thenRequest(38)
                    .expectNextCount(38)
                    .verifyComplete();
    }

    @Test
    @DisplayName("Reactive subscription manager provides correct metrics")
    void test_reactive_subscription_metrics() throws InterruptedException {
        // Given
        var eventsReceived = new AtomicInteger(0);
        var eventLatch     = new CountDownLatch(5);

        // When - start reactive polling and receive events
        var subscription = eventStore.pollEventsReactive(
                ORDERS,
                GlobalEventOrder.FIRST_GLOBAL_EVENT_ORDER.longValue(),
                Optional.of(10),
                Optional.empty(),
                Optional.of(SubscriberId.of("MetricsTest"))
                                                        ).subscribe(event -> {
            eventsReceived.incrementAndGet();
            eventLatch.countDown();
        });

        // Persist events
        var orderId    = OrderId.random();
        var unitOfWork = unitOfWorkFactory.getOrCreateNewUnitOfWork();
        eventStore.appendToStream(ORDERS, orderId, List.of(
                new OrderEvent.OrderAdded(orderId, CustomerId.random(), 1),
                new OrderEvent.ProductAddedToOrder(orderId, ProductId.random(), 2),
                new OrderEvent.ProductOrderQuantityAdjusted(orderId, ProductId.random(), 3),
                new OrderEvent.ProductRemovedFromOrder(orderId, ProductId.random()),
                new OrderEvent.OrderAccepted(orderId)
                                                          ));
        unitOfWork.commit();

        assertThat(eventLatch.await(5, TimeUnit.SECONDS)).isTrue();

        // Then - check metrics through the reactive subscription manager
        // Note: We access the manager through the event store's internal structure
        var metrics = eventStore.getReactiveSubscriptionManager().getMetrics();
        assertThat(metrics.activeSubscriptions).isGreaterThan(0);
        assertThat(metrics.totalNotificationsReceived).isGreaterThan(0);

        subscription.dispose();
    }

    @Test
    @DisplayName("Reactive polling continues after subscription cancellation and restart")
    void test_reactive_polling_restart_after_cancellation() throws InterruptedException {
        // Given
        var eventsReceived = new ConcurrentLinkedQueue<PersistedEvent>();
        var firstLatch     = new CountDownLatch(2);
        var secondLatch    = new CountDownLatch(2);

        // When - start subscription, receive some events, cancel, then restart
        var subscription1 = eventStore.pollEventsReactive(
                ORDERS,
                GlobalEventOrder.FIRST_GLOBAL_EVENT_ORDER.longValue(),
                Optional.of(10),
                Optional.empty(),
                Optional.of(SubscriberId.of("RestartTest1"))
                                                         ).subscribe(event -> {
            eventsReceived.add(event);
            firstLatch.countDown();
        });

        // Persist first batch of events
        var orderId1   = OrderId.random();
        var unitOfWork = unitOfWorkFactory.getOrCreateNewUnitOfWork();
        eventStore.appendToStream(ORDERS, orderId1, List.of(
                new OrderEvent.OrderAdded(orderId1, CustomerId.random(), 1),
                new OrderEvent.OrderAccepted(orderId1)
                                                           ));
        unitOfWork.commit();

        assertThat(firstLatch.await(5, TimeUnit.SECONDS)).isTrue();
        var firstBatchSize = eventsReceived.size();

        // Cancel subscription
        subscription1.dispose();
        Thread.sleep(100);

        // Start new subscription from where we left off
        var lastEventOrder = eventsReceived.stream()
                                           .mapToLong(event -> event.globalEventOrder().longValue())
                                           .max().orElse(0L) + 1;

        var subscription2 = eventStore.pollEventsReactive(
                ORDERS,
                lastEventOrder,
                Optional.of(10),
                Optional.empty(),
                Optional.of(SubscriberId.of("RestartTest2"))
                                                         ).subscribe(event -> {
            eventsReceived.add(event);
            secondLatch.countDown();
        });

        // Persist second batch of events
        var orderId2 = OrderId.random();
        unitOfWork = unitOfWorkFactory.getOrCreateNewUnitOfWork();
        eventStore.appendToStream(ORDERS, orderId2, List.of(
                new OrderEvent.OrderAdded(orderId2, CustomerId.random(), 2),
                new OrderEvent.OrderAccepted(orderId2)
                                                           ));
        unitOfWork.commit();

        assertThat(secondLatch.await(5, TimeUnit.SECONDS)).isTrue();

        // Then
        assertThat(eventsReceived.size()).isEqualTo(firstBatchSize + 2);

        subscription2.dispose();
    }

    /**
     * Creates test events similar to the main integration test
     */
    private Map<AggregateType, Map<?, List<?>>> createTestEvents() {
        var eventsPerAggregateType = new HashMap<AggregateType, Map<?, List<?>>>();

        // Products
        var productAggregatesAndEvents = new HashMap<Object, List<?>>();
        for (int i = 0; i < 5; i++) {
            var productId = ProductId.random();
            if (i % 2 == 0) {
                productAggregatesAndEvents.put(productId, List.of(
                        new ProductEvent.ProductAdded(productId),
                        new ProductEvent.ProductDiscontinued(productId)
                                                                 ));
            } else {
                productAggregatesAndEvents.put(productId, List.of(
                        new ProductEvent.ProductAdded(productId)
                                                                 ));
            }
        }
        eventsPerAggregateType.put(PRODUCTS, productAggregatesAndEvents);

        // Orders
        var orderAggregatesAndEvents = new HashMap<Object, List<?>>();
        for (int i = 0; i < 10; i++) {
            var orderId = OrderId.random();
            if (i % 2 == 0) {
                orderAggregatesAndEvents.put(orderId, List.of(
                        new OrderEvent.OrderAdded(orderId, CustomerId.random(), i),
                        new OrderEvent.ProductAddedToOrder(orderId, ProductId.random(), i)
                                                             ));
            } else if (i % 3 == 0) {
                orderAggregatesAndEvents.put(orderId, List.of(
                        new OrderEvent.OrderAdded(orderId, CustomerId.random(), i),
                        new OrderEvent.ProductAddedToOrder(orderId, ProductId.random(), i),
                        new OrderEvent.ProductOrderQuantityAdjusted(orderId, ProductId.random(), i - 1)
                                                             ));
            } else {
                orderAggregatesAndEvents.put(orderId, List.of(
                        new OrderEvent.OrderAdded(orderId, CustomerId.random(), i)
                                                             ));
            }
        }
        eventsPerAggregateType.put(ORDERS, orderAggregatesAndEvents);

        return eventsPerAggregateType;
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