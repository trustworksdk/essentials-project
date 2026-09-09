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
import dk.trustworks.essentials.examples.perflab.harness.*;
import dk.trustworks.essentials.components.queue.shardowned.*;
import org.awaitility.Awaitility;
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
 * Decomposes the shard-owned engine's cost advantage into the parts that survive realistic
 * obligations and the parts that do not.
 * <p>
 * The headline comparison measured 524 WAL bytes per message against the existing engine's 1 905, a
 * 72.5% reduction — but the two were not carrying the same load. The baseline serializes payloads to
 * JSON, opens a transaction per operation, and acknowledges one message at a time; the shard-owned
 * engine moved opaque bytes in batches of a hundred. A headline number that mixes a design advantage
 * with a batching advantage is exactly how a prototype's win evaporates on implementation, because
 * only one of the two is inherent to the design.
 * <p>
 * So each obligation is added back one at a time, against the same engine, and what each one costs is
 * measured rather than argued about:
 * <ol>
 *     <li><b>bytes, batched</b> — the headline configuration</li>
 *     <li><b>+ JSON</b> — payloads serialized with the same flavour-neutral serializer the baseline uses</li>
 *     <li><b>+ per-message enqueue</b> — one transaction per message instead of a hundred per batch</li>
 *     <li><b>+ per-message ack</b> — no batched acknowledgement either</li>
 * </ol>
 * What remains at the bottom of that list is the part of the advantage that comes from the design
 * rather than from the harness being generous.
 */
@Testcontainers(disabledWithoutDocker = true)
@EnabledIfSystemProperty(named = "benchmark.run", matches = "true")
class ShardOwnedCostDecompositionIT {
    private static final Logger log = LoggerFactory.getLogger(ShardOwnedCostDecompositionIT.class);

    private static final short QUEUE_ID      = 1;
    private static final int   SHARD_COUNT   = 8;
    private static final int   MESSAGE_COUNT = 20_000;
    private static final int   PAYLOAD_BYTES = 200;
    private static final int   REPETITIONS   = 3;

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
    void decompose_the_cost_advantage_into_design_and_batching() throws Exception {
        var environment = PgSnapshot.captureEnvironment(dataSource);
        var steps = List.of(new Step("bytes, batched", false, 100, false),
                            new Step("+ JSON payload", true, 100, false),
                            new Step("+ per-msg enqueue", true, 1, false),
                            new Step("+ per-msg ack", true, 1, true));

        var arms = new LinkedHashMap<String, java.util.function.IntFunction<RunResult>>();
        for (var step : steps) {
            arms.put(step.name(), repetition -> {
                try {
                    return measure(step, repetition, environment);
                } catch (Exception e) {
                    throw new IllegalStateException("step '" + step.name() + "' failed", e);
                }
            });
        }

        var results = new AbRunner(REPETITIONS).run(arms);
        var summaries = AbRunner.summarize(results);

        log.info("");
        log.info("===== COST DECOMPOSITION, {} messages x {} reps =====", MESSAGE_COUNT, REPETITIONS);
        log.info(String.format("%-20s %11s %7s %11s %11s", "obligation added", "WAL B/msg", "IQR", "vs prev", "vs baseline"));
        var baselineWal = 1905.0d; // measured in ShardOwnedVsBaselineCostIT under the same conditions
        Double previous = null;
        for (var summary : summaries) {
            var wal = summary.walBytesPerOperation().median();
            log.info(String.format("%-20s %11.0f %6.1f%% %10s %10.0f%%",
                                   summary.arm(),
                                   wal,
                                   100.0d * summary.walBytesPerOperation().interQuartileRange() / Math.max(1.0d, wal),
                                   previous == null ? "-" : String.format("%+.0f%%", 100.0d * (wal - previous) / previous),
                                   100.0d * (wal - baselineWal) / baselineWal));
            previous = wal;
        }
        log.info("Existing engine, same conditions: {} WAL bytes/msg", String.format("%.0f", baselineWal));
        log.info("The last row is the honest number: the design's advantage with every obligation restored.");
        log.info("=====================================================");

        RunResult.writeAll("target/perf-lab-baseline/nextgen-cost-decomposition.json",
                           Map.of("comparison", "cost decomposition",
                                  "messageCount", MESSAGE_COUNT,
                                  "baselineWalBytesPerMessage", baselineWal,
                                  "environment", environment,
                                  "summaries", summaries,
                                  "runs", results));

        results.forEach(result -> assertThat((Long) result.extra().get("inserts"))
                .as("%s rep %d: statistics had not settled", result.arm(), result.repetition())
                .isEqualTo((long) MESSAGE_COUNT));
    }

    private RunResult measure(Step step, int repetition, Map<String, String> environment) throws Exception {
        ShardOwnedSchema.recreate(dataSource);
        ShardOwnedSchema.registerQueue(dataSource, QUEUE_ID, SHARD_COUNT);

        var serializer = EssentialsObjectMappers.createJSONSerializer();
        var raw = new byte[PAYLOAD_BYTES];
        Arrays.fill(raw, (byte) 'x');
        var jsonBody = new BenchPayload("x".repeat(PAYLOAD_BYTES));
        var payload = step.json()
                      ? serializer.serialize(jsonBody).getBytes(StandardCharsets.UTF_8)
                      : raw;

        var defaults = ShardOwnerSettings.defaults();
        var settings = step.perMessageAck()
                       ? new ShardOwnerSettings(defaults.readBatchSize(), 1, Duration.ZERO,
                                                defaults.chaseDelay(), defaults.holeExpiry(), defaults.sweepInterval(),
                                                defaults.maxHolesPerChase(),
                                                defaults.keyConcurrency(), defaults.pollBackstop(), Duration.ofSeconds(30), 2, Duration.ofSeconds(5), Duration.ofMillis(30000))
                       : defaults;

        var handled = new AtomicInteger();
        try (var queue = new ShardOwnedQueue(dataSource, QUEUE_ID, SHARD_COUNT, "bench")) {
            queue.startConsuming((ignored, payloadType) -> handled.incrementAndGet(), settings, SHARD_COUNT);

            var before = PgSnapshot.capture(dataSource, List.of(ShardOwnedSchema.UNORDERED_TABLE));
            var startNanos = System.nanoTime();

            var batch = new ArrayList<byte[]>(step.enqueueBatchSize());
            for (var index = 0; index < MESSAGE_COUNT; index++) {
                batch.add(payload);
                if (batch.size() == step.enqueueBatchSize()) {
                    queue.enqueue(List.copyOf(batch), 1);
                    batch.clear();
                }
            }
            if (!batch.isEmpty()) {
                queue.enqueue(List.copyOf(batch), 1);
            }

            Awaitility.await().atMost(Duration.ofSeconds(300))
                      .untilAsserted(() -> assertThat(handled.get()).isEqualTo(MESSAGE_COUNT));
            Awaitility.await().atMost(Duration.ofSeconds(120))
                      .untilAsserted(() -> assertThat(queue.remaining()).isZero());

            var elapsedMillis = (System.nanoTime() - startNanos) / 1_000_000L;
            // Table statistics flush about once a second per backend; a fast arm would otherwise be
            // measured against counters that had not arrived.
            Thread.sleep(2_500L);
            var after = PgSnapshot.capture(dataSource, List.of(ShardOwnedSchema.UNORDERED_TABLE));

            var delta = after.deltaFrom(before);
            var table = "table." + ShardOwnedSchema.UNORDERED_TABLE + ".";
            var extra = new LinkedHashMap<String, Object>();
            extra.put("inserts", delta.getOrDefault(table + "n_tup_ins", 0L));
            extra.put("tupleUpdates", delta.getOrDefault(table + "n_tup_upd", 0L));
            extra.put("tupleDeletes", delta.getOrDefault(table + "n_tup_del", 0L));
            extra.put("commits", delta.getOrDefault("db.xact_commit", 0L));
            extra.put("payloadBytesOnWire", payload.length);
            extra.put("enqueueBatchSize", step.enqueueBatchSize());
            extra.put("perMessageAck", step.perMessageAck());

            return new RunResult("cost-decomposition", step.name(), repetition, Instant.now(), elapsedMillis,
                                 MESSAGE_COUNT,
                                 elapsedMillis == 0 ? 0.0d : MESSAGE_COUNT * 1000.0d / elapsedMillis,
                                 Map.of("json", step.json()),
                                 List.of(), delta, Map.of(), environment, extra);
        }
    }

    private record Step(String name, boolean json, int enqueueBatchSize, boolean perMessageAck) {
    }

    public record BenchPayload(String payload) {
    }
}
