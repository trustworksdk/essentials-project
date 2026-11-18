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
import dk.trustworks.essentials.components.queue.shardowned.ShardOwnedStorage.OrderedPayload;
import org.awaitility.Awaitility;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.testcontainers.junit.jupiter.*;
import org.testcontainers.postgresql.PostgreSQLContainer;

import java.nio.ByteBuffer;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * What the ordered lane costs per delivered message when SEVERAL queues are busy at once on a few pumps - the shape
 * of an application whose every event processor has an inbox on this engine.
 * <p>
 * Every other cost suite here measures one queue, or idle queues. The trading demo found the gap: under a market-open
 * rush the order router's inbox backed up while its handlers sat idle, and thread dumps put both pumps in database
 * round trips - most often the watermark's horizon probe ({@link ShardOwnedStorage#runningWriteTransactionIds}), which
 * every busy owner issues for itself on every pass. This suite reproduces that shape - {@code cost.queues} ordered
 * queues fed at once by single-message producer transactions, as an inbox is fed, on {@code cost.pumpThreads} pumps -
 * and reports, per delivered message, the horizon probes, read statements and acknowledgement flushes the pumps
 * issued, with the throughput and enqueue-to-handler latency they bought.
 * <p>
 * Benchmark-gated ({@code -Dbenchmark.run=true}); it records numbers for an A/B, and asserts only that every message
 * arrived in key order.
 */
@Testcontainers(disabledWithoutDocker = true)
@EnabledIfSystemProperty(named = "benchmark.run", matches = "true")
class ShardOwnedOrderedLoadedCostIT {
    private static final int      QUEUES          = Integer.getInteger("cost.queues", 8);
    private static final int      PUMP_THREADS    = Integer.getInteger("cost.pumpThreads", 2);
    /** Messages per second across all queues while the load runs. */
    private static final int      RATE            = Integer.getInteger("cost.rate", 2_000);
    private static final Duration LOAD            = Duration.ofSeconds(Integer.getInteger("cost.seconds", 20));
    private static final int      KEYS_PER_QUEUE  = 500;
    /** Producer threads per queue; one thread sending one-message transactions tops out near 330/s. */
    private static final int      PRODUCERS       = Integer.getInteger("cost.producersPerQueue", 4);
    /** A handler waiting a little, as an inbox handler calling out does. */
    private static final long     HANDLER_MICROS  = Long.getLong("cost.handlerMicros", 2_000);
    private static final int      SHARD_COUNT     = 4;
    private static final Duration IDLE            = Duration.ofSeconds(10);

    @Container
    static PostgreSQLContainer postgres = LabPostgres.create("max_connections=300");

    private HikariDataSource dataSource;

    @BeforeEach
    void setUp() throws Exception {
        var config = new HikariConfig();
        config.setJdbcUrl(postgres.getJdbcUrl());
        config.setUsername(postgres.getUsername());
        config.setPassword(postgres.getPassword());
        config.setMaximumPoolSize(PUMP_THREADS + 1 + QUEUES * PRODUCERS + 10);
        dataSource = new HikariDataSource(config);
        ShardOwnedSchema.recreate(dataSource);
        for (short queueId = 1; queueId <= QUEUES; queueId++) {
            ShardOwnedSchema.registerQueue(dataSource, queueId, SHARD_COUNT);
        }
    }

    @AfterEach
    void tearDown() {
        if (dataSource != null) {
            dataSource.close();
        }
    }

    @Test
    void records_what_busy_ordered_queues_cost_per_delivered_message() throws Exception {
        var expected   = new AtomicLong();
        var delivered  = new AtomicLong();
        var violations = new AtomicLong();
        var latencies  = new ConcurrentLinkedQueue<Long>();
        var lastOrder  = new ConcurrentHashMap<String, Long>();

        var queues  = new ArrayList<ShardOwnedQueue>();
        var runtime = new ShardRuntime(dataSource, settings());
        try {
            for (short queueId = 1; queueId <= QUEUES; queueId++) {
                var queue = ShardOwnedQueue.builder().setDataSource(dataSource).setQueueId(queueId).setShardCount(SHARD_COUNT)
                                           .setInstanceId("loaded-" + queueId).setRuntime(runtime).build();
                var prefix = queueId + ":";
                queue.startConsumingOrdered((messageId, key, payload, payloadType) -> {
                    var buffer   = ByteBuffer.wrap(payload);
                    var order    = buffer.getLong();
                    var enqueued = buffer.getLong();
                    var previous = lastOrder.put(prefix + key, order);
                    if (previous != null && previous >= order) {
                        violations.incrementAndGet();
                    }
                    handlerWork(HANDLER_MICROS);
                    latencies.add(System.nanoTime() - enqueued);
                    delivered.incrementAndGet();
                }, settings(), ShardOwnedSchema.ORDERED_UNITS);
                queues.add(queue);
            }
            Awaitility.await().atMost(Duration.ofSeconds(60))
                      .until(() -> queues.stream().mapToInt(ShardOwnedQueue::shardsHeld).sum() == QUEUES * ShardOwnedSchema.ORDERED_UNITS);
            TimeUnit.SECONDS.sleep(2);

            var before = sum(queues);
            var xactsBefore = transactions();
            var pumpCpuBefore = pumpCpuNanos();
            var started = System.nanoTime();

            // PRODUCERS per queue, each owning a disjoint set of keys and sending single-message transactions at its
            // share of the rate - the shape of an inbox being fed, one notification per commit.
            var producers = Executors.newFixedThreadPool(QUEUES * PRODUCERS);
            var perProducerRate = Math.max(1, RATE / (QUEUES * PRODUCERS));
            var deadline = started + LOAD.toNanos();
            for (var queue : queues) {
                for (var producer = 0; producer < PRODUCERS; producer++) {
                    var firstKey = producer;
                    producers.submit(() -> {
                        var nextOrder = new long[KEYS_PER_QUEUE];
                        var intervalNanos = 1_000_000_000L / perProducerRate;
                        var next = System.nanoTime();
                        var sent = 0L;
                        while (System.nanoTime() < deadline) {
                            var key = (int) ((firstKey + sent * PRODUCERS) % KEYS_PER_QUEUE);
                            var payload = ByteBuffer.allocate(16).putLong(nextOrder[key]++).putLong(System.nanoTime()).array();
                            queue.enqueueOrdered(List.of(new OrderedPayload("k" + key, nextOrder[key] - 1, payload, 1)));
                            expected.incrementAndGet();
                            sent++;
                            next += intervalNanos;
                            var sleep = next - System.nanoTime();
                            if (sleep > 0) {
                                TimeUnit.NANOSECONDS.sleep(sleep);
                            }
                        }
                        return null;
                    });
                }
            }
            producers.shutdown();
            assertThat(producers.awaitTermination(LOAD.toSeconds() + 60, TimeUnit.SECONDS)).isTrue();
            Awaitility.await().atMost(Duration.ofMinutes(5)).pollInterval(Duration.ofMillis(50))
                      .until(() -> delivered.get() >= expected.get());
            var elapsedNanos = System.nanoTime() - started;
            var pumpCpuLoaded = pumpCpuNanos() - pumpCpuBefore;
            // And with nothing to do: what the pumps cost while the queues they serve sit idle
            var idleBefore = pumpCpuNanos();
            TimeUnit.SECONDS.sleep(IDLE.toSeconds());
            var pumpCpuIdle = pumpCpuNanos() - idleBefore;

            var after = sum(queues);
            var xacts = transactions() - xactsBefore;
            var n = (double) (delivered.get());
            var sorted = latencies.stream().mapToLong(Long::longValue).sorted().toArray();
            System.out.printf(Locale.ROOT,
                              "loaded ordered lane: %d queues, %d pumps, %d msg/s offered for %ds, handler %dus, %d producers per queue%n"
                              + "  offered %d, delivered %d in %.1fs (%.0f msg/s), latency p50 %.1f ms p99 %.1f ms max %.1f ms%n"
                              + "  per delivered message: horizon probes %.3f, read statements %.3f, ack flushes %.3f, owner-passes %.3f, "
                              + "transactions %.3f%n"
                              + "  pump CPU: %.1f ms per 1 000 messages under load (%.0f%% of a core), %.0f%% of a core idle%n",
                              QUEUES, PUMP_THREADS, RATE, LOAD.toSeconds(), HANDLER_MICROS, PRODUCERS,
                              expected.get(), delivered.get(), elapsedNanos / 1e9, n / (elapsedNanos / 1e9),
                              percentile(sorted, 0.50) / 1e6, percentile(sorted, 0.99) / 1e6, sorted[sorted.length - 1] / 1e6,
                              (after[0] - before[0]) / n, (after[1] - before[1]) / n, (after[2] - before[2]) / n, (after[3] - before[3]) / n,
                              xacts / n,
                              pumpCpuLoaded / 1e6 / (n / 1000.0), pumpCpuLoaded * 100.0 / elapsedNanos,
                              pumpCpuIdle * 100.0 / IDLE.toNanos());

            assertThat(violations.get()).as("key order").isZero();
            assertThat(delivered.get()).isEqualTo(expected.get());
        } finally {
            queues.forEach(ShardOwnedQueue::close);
            runtime.close();
        }
    }

    /** horizon probes, read statements, ack flushes, owner-passes, summed over every queue's metrics. */
    private static long[] sum(List<ShardOwnedQueue> queues) {
        var totals = new long[4];
        for (var queue : queues) {
            var metrics = queue.metrics();
            totals[0] += metrics.horizonProbes.sum();
            totals[1] += metrics.orderedReadStatements.sum();
            totals[2] += metrics.ackFlushes.sum();
            totals[3] += metrics.cursorReads.sum();
        }
        return totals;
    }

    /** CPU time of the runtime's pump threads, which do every database round trip and every per-owner check. */
    private static long pumpCpuNanos() {
        var threads = java.lang.management.ManagementFactory.getThreadMXBean();
        var total = 0L;
        for (var info : threads.getThreadInfo(threads.getAllThreadIds())) {
            if (info != null && info.getThreadName().startsWith("shard-queue-pump")) {
                total += Math.max(0, threads.getThreadCpuTime(info.getThreadId()));
            }
        }
        return total;
    }

    private long transactions() throws Exception {
        try (var connection = dataSource.getConnection();
             var statement = connection.prepareStatement(
                     "SELECT xact_commit + xact_rollback FROM pg_stat_database WHERE datname = current_database()");
             var resultSet = statement.executeQuery()) {
            resultSet.next();
            return resultSet.getLong(1);
        }
    }

    private static long percentile(long[] sorted, double p) {
        return sorted[Math.min(sorted.length - 1, (int) (sorted.length * p))];
    }

    /**
     * Waits the way a handler calling out waits - parked, not spinning. A spinning handler made the saturated runs
     * CPU-bound on the lab's 8 cores (about 2 400 msg/s x 2 ms is ~5 cores spinning), which charged whichever arm kept
     * its pumps busier for CPU it would not use in an application whose handlers do I/O.
     */
    private static void handlerWork(long micros) {
        try {
            Thread.sleep(Duration.ofNanos(micros * 1_000));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private static ShardOwnerSettings settings() {
        var defaults = ShardOwnerSettings.defaults();
        return new ShardOwnerSettings(defaults.readBatchSize(), defaults.ackBatchSize(), defaults.ackFlushInterval(),
                                      defaults.chaseDelay(), defaults.holeExpiry(), defaults.sweepInterval(),
                                      defaults.maxHolesPerChase(),
                                      defaults.keyConcurrency(), defaults.pollBackstop(), defaults.maxSweepInterval(), PUMP_THREADS,
                                      defaults.shedGrace(), defaults.leaseTtl(), defaults.watermarkCap());
    }
}
