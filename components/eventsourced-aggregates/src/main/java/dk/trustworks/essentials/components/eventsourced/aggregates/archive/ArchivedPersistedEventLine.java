/*
 * Copyright 2021-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
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

import dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.eventstream.PersistedEvent;

import java.time.OffsetDateTime;

/**
 * Represents a line in the archived representation of a persisted event, encapsulating event
 * data and metadata for export and storage purposes.
 * <p>
 * This record provides a structured format for events and their associated metadata, including
 * information about the aggregate, event identifiers, order, timestamp, and other relevant details.
 * It is intended for use in scenarios where events are exported or archived from an event store.
 *
 * @param aggregateType      The type of aggregate associated with the event.
 * @param logicalAggregateId The logical identifier of the aggregate.
 * @param generation         The generation number of the aggregate.
 * @param streamAggregateId  The unique identifier of the specific aggregate stream.
 * @param eventId            The identifier of the event.
 * @param aggregateId        The aggregate identifier associated with the event.
 * @param eventOrder         The sequence order of the event in the aggregate stream.
 * @param globalEventOrder   The sequence order of the event globally across all streams.
 * @param eventRevision      The revision number associated with the event.
 * @param eventTypeOrName    The type or name of the event.
 * @param eventPayload       The serialized payload of the event.
 * @param metaDataJavaType   The Java type of the metadata, if available.
 * @param metaDataPayload    The serialized metadata of the event.
 * @param timestamp          The timestamp when the event occurred or was persisted.
 * @param causedByEventId    The identifier of the event that caused this event, if applicable.
 * @param correlationId      The correlation identifier for tracing related events, if available.
 * @param tenant             The tenant context associated with the event, if applicable.
 */
record ArchivedPersistedEventLine(
        String aggregateType,
        String logicalAggregateId,
        long generation,
        String streamAggregateId,
        String eventId,
        String aggregateId,
        long eventOrder,
        long globalEventOrder,
        int eventRevision,
        String eventTypeOrName,
        String eventPayload,
        String metaDataJavaType,
        String metaDataPayload,
        OffsetDateTime timestamp,
        String causedByEventId,
        String correlationId,
        String tenant
) {
    static ArchivedPersistedEventLine from(AggregateArchiveExportRequest request, PersistedEvent event) {
        return new ArchivedPersistedEventLine(request.aggregateType().toString(),
                                              request.logicalAggregateId(),
                                              request.generation().generation(),
                                              request.generation().streamAggregateId(),
                                              event.eventId().toString(),
                                              event.aggregateId().toString(),
                                              event.eventOrder().longValue(),
                                              event.globalEventOrder().longValue(),
                                              event.eventRevision().intValue(),
                                              event.event().getEventTypeOrNamePersistenceValue(),
                                              event.event().getJson(),
                                              event.metaData().getJavaType().orElse(null),
                                              event.metaData().getJson(),
                                              event.timestamp(),
                                              event.causedByEventId().map(Object::toString).orElse(null),
                                              event.correlationId().map(Object::toString).orElse(null),
                                              event.tenant().map(Object::toString).orElse(null));
    }
}
