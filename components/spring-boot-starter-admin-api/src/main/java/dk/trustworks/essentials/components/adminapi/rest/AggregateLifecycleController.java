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
 * HTTP surface for {@link AggregateLifecycleApi}, implementing the contract's {@code aggregate-lifecycle} tag.
 */
@RestController
@RequestMapping(AdminApiPaths.BASE_PATH_PLACEHOLDER)
public class AggregateLifecycleController {

    private final AggregateLifecycleApi     aggregateLifecycleApi;
    private final AdminApiPrincipalResolver principalResolver;

    public AggregateLifecycleController(AggregateLifecycleApi aggregateLifecycleApi,
                                        AdminApiPrincipalResolver principalResolver) {
        this.aggregateLifecycleApi = requireNonNull(aggregateLifecycleApi, "No aggregateLifecycleApi provided");
        this.principalResolver = requireNonNull(principalResolver, "No principalResolver provided");
    }

    @GetMapping("/aggregate-lifecycle/snapshot-policies")
    public List<ApiAggregateSnapshotPolicy> findAllAggregateSnapshotPolicies() {
        return aggregateLifecycleApi.findAllAggregateSnapshotPolicies(principalResolver.requireAuthenticatedPrincipal());
    }

    @GetMapping("/aggregate-lifecycle/closing-books-policies")
    public List<ApiAggregateClosingBooksPolicy> findAllAggregateClosingBooksPolicies() {
        return aggregateLifecycleApi.findAllAggregateClosingBooksPolicies(principalResolver.requireAuthenticatedPrincipal());
    }

    @GetMapping("/aggregate-lifecycle/aggregate-types/{aggregateType}/logical-aggregates/{logicalAggregateId}/closing-books-generations")
    public List<ApiClosingBooksGeneration> findClosingBooksGenerations(@PathVariable String aggregateType,
                                                                      @PathVariable String logicalAggregateId) {
        return aggregateLifecycleApi.findClosingBooksGenerations(principalResolver.requireAuthenticatedPrincipal(),
                                                                AggregateType.of(aggregateType),
                                                                logicalAggregateId);
    }

    @GetMapping("/aggregate-lifecycle/aggregate-types/{aggregateType}/logical-aggregates/{logicalAggregateId}/closing-books-generations/current")
    public ApiClosingBooksGeneration findCurrentClosingBooksGeneration(@PathVariable String aggregateType,
                                                                      @PathVariable String logicalAggregateId) {
        return aggregateLifecycleApi.findCurrentClosingBooksGeneration(principalResolver.requireAuthenticatedPrincipal(),
                                                                     AggregateType.of(aggregateType),
                                                                     logicalAggregateId)
                                    .orElseThrow(() -> new AdminApiResourceNotFoundException(
                                            "No open closing-books generation exists for aggregate type '" + aggregateType
                                                    + "' and logical aggregate id '" + logicalAggregateId + "'."));
    }

    @GetMapping("/aggregate-lifecycle/aggregate-types/{aggregateType}/logical-aggregates/{logicalAggregateId}/closing-books-generations/{generation}/event-stream")
    public ApiClosingBooksGenerationEventStream findClosingBooksGenerationEventStream(@PathVariable String aggregateType,
                                                                                     @PathVariable String logicalAggregateId,
                                                                                     @PathVariable long generation) {
        return aggregateLifecycleApi.findClosingBooksGenerationEventStream(principalResolver.requireAuthenticatedPrincipal(),
                                                                          AggregateType.of(aggregateType),
                                                                          logicalAggregateId,
                                                                          generation)
                                    .orElseThrow(() -> new AdminApiResourceNotFoundException(
                                            "No closing-books generation '" + generation + "' exists for aggregate type '"
                                                    + aggregateType + "' and logical aggregate id '" + logicalAggregateId + "'."));
    }

    @GetMapping("/aggregate-lifecycle/aggregate-types/{aggregateType}/aggregates/{aggregateId}/snapshots")
    public List<ApiAggregateSnapshot> findSnapshots(@PathVariable String aggregateType,
                                                    @PathVariable String aggregateId,
                                                    @RequestParam(defaultValue = "false") boolean includeSnapshotPayload) {
        return aggregateLifecycleApi.findSnapshots(principalResolver.requireAuthenticatedPrincipal(),
                                                  AggregateType.of(aggregateType),
                                                  aggregateId,
                                                  includeSnapshotPayload);
    }
}
