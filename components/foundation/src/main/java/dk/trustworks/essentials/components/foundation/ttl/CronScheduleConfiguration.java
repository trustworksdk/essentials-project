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

package dk.trustworks.essentials.components.foundation.ttl;

import dk.trustworks.essentials.components.foundation.scheduler.executor.FixedDelay;
import dk.trustworks.essentials.components.foundation.scheduler.pgcron.CronExpression;

import java.time.*;
import java.time.temporal.ChronoUnit;
import java.util.Optional;
import java.util.concurrent.TimeUnit;

import static dk.trustworks.essentials.shared.FailFast.requireNonNull;

/**
 * Represents a schedule configuration based on a cron expression and optionally supplemented
 * by a {@link FixedDelay}. This record encapsulates a {@link CronExpression} and an optional
 * {@link FixedDelay} instance, supporting both cron-based and fixed delay-based scheduling
 * mechanisms.
 * <p>
 * The class implements the {@link ScheduleConfiguration} interface, allowing it to be used
 * interchangeably with other schedule configurations.
 * <p>
 * Example usage:
 * <pre>
 * {@code
 * // Create a CronExpression
 * CronExpression cronExpression = CronExpression.of("0 0 * * *"); // Every day at midnight
 *
 * // Optional fixed delay (can be empty if not required)
 * Optional<FixedDelay> fixedDelay = Optional.of(FixedDelay.ONE_DAY);
 *
 * // Create the CronScheduleConfiguration with both components
 * CronScheduleConfiguration configuration = new CronScheduleConfiguration(cronExpression, fixedDelay);
 *
 * // Convert to FixedDelayScheduleConfiguration if applicable
 * FixedDelayScheduleConfiguration fixedDelayConfig = configuration.toFixedDelay();
 * }
 * </pre>
 *
 * @param cronExpression The cron expression that defines the schedule. Must not be null.
 * @param fixedDelay     Optional fixed delay configuration that can supplement the cron schedule.
 *                       Must not be null, but can be empty.
 */
public record CronScheduleConfiguration(CronExpression cronExpression,
                                        Optional<FixedDelay> fixedDelay) implements ScheduleConfiguration {

    public CronScheduleConfiguration {
        requireNonNull(cronExpression, "cronExpression must not be null");
        requireNonNull(fixedDelay, "fixedDelay must not be null");
    }

    /**
     * Represents a schedule configuration based on a cron expression and optionally supplemented
     * by a {@link FixedDelay}. This record encapsulates a {@link CronExpression}
     * <p>
     * The class implements the {@link ScheduleConfiguration} interface, allowing it to be used
     * interchangeably with other schedule configurations.
     * <p>
     * Example usage:
     * <pre>
     * {@code
     * // Create a CronExpression
     * CronExpression cronExpression = CronExpression.of("0 0 * * *"); // Every day at midnight
     *
     * // Create the CronScheduleConfiguration with both components
     * CronScheduleConfiguration configuration = new CronScheduleConfiguration(cronExpression);
     *
     * // Convert to FixedDelayScheduleConfiguration if applicable
     * FixedDelayScheduleConfiguration fixedDelayConfig = configuration.toFixedDelay();
     * }
     * </pre>
     *
     * @param cronExpression The cron expression that defines the schedule. Must not be null.
     */
    public CronScheduleConfiguration(CronExpression cronExpression) {
        this(cronExpression, Optional.empty());
    }


    public FixedDelayScheduleConfiguration toFixedDelayConfiguration() {
        var cronValue = cronExpression.toString().trim();
        Long periodMillis = null;
        Long initialDelayMillis = null;

        if (cronValue.matches("\\d+\\s*seconds?")) {
            var seconds = Integer.parseInt(cronValue.split("\\s+")[0]);
            periodMillis = seconds * 1000L;
            initialDelayMillis = periodMillis; // Wait one period before first execution
        } else if (cronValue.matches("\\d+\\s*minutes?")) {
            var minutes = Integer.parseInt(cronValue.split("\\s+")[0]);
            periodMillis = minutes * 60 * 1000L;
            initialDelayMillis = periodMillis; // Wait one period before first execution
        } else if (cronValue.matches("\\d+\\s*hours?")) {
            var hours = Integer.parseInt(cronValue.split("\\s+")[0]);
            periodMillis = hours * 3600 * 1000L;
            initialDelayMillis = periodMillis; // Wait one period before first execution
        } else if (cronValue.matches("\\d+\\s*days?")) {
            var days = Integer.parseInt(cronValue.split("\\s+")[0]);
            periodMillis = days * 24 * 3600 * 1000L;
            initialDelayMillis = periodMillis; // Wait one period before first execution
        } else if (cronValue.matches("\\*/\\d+\\s+\\*\\s+\\*\\s+\\*\\s+\\*")) {
            // Every N minutes: */N * * * *
            var minutes = Integer.parseInt(cronValue.split("\\s+")[0].substring(2));
            periodMillis = minutes * 60 * 1000L;
            initialDelayMillis = periodMillis; // Wait one period before first execution
        } else if (cronValue.matches("0\\s+\\*/\\d+\\s+\\*\\s+\\*\\s+\\*")) {
            // Every N hours: 0 */N * * *
            var hours = Integer.parseInt(cronValue.split("\\s+")[1].substring(2));
            periodMillis = hours * 3600 * 1000L;
            // For hourly patterns, align to next hour boundary
            var now = ZonedDateTime.now(ZoneId.systemDefault());
            var nextHour = now.truncatedTo(ChronoUnit.HOURS).plusHours(1);
            initialDelayMillis = Duration.between(now, nextHour).toMillis();
        } else if (cronValue.matches("0\\s+0\\s+\\*/\\d+\\s+\\*\\s+\\*")) {
            // Every N days: 0 0 */N * *
            var days = Integer.parseInt(cronValue.split("\\s+")[2].substring(2));
            periodMillis = days * 24 * 3600 * 1000L;
            // For daily patterns, align to next midnight
            var now = ZonedDateTime.now(ZoneId.systemDefault());
            var nextMidnight = now.truncatedTo(ChronoUnit.DAYS).plusDays(1);
            initialDelayMillis = Duration.between(now, nextMidnight).toMillis();
        } else if (cronValue.split("\\s+").length == 5) {
            var fiveFieldCron = parseFiveFieldCron(cronValue);
            if (fiveFieldCron != null) {
                periodMillis = fiveFieldCron.periodMillis;
                initialDelayMillis = fiveFieldCron.initialDelayMillis;
            }
        }

        if (periodMillis == null || initialDelayMillis == null) {
            throw new IllegalArgumentException(
                    String.format("Unable to parse cron expression '%s' to fixed delay", cronValue)
            );
        }

        return new FixedDelayScheduleConfiguration(
                new FixedDelay(initialDelayMillis, periodMillis, TimeUnit.MILLISECONDS)
        );
    }

    private static ParsedFixedDelay parseFiveFieldCron(String cronValue) {
        var parts = cronValue.split("\\s+");
        var minute = parts[0];
        var hour = parts[1];
        var dayOfMonth = parts[2];
        var month = parts[3];
        var dayOfWeek = parts[4];
        var zoneId = ZoneId.systemDefault();
        var now = ZonedDateTime.now(zoneId);

        if (!"*".equals(month) || !"*".equals(dayOfWeek)) {
            return null;
        }

        // Every N minutes: */N * * * *
        if (minute.startsWith("*/") && "*".equals(hour) && "*".equals(dayOfMonth)) {
            var n = parsePositiveInt(minute.substring(2));
            if (n == null) return null;

            var next = now.truncatedTo(ChronoUnit.MINUTES).plusMinutes(1);
            while (next.getMinute() % n != 0) {
                next = next.plusMinutes(1);
            }
            return new ParsedFixedDelay(Duration.ofMinutes(n).toMillis(), millisUntil(now, next));
        }

        // Every hour at minute M: M * * * *
        if (isMinute(minute) && "*".equals(hour) && "*".equals(dayOfMonth)) {
            var minuteOfHour = Integer.parseInt(minute);
            var next = now.truncatedTo(ChronoUnit.HOURS).withMinute(minuteOfHour);
            if (!next.isAfter(now)) {
                next = next.plusHours(1);
            }
            return new ParsedFixedDelay(Duration.ofHours(1).toMillis(), millisUntil(now, next));
        }

        // Every N hours at minute M: M */N * * *
        if (isMinute(minute) && hour.startsWith("*/") && "*".equals(dayOfMonth)) {
            var minuteOfHour = Integer.parseInt(minute);
            var everyHours = parsePositiveInt(hour.substring(2));
            if (everyHours == null) return null;

            var next = now.truncatedTo(ChronoUnit.HOURS).withMinute(minuteOfHour);
            while (!next.isAfter(now) || (next.getHour() % everyHours != 0)) {
                next = next.plusHours(1).withMinute(minuteOfHour);
            }
            return new ParsedFixedDelay(Duration.ofHours(everyHours).toMillis(), millisUntil(now, next));
        }

        // Daily at HH:MM: M H * * *
        if (isMinute(minute) && isHour(hour) && "*".equals(dayOfMonth)) {
            var minuteOfHour = Integer.parseInt(minute);
            var hourOfDay = Integer.parseInt(hour);

            var next = now.truncatedTo(ChronoUnit.DAYS).withHour(hourOfDay).withMinute(minuteOfHour);
            if (!next.isAfter(now)) {
                next = next.plusDays(1);
            }
            return new ParsedFixedDelay(Duration.ofDays(1).toMillis(), millisUntil(now, next));
        }

        // Every N days at HH:MM: M H */N * *
        if (isMinute(minute) && isHour(hour) && dayOfMonth.startsWith("*/")) {
            var minuteOfHour = Integer.parseInt(minute);
            var hourOfDay = Integer.parseInt(hour);
            var everyDays = parsePositiveInt(dayOfMonth.substring(2));
            if (everyDays == null) return null;

            var next = now.truncatedTo(ChronoUnit.DAYS).withHour(hourOfDay).withMinute(minuteOfHour);
            if (!next.isAfter(now)) {
                next = next.plusDays(1);
            }
            while (((next.getDayOfMonth() - 1) % everyDays) != 0) {
                next = next.plusDays(1);
            }
            return new ParsedFixedDelay(Duration.ofDays(everyDays).toMillis(), millisUntil(now, next));
        }

        return null;
    }

    private static boolean isMinute(String value) {
        if (!value.matches("\\d+")) return false;
        var minute = Integer.parseInt(value);
        return minute >= 0 && minute <= 59;
    }

    private static boolean isHour(String value) {
        if (!value.matches("\\d+")) return false;
        var hour = Integer.parseInt(value);
        return hour >= 0 && hour <= 23;
    }

    private static Integer parsePositiveInt(String value) {
        if (!value.matches("\\d+")) return null;
        var parsed = Integer.parseInt(value);
        if (parsed <= 0) return null;
        return parsed;
    }

    private static long millisUntil(ZonedDateTime now, ZonedDateTime next) {
        return Duration.between(now, next).toMillis();
    }

    private record ParsedFixedDelay(long periodMillis, long initialDelayMillis) {
    }

}
