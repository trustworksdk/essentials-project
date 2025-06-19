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

package dk.trustworks.essentials.components.foundation.scheduler.pgcron;

import dk.trustworks.essentials.components.foundation.scheduler.EssentialsScheduledJob;

import java.util.Objects;

import static dk.trustworks.essentials.shared.FailFast.*;

/**
 * Represents a PostgreSQL cron job scheduled using the pg_cron extension.
 * This immutable record encapsulates the details of a job, including the
 * target function to be executed and the cron expression defining when
 * the job is to be run.
 * Only use the function name without () or any sql, the name is validated with {@link PgCronRepository#FN_NAME}.
 * Function arguments are not supported yet.
 * <pre>
 *      function: sample_db_metrics
 *      cronExpression: *'/'1 * * * *
 * </pre>
 * A PgCronJob implements the {@link EssentialsScheduledJob} interface, allowing
 * it to be used with job schedulers that support this interface.
 *
 * @param name           the name of the job; must not be null.
 * @param function       the name of the function (without () and sql) to be executed as part of the cron job; must not be null.
 * @param cronExpression the cron expression indicating the schedule of the job; must not be null.
 */
public record PgCronJob(String name, String function, CronExpression cronExpression) implements EssentialsScheduledJob {

    public PgCronJob {
        requireNonNull(name, "name cannot be null");
        requireTrue(name.length() <= 50, "name must be <= 50 characters long");
        requireNonNull(function, "function cannot be null");
        requireNonNull(cronExpression, "cronExpression cannot be null");
    }

    @Override
    public boolean equals(Object o) {
        if (o == null || getClass() != o.getClass()) return false;
        PgCronJob pgCronJob = (PgCronJob) o;
        return Objects.equals(name, pgCronJob.name);
    }

    @Override
    public int hashCode() {
        return Objects.hashCode(name);
    }
}
