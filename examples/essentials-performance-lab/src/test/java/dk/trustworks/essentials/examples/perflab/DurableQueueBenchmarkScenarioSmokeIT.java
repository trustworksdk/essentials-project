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

import tools.jackson.databind.ObjectMapper;
import com.zaxxer.hikari.*;
import dk.trustworks.essentials.examples.perflab.scenario.DurableQueueBenchmarkScenario;
import org.junit.jupiter.api.*;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.*;

import java.nio.file.*;
import java.time.Duration;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Smoke test for the {@code durable-queues} baseline scenario: proves the harness can drive the
 * existing {@code PostgresqlDurableQueues} through both fetcher topologies and produce a comparable
 * result for each.
 * <p>
 * Deliberately short. This checks that the measurement works, not what the numbers are — a baseline
 * worth quoting comes from running the scenario against the compose stack with a controlled
 * environment and a duration long enough to gather a stable tail.
 */
@Testcontainers(disabledWithoutDocker = true)
class DurableQueueBenchmarkScenarioSmokeIT {

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:17.5-bookworm")
            .withDatabaseName("essentials_lab")
            .withUsername("essentials")
            .withPassword("essentials");

    private HikariDataSource dataSource;

    @BeforeEach
    void setUp() {
        var config = new HikariConfig();
        config.setJdbcUrl(postgres.getJdbcUrl());
        config.setUsername(postgres.getUsername());
        config.setPassword(postgres.getPassword());
        config.setMaximumPoolSize(30);
        dataSource = new HikariDataSource(config);
    }

    @AfterEach
    void tearDown() {
        if (dataSource != null) {
            dataSource.close();
        }
    }

    @Test
    void durable_queue_baseline_measures_both_fetcher_topologies() throws Exception {
        var properties = new EssentialsPerformanceLabProperties();
        properties.setWarmup(Duration.ZERO);
        properties.setDuration(Duration.ofSeconds(3));
        properties.setProducerThreads(2);
        properties.setQueueCount(1);
        properties.setMetricsOutputFile("target/perf-lab-smoke/durable-queues.json");

        var settings = properties.getQueueBenchmark();
        settings.setArms(List.of("centralized", "traditional"));
        settings.setRepetitions(3);
        // The existing implementation fetches at most one message per available worker slot per
        // poll, so consumption is bounded by parallelConsumers / pollingInterval. Three consumers
        // caps the whole run at 150 messages a second, which measures the configuration rather than
        // the implementation.
        settings.setParallelConsumers(20);
        settings.setBusyQueues(1);
        settings.setMaxInFlight(2_000);
        settings.setDrainTimeout(Duration.ofSeconds(30));

        new DurableQueueBenchmarkScenario(dataSource).run(properties);

        var output = Path.of("target/perf-lab-smoke/durable-queues.json");
        assertThat(output).exists();

        var json = new ObjectMapper().readTree(Files.readString(output));
        assertThat(json.get("scenario").asString()).isEqualTo("durable-queues");
        assertThat(json.get("workload").asString()).isEqualTo("UNORDERED");

        var runs = json.get("runs");
        assertThat(runs).hasSize(6); // 2 arms x 3 repetitions

        runs.forEach(run -> {
            var extra = run.get("extra");
            assertThat(extra.get("messagesQueued").asLong()).isPositive();
            assertThat(extra.get("messagesHandled").asLong()).isPositive();
            // The drain loop exists so throughput is not flattered by counting enqueues nobody
            // handled; if it works, almost nothing is left behind.
            assertThat(extra.get("messagesStillQueuedAtEnd").asLong())
                    .as("drain should leave the queue essentially empty")
                    .isLessThan(extra.get("messagesQueued").asLong());
            assertThat(extra.get("deadLetterMessages").asLong())
                    .as("no failures were injected, so nothing should be dead-lettered")
                    .isZero();
            // This scenario acknowledges and deletes, unlike seq-gap, so it must actually observe
            // the tuple churn that acknowledgement produces.
            assertThat(run.get("dbDelta").has("table.perflab_durable_queues.n_dead_tup")).isTrue();
            assertThat(run.get("walBytesPerOperation").asDouble()).isPositive();
            // Derived from n_tup_upd + n_tup_del, which are counters. The earlier version of this
            // metric differenced n_dead_tup, a gauge, and reported negative dead tuples per message.
            assertThat(extra.get("deadTuplesCreatedPerMessage").asDouble())
                    .as("tuple churn per message is a counter-derived figure and can never be negative")
                    .isNotNegative();
            // Bounded in-flight work should keep every run in a steady state. A saturated run's
            // latency figures measure backlog depth and must not be quoted as delivery latency.
            assertThat(extra.get("saturated").asBoolean())
                    .as("backpressure should keep producers from outrunning consumers")
                    .isFalse();
            // This profile runs unthrottled, so it measures throughput. The harness must say so
            // rather than let its latency numbers — which Little's Law fixes at depth/throughput —
            // be read as delivery latency.
            assertThat(extra.get("latencyMeaningful").asBoolean())
                    .as("an unthrottled run is a throughput measurement, and must not claim otherwise")
                    .isFalse();
        });

        var summaries = json.get("summaries");
        assertThat(summaries).hasSize(2);
        summaries.forEach(summary -> {
            assertThat(summary.get("throughputPerSecond").get("median").asDouble()).isPositive();
            assertThat(summary.get("responseTimeP99Micros").get("median").asDouble()).isPositive();
            assertThat(summary.get("walBytesPerOperation").has("interQuartileRange")).isTrue();
        });
    }

    /**
     * The latency profile: offer a rate well below capacity so that delivery latency is a property
     * of the implementation rather than of the standing queue depth. This is the run that produces
     * the baseline figure the design's latency targets are measured against.
     */
    @Test
    void durable_queue_baseline_measures_delivery_latency_when_offered_rate_is_below_capacity() throws Exception {
        var properties = new EssentialsPerformanceLabProperties();
        properties.setWarmup(Duration.ZERO);
        properties.setDuration(Duration.ofSeconds(6));
        properties.setProducerThreads(2);
        properties.setQueueCount(1);
        // Well under the unthrottled capacity measured by the throughput profile above.
        properties.setProducerRateHz(100.0d);
        properties.setMetricsOutputFile("target/perf-lab-smoke/durable-queues-latency.json");

        var settings = properties.getQueueBenchmark();
        settings.setArms(List.of("centralized"));
        settings.setRepetitions(3);
        settings.setParallelConsumers(20);
        settings.setBusyQueues(1);
        settings.setMaxInFlight(100_000);
        settings.setDrainTimeout(Duration.ofSeconds(20));

        new DurableQueueBenchmarkScenario(dataSource).run(properties);

        var json = new ObjectMapper().readTree(Files.readString(Path.of("target/perf-lab-smoke/durable-queues-latency.json")));
        json.get("runs").forEach(run -> {
            var extra = run.get("extra");
            assertThat(extra.get("latencyMeaningful").asBoolean())
                    .as("an offered rate below capacity should never engage backpressure")
                    .isTrue();
            assertThat(extra.get("producerBackpressureWaits").asLong()).isZero();
        });

        var p50 = json.get("summaries").get(0).get("responseTimeP50Micros").get("median").asDouble();
        // With a 20ms polling interval, mean wait for a poll is ~10ms; anything near a second would
        // mean the run was still capacity-bound and the profile is not doing what it claims.
        assertThat(p50)
                .as("delivery latency at a rate below capacity should be milliseconds, not seconds")
                .isLessThan(500_000.0d);
    }
}
