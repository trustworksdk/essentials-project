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

/**
 * Represents an entry in the archive system for an aggregate. This record encapsulates the
 * metadata and operational state related to the archiving process of an aggregate.
 * <p>
 * Fields:
 * - aggregateType: The type of the aggregate associated with the archive entry.
 * - logicalAggregateId: The logical identifier of the aggregate.
 * - generation: The generation number of the aggregate, which must be a positive value.
 * - streamAggregateId: The unique identifier of the stream for the aggregate.
 * - status: The current status of the archive entry, indicating its stage in the archiving process.
 * - format: The format of the archive, applicable when the status is not IN_PROGRESS.
 * - archiveLocation: The storage location of the archive, applicable when the status is not IN_PROGRESS.
 * - eventCount: The number of events included in the archive, applicable when the status is not IN_PROGRESS.
 * - checksum: The checksum calculated for the archive to verify its integrity.
 * - closedAt: The timestamp when the aggregate was closed, marking the end of changes to the aggregate.
 * - archivedAt: The timestamp when the archiving process was completed, applicable when the status is not IN_PROGRESS.
 * - archiveError: The error message, if any, encountered during the archiving process.
 * <p>
 * Validation:
 * - aggregateType and status must not be null.
 * - logicalAggregateId and streamAggregateId must not be blank.
 * - generation must be greater than or equal to 1.
 * - If the status is not IN_PROGRESS:
 *   - format must not be null.
 *   - archiveLocation must not be blank.
 *   - eventCount must be greater than or equal to 0.
 *   - archivedAt must not be null.
 */
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
        if (status != AggregateArchiveStatus.IN_PROGRESS) {
            requireNonNull(format, "No format provided");
            requireNonBlank(archiveLocation, "No archiveLocation provided");
            if (eventCount < 0) throw new IllegalArgumentException("eventCount must be >= 0");
            requireNonNull(archivedAt, "No archivedAt provided");
        }
    }
}
