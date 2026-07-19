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
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Smoke test for the backpressure scenario — runs with CDC disabled (falls back to polling) so we
 * don't need a wal2json-enabled Postgres image. Verifies the scenario runs end-to-end, emits the
 * expected JSON shape, and reports the three invariants ({@code invariantBoundedBufferHeld},
 * {@code invariantNoEventsLost}, {@code invariantNoDispatcherTickFailures}).
 * <p>
 * Bounded-buffer validation under real CDC load requires a wal2json-enabled Postgres — run the
 * scenario manually against the {@code docker-compose.yml} stack for that.
 */
@Testcontainers(disabledWithoutDocker = true)
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE,
        properties = {
                "essentials.lab.scenario=backpressure",
                "essentials.lab.warmup=0s",
                "essentials.lab.duration=2s",
                "essentials.lab.producer-threads=1",
                "essentials.lab.subscriber-count=1",
                "essentials.lab.aggregate-cardinality=10",
                "essentials.lab.random-seed=11",
                "essentials.lab.subscriber-handler-delay-ms=10",
                "essentials.eventstore.cdc.enabled=false",
                "essentials.lab.metrics-output-file=target/perf-lab-smoke/backpressure.json"
        })
class BackpressureScenarioSmokeIT {

    // Deliberately NOT annotated @Container. @Container stops the container from JUnit's
    // AfterAllCallback, but the @SpringBootTest context is cached by the TestContext framework
    // and only closed later, by SpringApplicationShutdownHook at JVM exit. That ordering runs
    // the entire Spring shutdown against a dead database: an EOFException storm from the
    // background pollers, plus a 30s stall while DBFencedLockManager.stop() tries to release
    // its locks and blocks on Hikari's connectionTimeout. Starting the container manually (see
    // registerProperties) leaves it running for the life of the JVM, so it outlives the context;
    // Testcontainers' Ryuk sidecar reaps it after the JVM exits.
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
        // Started here rather than in a static initializer so it only happens once the context is
        // actually being built — i.e. after @Testcontainers' disabledWithoutDocker condition has
        // been evaluated, so a Docker-less machine skips instead of failing to start a container.
        postgres.start();
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
    }

    @Test
    void backpressure_scenario_writes_json_with_invariants_and_pressure_stats() throws Exception {
        var output = Path.of("target/perf-lab-smoke/backpressure.json");
        assertThat(output).exists();

        var json = objectMapper.readTree(Files.readString(output));

        // Key configured values round-trip.
        assertThat(json.get("handlerDelayMs").asLong()).isEqualTo(10L);
        assertThat(json.get("backpressureBufferSize").asInt()).isPositive();

        // Pressure struct is present.
        var pressure = json.get("pressure");
        assertThat(pressure).isNotNull();
        assertThat(pressure.has("peakBackfillLiveBufferSize")).isTrue();
        assertThat(pressure.has("peakInboxReceivedCount")).isTrue();
        assertThat(pressure.has("finalInboxReceivedCount")).isTrue();
        assertThat(pressure.has("samples")).isTrue();
        assertThat(pressure.has("dispatcherTickFailuresDelta")).isTrue();

        // All four invariants are reported. With CDC disabled (polling fallback), all should hold
        // trivially — this proves the scenario doesn't false-alarm in the happy path and that the
        // split between "actually lost" and "caught up in time" is wired correctly.
        assertThat(json.get("invariantBoundedBufferHeld").asBoolean()).isTrue();
        assertThat(json.get("invariantNoEventsActuallyLost").asBoolean()).isTrue();
        assertThat(json.get("invariantCaughtUpWithinTimeout").asBoolean()).isTrue();
        assertThat(json.get("invariantNoDispatcherTickFailures").asBoolean()).isTrue();

        // eventsInDbCount should equal producedEvents in the happy path (all appends landed durably).
        long produced = json.get("producedEvents").asLong();
        assertThat(produced).isPositive();
        assertThat(json.get("eventsInDbCount").asLong()).isEqualTo(produced);
    }
}
