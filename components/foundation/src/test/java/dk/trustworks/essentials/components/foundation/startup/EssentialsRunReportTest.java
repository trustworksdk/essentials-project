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
import dk.trustworks.essentials.components.foundation.startup.EssentialsRunReport.*;
import org.junit.jupiter.api.*;
import org.slf4j.LoggerFactory;

import java.time.*;
import java.util.concurrent.*;

import static org.assertj.core.api.Assertions.assertThat;

class EssentialsRunReportTest {
    private ListAppender<ILoggingEvent>   logAppender;
    private ch.qos.logback.classic.Logger logger;

    @BeforeEach
    void setUp() {
        logger = (ch.qos.logback.classic.Logger) LoggerFactory.getLogger(EssentialsRunReport.class);
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
        new EssentialsRunReport().log();

        assertThat(logAppender.list).singleElement().satisfies(event -> {
            assertThat(event.getLevel()).isEqualTo(Level.INFO);
            assertThat(event.getFormattedMessage()).isEqualTo("Essentials startup report: no findings");
        });
    }

    @Test
    void the_block_groups_the_findings_by_severity_with_their_fix_and_is_logged_at_the_highest_severity() {
        var report = new EssentialsRunReport();
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
        var report = new EssentialsRunReport();
        report.add(Finding.warn("a", "b", "c"));

        report.log();

        assertThat(logAppender.list).singleElement().extracting(ILoggingEvent::getLevel).isEqualTo(Level.WARN);
    }

    @Test
    void a_finding_reaches_only_the_report_bound_to_the_current_thread() throws Exception {
        EssentialsRunReport.addToCurrent(Finding.warn("nothing bound", "dropped", ""));
        assertThat(EssentialsRunReport.current()).isEmpty();

        var outer = new EssentialsRunReport();
        var inner = new EssentialsRunReport();
        try (var outerBinding = outer.bindToCurrentThread()) {
            EssentialsRunReport.addToCurrent(Finding.warn("outer", "1", ""));
            try (var innerBinding = inner.bindToCurrentThread()) {
                EssentialsRunReport.addToCurrent(Finding.warn("inner", "2", ""));
                // Another thread sees neither
                var executor = Executors.newSingleThreadExecutor();
                try {
                    assertThat(executor.submit(() -> EssentialsRunReport.current().isPresent()).get(5, TimeUnit.SECONDS)).isFalse();
                } finally {
                    executor.shutdownNow();
                }
            }
            assertThat(EssentialsRunReport.current()).containsSame(outer);
            EssentialsRunReport.addToCurrent(Finding.warn("outer", "3", ""));
        }

        assertThat(EssentialsRunReport.current()).isEmpty();
        assertThat(outer.getFindings()).extracting(Finding::message).containsExactly("1", "3");
        assertThat(inner.getFindings()).extracting(Finding::message).containsExactly("2");
    }

    @Test
    void closing_a_binding_on_another_thread_or_twice_changes_nothing() throws Exception {
        var report  = new EssentialsRunReport();
        var binding = report.bindToCurrentThread();
        try {
            var executor = Executors.newSingleThreadExecutor();
            try {
                executor.submit(binding::close).get(5, TimeUnit.SECONDS);
            } finally {
                executor.shutdownNow();
            }
            assertThat(EssentialsRunReport.current()).containsSame(report);
        } finally {
            binding.close();
            binding.close();
        }
        assertThat(EssentialsRunReport.current()).isEmpty();
    }

    @Test
    void without_a_bound_report_a_finding_reaches_the_run_report_only_when_exactly_one_is_registered() throws Exception {
        assertThat(EssentialsRunReport.runReport()).isEmpty();
        var first  = new EssentialsRunReport();
        var second = new EssentialsRunReport();

        // 0 registered: dropped
        EssentialsRunReport.addToCurrent(Finding.warn("none registered", "dropped", ""));

        // 1 registered: reaches it, from any thread
        try (var firstRegistration = first.registerAsRunReport()) {
            assertThat(EssentialsRunReport.runReport()).containsSame(first);
            assertThat(EssentialsRunReport.current()).isEmpty();
            EssentialsRunReport.addToCurrent(Finding.warn("one registered", "kept", ""));
            var executor = Executors.newSingleThreadExecutor();
            try {
                executor.submit(() -> EssentialsRunReport.addToCurrent(Finding.warn("one registered", "kept on another thread", ""))).get(5, TimeUnit.SECONDS);
            } finally {
                executor.shutdownNow();
            }

            // A report bound to the thread still wins
            var bound = new EssentialsRunReport();
            try (var binding = bound.bindToCurrentThread()) {
                EssentialsRunReport.addToCurrent(Finding.warn("bound", "to the bound report", ""));
            }
            assertThat(bound.getFindings()).extracting(Finding::message).containsExactly("to the bound report");

            // 2 registered: dropped - neither is "the" run report
            try (var secondRegistration = second.registerAsRunReport()) {
                assertThat(EssentialsRunReport.runReport()).isEmpty();
                assertThat(first.isRegisteredAsRunReport()).isTrue();
                EssentialsRunReport.addToCurrent(Finding.warn("two registered", "dropped", ""));
            }

            // Back to 1
            assertThat(EssentialsRunReport.runReport()).containsSame(first);
            assertThat(second.isRegisteredAsRunReport()).isFalse();
            firstRegistration.close();
            firstRegistration.close();
        }

        assertThat(EssentialsRunReport.runReport()).isEmpty();
        EssentialsRunReport.addToCurrent(Finding.warn("none registered", "dropped again", ""));
        assertThat(first.getFindings()).extracting(Finding::message).containsExactly("kept", "kept on another thread");
        assertThat(second.getFindings()).isEmpty();
    }

    @Test
    void identical_findings_are_kept_once_with_their_count_and_first_and_last_seen() {
        var clock  = new MutableClock(Instant.parse("2026-10-12T08:00:00Z"));
        var report = new EssentialsRunReport(10, clock);
        var noisy  = Finding.warn("queue 'Orders'", "enqueued outside a UnitOfWork", "Use a UnitOfWork");

        report.add(noisy);
        clock.now = Instant.parse("2026-10-12T08:05:00Z");
        report.add(Finding.info("schema", "a note", ""));
        clock.now = Instant.parse("2026-10-12T09:00:00Z");
        report.add(noisy);
        report.add(noisy);

        assertThat(report.getFindings()).containsExactly(noisy, Finding.info("schema", "a note", ""));
        assertThat(report.getOccurrences()).containsExactly(
                new Occurrences(noisy, 3, Instant.parse("2026-10-12T08:00:00Z"), Instant.parse("2026-10-12T09:00:00Z")),
                new Occurrences(Finding.info("schema", "a note", ""), 1, Instant.parse("2026-10-12T08:05:00Z"), Instant.parse("2026-10-12T08:05:00Z")));
    }

    @Test
    void beyond_the_cap_distinct_findings_are_only_counted() {
        var report = new EssentialsRunReport(2);
        report.add(Finding.warn("a", "1", ""));
        report.add(Finding.warn("b", "2", ""));
        report.add(Finding.warn("c", "3", ""));
        report.add(Finding.warn("c", "3", ""));
        report.add(Finding.error("d", "4", ""));
        // A finding already kept is still counted, not overflowed
        report.add(Finding.warn("a", "1", ""));

        assertThat(report.getFindings()).extracting(Finding::source).containsExactly("a", "b");
        assertThat(report.getOccurrences().getFirst().count()).isEqualTo(2);
        assertThat(report.getOverflowCount()).isEqualTo(3);
        assertThat(report.renderRunReport()).contains("Plus 3 occurrences of findings not kept - the report holds at most 2 distinct findings");
        assertThat(new EssentialsRunReport().getOverflowCount()).isZero();
    }

    @Test
    void the_periodic_log_prints_only_the_findings_not_printed_before_at_their_highest_severity() {
        var report = new EssentialsRunReport(2);
        report.add(Finding.warn("startup", "found at start-up", ""));
        report.log();
        logAppender.list.clear();

        // Nothing new: nothing logged
        assertThat(report.logNewFindings()).isFalse();
        // A repeat of a finding already printed is not new
        report.add(Finding.warn("startup", "found at start-up", ""));
        assertThat(report.logNewFindings()).isFalse();
        assertThat(logAppender.list).isEmpty();

        report.add(Finding.info("runtime", "a note", ""));
        assertThat(report.logNewFindings()).isTrue();
        assertThat(logAppender.list).singleElement().satisfies(event -> {
            assertThat(event.getLevel()).isEqualTo(Level.INFO);
            assertThat(event.getFormattedMessage()).contains("Essentials report, 1 new finding since the last report: 0 errors, 0 warnings, 1 note")
                                                   .contains("  - [runtime] a note\n    Seen once, at ")
                                                   .doesNotContain("found at start-up");
        });
        logAppender.list.clear();

        // Overflow alone is news too
        report.add(Finding.error("runtime", "beyond the cap", "fix it"));
        assertThat(report.logNewFindings()).isTrue();
        assertThat(logAppender.list).singleElement().satisfies(event -> {
            assertThat(event.getLevel()).isEqualTo(Level.WARN);
            assertThat(event.getFormattedMessage()).contains("Essentials report, 0 new findings since the last report")
                                                   .contains("Plus 1 occurrence of findings not kept");
        });
        assertThat(report.logNewFindings()).isFalse();
    }

    @Test
    void the_run_report_lists_every_finding_of_the_run_with_its_occurrences() {
        var clock  = new MutableClock(Instant.parse("2026-10-12T08:00:00Z"));
        var report = new EssentialsRunReport(10, clock);
        report.add(Finding.warn("startup", "found at start-up", "Fix A"));
        report.log();
        clock.now = Instant.parse("2026-10-12T10:00:00Z");
        report.add(Finding.error("runtime", "found later", "Fix B"));
        clock.now = Instant.parse("2026-10-12T11:00:00Z");
        report.add(Finding.error("runtime", "found later", "Fix B"));
        report.logNewFindings();
        logAppender.list.clear();

        report.logRunReport();

        assertThat(logAppender.list).singleElement().satisfies(event -> {
            assertThat(event.getLevel()).isEqualTo(Level.ERROR);
            assertThat(event.getFormattedMessage())
                    .contains("Essentials run report: 1 error, 1 warning, 0 notes over the whole run - each was also logged where it was found")
                    .contains("  - [runtime] found later\n    Fix: Fix B\n    Seen 2 times, first at 2026-10-12T10:00:00Z, last at 2026-10-12T11:00:00Z\n")
                    .contains("  - [startup] found at start-up\n    Fix: Fix A\n    Seen once, at 2026-10-12T08:00:00Z\n");
        });
        // The start-up block does not carry the occurrences
        assertThat(report.render()).doesNotContain("Seen");
        assertThat(new EssentialsRunReport().renderRunReport()).isEqualTo("Essentials run report: no findings over the whole run");
    }

    @Test
    void closing_one_of_two_registrations_of_the_same_report_keeps_it_registered() {
        var report = new EssentialsRunReport();
        var first  = report.registerAsRunReport();
        var second = report.registerAsRunReport();
        try {
            first.close();
            assertThat(report.isRegisteredAsRunReport()).isTrue();
            assertThat(EssentialsRunReport.runReport()).containsSame(report);
            second.close();
            assertThat(report.isRegisteredAsRunReport()).isFalse();
            assertThat(EssentialsRunReport.runReport()).isEmpty();
        } finally {
            first.close();
            second.close();
        }
    }

    @Test
    void out_of_order_binding_closes_leave_nothing_bound() {
        var a = new EssentialsRunReport();
        var b = new EssentialsRunReport();
        var bindingA = a.bindToCurrentThread();
        var bindingB = b.bindToCurrentThread();
        assertThat(EssentialsRunReport.current()).containsSame(b);

        bindingA.close();
        assertThat(EssentialsRunReport.current()).containsSame(b);
        bindingB.close();
        assertThat(EssentialsRunReport.current()).isEmpty();
    }

    @Test
    void a_report_that_was_not_the_run_report_says_so_instead_of_claiming_no_findings() {
        var clock  = new MutableClock(Instant.parse("2026-10-12T08:00:00Z"));
        var first  = new EssentialsRunReport(10, clock);
        var second = new EssentialsRunReport(10, clock);
        var firstRegistration = first.registerAsRunReport();
        Registration secondRegistration = null;
        try {
            clock.now = Instant.parse("2026-10-12T08:05:00Z");
            secondRegistration = second.registerAsRunReport();
            assertThat(logAppender.list).anySatisfy(event -> {
                assertThat(event.getLevel()).isEqualTo(Level.INFO);
                assertThat(event.getFormattedMessage()).contains("More than one Essentials run report is registered");
            });
            // No run report now: the finding goes nowhere
            EssentialsRunReport.addToCurrent(Finding.warn("queue", "enqueue outside a UnitOfWork", ""));
            assertThat(first.getFindings()).isEmpty();

            assertThat(first.renderRunReport())
                    .contains("no findings over the whole run, but runtime findings were not collected from 2026-10-12T08:05:00Z until now")
                    .contains("2 run reports registered");
            assertThat(first.logNewFindings()).isTrue();
            assertThat(first.logNewFindings()).isFalse();

            clock.now = Instant.parse("2026-10-12T08:10:00Z");
            secondRegistration.close();
            assertThat(first.renderRunReport()).contains("from 2026-10-12T08:05:00Z until 2026-10-12T08:10:00Z");
            assertThat(second.renderRunReport()).contains("from 2026-10-12T08:05:00Z until 2026-10-12T08:10:00Z");
        } finally {
            firstRegistration.close();
            if (secondRegistration != null) {
                secondRegistration.close();
            }
        }
    }

    private static final class MutableClock extends Clock {
        private volatile Instant now;

        private MutableClock(Instant now) {
            this.now = now;
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return now;
        }
    }
}
