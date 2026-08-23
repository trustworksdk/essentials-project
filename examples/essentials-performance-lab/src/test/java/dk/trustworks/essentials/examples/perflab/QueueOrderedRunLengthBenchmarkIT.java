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
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.*;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.nio.file.*;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The gate on the per-key cursor: sweeps ordered key cardinality against cursor run length and reports database
 * round trips per message.
 * <pre>{@code
 * JAVA_HOME=... mvn verify -pl examples/essentials-performance-lab \
 *   -Dbenchmark.run=true -Dit.test=QueueOrderedRunLengthBenchmarkIT \
 *   -Drl.keyCounts=8,64,500,2000 -Drl.runLengths=1,4,16,64
 * }</pre>
 * Read {@code roundTripReduction} in the emitted JSON, not the wall clock: §7 of the measurements established
 * that the transaction is the per-message cost, and at this scale wall clock is dominated by autovacuum timing.
 */
@Testcontainers(disabledWithoutDocker = true)
@EnabledIfSystemProperty(named = "benchmark.run", matches = "true")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE,
        properties = {
                "essentials.lab.scenario=queue-ordered-run-length",
                "essentials.lab.run-length-messages=${rl.messages:50000}",
                "essentials.lab.run-length-claim-batch-size=${rl.claimBatchSize:500}",
                "essentials.lab.run-length-repetitions=${rl.repetitions:2}",
                "essentials.lab.run-length-key-counts=${rl.keyCounts:8,64,500,2000}",
                "essentials.lab.run-length-run-lengths=${rl.runLengths:1,4,16,64}",
                "essentials.eventstore.cdc.enabled=false",
                "essentials.lab.metrics-output-file=target/perf-lab-benchmark/queue-ordered-run-length.json"
        })
class QueueOrderedRunLengthBenchmarkIT {

    // Deliberately NOT annotated @Container — see BackpressureScenarioSmokeIT.
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:17.5-bookworm")
            .withDatabaseName("essentials_lab")
            .withUsername("essentials")
            .withPassword("essentials");

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
    void every_case_drains_its_backlog_and_the_comparison_is_produced() throws Exception {
        var output = Path.of("target/perf-lab-benchmark/queue-ordered-run-length.json");
        assertThat(output).exists();

        var json = objectMapper.readTree(Files.readString(output));
        assertThat(json.get("cases")).isNotEmpty();
        for (var singleCase : json.get("cases")) {
            // A case that stranded messages timed a different workload and its round count is not comparable.
            assertThat(singleCase.get("messagesClaimed").asInt())
                    .as("arm %s at %d keys, run length %d must claim every message it inserted",
                        singleCase.get("arm").asText(), singleCase.get("keyCount").asInt(), singleCase.get("runLength").asInt())
                    .isEqualTo(singleCase.get("messagesInserted").asInt());
            assertThat(singleCase.get("rounds").asLong()).isPositive();
        }
        assertThat(json.get("comparisons")).isNotEmpty();
    }
}
