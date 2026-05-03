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

    Optional<AggregateArchiveEntry> findArchivedGeneration(AggregateType aggregateType,
                                                           String logicalAggregateId,
                                                           long generation);

    List<AggregateArchiveEntry> findArchivedGenerations(AggregateType aggregateType,
                                                        String logicalAggregateId);

    List<AggregateArchiveSummary> summarizeArchivedGenerations();
}
