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
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.Optional;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.*;

public class CronScheduleConfigurationTest {

    @Test
    void testSecondsPattern() {
        CronScheduleConfiguration config = new CronScheduleConfiguration(
                 CronExpression.of("10 seconds"),
                Optional.empty()
        );
        FixedDelayScheduleConfiguration fdc = config.toFixedDelayConfiguration();
        FixedDelay fd = fdc.fixedDelay();

        long expected = 10 * 1000L;
        assertThat(fd.unit()).as("time unit").isEqualTo(TimeUnit.MILLISECONDS);
        assertThat(fd.period()).as("period").isEqualTo(expected);
        assertThat(fd.initialDelay()).as("initial delay").isEqualTo(expected);
    }

    @Test
    void testMinutesPattern() {
        CronScheduleConfiguration config = new CronScheduleConfiguration(
                 CronExpression.of("5 minutes"),
                Optional.empty()
        );
        FixedDelayScheduleConfiguration fdc = config.toFixedDelayConfiguration();
        FixedDelay fd = fdc.fixedDelay();

        long expected = 5 * 60 * 1000L;
        assertThat(fd.unit()).as("time unit").isEqualTo(TimeUnit.MILLISECONDS);
        assertThat(fd.period()).as("period").isEqualTo(expected);
        assertThat(fd.initialDelay()).as("initial delay").isEqualTo(expected);
    }

    @Test
    void testHoursPattern() {
        CronScheduleConfiguration config = new CronScheduleConfiguration(
                CronExpression.of("2 hours"),
                Optional.empty()
        );
        FixedDelayScheduleConfiguration fdc = config.toFixedDelayConfiguration();
        FixedDelay fd = fdc.fixedDelay();

        long expected = 2 * 3600 * 1000L;
        assertThat(fd.unit()).as("time unit").isEqualTo(TimeUnit.MILLISECONDS);
        assertThat(fd.period()).as("period").isEqualTo(expected);
        assertThat(fd.initialDelay()).as("initial delay").isEqualTo(expected);
    }

    @Test
    void testDaysPattern() {
        CronScheduleConfiguration config = new CronScheduleConfiguration(
                new CronExpression("3 days"),
                Optional.empty()
        );
        FixedDelayScheduleConfiguration fdc = config.toFixedDelayConfiguration();
        FixedDelay fd = fdc.fixedDelay();

        long expected = 3 * 24 * 3600 * 1000L;
        assertThat(fd.unit()).as("time unit").isEqualTo(TimeUnit.MILLISECONDS);
        assertThat(fd.period()).as("period").isEqualTo(expected);
        assertThat(fd.initialDelay()).as("initial delay").isEqualTo(expected);
    }

    @Test
    void testSlashPattern() {
        CronScheduleConfiguration config = new CronScheduleConfiguration(
                CronExpression.of("*/15 * * * *"),
                Optional.empty()
        );
        FixedDelayScheduleConfiguration fdc = config.toFixedDelayConfiguration();
        FixedDelay fd = fdc.fixedDelay();

        long expected = 15 * 60 * 1000L;
        assertThat(fd.unit()).as("time unit").isEqualTo(TimeUnit.MILLISECONDS);
        assertThat(fd.period()).as("period").isEqualTo(expected);
        assertThat(fd.initialDelay()).as("initial delay").isEqualTo(expected);
    }

    @Test
    void testHourlyCronPattern() {
        CronScheduleConfiguration config = new CronScheduleConfiguration(
                CronExpression.of("0 * * * *"),
                Optional.empty()
        );
        FixedDelayScheduleConfiguration fdc = config.toFixedDelayConfiguration();
        FixedDelay                      fd  = fdc.fixedDelay();

        long expectedPeriod = Duration.ofHours(1).toMillis();
        assertThat(fd.unit()).as("time unit").isEqualTo(TimeUnit.MILLISECONDS);
        assertThat(fd.period()).as("period").isEqualTo(expectedPeriod);
        assertThat(fd.initialDelay())
                .as("initial delay should be within (0, period]")
                .isGreaterThan(0)
                .isLessThanOrEqualTo(expectedPeriod);
    }

    @Test
    void testDailyCronPattern() {
        CronScheduleConfiguration config = new CronScheduleConfiguration(
                CronExpression.of("0 0 * * *"),
                Optional.empty()
        );
        FixedDelayScheduleConfiguration fdc = config.toFixedDelayConfiguration();
        FixedDelay fd = fdc.fixedDelay();

        long expectedPeriod = Duration.ofDays(1).toMillis();
        assertThat(fd.unit()).as("time unit").isEqualTo(TimeUnit.MILLISECONDS);
        assertThat(fd.period()).as("period").isEqualTo(expectedPeriod);
        assertThat(fd.initialDelay())
                .as("initial delay should be within (0, period]")
                .isGreaterThan(0)
                .isLessThanOrEqualTo(expectedPeriod);
    }

    @Test
    void testInvalidPatternThrows() {
        CronScheduleConfiguration config = new CronScheduleConfiguration(
                CronExpression.of("invalid"),
                Optional.empty()
        );
        assertThatThrownBy(config::toFixedDelayConfiguration)
                .isInstanceOf(IllegalArgumentException.class);
    }

}
