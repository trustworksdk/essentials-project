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

package dk.trustworks.essentials.examples.trading.accounts.automations.clear_deposits;

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
import org.springframework.http.HttpStatus;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.*;
import org.springframework.web.client.RestClient;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.*;

import java.math.BigDecimal;
import java.time.Duration;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

/**
 * A deposit becomes withdrawable when the exchange closes the business day its return window ends on: the exchange's
 * {@code BusinessDayClosed} reaches the business calendar (here through the event-log catch-up reader), and
 * {@link DepositClearing} clears what is due. Before that close it is credited but not withdrawable.
 * <p>
 * Skipped unless the {@code exchange-simulator:dev} image exists locally (see {@link SimulatorImages}).
 */
@Testcontainers
@EnabledIf("dk.trustworks.essentials.examples.trading.integration.SimulatorImages#exchangeAvailable")
@SpringBootTest(properties = {
        "trading-demo.queue-load.enabled=false",
        "trading-demo.simulators.webhook-secret-file=simulators/secrets/webhook-secret.txt"
}, webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureTestRestTemplate
@DirtiesContext // close while this class's containers are still up; a cached context outlives them
class ClearDepositsIT {
    private static final Duration TIMEOUT = Duration.ofSeconds(60);
    private static final String   ACCOUNT = "ACC-CLEAR-DEPOSITS";

    @Container
    static final PostgreSQLContainer postgres = new PostgreSQLContainer("postgres:18.4")
            .withDatabaseName("trading-demo-clear-deposits-it-db")
            .withUsername("test")
            .withPassword("test");

    /**
     * Frozen, so no business day closes until the test moves the clock.
     */
    @Container
    static final GenericContainer<?> exchange = SimulatorImages.exchangeAsTheDemoRunsIt().withEnv("EXCHANGE__CLOCK__RUNNING", "false");

    @DynamicPropertySource
    static void registerProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
        registry.add("spring.datasource.driver-class-name", postgres::getDriverClassName);
        registry.add("trading-demo.simulators.exchange.base-url", () -> "http://" + exchange.getHost() + ":" + exchange.getMappedPort(8080));
    }

    @Autowired
    private TestRestTemplate restTemplate;

    @Test
    void a_deposit_becomes_withdrawable_when_the_exchange_closes_the_day_its_return_window_ends() {
        assertThat(alice().postForEntity("/api/accounts", Map.of("accountId", ACCOUNT, "ownerId", "USR-ALICE", "currency", "USD"), Void.class)
                          .getStatusCode()).isEqualTo(HttpStatus.CREATED);
        // Monday 2030-01-07, the exchange's first day (simulators/exchange.yaml); 3 business days -> clears Thursday
        var deposit = operator().postForEntity("/api/admin/accounts/" + ACCOUNT + "/deposits",
                                               Map.of("accountId", ACCOUNT, "depositId", "DEP-CLEAR-1", "amount", usd("1000.00"), "businessDate", "2030-01-07"),
                                               String.class);
        assertThat(deposit.getStatusCode()).as("%s", deposit.getBody()).isEqualTo(HttpStatus.OK);
        await().atMost(TIMEOUT).untilAsserted(() -> assertThat(overview().uncleared().getAmount().value()).isEqualByComparingTo("1000.00"));
        assertThat(overview().withdrawable().getAmount().value()).isEqualByComparingTo("0");

        // The clock starts Monday 13:00 UTC and the cutoff is 16:30 New York (21:30 UTC): three days on closes Monday to
        // Wednesday - not yet
        advanceExchangeClock(3);
        await().during(Duration.ofSeconds(8)).atMost(Duration.ofSeconds(10))
               .untilAsserted(() -> assertThat(overview().uncleared().getAmount().value()).isEqualByComparingTo("1000.00"));

        // A day more closes Thursday
        advanceExchangeClock(1);
        await().atMost(TIMEOUT).untilAsserted(() -> {
            var account = overview();
            assertThat(account.uncleared().getAmount().value()).isEqualByComparingTo("0");
            assertThat(account.withdrawable().getAmount().value()).isEqualByComparingTo("1000.00");
        });
    }

    @Test
    void the_return_window_counts_the_exchanges_business_days_holidays_excluded() {
        var account = "ACC-CLEAR-HOLIDAY";
        assertThat(alice().postForEntity("/api/accounts", Map.of("accountId", account, "ownerId", "USR-ALICE", "currency", "USD"), Void.class)
                          .getStatusCode()).isEqualTo(HttpStatus.CREATED);
        // Friday 2030-01-18; Monday the 21st is a holiday (simulators/exchange.yaml), so 3 business days end Thursday the
        // 24th - weekdays alone would end them Wednesday the 23rd
        assertThat(operator().postForEntity("/api/admin/accounts/" + account + "/deposits",
                                            Map.of("accountId", account, "depositId", "DEP-CLEAR-HOLIDAY", "amount", usd("100.00"), "businessDate", "2030-01-18"),
                                            String.class).getStatusCode()).isEqualTo(HttpStatus.OK);

        await().atMost(TIMEOUT).untilAsserted(() -> assertThat(alice().getForEntity("/api/accounts/" + account + "/activity", String.class).getBody())
                .contains("withdrawable from 2030-01-24"));
    }

    private static void advanceExchangeClock(int days) {
        var apiClient = new ApiClient(RestClient.builder().build());
        apiClient.setBasePath("http://" + exchange.getHost() + ":" + exchange.getMappedPort(8080));
        new TestControlApi(apiClient).advanceClock(new ClockAdvance().days(days).hours(0).minutes(0).seconds(0));
    }

    private AccountOverview overview() {
        var account = alice().getForEntity("/api/accounts/" + ACCOUNT, AccountOverview.class).getBody();
        assertThat(account).as("account %s not projected yet", ACCOUNT).isNotNull();
        return account;
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
}
