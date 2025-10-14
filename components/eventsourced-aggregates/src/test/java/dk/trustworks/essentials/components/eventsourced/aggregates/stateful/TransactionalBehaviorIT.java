/*
 * Copyright 2021-2025 the original author or authors.
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
import dk.trustworks.essentials.components.eventsourced.aggregates.stateful.modern.AggregateRoot;
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
import org.jdbi.v3.core.Jdbi;
import org.jdbi.v3.postgres.PostgresPlugin;
import org.junit.jupiter.api.*;
import org.slf4j.*;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.*;

import java.time.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;

import static dk.trustworks.essentials.components.eventsourced.aggregates.stateful.StatefulAggregateInstanceFactory.reflectionBasedAggregateRootFactory;
import static dk.trustworks.essentials.shared.FailFast.requireNonNull;
import static org.assertj.core.api.Assertions.*;

/**
 * Integration tests to verify transactional behavior in event-sourced CQRS system using a combination of {@link StatefulAggregateRepository}
 * and {@link InTransactionEventProcessor}.
 *
 * <h2>Scenario 1: Exception Propagation & Transaction Rollback</h2>
 * <p><b>Original Problem Statement:</b></p>
 * <p>When an exception is thrown in a nested (InTransactionEventProcessor) event handler (P4), the exception should propagate all the way back to the command caller,
 * AND the entire transaction (including both event persistence and view updates) should be rolled back. Without proper exception handling
 * and transaction management, the exception might be swallowed, or partial changes might be committed.</p>
 *
 * <p><b>Event Flow - Chained Event Processing within the same {@link InTransactionEventProcessor}:</b></p>
 * <pre>
 * Command: CreateOrderCommand(orderId, customerId)
 *    ↓
 * [CommandHandler] creates aggregate → persists {@link TestOrderEvent.OrderCreated} event
 *    ↓
 * [P1] reacts to {@link TestOrderEvent.OrderCreated} → loads aggregate → applies {@link TestOrderEvent.ProductAdded} event
 *    ↓
 * [P2] reacts to {@link TestOrderEvent.ProductAdded} → updates {@link OrderView} (database table)
 *    ↓
 * [P3] reacts to {@link TestOrderEvent.ProductAdded} → loads aggregate → applies {@link TestOrderEvent.OrderConfirmed} event
 *    ↓
 * [P4] reacts to {@link TestOrderEvent.OrderConfirmed} → throws RuntimeException("Intentional failure in P4")
 * </pre>
 *
 * <p><b>Expected Behavior:</b></p>
 * <ul>
 *   <li>Exception propagates to commandBus.send() caller with root cause "Intentional failure in P4"</li>
 *   <li>NO events persisted in EventStore (complete rollback)</li>
 *   <li>NO view updates persisted in order_views table (complete rollback)</li>
 *   <li>NO aggregate state exists after rollback</li>
 * </ul>
 *
 * <p><b>Tests:</b></p>
 * <ul>
 *   <li>{@link #exception_in_event_handler_chain_should_propagate_and_rollback_entire_transaction()} - Failure case</li>
 *   <li>{@link #successful_event_handler_chain_should_commit_all_changes()} - Success case (no exception)</li>
 * </ul>
 *
 * <h2>Scenario 2: Multiple changes to the same Aggregate across different InTransactionEventProcessors within the same UnitOfWork</h2>
 * <p><b>Original Problem Statement:</b></p>
 * <p>When multiple {@link InTransactionEventProcessor}s react to the same event ({@link TestOrderEvent.OrderCreated}) and each loads and modifies the same aggregate
 * within the same transaction, WITHOUT proper identity map/caching, they will each load a stale version of the aggregate. When both processors
 * try to persist events with the same eventOrder, an {@link OptimisticAppendToStreamException} is thrown.</p>
 *
 * <p><b>Event Flow - Concurrent Event Processing:</b></p>
 * <pre>
 * Command: CreateOrderCommand(orderId, customerId)
 *    ↓
 * [CommandHandler] creates aggregate → persists {@link TestOrderEvent.OrderCreated} event (eventOrder=0)
 *    ↓
 * ┌──────────────────────────────────┬──────────────────────────────────┐
 * │ [EventProcessorP1]               │ [EventProcessorP2]               │
 * │ reacts to OrderCreated           │ reacts to OrderCreated           │
 * │                                  │                                  │
 * │ repository.load(orderId)         │ repository.load(orderId)         │
 * │ → loads aggregate (eventOrder=0 )│ → loads aggregate (eventOrder=?) │
 * │                                  │                                  │
 * │ order.addProduct(PRODUCT_A)      │ order.addProduct(PRODUCT_B)      │
 * │ → applies ProductAdded event     │ → applies ProductAdded event     │
 * │ → persists with eventOrder=1     │ → persists with eventOrder=?     │
 * └──────────────────────────────────┴──────────────────────────────────┘
 * </pre>
 *
 * <p><b>Behavior WITHOUT Identity Map/Caching (BUG):</b></p>
 * <ul>
 *   <li>P1: loads aggregate at eventOrder=0, applies PRODUCT_A → persists at eventOrder=1 ✓</li>
 *   <li>P2: loads aggregate at eventOrder=0 (STALE!), applies PRODUCT_B → tries to persist at eventOrder=1</li>
 *   <li>Result: <b>OptimisticAppendToStreamException</b> - eventOrder conflict (both trying to use eventOrder=1)</li>
 * </ul>
 *
 * <p><b>Behavior WITH Identity Map/Caching (FIXED):</b></p>
 * <ul>
 *   <li>P1: loads aggregate at eventOrder=0, applies PRODUCT_A → persists at eventOrder=1 ✓</li>
 *   <li>P2: loads aggregate from cache at eventOrder=1 (FRESH!), applies PRODUCT_B → persists at eventOrder=2 ✓</li>
 *   <li>Result: Both products successfully added, no exception</li>
 * </ul>
 *
 * <p><b>Expected Behavior (After Fix):</b></p>
 * <ul>
 *   <li>No OptimisticAppendToStreamException thrown</li>
 *   <li>3 events persisted: OrderCreated + ProductAdded(PRODUCT_A) + ProductAdded(PRODUCT_B)</li>
 *   <li>Aggregate contains both products: {PRODUCT_A, PRODUCT_B}</li>
 * </ul>
 *
 * <p><b>Test:</b></p>
 * <ul>
 *   <li>{@link #multiple_event_handlers_loading_same_aggregate_should_not_cause_optimistic_concurrency_exception()}</li>
 * </ul>
 */
@Testcontainers
public class TransactionalBehaviorIT {
    private static final Logger        log    = LoggerFactory.getLogger(TransactionalBehaviorIT.class);
    public static final  AggregateType ORDERS = AggregateType.of("TestOrders");

    @Container
    private final PostgreSQLContainer<?> postgreSQLContainer = new PostgreSQLContainer<>("postgres:latest")
            .withDatabaseName("event-store")
            .withUsername("test-user")
            .withPassword("secret-password");

    private Jdbi                                                                    jdbi;
    private EventStoreManagedUnitOfWorkFactory                                      unitOfWorkFactory;
    private PostgresqlEventStore<SeparateTablePerAggregateEventStreamConfiguration> eventStore;
    private StatefulAggregateRepository<OrderId, TestOrderEvent, TestOrder>         ordersRepository;
    private DefaultEventStoreSubscriptionManager                                    eventStoreSubscriptionManager;
    private PostgresqlDurableQueues                                                 durableQueues;
    private Inboxes                                                                 inboxes;
    private DurableLocalCommandBus                                                  commandBus;
    private PostgresqlFencedLockManager                                             lockManager;

    // Scenario 1: Exception propagation test components
    private ChainedEventProcessor chainedProcessor;
    private OrderViewRepository   orderViewRepository;

    // Scenario 2: Optimistic concurrency test components
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
                                                            TestOrder.class);

        durableQueues = PostgresqlDurableQueues.builder()
                                               .setUnitOfWorkFactory(unitOfWorkFactory)
                                               .build();

        inboxes = Inboxes.durableQueueBasedInboxes(durableQueues, lockManager);

        commandBus = DurableLocalCommandBus.builder()
                                           .setDurableQueues(durableQueues)
                                           .setInterceptors(new UnitOfWorkControllingCommandBusInterceptor(unitOfWorkFactory))
                                           .build();

        orderViewRepository = new OrderViewRepository(unitOfWorkFactory);

        durableQueues.start();
        eventStoreSubscriptionManager.start();
    }

    @AfterEach
    void cleanup() {
        unitOfWorkFactory.getCurrentUnitOfWork().ifPresent(UnitOfWork::rollback);
        assertThat(unitOfWorkFactory.getCurrentUnitOfWork()).isEmpty();

        if (chainedProcessor != null) {
            chainedProcessor.stop();
        }
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

    // ========================================================================================================
    // SCENARIO 1: Exception Propagation & Transaction Rollback
    // ========================================================================================================

    @Test
    void exception_in_event_handler_chain_should_propagate_and_rollback_entire_transaction() {
        log.info("---------- Scenario 1: Exception Propagation & Transaction Rollback -------");

        // Given: Event processor configured to fail on OrderConfirmed event
        chainedProcessor = new ChainedEventProcessor(
                new EventProcessorDependencies(
                        eventStoreSubscriptionManager,
                        inboxes,
                        commandBus,
                        List.of()
                ),
                ordersRepository,
                orderViewRepository,
                new AtomicBoolean(true)  // shouldFailOnConfirmed = true
        );
        chainedProcessor.start();

        var orderId = OrderId.random();
        var cmd     = new CreateOrderCommand(orderId, CustomerId.random());

        // When: Send command that triggers chained event processing ending with exception
        // Flow: CreateOrder -> OrderCreated -> P1(ProductAdded) -> P2(UpdateView) -> P3(OrderConfirmed) -> P4(EXCEPTION)

        // Then: Exception should propagate to caller
        assertThatThrownBy(() -> commandBus.send(cmd))
                .hasCauseInstanceOf(RuntimeException.class)
                .hasRootCauseMessage("Intentional failure in P4");

        // Verify rollback: no events persisted
        var stream = unitOfWorkFactory.withUnitOfWork(() -> eventStore.fetchStream(ORDERS, orderId));
        assertThat(stream)
                .as("No events should be persisted after rollback")
                .isEmpty();

        // Verify rollback: no view updates
        var view = unitOfWorkFactory.withUnitOfWork(() -> orderViewRepository.findById(orderId));
        assertThat(view)
                .as("View should not be updated after rollback")
                .isEmpty();

        // Verify no aggregate exists
        assertThat(unitOfWorkFactory.withUnitOfWork(() -> ordersRepository.tryLoad(orderId)))
                .as("Aggregate should not exist after rollback")
                .isEmpty();
    }

    @Test
    void successful_event_handler_chain_should_commit_all_changes() {
        log.info("---------- Scenario 1 (Success Case): Successful Event Handler Chain -------");

        // Given: Event processor configured to succeed
        chainedProcessor = new ChainedEventProcessor(
                new EventProcessorDependencies(
                        eventStoreSubscriptionManager,
                        inboxes,
                        commandBus,
                        List.of()
                ),
                ordersRepository,
                orderViewRepository,
                new AtomicBoolean(false)  // shouldFailOnConfirmed = false
        );
        chainedProcessor.start();

        var orderId = OrderId.random();
        var cmd     = new CreateOrderCommand(orderId, CustomerId.random());

        // When: Send command that triggers successful chained event processing
        assertThatCode(() -> commandBus.send(cmd))
                .doesNotThrowAnyException();

        // Then: All events should be persisted
        var stream = unitOfWorkFactory.withUnitOfWork(() -> eventStore.fetchStream(ORDERS, orderId));
        assertThat(stream).isPresent();
        assertThat(stream.get().eventList())
                .hasSize(3)  // OrderCreated + ProductAdded + OrderConfirmed
                .satisfies(events -> {
                    assertThat((Object) events.get(0).event().deserialize()).isInstanceOf(TestOrderEvent.OrderCreated.class);
                    assertThat((Object) events.get(1).event().deserialize()).isInstanceOf(TestOrderEvent.ProductAdded.class);
                    assertThat((Object) events.get(2).event().deserialize()).isInstanceOf(TestOrderEvent.OrderConfirmed.class);
                });

        // Verify view was updated
        var view = unitOfWorkFactory.withUnitOfWork(() -> orderViewRepository.findById(orderId));
        assertThat(view).isPresent();
        assertThat(view.get())
                .satisfies(v -> {
                    assertThat((Object) v.orderId).isEqualTo(orderId);
                    assertThat(v.productCount).isEqualTo(1);
                    assertThat(v.confirmed).isTrue();
                });

        // Verify aggregate state
        var order = unitOfWorkFactory.withUnitOfWork(() -> ordersRepository.load(orderId));
        assertThat(order.products).hasSize(1).contains(ProductId.of("AUTO_PRODUCT"));
        assertThat(order.confirmed).isTrue();
    }

    // ==============================================================================================================================
    // SCENARIO 2: Multiple changes to the same Aggregate across different InTransactionEventProcessors within the same UnitOfWork
    // ==============================================================================================================================

    @Test
    void multiple_event_handlers_loading_same_aggregate_should_not_cause_optimistic_concurrency_exception() {
        log.info("---------- Scenario 2: Multiple Event Handlers Loading Same Aggregate -------");

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
        var cmd     = new CreateOrderCommand(orderId, CustomerId.random());

        // When: Send command which creates aggregate with OrderCreated event
        // This triggers BOTH P1 and P2 to react to OrderCreated
        // Without identity map/caching:
        //   - P1 loads aggregate (eventOrder=0), applies ProductAdded for PRODUCT_A (eventOrder=1)
        //   - P2 loads aggregate (eventOrder=0 - stale!), tries to apply ProductAdded for PRODUCT_B (eventOrder=1 - conflict!)
        //   - Result: OptimisticAppendToStreamException
        // With identity map/caching (after fix):
        //   - P1 loads aggregate (eventOrder=0), applies ProductAdded for PRODUCT_A (eventOrder=1)
        //   - P2 loads aggregate from cache (eventOrder=1 - fresh!), applies ProductAdded for PRODUCT_B (eventOrder=2)
        //   - Result: Both events successfully persisted

        // Then: Should NOT throw exception
        assertThatCode(() -> commandBus.send(cmd))
                .as("Both event processors should successfully modify the aggregate without OptimisticAppendToStreamException")
                .doesNotThrowAnyException();

        // Verify both products were added successfully
        var stream = unitOfWorkFactory.withUnitOfWork(() -> eventStore.fetchStream(ORDERS, orderId));

        assertThat(stream).isPresent();
        assertThat(stream.get().eventList())
                .as("Should have OrderCreated + 2 ProductAdded events")
                .hasSize(3)
                .satisfies(events -> {
                    assertThat((Object) events.get(0).event().deserialize()).isInstanceOf(TestOrderEvent.OrderCreated.class);
                    assertThat((Object) events.get(1).event().deserialize()).isInstanceOf(TestOrderEvent.ProductAdded.class);
                    assertThat((Object) events.get(2).event().deserialize()).isInstanceOf(TestOrderEvent.ProductAdded.class);
                });

        var order = unitOfWorkFactory.withUnitOfWork(() -> ordersRepository.load(orderId));
        assertThat(order.products)
                .as("Both products should be added to the order")
                .hasSize(2)
                .contains(ProductId.of("PRODUCT_A"), ProductId.of("PRODUCT_B"));
    }

    // ==============================================================================================================================
    // SCENARIO 3: Multiple changes to an existing Aggregate across different InTransactionEventProcessors within the same UnitOfWork
    // ==============================================================================================================================

    @Test
    void multiple_chained_events_on_existing_aggregate_test() {
        log.info("---------- Scenario 3: Multiple chained Event Handlers on an existing Aggregate (AggregateRoot) -------");

        // Given: Command handler and event processor that reacts to ProductAdded
        var eventProcessorDeps = new EventProcessorDependencies(
                eventStoreSubscriptionManager,
                inboxes,
                commandBus,
                List.of()
        );

        commandHandler = new OrderCommandHandler(eventProcessorDeps, ordersRepository);
        commandHandler.start();

        // EventProcessorP3 reacts to ProductAdded and applies another event
        var eventProcessorP3 = new EventProcessorP3(eventProcessorDeps, ordersRepository);
        eventProcessorP3.start();

        var orderId = OrderId.random();
        var createCmd = new CreateOrderCommand(orderId, CustomerId.random());

        // First: Create the aggregate
        assertThatCode(() -> commandBus.send(createCmd))
                .as("Aggregate creation should not throw exception")
                .doesNotThrowAnyException();

        // When: Send command which adds a product to an existing order
        // This triggers P3 to react to ProductAdded and append OrderConfirmed event to the same aggregate
        var addProductCmd = new AddProductCommand(orderId, ProductId.of("PRODUCT_A"));
        
        // Then: Should NOT throw exception
        assertThatCode(() -> commandBus.send(addProductCmd))
                .as("Event processor should successfully modify the existing aggregate without OptimisticAppendToStreamException")
                .doesNotThrowAnyException();

        // Verify events were added successfully
        var stream = unitOfWorkFactory.withUnitOfWork(() -> eventStore.fetchStream(ORDERS, orderId));

        assertThat(stream).isPresent();
        assertThat(stream.get().eventList())
                .as("Should have OrderCreated + ProductAdded + OrderConfirmed events")
                .hasSize(3)
                .satisfies(events -> {
                    assertThat((Object) events.get(0).event().deserialize()).isInstanceOf(TestOrderEvent.OrderCreated.class);
                    assertThat((Object) events.get(1).event().deserialize()).isInstanceOf(TestOrderEvent.ProductAdded.class);
                    assertThat((Object) events.get(2).event().deserialize()).isInstanceOf(TestOrderEvent.OrderConfirmed.class);
                });

        // Verify aggregate state
        var order = unitOfWorkFactory.withUnitOfWork(() -> ordersRepository.load(orderId));
        assertThat(order.products).hasSize(1).contains(ProductId.of("PRODUCT_A"));
        assertThat(order.confirmed).isTrue();

        eventProcessorP3.stop();
    }

   


    // ========================================================================================================
    // TEST AGGREGATE
    // ========================================================================================================

    public static class TestOrder extends AggregateRoot<OrderId, TestOrderEvent, TestOrder> {
        public Set<ProductId> products;
        public boolean        confirmed;

        public TestOrder(OrderId orderId) {
            super(orderId);
        }

        public TestOrder(OrderId orderId, CustomerId customerId) {
            this(orderId);
            requireNonNull(customerId, "You must provide a customerId");
            apply(new TestOrderEvent.OrderCreated(orderId, customerId));
        }

        public void addProduct(ProductId productId) {
            requireNonNull(productId, "You must provide a productId");
            apply(new TestOrderEvent.ProductAdded(aggregateId(), productId));
        }

        public void confirm() {
            if (!confirmed) {
                apply(new TestOrderEvent.OrderConfirmed(aggregateId()));
            }
        }

        @EventHandler
        private void on(TestOrderEvent.OrderCreated e) {
            products = new HashSet<>();
            confirmed = false;
        }

        @EventHandler
        private void on(TestOrderEvent.ProductAdded e) {
            products.add(e.productId);
        }

        @EventHandler
        private void on(TestOrderEvent.OrderConfirmed e) {
            confirmed = true;
        }
    }

    // ========================================================================================================
    // TEST EVENTS
    // ========================================================================================================

    public static class TestOrderEvent {
        public final OrderId orderId;

        public TestOrderEvent(OrderId orderId) {
            this.orderId = requireNonNull(orderId);
        }

        public static class OrderCreated extends TestOrderEvent {
            public final CustomerId customerId;

            public OrderCreated(OrderId orderId, CustomerId customerId) {
                super(orderId);
                this.customerId = customerId;
            }
        }

        public static class ProductAdded extends TestOrderEvent {
            public final ProductId productId;

            public ProductAdded(OrderId orderId, ProductId productId) {
                super(orderId);
                this.productId = productId;
            }
        }

        public static class OrderConfirmed extends TestOrderEvent {
            public OrderConfirmed(OrderId orderId) {
                super(orderId);
            }
        }
    }

    // ========================================================================================================
    // TEST COMMANDS
    // ========================================================================================================

    private record CreateOrderCommand(OrderId orderId, CustomerId customerId) {
    }

    private record AddProductCommand(OrderId orderId, ProductId productId) {
    }

    // ========================================================================================================
    // VIEW PROJECTION
    // ========================================================================================================

    public static class OrderView {
        public OrderId orderId;
        public int     productCount;
        public boolean confirmed;
    }

    // ========================================================================================================
    // SCENARIO 1: CHAINED EVENT PROCESSOR
    // ========================================================================================================

    /**
     * Single processor that handles command and reacts to all events in a chain.
     * <p>
     * <b>Handler Chain:</b>
     * <ol>
     *   <li><b>CommandHandler:</b> handle(CreateOrderCommand) → creates aggregate → persists OrderCreated</li>
     *   <li><b>P1:</b> on(OrderCreated) → loads aggregate → applies ProductAdded</li>
     *   <li><b>P2:</b> onProductAdded(ProductAdded) → updates OrderView in database</li>
     *   <li><b>P3:</b> onProductAdded(ProductAdded) → loads aggregate → applies OrderConfirmed</li>
     *   <li><b>P4:</b> on(OrderConfirmed) → conditionally throws exception OR updates view</li>
     * </ol>
     * <p>
     * When {@code shouldFailOnConfirmed=true}, P4 throws an exception that should:
     * <ul>
     *   <li>Propagate to the command caller</li>
     *   <li>Rollback all events (OrderCreated, ProductAdded, OrderConfirmed)</li>
     *   <li>Rollback all view updates in the order_views table</li>
     * </ul>
     */
    private static class ChainedEventProcessor extends InTransactionEventProcessor {
        private final StatefulAggregateRepository<OrderId, TestOrderEvent, TestOrder> repository;
        private final OrderViewRepository                                             viewRepository;
        private final AtomicBoolean                                                   shouldFailOnConfirmed;

        protected ChainedEventProcessor(EventProcessorDependencies eventProcessorDependencies,
                                        StatefulAggregateRepository<OrderId, TestOrderEvent, TestOrder> repository,
                                        OrderViewRepository viewRepository,
                                        AtomicBoolean shouldFailOnConfirmed) {
            super(eventProcessorDependencies, false);
            this.repository = repository;
            this.viewRepository = viewRepository;
            this.shouldFailOnConfirmed = shouldFailOnConfirmed;
        }

        @Override
        public String getProcessorName() {
            return "ChainedEventProcessor";
        }

        @Override
        protected List<AggregateType> reactsToEventsRelatedToAggregateTypes() {
            return List.of(ORDERS);
        }

        @CmdHandler
        void handle(CreateOrderCommand cmd) {
            log.debug("@CmdHandler: Creating order {}", cmd.orderId);
            repository.save(new TestOrder(cmd.orderId, cmd.customerId));
        }

        // P1: React to OrderCreated -> add product
        @MessageHandler
        void on(TestOrderEvent.OrderCreated event) {
            log.debug("P1: Reacting to OrderCreated {} -> adding product", event.orderId);
            var order = repository.load(event.orderId);
            order.addProduct(ProductId.of("AUTO_PRODUCT"));
        }

        // P2 & P3: React to ProductAdded -> update view AND confirm order
        @MessageHandler
        void onProductAdded(TestOrderEvent.ProductAdded event) {
            log.debug("P2: Reacting to ProductAdded {} -> updating view", event.orderId);
            var view = viewRepository.findById(event.orderId).orElse(new OrderView());
            view.orderId = event.orderId;
            view.productCount++;
            viewRepository.save(view);

            log.debug("P3: Reacting to ProductAdded {} -> confirming order", event.orderId);
            var order = repository.load(event.orderId);
            order.confirm();
        }

        // P4: React to OrderConfirmed -> throw exception (configurable)
        @MessageHandler
        void on(TestOrderEvent.OrderConfirmed event) {
            log.debug("P4: Reacting to OrderConfirmed {} -> shouldFail={}", event.orderId, shouldFailOnConfirmed.get());
            if (shouldFailOnConfirmed.get()) {
                throw new RuntimeException("Intentional failure in P4");
            }
            var view = viewRepository.findById(event.orderId);
            if (view.isPresent()) {
                var v = view.get();
                v.confirmed = true;
                viewRepository.save(v);
            }
        }
    }

    // ========================================================================================================
    // SCENARIO 2: CONCURRENT EVENT PROCESSORS
    // ========================================================================================================

    /**
     * Command handler for Scenario 2 - creates the aggregate and persists OrderCreated event.
     * <p>
     * This triggers both EventProcessorP1 and EventProcessorP2 to react concurrently.
     */
    private static class OrderCommandHandler extends InTransactionEventProcessor {
        private final StatefulAggregateRepository<OrderId, TestOrderEvent, TestOrder> repository;

        protected OrderCommandHandler(EventProcessorDependencies eventProcessorDependencies,
                                      StatefulAggregateRepository<OrderId, TestOrderEvent, TestOrder> repository) {
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
            log.debug("@CmdHandler: Creating order {} (for concurrent test)", cmd.orderId);
            repository.save(new TestOrder(cmd.orderId, cmd.customerId));
        }

        @CmdHandler
        void handle(AddProductCommand cmd) {
            log.debug("@CmdHandler: Adding product {} to order {}", cmd.productId, cmd.orderId);
            var order = repository.load(cmd.orderId);
            order.addProduct(cmd.productId);
        }
    }

    /**
     * Event processor P1 - reacts to OrderCreated and adds PRODUCT_A.
     * <p>
     * Executes together with EventProcessorP2 within the same transaction.
     * <p>
     * <b>Without identity map:</b> Loads aggregate at eventOrder=0, adds PRODUCT_A → eventOrder=1<br>
     * <b>With identity map:</b> Loads aggregate at eventOrder=0, adds PRODUCT_A → eventOrder=1
     */
    private static class EventProcessorP1 extends InTransactionEventProcessor {
        private final StatefulAggregateRepository<OrderId, TestOrderEvent, TestOrder> repository;

        protected EventProcessorP1(EventProcessorDependencies eventProcessorDependencies,
                                   StatefulAggregateRepository<OrderId, TestOrderEvent, TestOrder> repository) {
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
            log.debug("P1: Reacting to OrderCreated {} -> adding PRODUCT_A", event.orderId);
            var order = repository.load(event.orderId);
            order.addProduct(ProductId.of("PRODUCT_A"));
        }
    }

    /**
     * Event processor P2 - reacts to OrderCreated and adds PRODUCT_B.
     * <p>
     * Executes together with EventProcessorP1 within the same transaction.
     * <p>
     * <b>Without identity map (BUG):</b> Loads aggregate at eventOrder=0 (STALE!), adds PRODUCT_B → tries eventOrder=1 → OptimisticAppendToStreamException<br>
     * <b>With identity map (FIXED):</b> Loads aggregate from cache at eventOrder=1 (FRESH!), adds PRODUCT_B → eventOrder=2 ✓
     */
    private static class EventProcessorP2 extends InTransactionEventProcessor {
        private final StatefulAggregateRepository<OrderId, TestOrderEvent, TestOrder> repository;

        protected EventProcessorP2(EventProcessorDependencies eventProcessorDependencies,
                                   StatefulAggregateRepository<OrderId, TestOrderEvent, TestOrder> repository) {
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
        void on(TestOrderEvent.OrderCreated event) {
            log.debug("P2: Reacting to OrderCreated {} -> adding PRODUCT_B", event.orderId);
            var order = repository.load(event.orderId);
            order.addProduct(ProductId.of("PRODUCT_B"));
        }
    }

    /**
     * Event processor P3 - reacts to ProductAdded and confirms the order.
     * <p>
     * Used in Scenario 3 to test chained event processing on an existing aggregate.
     * <p>
     * This processor loads the aggregate and applies OrderConfirmed event when a product is added.
     */
    private static class EventProcessorP3 extends InTransactionEventProcessor {
        private final StatefulAggregateRepository<OrderId, TestOrderEvent, TestOrder> repository;

        protected EventProcessorP3(EventProcessorDependencies eventProcessorDependencies,
                                   StatefulAggregateRepository<OrderId, TestOrderEvent, TestOrder> repository) {
            super(eventProcessorDependencies, false);
            this.repository = repository;
        }

        @Override
        public String getProcessorName() {
            return "EventProcessorP3";
        }

        @Override
        protected List<AggregateType> reactsToEventsRelatedToAggregateTypes() {
            return List.of(ORDERS);
        }

        @MessageHandler
        void on(TestOrderEvent.ProductAdded event) {
            log.debug("P3: Reacting to ProductAdded {} -> confirming order", event.orderId);
            var order = repository.load(event.orderId);
            order.confirm();
        }
    }

    // ========================================================================================================
    // HELPER CLASSES
    // ========================================================================================================

    /**
     * Database-backed Order View Repository that participates in UnitOfWork/transactions.
     */
    public static class OrderViewRepository {
        private final EventStoreManagedUnitOfWorkFactory unitOfWorkFactory;

        public OrderViewRepository(EventStoreManagedUnitOfWorkFactory unitOfWorkFactory) {
            this.unitOfWorkFactory = unitOfWorkFactory;
            // Create order view table
            unitOfWorkFactory.usingUnitOfWork(uow -> {
                uow.handle().execute("CREATE TABLE IF NOT EXISTS order_views (" +
                                             "order_id TEXT PRIMARY KEY, " +
                                             "product_count INT NOT NULL, " +
                                             "confirmed BOOLEAN NOT NULL)");
            });
        }

        public Optional<OrderView> findById(OrderId orderId) {
            // Execute within the current UnitOfWork to participate in the transaction
            return unitOfWorkFactory.getRequiredUnitOfWork().handle().select(
                                            "SELECT order_id, product_count, confirmed FROM order_views WHERE order_id = ?",
                                            orderId.toString())
                                    .map((rs, ctx) -> {
                                        var view = new OrderView();
                                        view.orderId = OrderId.of(rs.getString("order_id"));
                                        view.productCount = rs.getInt("product_count");
                                        view.confirmed = rs.getBoolean("confirmed");
                                        return view;
                                    })
                                    .findFirst();
        }

        public void save(OrderView view) {
            // Execute within the current UnitOfWork to participate in the transaction
            var handle = unitOfWorkFactory.getRequiredUnitOfWork().handle();

            int updated = handle.createUpdate("UPDATE order_views SET product_count = :productCount, confirmed = :confirmed WHERE order_id = :orderId")
                                .bind("orderId", view.orderId.toString())
                                .bind("productCount", view.productCount)
                                .bind("confirmed", view.confirmed)
                                .execute();

            if (updated == 0) {
                // Insert if not exists
                handle.createUpdate("INSERT INTO order_views (order_id, product_count, confirmed) VALUES (:orderId, :productCount, :confirmed)")
                      .bind("orderId", view.orderId.toString())
                      .bind("productCount", view.productCount)
                      .bind("confirmed", view.confirmed)
                      .execute();
            }
        }
    }

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
