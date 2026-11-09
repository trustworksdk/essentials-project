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

package dk.trustworks.essentials.examples.trading._demo_harness.demo_control;

import dk.trustworks.essentials.examples.trading.integration.BusinessCalendar;
import dk.trustworks.essentials.examples.trading.integration.ExchangeClosingBooksCalendar;
import dk.trustworks.essentials.examples.trading.integration.exchange_simulator.client.ApiClient;
import dk.trustworks.essentials.examples.trading.integration.exchange_simulator.client.api.*;
import dk.trustworks.essentials.examples.trading.integration.exchange_simulator.client.model.*;
import jakarta.annotation.PreDestroy;
import org.slf4j.*;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicReference;

import static dk.trustworks.essentials.shared.FailFast.requireNonNull;

/**
 * The exchange's clock, as the operator page drives it (phase 8 §3, D31, D32): pause, resume and speed through the
 * simulator's operator endpoints, and jumps forward - to just before the next open or close, or over a number of business
 * days - through its test endpoint.
 * <p>
 * A jump is a <em>fast-forward</em>, never a set: the clock is moved to just past each close in turn, and the next step
 * waits until the application has recorded that close ({@link ExchangeClosingBooksCalendar}), so every close in between
 * happens as it would have - day orders expire, the books roll, trades settle, deposits clear, reconciliation runs.
 * The clock only moves forward; going back means resetting the stack.
 * <p>
 * The exchange's session and cutoff are its configuration ({@code simulators/exchange.yaml}): 09:30-16:00, cutoff 16:30,
 * New York. They are repeated here because the exchange publishes the cutoff ({@code GET /api/calendar}) but not its
 * session hours.
 */
@Component
@ConditionalOnProperty(prefix = "trading-demo.simulators", name = {"exchange.base-url", "webhook-secret-file"})
public class ExchangeClockControl {
    private static final Logger    log           = LoggerFactory.getLogger(ExchangeClockControl.class);
    static final         ZoneId    NEW_YORK      = ZoneId.of("America/New_York");
    static final         LocalTime SESSION_OPEN  = LocalTime.of(9, 30);
    static final         LocalTime SESSION_CLOSE = LocalTime.of(16, 0);
    static final         LocalTime CUTOFF        = LocalTime.of(16, 30);
    /**
     * A jump stops this long before the open or close it aims at, so the audience sees it happen.
     */
    static final         Duration  LEAD          = Duration.ofMinutes(5);
    private static final Duration  CLOSE_TIMEOUT = Duration.ofSeconds(60);
    private static final Set<BigDecimal> SPEEDS  = Set.of(BigDecimal.ONE, BigDecimal.valueOf(10), BigDecimal.valueOf(60), BigDecimal.valueOf(300), BigDecimal.valueOf(1440));

    public enum Target {
        NEXT_OPEN,
        NEXT_CLOSE
    }

    /**
     * A jump in progress or the last one.
     *
     * @param description what it does, in words
     * @param closesDone  closes passed so far
     * @param closesTotal closes it passes in all
     * @param state       {@code RUNNING}, {@code DONE} or {@code FAILED}
     * @param message     what it is doing now, or why it failed
     */
    public record Job(String description, int closesDone, int closesTotal, String state, String message) {
        boolean running() {
            return state.equals("RUNNING");
        }
    }

    /**
     * The clock as the page shows it.
     */
    public record ClockState(Instant now,
                             LocalDateTime newYorkTime,
                             boolean running,
                             BigDecimal speed,
                             String phase,
                             String phaseText,
                             LocalDate openBusinessDate,
                             LocalDate lastClosedBusinessDate,
                             Job job) {
    }

    private final ClockApi                     operatorClock;
    private final TestControlApi               testControl;
    private final BusinessCalendar             businessCalendar;
    private final ExchangeClosingBooksCalendar closes;
    private final ExecutorService              jobs = Executors.newSingleThreadExecutor(Thread.ofPlatform().name("demo-clock-jump").daemon().factory());
    private final AtomicReference<Job>         job  = new AtomicReference<>();

    public ExchangeClockControl(ApiClient exchangeSimulatorApiClient,
                                BusinessCalendar businessCalendar,
                                ExchangeClosingBooksCalendar closes) {
        requireNonNull(exchangeSimulatorApiClient, "No exchangeSimulatorApiClient provided");
        this.operatorClock = new ClockApi(exchangeSimulatorApiClient);
        this.testControl = new TestControlApi(exchangeSimulatorApiClient);
        this.businessCalendar = requireNonNull(businessCalendar, "No businessCalendar provided");
        this.closes = requireNonNull(closes, "No closes provided");
    }

    public ClockState state() {
        var clock = operatorClock.getOperatorClock();
        var now   = clock.getNow().toInstant();
        var local = LocalDateTime.ofInstant(now, NEW_YORK);
        var phase = phase(now);
        return new ClockState(now,
                              local,
                              Boolean.TRUE.equals(clock.getRunning()),
                              clock.getSpeed(),
                              phase,
                              phaseText(phase, local),
                              closes.openBusinessDate().orElse(null),
                              closes.latestClosedBusinessDate().orElse(null),
                              job.get());
    }

    public void pause() {
        requireNoJob();
        operatorClock.freezeOperatorClock();
    }

    public void resume() {
        requireNoJob();
        operatorClock.resumeOperatorClock();
    }

    public void speed(BigDecimal speed) {
        requireNonNull(speed, "No speed provided");
        if (SPEEDS.stream().noneMatch(allowed -> allowed.compareTo(speed) == 0)) {
            throw new IllegalArgumentException("speed must be one of 1, 10, 60, 300, 1440, was " + speed);
        }
        operatorClock.setOperatorClockSpeed(new SetOperatorClockSpeedRequest().speed(speed));
    }

    /**
     * Jumps to {@link #LEAD} before the next session open or close, through every close in between.
     */
    public Job skip(Target target) {
        requireNonNull(target, "No target provided");
        var now = now();
        var to  = (target == Target.NEXT_OPEN ? nextSessionTime(now, SESSION_OPEN) : nextSessionTime(now, SESSION_CLOSE)).minus(LEAD);
        var description = target == Target.NEXT_OPEN ? "Skipping to 5 minutes before the next open" : "Skipping to 5 minutes before the next close";
        return start(description, to);
    }

    /**
     * Runs the next {@code businessDays} closes, then stops {@link #LEAD} before the following open.
     */
    public Job advance(int businessDays) {
        if (businessDays < 1 || businessDays > 20) {
            throw new IllegalArgumentException("businessDays must be 1 to 20, was " + businessDays);
        }
        var now       = now();
        var lastClose = nextCutoff(now);
        for (var i = 1; i < businessDays; i++) {
            lastClose = nextCutoff(lastClose.plusSeconds(1));
        }
        var to = nextSessionTime(lastClose, SESSION_OPEN).minus(LEAD);
        return start("Advancing " + businessDays + " business day" + (businessDays == 1 ? "" : "s"), to);
    }

    private synchronized Job start(String description, Instant to) {
        requireNoJob();
        var total   = countCutoffs(now(), to);
        var started = new Job(description, 0, total, "RUNNING", "Starting");
        job.set(started);
        jobs.execute(() -> run(started, to));
        return started;
    }

    private void run(Job started, Instant to) {
        var wasRunning = Boolean.TRUE.equals(operatorClock.getOperatorClock().getRunning());
        try {
            if (wasRunning) {
                operatorClock.freezeOperatorClock(); // so the clock stays where each step puts it
            }
            var done = 0;
            while (true) {
                var now    = now();
                var cutoff = nextCutoff(now);
                if (!cutoff.isBefore(to)) {
                    advanceBy(Duration.between(now, to));
                    break;
                }
                var businessDate = LocalDate.ofInstant(cutoff, NEW_YORK);
                job.set(new Job(started.description(), done, started.closesTotal(), "RUNNING",
                                "Day " + (done + 1) + " of " + started.closesTotal() + ": closing " + businessDate + ", waiting for the close to be processed"));
                advanceBy(Duration.between(now, cutoff.plus(Duration.ofMinutes(1))));
                awaitClose(businessDate);
                done++;
            }
            job.set(new Job(started.description(), done, started.closesTotal(), "DONE", "Done: " + LocalDateTime.ofInstant(to, NEW_YORK).toLocalTime() + " New York"));
        } catch (RuntimeException e) {
            log.warn("Clock jump '{}' failed", started.description(), e);
            job.set(new Job(started.description(), job.get().closesDone(), started.closesTotal(), "FAILED", e.getMessage()));
        } finally {
            if (wasRunning) {
                try {
                    operatorClock.resumeOperatorClock();
                } catch (RuntimeException e) {
                    log.warn("Could not resume the exchange's clock after a jump", e);
                }
            }
        }
    }

    private void advanceBy(Duration duration) {
        if (duration.isNegative() || duration.isZero()) {
            return;
        }
        // Rounded up, so a jump never lands a fraction of a second short of its target
        var seconds = duration.toSeconds() + (duration.toNanosPart() > 0 ? 1 : 0);
        testControl.advanceClock(new ClockAdvance().days((int) (seconds / 86_400))
                                                   .hours((int) (seconds % 86_400 / 3_600))
                                                   .minutes((int) (seconds % 3_600 / 60))
                                                   .seconds((int) (seconds % 60)));
    }

    private void awaitClose(LocalDate businessDate) {
        var deadline = System.nanoTime() + CLOSE_TIMEOUT.toNanos();
        while (closes.latestClosedBusinessDate().map(closed -> closed.isBefore(businessDate)).orElse(true)) {
            if (System.nanoTime() > deadline) {
                throw new IllegalStateException("The close of " + businessDate + " was not recorded within " + CLOSE_TIMEOUT.toSeconds() + " s");
            }
            try {
                Thread.sleep(100);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("Interrupted waiting for the close of " + businessDate, e);
            }
        }
    }

    private void requireNoJob() {
        var current = job.get();
        if (current != null && current.running()) {
            throw new IllegalStateException("A clock jump is running: " + current.description());
        }
    }

    private Instant now() {
        return operatorClock.getOperatorClock().getNow().toInstant();
    }

    /**
     * Pre-open, open, after the close (until the cutoff), closed for the night, or a day with no trading.
     */
    String phase(Instant now) {
        var local = LocalDateTime.ofInstant(now, NEW_YORK);
        if (!isBusinessDay(local.toLocalDate())) {
            return "NO_TRADING_DAY";
        }
        var time = local.toLocalTime();
        if (!time.isBefore(SESSION_OPEN) && time.isBefore(SESSION_CLOSE)) {
            return "OPEN";
        }
        if (time.isBefore(SESSION_OPEN)) {
            return "PRE_OPEN";
        }
        return time.isBefore(CUTOFF) ? "AFTER_CLOSE" : "CLOSED";
    }

    private static String phaseText(String phase, LocalDateTime local) {
        var at = local.toLocalTime().withSecond(0).withNano(0) + " New York, " + local.getDayOfWeek().toString().charAt(0)
                + local.getDayOfWeek().toString().substring(1).toLowerCase() + " " + local.toLocalDate();
        return switch (phase) {
            case "OPEN" -> "Market open - " + at;
            case "PRE_OPEN" -> "Before the open - " + at + ". The market opens at 09:30";
            case "AFTER_CLOSE" -> "After the close - " + at + ". The business day ends at the 16:30 cutoff";
            case "CLOSED" -> "Closed for the night - " + at + ". The business day has closed";
            default -> "No trading today - " + at + " (weekend or holiday)";
        };
    }

    boolean isBusinessDay(LocalDate date) {
        return businessCalendar.businessDaysAfter(date.minusDays(1), 1).map(date::equals)
                               .orElse(date.getDayOfWeek() != DayOfWeek.SATURDAY && date.getDayOfWeek() != DayOfWeek.SUNDAY);
    }

    /**
     * The next business day's cutoff strictly after {@code after}.
     */
    Instant nextCutoff(Instant after) {
        var date = LocalDate.ofInstant(after, NEW_YORK);
        for (var i = 0; i < 30; i++, date = date.plusDays(1)) {
            var cutoff = date.atTime(CUTOFF).atZone(NEW_YORK).toInstant();
            if (isBusinessDay(date) && cutoff.isAfter(after)) {
                return cutoff;
            }
        }
        throw new IllegalStateException("No business day within 30 days of " + after);
    }

    /**
     * The next time {@code time} New York on a business day, strictly after {@code after} plus the {@link #LEAD}.
     */
    Instant nextSessionTime(Instant after, LocalTime time) {
        var date = LocalDate.ofInstant(after, NEW_YORK);
        for (var i = 0; i < 30; i++, date = date.plusDays(1)) {
            var at = date.atTime(time).atZone(NEW_YORK).toInstant();
            if (isBusinessDay(date) && at.minus(LEAD).isAfter(after)) {
                return at;
            }
        }
        throw new IllegalStateException("No business day within 30 days of " + after);
    }

    private int countCutoffs(Instant from, Instant to) {
        var count  = 0;
        var cutoff = nextCutoff(from);
        while (cutoff.isBefore(to)) {
            count++;
            cutoff = nextCutoff(cutoff);
        }
        return count;
    }

    @PreDestroy
    public void stop() {
        jobs.shutdownNow();
    }
}
