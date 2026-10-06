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

import dk.trustworks.essentials.components.foundation.scheduler.ScheduledJobRun;

import java.time.OffsetDateTime;

import static dk.trustworks.essentials.shared.FailFast.requireNonNull;

/**
 * The outcome of {@link SchedulerApi#runJobNow(Object, String)}.
 *
 * @param jobName    the job's registered name
 * @param jobType    {@code EXECUTOR} or {@code PG_CRON}
 * @param startedAt  when the run started
 * @param durationMs how long the run took, in milliseconds
 * @param succeeded  whether the job completed without an error
 * @param error      the exception type and message when the run failed, otherwise {@code null}
 */
public record ApiScheduledJobRun(String jobName,
                                 String jobType,
                                 OffsetDateTime startedAt,
                                 long durationMs,
                                 boolean succeeded,
                                 String error) {

    public static ApiScheduledJobRun from(ScheduledJobRun run) {
        requireNonNull(run, "run cannot be null");
        return new ApiScheduledJobRun(run.jobName(),
                                      run.jobType().name(),
                                      run.startedAt(),
                                      run.duration().toMillis(),
                                      run.succeeded(),
                                      run.error());
    }
}
