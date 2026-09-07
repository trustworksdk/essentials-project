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
import dk.trustworks.essentials.examples.perflab.harness.*;
import org.awaitility.Awaitility;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.slf4j.*;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.*;

import java.time.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.IntFunction;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * How much handler concurrency is worth having &mdash; measured, rather than assumed.
 * <p>
 * <b>Why this is measurable when absolute throughput is not.</b> Phase 0 found throughput at
 * saturation varying 861% on this hardware, and every gate here is written against per-message cost
 * for that reason. But choosing {@code parallelConsumers} does not need an absolute number: it needs
 * to know where <em>more</em> concurrency stops buying anything, which is a comparison between arms.
 * The arms are interleaved by {@link AbRunner} in one container in one run, so the drift that ruins
 * an absolute figure is largely common to all of them &mdash; the same reason the WAL comparison
 * holds to an interquartile range of a few tenths of a percent while raw throughput swings.
 * <p>
 * <b>The handler has to block, or the question is meaningless.</b> A handler that returns immediately
 * is CPU-bound, and its optimum is roughly one thread per core no matter what a queue does. Real
 * handlers wait on something &mdash; another service, another database &mdash; and that is where
 * concurrency pays. This sweeps a 2&nbsp;ms handler, which stands in for exactly that.
 * <p>
 * The figure to read is drain time for a fixed workload, and its interquartile range. Where the
 * medians stop separating, more concurrency is buying nothing.
 */
@Testcontainers(disabledWithoutDocker = true)
class NextGenConcurrencySweepIT {
    private static final Logger log = LoggerFactory.getLogger(NextGenConcurrencySweepIT.class);

    private static final short QUEUE_ID      = 1;
    private static final int   SHARD_COUNT   = 8;
    private static final int   MESSAGE_COUNT = 4_000;
    private static final long  HANDLER_MILLIS = 2L;
    private static final int   REPETITIONS   = 3;
    private static final int[] ARMS          = {1, 2, 4, 8, 16, 32, 64, 128};

    @Container
    static PostgreSQLContainer<?> postgres = LabPostgres.create("max_connections=200");

    private HikariDataSource dataSource;

    @BeforeEach
    void setUp() {
        var config = new HikariConfig();
        config.setJdbcUrl(postgres.getJdbcUrl());
        config.setUsername(postgres.getUsername());
        config.setPassword(postgres.getPassword());
        config.setMaximumPoolSize(40);
        dataSource = new HikariDataSource(config);
    }

    @AfterEach
    void tearDown() {
        if (dataSource != null) {
            dataSource.close();
        }
    }

    @Test
    @EnabledIfSystemProperty(named = "benchmark.run", matches = "true")
    void find_where_more_parallel_consumers_stops_buying_anything() throws Exception {
        var environment = new LinkedHashMap<>(LabPostgres.describe());
        environment.put("handlerMillis", String.valueOf(HANDLER_MILLIS));
        environment.put("messages", String.valueOf(MESSAGE_COUNT));

        var arms = new LinkedHashMap<String, IntFunction<RunResult>>();
        for (var parallelConsumers : ARMS) {
            var value = parallelConsumers;
            arms.put(String.valueOf(value), repetition -> {
                try {
                    return measure(value, repetition, environment);
                } catch (Exception e) {
                    throw new IllegalStateException("arm " + value + " failed", e);
                }
            });
        }

        var results = new AbRunner(REPETITIONS).run(arms);
        var summaries = AbRunner.summarize(results);

        log.warn("===== DRAIN TIME BY parallelConsumers, {} messages, {}ms handler, {} shards =====",
                 MESSAGE_COUNT, HANDLER_MILLIS, SHARD_COUNT);
        log.warn("parallelConsumers    drain ms (median)      IQR       msg/s");
        for (var summary : summaries) {
            var median = summary.throughputPerSecond().median();
            var drain = median > 0 ? MESSAGE_COUNT / median * 1000.0 : Double.NaN;
            log.warn(String.format("%-18s %14.0f %9.1f%% %11.0f",
                                   summary.arm(), drain,
                                   summary.throughputPerSecond().interQuartileRange() / Math.max(1e-9, median) * 100.0,
                                   median));
        }
        log.warn("A flat region means more concurrency is buying nothing; pick the knee, not the peak.");
        log.warn("=============================================================================");

        // The floor: one handler at a time cannot beat many when the handler blocks. If this does not
        // hold, the sweep measured nothing and no default should be read off it.
        var single = summaries.stream().filter(s -> s.arm().equals("1")).findFirst().orElseThrow();
        var eight = summaries.stream().filter(s -> s.arm().equals("8")).findFirst().orElseThrow();
        assertThat(eight.throughputPerSecond().median())
                .as("a blocking handler must go faster with eight in flight than with one, or this "
                    + "sweep is not measuring concurrency at all")
                .isGreaterThan(single.throughputPerSecond().median() * 1.5);
    }

    private RunResult measure(int parallelConsumers, int repetition, Map<String, String> environment) throws Exception {
        NextGenSchema.create(dataSource, SHARD_COUNT);
        NextGenSchema.registerQueue(dataSource, QUEUE_ID, SHARD_COUNT);

        var payload = new byte[200];
        Arrays.fill(payload, (byte) 'x');
        var handled = new AtomicInteger();

        try (var queue = new NextGenQueue(dataSource, QUEUE_ID, SHARD_COUNT, "sweep-" + parallelConsumers)) {
            queue.setParallelConsumers(parallelConsumers);
            queue.startConsuming(ignored -> {
                try {
                    // Stands in for a handler that waits on something, which is the only case where
                    // concurrency can help.
                    Thread.sleep(HANDLER_MILLIS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return;
                }
                handled.incrementAndGet();
            }, ShardOwnerSettings.defaults(), SHARD_COUNT);

            var before = PgSnapshot.capture(dataSource, List.of(NextGenSchema.UNORDERED_TABLE));
            var startNanos = System.nanoTime();
            for (var batch = 0; batch < MESSAGE_COUNT / 100; batch++) {
                var payloads = new ArrayList<byte[]>(100);
                for (var index = 0; index < 100; index++) {
                    payloads.add(payload);
                }
                queue.enqueue(payloads, 1);
            }
            Awaitility.await().atMost(Duration.ofSeconds(300))
                      .untilAsserted(() -> assertThat(handled.get()).isGreaterThanOrEqualTo(MESSAGE_COUNT));
            var elapsedMillis = (System.nanoTime() - startNanos) / 1_000_000L;
            var after = PgSnapshot.capture(dataSource, List.of(NextGenSchema.UNORDERED_TABLE));

            var extra = new LinkedHashMap<String, Object>();
            extra.put("parallelConsumers", parallelConsumers);
            extra.put("handled", handled.get());
            return new RunResult("nextgen-concurrency", String.valueOf(parallelConsumers), repetition,
                                 Instant.now(), elapsedMillis, MESSAGE_COUNT,
                                 MESSAGE_COUNT / (elapsedMillis / 1000.0),
                                 Map.of("shards", SHARD_COUNT), List.of(),
                                 after.deltaFrom(before), Map.of(), environment, extra);
        }
    }
}
