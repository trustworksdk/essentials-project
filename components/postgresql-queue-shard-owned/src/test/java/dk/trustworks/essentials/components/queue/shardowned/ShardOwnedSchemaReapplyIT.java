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

import com.zaxxer.hikari.*;
import org.junit.jupiter.api.*;
import org.testcontainers.junit.jupiter.*;
import org.testcontainers.postgresql.PostgreSQLContainer;

import java.sql.SQLException;
import java.util.*;
import java.util.concurrent.*;

import static org.assertj.core.api.Assertions.*;

/**
 * Every instance runs {@link ShardOwnedSchema#initialize} - or the schema harness runs the same statements - when it
 * starts, while the instances already running keep writing to the engine's tables. Against a schema that is already in
 * place it must take no lock on those tables. {@code CREATE INDEX IF NOT EXISTS} took {@code SHARE} on the lane and
 * dead-letter tables even when the index existed, and {@code ALTER TABLE ... ADD COLUMN IF NOT EXISTS}
 * {@code ACCESS EXCLUSIVE} on the dead-letter and membership tables even when the column existed, each held until the
 * whole schema transaction committed: every start waited for the enqueues, acknowledgements, dead-letter moves and
 * heartbeats in flight, blocked the next ones meanwhile, and the stronger lock taken after the weaker one could close a
 * lock cycle with a running write, which PostgreSQL breaks with "deadlock detected".
 */
@Testcontainers(disabledWithoutDocker = true)
class ShardOwnedSchemaReapplyIT {
    private static final long REAPPLY_TIMEOUT_SECONDS = 10;

    @Container
    static PostgreSQLContainer postgres = LabPostgres.create();

    private HikariDataSource dataSource;
    private ExecutorService  executor;

    @BeforeEach
    void setUp() throws Exception {
        var config = new HikariConfig();
        config.setJdbcUrl(postgres.getJdbcUrl());
        config.setUsername(postgres.getUsername());
        config.setPassword(postgres.getPassword());
        dataSource = new HikariDataSource(config);
        ShardOwnedSchema.recreate(dataSource);
        executor = Executors.newSingleThreadExecutor();
    }

    @AfterEach
    void tearDown() {
        if (executor != null) {
            executor.shutdownNow();
        }
        if (dataSource != null) {
            dataSource.close();
        }
    }

    @Test
    void initializing_an_existing_schema_does_not_wait_for_writes_in_flight() throws Exception {
        var tables = List.of(ShardOwnedSchema.UNORDERED_TABLE,
                             ShardOwnedSchema.ORDERED_TABLE,
                             ShardOwnedSchema.DLQ_TABLE,
                             ShardOwnedSchema.LEASE_TABLE,
                             ShardOwnedSchema.INSTANCE_TABLE,
                             ShardOwnedSchema.REGISTRY_TABLE);
        try (var writer = dataSource.getConnection()) {
            writer.setAutoCommit(false);
            try (var statement = writer.createStatement()) {
                // What every enqueue, acknowledgement, dead-letter move and heartbeat holds until it commits
                for (var table : tables) {
                    statement.execute("LOCK TABLE " + table + " IN ROW EXCLUSIVE MODE");
                }
            }

            var reapply = executor.submit(() -> {
                ShardOwnedSchema.initialize(dataSource);
                return null;
            });
            try {
                reapply.get(REAPPLY_TIMEOUT_SECONDS, TimeUnit.SECONDS);
            } catch (TimeoutException e) {
                fail("Initializing the existing schema waited for a transaction writing to " + tables + " - waiting for: " + locksWaitedFor());
            } finally {
                writer.rollback();
                reapply.cancel(true);
            }
        }
    }

    @Test
    void initializing_creates_an_index_and_a_column_that_are_missing() throws Exception {
        try (var connection = dataSource.getConnection(); var statement = connection.createStatement()) {
            statement.execute("DROP INDEX " + ShardOwnedSchema.UNORDERED_TABLE + "_visible");
            statement.execute("ALTER TABLE " + ShardOwnedSchema.INSTANCE_TABLE + " DROP COLUMN incarnation");
        }
        assertThat(indexExists(ShardOwnedSchema.UNORDERED_TABLE + "_visible")).isFalse();
        assertThat(columnExists(ShardOwnedSchema.INSTANCE_TABLE, "incarnation")).isFalse();

        ShardOwnedSchema.initialize(dataSource);

        assertThat(indexExists(ShardOwnedSchema.UNORDERED_TABLE + "_visible")).isTrue();
        assertThat(columnExists(ShardOwnedSchema.INSTANCE_TABLE, "incarnation")).isTrue();
    }

    private List<String> locksWaitedFor() throws SQLException {
        var waitingFor = new ArrayList<String>();
        try (var connection = dataSource.getConnection();
             var statement = connection.createStatement();
             var rows = statement.executeQuery("SELECT relation::regclass::text || ' (' || mode || ')' FROM pg_locks WHERE NOT granted AND relation IS NOT NULL")) {
            while (rows.next()) {
                waitingFor.add(rows.getString(1));
            }
        }
        return waitingFor;
    }

    private boolean indexExists(String indexName) throws SQLException {
        return exists("SELECT EXISTS (SELECT 1 FROM pg_indexes WHERE indexname = ?)", indexName);
    }

    private boolean columnExists(String tableName, String columnName) throws SQLException {
        return exists("SELECT EXISTS (SELECT 1 FROM information_schema.columns WHERE table_name = ? AND column_name = ?)", tableName, columnName);
    }

    private boolean exists(String sql, String... parameters) throws SQLException {
        try (var connection = dataSource.getConnection(); var statement = connection.prepareStatement(sql)) {
            for (var i = 0; i < parameters.length; i++) {
                statement.setString(i + 1, parameters[i]);
            }
            try (var rows = statement.executeQuery()) {
                rows.next();
                return rows.getBoolean(1);
            }
        }
    }
}
