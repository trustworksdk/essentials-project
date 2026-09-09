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
import dk.trustworks.essentials.components.queue.shardowned.*;
import dk.trustworks.essentials.components.queue.shardowned.spi.*;
import dk.trustworks.essentials.examples.perflab.harness.*;
import org.awaitility.Awaitility;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.*;

import java.nio.charset.StandardCharsets;
import java.time.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.IntFunction;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * What {@code shardCount} actually buys, on the lane where it cannot be changed later.
 *
 * <h2>Why this exists</h2>
 * The engine shipped telling callers that shards are "the unit of parallelism and of ordering" and
 * that a quiet queue should have fewer than a busy one — both true, neither a number. Nothing swept
 * the knob, so the worked examples' "8 for busy, 1–2 for quiet" rested on nothing measured. That is
 * the same shape as the process-wide handler ceiling that was removed for being an unmeasured
 * constant, and it matters more here, because on the ordered lane {@code shardCount} cannot be raised
 * without draining the lane and restarting every instance.
 *
 * <h2>What is measured, and what cannot be</h2>
 * Arms are interleaved by {@link AbRunner}, so the <em>relative</em> shape of the curve survives an
 * environment whose absolute throughput moves by an order of magnitude between runs. Absolute msg/s
 * here is not a result and is not quoted as one.
 * <p>
 * The ordered lane is the subject because it is the one with the irreversible decision. The
 * theoretical ceiling on concurrent work is {@code shardCount x keyConcurrency}, so the question is
 * where real throughput stops following that product — the point past which more shards buy idle cost
 * and nothing else.
 */
@Testcontainers(disabledWithoutDocker = true)
@EnabledIfSystemProperty(named = "benchmark.run", matches = "true")
class ShardOwnedShardCountSweepIT {

    private static final int   MESSAGE_COUNT = 4_000;
    private static final int   KEYS          = 500;
    private static final int   PAYLOAD_BYTES = 200;
    private static final int   REPETITIONS   = 3;
    /** Slow enough that concurrency is what is being measured rather than the driver loop. */
    private static final long  HANDLER_MILLIS = 2;
    private static final int[] SHARD_COUNTS  = {1, 2, 4, 8, 16};

    @Container
    static PostgreSQLContainer<?> postgres = LabPostgres.create();

    private HikariDataSource dataSource;

    @BeforeEach
    void setUp() throws Exception {
        var config = new HikariConfig();
        config.setJdbcUrl(postgres.getJdbcUrl());
        config.setUsername(postgres.getUsername());
        config.setPassword(postgres.getPassword());
        config.setMaximumPoolSize(60);
        dataSource = new HikariDataSource(config);
        ShardOwnedSchema.recreate(dataSource);
        // One queue per arm: shardCount is fixed at registration, which is the constraint under test.
        for (var shards : SHARD_COUNTS) {
            ShardOwnedSchema.registerQueue(dataSource, QueueName.of("sweep-" + shards), shards);
        }
    }

    @AfterEach
    void tearDown() {
        if (dataSource != null) {
            dataSource.close();
        }
    }

    @Test
    void ordered_throughput_against_shard_count() {
        var environment = LabPostgres.describe();
        var arms = new LinkedHashMap<String, IntFunction<RunResult>>();
        for (var shards : SHARD_COUNTS) {
            arms.put("shards-" + shards, repetition -> measure(repetition, shards, environment));
        }

        var results = new AbRunner(REPETITIONS).run(arms);
        var summaries = AbRunner.summarize(results);
        var peak = summaries.stream().mapToDouble(s -> s.throughputPerSecond().median()).max().orElse(1.0d);

        System.out.println();
        System.out.println("=== ordered lane, " + KEYS + " keys, " + HANDLER_MILLIS + "ms handler, keyConcurrency 8 ===");
        System.out.printf("%-12s %10s %8s %12s %14s %18s%n",
                          "shards", "msg/s", "IQR", "% of peak", "per shard", "concurrency ceiling");
        for (var summary : summaries) {
            var shards = Integer.parseInt(summary.arm().substring("shards-".length()));
            var throughput = summary.throughputPerSecond();
            System.out.printf("%-12d %10.0f %7.1f%% %11.0f%% %14.0f %18d%n",
                              shards,
                              throughput.median(),
                              throughput.median() == 0.0d ? Double.NaN
                                                          : 100.0d * throughput.interQuartileRange() / throughput.median(),
                              100.0d * throughput.median() / peak,
                              throughput.median() / shards,
                              shards * 8L);
        }
        System.out.println();
        System.out.println("  Absolute msg/s is NOT a result — this lab moves by an order of magnitude between");
        System.out.println("  runs. The shape of the 'per shard' column is: it says where a shard stops");
        System.out.println("  earning its keep. Idle cost is ~0.1 queries/s per owned shard per lane, so shards");
        System.out.println("  past that point are pure overhead — and on the ordered lane the decision cannot");
        System.out.println("  be revisited without draining the lane and restarting every instance.");
        System.out.println();

        for (var result : results) {
            assertThat(((Number) result.extra().get("handled")).intValue())
                    .as("%s must have handled every message", result.arm())
                    .isEqualTo(MESSAGE_COUNT);
        }

        // The one relation this sweep exists to establish: more shards buy concurrency on the ordered
        // lane, so one shard must not match many. If it does, the sweep measured the driver.
        var one = named(summaries, "shards-1").throughputPerSecond().median();
        var eight = named(summaries, "shards-8").throughputPerSecond().median();
        assertThat(eight)
                .as("shards are the unit of cross-key parallelism; eight must beat one or this "
                    + "measured something other than the knob")
                .isGreaterThan(one);
    }

    private static AbRunner.ArmSummary named(List<AbRunner.ArmSummary> summaries, String arm) {
        return summaries.stream().filter(summary -> summary.arm().equals(arm)).findFirst().orElseThrow();
    }

    private RunResult measure(int repetition, int shards, Map<String, String> environment) {
        var handled = new AtomicInteger();
        try (var queue = PostgresqlMessageQueue.builder()
                                               .setDataSource(dataSource)
                                               .setQueueName(QueueName.of("sweep-" + shards))
                                               .setInstanceId("sweep-" + shards + "-" + repetition)
                                               .build()) {
            queue.consume((key, payload, payloadType) -> {
                              try {
                                  Thread.sleep(HANDLER_MILLIS);
                              } catch (InterruptedException e) {
                                  Thread.currentThread().interrupt();
                                  throw new IllegalStateException(e);
                              }
                              handled.incrementAndGet();
                          },
                          ConsumerOptions.defaults());

            var payload = "x".repeat(PAYLOAD_BYTES).getBytes(StandardCharsets.UTF_8);
            var messages = new ArrayList<Message>(MESSAGE_COUNT);
            for (var index = 0; index < MESSAGE_COUNT; index++) {
                messages.add(Message.ordered(payload, 1, "key-" + (index % KEYS), index / KEYS));
            }
            var startNanos = System.nanoTime();
            queue.enqueue(messages);
            Awaitility.await().atMost(Duration.ofSeconds(600))
                      .untilAsserted(() -> assertThat(handled.get()).isEqualTo(MESSAGE_COUNT));
            var elapsedMillis = (System.nanoTime() - startNanos) / 1_000_000L;

            var throughput = elapsedMillis == 0 ? 0.0d : (handled.get() * 1_000.0d) / elapsedMillis;
            return new RunResult("shard-count-sweep", "shards-" + shards, repetition, Instant.now(),
                                 elapsedMillis, handled.get(), throughput,
                                 Map.of("shardCount", shards, "keys", KEYS, "handlerMillis", HANDLER_MILLIS),
                                 List.of(), Map.of(), Map.of(), environment,
                                 Map.of("handled", handled.get(), "concurrencyCeiling", shards * 8L));
        } catch (Exception e) {
            throw new IllegalStateException("shards-" + shards + " arm failed", e);
        }
    }
}
