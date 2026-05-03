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

import static dk.trustworks.essentials.shared.FailFast.requireNonNull;

/**
 * Represents a summary of archive-related data for a specific aggregate type.
 * This record encapsulates details about the number of archived generations,
 * failed generations, total archived events, and the timestamp of the last archiving operation.
 *
 * @param aggregateType           The type of aggregate associated with this archive summary.
 * @param archivedGenerationCount The count of successfully archived generations.
 * @param failedGenerationCount   The count of generations that failed during the archiving process.
 * @param totalArchivedEventCount The total number of events that have been archived.
 * @param lastArchivedAt          The timestamp indicating when the last archiving operation occurred.
 */
public record AggregateArchiveSummary(
        AggregateType aggregateType,
        long archivedGenerationCount,
        long failedGenerationCount,
        long totalArchivedEventCount,
        OffsetDateTime lastArchivedAt
) {
    public AggregateArchiveSummary {
        requireNonNull(aggregateType, "No aggregateType provided");
    }
}
