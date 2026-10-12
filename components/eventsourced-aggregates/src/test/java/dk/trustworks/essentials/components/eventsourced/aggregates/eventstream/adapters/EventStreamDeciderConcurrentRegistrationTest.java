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
import dk.trustworks.essentials.components.foundation.startup.EssentialsStartupReport;
import dk.trustworks.essentials.reactive.command.*;
import org.junit.jupiter.api.*;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

/**
 * Review F-004: configurators registering on one {@link CommandBus} from several threads used to share a static per-bus
 * adapter list, whose unsynchronized use made the duplicate-registration diagnostic fail - and its WARN go missing. The
 * configurator now reads the handlers the {@link CommandBus} lists, and {@link LocalCommandBus} registers concurrently
 * without losing a handler (its own concurrency is pinned by {@code LocalCommandBusConcurrentRegistrationTest} in
 * {@code reactive}). This pins the configurator's side: every concurrent registration is seen by a later configurator,
 * and no diagnostic fails along the way.
 */
class EventStreamDeciderConcurrentRegistrationTest {
    private static final Duration AT_MOST = Duration.ofSeconds(10);

    private ConfigurableEventStore<?>     eventStore;
    private ListAppender<ILoggingEvent>   logAppender;
    private ch.qos.logback.classic.Logger logger;

    @BeforeEach
    void setUp() {
        eventStore = mock(ConfigurableEventStore.class);
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
    @Timeout(60)
    void configurators_registering_concurrently_on_one_command_bus_are_all_seen_by_a_later_configurator() throws Exception {
        var commandBus     = new LocalCommandBus();
        var configurators  = 8;
        var start          = new CountDownLatch(1);
        var createdAdapters = ConcurrentHashMap.<CommandHandler>newKeySet();
        try (var executor = Executors.newFixedThreadPool(configurators)) {
            var futures = new ArrayList<Future<?>>();
            for (var i = 0; i < configurators; i++) {
                var aggregateType = "Orders-" + i;
                futures.add(executor.submit(() -> {
                    start.await(AT_MOST.toMillis(), TimeUnit.MILLISECONDS);
                    // Every configurator handles CreateOrder, so each one's diagnostic runs over the others' adapters
                    var configurator = new EventStreamDeciderAndAggregateTypeConfigurator(eventStore, commandBus, List.of(orderConfiguration(aggregateType)), List.of(new CreateOrderDecider()));
                    createdAdapters.addAll(configurator.getCreatedAdapters());
                    return null;
                }));
            }
            start.countDown();
            // Rethrows if a construction failed outright
            for (var future : futures) {
                future.get(AT_MOST.toMillis(), TimeUnit.MILLISECONDS);
            }
        }

        assertThat(commandBus.getCommandHandlers()).as("a registration was lost").containsExactlyInAnyOrderElementsOf(createdAdapters).hasSize(configurators);
        assertThat(diagnosticFailures()).as("a diagnostic failed").isEmpty();

        // One more CreateOrder adapter is the ninth
        var report = new EssentialsStartupReport();
        try (var binding = report.bindToCurrentThread()) {
            new EventStreamDeciderAndAggregateTypeConfigurator(eventStore, commandBus, List.of(orderConfiguration("Orders-last")), List.of(new CreateOrderDecider()));
        }
        assertThat(report.getFindings()).singleElement()
                                        .satisfies(finding -> assertThat(finding.message()).contains("would be handled by " + (configurators + 1) + " command handlers"));
    }

    private static EventStreamAggregateTypeConfiguration orderConfiguration(String aggregateType) {
        return new EventStreamAggregateTypeConfiguration(AggregateType.of(aggregateType),
                                                         OrderId.class,
                                                         AggregateIdSerializer.serializerFor(OrderId.class),
                                                         new EventStreamDeciderSupportsAggregateTypeChecker.HandlesCommandsThatInheritFromCommandType(OrderCommand.class),
                                                         command -> ((OrderCommand) command).orderId(),
                                                         event -> ((OrderEvent) event).orderId());
    }

    private List<String> diagnosticFailures() {
        return logAppender.list.stream()
                               .filter(event -> event.getLevel() == Level.ERROR)
                               .map(event -> event.getFormattedMessage() + (event.getThrowableProxy() != null ? ": " + event.getThrowableProxy().getClassName() : ""))
                               .toList();
    }
}
