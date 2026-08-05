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
import dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.eventstream.AggregateType;
import org.springframework.web.bind.annotation.*;

import java.util.List;

import static dk.trustworks.essentials.shared.FailFast.requireNonNull;

/**
 * HTTP surface for {@link AggregateArchiveApi}, implementing the contract's {@code aggregate-archive} tag.
 */
@RestController
@RequestMapping(AdminApiPaths.BASE_PATH_PLACEHOLDER)
public class AggregateArchiveController {

    private final AggregateArchiveApi       aggregateArchiveApi;
    private final AdminApiPrincipalResolver principalResolver;

    public AggregateArchiveController(AggregateArchiveApi aggregateArchiveApi,
                                      AdminApiPrincipalResolver principalResolver) {
        this.aggregateArchiveApi = requireNonNull(aggregateArchiveApi, "No aggregateArchiveApi provided");
        this.principalResolver = requireNonNull(principalResolver, "No principalResolver provided");
    }

    @GetMapping("/aggregate-archive/aggregate-types/{aggregateType}/logical-aggregates/{logicalAggregateId}/archived-generations")
    public List<ApiArchivedGeneration> findArchivedGenerations(@PathVariable String aggregateType,
                                                               @PathVariable String logicalAggregateId) {
        return aggregateArchiveApi.findArchivedGenerations(principalResolver.requireAuthenticatedPrincipal(),
                                                          AggregateType.of(aggregateType),
                                                          logicalAggregateId);
    }

    @GetMapping("/aggregate-archive/aggregate-types/{aggregateType}/logical-aggregates/{logicalAggregateId}/archived-generations/{generation}")
    public ApiArchivedGeneration findArchivedGeneration(@PathVariable String aggregateType,
                                                        @PathVariable String logicalAggregateId,
                                                        @PathVariable long generation) {
        return aggregateArchiveApi.findArchivedGeneration(principalResolver.requireAuthenticatedPrincipal(),
                                                         AggregateType.of(aggregateType),
                                                         logicalAggregateId,
                                                         generation)
                                  .orElseThrow(() -> new AdminApiResourceNotFoundException(
                                          "No archived generation '" + generation + "' exists for aggregate type '"
                                                  + aggregateType + "' and logical aggregate id '" + logicalAggregateId + "'."));
    }
}
