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

import dk.trustworks.essentials.components.eventsourced.aggregates.api.*;
import org.springframework.web.bind.annotation.*;

import java.util.List;

import static dk.trustworks.essentials.shared.FailFast.requireNonNull;

/**
 * HTTP surface for {@link AggregateArchiveStatisticsApi}, implementing the contract's
 * {@code aggregate-archive-statistics} tag.
 */
@RestController
@RequestMapping(AdminApiPaths.BASE_PATH_PLACEHOLDER)
public class AggregateArchiveStatisticsController {

    private final AggregateArchiveStatisticsApi aggregateArchiveStatisticsApi;
    private final AdminApiPrincipalResolver     principalResolver;

    public AggregateArchiveStatisticsController(AggregateArchiveStatisticsApi aggregateArchiveStatisticsApi,
                                                AdminApiPrincipalResolver principalResolver) {
        this.aggregateArchiveStatisticsApi = requireNonNull(aggregateArchiveStatisticsApi, "No aggregateArchiveStatisticsApi provided");
        this.principalResolver = requireNonNull(principalResolver, "No principalResolver provided");
    }

    @GetMapping("/aggregate-archive-statistics")
    public List<ApiAggregateArchiveStatistics> findAggregateArchiveStatistics() {
        return aggregateArchiveStatisticsApi.findAggregateArchiveStatistics(principalResolver.requireAuthenticatedPrincipal());
    }
}
