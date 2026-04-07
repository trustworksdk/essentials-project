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

package dk.trustworks.essentials.components.eventsourced.aggregates.closingbooks;

import java.time.*;
import java.time.format.DateTimeFormatter;
import java.time.temporal.IsoFields;
import java.time.temporal.TemporalAdjusters;
import java.util.Optional;

import static dk.trustworks.essentials.shared.FailFast.requireNonNull;

/**
 * Resolves the current time-based closing-books period for a configured cadence.
 * <p>
 * The returned value is intended to be a stable, comparable period identifier that
 * aggregates can persist as part of their generation state. Time-based rollovers can
 * therefore safely skip across gaps by comparing the aggregate's current period id to
 * the period id derived from the current clock value.
 */
public final class ClosingBooksTimeBoundaryCalculator {
    private static final DateTimeFormatter DAY_FORMATTER = DateTimeFormatter.ISO_LOCAL_DATE;
    private static final DateTimeFormatter MONTH_FORMATTER = DateTimeFormatter.ofPattern("yyyy-MM");
    private static final DateTimeFormatter YEAR_FORMATTER = DateTimeFormatter.ofPattern("yyyy");

    private ClosingBooksTimeBoundaryCalculator() {
    }

    /**
     * Resolves the current period ID based on the provided time boundary, zone, clock,
     * current period ID, and interval days. This method evaluates the time boundary
     * and determines the appropriate period ID for the current context.
     *
     * @param timeBoundary the time boundary defining the rollover rules (e.g., end of day, week, month, etc.)
     * @param zoneId the time zone to be used for calculations
     * @param clock the clock representing the current time
     * @param currentPeriodId the ID of the current period being evaluated
     * @param intervalDays the interval in days for fixed interval calculations (nullable, only used when timeBoundary is EVERY_N_DAYS)
     * @return the resolved period ID based on the provided parameters
     */
    public static String resolveCurrentPeriodId(ClosingBooksTimeBoundary timeBoundary,
                                                ZoneId zoneId,
                                                Clock clock,
                                                String currentPeriodId,
                                                Integer intervalDays) {
        return evaluate(timeBoundary, zoneId, clock, currentPeriodId, intervalDays).resolvedPeriodId();
    }

    /**
     * Evaluates the provided time boundary against the current clock time and determines the resulting
     * period ID and the number of advanced periods since the current period ID.
     *
     * @param timeBoundary the time boundary defining the rollover rules (e.g., end of day, week, month, etc.)
     * @param zoneId the time zone to use for date and time calculations
     * @param clock the clock representing the current time
     * @param currentPeriodId the ID of the currently active period to evaluate against
     * @param intervalDays the interval in days for fixed interval calculations;
     *                     applicable only when the time boundary is set to EVERY_N_DAYS
     * @return an instance of {@link ClosingBooksTimeBoundaryEvaluation} containing the resolved period ID
     *         and the count of advanced periods relative to the current period ID
     */
    public static ClosingBooksTimeBoundaryEvaluation evaluate(ClosingBooksTimeBoundary timeBoundary,
                                                              ZoneId zoneId,
                                                              Clock clock,
                                                              String currentPeriodId,
                                                              Integer intervalDays) {
        requireNonNull(timeBoundary, "No timeBoundary provided");
        requireNonNull(zoneId, "No zoneId provided");
        requireNonNull(clock, "No clock provided");

        var now = LocalDate.now(clock.withZone(zoneId));
        return switch (timeBoundary) {
            case NONE -> new ClosingBooksTimeBoundaryEvaluation(currentPeriodId, 0);
            case END_OF_DAY -> new ClosingBooksTimeBoundaryEvaluation(now.format(DAY_FORMATTER),
                                                                      dayDistance(currentPeriodId, now));
            case END_OF_WEEK -> new ClosingBooksTimeBoundaryEvaluation(formatIsoWeek(now),
                                                                       weekDistance(currentPeriodId, now));
            case END_OF_MONTH -> new ClosingBooksTimeBoundaryEvaluation(YearMonth.from(now).format(MONTH_FORMATTER),
                                                                        monthDistance(currentPeriodId, now));
            case END_OF_YEAR -> new ClosingBooksTimeBoundaryEvaluation(now.format(YEAR_FORMATTER),
                                                                       yearDistance(currentPeriodId, now));
            case EVERY_N_DAYS -> resolveFixedIntervalEvaluation(now, currentPeriodId, intervalDays);
        };
    }

    static String resolveFixedIntervalPeriodId(LocalDate now, String currentPeriodId, Integer intervalDays) {
        return resolveFixedIntervalEvaluation(now, currentPeriodId, intervalDays).resolvedPeriodId();
    }

    static ClosingBooksTimeBoundaryEvaluation resolveFixedIntervalEvaluation(LocalDate now, String currentPeriodId, Integer intervalDays) {
        if (intervalDays == null || intervalDays <= 0) {
            throw new IllegalArgumentException("intervalDays must be > 0 for EVERY_N_DAYS");
        }

        var anchor = parseIsoLocalDate(currentPeriodId).orElse(now);
        if (now.isBefore(anchor)) {
            return new ClosingBooksTimeBoundaryEvaluation(anchor.format(DAY_FORMATTER), 0);
        }

        var elapsedDays = Duration.between(anchor.atStartOfDay(), now.atStartOfDay()).toDays();
        var periodOffset = elapsedDays / intervalDays;
        return new ClosingBooksTimeBoundaryEvaluation(anchor.plusDays(periodOffset * intervalDays).format(DAY_FORMATTER),
                                                      periodOffset);
    }

    private static String formatIsoWeek(LocalDate date) {
        var weekStart = date.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY));
        var weekBasedYear = weekStart.get(IsoFields.WEEK_BASED_YEAR);
        var week = weekStart.get(IsoFields.WEEK_OF_WEEK_BASED_YEAR);
        return "%d-W%02d".formatted(weekBasedYear, week);
    }

    private static Optional<LocalDate> parseIsoLocalDate(String value) {
        if (value == null || value.isBlank()) {
            return Optional.empty();
        }

        try {
            return Optional.of(LocalDate.parse(value, DAY_FORMATTER));
        } catch (DateTimeException ignored) {
            return Optional.empty();
        }
    }

    private static long dayDistance(String currentPeriodId, LocalDate now) {
        return parseIsoLocalDate(currentPeriodId)
                .map(current -> Math.max(0, Duration.between(current.atStartOfDay(), now.atStartOfDay()).toDays()))
                .orElse(0L);
    }

    private static long weekDistance(String currentPeriodId, LocalDate now) {
        if (currentPeriodId == null || currentPeriodId.isBlank()) {
            return 0;
        }
        try {
            var pieces = currentPeriodId.split("-W");
            if (pieces.length != 2) {
                return 0;
            }
            var weekBasedYear = Integer.parseInt(pieces[0]);
            var week = Integer.parseInt(pieces[1]);
            var currentWeekStart = LocalDate.of(weekBasedYear, 1, 4)
                                            .with(IsoFields.WEEK_OF_WEEK_BASED_YEAR, week)
                                            .with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY));
            var nowWeekStart = now.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY));
            return Math.max(0, Duration.between(currentWeekStart.atStartOfDay(), nowWeekStart.atStartOfDay()).toDays() / 7);
        } catch (RuntimeException ignored) {
            return 0;
        }
    }

    private static long monthDistance(String currentPeriodId, LocalDate now) {
        if (currentPeriodId == null || currentPeriodId.isBlank()) {
            return 0;
        }
        try {
            var current = YearMonth.parse(currentPeriodId, MONTH_FORMATTER);
            return Math.max(0, current.until(YearMonth.from(now), java.time.temporal.ChronoUnit.MONTHS));
        } catch (RuntimeException ignored) {
            return 0;
        }
    }

    private static long yearDistance(String currentPeriodId, LocalDate now) {
        if (currentPeriodId == null || currentPeriodId.isBlank()) {
            return 0;
        }
        try {
            var current = Integer.parseInt(currentPeriodId);
            return Math.max(0, now.getYear() - current);
        } catch (RuntimeException ignored) {
            return 0;
        }
    }
}
