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
import dk.trustworks.essentials.components.foundation.messaging.RedeliveryPolicy;
import dk.trustworks.essentials.components.foundation.messaging.queue.*;
import dk.trustworks.essentials.components.foundation.messaging.queue.operations.ConsumeFromQueue;
import dk.trustworks.essentials.components.foundation.transaction.jdbi.JdbiUnitOfWorkFactory;
import dk.trustworks.essentials.components.queue.postgresql.PostgresqlDurableQueues;
import dk.trustworks.essentials.components.queue.shardowned.*;
import dk.trustworks.essentials.examples.perflab.harness.*;
import org.awaitility.Awaitility;
import org.jdbi.v3.core.Jdbi;
import org.jdbi.v3.postgres.PostgresPlugin;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.*;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import java.time.Instant;
import java.util.function.IntFunction;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * What each engine costs to move the same messages: throughput, threads, and held connections.
 *
 * <h2>The trap this test is built around</h2>
 * <b>The current implementation's throughput is a configuration choice, not a property of its
 * design.</b> Every measurement in this lab lands on {@code parallelConsumers x pollsPerSecond} with
 * an interquartile range under 1% — the database is never the limit. So a single "engine A does N,
 * engine B does M" comparison says almost nothing: pick a 20 ms poll and the baseline looks ten times
 * slower, pick 2 ms and it nearly closes the gap, and neither number describes the engine.
 * <p>
 * The baseline therefore runs at <b>two poll cadences</b> here, so the ceiling it is bound by is
 * visible in the output rather than hidden in a constant. The shard-owned engine has no equivalent
 * knob — it is woken by {@code NOTIFY} and by local hand-off — which is itself the finding.
 * <p>
 * <b>Resources are the comparison that survives.</b> Threads and held connections are properties of
 * each design and do not move with the operating point: the baseline needs a connection per consumer
 * to acknowledge on, so its pool use scales with {@code parallelConsumers}, while the shard-owned
 * engine holds {@code pumpThreads + 1} per process regardless of queues, shards or consumers. That
 * ratio is the honest headline, and it is what this test reports alongside throughput rather than
 * underneath it.
 *
 * <h2>Reading the output</h2>
 * Arms are interleaved by {@link AbRunner} within one run, so the <em>relative</em> figures hold even
 * though absolute throughput in this lab varies by nearly an order of magnitude between sessions.
 * Compare arms against each other inside one run; do not quote a number across runs.
 */
@Testcontainers(disabledWithoutDocker = true)
@EnabledIfSystemProperty(named = "benchmark.run", matches = "true")
class EngineResourceComparisonIT {

    private static final int   MESSAGE_COUNT = 20_000;
    private static final int   PAYLOAD_BYTES = 200;
    private static final int   REPETITIONS   = 3;
    private static final short QUEUE_ID      = 1;
    private static final int   SHARD_COUNT   = 8;
    private static final int   BASELINE_CONSUMERS = 20;
    /** A second consumer count, so "connections scale with consumers" is measured rather than asserted. */
    private static final int   BASELINE_CONSUMERS_DOUBLED = 40;
    private static final String BASELINE_TABLE = "baseline_durable_queue";

    @Container
    static PostgreSQLContainer<?> postgres = LabPostgres.create();

    private HikariDataSource dataSource;

    @BeforeEach
    void setUp() throws Exception {
        var config = new HikariConfig();
        config.setJdbcUrl(postgres.getJdbcUrl());
        config.setUsername(postgres.getUsername());
        config.setPassword(postgres.getPassword());
        // Deliberately generous. The point is to observe how many connections each engine ACTUALLY
        // holds, which a pool small enough to throttle either arm would hide.
        config.setMaximumPoolSize(120);
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
    void throughput_threads_and_connections_for_both_engines() {
        var environment = LabPostgres.describe();
        var arms = new LinkedHashMap<String, IntFunction<RunResult>>();
        arms.put("baseline-20ms-20consumers",
                 repetition -> measureBaseline(repetition, Duration.ofMillis(20), BASELINE_CONSUMERS, environment));
        // Same poll, twice the consumers. The baseline's two routes to more throughput are more
        // consumers or a faster poll, and this arm prices the first one in connections.
        arms.put("baseline-20ms-40consumers",
                 repetition -> measureBaseline(repetition, Duration.ofMillis(20), BASELINE_CONSUMERS_DOUBLED, environment));
        arms.put("baseline-5ms-20consumers",
                 repetition -> measureBaseline(repetition, Duration.ofMillis(5), BASELINE_CONSUMERS, environment));
        arms.put("shard-owned", repetition -> measureShardOwned(repetition, environment));

        var results = new AbRunner(REPETITIONS).run(arms);
        var summaries = AbRunner.summarize(results);

        System.out.println();
        // The banner is five '=' and there is a matching one below, because scripts/perf-host.sh
        // extracts each suite's table by toggling on /=====/ and copying what lies between. A
        // three-'=' opener with no closer reads as no table at all, and the run's summary file
        // comes out empty while the suite itself passes.
        System.out.println("===== THROUGHPUT, THREADS AND CONNECTIONS, " + MESSAGE_COUNT
                           + " messages of " + PAYLOAD_BYTES + " bytes =====");
        // The slowest and fastest repetition are printed beside the median on purpose. "IQR 44%" is
        // a statistic a reader can skim past; "3 runs of the same thing produced 2 900 and 5 400" is
        // not, and it is the same fact.
        System.out.printf("%-26s %10s %8s %18s %9s %11s%n",
                          "arm", "msg/s", "IQR", "slowest..fastest run", "threads", "held conns");
        for (var summary : summaries) {
            var throughput = summary.throughputPerSecond();
            System.out.printf("%-26s %10.0f %7.1f%% %8.0f..%-8.0f %9.0f %11.0f%n",
                              summary.arm(),
                              throughput.median(),
                              throughput.median() == 0.0d ? Double.NaN
                                                          : 100.0d * throughput.interQuartileRange() / throughput.median(),
                              throughput.min(),
                              throughput.max(),
                              distributionOf(results, summary.arm(), "peakThreads").median(),
                              distributionOf(results, summary.arm(), "peakHeldConnections").median());
        }
        System.out.println();
        for (var summary : summaries) {
            var throughput = summary.throughputPerSecond();
            var iqrPercent = throughput.median() == 0.0d ? 0.0d
                                                         : 100.0d * throughput.interQuartileRange() / throughput.median();
            if (iqrPercent > 25.0d) {
                System.out.printf("  !! %s throughput spread is %.0f%% — DO NOT quote this figure. "
                                  + "This lab cannot hold a saturated throughput number still.%n",
                                  summary.arm(), iqrPercent);
            }
        }
        System.out.println();
        System.out.println("  The baseline's msg/s is pinned to parallelConsumers x pollsPerSecond, so it is a");
        System.out.println("  configuration choice rather than a property: 20x50=1000/s and 20x200=4000/s below.");
        System.out.println("  Its two routes to more throughput both cost something — more consumers costs");
        System.out.println("  connections, a faster poll costs query rate. The shard-owned engine is woken by");
        System.out.println("  NOTIFY and local hand-off, so it pays neither, and its connection count is");
        System.out.println("  pumpThreads + 1 per PROCESS however much it is asked to do.");
        System.out.println("==========================================================================");
        System.out.println();

        // Correctness of the measurement before any claim about it: every arm must actually have
        // moved the whole workload, or its throughput is a division by the wrong denominator.
        for (var result : results) {
            assertThat(((Number) result.extra().get("handled")).intValue())
                    .as("%s must have handled every message for its throughput to mean anything", result.scenario())
                    .isEqualTo(MESSAGE_COUNT);
        }

        // The one relation that is a design property rather than an operating point: the shard-owned
        // engine's connection use does not grow with consumer count, the baseline's does.
        var shardOwned = distributionOf(results, "shard-owned", "peakHeldConnections").median();
        var baseline20 = distributionOf(results, "baseline-20ms-20consumers", "peakHeldConnections").median();
        var baseline40 = distributionOf(results, "baseline-20ms-40consumers", "peakHeldConnections").median();

        // The mechanism, not just the gap: the baseline needs a connection per consumer to
        // acknowledge on, so doubling the consumers it needs for throughput costs connections.
        assertThat(baseline40)
                .as("doubling the baseline's consumers must cost connections — that is why its "
                    + "throughput and its pool use cannot be tuned independently")
                .isGreaterThan(baseline20);
        assertThat(shardOwned)
                .as("shard-owned holds pumpThreads + 1 per process, whatever it is asked to do")
                .isLessThan(baseline20);
    }

    /**
     * {@link AbRunner.ArmSummary} aggregates a fixed set of metrics, and threads and connections are
     * not among them — so they are aggregated here from the raw results, with the same
     * {@link AbRunner.Distribution} so a median never appears without its spread.
     */
    private static AbRunner.Distribution distributionOf(List<RunResult> results, String arm, String metric) {
        var values = results.stream()
                            .filter(result -> result.arm().equals(arm))
                            .mapToDouble(result -> result.jvmDelta().getOrDefault(metric, 0L))
                            .toArray();
        return AbRunner.Distribution.of(values);
    }

    /**
     * Samples threads and held connections while an arm runs.
     * <p>
     * Held (checked-out) connections, not rows in {@code pg_stat_activity}: the latter is mostly the
     * pool keeping what it once opened, and it reports roughly twice the truth. Held is the number
     * that starves an application.
     */
    private final class ResourceSampler implements AutoCloseable {
        private final ScheduledExecutorService sampler = Executors.newSingleThreadScheduledExecutor(runnable -> {
            var thread = new Thread(runnable, "resource-sampler");
            thread.setDaemon(true);
            return thread;
        });
        private final AtomicInteger peakThreads     = new AtomicInteger();
        private final AtomicInteger peakConnections = new AtomicInteger();

        private ResourceSampler() {
            sampler.scheduleAtFixedRate(() -> {
                peakThreads.accumulateAndGet(Thread.getAllStackTraces().size(), Math::max);
                peakConnections.accumulateAndGet(dataSource.getHikariPoolMXBean().getActiveConnections(), Math::max);
            }, 0, 25, TimeUnit.MILLISECONDS);
        }

        @Override
        public void close() {
            sampler.shutdownNow();
        }
    }

    private RunResult measureShardOwned(int repetition, Map<String, String> environment) {
        var handled = new AtomicInteger();
        var runtime = new ShardRuntime(dataSource, ShardOwnerSettings.defaults());
        try (var sampler = new ResourceSampler();
             var queue = PostgresqlMessageQueue.builder()
                                               .setDataSource(dataSource)
                                               .setQueueId(QUEUE_ID)
                                               .setShardCount(SHARD_COUNT)
                                               .setInstanceId("cmp-shard-owned")
                                               .build()) {
            queue.consume((messageId, key, payload, payloadType) -> handled.incrementAndGet(),
                          dk.trustworks.essentials.components.queue.shardowned.spi.ConsumerOptions.defaults());

            var payload = "x".repeat(PAYLOAD_BYTES).getBytes(StandardCharsets.UTF_8);
            var startNanos = System.nanoTime();
            for (var batch = 0; batch < MESSAGE_COUNT / 100; batch++) {
                var messages = new ArrayList<dk.trustworks.essentials.components.queue.shardowned.spi.Message>(100);
                for (var index = 0; index < 100; index++) {
                    messages.add(dk.trustworks.essentials.components.queue.shardowned.spi.Message.of(payload, 1));
                }
                queue.enqueue(messages);
            }
            Awaitility.await().atMost(Duration.ofSeconds(600))
                      .untilAsserted(() -> assertThat(handled.get()).isEqualTo(MESSAGE_COUNT));
            var elapsedMillis = (System.nanoTime() - startNanos) / 1_000_000L;

            return result("shard-owned", repetition, elapsedMillis, handled.get(), sampler, environment,
                          Map.of("wakeup", "NOTIFY + local hand-off", "pollInterval", "none"));
        } catch (Exception e) {
            throw new IllegalStateException("shard-owned arm failed", e);
        } finally {
            runtime.stop();
        }
    }

    private RunResult measureBaseline(int repetition, Duration pollingInterval, int parallelConsumers,
                                      Map<String, String> environment) {
        var jdbi = Jdbi.create(dataSource).installPlugin(new PostgresPlugin());
        var durableQueues = PostgresqlDurableQueues.builder()
                                                   .setUnitOfWorkFactory(new JdbiUnitOfWorkFactory(jdbi))
                                                   .setJsonSerializer(dk.trustworks.essentials.components.foundation.json.EssentialsObjectMappers.createJSONSerializer())
                                                   .setSharedQueueTableName(BASELINE_TABLE)
                                                   .setUseCentralizedMessageFetcher(true)
                                                   .setCentralizedMessageFetcherPollingInterval(pollingInterval)
                                                   .build();
        durableQueues.start();
        var handled = new AtomicInteger();
        DurableQueueConsumer consumer = null;
        try (var sampler = new ResourceSampler()) {
            try (var connection = dataSource.getConnection();
                 var statement = connection.createStatement()) {
                statement.execute("TRUNCATE TABLE " + BASELINE_TABLE);
            }
            var queueName = QueueName.of("cmp-queue");
            consumer = durableQueues.consumeFromQueue(ConsumeFromQueue.builder()
                                                                     .setQueueName(queueName)
                                                                     .setRedeliveryPolicy(RedeliveryPolicy.fixedBackoff(Duration.ofMillis(100), 3))
                                                                     .setParallelConsumers(parallelConsumers)
                                                                     .setQueueMessageHandler(message -> handled.incrementAndGet())
                                                                     .build());

            var payload = "x".repeat(PAYLOAD_BYTES);
            var startNanos = System.nanoTime();
            for (var batch = 0; batch < MESSAGE_COUNT / 100; batch++) {
                var messages = new ArrayList<dk.trustworks.essentials.components.foundation.messaging.queue.Message>(100);
                for (var index = 0; index < 100; index++) {
                    messages.add(dk.trustworks.essentials.components.foundation.messaging.queue.Message.of(new BenchPayload(payload)));
                }
                durableQueues.queueMessages(queueName, messages);
            }
            Awaitility.await().atMost(Duration.ofSeconds(600))
                      .untilAsserted(() -> assertThat(handled.get()).isEqualTo(MESSAGE_COUNT));
            var elapsedMillis = (System.nanoTime() - startNanos) / 1_000_000L;

            var ceiling = parallelConsumers * (1_000L / pollingInterval.toMillis());
            return result("baseline-" + pollingInterval.toMillis() + "ms-" + parallelConsumers + "consumers",
                          repetition, elapsedMillis, handled.get(), sampler, environment,
                          Map.of("wakeup", "poll",
                                 "pollIntervalMillis", pollingInterval.toMillis(),
                                 "parallelConsumers", parallelConsumers,
                                 "pollCadenceCeilingPerSecond", ceiling));
        } catch (Exception e) {
            throw new IllegalStateException("baseline arm failed", e);
        } finally {
            if (consumer != null) {
                consumer.stop();
            }
            durableQueues.stop();
        }
    }

    private RunResult result(String arm,
                             int repetition,
                             long elapsedMillis,
                             int handled,
                             ResourceSampler sampler,
                             Map<String, String> environment,
                             Map<String, Object> extra) {
        var throughput = elapsedMillis == 0 ? 0.0d : (handled * 1_000.0d) / elapsedMillis;
        var allExtra = new LinkedHashMap<String, Object>(extra);
        allExtra.put("handled", handled);
        return new RunResult("engine-resource-comparison", arm, repetition, Instant.now(),
                             elapsedMillis, handled, throughput,
                             Map.of("messageCount", MESSAGE_COUNT, "payloadBytes", PAYLOAD_BYTES),
                             List.of(), Map.of(),
                             Map.of("peakThreads", (long) sampler.peakThreads.get(),
                                    "peakHeldConnections", (long) sampler.peakConnections.get()),
                             environment, allExtra);
    }

    public record BenchPayload(String value) {
    }
}
