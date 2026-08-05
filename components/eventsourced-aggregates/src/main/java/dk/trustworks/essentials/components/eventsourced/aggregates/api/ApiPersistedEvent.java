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

package dk.trustworks.essentials.components.eventsourced.aggregates.api;

import dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.eventstream.PersistedEvent;

import java.time.OffsetDateTime;

public record ApiPersistedEvent(
        String eventId,
        String aggregateType,
        String aggregateId,
        long eventOrder,
        long globalEventOrder,
        int eventRevision,
        String eventPayload,
        String metaDataPayload,
        OffsetDateTime timestamp,
        String causedByEventId,
        String correlationId,
        String tenant
) {
    public static ApiPersistedEvent from(PersistedEvent persistedEvent) {
        return new ApiPersistedEvent(persistedEvent.eventId().toString(),
                                     persistedEvent.aggregateType().toString(),
                                     persistedEvent.aggregateId().toString(),
                                     persistedEvent.eventOrder().longValue(),
                                     persistedEvent.globalEventOrder().longValue(),
                                     persistedEvent.eventRevision().intValue(),
                                     persistedEvent.event().getJson(),
                                     persistedEvent.metaData().getJson(),
                                     persistedEvent.timestamp(),
                                     persistedEvent.causedByEventId().map(Object::toString).orElse(null),
                                     persistedEvent.correlationId().map(Object::toString).orElse(null),
                                     persistedEvent.tenant().map(Object::toString).orElse(null));
    }
}
