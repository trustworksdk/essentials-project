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

import dk.trustworks.essentials.components.eventsourced.aggregates.closingbooks.AggregateGeneration;
import dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.eventstream.AggregateType;
import dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.eventstream.PersistedEvent;

import java.util.stream.Stream;

import static dk.trustworks.essentials.shared.FailFast.requireNonBlank;
import static dk.trustworks.essentials.shared.FailFast.requireNonNull;

/**
 * Request passed to {@link AggregateArchiveExporter} describing which generation to export and
 * providing a single-pass {@link Stream} of {@link PersistedEvent}s sourced lazily from the event
 * store. The stream is consumed exactly once by the exporter; callers must not reuse this request.
 * <p>
 * Note: the event source is typically backed by an open JDBC handle/UoW, so iteration must
 * complete before the surrounding {@code UnitOfWork} is closed.
 */
public record AggregateArchiveExportRequest(
        AggregateType aggregateType,
        String logicalAggregateId,
        AggregateGeneration<String> generation,
        Stream<PersistedEvent> persistedEvents
) {
    public AggregateArchiveExportRequest {
        requireNonNull(aggregateType, "No aggregateType provided");
        requireNonBlank(logicalAggregateId, "No logicalAggregateId provided");
        requireNonNull(generation, "No generation provided");
        requireNonNull(persistedEvents, "No persistedEvents provided");
    }
}
