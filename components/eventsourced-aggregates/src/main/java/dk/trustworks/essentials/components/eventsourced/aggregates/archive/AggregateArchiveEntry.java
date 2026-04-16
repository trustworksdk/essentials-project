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

package dk.trustworks.essentials.components.eventsourced.aggregates.archive;

import dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.eventstream.AggregateType;

import java.time.OffsetDateTime;

import static dk.trustworks.essentials.shared.FailFast.requireNonBlank;
import static dk.trustworks.essentials.shared.FailFast.requireNonNull;

public record AggregateArchiveEntry(
        AggregateType aggregateType,
        String logicalAggregateId,
        long generation,
        String streamAggregateId,
        AggregateArchiveStatus status,
        AggregateArchiveFormat format,
        String archiveLocation,
        long eventCount,
        String checksum,
        OffsetDateTime closedAt,
        OffsetDateTime archivedAt,
        String archiveError
) {
    public AggregateArchiveEntry {
        requireNonNull(aggregateType, "No aggregateType provided");
        requireNonBlank(logicalAggregateId, "No logicalAggregateId provided");
        if (generation < 1) throw new IllegalArgumentException("generation must be >= 1");
        requireNonBlank(streamAggregateId, "No streamAggregateId provided");
        requireNonNull(status, "No status provided");
        requireNonNull(format, "No format provided");
        requireNonBlank(archiveLocation, "No archiveLocation provided");
        if (eventCount < 0) throw new IllegalArgumentException("eventCount must be >= 0");
        requireNonNull(archivedAt, "No archivedAt provided");
    }
}
