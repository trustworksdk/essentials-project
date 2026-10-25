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
package dk.trustworks.essentials.components.foundation.postgresql;

import dk.trustworks.essentials.components.foundation.schema.*;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The WARN set is JVM-wide, so every test here uses module ids of its own.
 */
class PostgresqlCreateSchemaApplierTableLockWarningTest {

    @Test
    void a_repeatable_change_with_a_bare_index_or_column_statement_is_warned_about_once_per_module_and_change() {
        var changeSets = List.of(new SchemaChangeSet("lock-warning-a", SchemaOrder.ORDER_APPLICATION, List.of(
                                         SchemaChange.repeatable("orders-table", "orders", "CREATE TABLE IF NOT EXISTS orders (id BIGINT, status TEXT)"),
                                         SchemaChange.repeatable("orders-status-index", "orders", "CREATE INDEX IF NOT EXISTS orders_status_idx ON orders (status)"),
                                         SchemaChange.repeatable("orders-total-column", "orders", "ALTER TABLE orders ADD COLUMN IF NOT EXISTS total NUMERIC"))),
                                 new SchemaChangeSet("lock-warning-b", SchemaOrder.ORDER_APPLICATION, List.of(
                                         SchemaChange.repeatable("orders-status-index", "orders", "CREATE INDEX IF NOT EXISTS orders_status_idx ON orders (status)"))));

        assertThat(PostgresqlCreateSchemaApplier.warnOfRepeatableChangesLockingTheirTable(changeSets))
                .containsExactly("lock-warning-a/orders-status-index", "lock-warning-a/orders-total-column", "lock-warning-b/orders-status-index");
        assertThat(PostgresqlCreateSchemaApplier.warnOfRepeatableChangesLockingTheirTable(changeSets)).as("each is warned about once").isEmpty();
    }

    @Test
    void a_one_shot_change_or_a_wrapped_statement_is_not_warned_about() {
        var changeSets = List.of(new SchemaChangeSet("lock-warning-c", SchemaOrder.ORDER_APPLICATION, List.of(
                SchemaChange.once("orders-status-index", "orders", "CREATE INDEX IF NOT EXISTS orders_status_idx ON orders (status)"),
                SchemaChange.repeatable("orders-id-index", "orders",
                                        PostgresqlSchemaStatements.createIndexUnlessPresent("orders", "orders_id_idx", "CREATE INDEX IF NOT EXISTS orders_id_idx ON orders (id)")),
                SchemaChange.repeatable("orders-total-column", "orders",
                                        PostgresqlSchemaStatements.addColumnUnlessPresent("orders", "total", "ALTER TABLE orders ADD COLUMN IF NOT EXISTS total NUMERIC")))));

        assertThat(PostgresqlCreateSchemaApplier.warnOfRepeatableChangesLockingTheirTable(changeSets)).isEmpty();
    }
}
