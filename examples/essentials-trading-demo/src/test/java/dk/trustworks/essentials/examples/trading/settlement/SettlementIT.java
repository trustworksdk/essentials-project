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

package dk.trustworks.essentials.examples.trading.settlement;

import dk.trustworks.essentials.examples.trading.accounts.views.account_overview.AccountOverview;
import dk.trustworks.essentials.examples.trading.integration.SimulatorImages;
import dk.trustworks.essentials.examples.trading.integration.exchange_simulator.client.ApiClient;
import dk.trustworks.essentials.examples.trading.integration.exchange_simulator.client.api.TestControlApi;
import dk.trustworks.essentials.examples.trading.integration.exchange_simulator.client.model.ClockAdvance;
import dk.trustworks.essentials.examples.trading.settlement.types.SettlementState;
import dk.trustworks.essentials.examples.trading.settlement.views.settlements.SettlementView;
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
 * A filled buy settles at the exchange T+1 and its pending cash and shares become settled; then the exchange corrects
 * the settlement amount (correction probability 1) and the ledger books the change on cash and cost basis.
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
class SettlementIT {
    private static final Duration TIMEOUT = Duration.ofSeconds(60);

    @Container
    static final PostgreSQLContainer postgres = new PostgreSQLContainer("postgres:18.4")
            .withDatabaseName("trading-demo-settlement-it-db")
            .withUsername("test")
            .withPassword("test");

    /**
     * Frozen, so the test moves the business days on; settlement simulated T+1 (simulators/exchange.yaml).
     */
    @Container
    static final GenericContainer<?> exchange = SimulatorImages.exchangeAsTheDemoRunsIt()
                                                              .withEnv("EXCHANGE__CLOCK__RUNNING", "false")
                                                              .withEnv("EXCHANGE__SETTLEMENT__CORRECT_PROBABILITY", "1.0");

    @DynamicPropertySource
    static void registerProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
        registry.add("spring.datasource.driver-class-name", postgres::getDriverClassName);
        registry.add("trading-demo.simulators.exchange.base-url", SettlementIT::exchangeUrl);
    }

    @Autowired
    private TestRestTemplate restTemplate;

    @Test
    void a_filled_buy_settles_and_a_corrected_amount_changes_settled_cash() {
        buyTwoAcme("ACC-SETTLE", "ORD-SETTLE-1");
        var cost = overview("ACC-SETTLE").pendingCashOut().getAmount().value();

        advanceExchangeClock(4); // past T+1, whatever weekday the clock is on

        await().atMost(TIMEOUT).untilAsserted(() -> {
            var account = overview("ACC-SETTLE");
            assertThat(account.pendingCashOut().getAmount().value()).isEqualByComparingTo("0");
            assertThat(settlementsOf("ACC-SETTLE")).singleElement().satisfies(s -> assertThat(s.state()).isEqualTo(SettlementState.SETTLED));
        });

        advanceExchangeClock(4); // past the correction's business day

        await().atMost(TIMEOUT).untilAsserted(() -> {
            var settlement = settlementsOf("ACC-SETTLE").getFirst();
            assertThat(settlement.amount()).as("the exchange corrected the amount").isNotEqualByComparingTo(cost);
            assertThat(overview("ACC-SETTLE").settledCash().getAmount().value()).isEqualByComparingTo(new BigDecimal("10000.00").subtract(settlement.amount()));
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

    private List<SettlementView> settlementsOf(String accountId) {
        return Arrays.asList(Objects.requireNonNull(operator().getForEntity("/api/admin/settlements?accountId=" + accountId, SettlementView[].class).getBody()));
    }

    private List<SettlementView> exceptions() {
        return Arrays.asList(Objects.requireNonNull(operator().getForEntity("/api/admin/settlements/exceptions", SettlementView[].class).getBody()));
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
