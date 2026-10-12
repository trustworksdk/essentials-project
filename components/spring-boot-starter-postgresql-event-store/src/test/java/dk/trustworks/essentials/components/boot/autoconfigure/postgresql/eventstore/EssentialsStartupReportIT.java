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

package dk.trustworks.essentials.components.boot.autoconfigure.postgresql.eventstore;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import dk.trustworks.essentials.components.boot.autoconfigure.postgresql.EssentialsComponentsConfiguration;
import dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.ConfigurableEventStore;
import dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.eventstream.AggregateType;
import dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.persistence.table_per_aggregate_type.SeparateTablePerAggregateEventStreamConfiguration;
import dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.processor.*;
import dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.serializer.AggregateIdSerializer;
import dk.trustworks.essentials.components.foundation.boot.EssentialsStartupChecksAutoConfiguration;
import dk.trustworks.essentials.components.foundation.messaging.MessageHandler;
import dk.trustworks.essentials.components.foundation.messaging.queue.*;
import dk.trustworks.essentials.components.foundation.startup.EssentialsStartupReport;
import dk.trustworks.essentials.components.foundation.startup.EssentialsStartupReport.*;
import dk.trustworks.essentials.shared.security.EssentialsSecurityProvider;
import org.awaitility.Awaitility;
import org.junit.jupiter.api.*;
import org.slf4j.LoggerFactory;
import org.springframework.boot.*;
import org.springframework.boot.autoconfigure.ImportAutoConfiguration;
import org.springframework.boot.jdbc.autoconfigure.*;
import org.springframework.boot.logging.LoggingSystem;
import org.springframework.context.annotation.*;
import org.testcontainers.junit.jupiter.*;
import org.testcontainers.postgresql.PostgreSQLContainer;

import java.time.Duration;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * A deliberately misconfigured application - a removed property still set, and a view that handles only part of a
 * sealed event type - started through {@link SpringApplication}, so the startup report is printed when it is ready.
 */
@Testcontainers
class EssentialsStartupReportIT {
    static final AggregateType ORDERS = AggregateType.of("StartupReportOrders");

    @Container
    private static final PostgreSQLContainer postgreSQLContainer = new PostgreSQLContainer("postgres:18.4")
            .withDatabaseName("startup-report")
            .withUsername("test-user")
            .withPassword("secret-password");

    private ListAppender<ILoggingEvent>   logAppender;
    private ch.qos.logback.classic.Logger logger;

    @BeforeEach
    void setUp() {
        // Spring Boot's logging system would reset logback, and with it the appender attached below
        System.setProperty(LoggingSystem.SYSTEM_PROPERTY, LoggingSystem.NONE);
        logger = (ch.qos.logback.classic.Logger) LoggerFactory.getLogger(EssentialsStartupReport.class);
        logAppender = new ListAppender<>();
        logAppender.start();
        logger.addAppender(logAppender);
    }

    @AfterEach
    void tearDown() {
        logger.detachAppender(logAppender);
        System.clearProperty(LoggingSystem.SYSTEM_PROPERTY);
    }

    @Test
    void the_misconfigurations_are_printed_as_one_block_when_the_application_is_ready() {
        var application = new SpringApplication(MisconfiguredApplication.class);
        application.setWebApplicationType(WebApplicationType.NONE);
        application.setBannerMode(Banner.Mode.OFF);
        try (var context = application.run("--spring.datasource.url=" + postgreSQLContainer.getJdbcUrl(),
                                           "--spring.datasource.username=" + postgreSQLContainer.getUsername(),
                                           "--spring.datasource.password=" + postgreSQLContainer.getPassword(),
                                           "--essentials.eventstore.cdc.enabled=false",
                                           "--essentials.durable-queues.transactional-mode=fully-transactional")) {
            var report = context.getBean(EssentialsStartupReport.class);
            assertThat(report.getFindings()).extracting(Finding::severity, Finding::source)
                                            .as("exactly the two misconfigurations - a default starter setup adds nothing")
                                            .containsExactlyInAnyOrder(org.assertj.core.groups.Tuple.tuple(Severity.ERROR, "property 'essentials.durable-queues.transactional-mode'"),
                                                                       org.assertj.core.groups.Tuple.tuple(Severity.WARN, "ViewEventProcessor 'OrderSummaryView'"));

            var blocks = logAppender.list.stream().filter(event -> event.getFormattedMessage().contains("Essentials startup report")).toList();
            assertThat(blocks).singleElement().satisfies(event -> {
                assertThat(event.getLevel()).isEqualTo(Level.ERROR);
                var block = event.getFormattedMessage();
                assertThat(block).contains("ERROR (")
                                 .contains("[property 'essentials.durable-queues.transactional-mode'] is set (value 'fully-transactional'")
                                 .contains("Fix: Removed in 0.60.0. TransactionalMode was removed")
                                 .contains("WARN (")
                                 .contains("[ViewEventProcessor 'OrderSummaryView'] The sealed event type(s) [" + OrderEvent.class.getName() + "] permit 1 type(s)")
                                 .contains(OrderShipped.class.getName())
                                 .doesNotContain(OrderArchived.class.getName())
                                 .contains("Fix: Add a @MessageHandler for each type the view should react to");
                assertThat(block.indexOf("ERROR (")).isLessThan(block.indexOf("WARN ("));
            });
        }
    }

    /**
     * Relies on this context being the only one open in the JVM - no test in this module keeps a context cached - since
     * a finding raised after start-up reaches a report only then
     */
    @Test
    void a_finding_raised_while_the_application_runs_is_logged_as_new_and_in_the_run_report_on_close() {
        var application = new SpringApplication(MisconfiguredApplication.class);
        application.setWebApplicationType(WebApplicationType.NONE);
        application.setBannerMode(Banner.Mode.OFF);
        try (var context = application.run("--spring.datasource.url=" + postgreSQLContainer.getJdbcUrl(),
                                           "--spring.datasource.username=" + postgreSQLContainer.getUsername(),
                                           "--spring.datasource.password=" + postgreSQLContainer.getPassword(),
                                           "--essentials.eventstore.cdc.enabled=false",
                                           "--essentials.startup-report.new-findings-interval=200ms")) {
            assertThat(EssentialsStartupReport.runReport()).containsSame(context.getBean(EssentialsStartupReport.class));

            // At runtime, on this thread, with no report bound to it: an enqueue outside a UnitOfWork
            context.getBean(DurableQueues.class).queueMessage(QueueName.of("StartupReportRuntimeQueue"), Message.of("payload"));

            Awaitility.await().atMost(Duration.ofSeconds(10)).untilAsserted(() -> assertThat(logged()).anySatisfy(event -> {
                assertThat(event.getLevel()).isEqualTo(Level.WARN);
                assertThat(event.getFormattedMessage()).contains("Essentials report, 1 new finding since the last report: 0 errors, 1 warning, 0 notes")
                                                       .contains("[DurableQueues enqueue on queue 'StartupReportRuntimeQueue' from ")
                                                       .contains("a message was enqueued outside a UnitOfWork/transaction, so it committed on its own")
                                                       .doesNotContain("OrderSummaryView");
            }));
        }

        assertThat(logged()).filteredOn(event -> event.getFormattedMessage().contains("Essentials run report")).singleElement().satisfies(event -> {
            assertThat(event.getLevel()).isEqualTo(Level.WARN);
            assertThat(event.getFormattedMessage()).contains("Essentials run report: 0 errors, 2 warnings, 0 notes over the whole run")
                                                   .contains("[ViewEventProcessor 'OrderSummaryView']")
                                                   .contains("[DurableQueues enqueue on queue 'StartupReportRuntimeQueue' from ")
                                                   .contains("Seen once, at ");
        });
        assertThat(EssentialsStartupReport.runReport()).isEmpty();
    }

    /**
     * The periodic log appends on its own thread; logback's AppenderBase appends under the appender's monitor
     */
    private List<ILoggingEvent> logged() {
        synchronized (logAppender) {
            return List.copyOf(logAppender.list);
        }
    }

    public sealed interface OrderEvent permits OrderPlaced, OrderShipped, OrderArchived {
    }

    public record OrderPlaced(String orderId) implements OrderEvent {
    }

    public record OrderShipped(String orderId) implements OrderEvent {
    }

    public record OrderArchived(String orderId) implements OrderEvent {
    }

    @Configuration(proxyBeanMethods = false)
    @ImportAutoConfiguration({DataSourceAutoConfiguration.class,
                              DataSourceTransactionManagerAutoConfiguration.class,
                              EssentialsStartupChecksAutoConfiguration.class,
                              EssentialsComponentsConfiguration.class,
                              EventStoreConfiguration.class})
    static class MisconfiguredApplication {
        @Bean
        EssentialsSecurityProvider securityProvider() {
            return new EssentialsSecurityProvider.AllAccessSecurityProvider();
        }

        @Bean
        OrderSummaryView orderSummaryView(ViewEventProcessorDependencies dependencies,
                                          ConfigurableEventStore<SeparateTablePerAggregateEventStreamConfiguration> eventStore) {
            eventStore.addAggregateEventStreamConfiguration(ORDERS, AggregateIdSerializer.serializerFor(String.class));
            return new OrderSummaryView(dependencies);
        }
    }

    /**
     * Handles OrderPlaced, ignores OrderArchived on purpose - and has forgotten OrderShipped
     */
    @IgnoresEventTypes(OrderArchived.class)
    static class OrderSummaryView extends ViewEventProcessor {
        OrderSummaryView(ViewEventProcessorDependencies dependencies) {
            super(dependencies);
        }

        @Override
        public String getProcessorName() {
            return "OrderSummaryView";
        }

        @Override
        protected List<AggregateType> reactsToEventsRelatedToAggregateTypes() {
            return List.of(ORDERS);
        }

        @MessageHandler
        void on(OrderPlaced event) {
        }
    }
}
