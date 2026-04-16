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
