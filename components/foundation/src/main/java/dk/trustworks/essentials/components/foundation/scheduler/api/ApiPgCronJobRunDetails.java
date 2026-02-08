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

package dk.trustworks.essentials.components.foundation.scheduler.api;

import dk.trustworks.essentials.components.foundation.scheduler.pgcron.PgCronRepository.PgCronJobRunDetails;

import java.time.LocalDateTime;

import static dk.trustworks.essentials.shared.FailFast.requireNonNull;

/**
 * Represents the details of a PostgreSQL cron job run, encapsulating metadata about individual
 * executions of a cron job, including execution identifiers, timing, command details, and
 * status information.
 *
 * @param jobId         the identifier of the cron job
 * @param runId         the unique identifier of the specific execution instance of the cron job
 * @param jobPid        the process ID of the cron job during its execution
 * @param database      the name of the database where the cron job was executed
 * @param username      the username under which the cron job was executed
 * @param command       the command executed as part of the cron job
 * @param status        the status of the execution (e.g., 'success' or 'failure')
 * @param returnMessage the message or output returned by the execution of the cron job
 * @param startTime     the timestamp indicating when the execution of the job started
 * @param endTime       the timestamp indicating when the execution of the job ended
 */
public record ApiPgCronJobRunDetails(
        Integer jobId,
        Integer runId,
        Integer jobPid,
        String database,
        String username,
        String command,
        String status,
        String returnMessage,
        LocalDateTime startTime,
        LocalDateTime endTime
) {

    /**
     * Converts a {@link PgCronJobRunDetails} instance into an {@link ApiPgCronJobRunDetails} instance.
     * This method maps the properties of the provided PgCronJobRunDetails to the corresponding
     * fields of an ApiPgCronJobRunDetails.
     *
     * @param runDetails the PgCronJobRunDetails object to be converted; must not be null
     * @return an ApiPgCronJobRunDetails object representing the provided PgCronJobRunDetails
     * @throws IllegalArgumentException if the provided runDetails is null
     */
    public static ApiPgCronJobRunDetails from(PgCronJobRunDetails runDetails) {
        requireNonNull(runDetails, "runDetails cannot be null");
        return new ApiPgCronJobRunDetails(
                runDetails.jobId(),
                runDetails.runId(),
                runDetails.jobPid(),
                runDetails.database(),
                runDetails.username(),
                runDetails.command(),
                runDetails.status(),
                runDetails.returnMessage(),
                runDetails.startTime().toLocalDateTime(),
                runDetails.endTime().toLocalDateTime()
        );
    }
}
