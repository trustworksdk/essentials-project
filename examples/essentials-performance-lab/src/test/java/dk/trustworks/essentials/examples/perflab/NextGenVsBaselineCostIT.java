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
import dk.trustworks.essentials.examples.perflab.harness.*;
import dk.trustworks.essentials.components.queue.shardowned.*;
import org.awaitility.Awaitility;
import org.jdbi.v3.core.Jdbi;
import org.jdbi.v3.postgres.PostgresPlugin;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.slf4j.*;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.*;

import java.nio.charset.StandardCharsets;
import java.time.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The first cost comparison between the shard-owned engine and the existing implementation.
 * <p>
 * Gated on the metrics Phase 0 established this lab can actually resolve — <b>WAL bytes per message
 * and dead tuples per message</b> — and deliberately not on throughput, which varied 861% across
 * operating points here and is a property of the machine rather than of the design.
 * <p>
 * A fixed message count rather than a fixed duration, so both arms divide by the same denominator
 * and a slower arm is not also charged a different amount of work.
 * <p>
 * <b>What this comparison is not.</b> The existing implementation serializes every payload through a
 * flavour-neutral JSON serializer, routes every operation through an interceptor chain, and opens a
 * {@code UnitOfWork} per operation. The shard-owned engine does none of those yet — it moves opaque
 * bytes. So this measures the <em>storage layer</em>, and any advantage it shows is an upper bound
 * that will shrink once an adapter puts the same obligations on both sides. Reporting it as an
 * end-to-end result would be exactly the overstatement that makes a prototype's numbers fail to
 * survive implementation.
 */
@Testcontainers(disabledWithoutDocker = true)
@EnabledIfSystemProperty(named = "benchmark.run", matches = "true")
class NextGenVsBaselineCostIT {
    private static final Logger log = LoggerFactory.getLogger(NextGenVsBaselineCostIT.class);

    private static final short  QUEUE_ID       = 1;
    private static final int    SHARD_COUNT    = 8;
    private static final int    MESSAGE_COUNT  = 20_000;
    private static final int    PAYLOAD_BYTES  = 200;
    private static final int    REPETITIONS    = 3;
    private static final String BASELINE_TABLE = "perflab_baseline_queues";

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
    void compare_per_message_cost_of_both_engines() throws Exception {
        var environment = PgSnapshot.captureEnvironment(dataSource);
        var arms = new LinkedHashMap<String, java.util.function.IntFunction<RunResult>>();
        arms.put("baseline", repetition -> {
            try {
                return measureBaseline(repetition, environment);
            } catch (Exception e) {
                throw new IllegalStateException("baseline arm failed", e);
            }
        });
        arms.put("shard-owned", repetition -> {
            try {
                return measureNextGen(repetition, environment);
            } catch (Exception e) {
                throw new IllegalStateException("shard-owned arm failed", e);
            }
        });

        var results = new AbRunner(REPETITIONS).run(arms);
        var summaries = AbRunner.summarize(results);

        log.info("");
        log.info("======== PER-MESSAGE COST, {} messages x {} reps, {}-byte payloads ========",
                 MESSAGE_COUNT, REPETITIONS, PAYLOAD_BYTES);
        log.info(String.format("%-14s %14s %8s %14s %14s", "arm", "WAL B/msg", "IQR", "deadTup/msg", "tupUpd+Del"));
        for (var summary : summaries) {
            var armResults = results.stream().filter(r -> r.arm().equals(summary.arm())).toList();
            var deadTuples = armResults.stream()
                                       .mapToDouble(r -> (Double) r.extra().get("deadTuplesCreatedPerMessage"))
                                       .average().orElse(0);
            var churn = armResults.stream().mapToLong(r -> (Long) r.extra().get("tupleChurn")).sum() / armResults.size();
            log.info(String.format("%-14s %14.0f %7.1f%% %14.2f %14d",
                                   summary.arm(),
                                   summary.walBytesPerOperation().median(),
                                   100.0d * summary.walBytesPerOperation().interQuartileRange()
                                           / Math.max(1.0d, summary.walBytesPerOperation().median()),
                                   deadTuples,
                                   churn));
        }
        var baseline = summaries.stream().filter(s -> s.arm().equals("baseline")).findFirst().orElseThrow();
        var nextGen = summaries.stream().filter(s -> s.arm().equals("shard-owned")).findFirst().orElseThrow();
        var walChange = 100.0d * (nextGen.walBytesPerOperation().median() - baseline.walBytesPerOperation().median())
                        / baseline.walBytesPerOperation().median();
        log.info("WAL bytes per message: {}{}%  (distributions {})",
                 walChange >= 0 ? "+" : "", String.format("%.1f", walChange),
                 baseline.walBytesPerOperation().overlaps(nextGen.walBytesPerOperation())
                 ? "OVERLAP — this run does not separate the arms"
                 : "are separated");
        log.info("STORAGE-LAYER COMPARISON ONLY: the baseline carries JSON serialization, an interceptor");
        log.info("chain and a UnitOfWork per operation that the shard-owned engine does not yet have.");
        log.info("========================================================================");

        RunResult.writeAll("target/perf-lab-baseline/nextgen-vs-baseline.json",
                           Map.of("comparison", "per-message cost, storage layer only",
                                  "messageCount", MESSAGE_COUNT,
                                  "payloadBytes", PAYLOAD_BYTES,
                                  "environment", environment,
                                  "summaries", summaries,
                                  "runs", results));

        results.forEach(result -> {
            assertThat(result.opsCompleted())
                    .as("every arm must actually have handled the full message count")
                    .isEqualTo(MESSAGE_COUNT);
            // Statistics that have not flushed produce plausible-looking per-message costs that are
            // simply wrong. Assert the denominator the harness measured against the work it knows it
            // did, so a lagging stats collector fails the run instead of quietly flattering an arm.
            assertThat((Long) result.extra().get("inserts"))
                    .as("%s rep %d: table statistics had not settled, so its tuple costs are fiction",
                        result.arm(), result.repetition())
                    .isEqualTo((long) MESSAGE_COUNT);
        });
    }

    private RunResult measureNextGen(int repetition, Map<String, String> environment) throws Exception {
        NextGenSchema.create(dataSource, SHARD_COUNT);
        NextGenSchema.registerQueue(dataSource, QUEUE_ID, SHARD_COUNT);

        var payload = new byte[PAYLOAD_BYTES];
        Arrays.fill(payload, (byte) 'x');
        var handled = new AtomicInteger();

        try (var queue = new NextGenQueue(dataSource, QUEUE_ID, SHARD_COUNT, "bench")) {
            queue.startConsuming(ignored -> handled.incrementAndGet(), ShardOwnerSettings.defaults(), SHARD_COUNT);

            var before = PgSnapshot.capture(dataSource, List.of(NextGenSchema.UNORDERED_TABLE));
            var startNanos = System.nanoTime();

            for (var batch = 0; batch < MESSAGE_COUNT / 100; batch++) {
                var payloads = new ArrayList<byte[]>(100);
                for (var index = 0; index < 100; index++) {
                    payloads.add(payload);
                }
                queue.enqueue(payloads, 1);
            }
            Awaitility.await().atMost(Duration.ofSeconds(120))
                      .untilAsserted(() -> assertThat(handled.get()).isEqualTo(MESSAGE_COUNT));
            Awaitility.await().atMost(Duration.ofSeconds(60))
                      .untilAsserted(() -> assertThat(queue.remaining()).isZero());

            var elapsedMillis = (System.nanoTime() - startNanos) / 1_000_000L;
            // Ground truth, straight from the table, with no filter on queue or shard. The reported
            // delete count came back as zero while the queue's own remaining() said empty, and those
            // two cannot both be right — this says which.
            long rowsLeft;
            try (var connection = dataSource.getConnection();
                 var statement = connection.prepareStatement("SELECT count(*) FROM " + NextGenSchema.UNORDERED_TABLE);
                 var resultSet = statement.executeQuery()) {
                resultSet.next();
                rowsLeft = resultSet.getLong(1);
            }
            assertThat(rowsLeft).as("the arm must have emptied its table before its costs are read").isZero();
            settleStatistics(NextGenSchema.UNORDERED_TABLE, MESSAGE_COUNT, MESSAGE_COUNT);
            var after = PgSnapshot.capture(dataSource, List.of(NextGenSchema.UNORDERED_TABLE));
            return buildResult("shard-owned", repetition, elapsedMillis, before, after,
                               NextGenSchema.UNORDERED_TABLE, environment, queue.metrics().snapshot());
        }
    }

    private RunResult measureBaseline(int repetition, Map<String, String> environment) throws Exception {
        var jdbi = Jdbi.create(dataSource).installPlugin(new PostgresPlugin());
        var durableQueues = PostgresqlDurableQueues.builder()
                                                   .setUnitOfWorkFactory(new JdbiUnitOfWorkFactory(jdbi))
                                                   .setJsonSerializer(EssentialsObjectMappers.createJSONSerializer())
                                                   .setSharedQueueTableName(BASELINE_TABLE)
                                                   .setUseCentralizedMessageFetcher(true)
                                                   .setCentralizedMessageFetcherPollingInterval(Duration.ofMillis(20))
                                                   .build();
        durableQueues.start();
        var queueName = QueueName.of("bench-queue");
        var handled = new AtomicInteger();
        DurableQueueConsumer consumer = null;
        try {
            try (var connection = dataSource.getConnection();
                 var statement = connection.createStatement()) {
                statement.execute("TRUNCATE TABLE " + BASELINE_TABLE);
            }
            consumer = durableQueues.consumeFromQueue(ConsumeFromQueue.builder()
                                                                     .setQueueName(queueName)
                                                                     .setRedeliveryPolicy(RedeliveryPolicy.fixedBackoff(Duration.ofMillis(100), 3))
                                                                     .setParallelConsumers(20)
                                                                     .setQueueMessageHandler(message -> handled.incrementAndGet())
                                                                     .build());

            var before = PgSnapshot.capture(dataSource, List.of(BASELINE_TABLE));
            var startNanos = System.nanoTime();

            var payload = "x".repeat(PAYLOAD_BYTES);
            for (var batch = 0; batch < MESSAGE_COUNT / 100; batch++) {
                var messages = new ArrayList<Message>(100);
                for (var index = 0; index < 100; index++) {
                    messages.add(Message.of(new BenchPayload(payload)));
                }
                durableQueues.queueMessages(queueName, messages);
            }
            Awaitility.await().atMost(Duration.ofSeconds(600))
                      .untilAsserted(() -> assertThat(handled.get()).isEqualTo(MESSAGE_COUNT));

            var elapsedMillis = (System.nanoTime() - startNanos) / 1_000_000L;
            settleStatistics(BASELINE_TABLE, MESSAGE_COUNT, 0L);
            var after = PgSnapshot.capture(dataSource, List.of(BASELINE_TABLE));
            return buildResult("baseline", repetition, elapsedMillis, before, after,
                               BASELINE_TABLE, environment, Map.of());
        } finally {
            if (consumer != null) {
                consumer.stop();
            }
            durableQueues.stop();
        }
    }

    /**
     * Wait for PostgreSQL's cumulative table statistics to catch up before reading them.
     * <p>
     * A backend accumulates {@code n_tup_ins}/{@code upd}/{@code del} locally and flushes to shared
     * memory at most about once a second. The shard-owned arm finishes 20 000 messages in under
     * 400 ms, so without this the counters are simply not there yet — the first run of this
     * comparison reported 16 800, 0 and 0 inserts across three repetitions of identical work, and a
     * dead-tuple figure of 0.10 per message. That is ten times better than the design's own floor of
     * one delete per message, which is the tell: it is not possible, so it was not a measurement.
     * <p>
     * WAL bytes are unaffected, being read from {@code pg_current_wal_lsn} rather than from the
     * statistics collector — which is why they were stable to 0.0% across the same runs.
     */
    /**
     * Wait for the tuple counters to catch up with work that has already committed &mdash; do not
     * guess how long that takes.
     * <p>
     * This was a fixed 2.5 second sleep, and it silently stopped being enough. The queue drained (a
     * plain {@code count(*)} on the table returned zero, so every delete had committed) while
     * {@code pg_stat_user_tables} still reported between 0 and 6 666 of the 20 000 deletes. That
     * produced 0.00 and 0.33 dead tuples per message on consecutive runs, both below the design's own
     * floor of 1.0 and therefore both impossible &mdash; the third time in this work that a plausible
     * per-message figure turned out to be an unflushed statistic.
     *
     * @param table   the table whose counters must catch up
     * @param deletes how many deletes the caller knows happened; {@code n_tup_del} must reach it
     */
    private void settleStatistics(String table, long inserts, long deletes) throws Exception {
        var deadline = System.nanoTime() + Duration.ofSeconds(60).toNanos();
        while (System.nanoTime() < deadline) {
            try (var connection = dataSource.getConnection();
                 var statement = connection.prepareStatement(
                         "SELECT n_tup_ins, n_tup_del FROM pg_stat_user_tables WHERE relname = ?")) {
                statement.setString(1, table);
                try (var resultSet = statement.executeQuery()) {
                    if (resultSet.next()
                        && resultSet.getLong(1) >= inserts
                        && resultSet.getLong(2) >= deletes) {
                        return;
                    }
                }
            }
            Thread.sleep(250L);
        }
        throw new IllegalStateException("Table statistics for " + table + " never caught up with "
                                        + inserts + " inserts and " + deletes + " deletes; any "
                                        + "per-message tuple cost from this run would be fiction");
    }

    private RunResult buildResult(String arm,
                                  int repetition,
                                  long elapsedMillis,
                                  PgSnapshot before,
                                  PgSnapshot after,
                                  String table,
                                  Map<String, String> environment,
                                  Map<String, Object> engineMetrics) {
        var delta = after.deltaFrom(before);
        var updates = delta.getOrDefault("table." + table + ".n_tup_upd", 0L);
        var deletes = delta.getOrDefault("table." + table + ".n_tup_del", 0L);
        var extra = new LinkedHashMap<String, Object>();
        extra.put("tupleUpdates", updates);
        extra.put("tupleDeletes", deletes);
        extra.put("tupleChurn", updates + deletes);
        extra.put("deadTuplesCreatedPerMessage", (double) (updates + deletes) / MESSAGE_COUNT);
        extra.put("inserts", delta.getOrDefault("table." + table + ".n_tup_ins", 0L));
        extra.put("engineMetrics", engineMetrics);
        return new RunResult("nextgen-vs-baseline", arm, repetition, Instant.now(), elapsedMillis,
                             MESSAGE_COUNT,
                             elapsedMillis == 0 ? 0.0d : MESSAGE_COUNT * 1000.0d / elapsedMillis,
                             Map.of("messageCount", MESSAGE_COUNT, "payloadBytes", PAYLOAD_BYTES),
                             List.of(), delta, Map.of(), environment, extra);
    }

    public record BenchPayload(String payload) {
    }
}
