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
package dk.trustworks.essentials.components.eventlog;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.*;

class AggregateTypeTablesTest {

    @Test
    void names_are_derived_lower_cased_with_the_el_prefix() {
        var tables = AggregateTypeTables.of("Orders");
        assertThat(tables.eventTable()).isEqualTo("el_orders_events");
        assertThat(tables.headTable()).isEqualTo("el_orders_heads");
        assertThat(tables.streamOrderConstraint()).isEqualTo("el_orders_events_stream_order");
        assertThat(tables.eventIdIndex()).isEqualTo("el_orders_events_event_id");
    }

    @Test
    void the_longest_allowed_aggregate_type_fits_every_name_in_63_bytes() {
        var type   = "a".repeat(AggregateTypeTables.MAX_AGGREGATE_TYPE_LENGTH);
        var tables = AggregateTypeTables.of(type);
        assertThat(tables.streamOrderConstraint()).hasSize(63);
    }

    @Test
    void a_name_that_would_exceed_63_bytes_is_refused_not_truncated() {
        assertThatThrownBy(() -> AggregateTypeTables.of("a".repeat(AggregateTypeTables.MAX_AGGREGATE_TYPE_LENGTH + 1)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("exceeds 63 bytes");
    }

    @Test
    void an_aggregate_type_that_is_not_an_identifier_is_refused() {
        assertThatThrownBy(() -> AggregateTypeTables.of("orders; DROP TABLE x")).isInstanceOf(RuntimeException.class);
        assertThatThrownBy(() -> AggregateTypeTables.of("order-lines")).isInstanceOf(RuntimeException.class);
    }

    @Test
    void every_append_statement_takes_the_xid_first() {
        var statements = EventLogStorage.TableStatements.of(AggregateTypeTables.of("Orders"));
        for (var sql : new String[]{statements.appendAfter(), statements.appendNewStream(), statements.appendWithoutExpectation()}) {
            assertThat(sql).startsWith(EventLogStorage.XID_CTE);
            assertThat(sql).contains("FROM xid");
        }
    }
}
