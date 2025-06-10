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

import java.util.List;

/**
 * Interface defining operations for interacting with scheduled jobs and their execution details.
 * Provides methods to retrieve PostgreSQL cron jobs, their execution details, and API executor jobs.
 */
public interface SchedulerApi {

    /**
     * Retrieves a paginated list of PostgreSQL cron jobs associated with the provided principal.
     *
     * @param principal  the principal object representing the user or entity requesting the jobs
     * @param startIndex the index of the first job to retrieve in the paginated result
     * @param pageSize   the maximum number of jobs to include in the result
     * @return a list of {@link ApiPgCronJob} instances representing the PostgreSQL cron jobs
     */
    List<ApiPgCronJob> getPgCronJobs(Object principal, long startIndex, long pageSize);

    /**
     * Retrieves the total number of PostgreSQL cron jobs associated with the provided principal.
     *
     * @param principal the principal object representing the user or entity querying for cron jobs
     * @return the total count of PostgreSQL cron jobs associated with the given principal
     */
    long getTotalPgCronJobs(Object principal);

    /**
     * Retrieves a paginated list of execution details for a specific PostgreSQL cron job.
     * Each execution detail encapsulates metadata about an individual run, such as the command executed,
     * execution timing, status, and additional information.
     *
     * @param principal  the principal object representing the user or entity requesting the execution details
     * @param jobId      the unique identifier of the PostgreSQL cron job whose run details are to be retrieved
     * @param startIndex the index of the first execution detail to retrieve in the paginated result
     * @param pageSize   the maximum number of execution details to include in the result
     * @return a list of {@link ApiPgCronJobRunDetails} instances representing the details of individual cron job executions
     */
    List<ApiPgCronJobRunDetails> getPgCronJobRunDetails(Object principal, Integer jobId, long startIndex, long pageSize);

    /**
     * Retrieves the total number of execution details for a specific PostgreSQL cron job.
     *
     * @param principal the principal object representing the user or entity querying for cron job run details
     * @param jobId     the unique identifier of the PostgreSQL cron job for which the total run details are being requested
     * @return the total count of execution details associated with the specified PostgreSQL cron job
     */
    long getTotalPgCronJobRunDetails(Object principal, Integer jobId);

    /**
     * Retrieves a paginated list of API executor jobs associated with the provided principal.
     * Each API executor job contains details about a scheduled job, including its name,
     * execution timing, and scheduled timestamp.
     *
     * @param principal  the principal object representing the user or entity requesting the jobs
     * @param startIndex the index of the first job to retrieve in the paginated result
     * @param pageSize   the maximum number of jobs to include in the result
     * @return a list of {@link ApiExecutorJob} instances representing the API executor jobs
     */
    List<ApiExecutorJob> getExecutorJobs(Object principal, long startIndex, long pageSize);

    /**
     * Retrieves the total number of executor jobs associated with the provided principal.
     *
     * @param principal the principal object representing the user or entity querying for executor jobs
     * @return the total count of executor jobs associated with the given principal
     */
    long getTotalExecutorJobs(Object principal);
}
