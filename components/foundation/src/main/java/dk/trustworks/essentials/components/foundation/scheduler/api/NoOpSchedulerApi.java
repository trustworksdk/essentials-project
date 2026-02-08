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

import java.util.List;

/**
 * A no-operation implementation of the {@link SchedulerApi} interface.
 * This implementation acts as a placeholder, providing empty collections
 * and zero counts for all method calls. It does not perform any actual
 * scheduling-related tasks.
 */
public class NoOpSchedulerApi implements SchedulerApi {

    @Override
    public List<ApiPgCronJob> getPgCronJobs(Object principal, long startIndex, long pageSize) {
        return List.of();
    }

    @Override
    public long getTotalPgCronJobs(Object principal) {
        return 0;
    }

    @Override
    public List<ApiPgCronJobRunDetails> getPgCronJobRunDetails(Object principal, Integer jobId, long startIndex, long pageSize) {
        return List.of();
    }

    @Override
    public long getTotalPgCronJobRunDetails(Object principal, Integer jobId) {
        return 0;
    }

    @Override
    public List<ApiExecutorJob> getExecutorJobs(Object principal, long startIndex, long pageSize) {
        return List.of();
    }

    @Override
    public long getTotalExecutorJobs(Object principal) {
        return 0;
    }
}
