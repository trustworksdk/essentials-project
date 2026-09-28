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
import org.jdbi.v3.core.Jdbi;
import org.junit.jupiter.api.*;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.*;

import java.util.*;
import java.util.concurrent.*;

import static org.assertj.core.api.Assertions.*;

@Testcontainers
class PostgresqlCreateSchemaApplierIT {
    @Container
    private static final PostgreSQLContainer<?> postgreSQLContainer = new PostgreSQLContainer<>("postgres:18.4")
            .withDatabaseName("schema-harness-db")
            .withUsername("test-user")
            .withPassword("secret-password");

    private Jdbi jdbi;

    @BeforeEach
    void setup() {
        jdbi = Jdbi.create(postgreSQLContainer.getJdbcUrl(), postgreSQLContainer.getUsername(), postgreSQLContainer.getPassword());
        // The container is shared by every test in this class
        jdbi.useHandle(handle -> handle.execute("DROP SCHEMA public CASCADE; CREATE SCHEMA public"));
    }

    @Test
    void creates_the_ledger_and_the_described_objects_and_records_each_change() {
        apply(set("orders", SchemaOrder.ORDER_APPLICATION,
                  SchemaChange.repeatable("orders-table", "orders", "CREATE TABLE IF NOT EXISTS orders (id BIGINT PRIMARY KEY)"),
                  SchemaChange.once("orders-index", "orders_idx", "CREATE INDEX IF NOT EXISTS orders_idx ON orders (id)")));

        assertThat(tableExists("orders")).isTrue();
        assertThat(ledger()).containsExactlyInAnyOrder("orders/orders-table/orders", "orders/orders-index/orders_idx");
    }

    @Test
    void a_one_shot_change_runs_once_and_a_repeatable_change_runs_every_time() {
        jdbi.useHandle(handle -> handle.execute("CREATE TABLE runs (change_id TEXT)"));
        var changes = set("counter", SchemaOrder.ORDER_APPLICATION,
                          SchemaChange.once("once", "runs", "INSERT INTO runs VALUES ('once')"),
                          SchemaChange.repeatable("every", "runs", "INSERT INTO runs VALUES ('every')"));

        apply(changes);
        apply(changes);
        apply(changes);

        assertThat(count("SELECT count(*) FROM runs WHERE change_id = 'once'")).isEqualTo(1);
        assertThat(count("SELECT count(*) FROM runs WHERE change_id = 'every'")).isEqualTo(3);
    }

    @Test
    void a_one_shot_change_edited_after_it_was_applied_fails_before_anything_runs() {
        apply(set("orders", SchemaOrder.ORDER_APPLICATION,
                  SchemaChange.once("orders-table", "orders", "CREATE TABLE orders (id BIGINT)")));
        jdbi.useHandle(handle -> handle.execute("CREATE TABLE marker (id INT)"));

        assertThatThrownBy(() -> apply(set("earlier", SchemaOrder.ORDER_INFRASTRUCTURE,
                                           SchemaChange.once("drop-marker", "marker", "DROP TABLE marker")),
                                       set("orders", SchemaOrder.ORDER_APPLICATION,
                                           SchemaChange.once("orders-table", "orders", "CREATE TABLE orders (id BIGINT, total NUMERIC)"))))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("'orders' change 'orders-table' on 'orders'")
                .hasMessageContaining("recorded checksum");

        assertThat(tableExists("marker")).as("the check runs before any change set executes").isTrue();
    }

    @Test
    void a_repeatable_change_may_change_its_statements() {
        apply(set("orders", SchemaOrder.ORDER_APPLICATION,
                  SchemaChange.repeatable("orders-table", "orders", "CREATE TABLE IF NOT EXISTS orders (id BIGINT)")));

        apply(set("orders", SchemaOrder.ORDER_APPLICATION,
                  SchemaChange.repeatable("orders-table", "orders", "CREATE TABLE IF NOT EXISTS orders (id BIGINT)",
                                          "ALTER TABLE orders ADD COLUMN IF NOT EXISTS total NUMERIC")));

        assertThat(count("SELECT count(*) FROM information_schema.columns WHERE table_name = 'orders' AND column_name = 'total'")).isEqualTo(1);
    }

    @Test
    void an_invalid_object_name_is_rejected_before_anything_runs() {
        assertThatThrownBy(() -> apply(set("fine", SchemaOrder.ORDER_INFRASTRUCTURE,
                                           SchemaChange.once("fine-table", "fine", "CREATE TABLE fine (id INT)")),
                                       set("evil", SchemaOrder.ORDER_APPLICATION,
                                           SchemaChange.once("evil", "orders; DROP TABLE users", "SELECT 1"))))
                .isInstanceOf(RuntimeException.class);

        assertThat(tableExists("fine")).isFalse();
        assertThat(tableExists(PostgresqlCreateSchemaApplier.DEFAULT_SCHEMA_HISTORY_TABLE_NAME)).isFalse();
    }

    @Test
    void a_failing_change_set_rolls_back_with_its_ledger_rows_and_leaves_earlier_sets_applied() {
        assertThatThrownBy(() -> apply(set("first", SchemaOrder.ORDER_INFRASTRUCTURE,
                                           SchemaChange.once("a-table", "a_table", "CREATE TABLE a_table (id INT)")),
                                       set("second", SchemaOrder.ORDER_APPLICATION,
                                           SchemaChange.once("b-table", "b_table", "CREATE TABLE b_table (id INT)"),
                                           SchemaChange.once("broken", "b_table", "CREATE TABLE b_table (id INT)"))))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("'second' schema change 'broken' on 'b_table' failed");

        assertThat(tableExists("a_table")).isTrue();
        assertThat(tableExists("b_table")).isFalse();
        assertThat(ledger()).containsExactly("first/a-table/a_table");
    }

    @Test
    void statements_reach_postgresql_unparsed() {
        apply(set("functions", SchemaOrder.ORDER_APPLICATION,
                  SchemaChange.repeatable("answer-fn", "answer",
                                          """
                                          CREATE OR REPLACE FUNCTION answer() RETURNS TEXT AS $$
                                          DECLARE result TEXT;
                                          BEGIN
                                              result := 42::text;
                                              RETURN result;
                                          END;
                                          $$ LANGUAGE plpgsql
                                          """)));

        String answer = jdbi.withHandle(handle -> handle.createQuery("SELECT answer()").mapTo(String.class).one());
        assertThat(answer).isEqualTo("42");
    }

    @Test
    void concurrent_applies_serialise_behind_the_bootstrap_lock() throws Exception {
        var changes = set("orders", SchemaOrder.ORDER_APPLICATION,
                          SchemaChange.repeatable("orders-table", "orders", "CREATE TABLE IF NOT EXISTS orders (id BIGINT PRIMARY KEY)"),
                          SchemaChange.repeatable("orders-index", "orders_idx", "CREATE INDEX IF NOT EXISTS orders_idx ON orders (id)"));
        var executor = Executors.newFixedThreadPool(8);
        try {
            var start   = new CountDownLatch(1);
            var futures = new ArrayList<Future<?>>();
            for (var i = 0; i < 8; i++) {
                futures.add(executor.submit(() -> {
                    start.await();
                    apply(changes);
                    return null;
                }));
            }
            start.countDown();
            for (var future : futures) {
                future.get(60, TimeUnit.SECONDS);
            }
        } finally {
            executor.shutdownNow();
        }

        assertThat(ledger()).containsExactlyInAnyOrder("orders/orders-table/orders", "orders/orders-index/orders_idx");
    }

    private void apply(SchemaChangeSet... changeSets) {
        new PostgresqlCreateSchemaApplier(jdbi, PostgresqlCreateSchemaApplier.DEFAULT_SCHEMA_HISTORY_TABLE_NAME, "it").apply(List.of(changeSets));
    }

    private static SchemaChangeSet set(String moduleId, int order, SchemaChange... changes) {
        return new SchemaChangeSet(moduleId, order, List.of(changes));
    }

    private boolean tableExists(String table) {
        return count("SELECT count(*) FROM information_schema.tables WHERE table_name = '" + table + "'") == 1;
    }

    private long count(String sql) {
        return jdbi.withHandle(handle -> handle.createQuery(sql).mapTo(Long.class).one());
    }

    private List<String> ledger() {
        return jdbi.withHandle(handle -> handle.createQuery("SELECT module_id || '/' || change_id || '/' || object_name FROM " +
                                                                    PostgresqlCreateSchemaApplier.DEFAULT_SCHEMA_HISTORY_TABLE_NAME)
                                               .mapTo(String.class)
                                               .list());
    }
}
