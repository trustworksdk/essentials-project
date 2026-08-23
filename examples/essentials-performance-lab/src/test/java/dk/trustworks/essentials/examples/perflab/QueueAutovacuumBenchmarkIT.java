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
 * Whether per-table autovacuum settings on the queue table flatten the degradation curve, and at which values.
 * <pre>{@code
 * JAVA_HOME=... mvn verify -pl examples/essentials-performance-lab \
 *   -Dbenchmark.run=true -Dit.test=QueueAutovacuumBenchmarkIT \
 *   -Dav.cycles=12 -Dav.naptime=60
 * }</pre>
 * {@code av.naptime} sets the cluster's {@code autovacuum_naptime}, which is deliberately a dimension: it
 * defaults to 60s, it is a cluster setting Essentials cannot control, and a queue can churn tens of thousands of
 * rows before the daemon even looks — in which regime a per-table threshold changes nothing. Run it at 60 and
 * again at something small to tell the two apart.
 */
@Testcontainers(disabledWithoutDocker = true)
@EnabledIfSystemProperty(named = "benchmark.run", matches = "true")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE,
        properties = {
                "essentials.lab.scenario=queue-autovacuum",
                "essentials.lab.autovacuum-cycles=${av.cycles:12}",
                "essentials.lab.autovacuum-messages-per-cycle=${av.messagesPerCycle:20000}",
                "essentials.eventstore.cdc.enabled=false",
                "essentials.lab.metrics-output-file=target/perf-lab-benchmark/queue-autovacuum.json"
        })
class QueueAutovacuumBenchmarkIT {

    // Deliberately NOT annotated @Container — see BackpressureScenarioSmokeIT.
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:17.5-bookworm")
            .withDatabaseName("essentials_lab")
            .withUsername("essentials")
            .withPassword("essentials")
            .withCommand("postgres", "-c", "autovacuum_naptime=" + System.getProperty("av.naptime", "60"));

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
    void every_cycle_drains_and_the_degradation_summary_is_produced() throws Exception {
        var output = Path.of("target/perf-lab-benchmark/queue-autovacuum.json");
        assertThat(output).exists();

        var json = objectMapper.readTree(Files.readString(output));
        assertThat(json.get("cycleResults")).isNotEmpty();
        for (var cycle : json.get("cycleResults")) {
            assertThat(cycle.get("messagesDrained").asInt())
                    .as("arm %s cycle %d must drain everything it inserted, or its cost is not comparable",
                        cycle.get("arm").asText(), cycle.get("cycle").asInt())
                    .isEqualTo(cycle.get("messagesInserted").asInt());
        }
        assertThat(json.get("summary")).isNotEmpty();
        // The cluster naptime is recorded because it may be the variable that actually matters.
        assertThat(json.get("clusterAutovacuumNaptime").asText()).isNotBlank();
    }
}
