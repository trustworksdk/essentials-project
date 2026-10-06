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

package dk.trustworks.essentials.examples.trading.brokerage;

import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.*;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.*;

/**
 * The settlement automation with the application's {@code DurableQueues} on {@code PostgresqlDurableQueues}.
 */
@Testcontainers
@SpringBootTest(properties = {
        "trading-demo.simulation.enabled=false",
        "trading-demo.load.enabled=false",
        "trading-demo.simulation.trade-lifecycle=automated",
        "trading-demo.clearing-house.latency=100ms"
})
@DirtiesContext // close while this class's container is still up; a cached context outlives it and stalls the next context switch
class SettleTradeAutomationTest extends AbstractSettleTradeAutomationTest {
    @Container
    static final PostgreSQLContainer postgres = postgres("trading-demo-settle-test-db");

    @DynamicPropertySource
    static void registerProperties(DynamicPropertyRegistry registry) {
        registerDataSource(registry, postgres);
    }
}
