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

import static dk.trustworks.essentials.components.queue.shardowned.ShardOwnedSchema.*;
import static dk.trustworks.essentials.components.queue.shardowned.SingleRowQueries.queryForRow;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * Which rows a takeover charges and dead-letters, asserted on the statements themselves against rows laid out by
 * hand - the states are awkward to reach through a running owner and exact here.
 * <ul>
 *     <li>A row nobody could have been delivering is never charged, nor dead-lettered as exhausted: an ordered row
 *     queued behind a retry in backoff or behind a dead letter, and an unordered row a live pull session holds.</li>
 *     <li>A delayed ordered row does not take a place in the per-key window, so it cannot push the row that was in
 *     a handler out of it.</li>
 *     <li>A requested redelivery is not counted, yet still holds its ordered key across a takeover.</li>
 *     <li>A pull session's failure counts on the stored count, and only while the session still holds the row or
 *     the shard.</li>
 * </ul>
 */
@Testcontainers(disabledWithoutDocker = true)
class ShardOwnedTakeoverChargeIT {

    private static final short QUEUE_ID     = 1;
    private static final int   SHARD_COUNT  = 4;
    private static final int   SHARD        = 0;
    private static final int   MAX_ATTEMPTS = 3;
    /**
     * The instance taking the unit over. The charge is fenced, so it holds the unit's lease (see {@link #take}).
     */
    private static final String OWNER = "owner-1";

    @Container
    static PostgreSQLContainer postgres = LabPostgres.create();

    private HikariDataSource  dataSource;
    private ShardOwnedStorage storage;

    @BeforeEach
    void setUp() throws Exception {
        var config = new HikariConfig();
        config.setJdbcUrl(postgres.getJdbcUrl());
        config.setUsername(postgres.getUsername());
        config.setPassword(postgres.getPassword());
        config.setMaximumPoolSize(5);
        dataSource = new HikariDataSource(config);
        ShardOwnedSchema.recreate(dataSource);
        ShardOwnedSchema.registerQueue(dataSource, QUEUE_ID, SHARD_COUNT);
        storage = new ShardOwnedStorage(dataSource, QUEUE_ID);
    }

    @AfterEach
    void tearDown() {
        if (dataSource != null) {
            dataSource.close();
        }
    }

    @Test
    void rows_queued_behind_a_retry_in_backoff_are_never_charged_nor_dead_lettered_by_takeovers() throws Exception {
        // key-1: its head waits out a retry, two rows are queued behind it, and one more carries a count left from before
        insertOrdered("key-1", 0, 1, 3_600, 1, null);
        insertOrdered("key-1", 1, 2, 0, 0, null);
        insertOrdered("key-1", 2, 3, 0, 0, null);
        insertOrdered("key-1", 3, 4, 0, MAX_ATTEMPTS, null);
        // key-2: a visible head, which a crashed owner could have had in a handler - the control
        insertOrdered("key-2", 0, 5, 0, 0, null);

        try (var connection = dataSource.getConnection()) {
            for (var takeover = 0; takeover < MAX_ATTEMPTS + 2; takeover++) {
                storage.bumpOrderedAttemptsOnTakeover(connection, SHARD, 200, OWNER, take("ordered"));
            }
            assertThat(attempts(ORDERED_TABLE, 2)).as("queued behind a key held by its retry").isZero();
            assertThat(attempts(ORDERED_TABLE, 3)).as("queued behind a key held by its retry").isZero();
            assertThat(attempts(ORDERED_TABLE, 1)).as("the retry itself is not visible, so not in a handler").isEqualTo(1);
            assertThat(attempts(ORDERED_TABLE, 5)).as("the control key is charged every time").isEqualTo(MAX_ATTEMPTS + 2);

            assertThat(storage.exhaustedAfterTakeover(connection, ORDERED_TABLE, SHARD, MAX_ATTEMPTS))
                    .as("only the control key's row; seq 4 is exhausted on paper but nobody could have been delivering it")
                    .containsExactly(5L);
        }
    }

    @Test
    void rows_queued_behind_a_dead_letter_are_never_charged_nor_dead_lettered_by_takeovers() throws Exception {
        insertDeadLetter("key-1", 0, 1);
        insertOrdered("key-1", 1, 2, 0, MAX_ATTEMPTS - 1, null);

        try (var connection = dataSource.getConnection()) {
            storage.bumpOrderedAttemptsOnTakeover(connection, SHARD, 200, OWNER, take("ordered"));
            assertThat(attempts(ORDERED_TABLE, 2)).isEqualTo(MAX_ATTEMPTS - 1);
            assertThat(storage.exhaustedAfterTakeover(connection, ORDERED_TABLE, SHARD, MAX_ATTEMPTS - 1))
                    .as("parked behind the dead letter when its turn comes, never as exhausted by takeovers")
                    .isEmpty();
        }
    }

    @Test
    void a_delayed_row_ahead_of_the_row_in_a_handler_does_not_push_it_out_of_the_window() throws Exception {
        insertOrdered("key-1", 0, 1, 3_600, 0, null);
        insertOrdered("key-1", 1, 2, 3_600, 0, null);
        insertOrdered("key-1", 2, 3, 0, 0, null);

        try (var connection = dataSource.getConnection()) {
            // ackBatchSize 0: a window of one row per key
            storage.bumpOrderedAttemptsOnTakeover(connection, SHARD, 0, OWNER, take("ordered"));
        }
        assertThat(attempts(ORDERED_TABLE, 3)).as("the first VISIBLE row of its key").isEqualTo(1);
        assertThat(attempts(ORDERED_TABLE, 1)).as("a delayed row is never charged").isZero();
        assertThat(attempts(ORDERED_TABLE, 2)).as("a delayed row is never charged").isZero();
    }

    @Test
    void a_requested_redelivery_is_not_counted_and_still_holds_its_ordered_key_across_a_takeover() throws Exception {
        insertOrdered("key-1", 0, 1, 0, 0, null);
        insertOrdered("key-1", 1, 2, 0, 0, null);
        // A key nothing holds: the charge must land on it, so a charge refused outright cannot pass for one that skipped key-1
        insertOrdered("key-2", 0, 3, 0, 0, null);
        insertUnordered(10, 2, null, null);
        var orderedFence   = take("ordered");
        var unorderedFence = take("unordered");

        try (var connection = dataSource.getConnection()) {
            assertThat(storage.scheduleRequestedRedeliveryIfOwned(connection, ORDERED_TABLE, SHARD, 1, 3_600_000, OWNER, orderedFence)).isTrue();
            assertThat(storage.scheduleRequestedRedeliveryIfOwned(connection, UNORDERED_TABLE, SHARD, 10, 3_600_000, OWNER, unorderedFence)).isTrue();
            assertThat(attempts(ORDERED_TABLE, 1)).as("a requested redelivery is not an attempt").isZero();
            assertThat(attempts(UNORDERED_TABLE, 10)).as("a requested redelivery is not an attempt").isEqualTo(2);

            assertThat(storage.orderedRetriesInBackoff(connection, SHARD))
                    .as("a successor holds the key until the requested redelivery is due")
                    .extracting(retry -> retry.row().seq())
                    .containsExactly(1L);
            assertThat(storage.bumpOrderedAttemptsOnTakeover(connection, SHARD, 200, OWNER, orderedFence))
                    .as("only the key nothing holds is charged")
                    .isEqualTo(1);
            assertThat(attempts(ORDERED_TABLE, 3)).as("the head of a key nothing holds").isEqualTo(1);
            assertThat(attempts(ORDERED_TABLE, 2)).as("queued behind the held key").isZero();

            // A failure afterwards is a retry like any other, and clears the mark
            assertThat(storage.scheduleRetryIfOwned(connection, ORDERED_TABLE, SHARD, 1, 1, 3_600_000, OWNER, orderedFence)).isTrue();
            assertThat(lease(1)).isNull();
            // ... and the admin retry, which resets the count, leaves a plain delayed row
            assertThat(storage.scheduleRequestedRedeliveryIfOwned(connection, ORDERED_TABLE, SHARD, 1, 3_600_000, OWNER, orderedFence)).isTrue();
            assertThat(storage.retryMessage(SHARD, 1, true, 3_600_000)).isTrue();
            assertThat(storage.orderedRetriesInBackoff(connection, SHARD)).isEmpty();
        }
    }

    @Test
    void a_row_a_live_pull_session_holds_is_never_charged_nor_dead_lettered_by_a_takeover() throws Exception {
        insertUnordered(1, MAX_ATTEMPTS, -7L, 3_600);
        insertUnordered(2, MAX_ATTEMPTS, -8L, -1);

        try (var connection = dataSource.getConnection()) {
            storage.bumpAttemptsOnTakeover(connection, SHARD, 8, 200, OWNER, take("unordered"));
            assertThat(attempts(UNORDERED_TABLE, 1)).as("held by a live session").isEqualTo(MAX_ATTEMPTS);
            assertThat(attempts(UNORDERED_TABLE, 2)).as("the session's lease has lapsed").isEqualTo(MAX_ATTEMPTS + 1);
            assertThat(storage.exhaustedAfterTakeover(connection, UNORDERED_TABLE, SHARD, MAX_ATTEMPTS)).containsExactly(2L);
        }
    }

    @Test
    void a_shard_session_failure_counts_on_the_stored_count_and_is_fenced() throws Exception {
        insertUnordered(1, 2, null, null);
        var fence = storage.acquireSessionLease("unordered", SHARD, "session-1", 60_000).orElseThrow();

        try (var connection = dataSource.getConnection()) {
            assertThat(storage.countAndScheduleRetryIfOwned(connection, UNORDERED_TABLE, SHARD, 1, 1_000, "session-1", fence - 1))
                    .as("a superseded session's write lands nowhere")
                    .isFalse();
            assertThat(attempts(UNORDERED_TABLE, 1)).isEqualTo(2);
            assertThat(storage.countAndScheduleRetryIfOwned(connection, UNORDERED_TABLE, SHARD, 1, 1_000, "session-1", fence)).isTrue();
            assertThat(attempts(UNORDERED_TABLE, 1)).as("counted on the row, not reset to 1").isEqualTo(3);
        }
    }

    @Test
    void a_row_lease_session_failure_counts_on_the_stored_count_and_only_while_it_holds_the_row() throws Exception {
        insertUnordered(1, 4, -7L, 3_600);

        try (var connection = dataSource.getConnection()) {
            assertThat(storage.failSessionRow(connection, SHARD, 1, -8L)).as("another session's row").isFalse();
            assertThat(attempts(UNORDERED_TABLE, 1)).isEqualTo(4);
            assertThat(storage.failSessionRow(connection, SHARD, 1, -7L)).isTrue();
            assertThat(attempts(UNORDERED_TABLE, 1)).as("counted on the row, not reset to 1").isEqualTo(5);
            assertThat(lease(UNORDERED_TABLE, 1)).as("handed back").isNull();
        }
    }

    private void insertOrdered(String key, long keyOrder, long seq, int visibleInSeconds, int attempts, Long lease) throws SQLException {
        try (var connection = dataSource.getConnection();
             var statement = connection.prepareStatement(
                     "INSERT INTO " + ORDERED_TABLE + " (queue_id, shard, msg_key, key_order, seq, payload, payload_type, visible_at, attempts, lease)"
                             + " VALUES (?, ?, ?, ?, ?, '\\x00'::bytea, 1, now() + make_interval(secs => ?), ?, ?)")) {
            statement.setShort(1, QUEUE_ID);
            statement.setInt(2, SHARD);
            statement.setString(3, key);
            statement.setLong(4, keyOrder);
            statement.setLong(5, seq);
            statement.setInt(6, visibleInSeconds);
            statement.setInt(7, attempts);
            statement.setObject(8, lease);
            statement.executeUpdate();
        }
    }

    /**
     * @param leaseUntilInSeconds when the row lease lapses, relative to now; null for no row lease
     */
    private void insertUnordered(long seq, int attempts, Long lease, Integer leaseUntilInSeconds) throws SQLException {
        try (var connection = dataSource.getConnection();
             var statement = connection.prepareStatement(
                     "INSERT INTO " + UNORDERED_TABLE + " (queue_id, shard, seq, payload, payload_type, attempts, lease, lease_until)"
                             + " VALUES (?, ?, ?, '\\x00'::bytea, 1, ?, ?,"
                             + " CASE WHEN ?::int IS NULL THEN NULL ELSE now() + make_interval(secs => ?::int) END)")) {
            statement.setShort(1, QUEUE_ID);
            statement.setInt(2, SHARD);
            statement.setLong(3, seq);
            statement.setInt(4, attempts);
            statement.setObject(5, lease);
            statement.setObject(6, leaseUntilInSeconds);
            statement.setObject(7, leaseUntilInSeconds);
            statement.executeUpdate();
        }
    }

    private void insertDeadLetter(String key, long keyOrder, long seq) throws SQLException {
        try (var connection = dataSource.getConnection();
             var statement = connection.prepareStatement(
                     "INSERT INTO " + DLQ_TABLE + " (queue_id, shard, source_lane, msg_key, key_order, seq, payload, payload_type, attempts, last_error)"
                             + " VALUES (?, ?, 'ordered', ?, ?, ?, '\\x00'::bytea, 1, 1, 'failed')")) {
            statement.setShort(1, QUEUE_ID);
            statement.setInt(2, SHARD);
            statement.setString(3, key);
            statement.setLong(4, keyOrder);
            statement.setLong(5, seq);
            statement.executeUpdate();
        }
    }

    /**
     * Take {@link #SHARD} of {@code lane} for {@link #OWNER} under a new fence, as a takeover does.
     */
    private long take(String lane) throws SQLException {
        return storage.acquireLease(lane, SHARD, OWNER, 60_000).orElseThrow();
    }

    private int attempts(String table, long seq) throws SQLException {
        try (var connection = dataSource.getConnection()) {
            return storage.storedAttempts(connection, table, SHARD, seq);
        }
    }

    private Long lease(long seq) throws SQLException {
        return lease(ORDERED_TABLE, seq);
    }

    private Long lease(String table, long seq) throws SQLException {
        return queryForRow(dataSource, "Reading the lease of the " + table + " row of queue " + QUEUE_ID + " shard " + SHARD + " seq " + seq,
                           "SELECT lease FROM " + table + " WHERE queue_id = ? AND shard = ? AND seq = ?",
                           row -> {
                               var lease = row.getLong(1);
                               return row.wasNull() ? null : lease;
                           },
                           QUEUE_ID, SHARD, seq);
    }
}
