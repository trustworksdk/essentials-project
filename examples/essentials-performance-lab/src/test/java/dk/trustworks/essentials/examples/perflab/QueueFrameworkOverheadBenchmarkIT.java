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
 * Produces the per-message framework-overhead decomposition that bounds every schema-level result in the
 * queue investigation — the number that says how much of the cursor prototype's 2.64x and the table split's
 * 1.38x can survive contact with the real component.
 * <p>
 * Opt-in via {@code -Dbenchmark.run=true}, like every measuring-only suite.
 * <pre>{@code
 * JAVA_HOME=... mvn verify -pl examples/essentials-performance-lab \
 *   -Dbenchmark.run=true -Dit.test=QueueFrameworkOverheadBenchmarkIT \
 *   -Dfo.messages=20000 -Dfo.repetitions=3
 * }</pre>
 * <p>
 * Read {@code prototypeUpperBoundDeflator} in the emitted JSON first: it is the ratio between the write-cost
 * prototype's transaction shape and the production component's, and therefore the factor by which the
 * prototype's published ratios must be deflated. {@code componentTransactionGranularity} then says how much
 * of that is transaction granularity — the part fixable by batching acknowledgements and widening the unit
 * of work — and {@code frameworkOverheadAtEqualGranularity} how much is everything else the component does
 * per message.
 * <p>
 * {@code unitsOfWorkPerMessage} carries the transaction-granularity evidence and is counted client-side, so
 * unlike a server-side commit counter it is unaffected by anything else running on the instance. The
 * wall-clock ratios are not, so run it on an otherwise idle machine.
 */
@Testcontainers(disabledWithoutDocker = true)
@EnabledIfSystemProperty(named = "benchmark.run", matches = "true")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE,
        properties = {
                "essentials.lab.scenario=queue-framework-overhead",
                "essentials.lab.framework-overhead-messages=${fo.messages:20000}",
                "essentials.lab.framework-overhead-claim-batch-size=${fo.claimBatchSize:500}",
                "essentials.lab.framework-overhead-repetitions=${fo.repetitions:3}",
                "essentials.eventstore.cdc.enabled=false",
                "essentials.lab.metrics-output-file=target/perf-lab-benchmark/queue-framework-overhead.json"
        })
class QueueFrameworkOverheadBenchmarkIT {

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
    void the_decomposition_is_produced_and_every_arm_measured_the_same_amount_of_work() throws Exception {
        var output = Path.of("target/perf-lab-benchmark/queue-framework-overhead.json");
        assertThat(output).exists();

        var json  = objectMapper.readTree(Files.readString(output));
        var cases = json.get("cases");
        assertThat(cases).isNotEmpty();

        for (var singleCase : cases) {
            var arm = singleCase.get("arm").asText();
            // An arm that stranded messages timed a different workload, which would corrupt every ratio.
            assertThat(singleCase.get("messagesDrained").asInt())
                    .as("arm %s must drain every message it enqueued", arm)
                    .isEqualTo(singleCase.get("messagesInserted").asInt());
            assertThat(singleCase.get("drainMillis").asLong()).as("arm %s drain timing", arm).isPositive();
        }

        // All five ratios computable - the decomposition is the deliverable, not the raw cases.
        var decomposition = json.get("decomposition");
        assertThat(decomposition).hasSize(5);
        for (var ratio : decomposition) {
            assertThat(ratio.get("drainCostMultiple").isNull())
                    .as("ratio %s must be computable", ratio.get("ratio").asText())
                    .isFalse();
        }
    }
}
