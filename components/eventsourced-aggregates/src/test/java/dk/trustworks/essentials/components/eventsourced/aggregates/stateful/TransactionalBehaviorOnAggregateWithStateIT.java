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

package dk.trustworks.essentials.components.eventsourced.aggregates.stateful;

import com.fasterxml.jackson.annotation.JsonAutoDetect;
import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.fasterxml.jackson.datatype.jdk8.Jdk8Module;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import dk.trustworks.essentials.components.distributed.fencedlock.postgresql.PostgresqlFencedLockManager;
import dk.trustworks.essentials.components.eventsourced.aggregates.*;
import dk.trustworks.essentials.components.eventsourced.aggregates.stateful.classic.Event;
import dk.trustworks.essentials.components.eventsourced.aggregates.stateful.classic.state.*;
import dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.PostgresqlEventStore;
import dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.eventstream.*;
import dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.persistence.*;
import dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.persistence.table_per_aggregate_type.*;
import dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.processor.*;
import dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.serializer.json.JacksonJSONEventSerializer;
import dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.subscription.*;
import dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.transaction.EventStoreManagedUnitOfWorkFactory;
import dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.types.*;
import dk.trustworks.essentials.components.foundation.messaging.MessageHandler;
import dk.trustworks.essentials.components.foundation.messaging.eip.store_and_forward.Inboxes;
import dk.trustworks.essentials.components.foundation.postgresql.SqlExecutionTimeLogger;
import dk.trustworks.essentials.components.foundation.reactive.command.*;
import dk.trustworks.essentials.components.foundation.transaction.UnitOfWork;
import dk.trustworks.essentials.components.foundation.types.*;
import dk.trustworks.essentials.components.queue.postgresql.PostgresqlDurableQueues;
import dk.trustworks.essentials.jackson.immutable.EssentialsImmutableJacksonModule;
import dk.trustworks.essentials.jackson.types.EssentialTypesJacksonModule;
import dk.trustworks.essentials.reactive.command.CmdHandler;
import org.assertj.core.api.InstanceOfAssertFactories;
import org.jdbi.v3.core.Jdbi;
import org.jdbi.v3.postgres.PostgresPlugin;
import org.junit.jupiter.api.*;
import org.slf4j.*;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.*;

import java.time.*;
import java.util.List;

import static dk.trustworks.essentials.components.eventsourced.aggregates.stateful.StatefulAggregateInstanceFactory.reflectionBasedAggregateRootFactory;
import static dk.trustworks.essentials.shared.FailFast.requireNonNull;
import static org.assertj.core.api.Assertions.*;

@Testcontainers
public class TransactionalBehaviorOnAggregateWithStateIT {
    private static final Logger        log    = LoggerFactory.getLogger(TransactionalBehaviorOnAggregateWithStateIT.class);
    public static final  AggregateType ORDERS = AggregateType.of("TestOrders");

    @Container
    private final PostgreSQLContainer<?> postgreSQLContainer = new PostgreSQLContainer<>("postgres:latest")
            .withDatabaseName("event-store")
            .withUsername("test-user")
            .withPassword("secret-password");

    private Jdbi                                                                        jdbi;
    private EventStoreManagedUnitOfWorkFactory                                          unitOfWorkFactory;
    private PostgresqlEventStore<SeparateTablePerAggregateEventStreamConfiguration>     eventStore;
    private StatefulAggregateRepository<OrderId, TestOrderEvent, TestOrderWithState>    ordersRepository;
    private DefaultEventStoreSubscriptionManager                                        eventStoreSubscriptionManager;
    private PostgresqlDurableQueues                                                     durableQueues;
    private Inboxes                                                                     inboxes;
    private DurableLocalCommandBus                                                      commandBus;
    private PostgresqlFencedLockManager                                                 lockManager;

    private OrderCommandHandler commandHandler;
    private EventProcessorP1    eventProcessorP1;
    private EventProcessorP2    eventProcessorP2;

    @BeforeEach
    void setup() {
        jdbi = Jdbi.create(postgreSQLContainer.getJdbcUrl(),
                           postgreSQLContainer.getUsername(),
                           postgreSQLContainer.getPassword());
        jdbi.installPlugin(new PostgresPlugin());
        jdbi.setSqlLogger(new SqlExecutionTimeLogger());

        unitOfWorkFactory = new EventStoreManagedUnitOfWorkFactory(jdbi);
        var aggregateEventStreamConfigurationFactory = SeparateTablePerAggregateTypeEventStreamConfigurationFactory.standardSingleTenantConfiguration(new JacksonJSONEventSerializer(createObjectMapper()),
                                                                                                                                                      IdentifierColumnType.UUID,
                                                                                                                                                      JSONColumnType.JSONB);
        eventStore = new PostgresqlEventStore<>(unitOfWorkFactory,
                                                new SeparateTablePerAggregateTypePersistenceStrategy(jdbi,
                                                                                                     unitOfWorkFactory,
                                                                                                     new TestPersistableEventMapper(),
                                                                                                     aggregateEventStreamConfigurationFactory));

        lockManager = PostgresqlFencedLockManager.builder()
                                                 .setJdbi(jdbi)
                                                 .setUnitOfWorkFactory(unitOfWorkFactory)
                                                 .setLockTimeOut(Duration.ofSeconds(3))
                                                 .setLockConfirmationInterval(Duration.ofSeconds(1))
                                                 .buildAndStart();

        eventStoreSubscriptionManager = EventStoreSubscriptionManager.builder()
                                                                     .setEventStore(eventStore)
                                                                     .setFencedLockManager(lockManager)
                                                                     .setDurableSubscriptionRepository(new PostgresqlDurableSubscriptionRepository(jdbi, eventStore))
                                                                     .build();

        ordersRepository = StatefulAggregateRepository.from(eventStore,
                                                            ORDERS,
                                                            reflectionBasedAggregateRootFactory(),
                                                            TestOrderWithState.class);

        durableQueues = PostgresqlDurableQueues.builder()
                                               .setUnitOfWorkFactory(unitOfWorkFactory)
                                               .build();

        inboxes = Inboxes.durableQueueBasedInboxes(durableQueues, lockManager);

        commandBus = DurableLocalCommandBus.builder()
                                           .setDurableQueues(durableQueues)
                                           .setInterceptors(new UnitOfWorkControllingCommandBusInterceptor(unitOfWorkFactory))
                                           .build();

        durableQueues.start();
        eventStoreSubscriptionManager.start();
    }

    @AfterEach
    void cleanup() {
        unitOfWorkFactory.getCurrentUnitOfWork().ifPresent(UnitOfWork::rollback);
        assertThat(unitOfWorkFactory.getCurrentUnitOfWork()).isEmpty();

        if (commandHandler != null) {
            commandHandler.stop();
        }
        if (eventProcessorP1 != null) {
            eventProcessorP1.stop();
        }
        if (eventProcessorP2 != null) {
            eventProcessorP2.stop();
        }
        if (durableQueues != null) {
            durableQueues.stop();
        }
        if (eventStoreSubscriptionManager != null) {
            eventStoreSubscriptionManager.stop();
        }
        if (lockManager != null) {
            lockManager.stop();
        }
    }

    @Test
    void multiple_chained_events_on_new_aggregate_with_state_test() {
        log.info("---------- Scenario 1: multiple chained Event Handlers on a new Aggregate -------");

        // Given: Two event processors both reacting to OrderCreated event
        var eventProcessorDeps = new EventProcessorDependencies(
                eventStoreSubscriptionManager,
                inboxes,
                commandBus,
                List.of()
        );

        commandHandler = new OrderCommandHandler(eventProcessorDeps, ordersRepository);
        commandHandler.start();

        eventProcessorP1 = new EventProcessorP1(eventProcessorDeps, ordersRepository);
        eventProcessorP1.start();

        eventProcessorP2 = new EventProcessorP2(eventProcessorDeps, ordersRepository);
        eventProcessorP2.start();

        var orderId = OrderId.random();
        var cmd     = new CreateOrderCommand(orderId);

        // When: Send command which creates aggregate with OrderCreated event
        // This triggers P1 to react to OrderCreated and appends ProductAdded, which triggers P2 which reacts to ProductAdded and appends ProductDataAppended event to the same aggregate
        // Then: Should NOT throw exception
        assertThatCode(() -> commandBus.send(cmd))
                .as("Both event processors should successfully modify the aggregate without OptimisticAppendToStreamException")
                .doesNotThrowAnyException();

        // Verify both products were added successfully
        var stream = unitOfWorkFactory.withUnitOfWork(() -> eventStore.fetchStream(ORDERS, orderId));

        assertThat(stream).isPresent().get()
              .extracting(AggregateEventStream::eventList, InstanceOfAssertFactories.list(PersistedEvent.class))
              .as("Should have OrderCreated + 2 ProductAdded events")
              .hasSize(3)
              .satisfies(events -> {
                  assertThat((Object) events.get(0).event().deserialize()).isInstanceOf(TestOrderEvent.OrderCreated.class);
                  assertThat((Object) events.get(1).event().deserialize()).isInstanceOf(TestOrderEvent.ProductAdded.class);
                  assertThat((Object) events.get(2).event().deserialize()).isInstanceOf(TestOrderEvent.ProductDataAppended.class);
              });
    }

    @Test
    void multiple_chained_events_on_existing_aggregate_with_state_test() {
        log.info("---------- Scenario 2: multiple chained Event Handlers on an existing Aggregate -------");

        var eventProcessorDeps = new EventProcessorDependencies(
                eventStoreSubscriptionManager,
                inboxes,
                commandBus,
                List.of()
        );

        commandHandler = new OrderCommandHandler(eventProcessorDeps, ordersRepository);
        commandHandler.start();

        eventProcessorP2 = new EventProcessorP2(eventProcessorDeps, ordersRepository);
        eventProcessorP2.start();

        var orderId = OrderId.random();
        var cmd     = new CreateOrderCommand(orderId);

        assertThatCode(() -> commandBus.send(cmd))
                .as("Aggregate creation should not throw exception")
                .doesNotThrowAnyException();


        // When: Send command which adds a new product to an existing order
        // This triggers P2 which reacts to ProductAdded and appends ProductDataAppended event to the same aggregate
        // Then: Should NOT throw exception
        assertThatCode(() -> commandBus.send(new AddProductCommand(orderId, ProductId.of("PRODUCT_A"))))
                .as("Both event processors should successfully modify the aggregate without OptimisticAppendToStreamException")
                .doesNotThrowAnyException();


        // Verify both products were added successfully
        var stream = unitOfWorkFactory.withUnitOfWork(() -> eventStore.fetchStream(ORDERS, orderId));

        assertThat(stream).isPresent().get()
              .extracting(AggregateEventStream::eventList, InstanceOfAssertFactories.list(PersistedEvent.class))
              .as("Should have OrderCreated + ProductAdded + ProductDataAppended events")
              .hasSize(3)
              .satisfies(events -> {
                  assertThat((Object) events.get(0).event().deserialize()).isInstanceOf(TestOrderEvent.OrderCreated.class);
                  assertThat((Object) events.get(1).event().deserialize()).isInstanceOf(TestOrderEvent.ProductAdded.class);
                  assertThat((Object) events.get(2).event().deserialize()).isInstanceOf(TestOrderEvent.ProductDataAppended.class);
              });
    }

    // ========================================================================================================
    // TEST COMMANDS
    // ========================================================================================================

    private record CreateOrderCommand(OrderId orderId) {
    }

    private record AddProductCommand(OrderId orderId, ProductId productId) {
    }

    /**
     * Command handler - creates the aggregate and persists OrderCreated event.
     */
    private static class OrderCommandHandler extends InTransactionEventProcessor {
        private final StatefulAggregateRepository<OrderId, TestOrderEvent, TestOrderWithState> repository;

        protected OrderCommandHandler(EventProcessorDependencies eventProcessorDependencies,
                                      StatefulAggregateRepository<OrderId, TestOrderEvent, TestOrderWithState> repository) {
            super(eventProcessorDependencies, false);
            this.repository = repository;
        }

        @Override
        public String getProcessorName() {
            return "OrderCommandHandler";
        }

        @Override
        protected List<AggregateType> reactsToEventsRelatedToAggregateTypes() {
            return List.of(ORDERS);
        }

        @CmdHandler
        void handle(CreateOrderCommand cmd) {
            log.debug("@CmdHandler: Creating order {}", cmd.orderId);
            repository.save(new TestOrderWithState(cmd.orderId));
        }

        @CmdHandler
        void handle(AddProductCommand cmd) {
            repository.load(cmd.orderId).addProduct(cmd.productId);
        }
    }

    /**
     * Event processor reacting to OrderCreated event and directly adding a product to the order
     */
    private static class EventProcessorP1 extends InTransactionEventProcessor {
        private final StatefulAggregateRepository<OrderId, TestOrderEvent, TestOrderWithState> repository;

        protected EventProcessorP1(EventProcessorDependencies eventProcessorDependencies,
                                   StatefulAggregateRepository<OrderId, TestOrderEvent, TestOrderWithState> repository) {
            super(eventProcessorDependencies, false);
            this.repository = repository;
        }

        @Override
        public String getProcessorName() {
            return "EventProcessorP1";
        }

        @Override
        protected List<AggregateType> reactsToEventsRelatedToAggregateTypes() {
            return List.of(ORDERS);
        }

        @MessageHandler
        void on(TestOrderEvent.OrderCreated event) {
            log.debug("P2: Reacting to OrderCreated {} -> adding PRODUCT_A", event.aggregateId());
            repository.load(event.aggregateId()).addProduct(ProductId.of("PRODUCT_A"));
        }
    }

    /**
     * Event processor reacting to ProductAdded event and directly adding another event with product meta data
     */
    private static class EventProcessorP2 extends InTransactionEventProcessor {
        private final StatefulAggregateRepository<OrderId, TestOrderEvent, TestOrderWithState> repository;

        protected EventProcessorP2(EventProcessorDependencies eventProcessorDependencies,
                                   StatefulAggregateRepository<OrderId, TestOrderEvent, TestOrderWithState> repository) {
            super(eventProcessorDependencies, false);
            this.repository = repository;
        }

        @Override
        public String getProcessorName() {
            return "EventProcessorP2";
        }

        @Override
        protected List<AggregateType> reactsToEventsRelatedToAggregateTypes() {
            return List.of(ORDERS);
        }

        @MessageHandler
        void on(TestOrderEvent.ProductAdded event) {
            log.debug("P2: Reacting to OrderCreated {} -> adding ProductData related to the Product Added", event.aggregateId());
            repository.load(event.aggregateId()).appendProductData(event.productId, "productName");
        }
    }


    public static class TestOrderWithState extends AggregateRootWithState<OrderId, TestOrderEvent, TestOrderState, TestOrderWithState> {

        /**
         * Required when we use {@link StatefulAggregateInstanceFactory.ReflectionBasedAggregateInstanceFactory}, otherwise the
         * factory will call the constructor accepting the OrderId which cause the OrderCreated event to be applied everytime
         * we load the aggregate
         */
        public TestOrderWithState() {
        }

        public TestOrderWithState(OrderId orderId) {
            apply(new TestOrderEvent.OrderCreated(orderId));
        }

        public void addProduct(ProductId productId) {
            requireNonNull(productId, "You must provide a productId");
            apply(new TestOrderEvent.ProductAdded(aggregateId(), productId));
        }

        public void appendProductData(ProductId productId, String productName) {
            requireNonNull(productId, "You must provide a productId");
            apply(new TestOrderEvent.ProductDataAppended(aggregateId(), productId, productName));
        }

    }

    public static class TestOrderState extends AggregateState<OrderId, TestOrderEvent> {
    }

    public static class TestOrderEvent extends Event<OrderId> {

        protected TestOrderEvent(OrderId orderId) {
            aggregateId(requireNonNull(orderId));
        }

        public static class OrderCreated extends TestOrderEvent {

            public OrderCreated(OrderId orderId) {
                super(orderId);
            }
        }

        public static class ProductAdded extends TestOrderEvent {
            public final ProductId productId;

            public ProductAdded(OrderId orderId, ProductId productId) {
                super(orderId);
                this.productId = productId;
            }
        }

        public static class ProductDataAppended extends TestOrderEvent {
            public final ProductId productId;
            public final String productName;

            public ProductDataAppended(OrderId orderId, ProductId productId, String productName) {
                super(orderId);
                this.productId = productId;
                this.productName = productName;
            }
        }
    }



    // ========================================================================================================
    // HELPER CLASSES
    // ========================================================================================================

    public static ObjectMapper createObjectMapper() {
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

    public static class TestPersistableEventMapper implements PersistableEventMapper {
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
                                         null,
                                         EventMetaData.of("Key1", "Value1", "Key2", "Value2"),
                                         OffsetDateTime.now(),
                                         causedByEventId,
                                         correlationId,
                                         null);
        }
    }
}
