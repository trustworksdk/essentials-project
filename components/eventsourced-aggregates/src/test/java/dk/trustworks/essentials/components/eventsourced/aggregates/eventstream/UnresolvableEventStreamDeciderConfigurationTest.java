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
import dk.trustworks.essentials.components.eventsourced.aggregates.eventstream.HandlesCommandsThatInheritFromCommandTypeTest.*;
import dk.trustworks.essentials.components.eventsourced.aggregates.eventstream.adapters.EventStreamDeciderAndAggregateTypeConfigurator;
import dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.ConfigurableEventStore;
import dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.eventstream.AggregateType;
import dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.serializer.AggregateIdSerializer;
import dk.trustworks.essentials.reactive.command.CommandBus;
import org.junit.jupiter.api.*;
import org.slf4j.LoggerFactory;

import java.util.*;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.mock;

/**
 * {@link HandlesCommandsThatInheritFromCommandType#supports(EventStreamDecider)} used to WARN about a decider whose command
 * type it could not resolve, and it is asked once per aggregate type and per check - so one such decider produced a WARN for
 * every aggregate type configured. It now logs at DEBUG, and the configuration's exception reports the decider once, with the
 * guidance the WARN carried.
 */
class UnresolvableEventStreamDeciderConfigurationTest {
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
    void a_decider_whose_command_type_cannot_be_resolved_fails_the_configuration_once_with_guidance_and_no_WARN() {
        var orders = configuration("Orders", OrderCommand.class);
        var shipments = configuration("Shipments", ShipmentCommand.class);

        assertThatThrownBy(() -> new EventStreamDeciderAndAggregateTypeConfigurator(mock(ConfigurableEventStore.class),
                                                                                    mock(CommandBus.class),
                                                                                    List.of(orders, shipments),
                                                                                    List.of(new RawTypeDecider())))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Couldn't resolve which AggregateType that EventStreamDecider '" + RawTypeDecider.class.getName() + "' works with")
                .hasMessageContaining("COMMAND type argument couldn't be resolved")
                .hasMessageContaining("not a raw type, an unbound type variable or a JDK dynamic proxy");

        assertThat(logAppender.list).filteredOn(event -> event.getLevel().isGreaterOrEqual(Level.WARN)).isEmpty();
    }

    @Test
    void a_resolvable_decider_no_checker_claims_fails_the_configuration_without_the_unresolvable_command_type_guidance() {
        var shipments = configuration("Shipments", ShipmentCommand.class);

        assertThatThrownBy(() -> new EventStreamDeciderAndAggregateTypeConfigurator(mock(ConfigurableEventStore.class),
                                                                                    mock(CommandBus.class),
                                                                                    List.of(shipments),
                                                                                    List.of(new DirectCreateOrderDecider())))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Couldn't resolve which AggregateType that EventStreamDecider '" + DirectCreateOrderDecider.class.getName() + "' works with")
                .hasMessageNotContaining("COMMAND type argument couldn't be resolved");
    }

    private static EventStreamAggregateTypeConfiguration configuration(String aggregateType, Class<?> commandType) {
        return new EventStreamAggregateTypeConfiguration(AggregateType.of(aggregateType),
                                                         String.class,
                                                         AggregateIdSerializer.serializerFor(String.class),
                                                         new HandlesCommandsThatInheritFromCommandType(commandType),
                                                         command -> "id",
                                                         event -> "id");
    }
}
