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

import dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.cdc.PgOutputRowChange;
import dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.eventstream.AggregateType;
import dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.eventstream.PersistedEvent;
import dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.persistence.EventMetaData;
import dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.serializer.json.EventJSON;
import dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.serializer.json.EventMetaDataJSON;
import dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.serializer.json.JSONEventSerializer;
import dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.types.*;
import dk.trustworks.essentials.components.foundation.json.JSONSerializationException;
import dk.trustworks.essentials.components.foundation.types.CorrelationId;
import dk.trustworks.essentials.components.foundation.types.EventId;
import dk.trustworks.essentials.components.foundation.types.Tenant;
import dk.trustworks.essentials.components.foundation.types.TenantId;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.OffsetDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeFormatterBuilder;
import java.time.temporal.ChronoField;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicLong;

import static dk.trustworks.essentials.shared.FailFast.requireNonNull;

/**
 * Converts canonical {@link PgOutputRowChange} inserts into {@link PersistedEvent}s.
 */
public final class PgOutputToPersistedEventConverter {
    private static final Logger log = LoggerFactory.getLogger(PgOutputToPersistedEventConverter.class);

    private static final DateTimeFormatter PG_TIMESTAMP =
            new DateTimeFormatterBuilder()
                    .appendPattern("yyyy-MM-dd HH:mm:ss")
                    .optionalStart()
                    .appendFraction(ChronoField.NANO_OF_SECOND, 0, 9, true)
                    .optionalEnd()
                    .appendPattern("X")
                    .toFormatter();

    private final JSONEventSerializer jsonSerializer;
    private final AggregateTypeResolver      aggregateTypeResolver;
    /**
     * Turns the WAL's text {@code aggregate_id} back into the typed id, matching what
     * {@code PersistedEventRowMapper} produces on the polling path.
     */
    private final AggregateIdSerializerResolver aggregateIdSerializerResolver;

    /**
     * Diagnostic counters — answer "why did the dispatcher publish 0 events?" without needing to
     * enable TRACE logs. {@link #insertsSeenCount} is the total INSERT row-changes we've seen;
     * {@link #insertsWithUnknownAggregateCount} is how many of those were dropped because the
     * table wasn't registered with the {@link AggregateTypeResolver}. If the two counters are
     * equal, the resolver is returning empty for every event table — either the aggregate
     * wasn't registered with the event store, or there's a table-name mismatch (schema prefix,
     * case, etc.). Surfaced via {@link #getInsertsSeenCount()} / {@link #getInsertsWithUnknownAggregateCount()}
     * and included in the {@code CdcEffectivenessMonitor} failure log.
     */
    private final AtomicLong insertsSeenCount                 = new AtomicLong(0);
    private final AtomicLong insertsWithUnknownAggregateCount = new AtomicLong(0);

    public PgOutputToPersistedEventConverter(JSONEventSerializer jsonSerializer,
                                             AggregateTypeResolver aggregateTypeResolver,
                                             AggregateIdSerializerResolver aggregateIdSerializerResolver) {
        this.jsonSerializer = jsonSerializer;
        this.aggregateTypeResolver = aggregateTypeResolver;
        this.aggregateIdSerializerResolver = requireNonNull(aggregateIdSerializerResolver, "aggregateIdSerializerResolver cannot be null");
    }

    /**
     * @deprecated Leaves {@link PersistedEvent#aggregateId()} as the raw WAL text instead of the typed
     * aggregate id the polling path produces, so CDC-delivered events disagree with polled ones. Use
     * {@link #PgOutputToPersistedEventConverter(JSONEventSerializer, AggregateTypeResolver, AggregateIdSerializerResolver)}
     * with {@link AggregateIdSerializerResolver#forEventStore} instead.
     */
    @Deprecated(forRemoval = true)
    public PgOutputToPersistedEventConverter(JSONEventSerializer jsonSerializer,
                                             AggregateTypeResolver aggregateTypeResolver) {
        this(jsonSerializer, aggregateTypeResolver, AggregateIdSerializerResolver.rawText());
    }

    /**
     * Convert the row change when it represents a relevant EventStore insert.
     * Optional.empty() means the row was not relevant for EventStore processing, not that conversion failed.
     */
    public Optional<PersistedEvent> convertIfRelevant(PgOutputRowChange change) {
        if (change == null) return Optional.empty();
        if (!"insert".equalsIgnoreCase(change.kind())) return Optional.empty();

        insertsSeenCount.incrementAndGet();

        var aggregateType = aggregateTypeResolver.tryResolveFromEventTable(change.table())
                .orElse(null);
        if (aggregateType == null) {
            long unresolved = insertsWithUnknownAggregateCount.incrementAndGet();
            if (log.isDebugEnabled()) {
                log.debug("Dropping INSERT row — no aggregate registered for table '{}' (insertsSeen={}, insertsWithUnknownAggregate={})",
                          change.table(), insertsSeenCount.get(), unresolved);
            }
            return Optional.empty();
        }

        try {
            return Optional.of(toPersistedEvent(aggregateType, change.values()));
        } catch (Exception e) {
            log.warn("Failed to convert pgoutput row for aggregateType='{}'. table='{}' keys={}",
                     aggregateType, change.table(), change.values().keySet(), e);
            throw new JSONSerializationException("Failed to convert pgoutput row change to PersistedEvent", e);
        }
    }

    public Optional<WalGlobalOrdersExtractor.Gap> extractGap(PgOutputRowChange change) {
        if (change == null) return Optional.empty();
        if (!"insert".equalsIgnoreCase(change.kind())) return Optional.empty();

        var aggregateType = aggregateTypeResolver.tryResolveFromEventTable(change.table())
                .orElse(null);
        if (aggregateType == null) return Optional.empty();

        try {
            return Optional.of(new WalGlobalOrdersExtractor.Gap(
                    aggregateType,
                    GlobalEventOrder.of(requiredLong(change.values(), "global_order"))
            ));
        } catch (Exception e) {
            log.debug("Failed to extract pgoutput gap for aggregateType='{}', table='{}'", aggregateType, change.table(), e);
            return Optional.empty();
        }
    }

    private PersistedEvent toPersistedEvent(AggregateType aggregateType,
                                            Map<String, PgOutputRowChange.PgOutputValue> values) {
        var eventId = EventId.of(requiredText(values, "event_id"));
        // The WAL only ever carries the id as text. Deserialize it so PersistedEvent#aggregateId() holds the
        // same typed id the polling path yields via PersistedEventRowMapper — consumers must not be able to
        // tell which delivery path an event arrived on.
        var aggregateId = aggregateIdSerializerResolver.deserializeOrRaw(aggregateType, requiredObject(values, "aggregate_id"));
        var eventOrder = EventOrder.of(requiredLong(values, "event_order"));
        var eventRevision = EventRevision.of(Integer.parseInt(requiredText(values, "event_revision")));
        var globalOrder = GlobalEventOrder.of(requiredLong(values, "global_order"));
        var timestamp = parsePostgresTimestamp(requiredText(values, "timestamp"));

        var eventTypeValue = requiredText(values, "event_type");
        if (eventTypeValue.isBlank()) {
            throw new IllegalStateException("event_type was blank");
        }

        var event = new EventJSON(
                jsonSerializer,
                EventType.of(eventTypeValue),
                canonicalJson(requiredObject(values, "event_payload"))
        );

        var meta = new EventMetaDataJSON(
                jsonSerializer,
                EventMetaData.class.getName(),
                canonicalJson(optionalObject(values, "event_metadata").orElse("{}"))
        );

        Optional<EventId> causedBy = optionalText(values, "caused_by_event_id")
                .filter(s -> !s.isBlank())
                .map(EventId::of);

        Optional<CorrelationId> correlationId = optionalText(values, "correlation_id")
                .filter(s -> !s.isBlank())
                .map(CorrelationId::of);

        Optional<Tenant> tenant = optionalText(values, "tenant")
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
    }

    /**
     * Normalises a WAL column value into the JSON string that gets persisted as the event payload/metadata.
     * <p>
     * A {@code String} column already holds JSON text, so it is parsed and re-serialized to normalise formatting.
     * The round-trip goes through untyped binding, which is why {@code EssentialsObjectMappers} enables
     * {@code USE_BIG_DECIMAL_FOR_FLOATS} on both majors: without it a JSON float would bind to {@code Double} and
     * {@code 1.10} would come back out as {@code 1.1}, silently rewriting persisted payloads.
     */
    private String canonicalJson(Object raw) {
        try {
            if (raw == null) return "null";
            var value = (raw instanceof String stringValue)
                        ? jsonSerializer.deserialize(stringValue, Object.class)
                        : raw;
            return jsonSerializer.serialize(value);
        } catch (Exception e) {
            throw new JSONSerializationException("Failed to canonicalize JSON", e);
        }
    }

    private static OffsetDateTime parsePostgresTimestamp(String value) {
        try {
            return OffsetDateTime.parse(value, PG_TIMESTAMP);
        } catch (Exception e) {
            throw new IllegalStateException("Failed to parse timestamp '" + value + "'", e);
        }
    }

    private static String requiredText(Map<String, PgOutputRowChange.PgOutputValue> values, String key) {
        var value = requiredValue(values, key);
        return switch (value.kind()) {
            case TEXT -> value.textValue();
            case NULL -> throw new IllegalStateException("Column '" + key + "' was null");
            case UNCHANGED_TOAST -> throw new IllegalStateException("Column '" + key + "' was unchanged toast");
            case BINARY -> throw new IllegalStateException("Column '" + key + "' used binary pgoutput format which is not supported yet");
        };
    }

    private static Object requiredObject(Map<String, PgOutputRowChange.PgOutputValue> values, String key) {
        return optionalObject(values, key)
                .orElseThrow(() -> new IllegalStateException("Missing column '" + key + "'"));
    }

    private static Optional<Object> optionalObject(Map<String, PgOutputRowChange.PgOutputValue> values, String key) {
        var value = values.get(key);
        if (value == null) return Optional.empty();
        return switch (value.kind()) {
            case NULL -> Optional.empty();
            case UNCHANGED_TOAST -> Optional.empty();
            case TEXT -> Optional.of(value.textValue());
            case BINARY -> throw new IllegalStateException("Column '" + key + "' used binary pgoutput format which is not supported yet");
        };
    }

    private static long requiredLong(Map<String, PgOutputRowChange.PgOutputValue> values, String key) {
        return Long.parseLong(requiredText(values, key));
    }

    private static Optional<String> optionalText(Map<String, PgOutputRowChange.PgOutputValue> values, String key) {
        var value = values.get(key);
        if (value == null) return Optional.empty();
        return switch (value.kind()) {
            case NULL -> Optional.empty();
            case UNCHANGED_TOAST -> Optional.empty();
            case TEXT -> Optional.ofNullable(value.textValue());
            case BINARY -> throw new IllegalStateException("Column '" + key + "' used binary pgoutput format which is not supported yet");
        };
    }

    private static PgOutputRowChange.PgOutputValue requiredValue(Map<String, PgOutputRowChange.PgOutputValue> values, String key) {
        var value = values.get(key);
        if (value == null) throw new IllegalStateException("Missing column '" + key + "'");
        return value;
    }

    /**
     * Total number of INSERT row-changes this converter has been asked to convert. Includes
     * both successfully converted rows and those dropped due to an unknown aggregate. Exposed
     * for diagnostic logging — see {@link #insertsSeenCount}'s Javadoc.
     */
    public long getInsertsSeenCount() {
        return insertsSeenCount.get();
    }

    /**
     * Number of INSERT row-changes dropped because the table wasn't registered with the
     * {@link AggregateTypeResolver}. When this equals {@link #getInsertsSeenCount()} and both
     * are non-zero while {@code publishedEvents} is zero, the resolver is the smoking gun.
     */
    public long getInsertsWithUnknownAggregateCount() {
        return insertsWithUnknownAggregateCount.get();
    }
}
