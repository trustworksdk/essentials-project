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

import java.time.*;
import java.time.temporal.ChronoUnit;
import java.util.concurrent.TimeUnit;

import static dk.trustworks.essentials.shared.FailFast.requireNonNull;

public record CronScheduleConfiguration(CronExpression cronExpression,
                                        Optional<FixedDelay> fixedDelay) implements ScheduleConfiguration {

    public CronScheduleConfiguration {
        requireNonNull(cronExpression, "cronExpression must not be null");
        requireNonNull(fixedDelay, "fixedDelay must not be null");
    }

    public FixedDelayScheduleConfiguration toFixedDelay() {
        String cronValue = cronExpression.toString().trim();
        long periodMillis;
        long initialDelayMillis;

        if (cronValue.matches("\\d+\\s*seconds?")) {
            int seconds = Integer.parseInt(cronValue.split("\\s+")[0]);
            periodMillis = seconds * 1000L;
            initialDelayMillis = periodMillis;
        } else if (cronValue.matches("\\d+\\s*minutes?")) {
            int minutes = Integer.parseInt(cronValue.split("\\s+")[0]);
            periodMillis = minutes * 60 * 1000L;
            initialDelayMillis = periodMillis;
        } else if (cronValue.matches("\\d+\\s*hours?")) {
            int hours = Integer.parseInt(cronValue.split("\\s+")[0]);
            periodMillis = hours * 3600 * 1000L;
            initialDelayMillis = periodMillis;
        } else if (cronValue.matches("\\d+\\s*days?")) {
            int days = Integer.parseInt(cronValue.split("\\s+")[0]);
            periodMillis = days * 24 * 3600 * 1000L;
            initialDelayMillis = periodMillis;
        } else if (cronValue.matches("\\*/\\d+\\s+\\*\\s+\\*\\s+\\*\\s+\\*")) {
            int minutes = Integer.parseInt(cronValue.split("\\s+")[0].substring(2));
            periodMillis = minutes * 60 * 1000L;
            initialDelayMillis = periodMillis;
        } else if ("0 * * * *".equals(cronValue)) {
            ZonedDateTime now      = ZonedDateTime.now(ZoneId.systemDefault());
            ZonedDateTime nextHour = now.truncatedTo(ChronoUnit.HOURS).plusHours(1);
            initialDelayMillis = Duration.between(now, nextHour).toMillis();
            periodMillis = Duration.ofHours(1).toMillis();
        } else if ("0 0 * * *".equals(cronValue)) {
            ZonedDateTime now = ZonedDateTime.now(ZoneId.systemDefault());
            ZonedDateTime nextMidnight = now.truncatedTo(ChronoUnit.DAYS).plusDays(1);
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
}
