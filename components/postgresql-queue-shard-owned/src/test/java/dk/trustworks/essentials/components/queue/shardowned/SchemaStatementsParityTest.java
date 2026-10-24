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

package dk.trustworks.essentials.components.queue.shardowned;

import dk.trustworks.essentials.components.foundation.postgresql.PostgresqlSchemaStatements;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The engine's statements that take no lock on a table whose index or column is already in place must equal the
 * framework's {@link PostgresqlSchemaStatements}.
 * <p>
 * Duplicated rather than imported, for the reason {@link BootstrapLockKeyTest} gives: {@code foundation} is on this
 * module's test classpath only. A copy that drifts from the canonical one would still read as correct in review.
 */
class SchemaStatementsParityTest {

    @Test
    void create_index_unless_present_matches_the_framework() {
        var createIndex = "CREATE INDEX IF NOT EXISTS orders_visible ON orders (queue_id, shard, visible_at);";

        assertThat(ShardOwnedSchema.createIndexUnlessPresent("orders", "orders_visible", createIndex))
                .isEqualTo(PostgresqlSchemaStatements.createIndexUnlessPresent("orders", "orders_visible", createIndex));
    }

    @Test
    void add_column_unless_present_matches_the_framework() {
        var addColumn = "ALTER TABLE orders ADD COLUMN IF NOT EXISTS shipped_at timestamptz";

        assertThat(ShardOwnedSchema.addColumnUnlessPresent("orders", "shipped_at", addColumn))
                .isEqualTo(PostgresqlSchemaStatements.addColumnUnlessPresent("orders", "shipped_at", addColumn));
    }

    @Test
    void no_schema_statement_creates_an_index_or_adds_a_column_unguarded() {
        assertThat(ShardOwnedSchema.schemaStatements())
                .noneMatch(statement -> statement.strip().toUpperCase().startsWith("CREATE INDEX"))
                .noneMatch(statement -> statement.strip().toUpperCase().matches("(?s)ALTER TABLE .* ADD COLUMN .*"));
    }
}
