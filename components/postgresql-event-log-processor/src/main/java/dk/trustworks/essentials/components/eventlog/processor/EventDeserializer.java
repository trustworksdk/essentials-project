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
import dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.serializer.*;
import dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.serializer.json.*;
import dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.types.*;
import dk.trustworks.essentials.components.foundation.types.*;

import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

import static dk.trustworks.essentials.shared.FailFast.requireNonNull;

/**
 * Turns the engine's {@link StoredEvent} into a {@link DeliveredEvent} with the application's
 * {@link JSONEventSerializer} - from {@link EssentialsJSONEventSerializers#create()}, never a hand-built mapper - and the
 * aggregate type's {@link AggregateIdSerializer} and {@link TenantSerializer}. A failure (unknown class, bad JSON) is
 * thrown to the caller; the processors call this inside the handler, so the engine retries and then parks the event
 * like any handler failure.
 */
public final class EventDeserializer {
    private final JSONEventSerializer                         serializer;
    private final AggregateIdSerializer                       aggregateIdSerializer;
    private final TenantSerializer<? extends Tenant>          tenantSerializer;
    private final ConcurrentHashMap<String, AggregateType>    aggregateTypes = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, EventTypeOrName>  eventTypes     = new ConcurrentHashMap<>();

    public EventDeserializer(JSONEventSerializer serializer, AggregateIdSerializer aggregateIdSerializer, TenantSerializer<? extends Tenant> tenantSerializer) {
        this.serializer = requireNonNull(serializer, "No serializer provided");
        this.aggregateIdSerializer = requireNonNull(aggregateIdSerializer, "No aggregateIdSerializer provided");
        this.tenantSerializer = requireNonNull(tenantSerializer, "No tenantSerializer provided");
    }

    /** @return a deserializer with {@code String} aggregate ids and {@link TenantId} tenants */
    public static EventDeserializer of(JSONEventSerializer serializer) {
        return new EventDeserializer(serializer, new AggregateIdSerializer.StringIdSerializer(), new TenantSerializer.TenantIdSerializer());
    }

    /** @return a deserializer whose aggregate ids are of {@code aggregateIdType}, with {@link TenantId} tenants */
    public static EventDeserializer of(JSONEventSerializer serializer, Class<?> aggregateIdType) {
        return new EventDeserializer(serializer, AggregateIdSerializer.serializerFor(aggregateIdType), new TenantSerializer.TenantIdSerializer());
    }

    public JSONEventSerializer serializer() {
        return serializer;
    }

    /**
     * @throws RuntimeException if the payload or metadata cannot be deserialized
     */
    public DeliveredEvent deserialize(StoredEvent event) {
        requireNonNull(event, "No event provided");
        var eventType = eventTypes.computeIfAbsent(event.eventType(), EventDeserializer::typeOrName);
        Object payload = eventType.getEventType().isPresent()
                         ? serializer.deserialize(event.payload(), serializer.resolveClass(eventType.getEventType().get().getJavaTypeName()))
                         : event.payload();
        var metaData = event.metadata() == null ? EventMetaData.empty() : serializer.deserialize(event.metadata(), EventMetaData.class);
        return new DeliveredEvent(aggregateTypes.computeIfAbsent(event.aggregateType(), AggregateType::of),
                                  aggregateIdSerializer.deserialize(event.aggregateId()),
                                  EventOrder.of(event.eventOrder()),
                                  GlobalEventOrder.of(event.globalOrder()),
                                  EventId.of(event.eventId().toString()),
                                  Optional.ofNullable(event.causedByEventId()).map(id -> EventId.of(id.toString())),
                                  Optional.ofNullable(event.correlationId()).map(id -> CorrelationId.of(id.toString())),
                                  eventType,
                                  payload,
                                  metaData == null ? EventMetaData.empty() : metaData,
                                  event.timestamp(),
                                  tenantSerializer.deserialize(event.tenant()).map(Tenant.class::cast),
                                  event);
    }

    private static EventTypeOrName typeOrName(String stored) {
        return EventType.isSerializedEventType(stored) ? EventTypeOrName.with(EventType.of(stored)) : EventTypeOrName.with(EventName.of(stored));
    }
}
