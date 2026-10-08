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
import dk.trustworks.essentials.components.foundation.startup.EssentialsStartupReport;
import dk.trustworks.essentials.components.foundation.startup.EssentialsStartupReport.*;
import org.junit.jupiter.api.*;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.SmartInitializingSingleton;
import org.springframework.boot.*;
import org.springframework.boot.autoconfigure.ImportAutoConfiguration;
import org.springframework.boot.logging.LoggingSystem;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.*;

import java.util.*;
import java.util.concurrent.*;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The startup report through a real {@link SpringApplication}: one report per context, filled during the refresh,
 * printed once the application is ready
 */
class EssentialsStartupReportPublisherTest {
    private static final String REMOVED_PROPERTY = "essentials.durable-queues.transactional-mode=fully-transactional";

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
    void the_report_is_printed_as_one_error_block_when_the_application_is_ready() {
        try (var context = start(ApplicationWithACheck.class, REMOVED_PROPERTY)) {
            var report = context.getBean(EssentialsStartupReport.class);

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
            assertThat(EssentialsStartupReport.current()).isEmpty();
        }
    }

    @Test
    void an_application_without_findings_logs_one_info_line() {
        try (var context = start(TestApplication.class)) {
            assertThat(context.getBean(EssentialsStartupReport.class).getFindings()).isEmpty();
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
            assertThat(misconfigured.getBean(EssentialsStartupReport.class)).isNotSameAs(clean.getBean(EssentialsStartupReport.class));
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

    private List<ILoggingEvent> reportBlocks() {
        return logAppender.list.stream().filter(event -> event.getFormattedMessage().startsWith("\n=") || event.getFormattedMessage().startsWith("Essentials startup report")).toList();
    }

    private static List<String> errorSources(ConfigurableApplicationContext context) {
        return context.getBean(EssentialsStartupReport.class).getFindings(Severity.ERROR).stream().map(Finding::source).toList();
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
         * Stands in for a check deep inside a component, which only knows {@link EssentialsStartupReport#addToCurrent(Finding)}
         */
        @Bean
        SmartInitializingSingleton aCheckThatRunsDuringStartup() {
            return () -> EssentialsStartupReport.addToCurrent(Finding.warn("test check", "raised while a bean was created", "nothing"));
        }
    }
}
