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

import com.fasterxml.jackson.databind.*;
import com.zaxxer.hikari.*;
import dk.trustworks.essentials.examples.perflab.EssentialsPerformanceLabProperties.QueueBenchmark.Workload;
import dk.trustworks.essentials.examples.perflab.scenario.DurableQueueBenchmarkScenario;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.slf4j.*;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.*;

import java.nio.file.*;
import java.time.Duration;
import java.util.*;
import java.util.function.Consumer;

/**
 * Captures the baseline numbers for the workload profiles the design plan's later phases are gated
 * on. Measurement only — it asserts nothing about thresholds, because a suite that cannot fail on a
 * regression buys nothing per build. Opt in with {@code -Dbenchmark.run=true}.
 * <p>
 * Each profile is the same scenario under different configuration, and each answers a question a
 * later phase's gate depends on:
 * <ul>
 *     <li><b>Ordered by key cardinality</b> — Phase 4's gate is "ordered within 30% of unordered".
 *         Nobody currently knows what today's gap is.</li>
 *     <li><b>Injected failures</b> — Phase 5's gate is "10% failures cost under 10% throughput".</li>
 *     <li><b>Many idle queues</b> — the design claims idle queues should cost nothing; the baseline
 *         needs to show what they cost now.</li>
 *     <li><b>Soak</b> — whether p99 drifts as dead tuples accumulate. Shortened here; the real gate
 *         needs the full 30 minutes.</li>
 * </ul>
 */
@Testcontainers(disabledWithoutDocker = true)
@EnabledIfSystemProperty(named = "benchmark.run", matches = "true")
class DurableQueueBaselineProfilesIT {
    private static final Logger log = LoggerFactory.getLogger(DurableQueueBaselineProfilesIT.class);

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

    /**
     * Finds the operating point at which PostgreSQL, rather than the poll cadence, limits the
     * existing implementation.
     * <p>
     * Phase 0's headline finding was that throughput tracks {@code parallelConsumers × pollsPerSecond}
     * exactly, which means every cost comparison made below that ceiling is invisible and several of
     * the plan's gates cannot fail. Until the ceiling is lifted clear of the database, no throughput
     * gate discriminates.
     * <p>
     * The poll interval is the lever rather than the consumer count: each consumer needs a connection
     * to acknowledge on, so raising consumers runs into {@code max_connections} long before it runs
     * into anything interesting, whereas shortening the interval raises the ceiling for free.
     * <p>
     * {@code producerBackpressureWaits} tells us which side of the system bound each run. Backpressure
     * engaging means the consumers were the limit, which is what we want to measure. It never engaging
     * means the <em>producers</em> could not feed the consumers, and that run says nothing about
     * consumer capacity — which is why this sweep runs far more producer threads than the profiles do.
     */
    @Test
    void capacity_sweep_finds_where_postgresql_becomes_the_bottleneck() throws Exception {
        var pollIntervals = List.of(20L, 10L, 5L, 2L, 1L);
        var rows = new ArrayList<String>();
        rows.add(String.format("%-10s %10s %10s %9s %12s %10s %s",
                               "pollMs", "ceiling/s", "actual/s", "of ceil", "bpWaits", "walB/msg", "bound by"));

        for (var pollMillis : pollIntervals) {
            var properties = baseProperties("capacity-poll-" + pollMillis + "ms");
            properties.setDuration(Duration.ofSeconds(5));
            // Eight producers, because two cannot offer more than a few thousand messages a second
            // and would cap the sweep well below the consumer ceiling we are trying to find.
            properties.setProducerThreads(8);
            var settings = properties.getQueueBenchmark();
            settings.setPollingInterval(Duration.ofMillis(pollMillis));
            settings.setMaxInFlight(5_000);

            new DurableQueueBenchmarkScenario(dataSource).run(properties);

            var json = new ObjectMapper().readTree(Files.readString(Path.of(properties.getMetricsOutputFile())));
            var throughput = throughputMedian(json);
            var run = json.get("runs").get(0);
            var backpressureWaits = run.get("extra").get("producerBackpressureWaits").asLong();
            var ceiling = settings.getParallelConsumers() * 1000.0d / pollMillis;
            rows.add(String.format("%-10d %10.0f %10.1f %8.0f%% %12d %10.0f %s",
                                   pollMillis,
                                   ceiling,
                                   throughput,
                                   100.0d * throughput / ceiling,
                                   backpressureWaits,
                                   run.get("walBytesPerOperation").asDouble(),
                                   backpressureWaits == 0 ? "PRODUCERS (inconclusive)" : "consumers/database"));
        }

        log.info("");
        log.info("=========== CAPACITY SWEEP (parallelConsumers=20, 8 producers) ===========");
        rows.forEach(log::info);
        log.info("A run tracking its ceiling is poll-bound; one falling away from it has found a real limit.");
        log.info("==========================================================================");
    }

    @Test
    void capture_baseline_profiles() throws Exception {
        var profiles = new LinkedHashMap<String, Consumer<EssentialsPerformanceLabProperties>>();

        profiles.put("unordered", properties -> {
        });
        profiles.put("ordered-keys-10", properties -> {
            properties.getQueueBenchmark().setWorkload(Workload.ORDERED);
            properties.getQueueBenchmark().setKeyCardinality(10);
        });
        profiles.put("ordered-keys-1000", properties -> {
            properties.getQueueBenchmark().setWorkload(Workload.ORDERED);
            properties.getQueueBenchmark().setKeyCardinality(1_000);
        });
        profiles.put("failures-10pct", properties -> properties.getQueueBenchmark().setFailurePercent(10));
        // These two must be read as a pair. Throughput here is bounded by parallelConsumers, so a
        // low-parallelism run tells you nothing about idle-queue cost on its own — the first attempt
        // at this profile reported "10% of unordered" when all it had measured was its own consumer
        // count. The control holds every other variable fixed and varies only the number of idle
        // queues, which is the only way the difference means anything.
        profiles.put("idle-queues-control-1", properties -> {
            properties.setQueueCount(1);
            properties.getQueueBenchmark().setBusyQueues(1);
            properties.getQueueBenchmark().setParallelConsumers(2);
        });
        profiles.put("idle-queues-30", properties -> {
            properties.setQueueCount(30);
            properties.getQueueBenchmark().setBusyQueues(1);
            properties.getQueueBenchmark().setParallelConsumers(2);
        });
        profiles.put("soak-60s", properties -> properties.setDuration(Duration.ofSeconds(60)));

        var captured = new LinkedHashMap<String, JsonNode>();
        for (var profile : profiles.entrySet()) {
            log.info("=== profile '{}' ===", profile.getKey());
            var properties = baseProperties(profile.getKey());
            profile.getValue().accept(properties);
            new DurableQueueBenchmarkScenario(dataSource).run(properties);
            captured.put(profile.getKey(),
                         new ObjectMapper().readTree(Files.readString(Path.of(properties.getMetricsOutputFile()))));
        }

        report(captured);
    }

    private static EssentialsPerformanceLabProperties baseProperties(String profileName) {
        return baseProperties(profileName, 20L, 2);
    }

    /**
     * @param pollMillis      20 ms is the implementation's shipped default and gives the realistic
     *                        baseline; 2 ms is the saturation point found by the capacity sweep, and is
     *                        the only operating point at which a throughput comparison discriminates
     * @param producerThreads must be high enough to keep the consumers fed, or the run measures the
     *                        producers instead
     */
    private static EssentialsPerformanceLabProperties baseProperties(String profileName, long pollMillis, int producerThreads) {
        var properties = new EssentialsPerformanceLabProperties();
        // A real warmup, not zero: it is what populates the table so the post-warmup ANALYZE can
        // give the planner statistics describing the run that follows. With warmup disabled every
        // repetition starts from an empty table and the fetch query's plan is decided by autoanalyze
        // timing, which is what made these results bimodal.
        properties.setWarmup(Duration.ofSeconds(4));
        properties.setDuration(Duration.ofSeconds(6));
        properties.setProducerThreads(producerThreads);
        properties.setQueueCount(1);
        properties.setMetricsOutputFile("target/perf-lab-baseline/" + profileName + ".json");

        var settings = properties.getQueueBenchmark();
        // One topology only: the fetcher comparison is already captured by the smoke test, and
        // holding it fixed here keeps each profile's numbers comparable with the others.
        settings.setArms(List.of("centralized"));
        settings.setRepetitions(3);
        settings.setParallelConsumers(20);
        settings.setPollingInterval(Duration.ofMillis(pollMillis));
        settings.setMaxInFlight(5_000);
        settings.setDrainTimeout(Duration.ofSeconds(45));
        return properties;
    }

    /**
     * The same profiles, re-run at the saturation point the capacity sweep found (20 consumers, 2 ms
     * poll, ~9.9k messages a second, database-bound).
     * <p>
     * At the shipped 20 ms default every profile sits on the poll-cadence ceiling, so the cost of
     * ordering, of failures and of idle queues is hidden in the headroom below it — which is why three
     * of the plan's gates could not fail. These are the numbers those gates have to be written against.
     */
    @Test
    void capture_baseline_profiles_at_saturation() throws Exception {
        var profiles = new LinkedHashMap<String, Consumer<EssentialsPerformanceLabProperties>>();
        profiles.put("sat-unordered", properties -> {
        });
        profiles.put("sat-ordered-keys-10", properties -> {
            properties.getQueueBenchmark().setWorkload(Workload.ORDERED);
            properties.getQueueBenchmark().setKeyCardinality(10);
            // With ten keys at most ten messages can ever be in flight, so a 5 000-deep in-flight
            // bound lets producers build a backlog only ten keys can drain — and the head-of-key
            // query then scans thousands of blocked rows. That measures backlog pathology, not
            // ordered delivery. Same class of mistake as the idle-queue profile made: a bound has
            // to be proportional to the parallelism the workload can actually use.
            properties.getQueueBenchmark().setMaxInFlight(100);
        });
        profiles.put("sat-ordered-keys-1000", properties -> {
            properties.getQueueBenchmark().setWorkload(Workload.ORDERED);
            properties.getQueueBenchmark().setKeyCardinality(1_000);
        });
        profiles.put("sat-failures-10pct", properties -> properties.getQueueBenchmark().setFailurePercent(10));
        profiles.put("sat-idle-control-1", properties -> {
            properties.setQueueCount(1);
            properties.getQueueBenchmark().setBusyQueues(1);
        });
        profiles.put("sat-idle-30", properties -> {
            properties.setQueueCount(30);
            properties.getQueueBenchmark().setBusyQueues(1);
        });

        // At the saturated operating point a profile is only reproducible when it is the only thing
        // running: measured alone the 2 ms configuration has an interquartile range of 0.3%, and the
        // same configuration measured inside a back-to-back sequence has 239%. Pass
        // -Dperflab.profile=<name> to capture one at a time, which is how the saturated numbers have
        // to be gathered here.
        var only = System.getProperty("perflab.profile");
        if (only != null && !only.isBlank()) {
            profiles.keySet().retainAll(Set.of(only.split(",")));
        }

        var captured = new LinkedHashMap<String, JsonNode>();
        for (var profile : profiles.entrySet()) {
            log.info("=== saturated profile '{}' ===", profile.getKey());
            var properties = baseProperties(profile.getKey(), 2L, 8);
            profile.getValue().accept(properties);
            new DurableQueueBenchmarkScenario(dataSource).run(properties);
            captured.put(profile.getKey(),
                         new ObjectMapper().readTree(Files.readString(Path.of(properties.getMetricsOutputFile()))));
        }

        log.info("");
        log.info("===== SATURATED BASELINE (20 consumers, 2ms poll, database-bound) =====");
        log.info(String.format("%-24s %10s %9s %11s %10s %6s", "profile", "thr/s", "vs unord", "deadTup/msg", "walB/msg", "DLQ"));
        var unordered = captured.containsKey("sat-unordered") ? throughputMedian(captured.get("sat-unordered")) : 0.0d;
        captured.forEach((name, json) -> {
            var run = json.get("runs").get(0);
            log.info(String.format("%-24s %10.1f %8.0f%% %11.2f %10.0f %6d",
                                   name,
                                   throughputMedian(json),
                                   unordered == 0 ? 0 : 100.0d * throughputMedian(json) / unordered,
                                   run.get("extra").get("deadTuplesCreatedPerMessage").asDouble(),
                                   run.get("walBytesPerOperation").asDouble(),
                                   run.get("extra").get("deadLetterMessages").asLong()));
        });
        if (captured.containsKey("sat-idle-control-1") && captured.containsKey("sat-idle-30")) {
            var control = throughputMedian(captured.get("sat-idle-control-1"));
            var withIdle = throughputMedian(captured.get("sat-idle-30"));
            log.info(String.format("Idle-queue cost at saturation: %.1f/s -> %.1f/s = %.1f%% of control",
                                   control, withIdle, control == 0 ? 0 : 100.0d * withIdle / control));
        }
        log.info("=======================================================================");
    }

    private static void report(Map<String, JsonNode> captured) {
        var unorderedThroughput = throughputMedian(captured.get("unordered"));
        log.info("");
        log.info("=========== BASELINE PROFILE SUMMARY ===========");
        log.info(String.format("%-24s %10s %9s %11s %10s %6s %8s",
                               "profile", "thr/s", "vs unord", "deadTup/msg", "walB/msg", "DLQ", "drain_ms"));
        captured.forEach((name, json) -> {
            var throughput = throughputMedian(json);
            var run = json.get("runs").get(0);
            log.info(String.format("%-24s %10.1f %8.0f%% %11.2f %10.0f %6d %8d",
                                   name,
                                   throughput,
                                   unorderedThroughput == 0 ? 0 : 100.0d * throughput / unorderedThroughput,
                                   run.get("extra").get("deadTuplesCreatedPerMessage").asDouble(),
                                   run.get("walBytesPerOperation").asDouble(),
                                   run.get("extra").get("deadLetterMessages").asLong(),
                                   run.get("extra").get("drainMillis").asLong()));
        });
        log.info("------------------------------------------------");
        var control = throughputMedian(captured.get("idle-queues-control-1"));
        var withIdle = throughputMedian(captured.get("idle-queues-30"));
        log.info(String.format("Idle-queue cost: 1 queue %.1f/s vs 30 queues (29 idle) %.1f/s -> %.1f%% of control",
                               control, withIdle, control == 0 ? 0 : 100.0d * withIdle / control));
        log.info("================================================");
    }

    private static double throughputMedian(JsonNode json) {
        return json.get("summaries").get(0).get("throughputPerSecond").get("median").asDouble();
    }
}
