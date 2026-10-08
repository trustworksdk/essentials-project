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
package dk.trustworks.essentials.components.foundation.schema;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class SchemaChangeTest {

    @Test
    void a_change_has_no_description_unless_one_is_given() {
        var change = new SchemaChange("table", "orders_events", List.of("SELECT 1"), true);

        assertThat(change.description()).isEmpty();
        assertThat(change.describeObject()).isEqualTo("orders_events");
    }

    @Test
    void a_description_is_not_part_of_the_ledger_identity_or_the_checksum() {
        var change    = SchemaChange.repeatable("table", "orders_events", "SELECT 1");
        var described = change.describedAs("AggregateType 'Orders'");

        assertThat(described.changeId()).isEqualTo(change.changeId());
        assertThat(described.objectName()).isEqualTo(change.objectName());
        assertThat(described.checksum()).isEqualTo(change.checksum());
        assertThat(described.describeObject()).isEqualTo("orders_events (AggregateType 'Orders')");
    }

    @Test
    void a_description_is_kept_on_one_line() {
        assertThat(SchemaChange.repeatable("table", "t1", "SELECT 1").describedAs(" first\r\nsecond\n").description()).isEqualTo("first second");
        assertThat(SchemaChange.repeatable("table", "t1", "SELECT 1").describedAs(null).description()).isEmpty();
    }

    @Test
    void a_change_set_describes_each_object_once() {
        var changeSet = new SchemaChangeSet("postgresql-event-store",
                                            SchemaOrder.ORDER_EVENT_STORE,
                                            List.of(SchemaChange.repeatable("table", "orders_events", "SELECT 1").describedAs("AggregateType 'Orders'"),
                                                    SchemaChange.repeatable("index", "orders_events", "SELECT 2").describedAs("AggregateType 'Orders'"),
                                                    SchemaChange.repeatable("table", "products_events", "SELECT 3")));

        assertThat(changeSet.describeObjects()).containsExactly("orders_events (AggregateType 'Orders')", "products_events");
        assertThat(changeSet.describe()).isEqualTo("postgresql-event-store: orders_events (AggregateType 'Orders'), products_events");
    }
}
