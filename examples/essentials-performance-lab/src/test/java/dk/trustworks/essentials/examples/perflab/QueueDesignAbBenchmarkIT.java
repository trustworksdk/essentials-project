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
 * The measurement run for the two queue-redesign levers, writing
 * {@code target/perf-lab-benchmark/queue-design-ab.json}.
 * <p>
 * {@code useOrderedUnorderedQuery} selects between two entirely different fetch queries and two different
 * index sets, and is fixed when {@code PostgresqlDurableQueues} is constructed — so it is a per-JVM
 * parameter, set here via {@code -Dqd.orderedUnorderedQuery} and echoed into the output so a pair of runs
 * can be compared. Note it defaults to {@code true} in the Spring starter even though
 * {@code PostgresqlDurableQueuesBuilder} defaults it to {@code false}.
 * <p>
 * Opt-in via {@code -Dbenchmark.run=true}, per this repo's convention for suites that measure rather than
 * assert. The assertions cover only harness integrity — every case drained, nothing left behind, and the
 * batched arm really batched — because a case that stranded rows produces a throughput figure that is an
 * artefact rather than a measurement.
 * <pre>{@code
 * JAVA_HOME=... mvn verify -pl examples/essentials-performance-lab \
 *   -Dbenchmark.run=true -Dit.test=QueueDesignAbBenchmarkIT \
 *   -Dqd.repetitions=5 -Dqd.poolSize=100 -Dqd.orderedUnorderedQuery=true
 * }</pre>
 */
@Testcontainers(disabledWithoutDocker = true)
@EnabledIfSystemProperty(named = "benchmark.run", matches = "true")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE,
        properties = {
                "essentials.lab.scenario=queue-design-ab",
                "essentials.lab.queue-design-ordered-fractions=${qd.orderedFractions:0.0,0.5,1.0}",
                "essentials.lab.queue-design-parallel-consumers=${qd.parallelConsumers:32}",
                "essentials.lab.queue-design-messages-per-case=${qd.messagesPerCase:4000}",
                "essentials.lab.queue-design-repetitions=${qd.repetitions:5}",
                "essentials.lab.queue-design-ordered-key-count=${qd.orderedKeyCount:64}",
                "essentials.lab.queue-design-ack-flush-interval=${qd.ackFlushInterval:50ms}",
                "essentials.lab.queue-design-ack-max-batch-size=${qd.ackMaxBatchSize:200}",
                // Set the framework flag and echo the same value into the report, so the output is
                // self-describing rather than depending on whoever ran it remembering the command line.
                "essentials.durable-queues.use-ordered-unordered-query=${qd.orderedUnorderedQuery:true}",
                "essentials.lab.queue-design-use-ordered-unordered-query-label=${qd.orderedUnorderedQuery:true}",
                "spring.datasource.hikari.maximum-pool-size=${qd.poolSize:100}",
                "essentials.eventstore.cdc.enabled=false",
                "essentials.lab.metrics-output-file=target/perf-lab-benchmark/queue-design-ab.json"
        })
class QueueDesignAbBenchmarkIT {

    // Deliberately NOT annotated @Container — see BackpressureScenarioSmokeIT.
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:17.5-bookworm")
            .withDatabaseName("essentials_lab")
            .withUsername("essentials")
            .withPassword("essentials")
            .withCommand("postgres",
                         "-c", "wal_level=logical",
                         "-c", "max_replication_slots=10",
                         "-c", "max_wal_senders=10",
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
    void queue_design_benchmark_completes_every_case_without_stranding_rows() throws Exception {
        var output = Path.of("target/perf-lab-benchmark/queue-design-ab.json");
        assertThat(output).exists();

        var json  = objectMapper.readTree(Files.readString(output));
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
            assertThat(singleCase.get("rowsLeftInQueue").asLong())
                    .as("case %s must leave no rows behind — stranded rows mean the drain clock stopped early", caseId)
                    .isZero();
            assertThat(singleCase.get("handlerFailures").asInt()).as("case %s had no handler failures", caseId).isZero();

            if ("BATCHED".equals(singleCase.get("ackMode").asText())) {
                assertThat(singleCase.get("ackFlushCount").asLong())
                        .as("case %s claims to batch acks but issued about one delete per message", caseId)
                        .isLessThan(singleCase.get("ackFlushedMessages").asLong());
            }
        }
    }
}
