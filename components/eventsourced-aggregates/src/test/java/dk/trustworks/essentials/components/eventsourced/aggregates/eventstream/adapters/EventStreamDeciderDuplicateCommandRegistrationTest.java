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

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import dk.trustworks.essentials.components.eventsourced.aggregates.eventstream.*;
import dk.trustworks.essentials.components.eventsourced.aggregates.eventstream.adapters.EventStreamDeciderAndAggregateTypeConfiguratorTest.*;
import dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.ConfigurableEventStore;
import dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.eventstream.AggregateType;
import dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.serializer.AggregateIdSerializer;
import dk.trustworks.essentials.components.foundation.startup.EssentialsRunReport;
import dk.trustworks.essentials.reactive.command.*;
import org.junit.jupiter.api.*;
import org.slf4j.LoggerFactory;

import java.util.*;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.mock;

/**
 * CB-3: two {@link EventStreamDeciderCommandHandlerAdapter}s handling one command type used to surface only at the
 * first send, as a {@link MultipleCommandHandlersFoundException} listing two {@code EventStreamDeciderCommandHandlerAdapter@hash}
 * entries. The {@link CommandBus} now warns as the adapter is added, the configurator adds it to the
 * {@link EssentialsRunReport}, and the adapter names its aggregate type and deciders. Two deciders inside one adapter
 * are invisible to the {@link CommandBus}; the configurator warns about those itself.
 */
class EventStreamDeciderDuplicateCommandRegistrationTest {
    private ConfigurableEventStore<?>             eventStore;
    private CommandBus                            commandBus;
    private EventStreamAggregateTypeConfiguration orderConfiguration;
    private ListAppender<ILoggingEvent>           logAppender;
    private ch.qos.logback.classic.Logger         logger;
    private ch.qos.logback.classic.Logger         busLogger;

    @BeforeEach
    void setUp() {
        eventStore = mock(ConfigurableEventStore.class);
        commandBus = new LocalCommandBus();
        orderConfiguration = new EventStreamAggregateTypeConfiguration(AggregateType.of("Orders"),
                                                                       OrderId.class,
                                                                       AggregateIdSerializer.serializerFor(OrderId.class),
                                                                       new EventStreamDeciderSupportsAggregateTypeChecker.HandlesCommandsThatInheritFromCommandType(OrderCommand.class),
                                                                       command -> ((OrderCommand) command).orderId(),
                                                                       event -> ((OrderEvent) event).orderId());

        logger = (ch.qos.logback.classic.Logger) LoggerFactory.getLogger(EventStreamDeciderAndAggregateTypeConfigurator.class);
        logAppender = new ListAppender<>();
        logAppender.start();
        logger.addAppender(logAppender);
        // The CommandBus WARNs about command types handled by more than one command handler, the configurator about two deciders in one adapter
        busLogger = (ch.qos.logback.classic.Logger) LoggerFactory.getLogger(LocalCommandBus.class);
        busLogger.addAppender(logAppender);
    }

    @AfterEach
    void tearDown() {
        logger.detachAppender(logAppender);
        busLogger.detachAppender(logAppender);
    }

    @Test
    void the_adapter_names_its_aggregate_type_and_deciders() {
        var adapter = new EventStreamDeciderCommandHandlerAdapter(eventStore, orderConfiguration, List.of(new CreateOrderDecider(), new ConfirmOrderDecider()));

        assertThat(adapter.toString()).isEqualTo("EventStreamDeciderCommandHandlerAdapter{aggregateType=Orders, deciders=[" +
                                                         CreateOrderDecider.class.getName() + ", " + ConfirmOrderDecider.class.getName() + "]}");
    }

    @Test
    void a_second_configurator_registering_an_already_handled_command_type_is_warned_about_at_construction() {
        new EventStreamDeciderAndAggregateTypeConfigurator(eventStore, commandBus, List.of(orderConfiguration), List.of(new CreateOrderDecider(), new ConfirmOrderDecider()));
        assertThat(warnings()).isEmpty();

        // Warns rather than fails: an overlap that is never sent works today, and a patch release must not reject it
        assertThatCode(() -> new EventStreamDeciderAndAggregateTypeConfigurator(eventStore, commandBus, List.of(orderConfiguration), List.of(new CreateOrderDecider())))
                .doesNotThrowAnyException();

        assertThat(warnings()).singleElement().asString()
                              .contains("'" + CreateOrder.class.getName() + "'")
                              .contains("2 command handlers")
                              .contains("aggregateType=Orders")
                              .contains(CreateOrderDecider.class.getName())
                              .contains(MultipleCommandHandlersFoundException.class.getSimpleName());

        // The send still fails, now naming what is registered twice
        assertThatThrownBy(() -> commandBus.send(new CreateOrder(OrderId.of("o-1"), CustomerId.of("c-1"))))
                .isInstanceOf(MultipleCommandHandlersFoundException.class)
                .hasMessageContaining("EventStreamDeciderCommandHandlerAdapter{aggregateType=Orders, deciders=[" + CreateOrderDecider.class.getName() + "]}");
    }

    @Test
    void a_command_type_handled_by_more_than_one_command_handler_is_added_to_the_startup_report_bound_to_the_thread() {
        new EventStreamDeciderAndAggregateTypeConfigurator(eventStore, commandBus, List.of(orderConfiguration), List.of(new CreateOrderDecider()));

        var report = new EssentialsRunReport();
        try (var binding = report.bindToCurrentThread()) {
            new EventStreamDeciderAndAggregateTypeConfigurator(eventStore, commandBus, List.of(orderConfiguration), List.of(new CreateOrderDecider()));
        }

        assertThat(report.getFindings()).singleElement().satisfies(finding -> {
            assertThat(finding.severity()).isEqualTo(EssentialsRunReport.Severity.WARN);
            assertThat(finding.source()).isEqualTo("command type '" + CreateOrder.class.getName() + "'");
            assertThat(finding.message()).contains("2 command handlers").contains(MultipleCommandHandlersFoundException.class.getSimpleName());
            assertThat(finding.fix()).contains("exactly one command handler");
        });
        // Logged once - by the CommandBus, not again by the configurator
        assertThat(warnings()).singleElement();
    }

    @Test
    void a_decider_for_a_command_type_an_annotated_command_handler_already_handles_is_warned_about() {
        var annotatedCommandHandler = new CreateOrderCommandHandler();
        commandBus.addCommandHandler(annotatedCommandHandler);

        var report = new EssentialsRunReport();
        try (var binding = report.bindToCurrentThread()) {
            new EventStreamDeciderAndAggregateTypeConfigurator(eventStore, commandBus, List.of(orderConfiguration), List.of(new CreateOrderDecider(), new ConfirmOrderDecider()));
        }

        assertThat(warnings()).singleElement().asString()
                              .contains("'" + CreateOrder.class.getName() + "'")
                              .contains("2 command handlers")
                              .contains("CreateOrderCommandHandler::AnnotatedCommandHandler")
                              .contains("aggregateType=Orders");
        assertThat(report.getFindings()).singleElement()
                                        .satisfies(finding -> assertThat(finding.source()).isEqualTo("command type '" + CreateOrder.class.getName() + "'"));
    }

    @Test
    void configurators_with_disjoint_command_types_are_not_warned_about() {
        new EventStreamDeciderAndAggregateTypeConfigurator(eventStore, commandBus, List.of(orderConfiguration), List.of(new CreateOrderDecider()));
        new EventStreamDeciderAndAggregateTypeConfigurator(eventStore, commandBus, List.of(orderConfiguration), List.of(new ConfirmOrderDecider()));

        assertThat(warnings()).isEmpty();
    }

    @Test
    void an_adapter_removed_from_the_command_bus_no_longer_counts() {
        var first = new EventStreamDeciderAndAggregateTypeConfigurator(eventStore, commandBus, List.of(orderConfiguration), List.of(new CreateOrderDecider()));
        first.getCreatedAdapters().forEach(commandBus::removeCommandHandler);

        new EventStreamDeciderAndAggregateTypeConfigurator(eventStore, commandBus, List.of(orderConfiguration), List.of(new CreateOrderDecider()));

        assertThat(warnings()).isEmpty();
    }

    @Test
    void two_deciders_for_one_command_type_in_one_configurator_are_warned_about() {
        new EventStreamDeciderAndAggregateTypeConfigurator(eventStore, commandBus, List.of(orderConfiguration), List.of(new CreateOrderDecider(), new AnotherCreateOrderDecider()));

        assertThat(warnings()).singleElement().asString()
                              .contains("'" + CreateOrder.class.getName() + "'")
                              .contains(CreateOrderDecider.class.getName())
                              .contains(AnotherCreateOrderDecider.class.getName());
    }

    @Test
    void deciders_declared_through_a_sub_interface_or_a_generic_abstract_base_class_are_checked_too() {
        assertThat(EventStreamDeciderCommandTypes.resolveCommandTypeArgument(SubInterfaceCreateOrderDecider.class)).contains(CreateOrder.class);
        assertThat(EventStreamDeciderCommandTypes.resolveCommandTypeArgument(BaseClassCreateOrderDecider.class)).contains(CreateOrder.class);

        new EventStreamDeciderAndAggregateTypeConfigurator(eventStore, commandBus, List.of(orderConfiguration), List.of(new SubInterfaceCreateOrderDecider(), new BaseClassCreateOrderDecider()));

        assertThat(warnings()).singleElement().asString()
                              .contains("'" + CreateOrder.class.getName() + "'")
                              .contains(SubInterfaceCreateOrderDecider.class.getName())
                              .contains(BaseClassCreateOrderDecider.class.getName());
    }

    private List<String> warnings() {
        return logAppender.list.stream()
                               .filter(event -> event.getLevel() == Level.WARN)
                               .map(ILoggingEvent::getFormattedMessage)
                               .toList();
    }

    public static class CreateOrderCommandHandler extends AnnotatedCommandHandler {
        @CmdHandler
        void handle(CreateOrder command) {
        }
    }

    public static class AnotherCreateOrderDecider implements EventStreamDecider<CreateOrder, OrderEvent> {
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
}
