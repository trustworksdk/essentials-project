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

package dk.trustworks.essentials.components.eventsourced.aggregates.snapshot;

import dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.eventstream.AggregateType;
import dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.types.EventOrder;
import dk.trustworks.essentials.shared.FailFast;

import java.time.OffsetDateTime;
import java.util.Optional;

import static dk.trustworks.essentials.shared.FailFast.requireNonNull;

/**
 * Represents the context for triggering a snapshot in an event-sourcing based system.
 * This record encapsulates all necessary information required to evaluate whether
 * a snapshot should be created for a given aggregate.
 *
 * @param aggregateType The aggregate type indicating the domain object category.
 * @param aggregateId The unique identifier of the aggregate instance.
 * @param aggregateImplementationType The runtime implementation class of the aggregate.
 * @param latestPersistedEventOrder The latest event order that has been persisted for the aggregate.
 * @param persistedEventsCount The total count of events persisted for the aggregate.
 * @param latestSnapshotEventOrder The optional event order of the latest snapshot, if one exists.
 * @param now The current timestamp representing the moment the snapshot trigger was evaluated.
 */
public record SnapshotTriggerContext<ID>(AggregateType aggregateType,
                                         ID aggregateId,
                                         Class<?> aggregateImplementationType,
                                         EventOrder latestPersistedEventOrder,
                                         int persistedEventsCount,
                                         Optional<EventOrder> latestSnapshotEventOrder,
                                         OffsetDateTime now) {

    public SnapshotTriggerContext(AggregateType aggregateType,
                                  ID aggregateId,
                                  Class<?> aggregateImplementationType,
                                  EventOrder latestPersistedEventOrder,
                                  int persistedEventsCount,
                                  Optional<EventOrder> latestSnapshotEventOrder,
                                  OffsetDateTime now) {
        this.aggregateType = requireNonNull(aggregateType, "aggregateType cannot be null");
        this.aggregateId = requireNonNull(aggregateId, "aggregateId cannot be null");
        this.aggregateImplementationType = requireNonNull(aggregateImplementationType, "aggregateImplementationType cannot be null");
        this.latestPersistedEventOrder = requireNonNull(latestPersistedEventOrder, "latestPersistedEventOrder cannot be null");
        this.persistedEventsCount = persistedEventsCount;
        this.latestSnapshotEventOrder = latestSnapshotEventOrder;
        this.now = requireNonNull(now, "now cannot be null");
    }
}
