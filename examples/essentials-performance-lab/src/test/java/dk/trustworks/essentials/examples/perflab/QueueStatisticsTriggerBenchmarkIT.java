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
 * What the delivery-statistics {@code AFTER DELETE} trigger costs, with the {@code EXCEPTION WHEN OTHERS}
 * block's subtransaction cost isolated, against the Java-side observer the improvements document proposes.
 * <pre>{@code
 * JAVA_HOME=... mvn verify -pl examples/essentials-performance-lab \
 *   -Dbenchmark.run=true -Dit.test=QueueStatisticsTriggerBenchmarkIT -Dst.messages=50000
 * }</pre>
 * Read {@code EXCEPTION_BLOCK_ISOLATED} in the comparisons: the difference between the trigger with and without
 * its exception block is the per-row subtransaction, and {@code pg_stat_slru} corroborates it directly rather
 * than by inference from wall clock.
 */
@Testcontainers(disabledWithoutDocker = true)
@EnabledIfSystemProperty(named = "benchmark.run", matches = "true")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE,
        properties = {
                "essentials.lab.scenario=queue-statistics-trigger",
                "essentials.lab.statistics-trigger-messages=${st.messages:50000}",
                "essentials.lab.statistics-trigger-repetitions=${st.repetitions:3}",
                "essentials.eventstore.cdc.enabled=false",
                "essentials.lab.metrics-output-file=target/perf-lab-benchmark/queue-statistics-trigger.json"
        })
class QueueStatisticsTriggerBenchmarkIT {

    // Deliberately NOT annotated @Container — see BackpressureScenarioSmokeIT.
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:17.5-bookworm")
            .withDatabaseName("essentials_lab")
            .withUsername("essentials")
            .withPassword("essentials")
            .withCommand("postgres", "-c", "log_min_messages=warning");

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
    void every_arm_records_the_statistics_it_should_and_the_comparison_is_produced() throws Exception {
        var output = Path.of("target/perf-lab-benchmark/queue-statistics-trigger.json");
        assertThat(output).exists();

        var json = objectMapper.readTree(Files.readString(output));
        assertThat(json.get("cases")).isNotEmpty();
        for (var singleCase : json.get("cases")) {
            var arm      = singleCase.get("arm").asText();
            var expected = "NO_STATISTICS".equals(arm) ? 0 : singleCase.get("messagesInserted").asInt();
            // An arm that silently recorded nothing would look fast for the wrong reason - the trigger's own
            // EXCEPTION WHEN OTHERS makes exactly that failure mode invisible.
            assertThat(singleCase.get("statisticsRows").asLong())
                    .as("arm %s must record one statistics row per acknowledged message", arm)
                    .isEqualTo(expected);
        }
        assertThat(json.get("comparisons")).isNotEmpty();
    }
}
