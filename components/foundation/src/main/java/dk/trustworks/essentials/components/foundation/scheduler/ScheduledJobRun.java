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


package dk.trustworks.essentials.components.foundation.scheduler;

import java.time.*;

import static dk.trustworks.essentials.shared.FailFast.requireNonNull;

/**
 * The outcome of running a scheduled job on demand with {@link EssentialsScheduler#runJobNow(String)}.
 *
 * @param jobName   the job's registered name
 * @param jobType   whether it is an executor job or a pg_cron job
 * @param startedAt when the run started
 * @param duration  how long the run took
 * @param succeeded whether the job completed without throwing - or, for a pg_cron job, without its function
 *                  raising an error
 * @param error     the exception type and message when the run failed, otherwise {@code null}
 */
public record ScheduledJobRun(String jobName,
                              JobType jobType,
                              OffsetDateTime startedAt,
                              Duration duration,
                              boolean succeeded,
                              String error) {

    public enum JobType {
        EXECUTOR,
        PG_CRON
    }

    public ScheduledJobRun {
        requireNonNull(jobName, "No jobName provided");
        requireNonNull(jobType, "No jobType provided");
        requireNonNull(startedAt, "No startedAt provided");
        requireNonNull(duration, "No duration provided");
    }
}
