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
}
