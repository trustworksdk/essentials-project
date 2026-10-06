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

import dk.trustworks.essentials.components.foundation.postgresql.api.*;
import org.springframework.web.bind.annotation.*;

import java.util.List;

import static dk.trustworks.essentials.shared.FailFast.requireNonNull;

/**
 * HTTP surface for {@link PostgresqlTableStatisticsApi}, implementing the contract's
 * {@code postgresql-table-statistics} tag.
 */
@RestController
@RequestMapping(AdminApiPaths.BASE_PATH_PLACEHOLDER)
public class PostgresqlTableStatisticsController {

    private final PostgresqlTableStatisticsApi tableStatisticsApi;
    private final AdminApiPrincipalResolver    principalResolver;

    public PostgresqlTableStatisticsController(PostgresqlTableStatisticsApi tableStatisticsApi,
                                               AdminApiPrincipalResolver principalResolver) {
        this.tableStatisticsApi = requireNonNull(tableStatisticsApi, "No tableStatisticsApi provided");
        this.principalResolver = requireNonNull(principalResolver, "No principalResolver provided");
    }

    @GetMapping("/postgresql/table-statistics")
    public List<ApiTableStatistics> fetchTableStatistics() {
        return tableStatisticsApi.fetchTableStatistics(principalResolver.requireAuthenticatedPrincipal());
    }
}
