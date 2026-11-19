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

package dk.trustworks.essentials.components.eventsourced.aggregates.eventstream.adapters;

import dk.trustworks.essentials.components.eventsourced.aggregates.eventstream.*;
import dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.ConfigurableEventStore;
import dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.eventstream.AggregateType;
import dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.serializer.AggregateIdSerializer;
import dk.trustworks.essentials.components.foundation.types.RandomIdGenerator;
import dk.trustworks.essentials.reactive.command.CommandBus;
import dk.trustworks.essentials.types.CharSequenceType;
import org.junit.jupiter.api.*;
import org.mockito.*;

import java.util.*;

import static dk.trustworks.essentials.shared.FailFast.requireNonNull;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.verify;

/**
 * Test class demonstrating the usage and behavior of {@link EventStreamDeciderAndAggregateTypeConfigurator}.
 * <p>
 * This test serves as both a unit test and a usage example, showing how the configurator
 * integrates deciders with the command handling infrastructure.
 */
class EventStreamDeciderAndAggregateTypeConfiguratorTest {

    @Mock
    private ConfigurableEventStore<?> eventStore;

    @Mock
    private CommandBus commandBus;

    // ========== Domain Types ==========

    /**
     * Value object representing an order identifier.
     */
    public static class OrderId extends CharSequenceType<OrderId> {
        public OrderId(CharSequence value) {
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
        public CustomerId(CharSequence value) {
            super(value);
        }

        public static CustomerId random() {
            return new CustomerId(RandomIdGenerator.generate());
        }

        public static CustomerId of(CharSequence id) {
            return new CustomerId(id);
        }
    }

    // Commands
    public interface OrderCommand {
        OrderId orderId();
    }

    public record CreateOrder(OrderId orderId, CustomerId customerId) implements OrderCommand {
        public CreateOrder {
            requireNonNull(orderId, "orderId cannot be null");
            requireNonNull(customerId, "customerId cannot be null");
        }
    }

    public record ConfirmOrder(OrderId orderId) implements OrderCommand {
        public ConfirmOrder {
            requireNonNull(orderId, "orderId cannot be null");
        }
    }

    // Events
    public interface OrderEvent {
        OrderId orderId();
    }

    public record OrderCreated(OrderId orderId, CustomerId customerId) implements OrderEvent {
    }

    public record OrderConfirmed(OrderId orderId) implements OrderEvent {
    }

    // Deciders
    public static class CreateOrderDecider implements EventStreamDecider<CreateOrder, OrderEvent> {
        @Override
        public Optional<OrderEvent> handle(CreateOrder command, List<OrderEvent> events) {
            boolean orderExists = events.stream()
                                        .anyMatch(event -> event instanceof OrderCreated);

            return orderExists ? Optional.empty()
                               : Optional.of(new OrderCreated(command.orderId(), command.customerId()));
        }

        @Override
        public boolean canHandle(Class<?> command) {
            return CreateOrder.class.isAssignableFrom(command);
        }
    }

    public static class ConfirmOrderDecider implements EventStreamDecider<ConfirmOrder, OrderEvent> {
        @Override
        public Optional<OrderEvent> handle(ConfirmOrder command, List<OrderEvent> events) {
            boolean orderExists = events.stream()
                                        .anyMatch(event -> event instanceof OrderCreated);
            boolean orderConfirmed = events.stream()
                                           .anyMatch(event -> event instanceof OrderConfirmed);

            if (!orderExists) {
                throw new IllegalStateException("Cannot confirm order that does not exist");
            }

            return orderConfirmed ? Optional.empty()
                                  : Optional.of(new OrderConfirmed(command.orderId()));
        }

        @Override
        public boolean canHandle(Class<?> command) {
            return ConfirmOrder.class.isAssignableFrom(command);
        }
    }

    private EventStreamAggregateTypeConfiguration orderConfiguration;
    private List<EventStreamDecider<?, ?>>        deciders;

    @BeforeEach
    void setUp() {
        MockitoAnnotations.openMocks(this);

        // Configure the order aggregate type
        orderConfiguration = new EventStreamAggregateTypeConfiguration(
                AggregateType.of("Orders"),
                OrderId.class,
                AggregateIdSerializer.serializerFor(OrderId.class),
                new EventStreamDeciderSupportsAggregateTypeChecker.HandlesCommandsThatInheritFromCommandType(OrderCommand.class),
                command -> ((OrderCommand) command).orderId(),
                event -> ((OrderEvent) event).orderId()
        );

        // Create deciders
        deciders = List.of(
                new CreateOrderDecider(),
                new ConfirmOrderDecider()
                          );
    }

    @Test
    void shouldConfigureDecidersAndAggregateTypesSuccessfully() {
        // When
        var configurator = new EventStreamDeciderAndAggregateTypeConfigurator(
                eventStore,
                commandBus,
                List.of(orderConfiguration),
                deciders
        );

        // Then
        // Verify aggregate type configuration was registered with event store
        verify(eventStore).addAggregateEventStreamConfiguration(
                eq(orderConfiguration.aggregateType()),
                eq(orderConfiguration.aggregateIdSerializer())
                                                               );

        // Verify command handler adapter was registered with command bus
        verify(commandBus).addCommandHandler(any(EventStreamDeciderCommandHandlerAdapter.class));

        // Verify configurator state
        var createdAdapters = configurator.getCreatedAdapters();
        assert createdAdapters.size() == 1;

        var adapter = createdAdapters.get(0);
        assert adapter.getConfiguration().equals(orderConfiguration);
        assert adapter.getDeciders().size() == 2;
    }

    @Test
    void shouldFailWhenDeciderCannotBeMatchedToAggregateType() {
        // Given - a decider that doesn't match any aggregate type
        var unmatchedDecider = new EventStreamDecider<Object, Object>() {
            @Override
            public Optional<Object> handle(Object command, List<Object> events) {
                return Optional.empty();
            }

            @Override
            public boolean canHandle(Class<?> command) {
                return false; // Never matches
            }
        };

        // When/Then
        try {
            new EventStreamDeciderAndAggregateTypeConfigurator(
                    eventStore,
                    commandBus,
                    List.of(orderConfiguration),
                    List.of(unmatchedDecider)
            );
            assert false : "Expected IllegalStateException";
        } catch (IllegalStateException e) {
            assert e.getMessage().contains("Couldn't resolve which AggregateType");
        }
    }

    @Test
    void shouldProvideAccessToConfiguredComponents() {
        // When
        var configurator = new EventStreamDeciderAndAggregateTypeConfigurator(
                eventStore,
                commandBus,
                List.of(orderConfiguration),
                deciders
        );

        // Then
        assert configurator.getEventStore() == eventStore;
        assert configurator.getCommandBus() == commandBus;
        assert configurator.getAggregateTypeConfigurations().size() == 1;
        assert configurator.getAggregateTypeConfigurations().get(0) == orderConfiguration;
        assert configurator.getDeciders().size() == 2;
        assert configurator.getCreatedAdapters().size() == 1;
    }
}
