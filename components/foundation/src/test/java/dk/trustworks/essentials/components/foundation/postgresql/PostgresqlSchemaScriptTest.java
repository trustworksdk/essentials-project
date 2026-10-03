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

import static org.assertj.core.api.Assertions.*;

class PostgresqlSchemaScriptTest {
    private final PostgresqlSchemaScript script = new PostgresqlSchemaScript(PostgresqlCreateSchemaApplier.DEFAULT_SCHEMA_HISTORY_TABLE_NAME);

    @Test
    void module_headers_follow_the_given_order_inside_one_transaction_under_the_bootstrap_lock() {
        var text = script.render(List.of(set("first", SchemaChange.repeatable("a", "t1", "CREATE TABLE IF NOT EXISTS t1 (id INT)")),
                                         set("second", SchemaChange.repeatable("b", "t2", "CREATE TABLE IF NOT EXISTS t2 (id INT)"))),
                                 "host");

        assertThat(text.indexOf("BEGIN;")).isLessThan(text.indexOf("pg_advisory_xact_lock"));
        assertThat(text.indexOf("CREATE TABLE IF NOT EXISTS essentials_schema_history")).isLessThan(text.indexOf("-- Module first"));
        assertThat(text.indexOf("-- Module first")).isLessThan(text.indexOf("-- Module second"));
        assertThat(text.strip()).endsWith("COMMIT;");
    }

    @Test
    void identifiers_are_written_as_escaped_literals_in_the_ledger_rows() {
        var text = script.render(List.of(set("o'brien", SchemaChange.repeatable("it's", "t1", "SELECT 1"))), "host");

        assertThat(text).contains("VALUES ('o''brien', 'it''s', 't1', ");
    }

    @Test
    void a_one_shot_change_is_guarded_by_the_ledger_and_its_statements_are_dollar_quoted() {
        var text = script.render(List.of(set("m", SchemaChange.once("drop-legacy", "t1", "DROP INDEX IF EXISTS legacy_idx"))), "host");

        assertThat(text).contains("IF NOT EXISTS (SELECT 1 FROM essentials_schema_history WHERE module_id = 'm' AND change_id = 'drop-legacy' AND object_name = 't1') THEN")
                        .contains("EXECUTE $essentials_statement$DROP INDEX IF EXISTS legacy_idx$essentials_statement$;");
    }

    @Test
    void a_statement_containing_the_scripts_own_quoting_tag_is_refused() {
        var clash = set("m", SchemaChange.once("c", "t1", "SELECT '$essentials_statement$'"));

        assertThatThrownBy(() -> script.render(List.of(clash), "host")).isInstanceOf(IllegalArgumentException.class)
                                                                       .hasMessageContaining("quoting tag");
    }

    @Test
    void an_addition_carries_no_header_and_no_ledger_table() {
        var text = script.renderAddition(List.of(set("m", SchemaChange.repeatable("c", "t1", "SELECT 1"))));

        assertThat(text).contains("-- Registered after start-up")
                        .doesNotContain("CREATE TABLE IF NOT EXISTS essentials_schema_history")
                        .contains("BEGIN;")
                        .contains("COMMIT;");
    }

    private static SchemaChangeSet set(String moduleId, SchemaChange... changes) {
        return new SchemaChangeSet(moduleId, SchemaOrder.ORDER_APPLICATION, List.of(changes));
    }
}
