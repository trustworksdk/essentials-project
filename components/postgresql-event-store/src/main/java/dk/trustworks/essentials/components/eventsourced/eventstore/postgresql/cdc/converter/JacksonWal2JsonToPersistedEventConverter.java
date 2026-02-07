/*
 *  Copyright 2021-2026 the original author or authors.
 *
 *  Licensed under the Apache License, Version 2.0 (the "License");
 *  you may not use this file except in compliance with the License.
 *  You may obtain a copy of the License at
 *
 *       https://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 */

package dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.cdc.converter;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.eventstream.*;
import dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.persistence.EventMetaData;
import dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.serializer.json.*;
import dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.types.*;
import dk.trustworks.essentials.components.foundation.json.JSONSerializationException;
import dk.trustworks.essentials.components.foundation.types.*;
import org.slf4j.*;

import java.time.OffsetDateTime;
import java.time.format.*;
import java.time.temporal.ChronoField;
import java.util.*;
import java.util.regex.*;

/**
 * Converts WAL2JSON PostgreSQL replication messages into persistent events for further processing.
 * <p>
 * The class leverages a JSON parser and serializer to interpret incoming JSON messages, and
 * maps the data into domain-specific `PersistedEvent` objects. It filters and processes
 * only the necessary insert operations from the WAL2JSON change set and validates the structural
 * integrity of the input data.
 * <p>
 * The conversion is configurable via the provided {@link JacksonJSONEventSerializer} for
 * JSON serialization/deserialization and {@link AggregateTypeResolver} for resolving the
 * aggregate type of events based on the table name.
 * <p>
 * Log levels such as DEBUG and TRACE help trace the process and provide detailed diagnostic
 * information for troubleshooting and insight into the transformation logic.
 * <p>
 * Key aspects of the conversion process include:
 * - Filtering only "insert" operations from changes in the WAL2JSON message.
 * - Mapping tables that match a specific naming convention (e.g., *_events).
 * - Resolving aggregate types for tables using the {@link AggregateTypeResolver}.
 * - Extracting column names and values, and transforming them into Java objects.
 * - Constructing `PersistedEvent` objects, encapsulated with various metadata
 * such as event IDs, timestamps, and aggregate details.
 * - Handling serialization and deserialization errors gracefully and ensuring invalid
 * data is logged and prevented from being processed further.
 */
public final class JacksonWal2JsonToPersistedEventConverter implements Wal2JsonToPersistedEventConverter {

    private final static Logger log = LoggerFactory.getLogger(JacksonWal2JsonToPersistedEventConverter.class);

    private static final Pattern           EVENT_TABLE  = Pattern.compile("(?i)^(.+)_events$");
    private static final DateTimeFormatter PG_TIMESTAMP =
            new DateTimeFormatterBuilder()
                    .appendPattern("yyyy-MM-dd HH:mm:ss")
                    .optionalStart()
                    .appendFraction(ChronoField.NANO_OF_SECOND, 0, 9, true)
                    .optionalEnd()
                    .appendPattern("X")
                    .toFormatter();

    private final JacksonJSONEventSerializer jacksonJSONSerializer;
    private final AggregateTypeResolver      aggregateTypeResolver;

    public JacksonWal2JsonToPersistedEventConverter(JacksonJSONEventSerializer jacksonJSONSerializer,
                                                    AggregateTypeResolver aggregateTypeResolver) {
        this.jacksonJSONSerializer = jacksonJSONSerializer;
        this.aggregateTypeResolver = aggregateTypeResolver;
    }

    @Override
    public List<PersistedEvent> convert(String wal2jsonMessage) {
        if (wal2jsonMessage == null || wal2jsonMessage.isBlank()) {
            log.debug("Wal2json message received was null or empty");
            return List.of();
        }

        if (log.isTraceEnabled()) {
            log.trace("wal2json message length={}, preview={}",
                      wal2jsonMessage.length(),
                      wal2jsonMessage.length() > 500 ? wal2jsonMessage.substring(0, 500) + "..." : wal2jsonMessage);
        }

        JsonNode root = null;
        try {
            root = jacksonJSONSerializer.getObjectMapper().readTree(wal2jsonMessage);
        } catch (JsonProcessingException e) {
            throw new JSONSerializationException("Failed to deserialize wal2json message to PersistedEvent", e);
        }
        JsonNode changeNode = root.get("change");
        if (!(changeNode instanceof ArrayNode changes)) {
            log.trace("wal2json root had no 'change' array. keys={}", root.fieldNames().hasNext() ? "present" : "none");
            return List.of();
        }

        log.debug("wal2json change contains entries '{}'", changes.size());

        List<PersistedEvent> events = new ArrayList<>(changes.size());

        for (var change : changes) {
            var kind = text(change, "kind");
            if (!"insert".equalsIgnoreCase(kind)) {
                log.trace("Skipping change kind='{}'", kind);
                continue;
            }

            var table = text(change, "table");
            if (table == null) {
                log.trace("Skipping insert with missing table");
                continue;
            }

            var m = EVENT_TABLE.matcher(table);
            if (!m.matches()) {
                log.trace("Skipping insert for table '{}' (does not match *_events)", table);
                continue;
            }

            var aggregateType = aggregateTypeResolver.resolveFromEventTable(table);
            if (aggregateType == null) continue;

            var row = extractRow(change);

            if (log.isTraceEnabled()) {
                log.trace("Row extracted for table='{}' aggregateType='{}' keys={}",
                          table, aggregateType, row.keySet());
            }

            var persistedEvent = toPersistedEvent(aggregateType, row);
            if (log.isTraceEnabled()) {
                log.trace("Converted wal2json row → PersistedEvent globalOrder={} eventId={} aggregateType={}",
                          persistedEvent.globalEventOrder(), persistedEvent.eventId(), persistedEvent.aggregateType());
            }
            events.add(persistedEvent);
        }

        return events;
    }

    private PersistedEvent toPersistedEvent(AggregateType aggregateType,
                                            Map<String, Object> r) {
        try {
            var eventId     = EventId.of(toString(r, "event_id"));
            var aggregateId = toObject(r, "aggregate_id");

            var eventOrder = EventOrder.of(toLong(r, "event_order"));

            var eventRevision =
                    EventRevision.of(Integer.parseInt(toString(r, "event_revision")));

            var globalOrder = GlobalEventOrder.of(toLong(r, "global_order"));

            var timestamp = parsePostgresTimestamp(toString(r, "timestamp"));

            var eventType = toString(r, "event_type");
            if (eventType.isBlank()) {
                throw new IllegalStateException("event_type was blank");
            }

            var event = new EventJSON(
                    jacksonJSONSerializer,
                    EventType.of(eventType),
                    toJson(toObject(r, "event_payload"))
            );

            var jsonEventMetadata = toJson(toObject(r, "event_metadata"));
            var meta = new EventMetaDataJSON(
                    jacksonJSONSerializer,
                    EventMetaData.class.getName(),
                    jsonEventMetadata
            );

            Optional<EventId> causedBy =
                    toOptional(r, "caused_by_event_id")
                            .filter(s -> !s.isBlank())
                            .map(EventId::of);

            Optional<CorrelationId> correlationId =
                    toOptional(r, "correlation_id")
                            .filter(s -> !s.isBlank())
                            .map(CorrelationId::of);

            Optional<Tenant> tenant =
                    toOptional(r, "tenant")
                            .filter(s -> !s.isBlank())
                            .map(TenantId::of);

            return PersistedEvent.from(
                    eventId,
                    aggregateType,
                    aggregateId,
                    event,
                    eventOrder,
                    eventRevision,
                    globalOrder,
                    meta,
                    timestamp,
                    causedBy,
                    correlationId,
                    tenant
                                      );
        } catch (Exception e) {
            // IMPORTANT: let the tailer treat this as a hard failure for this WAL message
            // so it will not ACK the LSN
            log.warn("Failed to convert wal2json row for aggregateType='{}'. keys={} row={}",
                     aggregateType, r.keySet(), r, e);
            throw new JSONSerializationException("Failed to convert wal2json message to PersistedEvent", e);
        }
    }

    private Map<String, Object> extractRow(JsonNode change) {
        var names  = (ArrayNode) change.get("columnnames");
        var values = (ArrayNode) change.get("columnvalues");

        Map<String, Object> row = new HashMap<>(names.size());
        for (int i = 0; i < names.size(); i++) {
            row.put(names.get(i).asText(), jsonToJava(values.get(i)));
        }
        return row;
    }

    private Object jsonToJava(JsonNode n) {
        if (n == null || n.isNull()) return null;
        if (n.isTextual()) return n.asText();
        if (n.isNumber()) return n.numberValue();
        if (n.isBoolean()) return n.asBoolean();
        return jacksonJSONSerializer.getObjectMapper().convertValue(n, Object.class);
    }

    private String toJson(Object o) {
        if (o instanceof String s) return s;
        try {
            return jacksonJSONSerializer.getObjectMapper().writeValueAsString(o);
        } catch (Exception e) {
            return String.valueOf(o);
        }
    }

    private String toCanonicalJson(Object o) {
        try {
            JsonNode n;
            if (o == null) return "null";
            if (o instanceof String s) {
                n = jacksonJSONSerializer.getObjectMapper().readTree(s);
            } else {
                n = jacksonJSONSerializer.getObjectMapper().valueToTree(o);
            }
            return jacksonJSONSerializer.getObjectMapper().writeValueAsString(n);
        } catch (Exception e) {
            throw new JSONSerializationException("Failed to canonicalize JSON", e);
        }
    }

    private static String text(JsonNode n, String key) {
        var node = n.get(key);
        return node != null && !node.isNull() ? node.asText() : null;
    }

    private static String toString(Map<String, Object> r, String key) {
        var o = r.get(key);
        if (o == null) throw new IllegalStateException("Missing column '" + key + "'");
        return o.toString();
    }

    private static Object toObject(Map<String, Object> r, String k) {
        var o = r.get(k);
        if (o == null) throw new IllegalStateException("Missing column '" + k + "'");
        return o;
    }

    private static long toLong(Map<String, Object> r, String k) {
        var v = r.get(k);
        if (v instanceof Number n) return n.longValue();
        return Long.parseLong(v.toString());
    }

    private static Optional<String> toOptional(Map<String, Object> r, String k) {
        var v = r.get(k);
        return v == null ? Optional.empty() : Optional.of(v.toString());
    }

    private static OffsetDateTime parsePostgresTimestamp(String value) {
        try {
            return OffsetDateTime.parse(value, PG_TIMESTAMP);
            // Fast path: ISO-8601, not sure if we need this and for performance reasons
            // return OffsetDateTime.parse(value);
        } catch (Exception e) {
            throw new IllegalStateException("Failed to parse timestamp '" + value + "'", e);
        }
    }

}
