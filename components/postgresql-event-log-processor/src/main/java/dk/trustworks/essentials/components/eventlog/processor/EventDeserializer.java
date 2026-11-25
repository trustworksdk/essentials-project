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
import dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.eventstream.AggregateType;
import dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.persistence.EventMetaData;
import dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.serializer.*;
import dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.serializer.json.*;
import dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.types.*;
import dk.trustworks.essentials.components.foundation.types.*;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

import static dk.trustworks.essentials.shared.FailFast.requireNonNull;
import static dk.trustworks.essentials.shared.MessageFormatter.msg;

/**
 * Turns the engine's {@link StoredEvent} into a {@link DeliveredEvent} with the application's
 * {@link JSONEventSerializer} - from {@link EssentialsJSONEventSerializers#create()}, never a hand-built mapper - and the
 * aggregate type's {@link AggregateIdSerializer} and {@link TenantSerializer}. The processors call this inside the
 * handler, so a failure is a handler failure, and there are two kinds:
 * <ul>
 *     <li>A payload or metadata text that is not JSON at all (empty, truncated, not a JSON document) throws a
 *     {@link ParkEventException} naming the event, with the serializer's exception as cause: the event parks at once as
 *     terminal, because no retry and no deployment can make that text parse. Told apart by the serializer's cause:
 *     Jackson's {@code tools.jackson.core.exc.StreamReadException} (the parser's failure; its subtypes included), or a
 *     blank text.</li>
 *     <li>Everything else is thrown as it is and retried by the engine: an unknown event class
 *     ({@code serializer.resolveClass}), and valid JSON that does not map onto the class (Jackson's
 *     {@code DatabindException}s). Both are typically healed by a rolling deployment that brings the class, or its new
 *     shape, to every node.</li>
 * </ul>
 * Jackson is recognised by class name, because this module never depends on Jackson (only the application's
 * {@link JSONEventSerializer} does).
 */
public final class EventDeserializer {
    /** The Jackson 3 parser's exception: the text is not JSON. A mapping failure is a {@code DatabindException}, which is not one of these. */
    static final String JACKSON_STREAM_READ_EXCEPTION = "tools.jackson.core.exc.StreamReadException";

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
     * @throws ParkEventException if the payload or metadata is not JSON at all (terminal: the event parks at once)
     * @throws RuntimeException   if the payload or metadata cannot be deserialized otherwise, for instance an unknown
     *                            event class or JSON that does not fit the class (retried)
     */
    public DeliveredEvent deserialize(StoredEvent event) {
        requireNonNull(event, "No event provided");
        var eventType = eventTypes.computeIfAbsent(event.eventType(), EventDeserializer::typeOrName);
        Object payload = eventType.getEventType().isPresent()
                         ? parse(event, "payload", event.payload(), serializer.resolveClass(eventType.getEventType().get().getJavaTypeName()))
                         : event.payload();
        var metaData = event.metadata() == null ? EventMetaData.empty() : parse(event, "metadata", event.metadata(), EventMetaData.class);
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

    private <T> T parse(StoredEvent event, String part, String json, Class<T> type) {
        try {
            return serializer.deserialize(json, type);
        } catch (RuntimeException e) {
            if ((json != null && json.isBlank()) || isStreamReadFailure(e)) {
                throw new ParkEventException(event,
                                             msg("The {} of event '{}' (aggregate type '{}', aggregate id '{}', event order {}, global order {}) is not valid JSON, " +
                                                 "so no retry or deployment can deserialize it; parked as terminal: {}",
                                                 part, event.eventId(), event.aggregateType(), event.aggregateId(), event.eventOrder(), event.globalOrder(),
                                                 rootMessage(e)),
                                             e);
            }
            throw e;
        }
    }

    /** Whether the cause chain holds Jackson's parser exception (or a subtype); by name, as this module has no Jackson dependency */
    static boolean isStreamReadFailure(Throwable failure) {
        var seen = Collections.newSetFromMap(new IdentityHashMap<Throwable, Boolean>());
        for (var cause = failure; cause != null && seen.add(cause); cause = cause.getCause()) {
            for (Class<?> type = cause.getClass(); type != null; type = type.getSuperclass()) {
                if (type.getName().equals(JACKSON_STREAM_READ_EXCEPTION)) {
                    return true;
                }
            }
        }
        return false;
    }

    private static String rootMessage(Throwable failure) {
        var root = failure;
        while (root.getCause() != null && root.getCause() != root) {
            root = root.getCause();
        }
        return root.getClass().getSimpleName() + ": " + root.getMessage();
    }

    private static EventTypeOrName typeOrName(String stored) {
        return EventType.isSerializedEventType(stored) ? EventTypeOrName.with(EventType.of(stored)) : EventTypeOrName.with(EventName.of(stored));
    }
}
