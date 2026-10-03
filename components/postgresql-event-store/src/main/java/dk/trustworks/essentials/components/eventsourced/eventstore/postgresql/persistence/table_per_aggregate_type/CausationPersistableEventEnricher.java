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

package dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.persistence.table_per_aggregate_type;

import dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.persistence.*;
import dk.trustworks.essentials.components.foundation.causation.CausationContext;

import static dk.trustworks.essentials.shared.FailFast.requireNonNull;

/**
 * Records the cause of every event written while a cause is bound in {@link CausationContext}: it sets
 * {@link PersistableEvent#causedByEventId()} to {@link CausationContext#current()}.
 * <p>
 * It only ever <em>fills an empty</em> {@code causedByEventId}. A cause the {@link PersistableEventMapper} - or an
 * earlier enricher - already decided is kept unchanged, and an event written with no cause bound is left as it is,
 * which is always legal: an event started by an HTTP request, a scheduler or a person has no causing event.
 * <p>
 * The framework binds the cause at every event delivery site it owns, and durable queues carry it across to the
 * consuming side, so with this enricher registered every event written in reaction to another event records which one.
 * Applications can bind a cause explicitly with {@link CausationContext#where(dk.trustworks.essentials.components.foundation.types.EventId)}.
 * <p>
 * The Spring Boot starter registers this enricher by default; {@code essentials.eventstore.causation.enabled=false}
 * turns it off.
 */
public final class CausationPersistableEventEnricher implements PersistableEventEnricher {
    @Override
    public PersistableEvent enrich(PersistableEvent event) {
        requireNonNull(event, "No event provided");
        if (event.causedByEventId().isPresent()) {
            return event;
        }
        return CausationContext.current()
                               .<PersistableEvent>map(causedBy -> new PersistableEvent.DefaultPersistableEvent(event.eventId(),
                                                                                                               event.streamName(),
                                                                                                               event.aggregateId(),
                                                                                                               event.eventTypeOrName(),
                                                                                                               event.event(),
                                                                                                               event.eventOrder(),
                                                                                                               event.eventRevision(),
                                                                                                               event.metaData(),
                                                                                                               event.timestamp().orElse(null),
                                                                                                               causedBy,
                                                                                                               event.correlationId().orElse(null),
                                                                                                               event.tenant().orElse(null)))
                               .orElse(event);
    }

    @Override
    public String toString() {
        return getClass().getSimpleName();
    }
}
