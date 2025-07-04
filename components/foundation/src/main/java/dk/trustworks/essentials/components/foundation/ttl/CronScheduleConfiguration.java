/*
 * Copyright 2021-2025 the original author or authors.
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
        long periodMillis;
        long initialDelayMillis;

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
        } else if ("0 * * * *".equals(cronValue)) {
            // Every hour at minute 0
            var now = ZonedDateTime.now(ZoneId.systemDefault());
            var nextHour = now.truncatedTo(ChronoUnit.HOURS).plusHours(1);
            initialDelayMillis = Duration.between(now, nextHour).toMillis();
            periodMillis = Duration.ofHours(1).toMillis();
        } else if ("0 0 * * *".equals(cronValue) || "0 0 0 * *".equals(cronValue)) {
            // Every day at midnight
            var now = ZonedDateTime.now(ZoneId.systemDefault());
            var nextMidnight = now.truncatedTo(ChronoUnit.DAYS).plusDays(1);
            initialDelayMillis = Duration.between(now, nextMidnight).toMillis();
            periodMillis = Duration.ofDays(1).toMillis();
        } else {
            throw new IllegalArgumentException(
                    String.format("Unable to parse cron expression '%s' to fixed delay", cronValue)
            );
        }

        return new FixedDelayScheduleConfiguration(
                new FixedDelay(initialDelayMillis, periodMillis, TimeUnit.MILLISECONDS)
        );
    }

}