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
import java.util.List;
import java.util.Optional;

/**
 * The AggregateArchiveRegistry interface provides methods to manage the archival of
 * aggregate generations within an event-sourcing system. It allows for saving archive
 * entries, retrieving individual archived generations as well as listings of archived
 * generations and their summaries.
 */
public interface AggregateArchiveRegistry {
    void save(AggregateArchiveEntry entry);

    /**
     * Atomically claim ownership of archiving the given generation by inserting an
     * {@link AggregateArchiveStatus#IN_PROGRESS} marker row. Returns {@code true} if this caller
     * inserted the claim row, {@code false} if a row already existed (another node owns the
     * claim, or the generation is already {@code ARCHIVED}/{@code FAILED}).
     * <p>
     * Note: stale {@code IN_PROGRESS} rows from crashed workers must currently be cleared by an
     * operator. Automatic visibility-timeout based reclaim is a planned follow-up.
     */
    boolean tryClaim(AggregateType aggregateType,
                     String logicalAggregateId,
                     long generation,
                     String streamAggregateId,
                     OffsetDateTime claimedAt);

    /**
     * Retrieves an archived generation entry for a specific aggregate based on the provided
     * aggregate type, logical aggregate ID, and generation number. If no matching archived
     * generation is found, an empty {@code Optional} is returned.
     *
     * @param aggregateType The type of the aggregate associated with the archive entry. Must not be null.
     * @param logicalAggregateId The logical identifier of the aggregate. Must not be blank.
     * @param generation The generation number of the aggregate. Must be greater than or equal to 1.
     * @return An {@code Optional} containing the matching {@code AggregateArchiveEntry} if found,
     *         otherwise an empty {@code Optional}.
     */
    Optional<AggregateArchiveEntry> findArchivedGeneration(AggregateType aggregateType,
                                                           String logicalAggregateId,
                                                           long generation);

    /**
     * Retrieves a list of archived generation entries for a specific aggregate based on the
     * provided aggregate type and logical aggregate ID. The entries represent the metadata
     * and operational states related to the archiving process of the aggregate's generations.
     *
     * @param aggregateType The type of the aggregate associated with the archive entries. Must not be null.
     * @param logicalAggregateId The logical identifier of the aggregate. Must not be blank.
     * @return A list of {@code AggregateArchiveEntry} objects matching the specified aggregate type
     *         and logical aggregate ID. If no archived generations are found, an empty list is returned.
     */
    List<AggregateArchiveEntry> findArchivedGenerations(AggregateType aggregateType,
                                                        String logicalAggregateId);

    /**
     * Summarizes archived generations across all aggregates, providing a concise overview of their
     * archiving status and related metadata.
     *
     * @return A list of {@code AggregateArchiveSummary} objects, where each summary represents an
     *         aggregate type and includes statistics such as the total number of archived generations,
     *         the number of failed generations, the total count of archived events, and the timestamp
     *         of the last successful archiving. If no archived generations exist, an empty list is returned.
     */
    List<AggregateArchiveSummary> summarizeArchivedGenerations();
}
