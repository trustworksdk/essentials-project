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
 * Orders priced to fill that rest at the exchange anyway - here every order on an instrument the stub rests - do not
 * hang the run: once its counts stand still it cancels them, reports them as {@code restedUnexpectedly}, and completes
 * with every hold released. Before, a run whose market moved past its late orders never drained.
 */
@Testcontainers
@SpringBootTest(properties = {
        "trading-demo.simulation.enabled=false",
        "trading-demo.load.enabled=false",
        "trading-demo.queue-load.enabled=false",
        "trading-demo.market-data.synthetic.tick-interval=200ms",
        "trading-demo.orders.stub-exchange.resting-instruments=ACME"
})
@DirtiesContext // close while this class's container is still up; a cached context outlives it and stalls the next context switch
class OrderFlowStrandedOrdersIT {
    @Container
    static final PostgreSQLContainer postgres = new PostgreSQLContainer("postgres:18.4")
            .withDatabaseName("trading-demo-order-flow-stranded-it-db")
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
    void orders_resting_at_the_exchange_are_cancelled_and_reported_instead_of_hanging_the_run() {
        await().atMost(Duration.ofSeconds(30)).ignoreExceptions()
               .until(() -> generator.start(new OrderFlowLoadGenerator.Settings(5, 4, 2, 40, 20, "ACME")) != null);

        await().atMost(Duration.ofMinutes(3)).until(() -> !"RUNNING".equals(generator.status().state()));
        var result = generator.status();
        assertThat(result.state()).as("%s", result).isEqualTo("COMPLETED");
        assertThat(result.placed()).as("%s", result).isEqualTo(20);
        assertThat(result.refused()).as("%s", result).isZero();
        // Every order the run did not cancel itself rested, market orders included: nothing fills
        assertThat(result.restedUnexpectedly()).as("%s", result).isEqualTo(result.placed() - result.resting());
        assertThat(result.bookings()).as("%s", result).isZero();
        assertThat(result.releases()).as("%s", result).isEqualTo(result.placed());
    }
}
