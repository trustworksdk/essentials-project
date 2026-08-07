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

package dk.trustworks.essentials.components.eventsourced.aggregates.api;

import dk.trustworks.essentials.components.eventsourced.aggregates.closingbooks.AggregateGeneration;

import java.time.OffsetDateTime;

public record ApiClosingBooksGeneration(
        String aggregateType,
        String logicalAggregateId,
        long generation,
        String streamAggregateId,
        String state,
        OffsetDateTime openedAt,
        OffsetDateTime closedAt
) {
    public static ApiClosingBooksGeneration from(AggregateGeneration<String> generation) {
        return new ApiClosingBooksGeneration(generation.aggregateType().toString(),
                                             generation.logicalAggregateId().toString(),
                                             generation.generation(),
                                             generation.streamAggregateId(),
                                             generation.state().name(),
                                             generation.openedAt(),
                                             generation.closedAt().orElse(null));
    }
}
