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

package dk.trustworks.essentials.components.foundation.startup;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import dk.trustworks.essentials.components.foundation.startup.EssentialsStartupReport.*;
import org.junit.jupiter.api.*;
import org.slf4j.LoggerFactory;

import java.util.concurrent.*;

import static org.assertj.core.api.Assertions.assertThat;

class EssentialsStartupReportTest {
    private ListAppender<ILoggingEvent>   logAppender;
    private ch.qos.logback.classic.Logger logger;

    @BeforeEach
    void setUp() {
        logger = (ch.qos.logback.classic.Logger) LoggerFactory.getLogger(EssentialsStartupReport.class);
        logAppender = new ListAppender<>();
        logAppender.start();
        logger.addAppender(logAppender);
    }

    @AfterEach
    void tearDown() {
        logger.detachAppender(logAppender);
    }

    @Test
    void an_empty_report_logs_one_info_line() {
        new EssentialsStartupReport().log();

        assertThat(logAppender.list).singleElement().satisfies(event -> {
            assertThat(event.getLevel()).isEqualTo(Level.INFO);
            assertThat(event.getFormattedMessage()).isEqualTo("Essentials startup report: no findings");
        });
    }

    @Test
    void the_block_groups_the_findings_by_severity_with_their_fix_and_is_logged_at_the_highest_severity() {
        var report = new EssentialsStartupReport();
        report.add(Finding.info("schema", "a note", ""));
        report.add(Finding.warn("view 'Orders'", "skips OrderShipped", "Add a @MessageHandler"));
        report.add(Finding.error("property 'essentials.x'", "is set but ignored", "Remove the property"));
        report.add(Finding.warn("view 'Orders'", "skips OrderShipped", "Add a @MessageHandler"));

        assertThat(report.getFindings()).hasSize(3);
        assertThat(report.getHighestSeverity()).contains(Severity.ERROR);
        var rendered = report.render();
        assertThat(rendered).contains("Essentials startup report: 1 error, 1 warning, 1 note");
        assertThat(rendered.indexOf("ERROR (1)")).isLessThan(rendered.indexOf("WARN (1)"));
        assertThat(rendered.indexOf("WARN (1)")).isLessThan(rendered.indexOf("INFO (1)"));
        assertThat(rendered).contains("  - [property 'essentials.x'] is set but ignored\n    Fix: Remove the property\n");
        assertThat(rendered).contains("  - [schema] a note\n").doesNotContain("a note\n    Fix:");

        report.log();
        assertThat(logAppender.list).singleElement().extracting(ILoggingEvent::getLevel).isEqualTo(Level.ERROR);
    }

    @Test
    void warnings_without_errors_are_logged_at_warn() {
        var report = new EssentialsStartupReport();
        report.add(Finding.warn("a", "b", "c"));

        report.log();

        assertThat(logAppender.list).singleElement().extracting(ILoggingEvent::getLevel).isEqualTo(Level.WARN);
    }

    @Test
    void a_finding_reaches_only_the_report_bound_to_the_current_thread() throws Exception {
        EssentialsStartupReport.addToCurrent(Finding.warn("nothing bound", "dropped", ""));
        assertThat(EssentialsStartupReport.current()).isEmpty();

        var outer = new EssentialsStartupReport();
        var inner = new EssentialsStartupReport();
        try (var outerBinding = outer.bindToCurrentThread()) {
            EssentialsStartupReport.addToCurrent(Finding.warn("outer", "1", ""));
            try (var innerBinding = inner.bindToCurrentThread()) {
                EssentialsStartupReport.addToCurrent(Finding.warn("inner", "2", ""));
                // Another thread sees neither
                var executor = Executors.newSingleThreadExecutor();
                try {
                    assertThat(executor.submit(() -> EssentialsStartupReport.current().isPresent()).get(5, TimeUnit.SECONDS)).isFalse();
                } finally {
                    executor.shutdownNow();
                }
            }
            assertThat(EssentialsStartupReport.current()).containsSame(outer);
            EssentialsStartupReport.addToCurrent(Finding.warn("outer", "3", ""));
        }

        assertThat(EssentialsStartupReport.current()).isEmpty();
        assertThat(outer.getFindings()).extracting(Finding::message).containsExactly("1", "3");
        assertThat(inner.getFindings()).extracting(Finding::message).containsExactly("2");
    }

    @Test
    void closing_a_binding_on_another_thread_or_twice_changes_nothing() throws Exception {
        var report  = new EssentialsStartupReport();
        var binding = report.bindToCurrentThread();
        try {
            var executor = Executors.newSingleThreadExecutor();
            try {
                executor.submit(binding::close).get(5, TimeUnit.SECONDS);
            } finally {
                executor.shutdownNow();
            }
            assertThat(EssentialsStartupReport.current()).containsSame(report);
        } finally {
            binding.close();
            binding.close();
        }
        assertThat(EssentialsStartupReport.current()).isEmpty();
    }
}
