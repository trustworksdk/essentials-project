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

package dk.trustworks.essentials.components.foundation.scheduler.executor;


import java.util.concurrent.TimeUnit;

import static dk.trustworks.essentials.shared.FailFast.*;

/**
 * Represents a fixed delay configuration for scheduling tasks. This record defines
 * the parameters required to schedule tasks with an initial delay and a fixed period
 * between subsequent executions.
 * <p>
 * This configuration is used to define scheduling intervals in executors or schedulers.
 *
 * @param initialDelay the initial delay before the task is executed for the first time, in the given time unit.
 * @param period the delay between the end of one execution and the start of the next, in the given time unit.
 * @param unit the time unit for the initial delay and period.
 */
public record FixedDelay(long initialDelay, long period, TimeUnit unit) {

    public FixedDelay {
        requireTrue(initialDelay >= 0, "Initial delay must not be negative");
        requireTrue(period > 0, "Period must not be negative");
        requireNonNull(unit, "TimeUnit must not be null");
    }

    public static FixedDelay ONE_DAY = new FixedDelay(0, 1, TimeUnit.DAYS);
}
