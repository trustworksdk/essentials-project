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
 * Smoke test for the {@code queue-design-ab} scenario. Its real job is not the throughput numbers — at this
 * size they are noise — but the correctness of the batched-acknowledgement prototype, which is the part that
 * could silently invalidate the whole measurement.
 * <p>
 * Specifically it pins that the BATCHED arm actually batches (fewer flushes than messages), that it deletes
 * every row it acknowledged (nothing left behind, so no message is quietly counted as handled while still
 * sitting in the table), and that both arms handle every message at every ordered fraction — including
 * {@code 1.0}, where the ordered per-key barrier serialises delivery per key and is the most likely place
 * for a harness bug to strand a message.
 */
@Testcontainers(disabledWithoutDocker = true)
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE,
        properties = {
                "essentials.lab.scenario=queue-design-ab",
                // 0.5 is deliberately included: a mixed backlog is the case that exposed
                // PostgresqlDurableQueues.queueMessages' inability to batch ordered and unordered messages
                // together, which the scenario works around by enqueueing in homogeneous runs.
                "essentials.lab.queue-design-ordered-fractions=0.0,0.5,1.0",
                "essentials.lab.queue-design-parallel-consumers=8",
                "essentials.lab.queue-design-messages-per-case=200",
                "essentials.lab.queue-design-ordered-key-count=16",
                "essentials.lab.queue-design-ack-flush-interval=25ms",
                "essentials.lab.queue-design-ack-max-batch-size=50",
                "essentials.lab.queue-design-use-ordered-unordered-query-label=true",
                "essentials.eventstore.cdc.enabled=false",
                "essentials.lab.metrics-output-file=target/perf-lab-smoke/queue-design-ab.json"
        })
class QueueDesignAbScenarioSmokeIT {

    // Deliberately NOT annotated @Container — see BackpressureScenarioSmokeIT.
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
    void queue_design_scenario_drains_both_ack_modes_at_every_ordered_fraction() throws Exception {
        var output = Path.of("target/perf-lab-smoke/queue-design-ab.json");
        assertThat(output).exists();

        var json = objectMapper.readTree(Files.readString(output));
        assertThat(json.get("scenario").asText()).isEqualTo("queue-design-ab");
        assertThat(json.get("useOrderedUnorderedQuery").asText()).isEqualTo("true");

        // 3 ordered fractions x 1 repetition x 2 ack modes.
        var cases = json.get("cases");
        assertThat(cases).hasSize(6);

        for (var singleCase : cases) {
            var caseId = singleCase.get("caseId").asText();
            assertThat(singleCase.get("drainedWithinTimeout").asBoolean()).as("case %s drained", caseId).isTrue();
            assertThat(singleCase.get("messagesHandled").asInt()).as("case %s handled everything", caseId).isEqualTo(200);
            assertThat(singleCase.get("handlerFailures").asInt()).as("case %s had no handler failures", caseId).isZero();
            // The decisive check on the batching prototype: whatever it reported as acknowledged is really
            // gone from the table by the time the case ends.
            assertThat(singleCase.get("rowsLeftInQueue").asLong()).as("case %s left no rows behind", caseId).isZero();
        }

        // The ordered fraction actually produced ordered messages, otherwise the 1.0 arm is measuring the
        // unordered path and the comparison is meaningless.
        var orderedCase = findCase(cases, "ordered100%/IMMEDIATE");
        assertThat(orderedCase.get("orderedMessages").asInt()).isEqualTo(200);
        var unorderedCase = findCase(cases, "ordered0%/IMMEDIATE");
        assertThat(unorderedCase.get("orderedMessages").asInt()).isZero();

        // The BATCHED arm really batched rather than degenerating into one flush per message.
        var batchedCase = findCase(cases, "ordered0%/BATCHED");
        assertThat(batchedCase.get("ackFlushedMessages").asLong()).isPositive();
        assertThat(batchedCase.get("ackFlushCount").asLong())
                .as("BATCHED must issue materially fewer deletes than there are messages")
                .isLessThan(batchedCase.get("ackFlushedMessages").asLong());

        // The mixed backlog really is mixed — the workaround must not have quietly dropped one kind.
        var mixedCase = findCase(cases, "ordered50%/IMMEDIATE");
        assertThat(mixedCase.get("orderedMessages").asInt()).isBetween(1, 199);

        var comparisons = json.get("comparisons");
        assertThat(comparisons).hasSize(3);
        for (var comparison : comparisons) {
            assertThat(comparison.get("immediateAckThroughputMedianMsgPerSecond").asDouble()).isPositive();
            assertThat(comparison.get("batchedAckThroughputMedianMsgPerSecond").asDouble()).isPositive();
            // Single repetition here, so there is no spread to judge the difference against.
            assertThat(comparison.get("speedupWithinNoise").isNull()).isTrue();
        }
    }

    private static com.fasterxml.jackson.databind.JsonNode findCase(com.fasterxml.jackson.databind.JsonNode cases, String caseId) {
        for (var singleCase : cases) {
            if (caseId.equals(singleCase.get("caseId").asText())) {
                return singleCase;
            }
        }
        throw new AssertionError("No case with id '" + caseId + "' in " + cases);
    }
}
