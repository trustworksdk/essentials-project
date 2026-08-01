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

package dk.trustworks.essentials.components.adminapi.rest;

import dk.trustworks.essentials.components.adminapi.rest.dto.CountResult;
import dk.trustworks.essentials.components.foundation.scheduler.api.*;
import org.springframework.web.bind.annotation.*;

import java.util.List;

import static dk.trustworks.essentials.shared.FailFast.requireNonNull;

/**
 * HTTP surface for {@link SchedulerApi}, implementing the contract's {@code scheduler} tag.
 */
@RestController
@RequestMapping(AdminApiPaths.BASE_PATH_PLACEHOLDER)
public class SchedulerController {

    private final SchedulerApi              schedulerApi;
    private final AdminApiPrincipalResolver principalResolver;

    public SchedulerController(SchedulerApi schedulerApi, AdminApiPrincipalResolver principalResolver) {
        this.schedulerApi = requireNonNull(schedulerApi, "No schedulerApi provided");
        this.principalResolver = requireNonNull(principalResolver, "No principalResolver provided");
    }

    @GetMapping("/scheduler/pg-cron-jobs")
    public List<ApiPgCronJob> getPgCronJobs(@RequestParam(defaultValue = AdminApiPaths.DEFAULT_START_INDEX) long startIndex,
                                            @RequestParam(defaultValue = AdminApiPaths.DEFAULT_PAGE_SIZE) long pageSize) {
        return schedulerApi.getPgCronJobs(principalResolver.requireAuthenticatedPrincipal(), startIndex, pageSize);
    }

    @GetMapping("/scheduler/pg-cron-jobs/count")
    public CountResult getTotalPgCronJobs() {
        return new CountResult(schedulerApi.getTotalPgCronJobs(principalResolver.requireAuthenticatedPrincipal()));
    }

    @GetMapping("/scheduler/pg-cron-jobs/{jobId}/run-details")
    public List<ApiPgCronJobRunDetails> getPgCronJobRunDetails(@PathVariable Integer jobId,
                                                               @RequestParam(defaultValue = AdminApiPaths.DEFAULT_START_INDEX) long startIndex,
                                                               @RequestParam(defaultValue = AdminApiPaths.DEFAULT_PAGE_SIZE) long pageSize) {
        return schedulerApi.getPgCronJobRunDetails(principalResolver.requireAuthenticatedPrincipal(), jobId, startIndex, pageSize);
    }

    @GetMapping("/scheduler/pg-cron-jobs/{jobId}/run-details/count")
    public CountResult getTotalPgCronJobRunDetails(@PathVariable Integer jobId) {
        return new CountResult(schedulerApi.getTotalPgCronJobRunDetails(principalResolver.requireAuthenticatedPrincipal(), jobId));
    }

    @GetMapping("/scheduler/executor-jobs")
    public List<ApiExecutorJob> getExecutorJobs(@RequestParam(defaultValue = AdminApiPaths.DEFAULT_START_INDEX) long startIndex,
                                                @RequestParam(defaultValue = AdminApiPaths.DEFAULT_PAGE_SIZE) long pageSize) {
        return schedulerApi.getExecutorJobs(principalResolver.requireAuthenticatedPrincipal(), startIndex, pageSize);
    }

    @GetMapping("/scheduler/executor-jobs/count")
    public CountResult getTotalExecutorJobs() {
        return new CountResult(schedulerApi.getTotalExecutorJobs(principalResolver.requireAuthenticatedPrincipal()));
    }
}
