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
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assumptions.assumeThat;

/**
 * Guards the pump's multi-unit ordered read against a plan cached while the table was drained.
 * <p>
 * A pump repeats the same statements on one long-lived connection. pgjdbc server-prepares a statement from its fifth
 * use, and PostgreSQL then keeps a generic plan if it costs no more than the custom ones. Vacuumed while drained,
 * {@code shard_queue_ordered} has statistics saying it is empty, which the planner trusts: the generic plan made then
 * is a sequential scan plus sort per unit, cached until the next analyze. Under the burst that followed, each read
 * scanned the whole table once per unit, and the trading demo's router inbox crawled at ~50 messages/s for the
 * 30-40 s until auto-analyze - in the half of its runs where autovacuum got to the drained table just before the
 * order flow. A never-analyzed table does not do it: the planner assumes a size then, and picks the index.
 * <p>
 * Asserts on the sequential scans the read performs, counted in the reading transaction itself
 * ({@code pg_stat_xact_user_tables}), not on timing. A control runs the same statement server-prepared, as before
 * the fix, and must scan, or the stale plan was not reproduced and the test proves nothing.
 */
@Testcontainers(disabledWithoutDocker = true)
class ShardOwnedOrderedReadPlanIT {
    private static final short QUEUE_ID      = 1;
    private static final int   UNITS         = 32;
    private static final int   ROWS_PER_UNIT = 1_000;
    private static final int   WARM_UP_READS = 10;
    private static final int   LIMIT         = 50;

    /**
     * {@link ShardOwnedStorage#readOrderedFromCursors}'s statement, prepared the way it was before the fix. The comment
     * keeps it apart from the real one in pgjdbc's per-connection statement cache, which is keyed by the text
     */
    private static final String SERVER_PREPARED_READ =
            "/* control */ SELECT x.shard, x.seq, x.msg_key, x.key_order, x.payload, x.payload_type"
                    + " FROM unnest(?::int[], ?::bigint[]) AS cur(shard, cursor),"
                    + " LATERAL (SELECT o.shard, o.seq, o.msg_key, o.key_order, o.payload, o.payload_type"
                    + "          FROM " + ShardOwnedSchema.ORDERED_TABLE + " o"
                    + "          WHERE o.queue_id = ? AND o.shard = cur.shard::smallint AND o.seq > cur.cursor"
                    + "            AND o.visible_at <= now()"
                    + "          ORDER BY o.seq LIMIT " + LIMIT + ") x";

    @Container
    static PostgreSQLContainer postgres = LabPostgres.create();

    private HikariDataSource dataSource;
    private ShardOwnedStorage storage;

    @BeforeEach
    void setUp() throws Exception {
        var config = new HikariConfig();
        config.setJdbcUrl(postgres.getJdbcUrl());
        config.setUsername(postgres.getUsername());
        config.setPassword(postgres.getPassword());
        dataSource = new HikariDataSource(config);
        ShardOwnedSchema.recreate(dataSource);
        try (var connection = dataSource.getConnection(); var statement = connection.createStatement()) {
            // Auto-analyze would invalidate the cached plan behind the test's back - which is what ended the slow phase
            statement.execute("ALTER TABLE " + ShardOwnedSchema.ORDERED_TABLE + " SET (autovacuum_enabled = false)");
        }
        storage = new ShardOwnedStorage(dataSource, QUEUE_ID);
    }

    @AfterEach
    void tearDown() {
        if (dataSource != null) {
            dataSource.close();
        }
    }

    @Test
    void the_ordered_read_uses_the_index_after_being_planned_against_a_drained_table() throws Exception {
        int[]  units   = IntStream.range(0, UNITS).toArray();
        long[] cursors = new long[UNITS];
        // Traffic, drained, then vacuumed - as autovacuum does to a quiet queue: statistics now say empty
        fill(0);
        try (var connection = dataSource.getConnection(); var statement = connection.createStatement()) {
            statement.execute("DELETE FROM " + ShardOwnedSchema.ORDERED_TABLE);
            statement.execute("VACUUM " + ShardOwnedSchema.ORDERED_TABLE);
        }
        try (var connection = dataSource.getConnection()) {
            var control = connection.prepareStatement(SERVER_PREPARED_READ);
            for (var i = 0; i < WARM_UP_READS; i++) {
                storage.readOrderedFromCursors(connection, QUEUE_ID, units, cursors, LIMIT);
                bindAndRun(control, connection, units, cursors);
            }

            fill(ROWS_PER_UNIT * UNITS);

            connection.setAutoCommit(false);
            try {
                bindAndRun(control, connection, units, cursors);
                var controlScans = sequentialScansInThisTransaction(connection);
                assumeThat(controlScans).as("control: the server-prepared read must still scan, or the empty-table plan was not cached")
                                        .isGreaterThanOrEqualTo(UNITS);

                var rows  = storage.readOrderedFromCursors(connection, QUEUE_ID, units, cursors, LIMIT);
                var scans = sequentialScansInThisTransaction(connection) - controlScans;
                System.out.printf("%n=== Ordered read after a drained-table plan: control %d sequential scans, current %d ===%n%n", controlScans, scans);
                assertThat(scans).as("the read must not scan the table once per unit").isZero();
                assertThat(rows).as("the read must still return a full batch per unit").hasSize(UNITS);
                assertThat(rows.values()).allSatisfy(perUnit -> assertThat(perUnit).hasSize(LIMIT));
            } finally {
                connection.rollback();
                connection.setAutoCommit(true);
            }
            control.close();
        }
    }

    private static void bindAndRun(PreparedStatement statement, Connection connection, int[] units, long[] cursors) throws SQLException {
        statement.setArray(1, connection.createArrayOf("int", IntStream.of(units).boxed().toArray()));
        statement.setArray(2, connection.createArrayOf("bigint", java.util.Arrays.stream(cursors).boxed().toArray()));
        statement.setShort(3, QUEUE_ID);
        try (var resultSet = statement.executeQuery()) {
            while (resultSet.next()) {
                // drain
            }
        }
    }

    /**
     * {@code ROWS_PER_UNIT * UNITS} rows, each unit's numbered from {@code firstSeq + 1} on
     */
    private void fill(long firstSeq) throws SQLException {
        try (var connection = dataSource.getConnection(); var statement = connection.createStatement()) {
            statement.execute("INSERT INTO " + ShardOwnedSchema.ORDERED_TABLE
                                      + " (queue_id, shard, msg_key, key_order, seq, payload, payload_type, visible_at)"
                                      + " SELECT " + QUEUE_ID + ", g % " + UNITS + ", 'key-' || g, 0, g, '\\x0102'::bytea, 1, now() - interval '1 hour'"
                                      + " FROM generate_series(" + (firstSeq + 1) + ", " + (firstSeq + (long) ROWS_PER_UNIT * UNITS) + ") g");
        }
    }

    /**
     * Counted by the reading backend for its own open transaction, so visible at once and to nobody else
     */
    private static long sequentialScansInThisTransaction(Connection connection) throws SQLException {
        try (var statement = connection.prepareStatement("SELECT coalesce(seq_scan, 0) FROM pg_stat_xact_user_tables WHERE relname = ?")) {
            statement.setString(1, ShardOwnedSchema.ORDERED_TABLE);
            try (var resultSet = statement.executeQuery()) {
                return resultSet.next() ? resultSet.getLong(1) : 0;
            }
        }
    }
}
