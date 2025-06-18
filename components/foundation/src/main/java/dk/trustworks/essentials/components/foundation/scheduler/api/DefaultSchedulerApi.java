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

import dk.trustworks.essentials.components.foundation.scheduler.EssentialsScheduler;
import dk.trustworks.essentials.shared.security.EssentialsSecurityProvider;

import java.util.List;

import static dk.trustworks.essentials.shared.FailFast.requireNonNull;
import static dk.trustworks.essentials.shared.security.EssentialsSecurityRoles.*;
import static dk.trustworks.essentials.shared.security.EssentialsSecurityValidator.validateHasAnyEssentialsSecurityRoles;

/**
 * Default implementation of the {@link SchedulerApi} interface, which provides methods for managing
 * and retrieving details about PostgreSQL cron jobs and API executor jobs.
 */
public class DefaultSchedulerApi implements SchedulerApi {

    private final EssentialsScheduler        essentialsScheduler;
    private final EssentialsSecurityProvider securityProvider;

    public DefaultSchedulerApi(EssentialsScheduler essentialsScheduler,
                               EssentialsSecurityProvider securityProvider) {
        this.essentialsScheduler = requireNonNull(essentialsScheduler, "essentialsScheduler cannot be null");
        this.securityProvider = requireNonNull(securityProvider, "securityProvider cannot be null");
    }

    private void validateRoles(Object principal) {
        validateHasAnyEssentialsSecurityRoles(securityProvider, principal, SCHEDULER_READER, ESSENTIALS_ADMIN);
    }

    @Override
    public List<ApiPgCronJob> getPgCronJobs(Object principal, long startIndex, long pageSize) {
        validateRoles(principal);
        return essentialsScheduler.fetchPgCronEntries(startIndex, pageSize).stream()
                                  .map(ApiPgCronJob::from)
                                  .toList();
    }

    @Override
    public long getTotalPgCronJobs(Object principal) {
        validateRoles(principal);
        return essentialsScheduler.getTotalPgCronEntries();
    }

    @Override
    public List<ApiPgCronJobRunDetails> getPgCronJobRunDetails(Object principal, Integer jobId, long startIndex, long pageSize) {
        validateRoles(principal);
        return essentialsScheduler.fetchPgCronJobRunDetails(jobId, startIndex, pageSize).stream()
                                  .map(ApiPgCronJobRunDetails::from)
                                  .toList();
    }

    @Override
    public long getTotalPgCronJobRunDetails(Object principal, Integer jobId) {
        validateRoles(principal);
        return essentialsScheduler.getTotalPgCronJobRunDetails(jobId);
    }

    @Override
    public List<ApiExecutorJob> getExecutorJobs(Object principal, long startIndex, long pageSize) {
        validateRoles(principal);
        return essentialsScheduler.fetchExecutorJobEntries(startIndex, pageSize).stream()
                                  .map(ApiExecutorJob::from)
                                  .toList();
    }

    @Override
    public long getTotalExecutorJobs(Object principal) {
        validateRoles(principal);
        return essentialsScheduler.geTotalExecutorJobEntries();
    }
}
