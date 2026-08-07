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

import com.fasterxml.jackson.databind.ObjectMapper;
import dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.serializer.json.JacksonJSONEventSerializer;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.HashSet;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

class WalMessageFilterTest {
    private final DefaultWalMessageFilter filter = new DefaultWalMessageFilter(
            new JacksonJSONEventSerializer(new ObjectMapper()),
            java.util.Set.of("orders_events"));

    @Test
    void should_match_insert_into_events_table_for_string_and_bytes() {
        String wal = """
                     {
                       "change": [
                         {
                           "kind" : "INSERT",
                           "schema" : "public",
                           "table" : "orders_events"
                         }
                       ]
                     }
                     """;

        assertThat(filter.shouldPersist(wal)).isTrue();
        assertThat(filter.shouldPersist(wal.getBytes(StandardCharsets.UTF_8))).isTrue();
    }

    @Test
    void should_reject_non_insert_for_string_and_bytes() {
        String wal = """
                     {
                       "change": [
                         {
                           "kind":"update",
                           "table":"orders_events"
                         }
                       ]
                     }
                     """;

        assertThat(filter.shouldPersist(wal)).isFalse();
        assertThat(filter.shouldPersist(wal.getBytes(StandardCharsets.UTF_8))).isFalse();
    }

    @Test
    void should_reject_non_events_table_for_string_and_bytes() {
        String wal = """
                     {
                       "change": [
                         {
                           "kind":"insert",
                           "table":"orders_outbox"
                         }
                       ]
                     }
                     """;

        assertThat(filter.shouldPersist(wal)).isFalse();
        assertThat(filter.shouldPersist(wal.getBytes(StandardCharsets.UTF_8))).isFalse();
    }

    @Test
    void should_match_when_any_change_entry_is_insert_for_configured_table() {
        String wal = """
                     {
                       "change": [
                         {
                           "kind":"update",
                           "table":"orders_events"
                         },
                         {
                           "kind":"insert",
                           "table":"orders_events"
                         }
                       ]
                     }
                     """;

        assertThat(filter.shouldPersist(wal)).isTrue();
        assertThat(filter.shouldPersist(wal.getBytes(StandardCharsets.UTF_8))).isTrue();
    }

    @Test
    void should_not_mix_kind_and_table_from_different_change_entries() {
        String wal = """
                     {
                       "change": [
                         {
                           "kind":"insert",
                           "table":"orders_outbox"
                         },
                         {
                           "kind":"update",
                           "table":"orders_events"
                         }
                       ]
                     }
                     """;

        assertThat(filter.shouldPersist(wal)).isFalse();
        assertThat(filter.shouldPersist(wal.getBytes(StandardCharsets.UTF_8))).isFalse();
    }

    /**
     * Regression test for the snapshot bug: aggregates registered at runtime after the filter is
     * constructed must become visible on the next {@code shouldPersist} call. Mirrors the
     * equivalent fix in {@code DefaultAggregateTypeResolver}.
     */
    @Test
    void supplier_constructor_sees_runtime_registrations() {
        var liveTables = new HashSet<String>();
        liveTables.add("orders_events");

        var liveFilter = new DefaultWalMessageFilter(
                new JacksonJSONEventSerializer(new ObjectMapper()),
                () -> liveTables);

        String ordersInsert = """
                              {"change":[{"kind":"insert","table":"orders_events"}]}
                              """;
        String customersInsert = """
                                 {"change":[{"kind":"insert","table":"customers_events"}]}
                                 """;

        // customers_events not yet registered — filter must reject.
        assertThat(liveFilter.shouldPersist(ordersInsert)).isTrue();
        assertThat(liveFilter.shouldPersist(customersInsert)).isFalse();

        // Simulate runtime registration via addAggregateEventStreamConfiguration(...).
        liveTables.add("customers_events");

        // Next call must see the newly-registered table without rebuilding the filter.
        assertThat(liveFilter.shouldPersist(customersInsert)).isTrue();
        assertThat(liveFilter.shouldPersist(ordersInsert)).isTrue();
    }

    /**
     * Verifies the supplier is invoked on every {@code shouldPersist} call — no caching that would
     * re-introduce the snapshot bug.
     */
    @Test
    void supplier_is_invoked_on_every_shouldPersist_call() {
        var invocations = new AtomicInteger();
        var liveFilter = new DefaultWalMessageFilter(
                new JacksonJSONEventSerializer(new ObjectMapper()),
                () -> {
                    invocations.incrementAndGet();
                    return java.util.Set.of("orders_events");
                });

        String wal = """
                     {"change":[{"kind":"insert","table":"orders_events"}]}
                     """;

        liveFilter.shouldPersist(wal);
        liveFilter.shouldPersist(wal);
        liveFilter.shouldPersist(wal.getBytes(StandardCharsets.UTF_8));

        assertThat(invocations.get()).isEqualTo(3);
    }

    /**
     * Empty table set must short-circuit to {@code false} without parsing the JSON — catches
     * regressions where the supplier path accidentally matches every table.
     */
    @Test
    void empty_supplier_rejects_everything() {
        var liveFilter = new DefaultWalMessageFilter(
                new JacksonJSONEventSerializer(new ObjectMapper()),
                java.util.Set.<String>of());

        String wal = """
                     {"change":[{"kind":"insert","table":"orders_events"}]}
                     """;

        assertThat(liveFilter.shouldPersist(wal)).isFalse();
        assertThat(liveFilter.shouldPersist(wal.getBytes(StandardCharsets.UTF_8))).isFalse();
    }
}
