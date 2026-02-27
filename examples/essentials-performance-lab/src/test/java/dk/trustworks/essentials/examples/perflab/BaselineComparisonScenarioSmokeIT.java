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
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.*;

import java.nio.file.*;

import static org.assertj.core.api.Assertions.assertThat;

@Testcontainers(disabledWithoutDocker = true)
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE,
        properties = {
                "essentials.lab.scenario=baseline-polling-vs-cdc-compare",
                "essentials.lab.warmup=0s",
                "essentials.lab.duration=1s",
                "essentials.lab.producer-threads=1",
                "essentials.lab.subscriber-count=1",
                "essentials.lab.aggregate-cardinality=10",
                "essentials.lab.random-seed=7",
                "essentials.eventstore.cdc.enabled=false",
                "essentials.lab.metrics-output-file=target/perf-lab-smoke/baseline-compare.json"
        })
class BaselineComparisonScenarioSmokeIT {

    @Container
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
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
    }

    @Test
    void compare_scenario_writes_json_output() throws Exception {
        var output = Path.of("target/perf-lab-smoke/baseline-compare.json");
        assertThat(output).exists();

        var json = objectMapper.readTree(Files.readString(output));
        assertThat(json.has("polling")).isTrue();
        assertThat(json.has("cdc")).isTrue();
        assertThat(json.has("cdcInbox")).isTrue();
        assertThat(json.has("cdcDirect")).isTrue();
        assertThat(json.has("delta")).isTrue();
        assertThat(json.has("deltaInbox")).isTrue();
        assertThat(json.has("deltaDirect")).isTrue();
    }
}
