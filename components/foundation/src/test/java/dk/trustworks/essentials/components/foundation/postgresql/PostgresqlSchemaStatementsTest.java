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

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.*;

class PostgresqlSchemaStatementsTest {
    @Test
    void an_index_statement_runs_only_when_the_catalog_has_no_index_of_that_name_on_the_table() {
        var statement = PostgresqlSchemaStatements.createIndexUnlessPresent("orders", "idx_orders_status", "CREATE INDEX IF NOT EXISTS idx_orders_status ON orders (status);");

        assertThat(statement).startsWith("DO $essentials_schema$")
                             .endsWith("$essentials_schema$")
                             .contains("i.indrelid = to_regclass('orders')")
                             .contains("c.relname = left(lower('idx_orders_status'), 63)")
                             .contains("        CREATE INDEX IF NOT EXISTS idx_orders_status ON orders (status);\n    END IF;");
    }

    @Test
    void a_column_statement_runs_only_when_the_catalog_has_no_live_column_of_that_name_on_the_table() {
        var statement = PostgresqlSchemaStatements.addColumnUnlessPresent("orders", "status", "ALTER TABLE orders ADD COLUMN IF NOT EXISTS status TEXT");

        assertThat(statement).startsWith("DO $essentials_schema$")
                             .contains("attrelid = to_regclass('orders')")
                             .contains("attname = left(lower('status'), 63)")
                             .contains("NOT attisdropped")
                             .contains("        ALTER TABLE orders ADD COLUMN IF NOT EXISTS status TEXT;\n    END IF;");
    }

    @Test
    void names_are_validated_because_they_are_concatenated_into_the_statement() {
        assertThatThrownBy(() -> PostgresqlSchemaStatements.createIndexUnlessPresent("orders'); DROP TABLE orders; --", "idx", "CREATE INDEX idx ON orders (id)"))
                .isInstanceOf(InvalidTableOrColumnNameException.class);
        assertThatThrownBy(() -> PostgresqlSchemaStatements.createIndexUnlessPresent("orders", "idx'", "CREATE INDEX idx ON orders (id)"))
                .isInstanceOf(InvalidTableOrColumnNameException.class);
        assertThatThrownBy(() -> PostgresqlSchemaStatements.addColumnUnlessPresent("orders", "select", "ALTER TABLE orders ADD COLUMN x TEXT"))
                .isInstanceOf(InvalidTableOrColumnNameException.class);
    }

    @Test
    void an_index_name_derived_from_a_table_name_of_full_length_is_accepted() {
        var indexName = "idx_" + "o".repeat(63) + "_status";

        assertThat(PostgresqlSchemaStatements.createIndexUnlessPresent("orders", indexName, "CREATE INDEX IF NOT EXISTS " + indexName + " ON orders (status)"))
                .contains("c.relname = left(lower('" + indexName + "'), 63)");
    }

    @Test
    void a_statement_containing_the_dollar_quote_is_rejected() {
        assertThatThrownBy(() -> PostgresqlSchemaStatements.createIndexUnlessPresent("orders", "idx", "CREATE INDEX idx ON orders (($essentials_schema$x$essentials_schema$))"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void a_bare_index_or_column_statement_takes_a_table_lock_even_when_present() {
        assertThat(PostgresqlSchemaStatements.takesTableLockEvenWhenPresent("CREATE INDEX IF NOT EXISTS idx_orders_status ON orders (status)")).isTrue();
        assertThat(PostgresqlSchemaStatements.takesTableLockEvenWhenPresent("  create unique index if not exists idx_orders_id ON orders (id);")).isTrue();
        assertThat(PostgresqlSchemaStatements.takesTableLockEvenWhenPresent("CREATE\n    INDEX idx ON orders (id)")).isTrue();
        assertThat(PostgresqlSchemaStatements.takesTableLockEvenWhenPresent("ALTER TABLE orders ADD COLUMN IF NOT EXISTS status TEXT")).isTrue();
        assertThat(PostgresqlSchemaStatements.takesTableLockEvenWhenPresent("alter table orders\n    add if not exists status text")).isTrue();
        assertThat(PostgresqlSchemaStatements.takesTableLockEvenWhenPresent("ALTER TABLE orders ALTER COLUMN id SET NOT NULL, ADD COLUMN IF NOT EXISTS status TEXT")).isTrue();
    }

    @Test
    void a_bare_index_or_column_statement_after_another_statement_in_the_same_text_is_found() {
        assertThat(PostgresqlSchemaStatements.takesTableLockEvenWhenPresent("CREATE TABLE IF NOT EXISTS orders (id BIGINT); CREATE INDEX IF NOT EXISTS idx ON orders (id)")).isTrue();
        assertThat(PostgresqlSchemaStatements.takesTableLockEvenWhenPresent("-- the status index\nCREATE INDEX IF NOT EXISTS idx ON orders (status)")).isTrue();
        assertThat(PostgresqlSchemaStatements.takesTableLockEvenWhenPresent("/* the status index */ CREATE INDEX IF NOT EXISTS idx ON orders (status)")).isTrue();
    }

    @Test
    void a_wrapped_statement_takes_no_table_lock_when_present() {
        assertThat(PostgresqlSchemaStatements.takesTableLockEvenWhenPresent(
                PostgresqlSchemaStatements.createIndexUnlessPresent("orders", "idx_orders_status", "CREATE UNIQUE INDEX IF NOT EXISTS idx_orders_status ON orders (status)"))).isFalse();
        assertThat(PostgresqlSchemaStatements.takesTableLockEvenWhenPresent(
                PostgresqlSchemaStatements.addColumnUnlessPresent("orders", "status", "ALTER TABLE orders ADD COLUMN IF NOT EXISTS status TEXT"))).isFalse();
    }

    @Test
    void index_and_column_text_inside_literals_comments_and_function_bodies_is_not_a_statement() {
        assertThat(PostgresqlSchemaStatements.takesTableLockEvenWhenPresent("CREATE TABLE IF NOT EXISTS orders (\n" +
                                                                                    "    id BIGINT, -- ; CREATE INDEX on id is not needed\n" +
                                                                                    "    note TEXT DEFAULT 'x; CREATE INDEX y ON orders (id)'\n" +
                                                                                    ")")).isFalse();
        assertThat(PostgresqlSchemaStatements.takesTableLockEvenWhenPresent("CREATE OR REPLACE FUNCTION f() RETURNS void AS $$ BEGIN EXECUTE 'x'; CREATE INDEX i ON t (c); END $$ LANGUAGE plpgsql")).isFalse();
        assertThat(PostgresqlSchemaStatements.takesTableLockEvenWhenPresent("COMMENT ON TABLE orders IS 'it''s; ALTER TABLE orders ADD COLUMN x INT'")).isFalse();
        assertThat(PostgresqlSchemaStatements.takesTableLockEvenWhenPresent("CREATE TABLE IF NOT EXISTS \"create index\" (id BIGINT)")).isFalse();
        assertThat(PostgresqlSchemaStatements.takesTableLockEvenWhenPresent("ALTER TABLE orders ADD CONSTRAINT orders_pk PRIMARY KEY (id)")).isFalse();
        assertThat(PostgresqlSchemaStatements.takesTableLockEvenWhenPresent("DROP INDEX IF EXISTS idx_orders_status")).isFalse();
    }

    @Test
    void a_blank_statement_is_rejected() {
        assertThatThrownBy(() -> PostgresqlSchemaStatements.addColumnUnlessPresent("orders", "status", " "))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
