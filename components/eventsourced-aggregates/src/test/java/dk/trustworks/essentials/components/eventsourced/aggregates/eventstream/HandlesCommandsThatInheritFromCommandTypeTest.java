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

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import dk.trustworks.essentials.components.eventsourced.aggregates.eventstream.EventStreamDeciderSupportsAggregateTypeChecker.HandlesCommandsThatInheritFromCommandType;
import org.junit.jupiter.api.*;
import org.slf4j.LoggerFactory;

import java.util.*;

import static org.assertj.core.api.Assertions.*;

/**
 * {@link HandlesCommandsThatInheritFromCommandType} used to read only the decider class's own directly implemented,
 * parameterized interfaces: a decider declared through a sub-interface or a generic base class failed with an
 * {@link IllegalStateException}, and one declared through a sub-interface that reorders the type arguments was matched
 * against the wrong one.
 */
class HandlesCommandsThatInheritFromCommandTypeTest {
    private final HandlesCommandsThatInheritFromCommandType orderChecker = new HandlesCommandsThatInheritFromCommandType(OrderCommand.class);

    private ListAppender<ILoggingEvent>   logAppender;
    private ch.qos.logback.classic.Logger logger;

    @BeforeEach
    void setUp() {
        logger = (ch.qos.logback.classic.Logger) LoggerFactory.getLogger(HandlesCommandsThatInheritFromCommandType.class);
        logAppender = new ListAppender<>();
        logAppender.start();
        logger.addAppender(logAppender);
    }

    @AfterEach
    void tearDown() {
        logger.detachAppender(logAppender);
    }

    @Test
    void a_decider_implementing_EventStreamDecider_directly_is_supported() {
        assertThat(orderChecker.supports(new DirectCreateOrderDecider())).isTrue();
    }

    @Test
    void a_decider_implementing_EventStreamDecider_through_a_sub_interface_is_supported() {
        assertThat(orderChecker.supports(new SubInterfaceCreateOrderDecider())).isTrue();
    }

    @Test
    void a_decider_extending_a_generic_abstract_base_class_is_supported() {
        assertThat(orderChecker.supports(new BaseClassCreateOrderDecider())).isTrue();
    }

    @Test
    void a_decider_whose_sub_interface_takes_only_the_event_type_is_matched_on_its_command_type() {
        // OrderEventDecider<E> extends EventStreamDecider<CreateOrder, E>: the sub-interface's first type argument is the event type
        assertThat(orderChecker.supports(new EventTypeOnlySubInterfaceDecider())).isTrue();
        assertThat(new HandlesCommandsThatInheritFromCommandType(OrderEvent.class).supports(new EventTypeOnlySubInterfaceDecider())).isFalse();
    }

    @Test
    void a_decider_of_another_command_type_is_not_supported() {
        assertThat(new HandlesCommandsThatInheritFromCommandType(ShipmentCommand.class).supports(new SubInterfaceCreateOrderDecider())).isFalse();
        assertThat(new HandlesCommandsThatInheritFromCommandType(ShipmentCommand.class).supports(new BaseClassCreateOrderDecider())).isFalse();
    }

    @Test
    void a_decider_whose_command_type_cannot_be_resolved_is_not_supported_and_warned_about() {
        assertThat(orderChecker.supports(new RawTypeDecider())).isFalse();
        assertThat(orderChecker.supports(new UnboundGenericDecider<CreateOrder>())).isFalse();
        assertThat(logAppender.list).filteredOn(event -> event.getLevel() == Level.WARN)
                                    .hasSize(2)
                                    .allSatisfy(event -> assertThat(event.getFormattedMessage()).contains("Couldn't resolve the command type of EventStreamDecider"));
    }

    @Test
    void a_null_decider_is_rejected() {
        assertThatThrownBy(() -> orderChecker.supports(null)).isInstanceOf(IllegalArgumentException.class);
    }

    // ------------------------------------------------------------------------------------------------------------------

    public interface OrderCommand {
    }

    public record CreateOrder(String orderId) implements OrderCommand {
    }

    public interface ShipmentCommand {
    }

    public interface OrderEvent {
    }

    public static class DirectCreateOrderDecider implements EventStreamDecider<CreateOrder, OrderEvent> {
        @Override
        public Optional<OrderEvent> handle(CreateOrder command, List<OrderEvent> events) {
            return Optional.empty();
        }

        @Override
        public boolean canHandle(Class<?> command) {
            return CreateOrder.class.isAssignableFrom(command);
        }
    }

    public interface OrderDecider extends EventStreamDecider<CreateOrder, OrderEvent> {
    }

    public static class SubInterfaceCreateOrderDecider implements OrderDecider {
        @Override
        public Optional<OrderEvent> handle(CreateOrder command, List<OrderEvent> events) {
            return Optional.empty();
        }

        @Override
        public boolean canHandle(Class<?> command) {
            return CreateOrder.class.isAssignableFrom(command);
        }
    }

    public abstract static class GenericDecider<C, E> implements EventStreamDecider<C, E> {
    }

    public static class BaseClassCreateOrderDecider extends GenericDecider<CreateOrder, OrderEvent> {
        @Override
        public Optional<OrderEvent> handle(CreateOrder command, List<OrderEvent> events) {
            return Optional.empty();
        }

        @Override
        public boolean canHandle(Class<?> command) {
            return CreateOrder.class.isAssignableFrom(command);
        }
    }

    public interface OrderEventDecider<E> extends EventStreamDecider<CreateOrder, E> {
    }

    public static class EventTypeOnlySubInterfaceDecider implements OrderEventDecider<OrderEvent> {
        @Override
        public Optional<OrderEvent> handle(CreateOrder command, List<OrderEvent> events) {
            return Optional.empty();
        }

        @Override
        public boolean canHandle(Class<?> command) {
            return CreateOrder.class.isAssignableFrom(command);
        }
    }

    @SuppressWarnings("rawtypes")
    public static class RawTypeDecider implements EventStreamDecider {
        @Override
        public Optional handle(Object command, List events) {
            return Optional.empty();
        }

        @Override
        public boolean canHandle(Class command) {
            return true;
        }
    }

    public static class UnboundGenericDecider<C> implements EventStreamDecider<C, OrderEvent> {
        @Override
        public Optional<OrderEvent> handle(C command, List<OrderEvent> events) {
            return Optional.empty();
        }

        @Override
        public boolean canHandle(Class<?> command) {
            return true;
        }
    }
}
