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
 * A small order-flow run completes: every placed order is booked or cancelled, and every hold comes back.
 */
@Testcontainers
@SpringBootTest(properties = {
        "trading-demo.simulation.enabled=false",
        "trading-demo.load.enabled=false",
        "trading-demo.queue-load.enabled=false",
        "trading-demo.market-data.synthetic.tick-interval=200ms"
})
@DirtiesContext // close while this class's container is still up; a cached context outlives it and stalls the next context switch
class OrderFlowLoadGeneratorIT {
    @Container
    static final PostgreSQLContainer postgres = new PostgreSQLContainer("postgres:18.4")
            .withDatabaseName("trading-demo-order-flow-it-db")
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
    private OrderFlowLoadGenerator generator;

    @Test
    void a_run_places_books_and_releases_every_order() {
        await().atMost(Duration.ofSeconds(30)).ignoreExceptions()
               .until(() -> generator.start(new OrderFlowLoadGenerator.Settings(10, 10, 4, 40, 20, "ACME")) != null);

        await().atMost(Duration.ofMinutes(3)).until(() -> !"RUNNING".equals(generator.status().state()));
        var result = generator.status();
        assertThat(result.state()).as("%s", result).isEqualTo("COMPLETED");
        assertThat(result.placed()).as("%s", result).isEqualTo(100);
        assertThat(result.refused()).as("%s", result).isZero();
        assertThat(result.bookings()).as("%s", result).isEqualTo(result.placed() - result.resting());
        assertThat(result.releases()).as("%s", result).isEqualTo(result.placed());
        assertThat(result.endToEndP50Ms()).as("%s", result).isPositive();
    }
}
