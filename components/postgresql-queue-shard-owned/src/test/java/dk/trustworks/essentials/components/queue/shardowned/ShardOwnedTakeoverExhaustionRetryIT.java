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
import dk.trustworks.essentials.components.queue.shardowned.spi.*;
import org.junit.jupiter.api.*;
import org.testcontainers.junit.jupiter.*;
import org.testcontainers.postgresql.PostgreSQLContainer;

import java.sql.*;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;

import static dk.trustworks.essentials.components.queue.shardowned.ShardOwnedSchema.*;
import static org.assertj.core.api.Assertions.*;
import static org.awaitility.Awaitility.await;

/**
 * A takeover's charge is made once per ownership, and the exhaustion dead-letter pass that follows it is retried until
 * it has run once - but only then. Mostly driven through {@code onTakeover} on an owner built by hand, with the
 * dead-letter insert made to fail by a trigger: the move throws, and {@code onTakeover} is called again on the same
 * owner, which is what the pump does - on its next pass while the connection is healthy, after a reconnect when not.
 * <ul>
 *     <li>On a healthy connection the pump must retry the pass without reconnecting, and keep serving its other
 *     shards meanwhile. It used to treat the failure as a lost connection: it reconnected several times a second and
 *     logged that at WARN. A pass runs every takeover before it pumps any shard, so the throw ended each pass before
 *     any shard was pumped, and every shard the pump served stalled, whatever its position among the pump's
 *     owners.</li>
 *     <li>The retry must not charge the window a second time. The flag used to be set only after the pass, so a
 *     pass that failed left the charge unrecorded and every reconnect charged again.</li>
 *     <li>A plain reconnect after the pass has run must not run it again: it would dead-letter a row this owner
 *     itself is delivering on its last attempt.</li>
 *     <li>The dead letter's text, and the cause the observer is given, must hold for a row a pull session failed
 *     before the takeover - the live lanes keep no error, so it cannot claim that no failure was ever recorded.</li>
 * </ul>
 */
@Testcontainers(disabledWithoutDocker = true)
class ShardOwnedTakeoverExhaustionRetryIT {

    private static final short  QUEUE_ID     = 1;
    private static final int    SHARD_COUNT  = 4;
    private static final int    SHARD        = 0;
    private static final int    MAX_ATTEMPTS = 3;
    private static final String OWNER        = "owner-1";

    @Container
    static PostgreSQLContainer postgres = LabPostgres.create();

    private HikariDataSource  dataSource;
    private ShardOwnedStorage storage;
    private List<DeadLettered> deadLettered;

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
        deadLettered = new CopyOnWriteArrayList<>();
    }

    @AfterEach
    void tearDown() {
        if (dataSource != null) {
            dataSource.close();
        }
    }

    @Test
    void an_unordered_takeover_whose_dead_letter_pass_fails_charges_once_and_parks_the_exhausted_row_on_the_retry() throws Exception {
        // seq 1 has one attempt left, the first of them failed by a row lease session; seq 2 has never been delivered
        insertUnordered(1, MAX_ATTEMPTS - 2, -7L);
        try (var connection = dataSource.getConnection()) {
            assertThat(storage.failSessionRow(connection, SHARD, 1, -7L)).isTrue();
        }
        insertUnordered(2, 0, null);
        var fence = storage.acquireLease("unordered", SHARD, OWNER, 60_000).orElseThrow();
        var owner = new ShardOwner(storage, SHARD, fence, ShardOwnerSettings.defaults(), (id, payload, type) -> { },
                                   metrics(), RedeliveryPolicy.fixed(Duration.ofSeconds(1), MAX_ATTEMPTS), new ShardWakeup(),
                                   OWNER, HandlerDispatch.inline(), false);

        assertTakeoverRetriedAfterAFailedDeadLetterPass(owner, UNORDERED_TABLE, MessageId.Lane.UNORDERED);

        // A plain reconnect: a row this owner is delivering on its last attempt is not parked under it
        insertUnordered(3, MAX_ATTEMPTS, null);
        try (var connection = dataSource.getConnection()) {
            owner.onTakeover(connection);
        }
        assertThat(attempts(UNORDERED_TABLE, 3)).as("not charged by a reconnect").isEqualTo(MAX_ATTEMPTS);
        assertThat(deadLetterError(3)).as("not dead-lettered by a reconnect").isEmpty();
    }

    @Test
    void an_ordered_takeover_whose_dead_letter_pass_fails_charges_once_and_parks_the_exhausted_row_on_the_retry() throws Exception {
        insertOrdered("key-1", 0, 1, MAX_ATTEMPTS - 1);
        insertOrdered("key-2", 0, 2, 0);
        var fence = storage.acquireLease("ordered", SHARD, OWNER, 60_000).orElseThrow();
        var owner = new OrderedShardOwner(storage, SHARD, fence, ShardOwnerSettings.defaults(), (id, key, payload, type) -> { },
                                          metrics(), RedeliveryPolicy.fixed(Duration.ofSeconds(1), MAX_ATTEMPTS), OWNER,
                                          new ShardWakeup(), HandlerDispatch.inline(), false);

        assertTakeoverRetriedAfterAFailedDeadLetterPass(owner, ORDERED_TABLE, MessageId.Lane.ORDERED);

        insertOrdered("key-3", 0, 3, MAX_ATTEMPTS);
        try (var connection = dataSource.getConnection()) {
            owner.onTakeover(connection);
        }
        assertThat(attempts(ORDERED_TABLE, 3)).as("not charged by a reconnect").isEqualTo(MAX_ATTEMPTS);
        assertThat(deadLetterError(3)).as("not dead-lettered by a reconnect").isEmpty();
    }

    @Test
    void a_pump_retries_a_failing_dead_letter_pass_on_a_healthy_connection_and_keeps_serving_its_other_shards() throws Exception {
        // Shard 0: seq 1 reaches the limit with the takeover charge, seq 2 is charged once and then delivered
        insertUnordered(1, MAX_ATTEMPTS - 1, null);
        insertUnordered(2, 0, null);
        var otherShard = SHARD + 1;
        try (var connection = dataSource.getConnection()) {
            storage.enqueueBatch(connection, otherShard, List.of(new byte[]{1}, new byte[]{2}, new byte[]{3}), 1);
        }
        var failingDeliveries = new CopyOnWriteArrayList<MessageId>();
        var otherDeliveries   = new CopyOnWriteArrayList<MessageId>();
        var failingMetrics    = metrics();
        var failing = new ShardOwner(storage, SHARD, storage.acquireLease("unordered", SHARD, OWNER, 60_000).orElseThrow(),
                                     ShardOwnerSettings.defaults(), (id, payload, type) -> failingDeliveries.add(id), failingMetrics,
                                     RedeliveryPolicy.fixed(Duration.ofSeconds(1), MAX_ATTEMPTS), new ShardWakeup(), OWNER,
                                     HandlerDispatch.inline(), false);
        var other = new ShardOwner(storage, otherShard, storage.acquireLease("unordered", otherShard, OWNER, 60_000).orElseThrow(),
                                   ShardOwnerSettings.defaults(), (id, payload, type) -> otherDeliveries.add(id), metrics(),
                                   RedeliveryPolicy.fixed(Duration.ofSeconds(1), MAX_ATTEMPTS), new ShardWakeup(), OWNER,
                                   HandlerDispatch.inline(), false);
        var pumpMetrics = new ShardOwnerMetrics();
        var running     = new AtomicBoolean(true);
        var pump        = new ShardPump(storage, ShardOwnerSettings.defaults(), pumpMetrics, running, new AtomicBoolean(false),
                                        "pump-under-test", Optional::empty);
        failDeadLetterInserts(true);
        pump.add(failing);
        pump.add(other);
        var thread = Thread.ofPlatform().name("pump-under-test").start(pump);
        try {
            await().atMost(Duration.ofSeconds(15))
                   .untilAsserted(() -> assertThat(otherDeliveries).as("the other shard keeps delivering").hasSize(3));
            // Long enough for several retries of the failing pass, each paced at 200 ms
            Thread.sleep(1_000);
            assertThat(deadLetterInsertRefusals()).as("the failing pass is retried on the pump's next passes").isGreaterThan(1);
            assertThat(pumpMetrics.connectionFailures.sum()).as("a failing statement is not a lost connection").isZero();
            assertThat(failingDeliveries).as("not pumped before its takeover has completed").isEmpty();
            assertThat(deadLetterError(1)).as("the injected failure must reach the dead-letter move").isEmpty();

            failDeadLetterInserts(false);
            await().atMost(Duration.ofSeconds(15))
                   .untilAsserted(() -> {
                       assertThat(deadLetterError(1)).contains(ShardOwnedStorage.EXHAUSTED_BY_TAKEOVERS);
                       assertThat(failingDeliveries).containsExactly(new MessageId(MessageId.Lane.UNORDERED, SHARD, 2));
                   });
            assertThat(failingMetrics.takeoverAttemptBumps.sum()).as("seq 1 and 2 charged once, however often the pass was retried")
                                                                 .isEqualTo(2);
            assertThat(deadLettered).containsExactly(new DeadLettered(new MessageId(MessageId.Lane.UNORDERED, SHARD, 1), MAX_ATTEMPTS,
                                                                      ShardOwnedStorage.EXHAUSTED_BY_TAKEOVERS));
            assertThat(pumpMetrics.connectionFailures.sum()).isZero();
        } finally {
            running.set(false);
            pump.wakeup().signal();
            thread.join(Duration.ofSeconds(10));
        }
    }

    /**
     * Seq 1 is one charge short of the limit and seq 2 has never been charged. The first {@code onTakeover} charges
     * both and then fails to park seq 1; the second must park it without charging seq 2 again.
     */
    private void assertTakeoverRetriedAfterAFailedDeadLetterPass(LeasedOwner owner, String table, MessageId.Lane lane) throws Exception {
        failDeadLetterInserts(true);
        try (var connection = dataSource.getConnection()) {
            assertThatThrownBy(() -> owner.onTakeover(connection))
                    .as("the injected failure must reach the dead-letter move")
                    .isInstanceOf(SQLException.class)
                    .hasMessageContaining("injected dead-letter failure");
        }
        assertThat(attempts(table, 1)).as("charged, and still live").isEqualTo(MAX_ATTEMPTS);
        assertThat(attempts(table, 2)).isEqualTo(1);
        assertThat(deadLetterError(1)).isEmpty();

        // The pump reconnects and calls onTakeover again
        failDeadLetterInserts(false);
        try (var connection = dataSource.getConnection()) {
            owner.onTakeover(connection);
        }
        assertThat(attempts(table, 2)).as("the window carries exactly one charge").isEqualTo(1);
        assertThat(attempts(table, 1)).as("moved out of the live lane").isZero();
        assertThat(deadLetterError(1)).contains(ShardOwnedStorage.EXHAUSTED_BY_TAKEOVERS);
        assertThat(ShardOwnedStorage.EXHAUSTED_BY_TAKEOVERS).doesNotContain("without a failure ever being recorded");
        assertThat(deadLettered).containsExactly(new DeadLettered(new MessageId(lane, SHARD, 1), MAX_ATTEMPTS,
                                                                  ShardOwnedStorage.EXHAUSTED_BY_TAKEOVERS));
    }

    private ShardOwnerMetrics metrics() {
        return new ShardOwnerMetrics(new QueueObserver() {
            @Override
            public void deadLettered(MessageId id, int attempts, Throwable cause) {
                deadLettered.add(new DeadLettered(id, attempts, cause.getMessage()));
            }
        });
    }

    private record DeadLettered(MessageId id, int attempts, String cause) {
    }

    /**
     * A trigger rather than a broken statement: the owner's own move has to be the thing that fails.
     */
    private void failDeadLetterInserts(boolean fail) throws SQLException {
        try (var connection = dataSource.getConnection();
             var statement = connection.createStatement()) {
            if (fail) {
                // A sequence, because nextval survives the rollback the refusal causes: it counts the refused inserts
                statement.execute("DROP SEQUENCE IF EXISTS dead_letter_insert_refusals");
                statement.execute("CREATE SEQUENCE dead_letter_insert_refusals");
                statement.execute("""
                                  CREATE OR REPLACE FUNCTION fail_dead_letter_insert() RETURNS trigger AS $$
                                  BEGIN
                                      PERFORM nextval('dead_letter_insert_refusals');
                                      RAISE EXCEPTION 'injected dead-letter failure';
                                  END
                                  $$ LANGUAGE plpgsql""");
                statement.execute("CREATE TRIGGER fail_dead_letter_insert BEFORE INSERT ON " + DLQ_TABLE
                                          + " FOR EACH ROW EXECUTE FUNCTION fail_dead_letter_insert()");
            } else {
                statement.execute("DROP TRIGGER fail_dead_letter_insert ON " + DLQ_TABLE);
            }
        }
    }

    private long deadLetterInsertRefusals() throws SQLException {
        try (var connection = dataSource.getConnection();
             var statement = connection.createStatement();
             var result = statement.executeQuery(
                     "SELECT CASE WHEN is_called THEN last_value ELSE 0 END FROM dead_letter_insert_refusals")) {
            result.next();
            return result.getLong(1);
        }
    }

    private void insertOrdered(String key, long keyOrder, long seq, int attempts) throws SQLException {
        try (var connection = dataSource.getConnection();
             var statement = connection.prepareStatement(
                     "INSERT INTO " + ORDERED_TABLE + " (queue_id, shard, msg_key, key_order, seq, payload, payload_type, attempts)"
                             + " VALUES (?, ?, ?, ?, ?, '\\x00'::bytea, 1, ?)")) {
            statement.setShort(1, QUEUE_ID);
            statement.setInt(2, SHARD);
            statement.setString(3, key);
            statement.setLong(4, keyOrder);
            statement.setLong(5, seq);
            statement.setInt(6, attempts);
            statement.executeUpdate();
        }
    }

    /**
     * @param sessionLease the row lease of a live pull session holding the row, or null for none
     */
    private void insertUnordered(long seq, int attempts, Long sessionLease) throws SQLException {
        try (var connection = dataSource.getConnection();
             var statement = connection.prepareStatement(
                     "INSERT INTO " + UNORDERED_TABLE + " (queue_id, shard, seq, payload, payload_type, attempts, lease, lease_until)"
                             + " VALUES (?, ?, ?, '\\x00'::bytea, 1, ?, ?, CASE WHEN ?::bigint IS NULL THEN NULL ELSE now() + interval '1 hour' END)")) {
            statement.setShort(1, QUEUE_ID);
            statement.setInt(2, SHARD);
            statement.setLong(3, seq);
            statement.setInt(4, attempts);
            statement.setObject(5, sessionLease);
            statement.setObject(6, sessionLease);
            statement.executeUpdate();
        }
    }

    private int attempts(String table, long seq) throws SQLException {
        try (var connection = dataSource.getConnection()) {
            return storage.storedAttempts(connection, table, SHARD, seq);
        }
    }

    private Optional<String> deadLetterError(long seq) throws SQLException {
        try (var connection = dataSource.getConnection();
             var statement = connection.prepareStatement("SELECT last_error FROM " + DLQ_TABLE + " WHERE queue_id = ? AND shard = ? AND seq = ?")) {
            statement.setShort(1, QUEUE_ID);
            statement.setInt(2, SHARD);
            statement.setLong(3, seq);
            try (var resultSet = statement.executeQuery()) {
                return resultSet.next() ? Optional.of(resultSet.getString(1)) : Optional.empty();
            }
        }
    }
}
