package dk.trustworks.essentials.components.foundation.ttl;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.Optional;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.*;

public class CronScheduleConfigurationTest {

    @Test
    void testSecondsPattern() {
        CronScheduleConfiguration config = new CronScheduleConfiguration(
                new CronExpression("10 seconds"),
                Optional.empty()
        );
        FixedDelayScheduleConfiguration fdc = config.toFixedDelay();
        FixedDelay fd = fdc.fixedDelay();

        long expected = 10 * 1000L;
        assertThat(fd.getTimeUnit()).as("time unit").isEqualTo(TimeUnit.MILLISECONDS);
        assertThat(fd.getPeriod()).as("period").isEqualTo(expected);
        assertThat(fd.getInitialDelay()).as("initial delay").isEqualTo(expected);
    }

    @Test
    void testMinutesPattern() {
        CronScheduleConfiguration config = new CronScheduleConfiguration(
                new CronExpression("5 minutes"),
                Optional.empty()
        );
        FixedDelayScheduleConfiguration fdc = config.toFixedDelay();
        FixedDelay fd = fdc.fixedDelay();

        long expected = 5 * 60 * 1000L;
        assertThat(fd.getTimeUnit()).as("time unit").isEqualTo(TimeUnit.MILLISECONDS);
        assertThat(fd.getPeriod()).as("period").isEqualTo(expected);
        assertThat(fd.getInitialDelay()).as("initial delay").isEqualTo(expected);
    }

    @Test
    void testHoursPattern() {
        CronScheduleConfiguration config = new CronScheduleConfiguration(
                new CronExpression("2 hours"),
                Optional.empty()
        );
        FixedDelayScheduleConfiguration fdc = config.toFixedDelay();
        FixedDelay fd = fdc.fixedDelay();

        long expected = 2 * 3600 * 1000L;
        assertThat(fd.getTimeUnit()).as("time unit").isEqualTo(TimeUnit.MILLISECONDS);
        assertThat(fd.getPeriod()).as("period").isEqualTo(expected);
        assertThat(fd.getInitialDelay()).as("initial delay").isEqualTo(expected);
    }

    @Test
    void testDaysPattern() {
        CronScheduleConfiguration config = new CronScheduleConfiguration(
                new CronExpression("3 days"),
                Optional.empty()
        );
        FixedDelayScheduleConfiguration fdc = config.toFixedDelay();
        FixedDelay fd = fdc.fixedDelay();

        long expected = 3 * 24 * 3600 * 1000L;
        assertThat(fd.getTimeUnit()).as("time unit").isEqualTo(TimeUnit.MILLISECONDS);
        assertThat(fd.getPeriod()).as("period").isEqualTo(expected);
        assertThat(fd.getInitialDelay()).as("initial delay").isEqualTo(expected);
    }

    @Test
    void testSlashPattern() {
        CronScheduleConfiguration config = new CronScheduleConfiguration(
                new CronExpression("*/15 * * * *"),
                Optional.empty()
        );
        FixedDelayScheduleConfiguration fdc = config.toFixedDelay();
        FixedDelay fd = fdc.fixedDelay();

        long expected = 15 * 60 * 1000L;
        assertThat(fd.getTimeUnit()).as("time unit").isEqualTo(TimeUnit.MILLISECONDS);
        assertThat(fd.getPeriod()).as("period").isEqualTo(expected);
        assertThat(fd.getInitialDelay()).as("initial delay").isEqualTo(expected);
    }

    @Test
    void testHourlyCronPattern() {
        CronScheduleConfiguration config = new CronScheduleConfiguration(
                new CronExpression("0 * * * *"),
                Optional.empty()
        );
        FixedDelayScheduleConfiguration fdc = config.toFixedDelay();
        FixedDelay fd = fdc.fixedDelay();

        long expectedPeriod = Duration.ofHours(1).toMillis();
        assertThat(fd.getTimeUnit()).as("time unit").isEqualTo(TimeUnit.MILLISECONDS);
        assertThat(fd.getPeriod()).as("period").isEqualTo(expectedPeriod);
        assertThat(fd.getInitialDelay())
                .as("initial delay should be within (0, period]")
                .isGreaterThan(0)
                .isLessThanOrEqualTo(expectedPeriod);
    }

    @Test
    void testDailyCronPattern() {
        CronScheduleConfiguration config = new CronScheduleConfiguration(
                new CronExpression("0 0 * * *"),
                Optional.empty()
        );
        FixedDelayScheduleConfiguration fdc = config.toFixedDelay();
        FixedDelay fd = fdc.fixedDelay();

        long expectedPeriod = Duration.ofDays(1).toMillis();
        assertThat(fd.getTimeUnit()).as("time unit").isEqualTo(TimeUnit.MILLISECONDS);
        assertThat(fd.getPeriod()).as("period").isEqualTo(expectedPeriod);
        assertThat(fd.getInitialDelay())
                .as("initial delay should be within (0, period]")
                .isGreaterThan(0)
                .isLessThanOrEqualTo(expectedPeriod);
    }

    @Test
    void testInvalidPatternThrows() {
        CronScheduleConfiguration config = new CronScheduleConfiguration(
                new CronExpression("invalid"),
                Optional.empty()
        );
        assertThatThrownBy(config::toFixedDelay)
                .isInstanceOf(IllegalArgumentException.class);
    }

}
