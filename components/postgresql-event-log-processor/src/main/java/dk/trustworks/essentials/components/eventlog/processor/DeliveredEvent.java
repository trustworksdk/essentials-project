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

package dk.trustworks.essentials.components.eventlog.processor;

import dk.trustworks.essentials.components.eventlog.StoredEvent;
import dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.eventstream.AggregateType;
import dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.persistence.EventMetaData;
import dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.types.*;
import dk.trustworks.essentials.components.foundation.types.*;

import java.time.OffsetDateTime;
import java.util.Optional;

import static dk.trustworks.essentials.shared.FailFast.requireNonNull;

/**
 * One delivered event with its payload deserialized, built from the engine's {@link StoredEvent}
 * by an {@link EventDeserializer}. The value types are the existing ones, so a handler moved from the old engine keeps
 * its types.
 *
 * @param aggregateId the aggregate id, deserialized with the type's {@code AggregateIdSerializer}
 * @param eventType   the stored event type: an {@link EventType} ({@code FQCN:} prefix) or an {@link EventName}
 * @param payload     the deserialized payload for an {@link EventType}; for an {@link EventName} the payload's JSON text
 * @param raw         the row as the engine read it
 */
public record DeliveredEvent(AggregateType aggregateType, Object aggregateId, EventOrder eventOrder, GlobalEventOrder globalOrder, EventId eventId,
                             Optional<EventId> causedBy, Optional<CorrelationId> correlationId, EventTypeOrName eventType, Object payload,
                             EventMetaData metaData, OffsetDateTime timestamp, Optional<Tenant> tenant, StoredEvent raw) {
    public DeliveredEvent {
        requireNonNull(aggregateType, "No aggregateType provided");
        requireNonNull(aggregateId, "No aggregateId provided");
        requireNonNull(eventOrder, "No eventOrder provided");
        requireNonNull(globalOrder, "No globalOrder provided");
        requireNonNull(eventId, "No eventId provided");
        requireNonNull(causedBy, "No causedBy provided");
        requireNonNull(correlationId, "No correlationId provided");
        requireNonNull(eventType, "No eventType provided");
        requireNonNull(payload, "No payload provided");
        requireNonNull(metaData, "No metaData provided");
        requireNonNull(tenant, "No tenant provided");
        requireNonNull(raw, "No raw provided");
    }

    /** @return the payload as {@code type} */
    public <T> T payloadAs(Class<T> type) {
        return type.cast(payload);
    }
}
