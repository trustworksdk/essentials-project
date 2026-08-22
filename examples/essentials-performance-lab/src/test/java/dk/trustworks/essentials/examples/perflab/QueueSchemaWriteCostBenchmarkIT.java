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
 * Runs the schema write-cost comparison that gates the DurableQueues v2 decision — does splitting ordered and
 * unordered messages into per-mode tables actually reduce write cost, and by how much.
 * <p>
 * Opt-in via {@code -Dbenchmark.run=true}. Deliberately runs at 200k rows by default rather than the 4k used
 * by earlier queue measurements: index maintenance is invisible at small scale, which is exactly why this
 * question was still open.
 * <pre>{@code
 * JAVA_HOME=... mvn verify -pl examples/essentials-performance-lab \
 *   -Dbenchmark.run=true -Dit.test=QueueSchemaWriteCostBenchmarkIT \
 *   -Dqs.messages=200000 -Dqs.repetitions=3
 * }</pre>
 */
@Testcontainers(disabledWithoutDocker = true)
@EnabledIfSystemProperty(named = "benchmark.run", matches = "true")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE,
        properties = {
                "essentials.lab.scenario=queue-schema-write-cost",
                "essentials.lab.schema-write-cost-messages=${qs.messages:200000}",
                "essentials.lab.schema-write-cost-claim-batch-size=${qs.claimBatchSize:500}",
                "essentials.lab.schema-write-cost-repetitions=${qs.repetitions:3}",
                "essentials.lab.schema-write-cost-ordered-key-count=${qs.orderedKeyCount:1000}",
                "essentials.eventstore.cdc.enabled=false",
                "essentials.lab.metrics-output-file=target/perf-lab-benchmark/queue-schema-write-cost.json"
        })
class QueueSchemaWriteCostBenchmarkIT {

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
    void every_arm_writes_claims_and_acknowledges_the_whole_workload() throws Exception {
        var output = Path.of("target/perf-lab-benchmark/queue-schema-write-cost.json");
        assertThat(output).exists();

        var json  = objectMapper.readTree(Files.readString(output));
        var cases = json.get("cases");
        assertThat(cases).isNotEmpty();

        for (var singleCase : cases) {
            var caseId = singleCase.get("caseId").asText();
            // A case that failed to claim everything it inserted has left rows behind, and its phase timings
            // then describe a different amount of work than its peers - which would silently corrupt the
            // comparison the whole benchmark exists to produce.
            assertThat(singleCase.get("messagesClaimed").asInt())
                    .as("case %s must claim every row it inserted", caseId)
                    .isEqualTo(singleCase.get("messagesInserted").asInt());
            assertThat(singleCase.get("insertMillis").asLong()).as("case %s insert timing", caseId).isPositive();
            assertThat(singleCase.get("claimMillis").asLong()).as("case %s claim timing", caseId).isPositive();
        }

        // The arms must actually differ in index count, otherwise the schemas were not built as intended and
        // any difference in timing is measuring something else.
        var splitUnordered = findCase(cases, "V2_SPLIT/UNORDERED");
        var sharedUnordered = findCase(cases, "V1_SHARED/UNORDERED");
        assertThat(splitUnordered.get("secondaryIndexCount").asInt()).isEqualTo(1);
        assertThat(sharedUnordered.get("secondaryIndexCount").asInt()).isEqualTo(6);
    }

    private static com.fasterxml.jackson.databind.JsonNode findCase(com.fasterxml.jackson.databind.JsonNode cases, String caseId) {
        for (var singleCase : cases) {
            if (caseId.equals(singleCase.get("caseId").asText())) {
                return singleCase;
            }
        }
        throw new AssertionError("No case with id '" + caseId + "'");
    }
}
