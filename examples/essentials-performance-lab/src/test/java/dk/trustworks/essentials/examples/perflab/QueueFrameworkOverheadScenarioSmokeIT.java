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
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.*;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.nio.file.*;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Smoke test for the {@code queue-framework-overhead} scenario. It asserts nothing about which arm is faster
 * — at this size the timings are noise and a performance assertion would be a flake generator — but it does
 * assert the two things that would silently invalidate the measurement if they were wrong.
 * <p>
 * <strong>Every arm must drain everything it enqueued.</strong> An arm that strands messages has measured a
 * different amount of work than its peers, and the ratios the scenario exists to produce would then be
 * comparing unlike quantities. The component arms are the plausible failure here: they drain through
 * {@code getNextMessageReadyForDelivery}, which returns empty both when the queue is exhausted and when a
 * row is merely not yet due.
 * <p>
 * <strong>The arms must actually differ in transaction granularity.</strong> That is the entire independent
 * variable. {@code unitsOfWorkPerMessage} is the direct evidence: the batched baseline must sit well below
 * one transaction per message, while the per-message arms must sit at or above one. If those collapse together, the
 * arms are not measuring what their names claim and every derived ratio is meaningless — so this is checked
 * on the shape of the data rather than assumed from the code.
 */
@Testcontainers(disabledWithoutDocker = true)
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE,
        properties = {
                "essentials.lab.scenario=queue-framework-overhead",
                "essentials.lab.framework-overhead-messages=400",
                "essentials.lab.framework-overhead-claim-batch-size=50",
                "essentials.lab.framework-overhead-repetitions=1",
                "essentials.eventstore.cdc.enabled=false",
                "essentials.lab.metrics-output-file=target/perf-lab-smoke/queue-framework-overhead.json"
        })
class QueueFrameworkOverheadScenarioSmokeIT {

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
    void every_arm_drains_its_whole_workload_and_the_arms_differ_in_transaction_granularity() throws Exception {
        var output = Path.of("target/perf-lab-smoke/queue-framework-overhead.json");
        assertThat(output).exists();

        var json  = objectMapper.readTree(Files.readString(output));
        var cases = json.get("cases");
        assertThat(cases).isNotEmpty();

        for (var singleCase : cases) {
            var arm = singleCase.get("arm").asText();
            assertThat(singleCase.get("messagesDrained").asInt())
                    .as("arm %s must drain every message it enqueued", arm)
                    .isEqualTo(singleCase.get("messagesInserted").asInt());
        }

        // All five arms present, and the warmup cases excluded from the reported set.
        assertThat(cases).hasSize(5);
        for (var singleCase : cases) {
            assertThat(singleCase.get("warmup").asBoolean()).isFalse();
        }

        var batched = findCase(cases, "RAW_BATCHED");
        var single  = findCase(cases, "RAW_SINGLE");
        assertThat(batched.get("unitsOfWorkPerMessage").asDouble())
                .as("the batched baseline amortises transactions across a claim batch")
                .isLessThan(1.0d);
        assertThat(single.get("unitsOfWorkPerMessage").asDouble())
                .as("the per-message arm pays a claim and an acknowledge transaction per message")
                .isGreaterThanOrEqualTo(1.0d);

        // The decomposition is the deliverable; an empty one means no arm pair could be reduced to a median.
        assertThat(json.get("decomposition")).isNotEmpty();
        for (var ratio : json.get("decomposition")) {
            assertThat(ratio.get("drainCostMultiple").isNull())
                    .as("ratio %s must be computable", ratio.get("ratio").asText())
                    .isFalse();
        }
    }

    private static JsonNode findCase(JsonNode cases, String arm) {
        for (var singleCase : cases) {
            if (arm.equals(singleCase.get("arm").asText())) {
                return singleCase;
            }
        }
        throw new AssertionError("No case for arm '" + arm + "'");
    }
}
