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
 * {@link ShardOwnedSchema#schemaStatements()} runs on every start, against tables the instances already running keep
 * writing to. A bare {@code CREATE INDEX} or {@code ALTER TABLE ... ADD COLUMN} locks its table even when the index or
 * column is already there, so each must go through {@code PostgresqlSchemaStatements}, which checks the catalog first.
 */
class ShardOwnedSchemaStatementsTest {

    @Test
    void no_schema_statement_creates_an_index_or_adds_a_column_unguarded() {
        assertThat(ShardOwnedSchema.schemaStatements()).noneMatch(PostgresqlSchemaStatements::takesTableLockEvenWhenPresent);
    }
}
