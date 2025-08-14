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

package dk.trustworks.essentials.components.eventsourced.aggregates.eventstream;

import com.fasterxml.jackson.databind.ObjectMapper;
import dk.trustworks.essentials.components.eventsourced.aggregates.*;
import dk.trustworks.essentials.components.eventsourced.aggregates.eventstream.adapters.EventStreamDeciderCommandHandlerAdapter;
import dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.PostgresqlEventStore;
import dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.eventstream.*;
import dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.persistence.table_per_aggregate_type.*;
import dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.serializer.AggregateIdSerializer;
import dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.serializer.json.JacksonJSONEventSerializer;
import dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.transaction.*;
import dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.types.GlobalEventOrder;
import dk.trustworks.essentials.components.foundation.postgresql.SqlExecutionTimeLogger;
import dk.trustworks.essentials.components.foundation.transaction.*;
import dk.trustworks.essentials.components.foundation.types.RandomIdGenerator;
import dk.trustworks.essentials.reactive.command.*;
import dk.trustworks.essentials.types.CharSequenceType;
import org.jdbi.v3.core.Jdbi;
import org.jdbi.v3.postgres.PostgresPlugin;
import org.junit.jupiter.api.*;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.*;
import reactor.core.Disposable;

import java.time.*;
import java.util.*;
import java.util.concurrent.ConcurrentLinkedQueue;

import static dk.trustworks.essentials.jackson.immutable.EssentialsImmutableJacksonModule.createObjectMapper;
import static dk.trustworks.essentials.shared.FailFast.requireNonNull;
import static org.assertj.core.api.Assertions.*;

/**
 * Comprehensive integration test for {@link EventStreamDecider} implementations using TestContainers and PostgreSQL EventStore.
 * <p>
 * This test demonstrates the complete integration of {@link EventStreamDecider} with:
 * <ul>
 *   <li>PostgreSQL EventStore with real database</li>
 *   <li>TestContainers for database setup</li>
 *   <li>Command handling infrastructure</li>
 *   <li>Event persistence and loading</li>
 *   <li>Transaction management</li>
 *   <li>Event streaming and subscriptions</li>
 * </ul>
 *
 * <p>
 * The test uses a realistic order management domain to showcase various scenarios
 * including command processing, event persistence, idempotent behavior, and error handling.
 */
@Testcontainers
class EventStreamDeciderIT {

    // ========== Test Infrastructure ==========

    public static final AggregateType ORDERS = AggregateType.of("Orders");

    @Container
    private final PostgreSQLContainer<?> postgreSQLContainer = new PostgreSQLContainer<>("postgres:latest")
            .withDatabaseName("eventstream-decider-test")
            .withUsername("test-user")
            .withPassword("secret-password");

    private Jdbi                                                                    jdbi;
    private EventStoreUnitOfWorkFactory<EventStoreUnitOfWork>                       unitOfWorkFactory;
    private TestPersistableEventMapper                                              eventMapper;
    private PostgresqlEventStore<SeparateTablePerAggregateEventStreamConfiguration> eventStore;
    private ObjectMapper                                                            objectMapper;
    private CommandBus                                                              commandBus;

    // Event collection for assertions
    private RecordingLocalEventBusConsumer recordingLocalEventBusConsumer;
    private Disposable                     persistedEventFlux;
    private Queue<PersistedEvent>          receivedEvents;

    // Domain components
    private EventStreamDeciderCommandHandlerAdapter orderCommandHandlerAdapter;
    private List<EventStreamDecider<?, ?>>          deciders;
    private EventStreamAggregateTypeConfiguration   orderConfiguration;

    // ========== Domain Types ==========

    /**
     * Value object representing an order identifier.
     */
    public static class OrderId extends CharSequenceType<OrderId> {
        protected OrderId(CharSequence value) {
            super(value);
        }

        public static OrderId random() {
            return new OrderId(RandomIdGenerator.generate());
        }

        public static OrderId of(CharSequence id) {
            return new OrderId(id);
        }
    }

    /**
     * Value object representing a customer identifier.
     */
    public static class CustomerId extends CharSequenceType<CustomerId> {
        protected CustomerId(CharSequence value) {
            super(value);
        }

        public static CustomerId random() {
            return new CustomerId(RandomIdGenerator.generate());
        }

        public static CustomerId of(CharSequence id) {
            return new CustomerId(id);
        }
    }

    /**
     * Enumeration of possible order statuses.
     */
    public enum OrderStatus {
        PENDING, CONFIRMED, SHIPPED, DELIVERED, CANCELLED
    }

    // ========== Commands ==========

    /**
     * Base interface for order commands.
     */
    public interface OrderCommand {
        OrderId orderId();
    }

    /**
     * Command to create a new order.
     */
    public record CreateOrder(OrderId orderId, CustomerId customerId) implements OrderCommand {
        public CreateOrder {
            requireNonNull(orderId, "orderId cannot be null");
            requireNonNull(customerId, "customerId cannot be null");
        }
    }

    /**
     * Command to confirm an order.
     */
    public record ConfirmOrder(OrderId orderId) implements OrderCommand {
        public ConfirmOrder {
            requireNonNull(orderId, "orderId cannot be null");
        }
    }

    /**
     * Command to ship an order.
     */
    public record ShipOrder(OrderId orderId) implements OrderCommand {
        public ShipOrder {
            requireNonNull(orderId, "orderId cannot be null");
        }
    }

    /**
     * Command to cancel an order.
     */
    public record CancelOrder(OrderId orderId, String reason) implements OrderCommand {
        public CancelOrder {
            requireNonNull(orderId, "orderId cannot be null");
            requireNonNull(reason, "reason cannot be null");
        }
    }

    // ========== Events ==========

    /**
     * Base interface for order events.
     */
    public interface OrderEvent {
        OrderId orderId();
    }

    /**
     * Event indicating an order was created.
     */
    public record OrderCreated(OrderId orderId, CustomerId customerId) implements OrderEvent {
        public OrderCreated {
            requireNonNull(orderId, "orderId cannot be null");
            requireNonNull(customerId, "customerId cannot be null");
        }
    }

    /**
     * Event indicating an order was confirmed.
     */
    public record OrderConfirmed(OrderId orderId) implements OrderEvent {
        public OrderConfirmed {
            requireNonNull(orderId, "orderId cannot be null");
        }
    }

    /**
     * Event indicating an order was shipped.
     */
    public record OrderShipped(OrderId orderId) implements OrderEvent {
        public OrderShipped {
            requireNonNull(orderId, "orderId cannot be null");
        }
    }

    /**
     * Event indicating an order was cancelled.
     */
    public record OrderCancelled(OrderId orderId, String reason) implements OrderEvent {
        public OrderCancelled {
            requireNonNull(orderId, "orderId cannot be null");
            requireNonNull(reason, "reason cannot be null");
        }
    }

    // ========== State ==========

    /**
     * Immutable state representation of an order - should optimally not be shared between deciders to keep coupling low
     */
    public record OrderState(
            OrderId orderId,
            CustomerId customerId,
            OrderStatus status,
            Instant createdAt,
            Instant confirmedAt,
            Instant shippedAt,
            Instant cancelledAt,
            String cancellationReason
    ) {

        public static OrderState created(OrderId orderId, CustomerId customerId) {
            return new OrderState(orderId, customerId, OrderStatus.PENDING, null, null, null, null, null);
        }

        public OrderState withConfirmed() {
            return new OrderState(orderId, customerId, OrderStatus.CONFIRMED, createdAt, null, shippedAt, cancelledAt, cancellationReason);
        }

        public OrderState withShipped() {
            return new OrderState(orderId, customerId, OrderStatus.SHIPPED, createdAt, confirmedAt, null, cancelledAt, cancellationReason);
        }

        public OrderState withCancelled(String reason) {
            return new OrderState(orderId, customerId, OrderStatus.CANCELLED, createdAt, confirmedAt, shippedAt, null, reason);
        }

        public boolean canBeConfirmed() {
            return status == OrderStatus.PENDING;
        }

        public boolean canBeShipped() {
            return status == OrderStatus.CONFIRMED;
        }

        public boolean canBeCancelled() {
            return status == OrderStatus.PENDING || status == OrderStatus.CONFIRMED;
        }
    }

    // ========== Deciders ==========

    /**
     * Decider for creating orders.
     */
    public static class CreateOrderDecider implements EventStreamDecider<CreateOrder, OrderEvent> {

        @Override
        public Optional<OrderEvent> handle(CreateOrder command, List<OrderEvent> events) {
            requireNonNull(command, "command cannot be null");
            requireNonNull(events, "events cannot be null");

            // Check if order already exists
            boolean orderExists = events.stream()
                                        .anyMatch(event -> event instanceof OrderCreated);

            if (orderExists) {
                // Idempotent behavior - order already created
                return Optional.empty();
            }

            // Create new order
            return Optional.of(new OrderCreated(command.orderId(), command.customerId()));
        }

        @Override
        public boolean canHandle(Class<?> command) {
            return CreateOrder.class == command;
        }
    }

    /**
     * Decider for confirming orders.
     */
    public static class ConfirmOrderDecider implements EventStreamDecider<ConfirmOrder, OrderEvent> {

        private final OrderEvolver evolver = new OrderEvolver();

        @Override
        public Optional<OrderEvent> handle(ConfirmOrder command, List<OrderEvent> events) {
            requireNonNull(command, "command cannot be null");
            requireNonNull(events, "events cannot be null");

            // Rebuild current state from events
            var currentState = EventStreamEvolver.applyEvents(evolver, events);

            if (currentState.isEmpty()) {
                throw new IllegalStateException("Cannot confirm order that does not exist");
            }

            var state = currentState.get();

            if (!state.canBeConfirmed()) {
                if (state.status() == OrderStatus.CONFIRMED) {
                    // Already confirmed - idempotent behavior
                    return Optional.empty();
                }
                throw new IllegalStateException("Cannot confirm order in status: " + state.status());
            }

            return Optional.of(new OrderConfirmed(command.orderId()));
        }

        @Override
        public boolean canHandle(Class<?> command) {
            return ConfirmOrder.class == command;
        }
    }

    /**
     * Decider for shipping orders.
     */
    public static class ShipOrderDecider implements EventStreamDecider<ShipOrder, OrderEvent> {

        private final OrderEvolver evolver = new OrderEvolver();

        @Override
        public Optional<OrderEvent> handle(ShipOrder command, List<OrderEvent> events) {
            requireNonNull(command, "command cannot be null");
            requireNonNull(events, "events cannot be null");

            var currentState = EventStreamEvolver.applyEvents(evolver, events);

            if (currentState.isEmpty()) {
                throw new IllegalStateException("Cannot ship order that does not exist");
            }

            var state = currentState.get();

            if (!state.canBeShipped()) {
                if (state.status() == OrderStatus.SHIPPED) {
                    // Already shipped - idempotent behavior
                    return Optional.empty();
                }
                throw new IllegalStateException("Cannot ship order in status: " + state.status());
            }

            return Optional.of(new OrderShipped(command.orderId()));
        }

        @Override
        public boolean canHandle(Class<?> command) {
            return ShipOrder.class == command;
        }
    }

    /**
     * Decider for cancelling orders.
     */
    public static class CancelOrderDecider implements EventStreamDecider<CancelOrder, OrderEvent> {

        private final OrderEvolver evolver = new OrderEvolver();

        @Override
        public Optional<OrderEvent> handle(CancelOrder command, List<OrderEvent> events) {
            requireNonNull(command, "command cannot be null");
            requireNonNull(events, "events cannot be null");

            var currentState = EventStreamEvolver.applyEvents(evolver, events);

            if (currentState.isEmpty()) {
                throw new IllegalStateException("Cannot cancel order that does not exist");
            }

            var state = currentState.get();

            if (!state.canBeCancelled()) {
                if (state.status() == OrderStatus.CANCELLED) {
                    // Already cancelled - idempotent behavior
                    return Optional.empty();
                }
                throw new IllegalStateException("Cannot cancel order in status: " + state.status());
            }

            return Optional.of(new OrderCancelled(command.orderId(), command.reason()));
        }

        @Override
        public boolean canHandle(Class<?> command) {
            return CancelOrder.class == command;
        }
    }

    // ========== Evolver ==========

    /**
     * Evolver for order state.
     */
    public static class OrderEvolver implements EventStreamEvolver<OrderEvent, OrderState> {

        @Override
        public Optional<OrderState> applyEvent(OrderEvent event, Optional<OrderState> currentState) {
            requireNonNull(event, "event cannot be null");
            requireNonNull(currentState, "currentState cannot be null");

            if (event instanceof OrderCreated created) {
                return Optional.of(
                        OrderState.created(created.orderId(), created.customerId())
                                  );
            } else if (event instanceof OrderConfirmed confirmed) {
                return currentState.map(OrderState::withConfirmed);
            } else if (event instanceof OrderShipped shipped) {
                return currentState.map(OrderState::withShipped);
            } else if (event instanceof OrderCancelled cancelled) {
                return currentState.map(state ->
                                                state.withCancelled(cancelled.reason())
                                       );
            } else {
                return currentState; // Unknown event types are ignored
            }
        }
    }

    // ========== Test Setup ==========

    @BeforeEach
    void setup() {
        // Setup database connection
        jdbi = Jdbi.create(postgreSQLContainer.getJdbcUrl(),
                           postgreSQLContainer.getUsername(),
                           postgreSQLContainer.getPassword());
        jdbi.installPlugin(new PostgresPlugin());
        jdbi.setSqlLogger(new SqlExecutionTimeLogger());

        // Setup unit of work factory
        unitOfWorkFactory = new EventStoreManagedUnitOfWorkFactory(jdbi);

        // Setup serialization
        objectMapper = createObjectMapper();
        eventMapper = new TestPersistableEventMapper();

        // Setup event store
        eventStore = new PostgresqlEventStore<>(unitOfWorkFactory,
                                                new SeparateTablePerAggregateTypePersistenceStrategy(jdbi,
                                                                                                     unitOfWorkFactory,
                                                                                                     eventMapper,
                                                                                                     SeparateTablePerAggregateTypeEventStreamConfigurationFactory.standardSingleTenantConfiguration(new JacksonJSONEventSerializer(objectMapper),
                                                                                                                                                                                                    IdentifierColumnType.TEXT,
                                                                                                                                                                                                    JSONColumnType.JSONB)));
        eventStore.addAggregateEventStreamConfiguration(ORDERS, OrderId.class);

        // Setup event recording
        recordingLocalEventBusConsumer = new RecordingLocalEventBusConsumer();
        eventStore.localEventBus().addSyncSubscriber(recordingLocalEventBusConsumer);

        receivedEvents = new ConcurrentLinkedQueue<>();
        persistedEventFlux = eventStore.pollEvents(ORDERS,
                                                   GlobalEventOrder.FIRST_GLOBAL_EVENT_ORDER,
                                                   Optional.empty(),
                                                   Optional.of(Duration.ofMillis(100)),
                                                   Optional.empty(),
                                                   Optional.empty(),
                                                   Optional.empty())
                                       .subscribe(receivedEvents::add);

        // Setup command infrastructure
        commandBus = new LocalCommandBus();

        // Setup deciders
        deciders = List.of(
                new CreateOrderDecider(),
                new ConfirmOrderDecider(),
                new ShipOrderDecider(),
                new CancelOrderDecider()
                          );

        // Setup aggregate configuration
        orderConfiguration = new EventStreamAggregateTypeConfiguration(
                ORDERS,
                OrderId.class,
                AggregateIdSerializer.serializerFor(OrderId.class),
                new EventStreamDeciderSupportsAggregateTypeChecker.HandlesCommandsThatInheritFromCommandType(OrderCommand.class),
                command -> ((OrderCommand) command).orderId(),
                event -> ((OrderEvent) event).orderId()
        );

        // Setup command handler adapter
        orderCommandHandlerAdapter = new EventStreamDeciderCommandHandlerAdapter(
                eventStore,
                orderConfiguration,
                deciders
        );

        // Register command handler with command bus
        commandBus.addCommandHandler(orderCommandHandlerAdapter);
    }

    @AfterEach
    void cleanup() {
        unitOfWorkFactory.getCurrentUnitOfWork().ifPresent(UnitOfWork::rollback);
        assertThat(unitOfWorkFactory.getCurrentUnitOfWork()).isEmpty();

        if (persistedEventFlux != null) {
            persistedEventFlux.dispose();
        }
    }

    // ========== Integration Tests ==========

    @Test
    void shouldCreateOrderSuccessfully() {
        // Given
        var orderId    = OrderId.random();
        var customerId = CustomerId.random();
        var command    = new CreateOrder(orderId, customerId);

        // When
        unitOfWorkFactory.usingUnitOfWork(unitOfWork -> {
            var result = commandBus.send(command);

            // Then
            assertThat(result).isInstanceOf(OrderCreated.class);
            var event = (OrderCreated) result;
            assertThat((CharSequence) event.orderId()).isEqualTo(orderId);
            assertThat((CharSequence) event.customerId()).isEqualTo(customerId);
        });

        // Verify event was persisted
        unitOfWorkFactory.usingUnitOfWork(unitOfWork -> {
            var eventStream = eventStore.fetchStream(ORDERS, orderId);
            assertThat(eventStream).isPresent();
            assertThat(eventStream.get().eventList()).hasSize(1);
            assertThat(eventStream.get().eventList().get(0).event().deserialize(OrderEvent.class)).isInstanceOf(OrderCreated.class);
        });
    }

    @Test
    void shouldBeIdempotentWhenCreatingExistingOrder() {
        // Given
        var orderId    = OrderId.random();
        var customerId = CustomerId.random();
        var command    = new CreateOrder(orderId, customerId);

        // When - First creation
        unitOfWorkFactory.usingUnitOfWork(unitOfWork -> {
            commandBus.send(command);
        });

        // When - Second creation (idempotent)
        unitOfWorkFactory.usingUnitOfWork(unitOfWork -> {
            var result = commandBus.send(command);

            // Then
            assertThat(result).isNull(); // No new event produced
        });

        // Verify only one event was persisted
        unitOfWorkFactory.usingUnitOfWork(unitOfWork -> {
            var eventStream = eventStore.fetchStream(ORDERS, orderId);
            assertThat(eventStream).isPresent();
            assertThat(eventStream.get().eventList()).hasSize(1);
        });
    }

    @Test
    void shouldConfirmOrderAfterCreation() {
        // Given
        var orderId    = OrderId.random();
        var customerId = CustomerId.random();

        // Create order first
        unitOfWorkFactory.usingUnitOfWork(unitOfWork -> {
            commandBus.send(new CreateOrder(orderId, customerId));
        });

        // When - Confirm order
        unitOfWorkFactory.usingUnitOfWork(unitOfWork -> {
            var result = commandBus.send(new ConfirmOrder(orderId));

            // Then
            assertThat(result).isInstanceOf(OrderConfirmed.class);
            var event = (OrderConfirmed) result;
            assertThat((CharSequence) event.orderId()).isEqualTo(orderId);
        });

        // Verify both events were persisted
        unitOfWorkFactory.usingUnitOfWork(unitOfWork -> {
            var eventStream = eventStore.fetchStream(ORDERS, orderId);
            assertThat(eventStream).isPresent();
            assertThat(eventStream.get().eventList()).hasSize(2);
            assertThat(eventStream.get().eventList().get(0).event().deserialize(OrderEvent.class)).isInstanceOf(OrderCreated.class);
            assertThat(eventStream.get().eventList().get(1).event().deserialize(OrderEvent.class)).isInstanceOf(OrderConfirmed.class);
        });
    }

    @Test
    void shouldFailToConfirmNonExistentOrder() {
        // Given
        var orderId = OrderId.random();

        // When / Then
        assertThatThrownBy(() -> {
            unitOfWorkFactory.usingUnitOfWork(unitOfWork -> {
                commandBus.send(new ConfirmOrder(orderId));
            });
        }).isInstanceOf(UnitOfWorkException.class)
          .hasCauseInstanceOf(IllegalStateException.class)
          .hasMessageContaining("Cannot confirm order that does not exist");
    }

    @Test
    void shouldShipConfirmedOrder() {
        // Given
        var orderId    = OrderId.random();
        var customerId = CustomerId.random();

        // Create and confirm order first
        unitOfWorkFactory.usingUnitOfWork(unitOfWork -> {
            commandBus.send(new CreateOrder(orderId, customerId));
            commandBus.send(new ConfirmOrder(orderId));
        });

        // When - Ship order
        unitOfWorkFactory.usingUnitOfWork(unitOfWork -> {
            var result = commandBus.send(new ShipOrder(orderId));

            // Then
            assertThat(result).isInstanceOf(OrderShipped.class);
            var event = (OrderShipped) result;
            assertThat((CharSequence) event.orderId()).isEqualTo(orderId);
        });

        // Verify all events were persisted
        unitOfWorkFactory.usingUnitOfWork(unitOfWork -> {
            var eventStream = eventStore.fetchStream(ORDERS, orderId);
            assertThat(eventStream).isPresent();
            assertThat(eventStream.get().eventList()).hasSize(3);
            assertThat(eventStream.get().eventList().get(0).event().deserialize(OrderEvent.class)).isInstanceOf(OrderCreated.class);
            assertThat(eventStream.get().eventList().get(1).event().deserialize(OrderEvent.class)).isInstanceOf(OrderConfirmed.class);
            assertThat(eventStream.get().eventList().get(2).event().deserialize(OrderEvent.class)).isInstanceOf(OrderShipped.class);
        });
    }

    @Test
    void shouldFailToShipPendingOrder() {
        // Given
        var orderId    = OrderId.random();
        var customerId = CustomerId.random();

        // Create order (but don't confirm)
        unitOfWorkFactory.usingUnitOfWork(unitOfWork -> {
            commandBus.send(new CreateOrder(orderId, customerId));
        });

        // When / Then
        assertThatThrownBy(() -> {
            unitOfWorkFactory.usingUnitOfWork(unitOfWork -> {
                commandBus.send(new ShipOrder(orderId));
            });
        }).isInstanceOf(UnitOfWorkException.class)
          .hasCauseInstanceOf(IllegalStateException.class)
          .hasMessageContaining("Cannot ship order in status: PENDING");
    }

    @Test
    void shouldCancelPendingOrder() {
        // Given
        var orderId    = OrderId.random();
        var customerId = CustomerId.random();
        var reason     = "Customer requested cancellation";

        // Create order first
        unitOfWorkFactory.usingUnitOfWork(unitOfWork -> {
            commandBus.send(new CreateOrder(orderId, customerId));
        });

        // When - Cancel order
        unitOfWorkFactory.usingUnitOfWork(unitOfWork -> {
            var result = commandBus.send(new CancelOrder(orderId, reason));

            // Then
            assertThat(result).isInstanceOf(OrderCancelled.class);
            var event = (OrderCancelled) result;
            assertThat((CharSequence) event.orderId()).isEqualTo(orderId);
            assertThat(event.reason()).isEqualTo(reason);
        });

        // Verify events were persisted
        unitOfWorkFactory.usingUnitOfWork(unitOfWork -> {
            var eventStream = eventStore.fetchStream(ORDERS, orderId);
            assertThat(eventStream).isPresent();
            assertThat(eventStream.get().eventList()).hasSize(2);
            assertThat(eventStream.get().eventList().get(0).event().deserialize(OrderEvent.class)).isInstanceOf(OrderCreated.class);
            assertThat(eventStream.get().eventList().get(1).event().deserialize(OrderEvent.class)).isInstanceOf(OrderCancelled.class);
        });
    }

    @Test
    void shouldCancelConfirmedOrder() {
        // Given
        var orderId    = OrderId.random();
        var customerId = CustomerId.random();
        var reason     = "Out of stock";

        // Create and confirm order first
        unitOfWorkFactory.usingUnitOfWork(unitOfWork -> {
            commandBus.send(new CreateOrder(orderId, customerId));
            commandBus.send(new ConfirmOrder(orderId));
        });

        // When - Cancel order
        unitOfWorkFactory.usingUnitOfWork(unitOfWork -> {
            var result = commandBus.send(new CancelOrder(orderId, reason));

            // Then
            assertThat(result).isInstanceOf(OrderCancelled.class);
            var event = (OrderCancelled) result;
            assertThat((CharSequence) event.orderId()).isEqualTo(orderId);
            assertThat(event.reason()).isEqualTo(reason);
        });
    }

    @Test
    void shouldFailToCancelShippedOrder() {
        // Given
        var orderId    = OrderId.random();
        var customerId = CustomerId.random();

        // Create, confirm, and ship order
        unitOfWorkFactory.usingUnitOfWork(unitOfWork -> {
            commandBus.send(new CreateOrder(orderId, customerId));
            commandBus.send(new ConfirmOrder(orderId));
            commandBus.send(new ShipOrder(orderId));
        });

        // When / Then
        assertThatThrownBy(() -> {
            unitOfWorkFactory.usingUnitOfWork(unitOfWork -> {
                commandBus.send(new CancelOrder(orderId, "Too late"));
            });
        }).isInstanceOf(UnitOfWorkException.class)
          .hasCauseInstanceOf(IllegalStateException.class)
          .hasMessageContaining("Cannot cancel order in status: SHIPPED");
    }

    @Test
    void shouldReconstructStateFromEventStream() {
        // Given
        var orderId    = OrderId.random();
        var customerId = CustomerId.random();
        var evolver    = new OrderEvolver();

        // Create a complete order lifecycle
        unitOfWorkFactory.usingUnitOfWork(unitOfWork -> {
            commandBus.send(new CreateOrder(orderId, customerId));
            commandBus.send(new ConfirmOrder(orderId));
            commandBus.send(new ShipOrder(orderId));
        });

        // When - Reconstruct state from events
        unitOfWorkFactory.usingUnitOfWork(unitOfWork -> {
            var eventStream = eventStore.fetchStream(ORDERS, orderId);
            assertThat(eventStream).isPresent();
            var events = eventStream.get().eventList().stream()
                                    .map(PersistedEvent::event)
                                    .map(eventJSON -> eventJSON.deserialize(OrderEvent.class))
                                    .toList();

            var finalState = EventStreamEvolver.applyEvents(evolver, events);

            // Then
            assertThat(finalState).isPresent();
            var state = finalState.get();
            assertThat((CharSequence) state.orderId()).isEqualTo(orderId);
            assertThat((CharSequence) state.customerId()).isEqualTo(customerId);
            assertThat(state.status()).isEqualTo(OrderStatus.SHIPPED);
        });
    }

    @Test
    void shouldHandleMultipleOrdersConcurrently() {
        // Given
        var order1Id   = OrderId.random();
        var order2Id   = OrderId.random();
        var customerId = CustomerId.random();

        // When - Process multiple orders
        unitOfWorkFactory.usingUnitOfWork(unitOfWork -> {
            // Create both orders
            commandBus.send(new CreateOrder(order1Id, customerId));
            commandBus.send(new CreateOrder(order2Id, customerId));

            // Process them differently
            commandBus.send(new ConfirmOrder(order1Id));
            commandBus.send(new CancelOrder(order2Id, "Changed mind"));

            commandBus.send(new ShipOrder(order1Id));
        });

        // Then - Verify both order streams
        unitOfWorkFactory.usingUnitOfWork(unitOfWork -> {
            var order1Stream = eventStore.fetchStream(ORDERS, order1Id);
            assertThat(order1Stream).isPresent();
            assertThat(order1Stream.get().eventList()).hasSize(3);
            assertThat(order1Stream.get().eventList().get(0).event().deserialize(OrderEvent.class)).isInstanceOf(OrderCreated.class);
            assertThat(order1Stream.get().eventList().get(1).event().deserialize(OrderEvent.class)).isInstanceOf(OrderConfirmed.class);
            assertThat(order1Stream.get().eventList().get(2).event().deserialize(OrderEvent.class)).isInstanceOf(OrderShipped.class);

            var order2Stream = eventStore.fetchStream(ORDERS, order2Id);
            assertThat(order2Stream).isPresent();
            assertThat(order2Stream.get().eventList()).hasSize(2);
            assertThat(order2Stream.get().eventList().get(0).event().deserialize(OrderEvent.class)).isInstanceOf(OrderCreated.class);
            assertThat(order2Stream.get().eventList().get(1).event().deserialize(OrderEvent.class)).isInstanceOf(OrderCancelled.class);
        });
    }
}
