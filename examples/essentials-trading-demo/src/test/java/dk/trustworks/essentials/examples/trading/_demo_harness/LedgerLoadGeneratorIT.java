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

package dk.trustworks.essentials.examples.trading._demo_harness;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.*;
import org.testcontainers.junit.jupiter.*;
import org.testcontainers.postgresql.PostgreSQLContainer;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The ledger load generator at toy size, both modes: every command handled, nothing failed, the activity view caught
 * up, and — the property the ORDERED mode exists to show — no optimistic-concurrency conflict when every command for
 * an account goes through one ordered key. Not a measurement; the numbers come from real runs (see
 * {@code _demo_harness/CLAUDE.md}).
 */
@Testcontainers
@SpringBootTest(properties = {
        "trading-demo.queue-load.enabled=false"
})
@DirtiesContext // close while this class's container is still up; a cached context outlives it and stalls the next context switch
class LedgerLoadGeneratorIT {
    @Container
    static final PostgreSQLContainer postgres = new PostgreSQLContainer("postgres:18.4")
            .withDatabaseName("trading-demo-ledger-load-it-db")
            .withUsername("test")
            .withPassword("test");

    @DynamicPropertySource
    static void registerProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
        registry.add("spring.datasource.driver-class-name", postgres::getDriverClassName);
    }

    @Autowired
    private LedgerLoadGenerator generator;

    @Test
    void both_modes_handle_every_command_and_only_the_direct_one_can_conflict() throws Exception {
        generator.start(new LedgerLoadGenerator.Settings(LedgerLoadGenerator.Mode.ORDERED, 10, 200, 2, 80, 4, 3));
        var ordered = generator.awaitResult(Duration.ofMinutes(3));

        assertThat(ordered.state()).as(String.valueOf(ordered)).isEqualTo("DONE");
        assertThat(ordered.commandsHandled()).isEqualTo(800);
        assertThat(ordered.failures()).isZero();
        assertThat(ordered.conflicts()).isZero();
        assertThat(ordered.viewCatchUpMs()).isGreaterThanOrEqualTo(0);

        generator.start(new LedgerLoadGenerator.Settings(LedgerLoadGenerator.Mode.DIRECT, 10, 200, 2, 80, 8, 1));
        var direct = generator.awaitResult(Duration.ofMinutes(3));

        // Racing cycles on two hot accounts: conflicts are expected, and a command can exhaust its retries. That is the
        // finding DIRECT exists to show, so it is accounted for rather than forbidden
        assertThat(direct.state()).as(String.valueOf(direct)).isEqualTo("DONE");
        assertThat(direct.commandsHandled() + direct.failures()).isEqualTo(800);
        assertThat(direct.viewCatchUpMs()).isGreaterThanOrEqualTo(0);
    }
}
