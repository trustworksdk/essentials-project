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

import tools.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.*;

import java.nio.file.*;

import dk.trustworks.essentials.examples.perflab.scenario.EventLogVsPollingVsCdcComparisonScenario;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Smoke test of the opt-in new-engine comparison (design phase 8, unit 8b-E): every leg runs, the two new-engine legs
 * deliver every event, self-label, and report the statement counters.
 */
@Testcontainers(disabledWithoutDocker = true)
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE,
        properties = {
                "essentials.lab.event-log.enabled=true",
                "essentials.lab.scenario=event-log-vs-polling-vs-cdc-compare",
                "essentials.lab.warmup=0s",
                "essentials.lab.duration=1s",
                "essentials.lab.producer-threads=1",
                "essentials.lab.subscriber-count=2",
                "essentials.lab.aggregate-cardinality=10",
                "essentials.lab.random-seed=7",
                "essentials.lab.producer-rate-hz=10",
                "essentials.eventstore.cdc.enabled=false",
                "essentials.lab.metrics-output-file=target/perf-lab-smoke/event-log-compare.json"
        })
class EventLogComparisonScenarioSmokeIT {

    // Not @Container, for the reason given in BaselineComparisonScenarioSmokeIT: the cached Spring context must outlive it.
    static PostgreSQLContainer postgres = new PostgreSQLContainer("postgres:17.5-bookworm")
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
    void compare_scenario_runs_every_leg_and_the_new_engine_delivers_everything() throws Exception {
        var output = Path.of("target/perf-lab-smoke/event-log-compare.json");
        assertThat(output).exists();
        var json = objectMapper.readTree(Files.readString(output));

        for (var leg : EventLogVsPollingVsCdcComparisonScenario.LEGS) {
            assertThat(json.has(leg)).as(leg).isTrue();
        }
        assertThat(json.has("deltaEventLogReactor")).isTrue();
        assertThat(json.has("deltaEventLogProjection")).isTrue();
        assertThat(json.get("polling").get("mode").asText()).isEqualTo("polling");

        for (var leg : new String[]{"eventLogReactor", "eventLogProjection"}) {
            var result = json.get(leg);
            assertThat(result.get("mode").asText()).as(leg).isEqualTo(leg.equals("eventLogReactor") ? "event-log-reactor" : "event-log-projection");
            assertThat(result.get("producedEvents").asLong()).as(leg).isPositive();
            assertThat(result.get("deliveredEvents").asLong()).as(leg).isEqualTo(result.get("expectedDeliveries").asLong());
            assertThat(result.get("caughtUpWithinTimeout").asBoolean()).as(leg).isTrue();
            assertThat(result.get("producerTargetRateHz").asDouble()).as(leg).isEqualTo(10.0d);
            assertThat(result.get("eventStoreTable").asText()).as(leg).isEqualTo("el_laborders_events");
            // appends alone execute statements, so the counter must have advanced
            assertThat(result.get("engineStatementsDuringMeasurement").asLong()).as(leg).isPositive();
            assertThat(result.get("eventStoreSelectsDuringMeasurement").asLong()).as(leg).isGreaterThanOrEqualTo(0L);
        }
    }
}
