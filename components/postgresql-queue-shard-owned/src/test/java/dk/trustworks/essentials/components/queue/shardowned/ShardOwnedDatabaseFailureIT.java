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
import org.awaitility.Awaitility;
import org.junit.jupiter.api.*;
import org.slf4j.*;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.*;

import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.*;

/**
 * The database going wrong in the ways it actually goes wrong.
 * <p>
 * Everything the engine does is a statement against PostgreSQL, so its failure modes are the
 * database's: connections cut, connections unavailable, and the server itself unreachable for a
 * while. None of these kills the application, which is what makes them dangerous — the process
 * survives, keeps its leases, and can quietly stop doing the one thing it exists to do.
 * <p>
 * The bar is the same for all of them: <b>recover, and lose nothing.</b> Stalling is the failure to
 * look for, not crashing.
 */
@Testcontainers(disabledWithoutDocker = true)
class ShardOwnedDatabaseFailureIT {
    private static final Logger log = LoggerFactory.getLogger(ShardOwnedDatabaseFailureIT.class);

    private static final short QUEUE_ID    = 1;
    private static final int   SHARD_COUNT = 4;

    @Container
    static PostgreSQLContainer<?> postgres = LabPostgres.create();

    private HikariDataSource dataSource;

    @BeforeEach
    void setUp() throws Exception {
        dataSource = pool(30);
        ShardOwnedSchema.recreate(dataSource);
        ShardOwnedSchema.registerQueue(dataSource, QUEUE_ID, SHARD_COUNT);
    }

    @AfterEach
    void tearDown() {
        if (dataSource != null) {
            dataSource.close();
        }
    }

    private HikariDataSource pool(int size) {
        var config = new HikariConfig();
        // A short socket timeout so an unreachable server surfaces as an error rather than a hang
        // that outlives the test. Production would tune this; the engine's behaviour is the same
        // either way, it just takes longer to notice.
        //
        // "&", not "?": Testcontainers' URL already carries a query string (loggerLevel=OFF). A
        // second "?" makes the parameters unparseable and pgjdbc ignores them silently — with which
        // this test passed against deliberately broken code, because no query ever timed out and the
        // failure path was never reached at all.
        config.setJdbcUrl(postgres.getJdbcUrl() + "&socketTimeout=5&connectTimeout=5");
        config.setUsername(postgres.getUsername());
        config.setPassword(postgres.getPassword());
        config.setMaximumPoolSize(size);
        config.setConnectionTimeout(3_000L);
        config.setInitializationFailTimeout(-1);
        return config == null ? null : new HikariDataSource(config);
    }

    /**
     * Starving the pool starves the <em>heartbeat</em>, not the owners.
     * <p>
     * An owner takes one connection when it starts and keeps it, so no amount of pool pressure
     * reaches its reads — an earlier version of this test assumed otherwise and passed against
     * deliberately broken code, because it never touched the path it claimed to cover. What
     * starvation actually breaks is everything that acquires a connection per use: enqueue, the
     * listener, and lease renewal. A heartbeat that cannot renew lets leases lapse, and owners that
     * lose their leases stop.
     * <p>
     * So the property is recovery through a different door than the connection-loss test: the engine
     * must re-acquire and resume once connections are available again, rather than sitting on dead
     * owners.
     */
    @Test
    void the_engine_recovers_when_the_connection_pool_is_starved() throws Exception {
        var received = new CopyOnWriteArrayList<String>();
        try (var small = pool(8);
             var queue = new ShardOwnedQueue(small, QUEUE_ID, SHARD_COUNT, "instance-1")) {
            queue.startConsuming((messageId, payload, payloadType) -> received.add(new String(payload, StandardCharsets.UTF_8)),
                                 ShardOwnerSettings.defaults(), SHARD_COUNT);

            for (var index = 0; index < 20; index++) {
                queue.enqueue(List.of(("before-" + index).getBytes(StandardCharsets.UTF_8)), 1);
            }
            Awaitility.await().atMost(Duration.ofSeconds(30))
                      .untilAsserted(() -> assertThat(received).hasSize(20));

            var hogged = new ArrayList<Connection>();
            try {
                for (var index = 0; index < 8; index++) {
                    try {
                        hogged.add(small.getConnection());
                    } catch (Exception e) {
                        break;
                    }
                }
                log.info("Holding {} connections; heartbeat and enqueue are now starved", hogged.size());
                // Long enough for several lease lifetimes to lapse without renewal.
                Thread.sleep(6_000L);
            } finally {
                for (var connection : hogged) {
                    connection.close();
                }
            }

            for (var index = 0; index < 20; index++) {
                var name = "after-" + index;
                Awaitility.await().atMost(Duration.ofSeconds(30))
                          .ignoreExceptions()
                          .untilAsserted(() -> queue.enqueue(List.of(name.getBytes(StandardCharsets.UTF_8)), 1));
            }
            Awaitility.await().atMost(Duration.ofSeconds(90))
                      .untilAsserted(() -> assertThat(received.stream().filter(m -> m.startsWith("after-")).distinct().count())
                              .as("the engine must resume once connections are available again")
                              .isEqualTo(20L));
        }
    }

    /**
     * The server itself gone for a while, then back. {@code docker pause} freezes the container:
     * connections neither succeed nor fail cleanly, which is a harsher shape than a terminated
     * connection and closer to a real outage.
     */
    @Test
    void the_engine_recovers_when_the_database_is_unreachable_and_returns() throws Exception {
        var received = new CopyOnWriteArrayList<String>();
        try (var queue = new ShardOwnedQueue(dataSource, QUEUE_ID, SHARD_COUNT, "instance-1")) {
            queue.startConsuming((messageId, payload, payloadType) -> received.add(new String(payload, StandardCharsets.UTF_8)),
                                 ShardOwnerSettings.defaults(), SHARD_COUNT);

            for (var index = 0; index < 30; index++) {
                queue.enqueue(List.of(("before-" + index).getBytes(StandardCharsets.UTF_8)), 1);
            }
            Awaitility.await().atMost(Duration.ofSeconds(30))
                      .untilAsserted(() -> assertThat(received).hasSize(30));

            docker("pause");
            log.info("Database paused");
            Thread.sleep(8_000L);
            docker("unpause");
            log.info("Database resumed");

            // The lease table went away with the server, so every lease has lapsed by now and the
            // owners have to re-establish themselves before anything can be delivered again.
            // Deterministic names, so the assertion below counts distinct messages rather than
            // whatever the receiving list happened to hold when each was built.
            for (var index = 0; index < 30; index++) {
                var name = "after-" + index;
                Awaitility.await().atMost(Duration.ofSeconds(60))
                          .ignoreExceptions()
                          .untilAsserted(() -> queue.enqueue(List.of(name.getBytes(StandardCharsets.UTF_8)), 1));
            }

            // Exactly all of them, not merely some. "isPositive" would pass with one shard recovered
            // and three permanently stalled, which is the failure this test exists to catch.
            Awaitility.await().atMost(Duration.ofSeconds(120))
                      .untilAsserted(() -> assertThat(received.stream().filter(m -> m.startsWith("after-")).distinct().count())
                              .as("every shard must resume delivering once the database returns")
                              .isEqualTo(30L));

            // And nothing enqueued before the outage was lost with it.
            assertThat(received.stream().filter(m -> m.startsWith("before-")).distinct().count()).isEqualTo(30L);
            Awaitility.await().atMost(Duration.ofSeconds(90))
                      .untilAsserted(() -> assertThat(queue.remaining()).isZero());
        }
    }

    /**
     * The wake-up listener must survive losing its connection.
     * <p>
     * Its failure is silent by construction: owners fall back to the backstop poll, so messages keep
     * arriving and only the latency changes — from sub-millisecond to up to half a second. Nothing
     * fails, nothing alerts, and the tier that the design's headline latency figure depends on is
     * simply gone until the process restarts. The connection-loss test above passes either way,
     * which is exactly why this needs its own assertion.
     */
    @Test
    void the_wake_up_listener_reconnects_after_losing_its_connection() throws Exception {
        var received = new CopyOnWriteArrayList<String>();
        try (var queue = new ShardOwnedQueue(dataSource, QUEUE_ID, SHARD_COUNT, "instance-1")) {
            queue.startConsuming((messageId, payload, payloadType) -> received.add(new String(payload, StandardCharsets.UTF_8)),
                                 ShardOwnerSettings.defaults(), SHARD_COUNT);

            for (var index = 0; index < 20; index++) {
                queue.enqueue(List.of(("before-" + index).getBytes(StandardCharsets.UTF_8)), 1);
            }
            Awaitility.await().atMost(Duration.ofSeconds(30))
                      .untilAsserted(() -> assertThat(received).hasSize(20));
            Awaitility.await().atMost(Duration.ofSeconds(20))
                      .untilAsserted(() -> assertThat(queue.notificationsReceived())
                              .as("the listener should be receiving hints before anything is broken")
                              .isPositive());
            var before = queue.notificationsReceived();

            terminateOtherBackends();
            log.info("Cut every connection, including the listener's");
            Thread.sleep(3_000L);

            for (var index = 0; index < 20; index++) {
                var name = "after-" + index;
                Awaitility.await().atMost(Duration.ofSeconds(30))
                          .ignoreExceptions()
                          .untilAsserted(() -> queue.enqueue(List.of(name.getBytes(StandardCharsets.UTF_8)), 1));
            }
            Awaitility.await().atMost(Duration.ofSeconds(60))
                      .untilAsserted(() -> assertThat(received.stream().filter(m -> m.startsWith("after-")).distinct().count())
                              .isEqualTo(20L));

            // Delivery resuming proves nothing about the listener — the backstop poll delivers too.
            // Notifications continuing to arrive is the only thing that distinguishes a live listener
            // from a dead one.
            assertThat(queue.notificationsReceived())
                    .as("the listener must reconnect, or Tier 1 is silently lost for the life of the process")
                    .isGreaterThan(before);
            // And it must have re-established LISTEN, not merely survived: a reused connection would
            // no longer be subscribed to the channel.
            assertThat(queue.listenerReconnects())
                    .as("LISTEN is per-connection and has to be re-registered on the new one")
                    .isGreaterThan(1L);
        }
    }

    /**
     * A write failure that is permanent rather than transient.
     * <p>
     * Every other failure here heals on its own — a connection comes back, a pool frees up, a server
     * unpauses. The reconnect loop added for those retries indefinitely, which is correct when the
     * fault is transient and needs justifying when it is not. Disk full, a read-only tablespace, a
     * revoked grant: the engine reconnects successfully every time and the statement fails every
     * time.
     * <p>
     * Revoking {@code DELETE} is the sharpest version, because acknowledgement is what breaks. The
     * engine can read and deliver but cannot record that it has, so at-least-once means it keeps
     * redelivering. That is the correct behaviour and it needs to be verified rather than assumed:
     * the failure modes to rule out are losing the messages, corrupting the cursor, and spinning hot
     * enough to take the database down with it.
     */
    @Test
    void a_permanent_write_failure_does_not_lose_data_and_recovers_when_repaired() throws Exception {
        var received = new CopyOnWriteArrayList<String>();
        try (var queue = new ShardOwnedQueue(dataSource, QUEUE_ID, SHARD_COUNT, "instance-1")) {
            queue.startConsuming((messageId, payload, payloadType) -> received.add(new String(payload, StandardCharsets.UTF_8)),
                                 ShardOwnerSettings.defaults(), SHARD_COUNT);

            for (var index = 0; index < 20; index++) {
                queue.enqueue(List.of(("before-" + index).getBytes(StandardCharsets.UTF_8)), 1);
            }
            Awaitility.await().atMost(Duration.ofSeconds(30))
                      .untilAsserted(() -> assertThat(received.stream().distinct().count()).isEqualTo(20L));
            Awaitility.await().atMost(Duration.ofSeconds(30))
                      .untilAsserted(() -> assertThat(queue.remaining()).isZero());

            // A trigger, not REVOKE. The test user OWNS these tables, and a table owner keeps every
            // privilege regardless of what is granted or revoked — so the first version of this test
            // revoked DELETE, changed nothing at all, and passed while acknowledging normally. The
            // tell was zero redeliveries: a queue that genuinely cannot acknowledge must redeliver.
            breakAcknowledgement();
            log.info("Acknowledgement is now impossible");

            // Prove the injection worked before asserting anything about behaviour under it.
            assertThatThrownBy(() -> execute("DELETE FROM " + ShardOwnedSchema.UNORDERED_TABLE + " WHERE false"))
                    .as("the failure injection itself has to work, or this test proves nothing")
                    .hasMessageContaining("acknowledgement disabled");

            for (var index = 0; index < 10; index++) {
                queue.enqueue(List.of(("broken-" + index).getBytes(StandardCharsets.UTF_8)), 1);
            }
            // Delivery still works; only acknowledgement is broken, so at-least-once means these get
            // redelivered rather than lost.
            Awaitility.await().atMost(Duration.ofSeconds(45))
                      .untilAsserted(() -> assertThat(received.stream().filter(m -> m.startsWith("broken-")).distinct().count())
                              .as("a queue that cannot acknowledge must still deliver")
                              .isEqualTo(10L));

            var duringOutage = received.size();
            Thread.sleep(4_000L);
            var redeliveries = received.size() - duringOutage;
            log.info("Redeliveries while acknowledgement was impossible: {}", redeliveries);

            // Zero, and that is the right answer rather than a suspicious one. The owner remembers
            // what it has already handed to a handler, so a sweep that finds those rows still present
            // does not deliver them again. Re-running a handler that already succeeded, purely
            // because the database cannot record that it did, would turn a write outage into
            // duplicate side effects — worse than the outage. A NEW owner has no such memory and
            // would redeliver, which is where at-least-once actually comes from.
            assertThat(redeliveries)
                    .as("a live owner must not redeliver work it has already handed out")
                    .isZero();

            // And the retry loop is paced, not spinning: every failed flush costs one reconnect cycle
            // at 200ms, so four seconds across four shards is tens, not thousands.
            var connectionFailures = (Long) queue.metrics().snapshot().get("connectionFailures");
            log.info("Reconnect cycles during the outage: {}", connectionFailures);
            assertThat(connectionFailures)
                    .as("a permanent write failure must not become a hot loop")
                    .isLessThan(500L);

            // Nothing may be lost while acknowledgement is impossible: the rows must still be there.
            assertThat(queue.remaining())
                    .as("messages must survive an acknowledgement outage")
                    .isPositive();

            repairAcknowledgement();
            log.info("Acknowledgement repaired");

            // Once repaired it must converge: everything acknowledged, queue empty, nothing lost.
            Awaitility.await().atMost(Duration.ofSeconds(60))
                      .untilAsserted(() -> assertThat(queue.remaining())
                              .as("the engine must drain once it can acknowledge again")
                              .isZero());
            assertThat(received.stream().filter(m -> m.startsWith("broken-")).distinct().count()).isEqualTo(10L);
            assertThat(received.stream().filter(m -> m.startsWith("before-")).distinct().count()).isEqualTo(20L);
        }
    }

    /**
     * A batch that fails part way through.
     * <p>
     * {@code enqueue} takes a list, and the design's own measurements push callers towards large
     * batches — batching is worth roughly an order of magnitude in wall-clock time. So what happens
     * when one row in a batch of a hundred is rejected is a question every caller will eventually
     * ask, and the answer has to be one of "all of them" or "none of them", never "some of them".
     * <p>
     * Partial enqueue is particularly bad for the outbox pattern this design exists to serve: a
     * caller that sees an exception will reasonably assume nothing was enqueued and retry, and half
     * the batch is then delivered twice while the caller believes it was delivered once.
     */
    @Test
    void a_batch_enqueue_is_all_or_nothing() throws Exception {
        try (var queue = new ShardOwnedQueue(dataSource, QUEUE_ID, SHARD_COUNT, "instance-1")) {
            // No consumer: this is about what reaches the table, not about delivery.
            execute("CREATE OR REPLACE FUNCTION shard_queue_reject_poison() RETURNS trigger AS $$ "
                    + "BEGIN IF encode(NEW.payload, 'escape') LIKE '%POISON%' "
                    + "THEN RAISE EXCEPTION 'rejected'; END IF; RETURN NEW; END; $$ LANGUAGE plpgsql");
            execute("CREATE TRIGGER shard_queue_reject_poison BEFORE INSERT ON " + ShardOwnedSchema.UNORDERED_TABLE
                    + " FOR EACH ROW EXECUTE FUNCTION shard_queue_reject_poison()");

            // Two sizes. A small batch is covered by the driver's implicit transaction around a
            // single sync — but pgjdbc splits large batches into chunks and syncs each one, so a
            // small batch passing says nothing about a large one. The design's own measurements push
            // callers towards large batches, so the large case is the one that matters.
            for (var batchSize : new int[]{10, 5_000}) {
                var batch = new ArrayList<byte[]>();
                for (var index = 0; index < batchSize; index++) {
                    batch.add((index == batchSize / 2 ? "POISON" : "ok-" + index).getBytes(StandardCharsets.UTF_8));
                }

                assertThatThrownBy(() -> queue.enqueue(batch, 1))
                        .as("the injection has to work at size %d, or this proves nothing", batchSize)
                        .isInstanceOf(Exception.class);

                assertThat(queue.remaining())
                        .as("a rejected batch of %d must leave nothing behind — a caller that sees an "
                            + "exception will retry, and a half-written batch becomes duplicates it "
                            + "never asked for", batchSize)
                        .isZero();
            }
        } finally {
            execute("DROP TRIGGER IF EXISTS shard_queue_reject_poison ON " + ShardOwnedSchema.UNORDERED_TABLE);
        }
    }

    /**
     * Make every DELETE on the live lane fail, the way a read-only tablespace or a full disk would:
     * the connection is fine, the statement is not.
     */
    private void breakAcknowledgement() throws Exception {
        execute("CREATE OR REPLACE FUNCTION shard_queue_block_delete() RETURNS trigger AS $$ "
                + "BEGIN RAISE EXCEPTION 'acknowledgement disabled'; END; $$ LANGUAGE plpgsql");
        execute("CREATE TRIGGER shard_queue_block_delete BEFORE DELETE ON " + ShardOwnedSchema.UNORDERED_TABLE
                + " FOR EACH STATEMENT EXECUTE FUNCTION shard_queue_block_delete()");
    }

    private void repairAcknowledgement() throws Exception {
        execute("DROP TRIGGER IF EXISTS shard_queue_block_delete ON " + ShardOwnedSchema.UNORDERED_TABLE);
    }

    private void execute(String sql) throws Exception {
        try (var connection = dataSource.getConnection();
             var statement = connection.createStatement()) {
            statement.execute(sql);
        }
    }

    /**
     * Terminate every backend except this test's own connection.
     */
    private int terminateOtherBackends() throws Exception {
        try (var connection = dataSource.getConnection();
             var statement = connection.prepareStatement(
                     "SELECT count(pg_terminate_backend(pid)) FROM pg_stat_activity"
                     + " WHERE datname = current_database() AND pid <> pg_backend_pid()")) {
            try (var resultSet = statement.executeQuery()) {
                resultSet.next();
                return resultSet.getInt(1);
            }
        }
    }

    private static void docker(String command) throws Exception {
        var process = new ProcessBuilder("docker", command, postgres.getContainerId())
                .redirectErrorStream(true).start();
        assertThat(process.waitFor(30, TimeUnit.SECONDS)).isTrue();
        assertThat(process.exitValue()).as("docker %s failed", command).isZero();
    }
}
