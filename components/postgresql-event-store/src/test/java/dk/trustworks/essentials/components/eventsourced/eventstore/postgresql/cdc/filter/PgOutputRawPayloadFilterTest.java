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

package dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.cdc.filter;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.HashSet;
import java.util.Set;
import java.util.function.Consumer;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Exercises {@link PgOutputRawPayloadFilter} against hand-crafted pgoutput binary messages.
 * Validates that the tailer-side pre-filter drops everything that would have produced zero
 * events in the dispatcher (B/C envelopes, U/D/T/Y/O/M) while correctly identifying INSERTs
 * on tracked event-stream tables versus non-event tables.
 */
class PgOutputRawPayloadFilterTest {

    @Test
    void drops_begin_and_commit_messages() {
        var filter = new PgOutputRawPayloadFilter(() -> Set.of("orders_events"));
        assertThat(filter.shouldPersist(bytes(out -> {
            out.write('B');
            writeLong(out, 1L);
            writeLong(out, 2L);
            writeInt(out, 3);
        }))).isFalse();

        assertThat(filter.shouldPersist(bytes(out -> {
            out.write('C');
            out.write(0);
            writeLong(out, 4L);
            writeLong(out, 5L);
            writeLong(out, 6L);
        }))).isFalse();
    }

    @ParameterizedTest
    @ValueSource(chars = {'U', 'D', 'T', 'Y', 'O', 'M', 'S', 'E', 'c', 'A', 'X'})
    void drops_non_data_and_non_insert_messages(char messageType) {
        var filter = new PgOutputRawPayloadFilter(() -> Set.of("orders_events"));
        byte[] payload = bytes(out -> out.write(messageType));
        assertThat(filter.shouldPersist(payload))
                .as("message type '%c' should be dropped by the pgoutput pre-filter", messageType)
                .isFalse();
    }

    @Test
    void keeps_relation_messages_and_populates_cache() {
        var filter = new PgOutputRawPayloadFilter(() -> Set.of("orders_events"));
        byte[] relation = relationMessage(42, "public", "orders_events");

        assertThat(filter.shouldPersist(relation))
                .as("RELATION messages must be persisted — the dispatcher's own decoder relies on them")
                .isTrue();

        assertThat(filter.getRelationIdToTableSnapshot())
                .containsEntry(42, "public.orders_events");
    }

    @Test
    void keeps_insert_for_tracked_event_stream_table() {
        var filter = new PgOutputRawPayloadFilter(() -> Set.of("orders_events"));
        filter.shouldPersist(relationMessage(42, "public", "orders_events")); // populates cache

        byte[] insert = insertMessage(42);
        assertThat(filter.shouldPersist(insert)).isTrue();
    }

    @Test
    void drops_insert_for_table_not_in_event_stream_supplier() {
        var filter = new PgOutputRawPayloadFilter(() -> Set.of("orders_events"));
        filter.shouldPersist(relationMessage(99, "public", "durable_queues")); // not an event-stream table

        byte[] insert = insertMessage(99);
        assertThat(filter.shouldPersist(insert)).isFalse();
    }

    @Test
    void insert_with_unknown_relation_id_is_conservatively_persisted() {
        var filter = new PgOutputRawPayloadFilter(() -> Set.of("orders_events"));
        // No RELATION message sent first — cache miss.
        byte[] insert = insertMessage(1234);
        assertThat(filter.shouldPersist(insert))
                .as("conservative fallback when the relation-id cache doesn't contain the id — " +
                            "prefer a wasted inbox byte over silently dropping a possibly-important row")
                .isTrue();
    }

    @Test
    void supplier_is_live_so_runtime_aggregate_registration_takes_effect() {
        var mutableTables = new HashSet<String>(Set.of("orders_events"));
        var filter = new PgOutputRawPayloadFilter(() -> mutableTables);

        filter.shouldPersist(relationMessage(42, "public", "orders_events"));
        filter.shouldPersist(relationMessage(43, "public", "customers_events"));

        // customers_events not registered yet
        assertThat(filter.shouldPersist(insertMessage(43))).isFalse();

        // Register it at runtime — mirror the aggregate-type supplier pattern
        mutableTables.add("customers_events");
        assertThat(filter.shouldPersist(insertMessage(43))).isTrue();
    }

    @Test
    void loose_match_allows_bare_supplier_entries_to_match_qualified_relation_names() {
        // Supplier returns bare names (no schema prefix) — matches the real
        // getSeparateTablePerEventStreamTableNameAggregates() keys.
        var filter = new PgOutputRawPayloadFilter(() -> Set.of("orders_events"));
        filter.shouldPersist(relationMessage(42, "public", "orders_events"));

        assertThat(filter.shouldPersist(insertMessage(42))).isTrue();
    }

    @Test
    void case_insensitive_match_on_table_names() {
        var filter = new PgOutputRawPayloadFilter(() -> Set.of("Orders_Events"));
        filter.shouldPersist(relationMessage(42, "PUBLIC", "ORDERS_EVENTS"));

        assertThat(filter.shouldPersist(insertMessage(42))).isTrue();
    }

    @Test
    void empty_payload_is_dropped() {
        var filter = new PgOutputRawPayloadFilter(() -> Set.of("orders_events"));
        assertThat(filter.shouldPersist(new byte[0])).isFalse();
        assertThat(filter.shouldPersist((byte[]) null)).isFalse();
    }

    @Test
    void string_variant_is_never_applicable_for_pgoutput() {
        // pgoutput is a binary protocol — any text input should be treated as non-applicable.
        var filter = new PgOutputRawPayloadFilter(() -> Set.of("orders_events"));
        assertThat(filter.shouldPersist("not a pgoutput payload")).isFalse();
    }

    @Test
    void clear_cache_forces_unknown_relation_fallback_path() {
        var filter = new PgOutputRawPayloadFilter(() -> Set.of("orders_events"));
        filter.shouldPersist(relationMessage(99, "public", "durable_queues"));
        assertThat(filter.shouldPersist(insertMessage(99))).isFalse();

        // After clearRelationCache() — subsequent INSERT for the same relation-id hits the
        // unknown-relation fallback (persist-conservatively) until a fresh R arrives.
        filter.clearRelationCache();
        assertThat(filter.shouldPersist(insertMessage(99))).isTrue();
    }

    // -------- pgoutput binary builders --------

    private static byte[] relationMessage(int relationId, String namespace, String relationName) {
        return bytes(out -> {
            out.write('R');
            writeInt(out, relationId);
            writeCString(out, namespace);
            writeCString(out, relationName);
            // The filter only reads up to relationName; we leave the rest of the R body empty.
            // The real pgoutput format would have more bytes here, but trailing bytes are
            // irrelevant for the filter's purposes.
        });
    }

    private static byte[] insertMessage(int relationId) {
        return bytes(out -> {
            out.write('I');
            writeInt(out, relationId);
            out.write('N');
            // Tuple body is ignored by the filter — just put a zero-length tuple.
            writeShort(out, 0);
        });
    }

    private static byte[] bytes(Consumer<ByteArrayOutputStream> writer) {
        var out = new ByteArrayOutputStream();
        writer.accept(out);
        return out.toByteArray();
    }

    private static void writeInt(ByteArrayOutputStream out, int value) {
        out.writeBytes(ByteBuffer.allocate(4).putInt(value).array());
    }

    private static void writeShort(ByteArrayOutputStream out, int value) {
        out.writeBytes(ByteBuffer.allocate(2).putShort((short) value).array());
    }

    private static void writeLong(ByteArrayOutputStream out, long value) {
        out.writeBytes(ByteBuffer.allocate(8).putLong(value).array());
    }

    private static void writeCString(ByteArrayOutputStream out, String value) {
        out.writeBytes(value.getBytes(StandardCharsets.UTF_8));
        out.write(0);
    }
}
