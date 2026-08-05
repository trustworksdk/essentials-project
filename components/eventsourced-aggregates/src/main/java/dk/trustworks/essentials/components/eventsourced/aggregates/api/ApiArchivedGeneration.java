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

import dk.trustworks.essentials.components.eventsourced.aggregates.archive.AggregateArchiveEntry;

import java.time.OffsetDateTime;

public record ApiArchivedGeneration(
        String aggregateType,
        String logicalAggregateId,
        long generation,
        String streamAggregateId,
        String status,
        String format,
        String archiveLocation,
        long eventCount,
        String checksum,
        OffsetDateTime closedAt,
        OffsetDateTime archivedAt,
        String archiveError
) {
    public static ApiArchivedGeneration from(AggregateArchiveEntry entry) {
        return new ApiArchivedGeneration(entry.aggregateType().toString(),
                                         entry.logicalAggregateId(),
                                         entry.generation(),
                                         entry.streamAggregateId(),
                                         entry.status().name(),
                                         entry.format().name(),
                                         entry.archiveLocation(),
                                         entry.eventCount(),
                                         entry.checksum(),
                                         entry.closedAt(),
                                         entry.archivedAt(),
                                         entry.archiveError());
    }
}
