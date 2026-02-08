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

import dk.trustworks.essentials.components.foundation.scheduler.pgcron.PgCronRepository.PgCronEntry;

import static dk.trustworks.essentials.shared.FailFast.requireNonNull;

/**
 * Represents a PostgreSQL cron job scheduled through the pg_cron extension.
 * This record encapsulates details about the cron job such as its schedule,
 * command, target node, database, activation status, and name.
 *
 * @param jobId     the unique identifier of the cron job
 * @param schedule  the cron expression indicating the job's execution schedule
 * @param command   the command or functionName to be executed
 * @param nodeName  the name of the node where the job will run
 * @param nodePort  the port number of the target node
 * @param database  the database context in which the job executes
 * @param active    a flag indicating whether the job is currently active
 * @param jobName   the identifiable name or alias for the job
 */
public record ApiPgCronJob(
        Integer jobId,
        String schedule,
        String command,
        String nodeName,
        Integer nodePort,
        String database,
        boolean active,
        String jobName
) {

    /**
     * Converts a {@link PgCronEntry} instance into an {@link ApiPgCronJob} instance.
     * This method maps the properties of the given PgCronEntry to the corresponding
     * fields of an ApiPgCronJob.
     *
     * @param pgCronEntry the PgCronEntry object to be converted; must not be null
     * @return an ApiPgCronJob object representing the provided PgCronEntry
     * @throws IllegalArgumentException if the provided PgCronEntry is null
     */
    public static ApiPgCronJob from(PgCronEntry pgCronEntry) {
        requireNonNull(pgCronEntry, "pgCronEntry cannot be null");
        return new ApiPgCronJob(
                pgCronEntry.jobId(),
                pgCronEntry.schedule(),
                pgCronEntry.command(),
                pgCronEntry.nodeName(),
                pgCronEntry.nodePort(),
                pgCronEntry.database(),
                pgCronEntry.active(),
                pgCronEntry.jobName()
        );
    }
}
