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

package dk.trustworks.essentials.components.foundation.scheduler;

import dk.trustworks.essentials.components.foundation.fencedlock.LockName;
import dk.trustworks.essentials.components.foundation.scheduler.executor.*;
import dk.trustworks.essentials.components.foundation.scheduler.executor.ExecutorScheduledJobRepository.ExecutorJobEntry;
import dk.trustworks.essentials.components.foundation.scheduler.pgcron.*;

import java.util.List;

/**
 * Represents a scheduler responsible for scheduling jobs defined by the EssentialsScheduledJob interface.
 * This interface provides a method for scheduling various types of jobs.
 * <p>
 * ** Note: This scheduler is not intended to replace a full-fledged scheduler such as Quartz or Spring, it is a simple
 * scheduler that utilizes the postgresql pg_cron extension if available or a simple ScheduledExecutorService to schedule jobs.
 * It is meant to be used internally by essentials components to schedule jobs.
 * **
 */
public interface EssentialsScheduler {

    /**
     * Schedules a PostgreSQL cron job using the pg_cron extension. The job
     * encapsulates the target database functionName to be executed along with
     * the cron expression that specifies its execution schedule.
     * <p>
     * See {@link PgCronRepository} security note. To mitigate the risk of SQL injection attacks, external or untrusted inputs should never directly provide the {@code  PgCronJob#cronExpression} and {@code  PgCronJob#functionName} values
     *
     * @param job the PgCronJob instance representing the PostgreSQL functionName
     *            and its associated cron schedule to be registered for execution
     */
    void schedulePgCronJob(PgCronJob job);

    /**
     * Schedules a job to be executed periodically using a fixed delay configuration. The job will be added to
     * the list of executor jobs managed by the scheduler. If the scheduler is already started, the job will also
     * be scheduled for execution immediately, using a scheduled executor service.
     *
     * @param job the ExecutorJob to be scheduled, which includes the job's name, the fixed delay configuration
     *            specifying the scheduling parameters (initial delay, period, and time unit), and the task to
     *            be executed
     */
    void scheduleExecutorJob(ExecutorJob job);

    /**
     * Checks if the PostgreSQL `pg_cron` extension is available for use. Requires that the scheduler has been started to provide a valid value.
     * This method determines whether the `pg_cron` extension is present and properly loaded in the PostgreSQL database.
     *
     * @return true if the `pg_cron` extension is available and properly loaded, false otherwise
     */
    boolean isPgCronAvailable();

    /**
     * Retrieves the lock name associated with the current scheduling system.
     *
     * @return a {@code LockName} representing the name of the {@code FencedLock}.
     */
    LockName getLockName();

    /**
     * Fetches a paginated list of entries from the PostgreSQL `pg_cron` table.
     * The `pg_cron` extension must be available and properly configured in the PostgreSQL database for this method to functionName as expected.
     *
     * @param startIndex the starting index of the entries to fetch
     * @param pageSize   the number of entries to fetch per page
     * @return a list of `PgCronEntry` objects representing the `pg_cron` jobs from the database
     */
    List<PgCronRepository.PgCronEntry> fetchPgCronEntries(long startIndex, long pageSize);

    /**
     * Returns the total number of entries present in the PostgreSQL `pg_cron` table.
     * This method relies on the `pg_cron` extension being properly installed and configured
     * in the PostgreSQL database.
     *
     * @return the total count of `pg_cron` entries available in the database
     */
    long getTotalPgCronEntries();

    /**
     * Fetches a paginated list of job run details for a specific PostgreSQL `pg_cron` job.
     * The method retrieves details about individual execution runs of the specified job
     * from the PostgreSQL `pg_cron` extension.
     *
     * @param jobId      the unique identifier of the specific `pg_cron` job for which the
     *                   run details are to be fetched.
     * @param startIndex the starting index of the job run details to fetch
     * @param pageSize   the number of job run details to fetch per page
     * @return a list of `PgCronRepository.PgCronJobRunDetails` objects representing the
     *         job run details for the specified job and pagination parameters
     */
    List<PgCronRepository.PgCronJobRunDetails> fetchPgCronJobRunDetails(Integer jobId, long startIndex, long pageSize);

    /**
     * Returns the total number of job run details available for a specific PostgreSQL `pg_cron` job.
     * This method retrieves the count of all execution records associated with the specified job ID
     * from the PostgreSQL `pg_cron` extension.
     *
     * @param jobId the unique identifier of the specific `pg_cron` job for which the total count
     *              of run details is to be retrieved.
     * @return the total count of job run details available for the specified `pg_cron` job.
     */
    long getTotalPgCronJobRunDetails(Integer jobId);

    /**
     * Fetches a paginated list of executor job entries managed by the scheduler.
     *
     * @param startIndex the starting index of the entries to fetch
     * @param pageSize   the number of entries to fetch per page
     * @return a list of {@code ExecutorScheduledJobRepository.ExecutorJobEntry} objects representing the executor jobs matching the pagination parameters
     */
    List<ExecutorJobEntry> fetchExecutorJobEntries(long startIndex, long pageSize);

    /**
     * Retrieves the total number of executor job entries managed by the scheduler.
     *
     * @return the total count of executor job entries.
     */
    long getTotalExecutorJobEntries();
}
