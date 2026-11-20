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

import dk.trustworks.essentials.components.eventlog.*;
import dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.serializer.AggregateIdSerializer;
import dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.serializer.json.JSONEventSerializer;
import dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.serializer.TenantSerializer;

import java.util.function.Function;

import static dk.trustworks.essentials.shared.FailFast.requireNonNull;

/**
 * What an {@link EventLogViewProcessor} or {@link EventLogReactor} is built with. The Spring Boot
 * starter declares one as a bean; without Spring, build it from the started {@link EventLog}.
 *
 * @param eventLog         the started engine the processors subscribe to
 * @param serializer       the application's serializer, from {@code EssentialsJSONEventSerializers}
 * @param pageTransactions where an {@link EventLogViewProcessor}'s page transactions come from - {@link UnitOfWorkPageTransactions}
 *                         or {@link SpringPageTransactions}, so a handler sees the page's unit of work or Spring transaction.
 *                         Not used by an {@link EventLogReactor}
 * @param aggregateIdSerializers the {@link AggregateIdSerializer} of each aggregate type, so a handler gets the typed id
 *                         ({@code OrderId}) the old engine gave it - the event store's per-type configuration
 *                         ({@code AggregateIdSerializer} of its {@code AggregateEventStreamConfiguration}). The
 *                         three-argument constructor delivers {@code String} ids
 */
public record EventLogProcessorDependencies(EventLog eventLog, JSONEventSerializer serializer, PageTransactionSource pageTransactions,
                                            Function<String, AggregateIdSerializer> aggregateIdSerializers) {
    public EventLogProcessorDependencies {
        requireNonNull(eventLog, "No eventLog provided");
        requireNonNull(serializer, "No serializer provided");
        requireNonNull(pageTransactions, "No pageTransactions provided");
        requireNonNull(aggregateIdSerializers, "No aggregateIdSerializers provided");
    }

    /**
     * {@code String} aggregate ids for every type
     */
    public EventLogProcessorDependencies(EventLog eventLog, JSONEventSerializer serializer, PageTransactionSource pageTransactions) {
        this(eventLog, serializer, pageTransactions, aggregateType -> new AggregateIdSerializer.StringIdSerializer());
    }

    /**
     * @return the deserializer of {@code aggregateType}'s events: its {@link AggregateIdSerializer}, {@code TenantId} tenants.
     * Resolved once per projection when it is built, never per event
     */
    public EventDeserializer deserializerFor(String aggregateType) {
        var idSerializer = requireNonNull(aggregateIdSerializers.apply(aggregateType), "No AggregateIdSerializer for aggregate type " + aggregateType);
        return new EventDeserializer(serializer, idSerializer, new TenantSerializer.TenantIdSerializer());
    }
}
