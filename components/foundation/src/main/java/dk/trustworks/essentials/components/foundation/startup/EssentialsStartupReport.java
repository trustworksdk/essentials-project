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

import org.slf4j.*;

import java.util.*;
import java.util.stream.Collectors;

import static dk.trustworks.essentials.shared.FailFast.requireNonNull;

/**
 * Collects the findings of the Essentials start-up checks, so they can be printed as one block once the application
 * is up (0.60.1). Each check keeps logging where it always did - an application without a report loses nothing - and
 * also adds a {@link Finding} to the report that is <em>current</em> on its thread, if there is one.
 * <p>
 * <b>One report per application context, bound to the thread that starts it.</b> There is no JVM-wide report: a test
 * JVM starts many Spring contexts, often at the same time, and a shared one would mix their findings. The Spring
 * Boot starters create a report per context and {@link #bindToCurrentThread() bind} it to the thread refreshing that
 * context for the duration of the refresh - which is where the checks run (bean creation, lifecycle start). A check
 * deep inside a library (a builder, a processor's {@code start()}) therefore needs no reference to the report and no
 * new constructor parameter: it calls {@link #addToCurrent(Finding)}, which is a no-op when nothing is bound - outside
 * Spring, on another thread, or after start-up - leaving the check's own log line as the only trace, exactly as before.
 * <p>
 * Without Spring, create a report, bind it around your own start-up and {@link #log()} it:
 * <pre>{@code
 * var report = new EssentialsStartupReport();
 * try (var binding = report.bindToCurrentThread()) {
 *     // build and start the Essentials components
 * }
 * report.log();
 * }</pre>
 * Identical findings are reported once. The report is thread-safe.
 */
public final class EssentialsStartupReport {
    private static final Logger                                log     = LoggerFactory.getLogger(EssentialsStartupReport.class);
    private static final ThreadLocal<EssentialsStartupReport> CURRENT = new ThreadLocal<>();

    /**
     * How serious a {@link Finding} is
     */
    public enum Severity {
        /**
         * The application is misconfigured in a way that loses or corrupts data, or silently changes behaviour
         */
        ERROR,
        /**
         * Probably a mistake - it works today, but not as the application most likely expects
         */
        WARN,
        /**
         * Worth knowing, nothing to fix
         */
        INFO
    }

    /**
     * One start-up finding
     *
     * @param severity how serious it is
     * @param source   what it is about - a property, a bean, a processor, a class
     * @param message  what was found
     * @param fix      what to do about it - the property, annotation or dependency to change; empty when there is nothing to do
     */
    public record Finding(Severity severity, String source, String message, String fix) {
        public Finding {
            requireNonNull(severity, "No severity provided");
            requireNonNull(source, "No source provided");
            requireNonNull(message, "No message provided");
            requireNonNull(fix, "No fix provided");
        }

        /**
         * @param source  what it is about
         * @param message what was found
         * @param fix     what to do about it
         * @return a {@link Severity#ERROR} finding
         */
        public static Finding error(String source, String message, String fix) {
            return new Finding(Severity.ERROR, source, message, fix);
        }

        /**
         * @param source  what it is about
         * @param message what was found
         * @param fix     what to do about it
         * @return a {@link Severity#WARN} finding
         */
        public static Finding warn(String source, String message, String fix) {
            return new Finding(Severity.WARN, source, message, fix);
        }

        /**
         * @param source  what it is about
         * @param message what was found
         * @param fix     what to do about it, or empty
         * @return an {@link Severity#INFO} finding
         */
        public static Finding info(String source, String message, String fix) {
            return new Finding(Severity.INFO, source, message, fix);
        }
    }

    private final Set<Finding> findings = new LinkedHashSet<>();

    /**
     * @return the report bound to the current thread by {@link #bindToCurrentThread()}, if any
     */
    public static Optional<EssentialsStartupReport> current() {
        return Optional.ofNullable(CURRENT.get());
    }

    /**
     * Add the finding to the report bound to the current thread; does nothing when none is bound. Never throws on a
     * non-null finding - a start-up check must not fail because of its report.
     *
     * @param finding the finding
     */
    public static void addToCurrent(Finding finding) {
        requireNonNull(finding, "No finding provided");
        var report = CURRENT.get();
        if (report != null) {
            report.add(finding);
        }
    }

    /**
     * @param finding the finding to add; an identical finding already in the report is not added again
     */
    public void add(Finding finding) {
        requireNonNull(finding, "No finding provided");
        synchronized (findings) {
            findings.add(finding);
        }
    }

    /**
     * @return every finding, in the order they were added
     */
    public List<Finding> getFindings() {
        synchronized (findings) {
            return List.copyOf(findings);
        }
    }

    /**
     * @param severity the severity
     * @return the findings of that severity, in the order they were added
     */
    public List<Finding> getFindings(Severity severity) {
        requireNonNull(severity, "No severity provided");
        return getFindings().stream().filter(finding -> finding.severity() == severity).toList();
    }

    /**
     * @return the most serious severity among the findings, or empty when there are none
     */
    public Optional<Severity> getHighestSeverity() {
        return getFindings().stream().map(Finding::severity).min(Comparator.naturalOrder());
    }

    /**
     * Make this the report {@link #current()} on the calling thread until the returned binding is closed - on the same
     * thread. Bindings nest: closing one restores the report that was current before it.
     *
     * @return the binding, to close when start-up is over
     */
    public Binding bindToCurrentThread() {
        var previous = CURRENT.get();
        CURRENT.set(this);
        return new Binding(this, previous, Thread.currentThread());
    }

    /**
     * @return the report as one block: a headline with the counts, then the findings grouped by severity, each with
     * its fix; a single line when there are no findings
     */
    public String render() {
        var all = getFindings();
        if (all.isEmpty()) {
            return "Essentials startup report: no findings";
        }
        var errors   = count(all, Severity.ERROR);
        var warnings = count(all, Severity.WARN);
        var notes    = count(all, Severity.INFO);
        var block = new StringBuilder();
        var banner = "=".repeat(120);
        block.append('\n').append(banner).append('\n')
             .append("Essentials startup report: ")
             .append(plural(errors, "error")).append(", ")
             .append(plural(warnings, "warning")).append(", ")
             .append(plural(notes, "note"))
             .append(" - each was also logged where it was found\n");
        for (var severity : Severity.values()) {
            var ofSeverity = all.stream().filter(finding -> finding.severity() == severity).toList();
            if (ofSeverity.isEmpty()) {
                continue;
            }
            block.append(severity).append(" (").append(ofSeverity.size()).append(")\n");
            for (var finding : ofSeverity) {
                block.append("  - [").append(finding.source()).append("] ").append(finding.message()).append('\n');
                if (!finding.fix().isBlank()) {
                    block.append("    Fix: ").append(finding.fix()).append('\n');
                }
            }
        }
        return block.append(banner).toString();
    }

    /**
     * Log {@link #render()} once: at ERROR when there is an error finding, at WARN when there is a warning, otherwise
     * at INFO (logger {@code dk.trustworks.essentials.components.foundation.startup.EssentialsStartupReport})
     */
    public void log() {
        var rendered = render();
        switch (getHighestSeverity().orElse(Severity.INFO)) {
            case ERROR -> log.error(rendered);
            case WARN -> log.warn(rendered);
            case INFO -> log.info(rendered);
        }
    }

    @Override
    public String toString() {
        var all = getFindings();
        return "EssentialsStartupReport{" + all.stream().collect(Collectors.groupingBy(Finding::severity, TreeMap::new, Collectors.counting())) + "}";
    }

    private static long count(List<Finding> findings, Severity severity) {
        return findings.stream().filter(finding -> finding.severity() == severity).count();
    }

    private static String plural(long count, String noun) {
        return count + " " + noun + (count == 1 ? "" : "s");
    }

    /**
     * A report bound to a thread by {@link #bindToCurrentThread()}. Closing it on the thread it was created on restores
     * the report that was current before; closing it twice, or on another thread, does nothing.
     */
    public static final class Binding implements AutoCloseable {
        private final EssentialsStartupReport report;
        private final EssentialsStartupReport previous;
        private final Thread                  thread;
        private       boolean                 closed;

        private Binding(EssentialsStartupReport report, EssentialsStartupReport previous, Thread thread) {
            this.report = report;
            this.previous = previous;
            this.thread = thread;
        }

        @Override
        public synchronized void close() {
            if (closed || Thread.currentThread() != thread) {
                return;
            }
            closed = true;
            if (CURRENT.get() == report) {
                if (previous == null) {
                    CURRENT.remove();
                } else {
                    CURRENT.set(previous);
                }
            }
        }
    }
}
