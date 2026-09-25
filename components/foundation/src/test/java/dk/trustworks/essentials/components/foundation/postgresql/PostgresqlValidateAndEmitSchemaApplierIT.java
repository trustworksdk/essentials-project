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

import dk.trustworks.essentials.components.foundation.postgresql.ListenNotify.SqlOperation;
import dk.trustworks.essentials.components.foundation.schema.*;
import org.jdbi.v3.core.Jdbi;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.*;

import java.nio.file.*;
import java.util.*;

import static org.assertj.core.api.Assertions.*;

@Testcontainers
class PostgresqlValidateAndEmitSchemaApplierIT {
    @Container
    private static final PostgreSQLContainer<?> postgreSQLContainer = new PostgreSQLContainer<>("postgres:18.4")
            .withDatabaseName("schema-validate-emit-db")
            .withUsername("test-user")
            .withPassword("secret-password");

    @TempDir
    Path tempDir;

    private Jdbi jdbi;

    @BeforeEach
    void setup() {
        jdbi = Jdbi.create(postgreSQLContainer.getJdbcUrl(), postgreSQLContainer.getUsername(), postgreSQLContainer.getPassword());
        // The container is shared by every test in this class
        jdbi.useHandle(handle -> handle.execute("DROP SCHEMA public CASCADE; CREATE SCHEMA public"));
    }

    @Test
    void validate_executes_nothing_and_lists_every_change_that_was_never_applied() {
        var validator = new PostgresqlValidateSchemaApplier(jdbi);

        assertThatThrownBy(() -> validator.apply(schema()))
                .isInstanceOfSatisfying(SchemaValidationException.class, e -> assertThat(e.problems()).hasSize(4)
                                                                                                        .allMatch(problem -> problem.endsWith("not applied")))
                .hasMessageContaining("'orders' change 'orders-table' on 'orders': not applied");

        assertThat(exists("orders")).isFalse();
        assertThat(exists(PostgresqlCreateSchemaApplier.DEFAULT_SCHEMA_HISTORY_TABLE_NAME)).as("not even the ledger").isFalse();
    }

    @Test
    void validate_passes_once_the_create_applier_has_applied_the_same_schema() {
        new PostgresqlCreateSchemaApplier(jdbi).apply(schema());

        new PostgresqlValidateSchemaApplier(jdbi).apply(schema());
    }

    @Test
    void validate_reports_a_change_whose_statements_differ_from_what_was_applied() {
        new PostgresqlCreateSchemaApplier(jdbi).apply(schema());
        var released = List.of(set("orders", SchemaOrder.ORDER_APPLICATION,
                                   SchemaChange.repeatable("orders-table", "orders", "CREATE TABLE IF NOT EXISTS orders (id BIGINT PRIMARY KEY, total NUMERIC)")));

        assertThatThrownBy(() -> new PostgresqlValidateSchemaApplier(jdbi).apply(released))
                .isInstanceOfSatisfying(SchemaValidationException.class, e -> assertThat(e.problems()).singleElement().asString()
                                                                                                        .contains("'orders' change 'orders-table' on 'orders': applied with other statements"));
    }

    @Test
    void emit_writes_the_script_and_fails_until_it_has_been_run_then_passes() throws Exception {
        var scriptFile = tempDir.resolve("schema/essentials.sql");
        var emitter    = new PostgresqlEmitSchemaApplier(scriptFile, jdbi);

        assertThatThrownBy(() -> emitter.apply(schema())).isInstanceOf(SchemaValidationException.class);
        assertThat(exists("orders")).as("emit executes nothing").isFalse();

        var script = Files.readString(scriptFile);
        assertThat(script).contains("-- Module orders (order 1000)")
                          .contains("-- Module orders-notifications (order 1000)")
                          .contains("pg_advisory_xact_lock(" + PostgresqlUtil.ESSENTIALS_BOOTSTRAP_LOCK_KEY + ")");
        run(script);

        assertThat(exists("orders")).isTrue();
        assertThat(exists("orders_idx")).isTrue();
        assertThat(ledgerAppliedBy()).containsOnly(PostgresqlSchemaScript.APPLIED_BY);
        new PostgresqlEmitSchemaApplier(tempDir.resolve("again.sql"), jdbi).apply(schema());
    }

    @Test
    void the_script_records_the_same_ledger_as_the_create_applier_and_may_be_re_run() {
        var script = new PostgresqlSchemaScript(PostgresqlCreateSchemaApplier.DEFAULT_SCHEMA_HISTORY_TABLE_NAME).render(schema(), "test");
        run(script);
        run(script);
        var fromScript = ledgerChecksums();

        jdbi.useHandle(handle -> handle.execute("DROP SCHEMA public CASCADE; CREATE SCHEMA public"));
        new PostgresqlCreateSchemaApplier(jdbi).apply(schema());

        assertThat(fromScript).isEqualTo(ledgerChecksums());
        assertThat(count("SELECT count(*) FROM order_events WHERE kind = 'seeded'")).as("the one-shot ran once across two runs").isEqualTo(1);
    }

    @Test
    void a_later_registration_is_appended_to_the_script_as_its_own_block() throws Exception {
        var scriptFile  = tempDir.resolve("essentials.sql");
        var emitter     = new PostgresqlEmitSchemaApplier(scriptFile, jdbi);
        var contributor = contributor("products");
        assertThatThrownBy(() -> emitter.apply(schema())).isInstanceOf(SchemaValidationException.class);

        assertThatThrownBy(() -> emitter.sinkFor(contributor).apply(List.of(SchemaChange.repeatable("products-table", "products",
                                                                                                    "CREATE TABLE IF NOT EXISTS products (id BIGINT)"))))
                .as("a registration fails validation until the appended block has been run")
                .isInstanceOf(SchemaValidationException.class);

        var script = Files.readString(scriptFile);
        assertThat(script).contains("-- Registered after start-up").contains("-- Module products");
        run(script);
        assertThat(exists("products")).isTrue();
        new PostgresqlValidateSchemaApplier(jdbi).apply(List.of(new SchemaChangeSet("products", SchemaOrder.ORDER_APPLICATION,
                                                                                     List.of(SchemaChange.repeatable("products-table", "products",
                                                                                                                     "CREATE TABLE IF NOT EXISTS products (id BIGINT)")))));
    }

    /**
     * Shapes the real contributors produce: plain DDL, a one-shot data change, and a PL/pgSQL function with a
     * trigger - dollar quotes the script's own one-shot guard must not collide with
     */
    private static List<SchemaChangeSet> schema() {
        return List.of(set("orders", SchemaOrder.ORDER_APPLICATION,
                           SchemaChange.repeatable("orders-table", "orders", "CREATE TABLE IF NOT EXISTS orders (id BIGINT PRIMARY KEY)"),
                           SchemaChange.repeatable("orders-index", "orders", "CREATE INDEX IF NOT EXISTS orders_idx ON orders (id)"),
                           SchemaChange.once("seed-events", "order_events",
                                             "CREATE TABLE IF NOT EXISTS order_events (kind TEXT)",
                                             "INSERT INTO order_events VALUES ('seeded')")),
                       set("orders-notifications", SchemaOrder.ORDER_APPLICATION,
                           SchemaChange.repeatable("notify-trigger", "orders",
                                                   ListenNotify.changeNotificationTriggerStatements("orders", List.of(SqlOperation.INSERT)).toArray(String[]::new))));
    }

    private static SchemaChangeSet set(String moduleId, int order, SchemaChange... changes) {
        return new SchemaChangeSet(moduleId, order, List.of(changes));
    }

    private static EssentialsSchemaContributor contributor(String moduleId) {
        return new EssentialsSchemaContributor() {
            @Override
            public String moduleId() {
                return moduleId;
            }

            @Override
            public int order() {
                return SchemaOrder.ORDER_APPLICATION;
            }

            @Override
            public List<SchemaChange> contribute(SchemaContext context) {
                return List.of();
            }
        };
    }

    /**
     * pgjdbc splits a multi-statement string itself, honouring dollar quotes - as psql would
     */
    private void run(String script) {
        jdbi.useHandle(handle -> {
            try (var statement = handle.getConnection().createStatement()) {
                statement.execute(script);
            } catch (java.sql.SQLException e) {
                throw new IllegalStateException(e);
            }
        });
    }

    private boolean exists(String relation) {
        return jdbi.withHandle(handle -> handle.createQuery("SELECT to_regclass(:name) IS NOT NULL").bind("name", relation).mapTo(Boolean.class).one());
    }

    private long count(String sql) {
        return jdbi.withHandle(handle -> handle.createQuery(sql).mapTo(Long.class).one());
    }

    private List<String> ledgerAppliedBy() {
        return jdbi.withHandle(handle -> handle.createQuery("SELECT DISTINCT applied_by FROM essentials_schema_history").mapTo(String.class).list());
    }

    private Map<String, String> ledgerChecksums() {
        var result = new TreeMap<String, String>();
        jdbi.useHandle(handle -> handle.createQuery("SELECT module_id || '/' || change_id || '/' || object_name AS key, checksum FROM essentials_schema_history")
                                       .map((rs, ctx) -> Map.entry(rs.getString("key"), rs.getString("checksum")))
                                       .forEach(entry -> result.put(entry.getKey(), entry.getValue())));
        return result;
    }
}
