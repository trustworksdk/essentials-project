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

package dk.trustworks.essentials.components.foundation.scheduler.pgcron;

import dk.trustworks.essentials.types.CharSequenceType;

/**
 * Represents a cron expression used for scheduling periodic tasks.
 * This class provides a strongly typed wrapper for cron expression strings, improving
 * code readability and type-safety when working with cron-based scheduling systems.
 * <p>
 * A cron expression is a string consisting of five or six fields, separated by white
 * space, representing a set of times defined by the string's syntax. For example,
 * "* * * * *" represents executing a task every minute, and "0 0 * * *" represents
 * executing a task every day at midnight.
 * <p>
 * Defined constants are available for common scheduling intervals:
 * - {@link #ONE_SECOND}: Represents a job scheduled every second.
 * - {@link #TEN_SECOND}: Represents a job scheduled every ten seconds.
 * - {@link #ONE_MINUTE}: Represents a job scheduled every minute.
 * - {@link #ONE_HOUR}: Represents a job scheduled every hour.
 * - {@link #ONE_DAY}: Represents a job scheduled every day.
 */
public class CronExpression extends CharSequenceType<CronExpression> {

    public CronExpression(String value) {
        super(value);
    }

    public static CronExpression of(String value) {
        return new CronExpression(value);
    }

    public static final CronExpression ONE_SECOND = new CronExpression("1 seconds");
    public static final CronExpression TEN_SECOND = new CronExpression("10 seconds");
    public static final CronExpression ONE_MINUTE = new CronExpression("*/1 * * * *");
    public static final CronExpression ONE_HOUR = new CronExpression("0 * * * *");
    public static final CronExpression ONE_DAY = new CronExpression("0 0 * * *");

}
