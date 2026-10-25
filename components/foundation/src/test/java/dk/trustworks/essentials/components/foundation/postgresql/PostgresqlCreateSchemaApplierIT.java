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
import dk.trustworks.essentials.components.foundation.transaction.jdbi.JdbiUnitOfWorkFactory;
import org.awaitility.Awaitility;
import org.jdbi.v3.core.Jdbi;
import org.junit.jupiter.api.*;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.*;

import java.sql.SQLException;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;

import static org.assertj.core.api.Assertions.*;

@Testcontainers
class PostgresqlCreateSchemaApplierIT {
    @Container
    private static final PostgreSQLContainer postgreSQLContainer = new PostgreSQLContainer("postgres:18.4")
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

    /**
     * A schema change set that needs a table a running transaction writes to, while that transaction then needs a table
     * the change set already locked - a lock cycle PostgreSQL breaks by aborting one side. Each attempt draws a value
     * from the {@code lock_both_attempts} sequence first, which the abort does not roll back: {@link #attempts()}
     */
    private static final SchemaChangeSet LOCKS_ORDERS_THEN_SHIPMENTS = set("orders", SchemaOrder.ORDER_APPLICATION,
                                                                          SchemaChange.repeatable("lock-both", "orders",
                                                                                                  "SELECT nextval('lock_both_attempts')",
                                                                                                  "LOCK TABLE orders IN SHARE MODE",
                                                                                                  "LOCK TABLE shipments IN SHARE MODE"));

    @Test
    void a_change_set_aborted_as_a_deadlock_victim_is_applied_again() throws Exception {
        var applying = deadlockTheFirstAttemptsOf(1, () -> apply(LOCKS_ORDERS_THEN_SHIPMENTS));

        assertThatCode(() -> applying.get(30, TimeUnit.SECONDS)).doesNotThrowAnyException();
        assertThat(attempts()).isEqualTo(2);
        assertThat(ledger()).contains("orders/lock-both/orders");
    }

    @Test
    void a_change_set_aborted_as_a_deadlock_victim_is_applied_again_through_a_unit_of_work_factory() throws Exception {
        var unitOfWorkFactory = new JdbiUnitOfWorkFactory(jdbi);

        var applying = deadlockTheFirstAttemptsOf(1, () -> new PostgresqlCreateSchemaApplier(unitOfWorkFactory).apply(List.of(LOCKS_ORDERS_THEN_SHIPMENTS)));

        assertThatCode(() -> applying.get(30, TimeUnit.SECONDS)).doesNotThrowAnyException();
        assertThat(attempts()).isEqualTo(2);
        assertThat(ledger()).contains("orders/lock-both/orders");
    }

    /**
     * Two deployments that keep deadlocking each other at boot: the applier gives up rather than retry forever, and
     * nothing of the change set is recorded
     */
    @Test
    void a_change_set_aborted_as_a_deadlock_victim_on_every_attempt_fails_with_the_deadlock() throws Exception {
        var applying = deadlockTheFirstAttemptsOf(PostgresqlCreateSchemaApplier.MAX_ATTEMPTS_ON_DEADLOCK, () -> apply(LOCKS_ORDERS_THEN_SHIPMENTS));

        assertThatThrownBy(() -> applying.get(30, TimeUnit.SECONDS)).satisfies(thrown -> assertThat(sqlStatesIn(thrown)).contains("40P01"));
        assertThat(attempts()).isEqualTo(PostgresqlCreateSchemaApplier.MAX_ATTEMPTS_ON_DEADLOCK);
        assertThat(ledger()).doesNotContain("orders/lock-both/orders");
    }

    /**
     * A Jdbi on Spring's {@code TransactionAwareDataSourceProxy} joins an active Spring transaction, and the applier
     * cannot tell from the Jdbi whether this one does - so it treats the transaction as the caller's
     */
    @Test
    void a_change_set_applied_through_a_jdbi_while_a_spring_transaction_is_active_is_not_applied_again() throws Exception {
        var applying = deadlockTheFirstAttemptsOf(1, () -> {
            TransactionSynchronizationManager.setActualTransactionActive(true);
            try {
                apply(LOCKS_ORDERS_THEN_SHIPMENTS);
            } finally {
                TransactionSynchronizationManager.setActualTransactionActive(false);
            }
        });

        assertThatThrownBy(() -> applying.get(30, TimeUnit.SECONDS)).satisfies(thrown -> assertThat(sqlStatesIn(thrown)).contains("40P01"));
        assertThat(attempts()).isEqualTo(1);
        assertThat(ledger()).doesNotContain("orders/lock-both/orders");
    }

    /**
     * The deadlock aborted the caller's transaction, and with it whatever the caller did before: only the caller can
     * start over
     */
    @Test
    void a_change_set_applied_in_a_unit_of_work_the_caller_started_is_not_applied_again() throws Exception {
        var unitOfWorkFactory = new JdbiUnitOfWorkFactory(jdbi);

        var applying = deadlockTheFirstAttemptsOf(1, () -> unitOfWorkFactory.usingUnitOfWork(unitOfWork -> new PostgresqlCreateSchemaApplier(unitOfWorkFactory).apply(List.of(LOCKS_ORDERS_THEN_SHIPMENTS))));

        assertThatThrownBy(() -> applying.get(30, TimeUnit.SECONDS)).satisfies(thrown -> assertThat(sqlStatesIn(thrown)).contains("40P01")
                                                                                                                         .doesNotContain("25P02"));
        assertThat(attempts()).isEqualTo(1);
        // The ledger table was created in the caller's transaction too
        assertThat(tableExists(PostgresqlCreateSchemaApplier.DEFAULT_SCHEMA_HISTORY_TABLE_NAME)).isFalse();
    }

    /**
     * Run the apply while a transaction holds {@code ROW EXCLUSIVE} on {@code shipments}, then make that transaction
     * lock {@code orders} once the apply holds {@code SHARE} on it and waits for {@code shipments} - for each of the
     * apply's first {@code deadlocks} attempts. The transaction waits longer than PostgreSQL's deadlock timeout before
     * it checks for a deadlock, so the apply is the side aborted. Its lock on {@code orders} is taken in a savepoint and
     * rolled back after each deadlock, so the apply's next attempt can take {@code SHARE} on {@code orders} again while
     * {@code shipments} stays locked.
     *
     * @param deadlocks how many of the apply's attempts in a row to abort
     * @return the apply, which the transaction no longer blocks
     */
    private Future<?> deadlockTheFirstAttemptsOf(int deadlocks, Runnable apply) {
        jdbi.useHandle(handle -> handle.execute("CREATE TABLE orders (id BIGINT); CREATE TABLE shipments (id BIGINT); CREATE SEQUENCE lock_both_attempts"));
        var executor = Executors.newSingleThreadExecutor();
        try (var runningTransaction = jdbi.open()) {
            try {
                runningTransaction.begin();
                runningTransaction.execute("SET LOCAL deadlock_timeout = '60s'");
                runningTransaction.execute("LOCK TABLE shipments IN ROW EXCLUSIVE MODE");

                var applying = executor.submit(apply);
                for (var deadlock = 1; deadlock <= deadlocks; deadlock++) {
                    Awaitility.await()
                              .atMost(Duration.ofSeconds(10))
                              .until(() -> applying.isDone() || count("SELECT count(*) FROM pg_locks WHERE relation = to_regclass('shipments') AND NOT granted") > 0);
                    if (applying.isDone()) {
                        // Gave up or succeeded before this attempt: the test's assertions tell which
                        break;
                    }
                    var savepoint = "deadlock_" + deadlock;
                    runningTransaction.savepoint(savepoint);
                    // Waits until PostgreSQL aborts the apply
                    runningTransaction.execute("LOCK TABLE orders IN ROW EXCLUSIVE MODE");
                    runningTransaction.rollbackToSavepoint(savepoint);
                }
                runningTransaction.commit();
                return applying;
            } finally {
                // A failed wait must not leave the transaction - and its locks - open
                if (runningTransaction.isInTransaction()) {
                    runningTransaction.rollback();
                }
            }
        } finally {
            executor.shutdown();
        }
    }

    /**
     * @return how many attempts of {@link #LOCKS_ORDERS_THEN_SHIPMENTS} ran its statements
     */
    private long attempts() {
        return count("SELECT CASE WHEN is_called THEN last_value ELSE 0 END FROM lock_both_attempts");
    }

    private static List<String> sqlStatesIn(Throwable thrown) {
        var sqlStates = new ArrayList<String>();
        for (var cause = thrown; cause != null; cause = cause.getCause()) {
            if (cause instanceof SQLException sqlException) {
                sqlStates.add(sqlException.getSQLState());
            }
        }
        return sqlStates;
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
