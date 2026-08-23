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
 * The last two unmeasured items on the storage track: the dead-letter side table and partitioning by
 * {@code queue_name}, against v1's shared table.
 * <pre>{@code
 * JAVA_HOME=... mvn verify -pl examples/essentials-performance-lab \
 *   -Dbenchmark.run=true -Dit.test=QueueStorageLayoutBenchmarkIT \
 *   -Dsl.messages=40000 -Dsl.queues=8 -Dsl.repetitions=2
 * }</pre>
 * Read {@code ackByIdSpeedup} first. Partitioning forces {@code queue_name} into the primary key while the whole
 * {@code DurableQueues} API is keyed by {@code QueueEntryId} alone, so every by-id operation loses its
 * single-partition lookup — and acknowledgement by id is the hot path §7 measured at 16.5x. Partitioning can win
 * decisively on purge and still be the wrong choice.
 */
@Testcontainers(disabledWithoutDocker = true)
@EnabledIfSystemProperty(named = "benchmark.run", matches = "true")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE,
        properties = {
                "essentials.lab.scenario=queue-storage-layout",
                "essentials.lab.storage-layout-messages=${sl.messages:40000}",
                "essentials.lab.storage-layout-queue-count=${sl.queues:8}",
                "essentials.lab.storage-layout-repetitions=${sl.repetitions:2}",
                "essentials.lab.storage-layout-dead-letter-percent=${sl.dlqPercent:5}",
                "essentials.eventstore.cdc.enabled=false",
                "essentials.lab.metrics-output-file=target/perf-lab-benchmark/queue-storage-layout.json"
        })
class QueueStorageLayoutBenchmarkIT {

    // Deliberately NOT annotated @Container — see BackpressureScenarioSmokeIT.
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:17.5-bookworm")
            .withDatabaseName("essentials_lab")
            .withUsername("essentials")
            .withPassword("essentials");

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
    void every_arm_drains_its_backlog_and_the_comparison_is_produced() throws Exception {
        var output = Path.of("target/perf-lab-benchmark/queue-storage-layout.json");
        assertThat(output).exists();

        var json = objectMapper.readTree(Files.readString(output));
        assertThat(json.get("cases")).isNotEmpty();
        for (var singleCase : json.get("cases")) {
            var arm = singleCase.get("arm").asText();
            // Dead-lettered messages are not claimable, so the drain target is what remains.
            var claimable = singleCase.get("messagesInserted").asInt() - singleCase.get("messagesDeadLettered").asInt();
            assertThat(singleCase.get("messagesClaimed").asInt())
                    .as("arm %s must claim every message that was not dead-lettered", arm)
                    .isEqualTo(claimable);
            assertThat(singleCase.get("ackByIdMillis").asLong()).as("arm %s ack timing", arm).isPositive();
        }
        assertThat(json.get("comparisons")).isNotEmpty();
    }
}
