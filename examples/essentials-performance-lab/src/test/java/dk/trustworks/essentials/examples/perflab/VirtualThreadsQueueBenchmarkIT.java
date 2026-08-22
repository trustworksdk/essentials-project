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
 * The real {@code virtual-threads-queue} measurement run: a full {@code parallelConsumers} sweep for both
 * handler shapes, writing {@code target/perf-lab-benchmark/virtual-threads-queue.json} for analysis.
 * <p>
 * Opt-in via {@code -Dbenchmark.run=true} per this repo's convention for suites that measure rather than
 * assert — it takes minutes and, having no threshold to fail on, buys nothing per build. The only
 * assertions here are that every case actually completed, because a timed-out or partially drained case
 * silently poisons the throughput numbers it produces.
 * <p>
 * The connection pool is deliberately left at the application default. The DB arm's whole point is to show
 * where the ceiling sits when the handler holds a pooled connection, and the pool size is recorded in the
 * output as {@code connectionPoolMaximumSize}.
 * <pre>{@code
 * JAVA_HOME=... mvn verify -pl examples/essentials-performance-lab \
 *   -Dbenchmark.run=true -Dit.test=VirtualThreadsQueueBenchmarkIT
 * }</pre>
 */
@Testcontainers(disabledWithoutDocker = true)
@EnabledIfSystemProperty(named = "benchmark.run", matches = "true")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE,
        properties = {
                "essentials.lab.scenario=virtual-threads-queue",
                "essentials.lab.virtual-threads-parallel-consumers=${vt.parallelConsumers:8,32,128,512}",
                "essentials.lab.virtual-threads-messages-per-case=${vt.messagesPerCase:600}",
                "essentials.lab.virtual-threads-handler-delay=${vt.handlerDelay:50ms}",
                "essentials.lab.virtual-threads-handler-mode=${vt.handlerMode:BOTH}",
                "essentials.lab.virtual-threads-repetitions=${vt.repetitions:5}",
                // Overridable so the sweep can be re-run against a wider pool. Whether throughput moves
                // when this moves is the test that separates "thread-bound" from "connection-bound", and
                // it is the first thing to check before reading anything into a platform-vs-virtual delta.
                "spring.datasource.hikari.maximum-pool-size=${vt.poolSize:10}",
                "essentials.eventstore.cdc.enabled=false",
                "essentials.lab.metrics-output-file=target/perf-lab-benchmark/virtual-threads-queue.json"
        })
class VirtualThreadsQueueBenchmarkIT {

    // Deliberately NOT annotated @Container — see BackpressureScenarioSmokeIT.
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:17.5-bookworm")
            .withDatabaseName("essentials_lab")
            .withUsername("essentials")
            .withPassword("essentials")
            .withCommand("postgres",
                         "-c", "wal_level=logical",
                         "-c", "max_replication_slots=10",
                         "-c", "max_wal_senders=10",
                         // The DB handler mode parks a connection per in-flight message for the length of a
                         // pg_sleep. The client-side pool bounds that far below this, but a server-side
                         // ceiling of 100 would turn any pool-size experiment into a connection error.
                         "-c", "max_connections=400");

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
    void virtual_threads_queue_benchmark_completes_every_case() throws Exception {
        var output = Path.of("target/perf-lab-benchmark/virtual-threads-queue.json");
        assertThat(output).exists();

        var json = objectMapper.readTree(Files.readString(output));
        var cases = json.get("cases");
        assertThat(cases).isNotEmpty();

        for (var singleCase : cases) {
            var caseId = singleCase.get("caseId").asText();
            assertThat(singleCase.get("drainedWithinTimeout").asBoolean())
                    .as("case %s must drain — a timed-out case reports a throughput that is an artefact of the timeout", caseId)
                    .isTrue();
            assertThat(singleCase.get("messagesHandled").asInt())
                    .as("case %s must handle every queued message", caseId)
                    .isEqualTo(singleCase.get("messagesQueued").asInt());
            assertThat(singleCase.get("handlerFailures").asInt())
                    .as("case %s must not have handler failures — a failed message is redelivered and double-counts", caseId)
                    .isZero();
        }
    }
}
