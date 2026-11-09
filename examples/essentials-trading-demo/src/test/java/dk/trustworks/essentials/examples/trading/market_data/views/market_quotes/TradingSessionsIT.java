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

package dk.trustworks.essentials.examples.trading.market_data.views.market_quotes;

import dk.trustworks.essentials.examples.trading.integration.SimulatorImages;
import dk.trustworks.essentials.examples.trading.integration.exchange_simulator.client.ApiClient;
import dk.trustworks.essentials.examples.trading.integration.exchange_simulator.client.api.TestControlApi;
import dk.trustworks.essentials.examples.trading.integration.exchange_simulator.client.model.ClockAdvance;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.resttestclient.TestRestTemplate;
import org.springframework.boot.resttestclient.autoconfigure.AutoConfigureTestRestTemplate;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpStatus;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.*;
import org.springframework.web.client.RestClient;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.*;
import org.testcontainers.postgresql.PostgreSQLContainer;

import java.math.BigDecimal;
import java.time.Duration;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

/**
 * The exchange's trading sessions as the demo runs it (D32, {@code simulators/exchange.yaml}): before the 09:30 New
 * York open the market is closed, the sessions endpoint says so and an order is rejected {@code MARKET_CLOSED}; after
 * the open it is open and an order fills.
 * <p>
 * Skipped unless the {@code exchange-simulator:dev} image exists locally (see {@link SimulatorImages}).
 */
@Testcontainers
@EnabledIf("dk.trustworks.essentials.examples.trading.integration.SimulatorImages#exchangeAvailable")
@SpringBootTest(properties = {
        "trading-demo.queue-load.enabled=false",
        "trading-demo.simulators.webhook-secret-file=simulators/secrets/webhook-secret.txt",
        "trading-demo.market-data.stale-after=2s"
}, webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureTestRestTemplate
@DirtiesContext // close while this class's containers are still up; a cached context outlives them
class TradingSessionsIT {
    private static final Duration TIMEOUT = Duration.ofSeconds(60);
    private static final String   ACCOUNT = "ACC-SESSIONS";

    @Container
    static final PostgreSQLContainer postgres = new PostgreSQLContainer("postgres:18.4")
            .withDatabaseName("trading-demo-sessions-it-db")
            .withUsername("test")
            .withPassword("test");

    /**
     * Frozen on Monday 2030-01-07 08:00 New York, before the open.
     */
    @Container
    static final GenericContainer<?> exchange = SimulatorImages.exchangeWithTradingSessions()
                                                              .withEnv("EXCHANGE__CLOCK__RUNNING", "false");

    @DynamicPropertySource
    static void registerProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
        registry.add("spring.datasource.driver-class-name", postgres::getDriverClassName);
        registry.add("trading-demo.simulators.exchange.base-url", TradingSessionsIT::exchangeUrl);
    }

    @Autowired
    private TestRestTemplate restTemplate;

    @Test
    void orders_are_rejected_before_the_open_and_fill_after_it() {
        assertThat(alice().postForEntity("/api/accounts", Map.of("accountId", ACCOUNT, "ownerId", "USR-ALICE", "currency", "USD"), Void.class)
                          .getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(operator().postForEntity("/api/admin/accounts/" + ACCOUNT + "/deposits",
                                            Map.of("accountId", ACCOUNT, "depositId", "DEP-" + ACCOUNT, "amount", Map.of("amount", new BigDecimal("100000.00"), "currency", "USD"),
                                                   "businessDate", "2030-01-07"),
                                            String.class).getStatusCode()).isEqualTo(HttpStatus.OK);

        await().atMost(TIMEOUT).untilAsserted(() -> assertThat(sessions()).containsEntry("ACME", "CLOSED"));
        placeLimitBuy("ORD-BEFORE-OPEN");
        await().atMost(TIMEOUT).untilAsserted(() -> assertThat(order("ORD-BEFORE-OPEN")).contains("\"REJECTED\"").contains("MARKET_CLOSED"));

        // 08:00 + 1 h 35 min: 09:35 New York, the session is open
        testControl().advanceClock(new ClockAdvance().days(0).hours(1).minutes(35).seconds(0));
        await().atMost(TIMEOUT).untilAsserted(() -> assertThat(sessions()).containsEntry("ACME", "OPEN"));
        await().atMost(TIMEOUT).untilAsserted(() -> assertThat(alice().getForEntity("/api/market-data/quotes/ACME", String.class).getStatusCode()).isEqualTo(HttpStatus.OK));
        placeLimitBuy("ORD-AFTER-OPEN");
        await().atMost(TIMEOUT).untilAsserted(() -> assertThat(order("ORD-AFTER-OPEN")).contains("\"FILLED\""));
    }

    /**
     * Marketable: far above any price the synthetic source quotes ACME at, so it fills at the ask.
     */
    private void placeLimitBuy(String orderId) {
        var placed = alice().postForEntity("/api/accounts/" + ACCOUNT + "/orders",
                                           Map.of("orderId", orderId, "accountId", ACCOUNT, "instrumentId", "ACME", "side", "BUY", "type", "LIMIT", "quantity", 1,
                                                  "limitPrice", Map.of("amount", new BigDecimal("500.00"), "currency", "USD")),
                                           String.class);
        assertThat(placed.getStatusCode()).as("%s", placed.getBody()).isEqualTo(HttpStatus.CREATED);
    }

    private String order(String orderId) {
        return alice().getForEntity("/api/accounts/" + ACCOUNT + "/orders/" + orderId, String.class).getBody();
    }

    @SuppressWarnings("unchecked")
    private Map<String, String> sessions() {
        return alice().getForEntity("/api/market-data/sessions", Map.class).getBody();
    }

    private TestRestTemplate operator() {
        return restTemplate.withBasicAuth("operator@demo.local", "operator-demo-password");
    }

    private TestRestTemplate alice() {
        return restTemplate.withBasicAuth("alice@demo.local", "alice-demo-password");
    }

    private static TestControlApi testControl() {
        var apiClient = new ApiClient(RestClient.builder().build());
        apiClient.setBasePath(exchangeUrl());
        return new TestControlApi(apiClient);
    }

    private static String exchangeUrl() {
        return "http://" + exchange.getHost() + ":" + exchange.getMappedPort(8080);
    }
}
