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

import java.sql.*;

import static dk.trustworks.essentials.components.queue.shardowned.SingleRowQueries.firstRow;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assumptions.assumeThat;

/**
 * Guards the plan of the pull-session claim against stale table statistics.
 * <p>
 * A shard that drains to empty and is then analyzed records {@code reltuples = 0} while keeping its pages. When the
 * next burst arrives the planner expects no rows, and a claim whose {@code UPDATE} joined the locking subquery back
 * to the table ({@code WHERE (queue_id, shard, seq) IN (...)}) became a nested loop over the shard, quadratic in its
 * size: a 32-row claim took 155 s instead of 7.5 ms at 20 000 rows per shard. The claim now selects its rows by
 * primary key. The test uses 2 000 rows per shard (2.8 s against 0.9 ms) to stay quick, and asserts on the plan,
 * not on timing, to stay deterministic.
 */
@Testcontainers(disabledWithoutDocker = true)
class ShardOwnedSessionClaimPlanIT {
    private static final short QUEUE_ID    = 1;
    private static final int   SHARD_COUNT = 4;
    private static final int   ROWS        = 2_000;

    /**
     * The claim before the fix, kept as a control: if it no longer degrades, the stale statistics were not
     * recreated and the test proves nothing
     */
    private static final String LEGACY_CLAIM = "UPDATE " + ShardOwnedSchema.UNORDERED_TABLE
            + " SET lease = ?, lease_until = now() + make_interval(secs => ? / 1000.0)"
            + " WHERE (queue_id, shard, seq) IN ("
            + "   SELECT queue_id, shard, seq FROM " + ShardOwnedSchema.UNORDERED_TABLE
            + "   WHERE queue_id = ? AND shard = ? AND visible_at <= now()"
            + "     AND (lease_until IS NULL OR lease_until <= now())"
            + "   ORDER BY seq LIMIT ? FOR UPDATE SKIP LOCKED)"
            + " RETURNING seq, payload, payload_type, attempts";

    @Container
    static PostgreSQLContainer postgres = LabPostgres.create();

    private HikariDataSource dataSource;

    @BeforeEach
    void setUp() throws Exception {
        var config = new HikariConfig();
        config.setJdbcUrl(postgres.getJdbcUrl());
        config.setUsername(postgres.getUsername());
        config.setPassword(postgres.getPassword());
        dataSource = new HikariDataSource(config);
        ShardOwnedSchema.recreate(dataSource);
        ShardOwnedSchema.registerQueue(dataSource, QUEUE_ID, SHARD_COUNT);
    }

    @AfterEach
    void tearDown() {
        if (dataSource != null) {
            dataSource.close();
        }
    }

    @Test
    void the_session_claim_does_not_nest_a_loop_over_the_shard_when_statistics_say_it_is_empty() throws Exception {
        try (var connection = dataSource.getConnection(); var statement = connection.createStatement()) {
            // Autovacuum would refresh the statistics behind the test's back
            statement.execute("ALTER TABLE " + ShardOwnedSchema.UNORDERED_TABLE + " SET (autovacuum_enabled = false)");
            fill(statement);
            statement.execute("DELETE FROM " + ShardOwnedSchema.UNORDERED_TABLE);
            statement.execute("ANALYZE " + ShardOwnedSchema.UNORDERED_TABLE);
            fill(statement);
        }

        var legacyPlan = explainAnalyze(LEGACY_CLAIM, false);
        assumeThat(legacyPlan).as("control: the legacy claim must degrade, or the stale statistics were not recreated")
                              .contains("Nested Loop");

        var plan = explainAnalyze(ShardOwnedStorage.claimForSessionSql(), true);
        System.out.printf("%n=== Session claim with stale statistics: legacy %s ms, current %s ms ===%n%n",
                          executionTime(legacyPlan), executionTime(plan));
        assertThat(plan).doesNotContain("Nested Loop");
        assertThat(plan).as("the claim must still claim a full batch").contains("\"Actual Rows\": 32");
    }

    private static String executionTime(String plan) {
        var matcher = java.util.regex.Pattern.compile("\"Execution Time\": ([0-9.]+)").matcher(plan);
        return matcher.find() ? matcher.group(1) : "?";
    }

    private static void fill(Statement statement) throws SQLException {
        statement.execute("INSERT INTO " + ShardOwnedSchema.UNORDERED_TABLE + " (queue_id, shard, seq, payload, payload_type, visible_at)"
                                  + " SELECT " + QUEUE_ID + ", g % " + SHARD_COUNT + ", g, '\\x0102'::bytea, 1, now() - interval '1 hour'"
                                  + " FROM generate_series(1, " + ROWS * SHARD_COUNT + ") g");
    }

    /**
     * EXPLAIN ANALYZE executes the claim, so it runs in a transaction that is rolled back
     */
    private String explainAnalyze(String claimSql, boolean bindsQueueAndShardTwice) throws SQLException {
        try (var connection = dataSource.getConnection()) {
            connection.setAutoCommit(false);
            try (var statement = connection.prepareStatement("EXPLAIN (ANALYZE, FORMAT JSON) " + claimSql)) {
                var index = 1;
                statement.setLong(index++, -5L);
                statement.setLong(index++, 30_000L);
                statement.setShort(index++, QUEUE_ID);
                statement.setInt(index++, 0);
                if (bindsQueueAndShardTwice) {
                    statement.setShort(index++, QUEUE_ID);
                    statement.setInt(index++, 0);
                }
                statement.setInt(index, 32);
                try (var resultSet = statement.executeQuery()) {
                    return firstRow(resultSet, "EXPLAIN ANALYZE of the claim").getString(1);
                }
            } finally {
                connection.rollback();
            }
        }
    }
}
