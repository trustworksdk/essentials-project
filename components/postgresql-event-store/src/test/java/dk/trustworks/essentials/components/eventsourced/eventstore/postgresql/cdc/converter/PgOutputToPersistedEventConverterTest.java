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
import dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.serializer.json.EssentialsJSONEventSerializers;
import dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.serializer.AggregateIdSerializer;
import dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.types.EventType;
import dk.trustworks.essentials.types.CharSequenceType;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static java.util.Map.entry;

class PgOutputToPersistedEventConverterTest {
    private final AggregateType orders = AggregateType.of("Orders");
    private final PgOutputToPersistedEventConverter converter = new PgOutputToPersistedEventConverter(
            EssentialsJSONEventSerializers.createForActiveJacksonFlavor(),
            tableName -> "orders_events".equals(tableName) ? orders : null
    );

    @Test
    void converts_insert_row_change_to_persisted_event() {
        var change = new PgOutputRowChange(
                "insert",
                7,
                "public",
                "orders_events",
                42,
                123456L,
                Map.ofEntries(
                        entry("event_id", text("evt-1")),
                        entry("aggregate_id", text("order-1")),
                        entry("event_order", text("3")),
                        entry("event_revision", text("1")),
                        entry("global_order", text("17")),
                        entry("timestamp", text("2026-04-17 10:15:30+00")),
                        entry("event_type", text("OrderCreated")),
                        entry("event_payload", text("{\"amount\": 42, \"currency\":\"DKK\"}")),
                        entry("event_metadata", text("{\"traceId\":\"abc\"}")),
                        entry("caused_by_event_id", text("cause-1")),
                        entry("correlation_id", text("corr-1")),
                        entry("tenant", text("tenant-1"))
                ),
                Map.of(),
                List.of("event_id")
        );

        var result = converter.convertIfRelevant(change);

        assertThat(result).isPresent();
        var event = result.orElseThrow();
        assertThat((Object) event.aggregateType()).isEqualTo(orders);
        assertThat(event.eventId().toString()).isEqualTo("evt-1");
        assertThat(event.aggregateId()).isEqualTo("order-1");
        assertThat(event.eventOrder().longValue()).isEqualTo(3L);
        assertThat(event.eventRevision().intValue()).isEqualTo(1);
        assertThat(event.globalEventOrder().longValue()).isEqualTo(17L);
        assertThat(event.event().getEventTypeOrNamePersistenceValue()).isEqualTo(EventType.of("OrderCreated").toString());
        assertThat(event.event().getJson()).isEqualTo("{\"amount\":42,\"currency\":\"DKK\"}");
        assertThat(event.metaData().getJson()).isEqualTo("{\"traceId\":\"abc\"}");
        assertThat(event.causedByEventId()).isPresent();
        assertThat(event.correlationId()).isPresent();
        assertThat(event.tenant()).isPresent();
    }

    /**
     * The WAL carries {@code aggregate_id} as text, but {@code PersistedEvent#aggregateId()} is contracted to
     * hold the typed id — that is what {@code PersistedEventRowMapper} produces on the polling path. When the
     * converter skipped the deserialization, CDC handed subscribers a raw {@code String} and anything relying
     * on the declared type broke under CDC only: {@code EventProcessor.forwardEventToInbox} threw
     * {@code Expected java.lang.String to be an instance of …Id} for every event it forwarded.
     */
    @Test
    void deserializes_the_aggregate_id_to_its_configured_type() {
        var typedConverter = new PgOutputToPersistedEventConverter(
                EssentialsJSONEventSerializers.createForActiveJacksonFlavor(),
                tableName -> "orders_events".equals(tableName) ? orders : null,
                aggregateType -> Optional.of(AggregateIdSerializer.serializerFor(TestOrderId.class))
        );

        var persistedEvent = typedConverter.convertIfRelevant(insertRowChangeWithAggregateId("order-1")).orElseThrow();

        assertThat(persistedEvent.aggregateId())
                .as("CDC must yield the same typed aggregate id the polling path does")
                .isInstanceOf(TestOrderId.class)
                .hasToString("order-1");
    }

    @Test
    void leaves_the_aggregate_id_as_raw_text_when_the_aggregate_type_is_unregistered() {
        var unresolvableConverter = new PgOutputToPersistedEventConverter(
                EssentialsJSONEventSerializers.createForActiveJacksonFlavor(),
                tableName -> "orders_events".equals(tableName) ? orders : null,
                aggregateType -> Optional.empty()
        );

        var persistedEvent = unresolvableConverter.convertIfRelevant(insertRowChangeWithAggregateId("order-1")).orElseThrow();

        assertThat(persistedEvent.aggregateId())
                .as("an unknown aggregate type must not fail conversion — that would quarantine a valid event")
                .isEqualTo("order-1");
    }

    private PgOutputRowChange insertRowChangeWithAggregateId(String aggregateId) {
        return new PgOutputRowChange(
                "insert", 7, "public", "orders_events", 42, 123456L,
                Map.ofEntries(
                        entry("event_id", text("evt-1")),
                        entry("aggregate_id", text(aggregateId)),
                        entry("event_order", text("3")),
                        entry("event_revision", text("1")),
                        entry("global_order", text("17")),
                        entry("timestamp", text("2026-04-17 10:15:30+00")),
                        entry("event_type", text("OrderCreated")),
                        entry("event_payload", text("{\"amount\": 42}")),
                        entry("event_metadata", text("{}"))
                ),
                Map.of(),
                List.of());
    }

    /**
     * A {@link CharSequenceType} aggregate id, matching the shape real aggregate ids use — the shape whose
     * {@code CharSequenceTypeIdSerializer} threw when handed the raw WAL text.
     */
    public static class TestOrderId extends CharSequenceType<TestOrderId> {
        public TestOrderId(CharSequence value) {
            super(value);
        }

        public static TestOrderId of(CharSequence value) {
            return new TestOrderId(value);
        }
    }

    @Test
    void ignores_non_insert_or_unmapped_tables() {
        var update = new PgOutputRowChange("update", 1, "public", "orders_events", null, null, Map.of(), Map.of(), List.of());
        var otherTable = new PgOutputRowChange("insert", 1, "public", "other_table", null, null, Map.of(), Map.of(), List.of());

        assertThat(converter.convertIfRelevant(update)).isEmpty();
        assertThat(converter.convertIfRelevant(otherTable)).isEmpty();
    }


    @Test
    void ignores_tables_when_resolver_throws_for_unknown_mapping() {
        var strictResolver = new DefaultAggregateTypeResolver(Map.of("orders_events", orders));
        var strictConverter = new PgOutputToPersistedEventConverter(
                EssentialsJSONEventSerializers.createForActiveJacksonFlavor(),
                strictResolver
        );
        var otherTable = new PgOutputRowChange("insert", 1, "public", "durable_subscriptions", null, null, Map.of(), Map.of(), List.of());

        assertThat(strictConverter.convertIfRelevant(otherTable)).isEmpty();
        assertThat(strictConverter.extractGap(otherTable)).isEmpty();
    }

    @Test
    void fails_for_binary_column_values() {
        var change = new PgOutputRowChange(
                "insert",
                7,
                "public",
                "orders_events",
                null,
                null,
                Map.of(
                        "event_id", text("evt-1"),
                        "aggregate_id", text("order-1"),
                        "event_order", text("3"),
                        "event_revision", text("1"),
                        "global_order", text("17"),
                        "timestamp", text("2026-04-17 10:15:30+00"),
                        "event_type", text("OrderCreated"),
                        "event_payload", binary(new byte[]{1, 2, 3})
                ),
                Map.of(),
                List.of()
        );

        assertThatThrownBy(() -> converter.convertIfRelevant(change))
                .hasRootCauseMessage("Column 'event_payload' used binary pgoutput format which is not supported yet");
    }

    private static PgOutputRowChange.PgOutputValue text(String value) {
        return PgOutputRowChange.PgOutputValue.text(value);
    }

    private static PgOutputRowChange.PgOutputValue binary(byte[] value) {
        return PgOutputRowChange.PgOutputValue.binary(value);
    }
}
