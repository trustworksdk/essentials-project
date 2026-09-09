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
import dk.trustworks.essentials.components.queue.shardowned.node.ShardOwnedNodeMain;
import dk.trustworks.essentials.components.queue.shardowned.*;
import org.awaitility.Awaitility;
import org.junit.jupiter.api.*;
import org.slf4j.*;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.*;

import java.io.*;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.sql.SQLException;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The engine across real operating-system processes.
 * <p>
 * Every other test here runs several instances inside one JVM — shared heap, shared collector,
 * shared clock, shared class loader. That is a model of a distributed system and it cannot produce
 * the two things that matter most: a process that genuinely dies mid-work, and two nodes whose
 * worlds are independent. Both guarantees this design makes across nodes — per-key ordering, and
 * fencing on failover — rested on reasoning until this test existed.
 * <p>
 * Deliveries are recorded into a table because a process killed with {@code SIGKILL} flushes nothing,
 * and the table's serial id supplies a global observation order across processes, which is what makes
 * cross-node ordering checkable.
 */
@Testcontainers(disabledWithoutDocker = true)
class ShardOwnedMultiProcessIT {
    private static final Logger log = LoggerFactory.getLogger(ShardOwnedMultiProcessIT.class);

    private static final short QUEUE_ID    = 1;
    private static final int   SHARD_COUNT = 8;

    @Container
    static PostgreSQLContainer<?> postgres = LabPostgres.create();

    private HikariDataSource dataSource;
    private final List<Process> nodes = new ArrayList<>();

    @BeforeEach
    void setUp() throws Exception {
        var config = new HikariConfig();
        config.setJdbcUrl(postgres.getJdbcUrl());
        config.setUsername(postgres.getUsername());
        config.setPassword(postgres.getPassword());
        config.setMaximumPoolSize(30);
        dataSource = new HikariDataSource(config);

        ShardOwnedSchema.recreate(dataSource);
        ShardOwnedSchema.registerQueue(dataSource, QUEUE_ID, SHARD_COUNT);
        try (var connection = dataSource.getConnection();
             var statement = connection.createStatement()) {
            statement.execute("DROP TABLE IF EXISTS shard_queue_observed");
            // The serial id is the observation clock. It is assigned by PostgreSQL at insert time, so
            // it orders deliveries across processes without the processes agreeing on anything.
            statement.execute("""
                              CREATE TABLE shard_queue_observed (
                                  id          bigserial PRIMARY KEY,
                                  instance_id text   NOT NULL,
                                  msg_key     text,
                                  value       bigint      NOT NULL,
                                  observed_at timestamptz NOT NULL DEFAULT now()
                              )
                              """);
        }
    }

    @AfterEach
    void tearDown() {
        nodes.forEach(Process::destroyForcibly);
        nodes.clear();
        if (dataSource != null) {
            dataSource.close();
        }
    }

    /**
     * Per-key ordering with the owners in separate processes.
     * <p>
     * This is the guarantee the current implementation explicitly does not make — its documentation
     * states that ordering across cluster nodes is not guaranteed, only within a node. Here a key
     * hashes to one shard and one process holds that shard, so the guarantee should hold across
     * processes. Until now that was an argument rather than a result.
     */
    @Test
    void ordering_per_key_holds_across_processes_and_lease_lifetimes() throws Exception {
        // Four shards each, so both processes genuinely own part of the lane.
        startNode("node-a", 4, "ordered");
        startNode("node-b", 4, "ordered");

        var keyCount = 40;
        var perKey = 20;
        var queue = new ShardOwnedQueue(dataSource, QUEUE_ID, SHARD_COUNT, "producer");

        // Enqueued over time rather than in one burst, so the run spans many lease lifetimes. The
        // node lease is holeExpiry x 3 = 900 ms; this takes roughly fifteen seconds, so the owners
        // have to renew repeatedly to keep working. An earlier version of this test enqueued
        // everything at once and finished inside a single lease lifetime — which is exactly why it
        // passed while the ordered lane had no heartbeat at all.
        var thirdNodeStarted = false;
        for (var order = 0; order < perKey; order++) {
            var batch = new ArrayList<ShardOwnedStorage.OrderedPayload>();
            for (var key = 0; key < keyCount; key++) {
                batch.add(new ShardOwnedStorage.OrderedPayload("k-" + key, order, encode(order), 1));
            }
            queue.enqueueOrdered(batch);
            Thread.sleep(750L);

            if (!thirdNodeStarted && order == perKey / 2) {
                // A third node arrives mid-run and asks for every shard. It gets a share — the
                // ordered lane sheds now — but only what the incumbents hand over after draining,
                // and never by taking a shard out from under a living owner.
                startNode("node-c", SHARD_COUNT, "ordered");
                thirdNodeStarted = true;
            }
        }

        var expected = keyCount * perKey;
        Awaitility.await().atMost(Duration.ofSeconds(90))
                  .untilAsserted(() -> assertThat(observedCount()).isEqualTo(expected));

        var byInstance = observationsPerInstance();
        log.info("Deliveries per process: {}", byInstance);
        // This assertion used to require that node-c got nothing, which was the pre-shedding
        // behaviour: the ordered lane only ever picked up shards nobody held. It sheds now, so the
        // late node legitimately ends up serving part of the lane.
        assertThat(byInstance.keySet())
                .as("every process must end up serving part of the lane")
                .containsExactlyInAnyOrder("node-a", "node-b", "node-c");
        byInstance.values().forEach(count -> assertThat(count).isPositive());

        // What the old assertion was really guarding: that the incumbents were not evicted. A node-c
        // that had taken shards out from under living owners — which is what would happen if leases
        // were not being renewed — would leave them with nothing to do from that point on.
        var afterNodeCArrived = deliveriesAfterFirstDeliveryBy("node-c");
        assertThat(afterNodeCArrived.getOrDefault("node-a", 0L))
                .as("node-a must keep delivering after the late node joined")
                .isPositive();
        assertThat(afterNodeCArrived.getOrDefault("node-b", 0L))
                .as("node-b must keep delivering after the late node joined")
                .isPositive();

        // The property, over a span many times the lease lifetime.
        assertThat(orderingViolations())
                .as("a key's messages must be delivered in key_order across processes and across lease renewals")
                .isEmpty();
    }

    /**
     * Failover across a real process death.
     * <p>
     * {@code destroyForcibly} is SIGKILL: no shutdown hook, no flush, no lease release, no chance for
     * the dying process to tidy up. Its lease simply stops being renewed. The survivor has to notice,
     * take the shards, and redeliver whatever was in flight — which is the entire reason the fast
     * path is allowed to write nothing when it consumes.
     */
    @Test
    void a_killed_process_loses_its_shards_and_nothing_is_lost() throws Exception {
        startNode("node-a", SHARD_COUNT, "unordered");
        startNode("node-b", SHARD_COUNT, "unordered");

        // Wait for the split to converge before enqueuing anything. The first node takes every shard
        // at startup and only sheds down to its fair share on a later heartbeat; without this wait the
        // first node drains the whole batch before the second has been given anything, and the test
        // then fails on a setup condition it never established rather than on the behaviour it exists
        // to check.
        Awaitility.await().atMost(Duration.ofSeconds(60))
                  .untilAsserted(() -> assertThat(liveLeaseOwners())
                          .as("both processes must hold shards before the load starts")
                          .hasSize(2));

        var queue = new ShardOwnedQueue(dataSource, QUEUE_ID, SHARD_COUNT, "producer");
        var firstBatch = 300;
        for (var index = 0; index < firstBatch; index++) {
            queue.enqueue(List.of(encode(index)), 1);
        }
        Awaitility.await().atMost(Duration.ofSeconds(90))
                  .untilAsserted(() -> assertThat(distinctObservedValues()).hasSize(firstBatch));

        var before = observationsPerInstance();
        assertThat(before).as("both processes should be sharing the load").hasSize(2);
        log.info("Before kill: {}", before);

        // SIGKILL one of them.
        var victim = nodes.getFirst();
        victim.destroyForcibly();
        assertThat(victim.waitFor(30, TimeUnit.SECONDS)).isTrue();
        log.info("Killed node-a (pid {})", victim.pid());

        // The survivor must take the abandoned shards and keep working.
        var secondBatch = 200;
        for (var index = firstBatch; index < firstBatch + secondBatch; index++) {
            queue.enqueue(List.of(encode(index)), 1);
        }

        Awaitility.await().atMost(Duration.ofSeconds(120))
                  .untilAsserted(() -> assertThat(distinctObservedValues()).hasSize(firstBatch + secondBatch));

        // Everything enqueued after the kill was handled by the survivor alone.
        var after = observationsPerInstance();
        log.info("After kill: {}", after);
        assertThat(after.get("node-b"))
                .as("the survivor must have handled the work the dead process could not")
                .isGreaterThan(before.getOrDefault("node-b", 0L));

        // At-least-once: nothing lost. Duplicates are permitted and expected for whatever was in
        // flight when the process died.
        assertThat(distinctObservedValues()).hasSize(firstBatch + secondBatch);
        Awaitility.await().atMost(Duration.ofSeconds(60))
                  .untilAsserted(() -> assertThat(queue.remaining()).isZero());
    }

    /**
     * A node that is alive but not running — the case fencing exists for, and the only one a
     * {@code SIGKILL} cannot produce.
     * <p>
     * {@code SIGSTOP} freezes the process: it holds its connections, its in-memory state and its
     * belief that it owns shards, but stops renewing leases and stops doing work. That is exactly a
     * long stop-the-world pause or a network partition from the database's point of view. The other
     * node takes the shards. Then {@code SIGCONT} resumes the first, which wakes up still convinced
     * it owns them and tries to acknowledge work it was in the middle of.
     * <p>
     * The dangerous outcome is not a duplicate — the contract permits those. It is the resumed node
     * <em>deleting</em> messages the new owner has not delivered yet, which is silent loss. Fencing
     * exists to make that acknowledgement fail, and this is the only test that puts it in that
     * position.
     */
    @Test
    void a_frozen_node_that_resumes_cannot_acknowledge_work_it_no_longer_owns() throws Exception {
        startNode("node-a", SHARD_COUNT, "unordered");
        startNode("node-b", SHARD_COUNT, "unordered");
        Awaitility.await().atMost(Duration.ofSeconds(60))
                  .untilAsserted(() -> assertThat(liveLeaseOwners()).hasSize(2));

        var queue = new ShardOwnedQueue(dataSource, QUEUE_ID, SHARD_COUNT, "producer");
        var total = 400;
        for (var index = 0; index < total; index++) {
            queue.enqueue(List.of(encode(index)), 1);
        }
        Awaitility.await().atMost(Duration.ofSeconds(90))
                  .untilAsserted(() -> assertThat(distinctObservedValues()).hasSize(total));

        var frozen = nodes.getFirst();
        signal(frozen, "STOP");
        log.info("Froze node-a (pid {})", frozen.pid());

        // Its lease must actually lapse, or the test proves nothing — a frozen node that keeps its
        // shards has not been superseded and fencing is never exercised.
        Awaitility.await().atMost(Duration.ofSeconds(60))
                  .untilAsserted(() -> assertThat(liveLeaseOwners())
                          .as("the frozen node's lease must expire and node-b must take over")
                          .containsExactly("node-b"));

        // Work that only node-b can do while node-a is frozen.
        var second = 200;
        for (var index = total; index < total + second; index++) {
            queue.enqueue(List.of(encode(index)), 1);
        }
        Awaitility.await().atMost(Duration.ofSeconds(120))
                  .untilAsserted(() -> assertThat(distinctObservedValues()).hasSize(total + second));

        signal(frozen, "CONT");
        log.info("Resumed node-a; it still believes it owns shards");
        Thread.sleep(5_000L);

        // The guarantee: nothing lost. If the resumed node's stale-fence acknowledgement had been
        // accepted, it would have deleted messages node-b had not delivered — and the only way to
        // observe that from outside the processes is that they never arrive. This assertion IS the
        // fencing check.
        assertThat(distinctObservedValues())
                .as("a resumed node must not be able to delete work the new owner still owes")
                .hasSize(total + second);

        // Deliberately NOT asserting that the resumed node goes quiet.
        //
        // An earlier version did, and failed: node-a delivered 451 more messages after resuming. That
        // is not fencing failing, it is rebalancing working. The resumed node's stale leases are
        // refused, its owners stop — and then, on the same heartbeat, it re-registers as a live
        // instance, computes its fair share and legitimately re-acquires shards. A node recovering
        // from a long pause rejoins and does useful work again, which is the behaviour anyone would
        // want; the test had simply assumed the weaker outcome.
        Awaitility.await().atMost(Duration.ofSeconds(60))
                  .untilAsserted(() -> assertThat(liveLeaseOwners())
                          .as("a recovered node should rejoin rather than stay excluded")
                          .hasSize(2));

        var third = 100;
        for (var index = total + second; index < total + second + third; index++) {
            queue.enqueue(List.of(encode(index)), 1);
        }
        Awaitility.await().atMost(Duration.ofSeconds(90))
                  .untilAsserted(() -> assertThat(distinctObservedValues())
                          .as("the rejoined pair must handle new work without losing any")
                          .hasSize(total + second + third));

        Awaitility.await().atMost(Duration.ofSeconds(60))
                  .untilAsserted(() -> assertThat(queue.remaining()).isZero());
    }

    // ---------- process control ----------

    /**
     * Send a POSIX signal to a child. {@code SIGSTOP} and {@code SIGCONT} have no equivalent in the
     * Java process API, and they are the only way to produce a process that is alive but not running.
     */
    private static void signal(Process process, String signal) throws Exception {
        var kill = new ProcessBuilder("kill", "-" + signal, Long.toString(process.pid()))
                .redirectErrorStream(true).start();
        assertThat(kill.waitFor(10, TimeUnit.SECONDS)).isTrue();
        assertThat(kill.exitValue()).as("kill -%s failed", signal).isZero();
    }


    private void startNode(String instanceId, int maxShards, String lane) throws IOException {
        var java = ProcessHandle.current().info().command().orElse("java");
        var command = List.of(java,
                              "-cp", System.getProperty("java.class.path"),
                              ShardOwnedNodeMain.class.getName(),
                              postgres.getJdbcUrl(),
                              postgres.getUsername(),
                              postgres.getPassword(),
                              instanceId,
                              Integer.toString(SHARD_COUNT),
                              Integer.toString(maxShards),
                              lane);
        var process = new ProcessBuilder(command).redirectErrorStream(true).start();
        nodes.add(process);

        // Wait for the node to announce itself, so the next one starts against a known state rather
        // than racing it for leases.
        var reader = new BufferedReader(new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8));
        var deadline = System.nanoTime() + Duration.ofSeconds(60).toNanos();
        while (System.nanoTime() < deadline) {
            var line = reader.readLine();
            if (line == null) {
                throw new IllegalStateException("Node " + instanceId + " exited before becoming ready");
            }
            if (line.startsWith("READY")) {
                log.info("Node {} ready (pid {})", instanceId, process.pid());
                // Keep draining output so the process does not block on a full pipe.
                var drain = new Thread(() -> {
                    try {
                        while (reader.readLine() != null) {
                            // discarded
                        }
                    } catch (IOException ignored) {
                        // the process died; nothing to do
                    }
                }, "drain-" + instanceId);
                drain.setDaemon(true);
                drain.start();
                return;
            }
        }
        throw new IllegalStateException("Node " + instanceId + " did not become ready");
    }

    // ---------- observations ----------

    private static byte[] encode(long value) {
        var payload = new byte[64];
        ByteBuffer.wrap(payload).putLong(value);
        return payload;
    }

    private long observedCount() throws SQLException {
        try (var connection = dataSource.getConnection();
             var statement = connection.createStatement();
             var resultSet = statement.executeQuery("SELECT count(*) FROM shard_queue_observed")) {
            resultSet.next();
            return resultSet.getLong(1);
        }
    }

    private Set<Long> distinctObservedValues() throws SQLException {
        try (var connection = dataSource.getConnection();
             var statement = connection.createStatement();
             var resultSet = statement.executeQuery("SELECT DISTINCT value FROM shard_queue_observed")) {
            var values = new HashSet<Long>();
            while (resultSet.next()) {
                values.add(resultSet.getLong(1));
            }
            return values;
        }
    }

    /**
     * Distinct owners holding an unexpired unordered-lane lease. Read from the database rather than
     * from either process, because in a multi-process test no participant has the whole picture.
     */
    private Set<String> liveLeaseOwners() throws SQLException {
        try (var connection = dataSource.getConnection();
             var statement = connection.prepareStatement(
                     "SELECT DISTINCT owner FROM " + ShardOwnedSchema.LEASE_TABLE
                     + " WHERE queue_id = ? AND lane = 'unordered' AND owner IS NOT NULL AND lease_until > now()")) {
            statement.setShort(1, QUEUE_ID);
            try (var resultSet = statement.executeQuery()) {
                var owners = new HashSet<String>();
                while (resultSet.next()) {
                    owners.add(resultSet.getString(1));
                }
                return owners;
            }
        }
    }

    private Map<String, Long> observationsPerInstance() throws SQLException {
        try (var connection = dataSource.getConnection();
             var statement = connection.createStatement();
             var resultSet = statement.executeQuery(
                     "SELECT instance_id, count(*) FROM shard_queue_observed GROUP BY instance_id")) {
            var counts = new LinkedHashMap<String, Long>();
            while (resultSet.next()) {
                counts.put(resultSet.getString(1), resultSet.getLong(2));
            }
            return counts;
        }
    }

    /**
     * Deliveries per process that landed after the given process's first delivery, using the
     * observation clock. Distinguishes a hand-over from an eviction: after an eviction the previous
     * owners contribute nothing here.
     */
    private Map<String, Long> deliveriesAfterFirstDeliveryBy(String instanceId) throws SQLException {
        var sql = """
                  SELECT instance_id, count(*) FROM shard_queue_observed
                  WHERE id > (SELECT min(id) FROM shard_queue_observed WHERE instance_id = ?)
                  GROUP BY instance_id
                  """;
        try (var connection = dataSource.getConnection();
             var statement = connection.prepareStatement(sql)) {
            statement.setString(1, instanceId);
            try (var resultSet = statement.executeQuery()) {
                var counts = new LinkedHashMap<String, Long>();
                while (resultSet.next()) {
                    counts.put(resultSet.getString(1), resultSet.getLong(2));
                }
                return counts;
            }
        }
    }

    /**
     * Keys whose {@code key_order} went backwards in observation order — computed in SQL, so the
     * comparison uses PostgreSQL's own insert ordering rather than anything the test reconstructs.
     */
    private List<String> orderingViolations() throws SQLException {
        var sql = """
                  SELECT msg_key FROM (
                      SELECT msg_key, value,
                             lag(value) OVER (PARTITION BY msg_key ORDER BY id) AS previous
                      FROM shard_queue_observed WHERE msg_key IS NOT NULL
                  ) ordered
                  WHERE previous IS NOT NULL AND value < previous
                  GROUP BY msg_key
                  """;
        try (var connection = dataSource.getConnection();
             var statement = connection.createStatement();
             var resultSet = statement.executeQuery(sql)) {
            var keys = new ArrayList<String>();
            while (resultSet.next()) {
                keys.add(resultSet.getString(1));
            }
            return keys;
        }
    }
}
