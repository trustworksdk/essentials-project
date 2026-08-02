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

import dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.api.PostgresqlEventStoreStatisticsApi;
import dk.trustworks.essentials.components.foundation.postgresql.api.*;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

import static dk.trustworks.essentials.shared.FailFast.requireNonNull;

/**
 * HTTP surface for {@link PostgresqlEventStoreStatisticsApi}, implementing the contract's
 * {@code event-store-statistics} tag. Each operation returns a map keyed by event-store table name.
 */
@RestController
@RequestMapping(AdminApiPaths.BASE_PATH_PLACEHOLDER)
public class EventStoreStatisticsController {

    private final PostgresqlEventStoreStatisticsApi statisticsApi;
    private final AdminApiPrincipalResolver         principalResolver;

    public EventStoreStatisticsController(PostgresqlEventStoreStatisticsApi statisticsApi,
                                          AdminApiPrincipalResolver principalResolver) {
        this.statisticsApi = requireNonNull(statisticsApi, "No statisticsApi provided");
        this.principalResolver = requireNonNull(principalResolver, "No principalResolver provided");
    }

    @GetMapping("/event-store/statistics/table-sizes")
    public Map<String, ApiTableSizeStatistics> fetchTableSizeStatistics() {
        return statisticsApi.fetchTableSizeStatistics(principalResolver.requireAuthenticatedPrincipal());
    }

    @GetMapping("/event-store/statistics/table-activity")
    public Map<String, ApiTableActivityStatistics> fetchTableActivityStatistics() {
        return statisticsApi.fetchTableActivityStatistics(principalResolver.requireAuthenticatedPrincipal());
    }

    @GetMapping("/event-store/statistics/table-cache-hit-ratio")
    public Map<String, ApiTableCacheHitRatio> fetchTableCacheHitRatio() {
        return statisticsApi.fetchTableCacheHitRatio(principalResolver.requireAuthenticatedPrincipal());
    }
}
