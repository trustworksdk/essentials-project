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

/**
 * Represents a PostgreSQL cron job scheduled using the pg_cron extension.
 * This immutable record encapsulates the details of a job, including the
 * target function to be executed and the cron expression defining when
 * the job is to be run.
 * <pre>
 *      function: sample_db_metrics()
 *      cronExpression: *'/'1 * * * *
 * </pre>
 * A PgCronJob implements the EssentialsScheduledJob interface, allowing
 * it to be used with job schedulers that support this interface.
 *
 * @param function        the name of the function to be executed as part of the cron job
 * @param cronExpression  the cron expression indicating the schedule of the job
 */
public record PgCronJob(String function, CronExpression cronExpression) implements EssentialsScheduledJob {

    @Override
    public String name() {
        return function + "#" + cronExpression.toString();
    }
}
