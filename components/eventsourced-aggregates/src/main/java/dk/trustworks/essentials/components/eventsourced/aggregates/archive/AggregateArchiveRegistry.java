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

    Optional<AggregateArchiveEntry> findArchivedGeneration(AggregateType aggregateType,
                                                           String logicalAggregateId,
                                                           long generation);

    List<AggregateArchiveEntry> findArchivedGenerations(AggregateType aggregateType,
                                                        String logicalAggregateId);

    List<AggregateArchiveSummary> summarizeArchivedGenerations();
}
