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
import org.slf4j.*;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.*;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * What several queues in one process actually cost — in threads, in held connections, in queries per
 * second while completely idle, and in hole churn.
 * <p>
 * <b>Why this test exists.</b> Every other measurement in this module was taken against a single
 * queue, and the phase gates were all about storage cost — WAL bytes per message, dead tuples per
 * message. The argument for choosing those was that they are properties of the design rather than of
 * the machine. That argument is right and was applied to exactly one kind of cost: threads, held
 * connections and idle query rate are equally properties of the design, equally machine-independent,
 * and were gated nowhere. This test closes that gap, and its first job is to record the numbers as
 * they are rather than to assert they are good.
 * <p>
 * The figures are printed and asserted only against limits a container can actually survive, so that
 * the rework has a before-picture to be measured against.
 */
@Testcontainers(disabledWithoutDocker = true)
class ShardOwnedMultiQueueCostIT {
    private static final Logger log = LoggerFactory.getLogger(ShardOwnedMultiQueueCostIT.class);

    /**
     * Five queues at four shards by default, so the gate stays in the normal build. The figures that
     * matter are per queue, so a bigger run is a scaling check rather than a different measurement:
     * {@code -Dcost.queues=25 -Dcost.shards=8}.
     */
    private static final int QUEUES       = Integer.getInteger("cost.queues", 5);
    private static final int SHARD_COUNT  = Integer.getInteger("cost.shards", 4);
    private static final int PUMP_THREADS = Integer.getInteger("cost.pumpThreads",
                                                               ShardOwnerSettings.defaults().pumpThreads());

    /**
     * {@code max_connections} raised well above PostgreSQL's default of 100.
     * <p>
     * Not a convenience: at twenty-five queues the engine holds roughly 150 connections, so the
     * default would refuse them. That is a real deployment constraint rather than a test artefact —
     * held connections are {@code pumpThreads x lanes x queues} plus a listener per lane per queue,
     * and a server has to be sized for it or {@code pumpThreads} lowered.
     */
    @Container
    static PostgreSQLContainer<?> postgres = LabPostgres.create("max_connections=500");

    private HikariDataSource dataSource;

    @BeforeEach
    void setUp() throws Exception {
        var config = new HikariConfig();
        config.setJdbcUrl(postgres.getJdbcUrl());
        config.setUsername(postgres.getUsername());
        config.setPassword(postgres.getPassword());
        // Deliberately generous: the point is to observe how many the engine takes, not to watch it
        // starve against a small pool.
        config.setMaximumPoolSize(Math.max(120, QUEUES * 10));
        dataSource = new HikariDataSource(config);
        ShardOwnedSchema.recreate(dataSource);
        for (var queueId = 1; queueId <= QUEUES; queueId++) {
            ShardOwnedSchema.registerQueue(dataSource, (short) queueId, SHARD_COUNT);
        }
    }

    @AfterEach
    void tearDown() {
        if (dataSource != null) {
            dataSource.close();
        }
    }

    /**
     * Connections checked out of the pool and not returned — which is what the engine actually holds.
     * <p>
     * An earlier version of this counted rows in {@code pg_stat_activity}, and reported 99 for five
     * queues. That number was mostly Hikari: a pool sized at 120 keeps what it opens, so the measure
     * counted the pool's growth rather than the engine's grip on it. Held connections are the thing
     * that starves a pool, so held connections are what this must report.
     */
    private int heldConnections() {
        return dataSource.getHikariPoolMXBean().getActiveConnections();
    }

    /**
     * Row updates against the lease table — the heartbeat's cost, and the one this engine is least
     * entitled to be casual about.
     * <p>
     * The design's signature property is that the steady-state path issues no claim write:
     * {@code n_tup_upd} on the message tables is zero. The lease table is not on that path, but the
     * heartbeat renews ONE ROW PER OWNED UNIT, so it is a write cadence that scales with units held
     * rather than with queues — and raising the ordered lane from a handful of shards to a fixed
     * space of sixty-four multiplied it without anything noticing.
     */
    private long leaseRowUpdates() throws Exception {
        try (var connection = dataSource.getConnection();
             var statement = connection.prepareStatement(
                     "SELECT n_tup_upd FROM pg_stat_user_tables WHERE relname = ?")) {
            statement.setString(1, ShardOwnedSchema.LEASE_TABLE);
            try (var resultSet = statement.executeQuery()) {
                return resultSet.next() ? resultSet.getLong(1) : 0L;
            }
        }
    }

    /** Transactions committed against this database — a fair proxy for queries, since every read runs autocommit. */
    private long transactions() throws Exception {
        try (var connection = dataSource.getConnection();
             var statement = connection.prepareStatement(
                     "SELECT xact_commit + xact_rollback FROM pg_stat_database WHERE datname = current_database()");
             var resultSet = statement.executeQuery()) {
            resultSet.next();
            return resultSet.getLong(1);
        }
    }

    private static ShardOwnerSettings settings() {
        var defaults = ShardOwnerSettings.defaults();
        return new ShardOwnerSettings(defaults.readBatchSize(), defaults.ackBatchSize(), defaults.ackFlushInterval(),
                                      defaults.chaseDelay(), defaults.holeExpiry(), defaults.sweepInterval(),
                                      defaults.maxHolesPerChase(),
                                      defaults.keyConcurrency(), defaults.pollBackstop(), Duration.ofSeconds(30), PUMP_THREADS,
                                      defaults.shedGrace(), Duration.ofMillis(30000), Duration.ofSeconds(60));
    }

    private static int liveThreads() {
        return java.lang.management.ManagementFactory.getThreadMXBean().getThreadCount();
    }

    @Test
    void records_what_several_idle_queues_cost_in_threads_connections_and_queries() throws Exception {
        // Settle first: taken immediately, the baseline includes threads a previous test class is
        // still winding down, and the delta comes out negative.
        TimeUnit.SECONDS.sleep(2);
        var threadsBefore = liveThreads();
        var connectionsBefore = heldConnections();

        var queues = new ArrayList<ShardOwnedQueue>();
        // One runtime for every queue in the process. Pumps, the wake-up listener and the heartbeat
        // are all queue-agnostic — queue_id is a bind parameter, not a property of a connection — so
        // scoping them per queue was what made connections scale with queue count.
        var runtime = new ShardRuntime(dataSource, settings());
        try {
            for (var queueId = 1; queueId <= QUEUES; queueId++) {
                var unordered = ShardOwnedQueue.builder().setDataSource(dataSource).setQueueId((short) queueId).setShardCount(SHARD_COUNT).setInstanceId("cost-u-" + queueId).setRuntime(runtime).build();
                unordered.startConsuming((messageId, payload, payloadType) -> {
                }, settings(), SHARD_COUNT);
                var ordered = ShardOwnedQueue.builder().setDataSource(dataSource).setQueueId((short) queueId).setShardCount(SHARD_COUNT).setInstanceId("cost-o-" + queueId).setRuntime(runtime).build();
                ordered.startConsumingOrdered((messageId, key, payload, payloadType) -> {
                }, settings(), SHARD_COUNT);
                queues.add(unordered);
                queues.add(ordered);
            }
            // Let every owner reach steady state before anything is counted.
            // Long enough for the sweep to back off on shards that have seen nothing. A fixed sweep
            // is a fixed query rate per shard regardless of traffic, which is what made 300 queues
            // cost 18 000 queries a second while completely idle.
            TimeUnit.SECONDS.sleep(35);

            var threadsAfter = liveThreads();
            var connectionsAfter = heldConnections();

            // Idle query rate, over a window with no traffic at all.
            var transactionsStart = transactions();
            var leaseUpdatesStart = leaseRowUpdates();
            var unitsHeld         = queues.stream().mapToInt(ShardOwnedQueue::shardsHeld).sum();
            // Longer than one HEARTBEAT (leaseTtl / 3 = 10s), not just longer than one sweep. A 5s
            // window reported zero lease updates and zero queries, which is a sampling artefact
            // rather than a result: the renewal simply had not fired inside it.
            var windowSeconds = 30L;
            TimeUnit.SECONDS.sleep(windowSeconds);
            var idleQueriesPerSecond  = (transactions() - transactionsStart) / (double) windowSeconds;
            var leaseUpdatesPerSecond = (leaseRowUpdates() - leaseUpdatesStart) / (double) windowSeconds;

            // Handler pools are created lazily, so an idle engine understates its thread count badly.
            // Drive every shard so the ordered lane's per-owner pools actually populate.
            for (var index = 0; index < Math.min(queues.size(), 10); index += 2) {
                var ordered = queues.get(index + 1);
                var batch = new ArrayList<ShardOwnedStorage.OrderedPayload>();
                for (var key = 0; key < 64; key++) {
                    batch.add(new ShardOwnedStorage.OrderedPayload("k-" + key, 0L,
                                                                ("x-" + key).getBytes(StandardCharsets.UTF_8), 1));
                }
                ordered.enqueueOrdered(batch);
            }
            TimeUnit.SECONDS.sleep(4);
            var threadsUnderLoad = liveThreads();
            var connectionsUnderLoad = heldConnections();
            log.warn("under load:         {} threads (+{}), {} held connections",
                     threadsUnderLoad, threadsUnderLoad - threadsBefore, connectionsUnderLoad);

            log.warn("=== {} queues x 2 lanes x {} shards, completely idle ===", QUEUES, SHARD_COUNT);
            log.warn("threads:            {} (+{})", threadsAfter, threadsAfter - threadsBefore);
            log.warn("backend connections:{} (+{})", connectionsAfter, connectionsAfter - connectionsBefore);
            log.warn("queries/second:     {}", idleQueriesPerSecond);
            log.warn("units held:         {} ({} unordered shards + {} ordered units per queue)",
                     unitsHeld, SHARD_COUNT, ShardOwnedSchema.ORDERED_UNITS);
            log.warn("lease UPDATEs/s:    {}  ({} per owned unit per second)",
                     leaseUpdatesPerSecond,
                     unitsHeld == 0 ? 0.0 : leaseUpdatesPerSecond / (double) unitsHeld);
            log.warn("idle queries per owned unit: {}",
                     unitsHeld == 0 ? 0.0 : idleQueriesPerSecond / (double) unitsHeld);
            log.warn("per queue:          {} threads, {} connections",
                     (threadsAfter - threadsBefore) / (double) QUEUES,
                     (connectionsAfter - connectionsBefore) / (double) QUEUES);
            // The figure that decides whether this scales: held connections must be a property of
            // the process, not of how many queues it runs.
            assertThat(connectionsAfter - connectionsBefore)
                    .as("held connections must not scale with the number of queues")
                    .isLessThanOrEqualTo(PUMP_THREADS + 2);

            // Recorded, not yet gated hard: these are the before-numbers the rework is measured
            // against. The assertions are the loosest statement that would still catch a regression
            // into absurdity.
            // Bounded above, not merely positive. "More threads than before" is true of almost any
            // code and false whenever the baseline drifts; what matters is that the count is a
            // property of the process rather than of how many queues it runs.
            assertThat(threadsUnderLoad - threadsBefore)
                    .as("threads must not scale with the number of queues")
                    .isLessThanOrEqualTo(PUMP_THREADS + 24);
            assertThat(connectionsAfter - connectionsBefore).isPositive();
            // Per owned shard, not in total. An absolute threshold is the wrong shape for a quantity
            // that scales with fan-out: the same engine measured 160 queries/s at five queues and
            // 1 614 at twenty-five, and both are 4.0 per owned shard — which is exactly the designed
            // floor of a 500ms sweep plus a 500ms backstop poll. Gating the total would have failed
            // purely for being asked to run bigger.
            //
            // Was ~2 600 per shard before the ordered lane was wired to Tier 1, where it had been
            // polling as fast as the database could answer.
            var ownedShards = QUEUES * 2L * SHARD_COUNT;
            var perShard = idleQueriesPerSecond / (double) ownedShards;
            log.warn("idle queries per owned shard: {}", perShard);
            assertThat(perShard)
                    .as("an idle shard must cost almost nothing once its sweep has backed off")
                    .isLessThan(0.5d);
        } finally {
            queues.forEach(ShardOwnedQueue::stop);
            runtime.close();
        }
    }

    /**
     * Sequences were once named per shard rather than per (queue, shard), so every queue on a shard
     * drew from one counter and the values other queues took looked like gaps — and the owner treats
     * a gap as a hole: chased every {@code chaseDelay}, and holding the acknowledgement floor down
     * until it expires. Measured at 3.8 holes per message with five queues, i.e. {@code queues - 1}.
     * <p>
     * A hole must mean "a transaction that has not committed yet", which is only true if the sequence
     * is dense within the queue that reads it.
     */
    @Test
    void several_queues_do_not_manufacture_holes_for_each_other() throws Exception {
        var perQueue = 40;
        var delivered = new java.util.concurrent.ConcurrentHashMap<Integer, java.util.Set<String>>();
        var queues = new ArrayList<ShardOwnedQueue>();
        // Shared, like the other test. Constructing a queue without a runtime gives it one of its own,
        // which is fine for a single queue and is exactly what must not be done for a hundred: at 100
        // queues it stood up 100 runtimes and exhausted a 500-connection pool.
        var runtime = new ShardRuntime(dataSource, settings());
        try {
            for (var queueId = 1; queueId <= QUEUES; queueId++) {
                var id = queueId;
                delivered.put(id, java.util.concurrent.ConcurrentHashMap.newKeySet());
                var queue = ShardOwnedQueue.builder().setDataSource(dataSource).setQueueId((short) queueId).setShardCount(SHARD_COUNT).setInstanceId("seq-" + queueId).setRuntime(runtime).build();
                // Tier 2 hands a message straight to the owner and stamps the row so the cursor read
                // skips it — so with hand-off on, the cursor never walks the sequence and the question
                // this test asks is never asked. The first version of this test measured zero holes
                // for exactly that reason.
                queue.setLocalHandoffEnabled(false);
                queue.startConsuming((messageId, payload, payloadType) -> delivered.get(id).add(new String(payload, StandardCharsets.UTF_8)),
                                     settings(), SHARD_COUNT);
                queues.add(queue);
            }

            // Interleaved across queues, which is what makes each queue's seq values sparse.
            for (var round = 0; round < perQueue; round++) {
                for (var queueId = 1; queueId <= QUEUES; queueId++) {
                    queues.get(queueId - 1).enqueue(
                            List.of(("q" + queueId + "-" + round).getBytes(StandardCharsets.UTF_8)), 1);
                }
            }

            org.awaitility.Awaitility.await().atMost(Duration.ofSeconds(60)).untilAsserted(() -> {
                for (var queueId = 1; queueId <= QUEUES; queueId++) {
                    assertThat(delivered.get(queueId)).as("queue %s", queueId).hasSize(perQueue);
                }
            });

            var observed = 0L;
            var abandoned = 0L;
            var chases = 0L;
            for (var queue : queues) {
                observed += queue.metrics().holesObserved.sum();
                abandoned += queue.metrics().holesAbandoned.sum();
                chases += queue.metrics().holeChaseQueries.sum();
            }
            var messages = (long) QUEUES * perQueue;
            log.warn("=== {} queues, per-(queue,shard) sequences, {} messages total ===", QUEUES, messages);
            log.warn("holes observed:  {} ({} per message)", observed, observed / (double) messages);
            log.warn("holes abandoned: {}", abandoned);
            log.warn("chase queries:   {} ({} per message)", chases, chases / (double) messages);

            // Holes from genuinely concurrent transactions still happen and are meant to; what must
            // not happen is one per message per other queue.
            assertThat(observed / (double) messages)
                    .as("holes per message must not scale with the number of queues (was 3.8 at five "
                        + "queues when the sequence was shared)")
                    .isLessThan(0.5d);
        } finally {
            queues.forEach(ShardOwnedQueue::stop);
            runtime.close();
        }
    }
}
