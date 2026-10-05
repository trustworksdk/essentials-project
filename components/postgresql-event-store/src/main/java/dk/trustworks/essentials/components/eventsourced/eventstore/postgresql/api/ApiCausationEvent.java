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

package dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.api;

import dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.eventstream.PersistedEvent;

import java.time.OffsetDateTime;

import static dk.trustworks.essentials.shared.FailFast.requireNonNull;

/**
 * An event as the causation operations of {@link EventStoreApi} describe it: its identity, where it sits, and what
 * caused it. Deliberately without the event or metadata payload - walking causation does not need them, and payloads
 * are what the admin API otherwise guards with a separate role.
 *
 * @param eventId          the id of the event
 * @param aggregateType    the aggregate type of the event stream holding it
 * @param aggregateId      the id of the aggregate it belongs to
 * @param eventType        the event's persisted type or name
 * @param eventOrder       its order within the aggregate's event stream
 * @param globalEventOrder its order within the aggregate type's event streams
 * @param timestamp        when it was persisted
 * @param causedByEventId  the id of the event that caused it; null when it has no recorded cause
 */
public record ApiCausationEvent(String eventId,
                                String aggregateType,
                                String aggregateId,
                                String eventType,
                                long eventOrder,
                                long globalEventOrder,
                                OffsetDateTime timestamp,
                                String causedByEventId) {

    public static ApiCausationEvent from(PersistedEvent persistedEvent) {
        requireNonNull(persistedEvent, "No persistedEvent provided");
        return new ApiCausationEvent(persistedEvent.eventId().toString(),
                                     persistedEvent.aggregateType().toString(),
                                     persistedEvent.aggregateId().toString(),
                                     persistedEvent.event().getEventTypeOrNamePersistenceValue(),
                                     persistedEvent.eventOrder().longValue(),
                                     persistedEvent.globalEventOrder().longValue(),
                                     persistedEvent.timestamp(),
                                     persistedEvent.causedByEventId().map(Object::toString).orElse(null));
    }
}
