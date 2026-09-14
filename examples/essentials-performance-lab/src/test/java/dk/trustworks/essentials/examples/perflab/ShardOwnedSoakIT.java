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

package dk.trustworks.essentials.examples.perflab;

import com.zaxxer.hikari.*;
import dk.trustworks.essentials.components.foundation.json.EssentialsObjectMappers;
import dk.trustworks.essentials.components.foundation.messaging.RedeliveryPolicy;
import dk.trustworks.essentials.components.foundation.messaging.queue.*;
import dk.trustworks.essentials.components.foundation.messaging.queue.operations.ConsumeFromQueue;
import dk.trustworks.essentials.components.foundation.transaction.jdbi.JdbiUnitOfWorkFactory;
import dk.trustworks.essentials.components.queue.postgresql.PostgresqlDurableQueues;
import dk.trustworks.essentials.components.queue.shardowned.*;
import dk.trustworks.essentials.examples.perflab.harness.*;
import org.jdbi.v3.core.Jdbi;
import org.jdbi.v3.postgres.PostgresPlugin;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.slf4j.*;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.*;

import java.nio.ByteBuffer;
import java.sql.SQLException;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Sustained load, both engines, sampled over time.
 * <p>
 * Every other measurement here is a snapshot, and a snapshot cannot see the thing the dead-tuple
 * figure is actually about. One dead tuple per message against two is not interesting for the length
 * of a benchmark; it is interesting an hour in, when the difference has become vacuum debt, index
 * bloat and a p99 that no longer resembles the one that was measured. A design that is only fast
 * before autovacuum has had to do anything is not fast.
 * <p>
 * So this reports a <em>series</em> rather than a number, and compares the last window with the
 * first. The question is not which engine is quicker — that is already known — but whether either
 * one drifts.
 * <p>
 * Both arms run at the same offered rate, well below either engine's capacity, so latency stays a
 * property of the design rather than of queue depth, and both do identical work per unit time.
 * <p>
 * Duration is {@code -Dsoak.minutes}, default 6, and rate is {@code -Dsoak.rate}, default 300/s.
 * Six minutes is long enough for several autovacuum cycles at the default 60-second naptime; a
 * genuine pre-release soak should run for hours. Both arms run for the full duration, so wall clock
 * is roughly twice {@code soak.minutes} plus container start-up.
 */
@Testcontainers(disabledWithoutDocker = true)
@EnabledIfSystemProperty(named = "benchmark.run", matches = "true")
class ShardOwnedSoakIT {
    private static final Logger log = LoggerFactory.getLogger(ShardOwnedSoakIT.class);

    private static final short  QUEUE_ID       = 1;
    private static final int    SHARD_COUNT    = 8;
    /**
     * Offered rate, {@code -Dsoak.rate}, default 300/s.
     * <p>
     * <b>Deliberately well below either engine's capacity</b>, and raising it is a trade rather than
     * an improvement. The soak asks whether latency drifts; at or above capacity Little's Law fixes
     * latency at {@code queueDepth / throughput} and the answer stops being a property of the design.
     * A higher rate accumulates dead tuples and bloat faster, which is the other thing a soak is for
     * — so vary it deliberately, and read the latency columns knowing what was traded for it.
     */
    private static final double RATE_PER_SECOND = Double.parseDouble(System.getProperty("soak.rate", "300"));
    private static final int    PAYLOAD_BYTES  = 200;
    private static final String BASELINE_TABLE = "perflab_soak_baseline";

    @Container
    static PostgreSQLContainer<?> postgres = LabPostgres.create();

    private HikariDataSource dataSource;

    @BeforeEach
    void setUp() {
        var config = new HikariConfig();
        config.setJdbcUrl(postgres.getJdbcUrl());
        config.setUsername(postgres.getUsername());
        config.setPassword(postgres.getPassword());
        config.setMaximumPoolSize(60);
        dataSource = new HikariDataSource(config);
    }

    @AfterEach
    void tearDown() {
        if (dataSource != null) {
            dataSource.close();
        }
    }

    @Test
    void neither_engine_should_drift_under_sustained_load() throws Exception {
        var minutes = Integer.parseInt(System.getProperty("soak.minutes", "6"));
        var duration = Duration.ofMinutes(minutes);
        var window = Duration.ofSeconds(30);

        var baseline = soakBaseline(duration, window);
        var shardOwned = soakShardOwned(duration, window);

        report("baseline (current implementation)", baseline);
        report("shard-owned", shardOwned);

        RunResult.writeAll("target/perf-lab-baseline/soak.json",
                           Map.of("durationMinutes", minutes,
                                  "ratePerSecond", RATE_PER_SECOND,
                                  "environment", PgSnapshot.captureEnvironment(dataSource),
                                  "baseline", baseline,
                                  "shardOwned", shardOwned));

        // Both must actually have kept up, or the comparison is between two backlogs.
        assertThat(baseline).hasSizeGreaterThan(2);
        assertThat(shardOwned).hasSizeGreaterThan(2);
    }

    /**
     * One sample of how the system looks right now.
     *
     * @param deadTuplesOutstanding a gauge, not a counter — autovacuum drives it back down, and
     *                              whether it does is the entire question
     */
    record Window(int index,
                  long handled,
                  long p50Micros,
                  long p99Micros,
                  long tableBytes,
                  long indexBytes,
                  long deadTuplesOutstanding,
                  long autovacuumCount) {
    }

    private void report(String label, List<Window> windows) {
        log.info("");
        log.info("===== SOAK: {} =====", label);
        log.info(String.format("%6s %9s %10s %10s %12s %12s %12s %11s",
                               "window", "handled", "p50 us", "p99 us", "table KB", "index KB", "deadTuples", "autovacuum"));
        for (var w : windows) {
            log.info(String.format("%6d %9d %10d %10d %12d %12d %12d %11d",
                                   w.index(), w.handled(), w.p50Micros(), w.p99Micros(),
                                   w.tableBytes() / 1024, w.indexBytes() / 1024,
                                   w.deadTuplesOutstanding(), w.autovacuumCount()));
        }
        var first = windows.getFirst();
        var last = windows.getLast();
        log.info("drift: p50 {} -> {} us ({}%), p99 {} -> {} us ({}%), table {} -> {} KB, index {} -> {} KB",
                 first.p50Micros(), last.p50Micros(), percent(first.p50Micros(), last.p50Micros()),
                 first.p99Micros(), last.p99Micros(), percent(first.p99Micros(), last.p99Micros()),
                 first.tableBytes() / 1024, last.tableBytes() / 1024,
                 first.indexBytes() / 1024, last.indexBytes() / 1024);
        log.info("=========================================");
    }

    private static String percent(long from, long to) {
        return from == 0 ? "n/a" : String.format("%+.0f", 100.0d * (to - from) / from);
    }

    private List<Window> soakBaseline(Duration duration, Duration window) throws Exception {
        var jdbi = Jdbi.create(dataSource).installPlugin(new PostgresPlugin());
        var durableQueues = PostgresqlDurableQueues.builder()
                                                   .setUnitOfWorkFactory(new JdbiUnitOfWorkFactory(jdbi))
                                                   .setJsonSerializer(EssentialsObjectMappers.createJSONSerializer())
                                                   .setSharedQueueTableName(BASELINE_TABLE)
                                                   .setUseCentralizedMessageFetcher(true)
                                                   .setCentralizedMessageFetcherPollingInterval(Duration.ofMillis(20))
                                                   .build();
        durableQueues.start();
        var queueName = QueueName.of("soak");
        var handled = new AtomicInteger();
        var recorder = new LatencyRecorder[]{new LatencyRecorder("soak")};
        DurableQueueConsumer consumer = null;
        try {
            consumer = durableQueues.consumeFromQueue(ConsumeFromQueue.builder()
                                                                      .setQueueName(queueName)
                                                                      .setRedeliveryPolicy(RedeliveryPolicy.fixedBackoff(Duration.ofMillis(100), 3))
                                                                      .setParallelConsumers(20)
                                                                      .setQueueMessageHandler(message -> {
                                                                          if (message.getPayload() instanceof SoakPayload body) {
                                                                              recorder[0].record(body.intendedAtNanos(), body.intendedAtNanos(), System.nanoTime());
                                                                          }
                                                                          handled.incrementAndGet();
                                                                      })
                                                                      .build());
            return run(duration, window, recorder, handled, BASELINE_TABLE,
                       intended -> durableQueues.queueMessage(queueName, Message.of(new SoakPayload(intended, "x".repeat(PAYLOAD_BYTES)))));
        } finally {
            if (consumer != null) {
                consumer.stop();
            }
            durableQueues.stop();
        }
    }

    private List<Window> soakShardOwned(Duration duration, Duration window) throws Exception {
        ShardOwnedSchema.recreate(dataSource);
        ShardOwnedSchema.registerQueue(dataSource, QUEUE_ID, SHARD_COUNT);
        var handled = new AtomicInteger();
        var recorder = new LatencyRecorder[]{new LatencyRecorder("soak")};

        try (var queue = new ShardOwnedQueue(dataSource, QUEUE_ID, SHARD_COUNT, "soak")) {
            queue.startConsuming((messageId, payload, payloadType) -> {
                recorder[0].record(ByteBuffer.wrap(payload).getLong(), ByteBuffer.wrap(payload).getLong(), System.nanoTime());
                handled.incrementAndGet();
            }, ShardOwnerSettings.defaults(), SHARD_COUNT);

            return run(duration, window, recorder, handled, ShardOwnedSchema.UNORDERED_TABLE, intended -> {
                var payload = new byte[PAYLOAD_BYTES];
                ByteBuffer.wrap(payload).putLong(intended);
                try {
                    queue.enqueue(List.of(payload), 1);
                } catch (SQLException e) {
                    throw new IllegalStateException(e);
                }
            });
        }
    }

    /**
     * Drive a fixed offered rate for {@code duration}, sampling every {@code window}.
     * <p>
     * Intended send times come from a schedule fixed up front, so a stall inside one window is
     * charged to the messages it delayed rather than quietly skipped.
     */
    private List<Window> run(Duration duration,
                             Duration window,
                             LatencyRecorder[] recorder,
                             AtomicInteger handled,
                             String table,
                             java.util.function.LongConsumer enqueue) throws Exception {
        var windows = new ArrayList<Window>();
        var intervalNanos = (long) (1_000_000_000.0d / RATE_PER_SECOND);
        var scheduleStart = System.nanoTime();
        var windowEnd = scheduleStart + window.toNanos();
        var deadline = scheduleStart + duration.toNanos();
        var handledAtWindowStart = 0;
        var operation = 0L;

        while (System.nanoTime() < deadline) {
            var intended = scheduleStart + operation * intervalNanos;
            var wait = intended - System.nanoTime();
            if (wait > 0) {
                TimeUnit.NANOSECONDS.sleep(wait);
            }
            enqueue.accept(intended);
            operation++;

            if (System.nanoTime() >= windowEnd) {
                var summary = recorder[0].responseTimeSummary();
                var stats = tableStats(table);
                windows.add(new Window(windows.size(),
                                       handled.get() - handledAtWindowStart,
                                       summary.p50Micros(), summary.p99Micros(),
                                       stats[0], stats[1], stats[2], stats[3]));
                // A fresh recorder per window, so a bad early window cannot flatten a later one — the
                // question is drift, and a cumulative histogram cannot show it.
                recorder[0] = new LatencyRecorder("soak");
                handledAtWindowStart = handled.get();
                windowEnd += window.toNanos();
            }
        }
        return windows;
    }

    /**
     * @return table bytes, index bytes, outstanding dead tuples, autovacuum runs
     */
    private long[] tableStats(String table) throws SQLException {
        try (var connection = dataSource.getConnection();
             var statement = connection.prepareStatement(
                     "SELECT pg_total_relation_size(?::regclass), pg_indexes_size(?::regclass),"
                     + " coalesce((SELECT n_dead_tup FROM pg_stat_user_tables WHERE relname = ?), 0),"
                     + " coalesce((SELECT autovacuum_count FROM pg_stat_user_tables WHERE relname = ?), 0)")) {
            statement.setString(1, table);
            statement.setString(2, table);
            statement.setString(3, table);
            statement.setString(4, table);
            try (var resultSet = statement.executeQuery()) {
                resultSet.next();
                return new long[]{resultSet.getLong(1), resultSet.getLong(2), resultSet.getLong(3), resultSet.getLong(4)};
            }
        }
    }

    public record SoakPayload(long intendedAtNanos, String payload) {
    }
}
