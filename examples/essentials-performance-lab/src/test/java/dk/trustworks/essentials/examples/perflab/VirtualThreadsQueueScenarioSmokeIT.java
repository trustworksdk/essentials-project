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

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.*;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.nio.file.*;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Smoke test for the {@code virtual-threads-queue} scenario — a deliberately tiny sweep that proves the
 * harness itself works: every case drains, the platform and virtual arms are both exercised for both
 * handler shapes, and the JSON carries the per-case and paired-comparison structure the analysis reads.
 * <p>
 * It asserts nothing about which arm is <em>faster</em>. At this size the numbers are noise, and a
 * performance assertion in a build-time test would be a flake generator. The actual measurement runs come
 * from invoking the scenario directly with a real sweep — see the module README.
 * <p>
 * CDC is disabled so the plain {@code postgres} image suffices (no wal2json needed); the scenario only
 * exercises {@code DurableQueues}.
 */
@Testcontainers(disabledWithoutDocker = true)
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE,
        properties = {
                "essentials.lab.scenario=virtual-threads-queue",
                "essentials.lab.virtual-threads-parallel-consumers=2,8",
                "essentials.lab.virtual-threads-messages-per-case=40",
                "essentials.lab.virtual-threads-handler-delay=20ms",
                "essentials.lab.virtual-threads-handler-mode=BOTH",
                "essentials.eventstore.cdc.enabled=false",
                "essentials.lab.metrics-output-file=target/perf-lab-smoke/virtual-threads-queue.json"
        })
class VirtualThreadsQueueScenarioSmokeIT {

    // Deliberately NOT annotated @Container — see BackpressureScenarioSmokeIT for why: the Spring context
    // outlives JUnit's AfterAllCallback, so a JUnit-managed container is torn down while the framework is
    // still shutting down against it.
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:17.5-bookworm")
            .withDatabaseName("essentials_lab")
            .withUsername("essentials")
            .withPassword("essentials")
            .withCommand("postgres",
                         "-c", "wal_level=logical",
                         "-c", "max_replication_slots=10",
                         "-c", "max_wal_senders=10");

    @Autowired
    ObjectMapper objectMapper;

    @DynamicPropertySource
    static void registerProperties(DynamicPropertyRegistry registry) {
        postgres.start();
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
    }

    @Test
    void virtual_threads_queue_scenario_drains_every_case_and_emits_paired_comparisons() throws Exception {
        var output = Path.of("target/perf-lab-smoke/virtual-threads-queue.json");
        assertThat(output).exists();

        var json = objectMapper.readTree(Files.readString(output));
        assertThat(json.get("scenario").asText()).isEqualTo("virtual-threads-queue");
        assertThat(json.get("handlerDelayMs").asLong()).isEqualTo(20L);

        // 2 handler modes x 2 parallelism values x 2 executor kinds.
        var cases = json.get("cases");
        assertThat(cases).hasSize(8);

        for (var singleCase : cases) {
            var caseId = singleCase.get("caseId").asText();
            assertThat(singleCase.get("drainedWithinTimeout").asBoolean()).as("case %s drained", caseId).isTrue();
            // Not the configured 40 for every case: the scenario scales the burst up to ~8 messages per
            // parallel-consumer slot so high-parallelism cases get a steady state rather than pure ramp-up.
            assertThat(singleCase.get("messagesQueued").asInt()).as("case %s queued a burst", caseId).isGreaterThanOrEqualTo(40);
            assertThat(singleCase.get("messagesHandled").asInt()).as("case %s handled all messages", caseId)
                                                                 .isEqualTo(singleCase.get("messagesQueued").asInt());
            assertThat(singleCase.get("handlerFailures").asInt()).as("case %s had no handler failures", caseId).isZero();
            assertThat(singleCase.get("throughputMsgPerSecond").asDouble()).as("case %s throughput", caseId).isPositive();
        }

        // Both executor kinds and both handler shapes are actually exercised — a silent fallback to one arm
        // would make every later comparison meaningless.
        var caseIds = cases.findValuesAsText("caseId");
        assertThat(caseIds).anyMatch(id -> id.startsWith("SLEEP/PLATFORM"))
                           .anyMatch(id -> id.startsWith("SLEEP/VIRTUAL"))
                           .anyMatch(id -> id.startsWith("DB/PLATFORM"))
                           .anyMatch(id -> id.startsWith("DB/VIRTUAL"));

        // One comparison per (handlerMode, parallelConsumers) pair, each with both arms filled in.
        var comparisons = json.get("comparisons");
        assertThat(comparisons).hasSize(4);
        for (var comparison : comparisons) {
            assertThat(comparison.get("platformThroughputMedianMsgPerSecond").asDouble()).isPositive();
            assertThat(comparison.get("virtualThroughputMedianMsgPerSecond").asDouble()).isPositive();
            assertThat(comparison.get("virtualThroughputSpeedup").asDouble()).isPositive();
            // The key is always emitted, but at the single repetition this smoke run uses there is no
            // spread to judge against, so the value must be null rather than a verdict.
            assertThat(comparison.has("speedupWithinNoise")).isTrue();
            assertThat(comparison.get("speedupWithinNoise").isNull()).isTrue();
        }
    }
}
