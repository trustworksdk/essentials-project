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
import dk.trustworks.essentials.examples.perflab.scenario.SequenceGapScenario;
import org.junit.jupiter.api.*;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.*;

import java.time.Duration;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Smoke test for the {@code seq-gap} scenario — proves the harness runs end to end and, more
 * importantly, that its correctness invariant is actually wired up rather than merely reported.
 * <p>
 * The scenario is run directly instead of through a Spring context: it needs nothing but a
 * {@link javax.sql.DataSource}, and skipping the context keeps this test fast enough to stay
 * ungated in the normal build.
 * <p>
 * Durations here are deliberately tiny. This test answers "does the measurement work", not "what
 * are the numbers" — real figures come from running the scenario against the compose stack, where
 * the environment is controlled.
 */
@Testcontainers(disabledWithoutDocker = true)
class SequenceGapScenarioSmokeIT {

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
        // One connection per producer and per shard reader, plus headroom for the snapshot queries.
        config.setMaximumPoolSize(20);
        dataSource = new HikariDataSource(config);
    }

    @AfterEach
    void tearDown() {
        if (dataSource != null) {
            dataSource.close();
        }
    }

    @Test
    void seq_gap_scenario_reports_hole_statistics_and_loses_no_messages() throws Exception {
        var properties = new EssentialsPerformanceLabProperties();
        properties.setWarmup(Duration.ZERO);
        properties.setDuration(Duration.ofSeconds(2));
        properties.setProducerThreads(4);
        properties.setMetricsOutputFile("target/perf-lab-smoke/seq-gap.json");

        var seqGap = properties.getSeqGap();
        seqGap.setShards(2);
        seqGap.setRepetitions(3);
        // Both arms: plain autocommit enqueue, and an enqueue holding its transaction open. The
        // second is what makes holes appear at all, so a run where it produces none would mean the
        // detection is broken rather than that the design is safe.
        seqGap.setTxHoldMillis(List.of(0L, 5L));
        seqGap.setDrainTimeout(Duration.ofSeconds(15));
        seqGap.setGapExpiry(Duration.ofSeconds(5));

        new SequenceGapScenario(dataSource).run(properties);

        var output = java.nio.file.Path.of("target/perf-lab-smoke/seq-gap.json");
        assertThat(output).exists();

        var json = new tools.jackson.databind.ObjectMapper().readTree(java.nio.file.Files.readString(output));
        assertThat(json.get("scenario").asString()).isEqualTo("seq-gap");
        assertThat(json.get("environment").get("pg.synchronous_commit")).isNotNull();

        var runs = json.get("runs");
        assertThat(runs).hasSize(6); // 2 arms x 3 repetitions

        runs.forEach(run -> {
            var extra = run.get("extra");
            assertThat(extra.get("messagesProduced").asLong())
                    .as("run should actually produce something")
                    .isPositive();
            assertThat(extra.get("invariantNoMessageLost").asBoolean())
                    .as("every committed row must reach the reader — this is the assumption the whole design rests on")
                    .isTrue();
            assertThat(extra.get("messagesLost").asLong()).isZero();
            // Delivery is deduplicated by the harness, so delivered can never exceed what the
            // producers actually committed.
            assertThat(extra.get("messagesDelivered").asLong())
                    .isLessThanOrEqualTo(extra.get("messagesProduced").asLong());
        });

        var summaries = json.get("summaries");
        assertThat(summaries).hasSize(2);
        summaries.forEach(summary -> {
            assertThat(summary.get("repetitions").asInt()).isEqualTo(3);
            assertThat(summary.get("throughputPerSecond").get("median").asDouble()).isPositive();
            // The IQR must be present, not just the median — a median quoted without its spread is
            // the failure mode this harness exists to prevent.
            assertThat(summary.get("throughputPerSecond").has("q1")).isTrue();
            assertThat(summary.get("throughputPerSecond").has("q3")).isTrue();
        });
    }
}
