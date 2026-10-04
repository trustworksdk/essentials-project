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
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.*;

/**
 * The settlement automation with the application's {@code DurableQueues} on the shard-owned engine, as the demo runs it
 * under the {@code compose} profile.
 * <p>
 * Every {@code EventProcessor} inbox is then a shard-owned queue, and an event reaches its handler as a reference the
 * processor resolves by the event's order. The adapter used to deliver every ordered message with order 0, so each
 * reference resolved to the aggregate's first event: {@code TradeExecuted} arrived as a second {@code TradePlaced},
 * nothing was ever settled, and nothing failed. {@link SettleTradeAutomationTest} could not see it - it runs on
 * {@code PostgresqlDurableQueues}.
 */
@Testcontainers
@SpringBootTest(properties = {
        "trading-demo.simulation.enabled=false",
        "trading-demo.load.enabled=false",
        "trading-demo.simulation.trade-lifecycle=automated",
        "trading-demo.clearing-house.latency=100ms",
        "essentials.shard-owned-queue.initialize-schema=true",
        "essentials.shard-owned-queue.durable-queues-enabled=true"
})
@DirtiesContext // close while this class's container is still up; a cached context outlives it and stalls the next context switch
class SettleTradeAutomationOnShardOwnedQueuesTest extends AbstractSettleTradeAutomationTest {
    @Container
    static final PostgreSQLContainer<?> postgres = postgres("trading-demo-settle-shard-owned-test-db");

    @DynamicPropertySource
    static void registerProperties(DynamicPropertyRegistry registry) {
        registerDataSource(registry, postgres);
    }
}
