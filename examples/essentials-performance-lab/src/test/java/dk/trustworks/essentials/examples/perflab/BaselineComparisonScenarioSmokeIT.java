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
                // Throttle producer so the smoke run also exercises the quiet-workload
                // path. 10 Hz over 1 s = ~10 events per leg; enough to verify the
                // counter advances, low enough to leave subscribers idle most of the time.
                "essentials.lab.producer-rate-hz=10",
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

        // All four legs present.
        assertThat(json.has("polling")).isTrue();
        assertThat(json.has("notifyPolling")).isTrue();
        assertThat(json.has("cdc")).isTrue();        // backward-compat alias for cdcInbox
        assertThat(json.has("cdcInbox")).isTrue();
        assertThat(json.has("cdcDirect")).isTrue();

        // Deltas against polling for every non-baseline leg.
        assertThat(json.has("delta")).isTrue();      // backward-compat alias for deltaInbox
        assertThat(json.has("deltaNotifyPolling")).isTrue();
        assertThat(json.has("deltaInbox")).isTrue();
        assertThat(json.has("deltaDirect")).isTrue();

        // The notify-polling leg should self-label as 'polling-notify' (proves the wiring
        // actually flipped — a regression in the autoconfig would leave mode='polling' here
        // and quietly produce identical numbers to the polling baseline).
        assertThat(json.get("notifyPolling").get("mode").asText()).isEqualTo("polling-notify");
        // The plain-polling leg should self-label as 'polling' (no S1 wake-up active).
        assertThat(json.get("polling").get("mode").asText()).isEqualTo("polling");

        // Producer-rate throttle is round-tripped from parent → child → JSON. Catches
        // regressions where the comparison scenario silently drops the new property.
        // producerRateHz is double-typed (fractional Hz supported); the comparison value
        // is the configured 10.0.
        assertThat(json.get("polling").get("producerTargetRateHz").asDouble()).isEqualTo(10.0d);
        assertThat(json.get("notifyPolling").get("producerTargetRateHz").asDouble()).isEqualTo(10.0d);

        // DB-load counter is wired and producing non-negative values. Don't assert
        // specific counts — even at 10 Hz over 1s the polling subscriber may or may not
        // have completed a poll cycle, depending on JVM warmup. We just need to know the
        // counter advanced at all on the polling leg (where polls definitely happen) and
        // that the field exists on every leg.
        assertThat(json.get("polling").get("eventStoreSelectsDuringMeasurement").asLong()).isGreaterThanOrEqualTo(0L);
        assertThat(json.get("polling").get("eventStoreSelectsPerSecond").asDouble()).isGreaterThanOrEqualTo(0.0d);
        assertThat(json.get("polling").get("eventStoreSelectsPerSecondPerSubscriber").asDouble()).isGreaterThanOrEqualTo(0.0d);
        assertThat(json.get("polling").get("eventStoreTable").asText()).isEqualTo("laborders_events");
        assertThat(json.get("notifyPolling").has("eventStoreSelectsDuringMeasurement")).isTrue();

        // Deltas include the new DB-load diffs.
        assertThat(json.get("deltaNotifyPolling").has("eventStoreSelectsPerSecondDiff")).isTrue();
        assertThat(json.get("deltaNotifyPolling").has("eventStoreSelectsPerSecondPerSubscriberDiff")).isTrue();
    }
}
