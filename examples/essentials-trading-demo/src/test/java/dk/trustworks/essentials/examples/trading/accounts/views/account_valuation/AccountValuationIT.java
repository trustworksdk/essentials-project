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

package dk.trustworks.essentials.examples.trading.accounts.views.account_valuation;

import dk.trustworks.essentials.examples.trading.accounts.views.account_overview.AccountOverview;
import dk.trustworks.essentials.examples.trading.integration.SimulatorImages;
import dk.trustworks.essentials.examples.trading.integration.exchange_simulator.client.ApiClient;
import dk.trustworks.essentials.examples.trading.integration.exchange_simulator.client.api.TestControlApi;
import dk.trustworks.essentials.examples.trading.integration.exchange_simulator.client.model.ClockAdvance;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIf;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.resttestclient.TestRestTemplate;
import org.springframework.boot.resttestclient.autoconfigure.AutoConfigureTestRestTemplate;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.*;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.*;
import org.springframework.web.client.RestClient;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.*;
import org.testcontainers.postgresql.PostgreSQLContainer;

import java.math.BigDecimal;
import java.time.Duration;
import java.util.*;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

/**
 * An account's end-of-day valuation from the real exchange's closing prices: after a buy of 2 ACME and a business day
 * closed at the exchange, the 2 shares are valued at ACME's closing price, and the total is that plus the cash balance.
 * <p>
 * Skipped unless the {@code exchange-simulator:dev} image exists locally (see {@link SimulatorImages}).
 */
@Testcontainers
@EnabledIf("dk.trustworks.essentials.examples.trading.integration.SimulatorImages#exchangeAvailable")
@SpringBootTest(properties = {
        "trading-demo.simulation.enabled=false",
        "trading-demo.load.enabled=false",
        "trading-demo.queue-load.enabled=false",
        "trading-demo.simulators.webhook-secret-file=simulators/secrets/webhook-secret.txt"
}, webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureTestRestTemplate
@DirtiesContext // close while this class's containers are still up; a cached context outlives them
class AccountValuationIT {
    private static final Duration TIMEOUT = Duration.ofSeconds(60);

    @Container
    static final PostgreSQLContainer postgres = new PostgreSQLContainer("postgres:18.4")
            .withDatabaseName("trading-demo-valuation-it-db")
            .withUsername("test")
            .withPassword("test");

    /**
     * Frozen, so the test moves the business days on; settlement simulated T+1 (simulators/exchange.yaml).
     */
    @Container
    static final GenericContainer<?> exchange = SimulatorImages.exchangeAsTheDemoRunsIt()
                                                              .withEnv("EXCHANGE__CLOCK__RUNNING", "false");

    @DynamicPropertySource
    static void registerProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
        registry.add("spring.datasource.driver-class-name", postgres::getDriverClassName);
        registry.add("trading-demo.simulators.exchange.base-url", AccountValuationIT::exchangeUrl);
    }

    @Autowired
    private TestRestTemplate restTemplate;

    @Test
    void positions_are_valued_at_the_latest_closing_prices() {
        buyTwoAcme("ACC-VALUE", "ORD-VALUE-1");

        advanceExchangeClock(4); // past at least one business day's cutoff

        await().atMost(TIMEOUT).untilAsserted(() -> {
            var valuation = alice().getForEntity("/api/accounts/ACC-VALUE/valuation", AccountValuation.class).getBody();
            assertThat(valuation).isNotNull();
            assertThat(valuation.businessDate()).isNotNull();
            assertThat(valuation.positions()).singleElement().satisfies(position -> {
                assertThat(position.instrumentId().toString()).isEqualTo("ACME");
                assertThat(position.held().value()).isEqualTo(2);
                assertThat(position.closingPrice()).isNotNull();
                assertThat(position.value().getAmount().value()).isEqualByComparingTo(position.closingPrice().getAmount().value().multiply(BigDecimal.TWO));
            });
            assertThat(valuation.totalValue().getAmount().value())
                    .isEqualByComparingTo(valuation.cashBalance().getAmount().value().add(valuation.marketValue().getAmount().value()));
        });
    }

    private TestRestTemplate operator() {
        return restTemplate.withBasicAuth("operator@demo.local", "operator-demo-password");
    }

    private TestRestTemplate alice() {
        return restTemplate.withBasicAuth("alice@demo.local", "alice-demo-password");
    }

    private static Map<String, Object> usd(String amount) {
        return Map.of("amount", new BigDecimal(amount), "currency", "USD");
    }

    /**
     * An account with 10 000 credited, and a market buy of 2 ACME filled at the exchange and booked pending.
     */
    private void buyTwoAcme(String accountId, String orderId) {
        assertThat(alice().postForEntity("/api/accounts", Map.of("accountId", accountId, "ownerId", "USR-ALICE", "currency", "USD"), Void.class)
                          .getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(operator().postForEntity("/api/admin/accounts/" + accountId + "/deposits",
                                            Map.of("accountId", accountId, "depositId", "DEP-" + accountId, "amount", usd("10000.00"), "businessDate", "2030-01-07"),
                                            String.class).getStatusCode()).isEqualTo(HttpStatus.OK);
        await().atMost(TIMEOUT).until(() -> alice().getForEntity("/api/accounts/" + accountId + "/orders", String.class).getStatusCode() == HttpStatus.OK);
        await().atMost(TIMEOUT).untilAsserted(() -> {
            var placed = alice().postForEntity("/api/accounts/" + accountId + "/orders",
                                               Map.of("orderId", orderId, "accountId", accountId, "instrumentId", "ACME", "side", "BUY", "type", "MARKET", "quantity", 2),
                                               String.class);
            assertThat(placed.getStatusCode()).as("%s", placed.getBody()).isEqualTo(HttpStatus.CREATED);
        });
        await().atMost(TIMEOUT).untilAsserted(() -> assertThat(overview(accountId).pendingCashOut().getAmount().value()).isPositive());
    }

    private AccountOverview overview(String accountId) {
        return alice().getForEntity("/api/accounts/" + accountId, AccountOverview.class).getBody();
    }

    private static void advanceExchangeClock(int days) {
        var apiClient = new ApiClient(RestClient.builder().build());
        apiClient.setBasePath(exchangeUrl());
        new TestControlApi(apiClient).advanceClock(new ClockAdvance().days(days).hours(0).minutes(0).seconds(0));
    }

    private static String exchangeUrl() {
        return "http://" + exchange.getHost() + ":" + exchange.getMappedPort(8080);
    }
}
