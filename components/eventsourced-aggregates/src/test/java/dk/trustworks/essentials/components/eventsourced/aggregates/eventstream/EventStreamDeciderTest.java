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

package dk.trustworks.essentials.components.eventsourced.aggregates.eventstream;

import dk.trustworks.essentials.components.eventsourced.aggregates.eventstream.test.GivenWhenThenScenario;
import dk.trustworks.essentials.components.foundation.types.RandomIdGenerator;
import dk.trustworks.essentials.types.CharSequenceType;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.*;

import static dk.trustworks.essentials.shared.FailFast.requireNonNull;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * Comprehensive test suite demonstrating {@link EventStreamDecider} implementation and usage.
 * <p>
 * This test class showcases the event stream decider pattern with a realistic
 * order management domain example, including:
 * <ul>
 *   <li>Command and event type definitions</li>
 *   <li>Multiple decider implementations</li>
 *   <li>State evolver implementation</li>
 *   <li>Given-When-Then testing scenarios</li>
 *   <li>Integration with event sourcing patterns</li>
 * </ul>
 */
class EventStreamDeciderTest {

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
     * Immutable state representation of an order.
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
            return CreateOrder.class.isAssignableFrom(command);
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
            return ConfirmOrder.class.isAssignableFrom(command);
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
            return ShipOrder.class.isAssignableFrom(command);
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
            return CancelOrder.class.isAssignableFrom(command);
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

    // ========== Tests ==========

    @Test
    void shouldCreateOrderWhenNoExistingOrder() {
        var scenario = new GivenWhenThenScenario<>(new CreateOrderDecider());

        var orderId    = OrderId.random();
        var customerId = CustomerId.random();

        scenario
                .given() // No existing events
                .when(new CreateOrder(orderId, customerId))
                .then(new OrderCreated(orderId, customerId));
    }

    @Test
    void shouldNotCreateOrderWhenOrderAlreadyExists() {
        var scenario = new GivenWhenThenScenario<>(new CreateOrderDecider());

        var orderId    = OrderId.random();
        var customerId = CustomerId.random();

        scenario
                .given(new OrderCreated(orderId, customerId))
                .when(new CreateOrder(orderId, customerId))
                .thenExpectNoEvent(); // Idempotent behavior
    }

    @Test
    void shouldConfirmOrderWhenOrderIsPending() {
        var scenario = new GivenWhenThenScenario<>(new ConfirmOrderDecider());

        var orderId    = OrderId.random();
        var customerId = CustomerId.random();

        scenario
                .given(new OrderCreated(orderId, customerId))
                .when(new ConfirmOrder(orderId))
                .then(new OrderConfirmed(orderId));
    }

    @Test
    void shouldNotConfirmOrderWhenOrderAlreadyConfirmed() {
        var scenario = new GivenWhenThenScenario<>(new ConfirmOrderDecider());

        var orderId    = OrderId.random();
        var customerId = CustomerId.random();

        scenario
                .given(
                        new OrderCreated(orderId, customerId),
                        new OrderConfirmed(orderId)
                      )
                .when(new ConfirmOrder(orderId))
                .thenExpectNoEvent(); // Idempotent behavior
    }

    @Test
    void shouldFailToConfirmOrderWhenOrderDoesNotExist() {
        var scenario = new GivenWhenThenScenario<>(new ConfirmOrderDecider());

        var orderId = OrderId.random();

        scenario
                .given() // No events
                .when(new ConfirmOrder(orderId))
                .thenFailsWithExceptionType(IllegalStateException.class, "Cannot confirm order that does not exist");
    }

    @Test
    void shouldShipOrderWhenOrderIsConfirmed() {
        var scenario = new GivenWhenThenScenario<>(new ShipOrderDecider());

        var orderId    = OrderId.random();
        var customerId = CustomerId.random();

        scenario
                .given(
                        new OrderCreated(orderId, customerId),
                        new OrderConfirmed(orderId)
                      )
                .when(new ShipOrder(orderId))
                .then(new OrderShipped(orderId));
    }

    @Test
    void shouldFailToShipOrderWhenOrderIsPending() {
        var scenario = new GivenWhenThenScenario<>(new ShipOrderDecider());

        var orderId    = OrderId.random();
        var customerId = CustomerId.random();

        scenario
                .given(new OrderCreated(orderId, customerId))
                .when(new ShipOrder(orderId))
                .thenFailsWithExceptionType(IllegalStateException.class, "Cannot ship order in status: PENDING");
    }

    @Test
    void shouldCancelOrderWhenOrderIsPending() {
        var scenario = new GivenWhenThenScenario<>(new CancelOrderDecider());

        var orderId    = OrderId.random();
        var customerId = CustomerId.random();
        var reason     = "Customer requested cancellation";

        scenario
                .given(new OrderCreated(orderId, customerId))
                .when(new CancelOrder(orderId, reason))
                .then(new OrderCancelled(orderId, reason));
    }

    @Test
    void shouldCancelOrderWhenOrderIsConfirmed() {
        var scenario = new GivenWhenThenScenario<>(new CancelOrderDecider());

        var orderId    = OrderId.random();
        var customerId = CustomerId.random();
        var reason     = "Out of stock";

        scenario
                .given(
                        new OrderCreated(orderId, customerId),
                        new OrderConfirmed(orderId)
                      )
                .when(new CancelOrder(orderId, reason))
                .then(new OrderCancelled(orderId, reason));
    }

    @Test
    void shouldFailToCancelOrderWhenOrderIsShipped() {
        var scenario = new GivenWhenThenScenario<>(new CancelOrderDecider());

        var orderId    = OrderId.random();
        var customerId = CustomerId.random();

        scenario
                .given(
                        new OrderCreated(orderId, customerId),
                        new OrderConfirmed(orderId),
                        new OrderShipped(orderId)
                      )
                .when(new CancelOrder(orderId, "Too late"))
                .thenFailsWithExceptionType(IllegalStateException.class, "Cannot cancel order in status: SHIPPED");
    }

    @Test
    void shouldTestEventStreamEvolver() {
        var evolver    = new OrderEvolver();
        var orderId    = OrderId.random();
        var customerId = CustomerId.random();

        // To avoid Java choosing intersection type "List<Record & OrderEvent>"
        List<OrderEvent> events = List.of(
                new OrderCreated(orderId, customerId),
                new OrderConfirmed(orderId),
                new OrderShipped(orderId)
                                         );

        var finalState = EventStreamEvolver.applyEvents(evolver, events);

        assertThat(finalState).isPresent();
        assertThat((CharSequence) finalState.get().orderId()).isEqualTo(orderId);
        assertThat((CharSequence) finalState.get().customerId()).isEqualTo(customerId);
        assertThat(finalState.get().status()).isEqualTo(OrderStatus.SHIPPED);
    }

    @Test
    void shouldTestCanHandleMethod() {
        var createOrderDecider  = new CreateOrderDecider();
        var confirmOrderDecider = new ConfirmOrderDecider();

        assertThat(createOrderDecider.canHandle(CreateOrder.class)).isTrue();
        assertThat(createOrderDecider.canHandle(ConfirmOrder.class)).isFalse();
        assertThat(createOrderDecider.canHandle(String.class)).isFalse();

        assertThat(confirmOrderDecider.canHandle(CreateOrder.class)).isFalse();
        assertThat(confirmOrderDecider.canHandle(ConfirmOrder.class)).isTrue();
        assertThat(confirmOrderDecider.canHandle(String.class)).isFalse();
    }
}
