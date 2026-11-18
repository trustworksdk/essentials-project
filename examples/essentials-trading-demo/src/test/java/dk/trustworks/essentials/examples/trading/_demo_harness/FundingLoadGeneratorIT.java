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
import static org.awaitility.Awaitility.await;

/**
 * The funding load scenario against the stub gateway: every deposit and withdrawal requested, completed and booked.
 */
@Testcontainers
@SpringBootTest(properties = {
        "trading-demo.queue-load.enabled=false"
})
@DirtiesContext // close while this class's container is still up; a cached context outlives it and stalls the next context switch
class FundingLoadGeneratorIT {
    @Container
    static final PostgreSQLContainer postgres = new PostgreSQLContainer("postgres:18.4")
            .withDatabaseName("trading-demo-funding-load-it-db")
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
    private FundingLoadGenerator generator;

    @Test
    void a_run_completes_and_books_every_deposit_and_withdrawal() {
        await().atMost(Duration.ofSeconds(30)).ignoreExceptions()
               .until(() -> generator.start(new FundingLoadGenerator.Settings(10, 3, 2, 4)) != null);

        await().atMost(Duration.ofMinutes(3)).until(() -> !"RUNNING".equals(generator.status().state()));
        var result = generator.status();
        assertThat(result.state()).as("%s", result).isEqualTo("COMPLETED");
        assertThat(result.requested()).as("%s", result).isEqualTo(50);
        assertThat(result.refused()).as("%s", result).isZero();
        assertThat(result.completed()).as("the stub gateway completes every payment: %s", result).isEqualTo(50);
        assertThat(result.booked()).as("%s", result).isEqualTo(50);
        assertThat(result.depositEndToEndP50Ms()).as("%s", result).isPositive();
        assertThat(result.withdrawalEndToEndP50Ms()).as("%s", result).isPositive();
    }
}
