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

package dk.trustworks.essentials.components.foundation.boot;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import dk.trustworks.essentials.components.foundation.startup.EssentialsRunReport;
import dk.trustworks.essentials.components.foundation.startup.EssentialsRunReport.*;
import org.awaitility.Awaitility;
import org.junit.jupiter.api.*;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.SmartInitializingSingleton;
import org.springframework.boot.*;
import org.springframework.boot.autoconfigure.ImportAutoConfiguration;
import org.springframework.boot.logging.LoggingSystem;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.*;

import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The startup report through a real {@link SpringApplication}: one report per context, filled during the refresh,
 * printed once the application is ready
 */
class EssentialsRunReportPublisherTest {
    private static final String REMOVED_PROPERTY = "essentials.durable-queues.transactional-mode=fully-transactional";

    private ListAppender<ILoggingEvent>   logAppender;
    private ch.qos.logback.classic.Logger logger;

    @BeforeEach
    void setUp() {
        // Spring Boot's logging system would reset logback, and with it the appender attached below
        System.setProperty(LoggingSystem.SYSTEM_PROPERTY, LoggingSystem.NONE);
        logger = (ch.qos.logback.classic.Logger) LoggerFactory.getLogger(EssentialsRunReport.class);
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
    void the_report_is_printed_as_one_error_block_when_the_application_is_ready() {
        try (var context = start(ApplicationWithACheck.class, REMOVED_PROPERTY)) {
            var report = context.getBean(EssentialsRunReport.class);

            assertThat(report.getFindings()).extracting(Finding::severity, Finding::source)
                                            .containsExactlyInAnyOrder(org.assertj.core.groups.Tuple.tuple(Severity.ERROR, "property 'essentials.durable-queues.transactional-mode'"),
                                                                       org.assertj.core.groups.Tuple.tuple(Severity.WARN, "test check"));
            assertThat(reportBlocks()).singleElement().satisfies(event -> {
                assertThat(event.getLevel()).isEqualTo(Level.ERROR);
                assertThat(event.getFormattedMessage()).contains("Essentials startup report: 1 error, 1 warning, 0 notes")
                                                       .contains("[property 'essentials.durable-queues.transactional-mode'] is set (value 'fully-transactional'")
                                                       .contains("Fix: Removed in 0.60.0. TransactionalMode was removed")
                                                       .contains("[test check] raised while a bean was created");
            });
            // The binding ended with the refresh
            assertThat(EssentialsRunReport.current()).isEmpty();
        }
    }

    @Test
    void an_application_without_findings_logs_one_info_line() {
        try (var context = start(TestApplication.class)) {
            assertThat(context.getBean(EssentialsRunReport.class).getFindings()).isEmpty();
        }
        assertThat(reportBlocks()).singleElement().satisfies(event -> {
            assertThat(event.getLevel()).isEqualTo(Level.INFO);
            assertThat(event.getFormattedMessage()).isEqualTo("Essentials startup report: no findings");
        });
    }

    @Test
    void two_contexts_in_one_jvm_do_not_share_findings() throws Exception {
        try (var misconfigured = start(TestApplication.class, REMOVED_PROPERTY);
             var clean = start(TestApplication.class)) {
            assertThat(errorSources(misconfigured)).containsExactly("property 'essentials.durable-queues.transactional-mode'");
            assertThat(errorSources(clean)).isEmpty();
            assertThat(misconfigured.getBean(EssentialsRunReport.class)).isNotSameAs(clean.getBean(EssentialsRunReport.class));
        }

        // ... nor when they start at the same time on different threads
        var executor = Executors.newFixedThreadPool(2);
        try {
            var misconfigured = executor.submit(() -> start(TestApplication.class, REMOVED_PROPERTY));
            var clean         = executor.submit(() -> start(TestApplication.class));
            try (var misconfiguredContext = misconfigured.get(60, TimeUnit.SECONDS);
                 var cleanContext = clean.get(60, TimeUnit.SECONDS)) {
                assertThat(errorSources(misconfiguredContext)).hasSize(1);
                assertThat(errorSources(cleanContext)).isEmpty();
            }
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    void a_finding_raised_after_start_up_is_logged_as_new_and_in_the_complete_report_when_the_context_closes() throws Exception {
        try (var context = start(ApplicationWithACheck.class, "essentials.run-report.new-findings-interval=100ms")) {
            var publisher = context.getBean(EssentialsRunReportPublisher.class);
            assertThat(publisher.getNewFindingsInterval()).isEqualTo(Duration.ofMillis(100));
            assertThat(publisher.isLoggingNewFindings()).isTrue();
            assertThat(EssentialsRunReport.runReport()).containsSame(context.getBean(EssentialsRunReport.class));

            // A check running after start-up, on a thread no report is bound to
            var executor = Executors.newSingleThreadExecutor();
            try {
                executor.submit(() -> EssentialsRunReport.addToCurrent(Finding.error("runtime check", "raised while the application ran", "fix the runtime"))).get(5, TimeUnit.SECONDS);
            } finally {
                executor.shutdownNow();
            }
            EssentialsRunReport.addToCurrent(Finding.error("runtime check", "raised while the application ran", "fix the runtime"));

            Awaitility.await().atMost(Duration.ofSeconds(10)).untilAsserted(() -> assertThat(newFindingsBlocks()).singleElement().satisfies(event -> {
                assertThat(event.getLevel()).isEqualTo(Level.ERROR);
                assertThat(event.getFormattedMessage()).contains("Essentials report, 1 new finding since the last report: 1 error, 0 warnings, 0 notes")
                                                       .contains("[runtime check] raised while the application ran")
                                                       .contains("Seen 2 times")
                                                       .doesNotContain("[test check]");
            }));
        }

        assertThat(runReportBlocks()).singleElement().satisfies(event -> {
            assertThat(event.getLevel()).isEqualTo(Level.ERROR);
            assertThat(event.getFormattedMessage()).contains("Essentials run report: 1 error, 1 warning, 0 notes over the whole run")
                                                   .contains("  - [runtime check] raised while the application ran\n    Fix: fix the runtime\n    Seen 2 times, first at ")
                                                   .contains("  - [test check] raised while a bean was created\n    Fix: nothing\n    Seen once, at ");
        });
        // Deregistered with the context
        assertThat(EssentialsRunReport.runReport()).isEmpty();
        // Exactly one new-findings block: nothing new after the first
        assertThat(newFindingsBlocks()).hasSize(1);
    }

    @Test
    void a_zero_interval_turns_the_periodic_log_off_but_the_run_report_is_still_logged_on_close() {
        EssentialsRunReportPublisher publisher;
        try (var context = start(TestApplication.class, "essentials.run-report.new-findings-interval=0")) {
            publisher = context.getBean(EssentialsRunReportPublisher.class);
            assertThat(publisher.getNewFindingsInterval()).isZero();
            assertThat(publisher.isLoggingNewFindings()).isFalse();
            EssentialsRunReport.addToCurrent(Finding.info("runtime", "a note", ""));
        }

        assertThat(newFindingsBlocks()).isEmpty();
        assertThat(runReportBlocks()).singleElement().satisfies(event -> {
            assertThat(event.getLevel()).isEqualTo(Level.INFO);
            assertThat(event.getFormattedMessage()).contains("Essentials run report: 0 errors, 0 warnings, 1 note over the whole run");
        });
    }

    @Test
    void the_default_interval_is_five_minutes_and_the_thread_stops_with_the_context() {
        EssentialsRunReportPublisher publisher;
        try (var context = start(TestApplication.class)) {
            publisher = context.getBean(EssentialsRunReportPublisher.class);
            assertThat(publisher.getNewFindingsInterval()).isEqualTo(EssentialsRunReportPublisher.DEFAULT_NEW_FINDINGS_INTERVAL).isEqualTo(Duration.ofMinutes(5));
            assertThat(publisher.isLoggingNewFindings()).isTrue();
        }
        assertThat(publisher.isLoggingNewFindings()).isFalse();
        assertThat(runReportBlocks()).singleElement().extracting(ILoggingEvent::getFormattedMessage).isEqualTo("Essentials run report: no findings over the whole run");
    }

    /**
     * The periodic log appends on its own thread; logback's AppenderBase appends under the appender's monitor
     */
    private List<ILoggingEvent> logged() {
        synchronized (logAppender) {
            return List.copyOf(logAppender.list);
        }
    }

    private List<ILoggingEvent> newFindingsBlocks() {
        return logged().stream().filter(event -> event.getFormattedMessage().contains("Essentials report, ")).toList();
    }

    private List<ILoggingEvent> runReportBlocks() {
        return logged().stream().filter(event -> event.getFormattedMessage().contains("Essentials run report")).toList();
    }

    private List<ILoggingEvent> reportBlocks() {
        return logAppender.list.stream().filter(event -> event.getFormattedMessage().startsWith("\n=") || event.getFormattedMessage().startsWith("Essentials startup report")).toList();
    }

    private static List<String> errorSources(ConfigurableApplicationContext context) {
        return context.getBean(EssentialsRunReport.class).getFindings(Severity.ERROR).stream().map(Finding::source).toList();
    }

    private static ConfigurableApplicationContext start(Class<?> applicationClass, String... properties) {
        var arguments = Arrays.stream(properties).map(property -> "--" + property).toArray(String[]::new);
        var application = new SpringApplication(applicationClass);
        application.setWebApplicationType(WebApplicationType.NONE);
        application.setBannerMode(Banner.Mode.OFF);
        return application.run(arguments);
    }

    @Configuration(proxyBeanMethods = false)
    @ImportAutoConfiguration(EssentialsStartupChecksAutoConfiguration.class)
    static class TestApplication {
    }

    @Configuration(proxyBeanMethods = false)
    @Import(TestApplication.class)
    static class ApplicationWithACheck {
        /**
         * Stands in for a check deep inside a component, which only knows {@link EssentialsRunReport#addToCurrent(Finding)}
         */
        @Bean
        SmartInitializingSingleton aCheckThatRunsDuringStartup() {
            return () -> EssentialsRunReport.addToCurrent(Finding.warn("test check", "raised while a bean was created", "nothing"));
        }
    }
}
