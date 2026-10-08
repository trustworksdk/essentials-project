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
import dk.trustworks.essentials.reactive.command.*;
import org.junit.jupiter.api.*;
import org.slf4j.LoggerFactory;

import java.util.*;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.mock;

/**
 * CB-3: two {@link EventStreamDeciderCommandHandlerAdapter}s handling one command type used to surface only at the
 * first send, as a {@link MultipleCommandHandlersFoundException} listing two {@code EventStreamDeciderCommandHandlerAdapter@hash}
 * entries. The configurator now warns at construction, and the adapter names its aggregate type and deciders.
 */
class EventStreamDeciderDuplicateCommandRegistrationTest {
    private ConfigurableEventStore<?>             eventStore;
    private CommandBus                            commandBus;
    private EventStreamAggregateTypeConfiguration orderConfiguration;
    private ListAppender<ILoggingEvent>           logAppender;
    private ch.qos.logback.classic.Logger         logger;

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
    }

    @AfterEach
    void tearDown() {
        logger.detachAppender(logAppender);
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

    private List<String> warnings() {
        return logAppender.list.stream()
                               .filter(event -> event.getLevel() == Level.WARN)
                               .map(ILoggingEvent::getFormattedMessage)
                               .toList();
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
}
