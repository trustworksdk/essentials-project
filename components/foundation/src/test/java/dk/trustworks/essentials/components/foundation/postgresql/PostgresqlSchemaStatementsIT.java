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

import org.jdbi.v3.core.Jdbi;
import org.junit.jupiter.api.*;
import org.testcontainers.junit.jupiter.*;
import org.testcontainers.postgresql.PostgreSQLContainer;

import static org.assertj.core.api.Assertions.*;

/**
 * A repeatable schema change runs on every start, while other instances write to its table: when what it ensures is
 * already in place it must not lock the table
 */
@Testcontainers
class PostgresqlSchemaStatementsIT {
    @Container
    private static final PostgreSQLContainer postgreSQLContainer = new PostgreSQLContainer("postgres:18.4")
            .withDatabaseName("schema-statements-db")
            .withUsername("test-user")
            .withPassword("secret-password");

    private static final String LONG_INDEX_NAME = "idx_orders_" + "x".repeat(70);

    private Jdbi jdbi;

    @BeforeEach
    void setup() {
        jdbi = Jdbi.create(postgreSQLContainer.getJdbcUrl(), postgreSQLContainer.getUsername(), postgreSQLContainer.getPassword());
        // The container is shared by every test in this class
        jdbi.useHandle(handle -> handle.execute("DROP SCHEMA public CASCADE; CREATE SCHEMA public"));
        jdbi.useHandle(handle -> handle.execute("CREATE TABLE orders (id BIGINT PRIMARY KEY, status TEXT)"));
    }

    @Test
    void a_missing_index_is_created() {
        executeWhileOrdersIsWrittenTo(createStatusIndex("idx_orders_status"), false);

        assertThat(indexExists("idx_orders_status")).isTrue();
    }

    @Test
    void an_existing_index_takes_no_lock_on_the_table() {
        execute(createStatusIndex("idx_orders_status"));

        assertThatCode(() -> executeWhileOrdersIsWrittenTo(createStatusIndex("idx_orders_status"), true)).doesNotThrowAnyException();
    }

    @Test
    void an_index_created_with_an_upper_case_name_is_found_under_its_folded_name() {
        execute(createStatusIndex("IDX_Orders_Status"));

        assertThatCode(() -> executeWhileOrdersIsWrittenTo(createStatusIndex("IDX_Orders_Status"), true)).doesNotThrowAnyException();
    }

    @Test
    void an_index_created_with_a_name_longer_than_postgresql_keeps_is_found_under_its_truncated_name() {
        execute(createStatusIndex(LONG_INDEX_NAME));

        assertThatCode(() -> executeWhileOrdersIsWrittenTo(createStatusIndex(LONG_INDEX_NAME), true)).doesNotThrowAnyException();
    }

    @Test
    void a_missing_column_is_added() {
        executeWhileOrdersIsWrittenTo(addColumn("shipped_ts"), false);

        assertThat(columnExists("shipped_ts")).isTrue();
    }

    @Test
    void an_existing_column_takes_no_lock_on_the_table() {
        execute(addColumn("shipped_ts"));

        assertThatCode(() -> executeWhileOrdersIsWrittenTo(addColumn("shipped_ts"), true)).doesNotThrowAnyException();
    }

    @Test
    void a_dropped_column_is_added_again() {
        execute(addColumn("shipped_ts"));
        jdbi.useHandle(handle -> handle.execute("ALTER TABLE orders DROP COLUMN shipped_ts"));

        execute(addColumn("shipped_ts"));

        assertThat(columnExists("shipped_ts")).isTrue();
    }

    private static String createStatusIndex(String indexName) {
        return PostgresqlSchemaStatements.createIndexUnlessPresent("orders", indexName, "CREATE INDEX IF NOT EXISTS " + indexName + " ON orders (status)");
    }

    private static String addColumn(String columnName) {
        return PostgresqlSchemaStatements.addColumnUnlessPresent("orders", columnName, "ALTER TABLE orders ADD COLUMN IF NOT EXISTS " + columnName + " TIMESTAMPTZ");
    }

    private void execute(String statement) {
        jdbi.useHandle(handle -> handle.execute(statement));
    }

    /**
     * Run the statement while another transaction holds ROW EXCLUSIVE on the table - what every in-flight insert, update
     * or delete holds. With {@code writerHolds} the writer keeps it until the statement is done, and a statement that
     * waits for it fails on its lock timeout; otherwise the writer commits first, so the statement can create
     */
    private void executeWhileOrdersIsWrittenTo(String statement, boolean writerHolds) {
        try (var writer = jdbi.open()) {
            writer.begin();
            writer.execute("LOCK TABLE orders IN ROW EXCLUSIVE MODE");
            if (!writerHolds) {
                writer.commit();
            }
            try {
                jdbi.useHandle(handle -> {
                    handle.execute("SET lock_timeout = '2s'");
                    handle.execute(statement);
                });
            } finally {
                if (writerHolds) {
                    writer.rollback();
                }
            }
        }
    }

    private boolean indexExists(String indexName) {
        return jdbi.withHandle(handle -> handle.createQuery("SELECT EXISTS (SELECT 1 FROM pg_indexes WHERE tablename = 'orders' AND indexname = :name)")
                                               .bind("name", indexName)
                                               .mapTo(Boolean.class)
                                               .one());
    }

    private boolean columnExists(String columnName) {
        return jdbi.withHandle(handle -> handle.createQuery("SELECT EXISTS (SELECT 1 FROM information_schema.columns WHERE table_name = 'orders' AND column_name = :name)")
                                               .bind("name", columnName)
                                               .mapTo(Boolean.class)
                                               .one());
    }
}
