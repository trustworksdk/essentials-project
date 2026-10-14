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

import java.time.*;
import java.util.*;
import java.util.stream.Collectors;

import static dk.trustworks.essentials.shared.FailFast.*;

/**
 * Collects the findings of the Essentials checks, so they can be printed as one block once the application is up, and
 * again - complete, with how often each was seen - when it stops (0.60.1). Each check keeps logging where it always did
 * - an application without a report loses nothing - and also calls {@link #addToCurrent(Finding)}.
 * <p>
 * <b>Where {@link #addToCurrent(Finding)} puts a finding.</b>
 * <ol>
 *     <li>Into the report {@link #bindToCurrentThread() bound} to the calling thread, if there is one. The Spring Boot
 *     starters create a report per application context and bind it to the thread refreshing that context for the
 *     duration of the refresh - which is where the start-up checks run (bean creation, lifecycle start). A check deep
 *     inside a library (a builder, a processor's {@code start()}) therefore needs no reference to the report and no new
 *     constructor parameter.</li>
 *     <li>Otherwise - a finding raised after start-up or on another thread, such as an enqueue outside a
 *     {@code UnitOfWork} or a processor started later - into the <em>run report</em>: the one report
 *     {@link #registerAsRunReport() registered} in this JVM, <b>only when exactly one is registered</b>. The Spring Boot
 *     starters register each context's report once its refresh is done and deregister it when the context closes.</li>
 *     <li>Otherwise nowhere: the check's own log line is the only trace. That is the case with no report registered
 *     (outside Spring, unless you register one), and with more than one: a test JVM starts many Spring contexts, often
 *     at the same time, and a finding that belongs to none of them in particular must not land in an arbitrary one.
 *     There is deliberately no JVM-wide report.</li>
 * </ol>
 * <p>
 * <b>Identical findings are kept once</b> ({@link Finding} equality: severity, source, message and fix), with how often,
 * when first and when last each was seen ({@link #getOccurrences()}). At most {@link #DEFAULT_MAX_DISTINCT_FINDINGS}
 * distinct findings are kept (see {@link #EssentialsRunReport(int)}); occurrences of further distinct findings are
 * only counted ({@link #getOverflowCount()}), so a noisy runtime finding cannot grow memory without bound.
 * <p>
 * <b>Three ways to print it.</b> {@link #log()} prints the start-up block. {@link #logNewFindings()} prints only the
 * findings not printed before - the Spring Boot starters call it periodically, so findings raised at runtime are seen
 * while the application runs. {@link #logRunReport()} prints every finding of the run with its counts - the Spring Boot
 * starters call it as the context closes. A shutdown log can be lost (the process is killed after SIGTERM, the log
 * shipper is already gone), which is why the periodic one exists.
 * <p>
 * Without Spring, create a report, bind it around your own start-up, {@link #log()} it, and register it for the run:
 * <pre>{@code
 * var report = new EssentialsRunReport();
 * try (var binding = report.bindToCurrentThread()) {
 *     // build and start the Essentials components
 * }
 * report.log();
 * var registration = report.registerAsRunReport();   // later findings, from any thread, reach this report
 * scheduler.scheduleWithFixedDelay(report::logNewFindings, 5, 5, TimeUnit.MINUTES);   // optional
 * // ... and on shutdown
 * report.logRunReport();
 * registration.close();
 * }</pre>
 * The report is thread-safe.
 */
public final class EssentialsRunReport {
    /**
     * The number of distinct findings a report created with {@link #EssentialsRunReport()} keeps
     */
    public static final int DEFAULT_MAX_DISTINCT_FINDINGS = 500;

    /**
     * How many periods without a run report a report lists one by one. Package-private for tests.
     */
    static final int MAX_UNCOLLECTED_PERIODS = 20;

    private static final Logger                                log        = LoggerFactory.getLogger(EssentialsRunReport.class);
    /**
     * The bindings of the calling thread, innermost first
     */
    private static final ThreadLocal<Deque<Binding>>       CURRENT    = new ThreadLocal<>();
    /**
     * Every report {@link #registerAsRunReport() registered}, by identity, with how many open registrations it has.
     * Guarded by itself.
     */
    private static final Map<EssentialsRunReport, Integer> REGISTERED = new IdentityHashMap<>();
    /**
     * The only registered report, or {@code null} when none or more than one is registered - what
     * {@link #addToCurrent(Finding)} falls back to. Recomputed under {@link #REGISTERED}'s lock.
     */
    private static volatile EssentialsRunReport            runReport;

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

    /**
     * How often a {@link Finding} was added to a report, and when
     *
     * @param finding   the finding
     * @param count     how many times it was added, at least 1
     * @param firstSeen when it was first added
     * @param lastSeen  when it was last added
     */
    public record Occurrences(Finding finding, long count, Instant firstSeen, Instant lastSeen) {
        public Occurrences {
            requireNonNull(finding, "No finding provided");
            requireNonNull(firstSeen, "No firstSeen provided");
            requireNonNull(lastSeen, "No lastSeen provided");
        }
    }

    private final Object              lock     = new Object();
    /**
     * Guarded by {@link #lock}; in the order the findings were first added
     */
    private final Map<Finding, Tally> findings = new LinkedHashMap<>();
    private final int                 maxDistinctFindings;
    private final Clock               clock;
    /**
     * Occurrences of findings not kept because {@link #maxDistinctFindings} was reached. Guarded by {@link #lock}.
     */
    private       long                overflowCount;
    /**
     * {@link #overflowCount} when the report was last printed. Guarded by {@link #lock}.
     */
    private       long                overflowCountPrinted;
    /**
     * The periods in which this report was registered but not the run report, because another was too, oldest first,
     * at most {@link #MAX_UNCOLLECTED_PERIODS}: once full, the first ones and the latest one. Guarded by {@link #lock}.
     */
    private final List<UncollectedPeriod> uncollected = new ArrayList<>();
    /**
     * How many ended periods were dropped from between the first kept ones and the latest, because
     * {@link #uncollected} was full, and the span they lie in. Guarded by {@link #lock}.
     */
    private       long                    droppedUncollected;
    private       Instant                 droppedUncollectedFrom;
    private       Instant                 droppedUncollectedUntil;
    /**
     * The period under way, or {@code null}. Guarded by {@link #lock}.
     */
    private       UncollectedPeriod       openUncollected;
    /**
     * Bumped when {@link #uncollectedPeriods()} changes; {@link #uncollectedVersionPrinted} is its value when the report
     * was last printed. Guarded by {@link #lock}.
     */
    private       long                    uncollectedVersion;
    private       long                    uncollectedVersionPrinted;

    /**
     * A report that keeps up to {@link #DEFAULT_MAX_DISTINCT_FINDINGS} distinct findings
     */
    public EssentialsRunReport() {
        this(DEFAULT_MAX_DISTINCT_FINDINGS);
    }

    /**
     * @param maxDistinctFindings how many distinct findings to keep, at least 1; occurrences of further distinct
     *                            findings are only counted, see {@link #getOverflowCount()}
     */
    public EssentialsRunReport(int maxDistinctFindings) {
        this(maxDistinctFindings, Clock.systemUTC());
    }

    /**
     * Package-private for tests
     *
     * @param maxDistinctFindings how many distinct findings to keep
     * @param clock               the clock stamping first and last seen
     */
    EssentialsRunReport(int maxDistinctFindings, Clock clock) {
        requireTrue(maxDistinctFindings >= 1, "maxDistinctFindings must be at least 1");
        this.maxDistinctFindings = maxDistinctFindings;
        this.clock = requireNonNull(clock, "No clock provided");
    }

    /**
     * @return the report bound to the current thread by {@link #bindToCurrentThread()}, if any. Does not fall back to
     * the {@link #runReport() run report}.
     */
    public static Optional<EssentialsRunReport> current() {
        return Optional.ofNullable(currentOrNull());
    }

    private static EssentialsRunReport currentOrNull() {
        var bindings  = CURRENT.get();
        var innermost = bindings == null ? null : bindings.peekFirst();
        return innermost == null ? null : innermost.report;
    }

    /**
     * @return the report {@link #addToCurrent(Finding)} falls back to when none is bound to the calling thread: the
     * report {@link #registerAsRunReport() registered} in this JVM when exactly one is, otherwise empty
     */
    public static Optional<EssentialsRunReport> runReport() {
        return Optional.ofNullable(runReport);
    }

    /**
     * Add the finding to the report bound to the current thread; when none is bound, to the {@link #runReport() run
     * report} - the one report registered in this JVM; when there is none, or more than one, do nothing. Never throws
     * on a non-null finding - a check must not fail because of its report.
     *
     * @param finding the finding
     */
    public static void addToCurrent(Finding finding) {
        requireNonNull(finding, "No finding provided");
        var report = currentOrNull();
        if (report == null) {
            report = runReport;
        }
        if (report != null) {
            report.add(finding);
        }
    }

    /**
     * Make this the report {@link #addToCurrent(Finding)} falls back to on any thread with no report bound - for as long
     * as it is the only one registered in this JVM - until the returned registration is closed. Registering a report
     * that is already registered is counted: it stays registered until every registration it was given is closed.
     * <p>
     * When a second report is registered the run report is gone - runtime findings are no longer collected by any
     * report. That is logged once at INFO, and each registered report records the period and says so in
     * {@link #renderRunReport()} and {@link #logNewFindings()}, so that its silence is not read as health.
     *
     * @return the registration, to close when the application stops
     */
    public Registration registerAsRunReport() {
        List<EssentialsRunReport> competing = null;
        synchronized (REGISTERED) {
            var before = REGISTERED.size();
            REGISTERED.merge(this, 1, Integer::sum);
            recomputeRunReport(null);
            if (before == 1 && REGISTERED.size() == 2) {
                competing = List.copyOf(REGISTERED.keySet());
            }
        }
        if (competing != null) {
            log.info("More than one Essentials run report is registered ({}) - runtime findings (raised after start-up) are only logged by their checks, "
                     + "not collected, until only one is left", competing.stream().map(EssentialsRunReport::identity).collect(Collectors.joining(" and ")));
        }
        return new Registration(this);
    }

    private String identity() {
        return "EssentialsRunReport@" + Integer.toHexString(System.identityHashCode(this));
    }

    /**
     * @return whether this report is {@link #registerAsRunReport() registered} - which does not make it the
     * {@link #runReport() run report} while another one is registered too
     */
    public boolean isRegisteredAsRunReport() {
        synchronized (REGISTERED) {
            return REGISTERED.containsKey(this);
        }
    }

    private static void deregister(EssentialsRunReport report) {
        synchronized (REGISTERED) {
            REGISTERED.computeIfPresent(report, (key, registrations) -> registrations > 1 ? registrations - 1 : null);
            recomputeRunReport(REGISTERED.containsKey(report) ? null : report);
        }
    }

    /**
     * Callers hold {@link #REGISTERED}'s lock
     *
     * @param deregistered the report that just left {@link #REGISTERED}, if any - its open uncollected period ends
     */
    private static void recomputeRunReport(EssentialsRunReport deregistered) {
        var registered = REGISTERED.size();
        runReport = registered == 1 ? REGISTERED.keySet().iterator().next() : null;
        if (deregistered != null) {
            deregistered.endUncollectedPeriod();
        }
        for (var report : REGISTERED.keySet()) {
            if (registered > 1) {
                report.startUncollectedPeriod(registered);
            } else {
                report.endUncollectedPeriod();
            }
        }
    }

    private void startUncollectedPeriod(int registered) {
        var now = clock.instant();
        synchronized (lock) {
            var previous = openUncollected;
            if (openUncollected != null) {
                openUncollected = openUncollected.withRegistered(Math.max(registered, openUncollected.registered()));
            } else {
                openUncollected = new UncollectedPeriod(now, null, registered);
            }
            if (!openUncollected.equals(previous)) {
                uncollectedVersion++;
            }
        }
    }

    private void endUncollectedPeriod() {
        var now = clock.instant();
        synchronized (lock) {
            if (openUncollected == null) {
                return;
            }
            var ended = openUncollected.withEnd(now);
            openUncollected = null;
            if (uncollected.size() == MAX_UNCOLLECTED_PERIODS) {
                // Keep the first periods and the latest, each as it was, and only count the ones in between - merging
                // two periods would claim findings were not collected in the interval between them, when they were
                var dropped = uncollected.removeLast();
                if (droppedUncollected == 0) {
                    droppedUncollectedFrom = dropped.from();
                }
                droppedUncollectedUntil = dropped.until();
                droppedUncollected++;
            }
            uncollected.add(ended);
            uncollectedVersion++;
        }
    }

    /**
     * Callers hold {@link #lock}
     *
     * @return one description per period, oldest first, with the dropped periods summarised in their place
     */
    private List<String> uncollectedPeriods() {
        var all = new ArrayList<String>();
        for (var i = 0; i < uncollected.size(); i++) {
            if (droppedUncollected > 0 && i == uncollected.size() - 1) {
                all.add("runtime findings were also not collected in " + plural(droppedUncollected, "more period") + " between " + droppedUncollectedFrom
                        + " and " + droppedUncollectedUntil + ", not listed - the report lists at most " + MAX_UNCOLLECTED_PERIODS + " periods");
            }
            all.add(describe(uncollected.get(i)));
        }
        if (openUncollected != null) {
            all.add(describe(openUncollected));
        }
        return all;
    }

    private static String describe(UncollectedPeriod period) {
        return "runtime findings were not collected from " + period.from() + " "
               + (period.until() == null ? "until now" : "until " + period.until())
               + ": " + period.registered() + " run reports registered, so none was the run report";
    }

    /**
     * @param finding the finding to add; an identical finding already in the report is not added again - its count and
     *                last-seen time are updated instead
     */
    public void add(Finding finding) {
        requireNonNull(finding, "No finding provided");
        var now = clock.instant();
        synchronized (lock) {
            var tally = findings.get(finding);
            if (tally != null) {
                tally.count++;
                tally.lastSeen = now;
            } else if (findings.size() < maxDistinctFindings) {
                findings.put(finding, new Tally(now));
            } else {
                overflowCount++;
            }
        }
    }

    /**
     * @return every distinct finding, in the order they were first added
     */
    public List<Finding> getFindings() {
        synchronized (lock) {
            return List.copyOf(findings.keySet());
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
     * @return every distinct finding with how often and when it was seen, in the order they were first added
     */
    public List<Occurrences> getOccurrences() {
        synchronized (lock) {
            return snapshot(false);
        }
    }

    /**
     * @return how many times a finding was added but not kept, because the report already held its maximum number of
     * distinct findings - each was still logged by its check
     */
    public long getOverflowCount() {
        synchronized (lock) {
            return overflowCount;
        }
    }

    /**
     * @return the most serious severity among the findings, or empty when there are none
     */
    public Optional<Severity> getHighestSeverity() {
        return getFindings().stream().map(Finding::severity).min(Comparator.naturalOrder());
    }

    /**
     * Make this the report {@link #current()} on the calling thread until the returned binding is closed - on the same
     * thread. Bindings nest: closing one restores the report that was current before it - also when they are closed out of order,
     * the report of the innermost binding still open is then the current one.
     *
     * @return the binding, to close when start-up is over
     */
    public Binding bindToCurrentThread() {
        var bindings = CURRENT.get();
        if (bindings == null) {
            bindings = new ArrayDeque<>();
            CURRENT.set(bindings);
        }
        var binding = new Binding(this, Thread.currentThread());
        bindings.addFirst(binding);
        return binding;
    }

    /**
     * @return the start-up block {@link #log()} logs: a headline with the counts, then the findings grouped by
     * severity, each with its fix; a single line when there are no findings
     */
    public String render() {
        synchronized (lock) {
            return render(snapshot(false), overflowCount, false, "Essentials startup report: ", "", "no findings", List.of());
        }
    }

    /**
     * @return the block {@link #logRunReport()} logs: every finding of the run so far, grouped by severity, each with
     * its fix and how often and when it was seen; a single line when there are no findings
     */
    public String renderRunReport() {
        synchronized (lock) {
            return render(snapshot(false), overflowCount, true, "Essentials run report: ", " over the whole run", "no findings over the whole run",
                          uncollectedPeriods());
        }
    }

    /**
     * Log {@link #render()} once: at ERROR when there is an error finding, at WARN when there is a warning, otherwise
     * at INFO (logger {@code dk.trustworks.essentials.components.foundation.startup.EssentialsRunReport}). Marks
     * every finding as printed, so {@link #logNewFindings()} does not repeat it.
     */
    public void log() {
        String   rendered;
        Severity level;
        synchronized (lock) {
            rendered = render();
            level = highestSeverity(snapshot(false)).orElse(Severity.INFO);
            markAllPrinted();
        }
        logAt(level, rendered);
    }

    /**
     * Log the findings not printed before - by {@link #log()}, {@link #logRunReport()} or an earlier call - as one
     * block, at the highest severity among them, each with how often and when it was seen; log nothing when there is
     * nothing new. Meant to be called periodically while the application runs.
     *
     * @return whether anything was logged
     */
    public boolean logNewFindings() {
        String   rendered;
        Severity level;
        synchronized (lock) {
            var newFindings = snapshot(true);
            var newOverflow = overflowCount - overflowCountPrinted;
            if (newFindings.isEmpty() && newOverflow == 0 && uncollectedVersion == uncollectedVersionPrinted) {
                return false;
            }
            rendered = render(newFindings, newOverflow, true,
                              "Essentials report, " + plural(newFindings.size(), "new finding") + " since the last report: ", "", "",
                              uncollectedVersion == uncollectedVersionPrinted ? List.of() : uncollectedPeriods());
            level = highestSeverity(newFindings).orElse(newOverflow > 0 ? Severity.WARN : Severity.INFO);
            markAllPrinted();
        }
        logAt(level, rendered);
        return true;
    }

    /**
     * Log {@link #renderRunReport()} - every finding of the run, each with how often and when it was seen - once, at
     * ERROR when there is an error finding, at WARN when there is a warning, otherwise at INFO. Meant to be called as
     * the application stops. A shutdown log can be lost (the process is killed, the log shipper has already stopped),
     * which is what the periodic {@link #logNewFindings()} is for.
     */
    public void logRunReport() {
        String   rendered;
        Severity level;
        synchronized (lock) {
            rendered = renderRunReport();
            level = highestSeverity(snapshot(false)).orElse(Severity.INFO);
            markAllPrinted();
        }
        logAt(level, rendered);
    }

    @Override
    public String toString() {
        var all = getFindings();
        return "EssentialsRunReport{" + all.stream().collect(Collectors.groupingBy(Finding::severity, TreeMap::new, Collectors.counting())) + "}";
    }

    /**
     * Callers hold {@link #lock}
     */
    private List<Occurrences> snapshot(boolean onlyNotPrinted) {
        return findings.entrySet().stream()
                       .filter(entry -> !onlyNotPrinted || !entry.getValue().printed)
                       .map(entry -> entry.getValue().toOccurrences(entry.getKey()))
                       .toList();
    }

    /**
     * Callers hold {@link #lock}
     */
    private void markAllPrinted() {
        findings.values().forEach(tally -> tally.printed = true);
        overflowCountPrinted = overflowCount;
        uncollectedVersionPrinted = uncollectedVersion;
    }

    private static Optional<Severity> highestSeverity(List<Occurrences> occurrences) {
        return occurrences.stream().map(occurrence -> occurrence.finding().severity()).min(Comparator.naturalOrder());
    }

    private static void logAt(Severity level, String rendered) {
        switch (level) {
            case ERROR -> log.error(rendered);
            case WARN -> log.warn(rendered);
            case INFO -> log.info(rendered);
        }
    }

    private String render(List<Occurrences> occurrences, long overflow, boolean withOccurrences, String headline, String scope, String empty, List<String> notCollected) {
        if (occurrences.isEmpty() && overflow == 0) {
            return notCollected.isEmpty()
                   ? headline + empty
                   : headline + (empty.isEmpty() ? "" : empty + ", but ") + String.join("; ", notCollected);
        }
        var block  = new StringBuilder();
        var banner = "=".repeat(120);
        block.append('\n').append(banner).append('\n')
             .append(headline)
             .append(plural(count(occurrences, Severity.ERROR), "error")).append(", ")
             .append(plural(count(occurrences, Severity.WARN), "warning")).append(", ")
             .append(plural(count(occurrences, Severity.INFO), "note"))
             .append(scope)
             .append(" - each was also logged where it was found\n");
        for (var severity : Severity.values()) {
            var ofSeverity = occurrences.stream().filter(occurrence -> occurrence.finding().severity() == severity).toList();
            if (ofSeverity.isEmpty()) {
                continue;
            }
            block.append(severity).append(" (").append(ofSeverity.size()).append(")\n");
            for (var occurrence : ofSeverity) {
                var finding = occurrence.finding();
                block.append("  - [").append(finding.source()).append("] ").append(finding.message()).append('\n');
                if (!finding.fix().isBlank()) {
                    block.append("    Fix: ").append(finding.fix()).append('\n');
                }
                if (withOccurrences) {
                    if (occurrence.count() == 1) {
                        block.append("    Seen once, at ").append(occurrence.firstSeen()).append('\n');
                    } else {
                        block.append("    Seen ").append(occurrence.count()).append(" times, first at ").append(occurrence.firstSeen())
                             .append(", last at ").append(occurrence.lastSeen()).append('\n');
                    }
                }
            }
        }
        if (overflow > 0) {
            block.append("Plus ").append(plural(overflow, "occurrence")).append(" of findings not kept - the report holds at most ")
                 .append(maxDistinctFindings).append(" distinct findings\n");
        }
        for (var period : notCollected) {
            block.append("Note: ").append(period).append('\n');
        }
        return block.append(banner).toString();
    }

    private static long count(List<Occurrences> occurrences, Severity severity) {
        return occurrences.stream().filter(occurrence -> occurrence.finding().severity() == severity).count();
    }

    private static String plural(long count, String noun) {
        return count + " " + noun + (count == 1 ? "" : "s");
    }

    /**
     * A period in which a registered report was not the run report. {@code until} is null while it lasts.
     */
    private record UncollectedPeriod(Instant from, Instant until, int registered) {
        UncollectedPeriod withEnd(Instant until) {
            return new UncollectedPeriod(from, until, registered);
        }

        UncollectedPeriod withRegistered(int registered) {
            return new UncollectedPeriod(from, until, registered);
        }
    }

    /**
     * The mutable counters of one finding. Guarded by the report's lock.
     */
    private static final class Tally {
        private final Instant firstSeen;
        private       Instant lastSeen;
        private       long    count = 1;
        private       boolean printed;

        private Tally(Instant firstSeen) {
            this.firstSeen = firstSeen;
            this.lastSeen = firstSeen;
        }

        private Occurrences toOccurrences(Finding finding) {
            return new Occurrences(finding, count, firstSeen, lastSeen);
        }
    }

    /**
     * A report registered by {@link #registerAsRunReport()}. Closing it deregisters the report; closing it twice does
     * nothing more.
     */
    public static final class Registration implements AutoCloseable {
        private final EssentialsRunReport report;
        private       boolean                 closed;

        private Registration(EssentialsRunReport report) {
            this.report = report;
        }

        @Override
        public synchronized void close() {
            if (!closed) {
                closed = true;
                deregister(report);
            }
        }
    }

    /**
     * A report bound to a thread by {@link #bindToCurrentThread()}. Closing it on the thread it was created on restores
     * the report that was current before; closing it twice, or on another thread, does nothing.
     */
    public static final class Binding implements AutoCloseable {
        private final EssentialsRunReport report;
        private final Thread                  thread;
        private       boolean                 closed;

        private Binding(EssentialsRunReport report, Thread thread) {
            this.report = report;
            this.thread = thread;
        }

        @Override
        public synchronized void close() {
            if (closed || Thread.currentThread() != thread) {
                return;
            }
            closed = true;
            var bindings = CURRENT.get();
            if (bindings != null) {
                bindings.removeFirstOccurrence(this);
                if (bindings.isEmpty()) {
                    CURRENT.remove();
                }
            }
        }
    }
}
