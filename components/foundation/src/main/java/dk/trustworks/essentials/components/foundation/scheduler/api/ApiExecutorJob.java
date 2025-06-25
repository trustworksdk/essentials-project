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

package dk.trustworks.essentials.components.foundation.scheduler.api;

import dk.trustworks.essentials.components.foundation.scheduler.executor.ExecutorScheduledJobRepository.ExecutorJobEntry;

import java.time.LocalDateTime;
import java.util.concurrent.TimeUnit;

import static dk.trustworks.essentials.shared.FailFast.requireNonNull;

/**
 * Represents an API scheduled executor job.
 * This record contains details about a scheduled job, including its name,
 * the initial delay before execution, the period at which it repeats,
 * the time unit for delay and period, and the scheduled timestamp.
 *
 * @param name         the unique name of the scheduled job
 * @param initialDelay the delay before the job starts executing for the first time
 * @param period       the interval between subsequent executions of the job
 * @param unit         the time unit of the initial delay and period
 * @param scheduledAt  the timestamp indicating when the job execution was scheduled
 */
public record ApiExecutorJob(
        String name,
        long initialDelay,
        long period,
        TimeUnit unit,
        LocalDateTime scheduledAt
) {

    /**
     * Converts an {@link ExecutorJobEntry} instance into an {@link ApiExecutorJob} instance.
     * This method extracts the relevant properties from the given ExecutorJobEntry
     * and uses them to create a new ApiExecutorJob object.
     *
     * @param executorJobEntry the ExecutorJobEntry object to be converted; must not be null
     * @return an ApiExecutorJob object representing the provided ExecutorJobEntry
     * @throws IllegalArgumentException if the provided executorJobEntry is null
     */
    public static ApiExecutorJob from(ExecutorJobEntry executorJobEntry) {
        requireNonNull(executorJobEntry, "executorJobEntry cannot be null");
        return new ApiExecutorJob(
                executorJobEntry.name(),
                executorJobEntry.initialDelay(),
                executorJobEntry.period(),
                executorJobEntry.unit(),
                executorJobEntry.scheduledAt().toLocalDateTime()
        );
    }
}
