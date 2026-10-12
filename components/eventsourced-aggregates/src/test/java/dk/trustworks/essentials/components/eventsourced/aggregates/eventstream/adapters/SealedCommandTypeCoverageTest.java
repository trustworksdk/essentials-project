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
import dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.ConfigurableEventStore;
import dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.eventstream.AggregateType;
import dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.serializer.AggregateIdSerializer;
import dk.trustworks.essentials.components.foundation.startup.EssentialsStartupReport;
import dk.trustworks.essentials.reactive.command.*;
import org.junit.jupiter.api.*;
import org.slf4j.LoggerFactory;

import java.util.*;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.mock;

/**
 * CB-4: a command a sealed command type permits, but that no {@link EventStreamDecider} handles, used to surface only at
 * its first send, as a {@link NoCommandHandlerFoundException}. The configurator now walks the permitted subclasses at
 * construction and warns - counting every command handler the {@link CommandBus} lists, not only the deciders.
 */
class SealedCommandTypeCoverageTest {
    private ConfigurableEventStore<?>             eventStore;
    private CommandBus                            commandBus;
    private EventStreamAggregateTypeConfiguration shipmentConfiguration;
    private ListAppender<ILoggingEvent>           logAppender;
    private ch.qos.logback.classic.Logger         logger;

    @BeforeEach
    void setUp() {
        eventStore = mock(ConfigurableEventStore.class);
        commandBus = new LocalCommandBus();
        shipmentConfiguration = new EventStreamAggregateTypeConfiguration(AggregateType.of("Shipments"),
                                                                          String.class,
                                                                          AggregateIdSerializer.serializerFor(String.class),
                                                                          new EventStreamDeciderSupportsAggregateTypeChecker.HandlesCommandsThatInheritFromCommandType(ShipmentCommand.class),
                                                                          command -> ((ShipmentCommand) command).shipmentId(),
                                                                          event -> ((ShipmentEvent) event).shipmentId());

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
    void a_permitted_command_of_the_sealed_aggregate_command_type_that_no_decider_handles_is_warned_about_at_construction() {
        assertThatCode(() -> new EventStreamDeciderAndAggregateTypeConfigurator(eventStore, commandBus, List.of(shipmentConfiguration),
                                                                                List.of(new CreateShipmentDecider(), new DispatchShipmentDecider())))
                .doesNotThrowAnyException();

        assertThat(warnings()).singleElement().asString()
                              .contains("'" + ShipmentCommand.class.getName() + "'")
                              .contains("AggregateType 'Shipments'")
                              .contains("1 command type(s)")
                              .contains(CancelShipment.class.getName())
                              .doesNotContain(CreateShipment.class.getName())
                              .doesNotContain(DispatchShipment.class.getName())
                              .contains(NoCommandHandlerFoundException.class.getSimpleName());
    }

    @Test
    void the_warning_is_also_added_to_the_startup_report_bound_to_the_thread() {
        var report = new EssentialsStartupReport();
        try (var binding = report.bindToCurrentThread()) {
            new EventStreamDeciderAndAggregateTypeConfigurator(eventStore, commandBus, List.of(shipmentConfiguration),
                                                               List.of(new CreateShipmentDecider(), new DispatchShipmentDecider()));
        }

        assertThat(report.getFindings()).singleElement().satisfies(finding -> {
            assertThat(finding.severity()).isEqualTo(EssentialsStartupReport.Severity.WARN);
            assertThat(finding.source()).isEqualTo("sealed command type '" + ShipmentCommand.class.getName() + "'");
            assertThat(finding.message()).contains(CancelShipment.class.getName());
            assertThat(finding.fix()).contains("Add an EventStreamDecider");
        });
    }

    @Test
    void a_sealed_command_type_whose_permitted_commands_are_all_handled_is_not_warned_about() {
        new EventStreamDeciderAndAggregateTypeConfigurator(eventStore, commandBus, List.of(shipmentConfiguration),
                                                           List.of(new CreateShipmentDecider(), new DispatchShipmentDecider(), new CancelShipmentDecider()));

        assertThat(warnings()).isEmpty();
    }

    @Test
    void a_permitted_command_an_annotated_command_handler_on_the_command_bus_handles_is_not_warned_about() {
        commandBus.addCommandHandler(new CancelShipmentCommandHandler());

        new EventStreamDeciderAndAggregateTypeConfigurator(eventStore, commandBus, List.of(shipmentConfiguration),
                                                           List.of(new CreateShipmentDecider(), new DispatchShipmentDecider()));

        assertThat(warnings()).isEmpty();
    }

    @Test
    void a_decider_declared_on_the_sealed_command_type_that_handles_only_some_of_its_commands_is_warned_about() {
        new EventStreamDeciderAndAggregateTypeConfigurator(eventStore, commandBus, List.of(shipmentConfiguration),
                                                           List.of(new CreateAndDispatchShipmentDecider()));

        assertThat(warnings()).singleElement().asString()
                              .contains("'" + ShipmentCommand.class.getName() + "'")
                              .contains(CancelShipment.class.getName());
    }

    private List<String> warnings() {
        return logAppender.list.stream()
                               .filter(event -> event.getLevel() == Level.WARN)
                               .map(ILoggingEvent::getFormattedMessage)
                               .toList();
    }

    public sealed interface ShipmentCommand permits CreateShipment, DispatchShipment, CancelShipment {
        String shipmentId();
    }

    public record CreateShipment(String shipmentId) implements ShipmentCommand {
    }

    public record DispatchShipment(String shipmentId) implements ShipmentCommand {
    }

    public record CancelShipment(String shipmentId) implements ShipmentCommand {
    }

    public record ShipmentEvent(String shipmentId) {
    }

    public static class CreateShipmentDecider implements EventStreamDecider<CreateShipment, ShipmentEvent> {
        @Override
        public Optional<ShipmentEvent> handle(CreateShipment command, List<ShipmentEvent> events) {
            return Optional.of(new ShipmentEvent(command.shipmentId()));
        }

        @Override
        public boolean canHandle(Class<?> command) {
            return CreateShipment.class.isAssignableFrom(command);
        }
    }

    public static class DispatchShipmentDecider implements EventStreamDecider<DispatchShipment, ShipmentEvent> {
        @Override
        public Optional<ShipmentEvent> handle(DispatchShipment command, List<ShipmentEvent> events) {
            return Optional.of(new ShipmentEvent(command.shipmentId()));
        }

        @Override
        public boolean canHandle(Class<?> command) {
            return DispatchShipment.class.isAssignableFrom(command);
        }
    }

    public static class CancelShipmentDecider implements EventStreamDecider<CancelShipment, ShipmentEvent> {
        @Override
        public Optional<ShipmentEvent> handle(CancelShipment command, List<ShipmentEvent> events) {
            return Optional.of(new ShipmentEvent(command.shipmentId()));
        }

        @Override
        public boolean canHandle(Class<?> command) {
            return CancelShipment.class.isAssignableFrom(command);
        }
    }

    public static class CancelShipmentCommandHandler extends AnnotatedCommandHandler {
        @CmdHandler
        void handle(CancelShipment command) {
        }
    }

    /**
     * Declared on the sealed root, but handles only two of its three commands
     */
    public static class CreateAndDispatchShipmentDecider implements EventStreamDecider<ShipmentCommand, ShipmentEvent> {
        @Override
        public Optional<ShipmentEvent> handle(ShipmentCommand command, List<ShipmentEvent> events) {
            return Optional.of(new ShipmentEvent(command.shipmentId()));
        }

        @Override
        public boolean canHandle(Class<?> command) {
            return CreateShipment.class.isAssignableFrom(command) || DispatchShipment.class.isAssignableFrom(command);
        }
    }
}
