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

package dk.trustworks.essentials.examples.trading.accounts;

import dk.trustworks.essentials.examples.trading.accounts.views.account_activity.ActivityEntry;
import dk.trustworks.essentials.examples.trading.accounts.views.account_overview.AccountOverview;
import dk.trustworks.essentials.examples.trading.accounts.views.positions.PositionView;
import dk.trustworks.essentials.types.*;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.resttestclient.TestRestTemplate;
import org.springframework.boot.resttestclient.autoconfigure.AutoConfigureTestRestTemplate;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.*;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.*;
import org.testcontainers.junit.jupiter.*;
import org.testcontainers.postgresql.PostgreSQLContainer;

import java.math.BigDecimal;
import java.time.Duration;
import java.util.*;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

/**
 * The account ledger end to end over HTTP, as the seeded customer Alice and the operator: open, fund, hold, trade,
 * settle, roll the books, and read it all back through the three views — which also proves the views' versioned deltas
 * add up to what the aggregate decided.
 */
@Testcontainers
@SpringBootTest(properties = {
        "trading-demo.queue-load.enabled=false"
}, webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureTestRestTemplate
@DirtiesContext // close while this class's container is still up; a cached context outlives it and stalls the next context switch
class AccountsIT {
    private static final Duration VIEW_TIMEOUT = Duration.ofSeconds(60);
    private static final String   ACCOUNT      = "ACC-ALICE-1";

    @Container
    static final PostgreSQLContainer postgres = new PostgreSQLContainer("postgres:18.4")
            .withDatabaseName("trading-demo-accounts-it-db")
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
    private TestRestTemplate restTemplate;

    private TestRestTemplate operator() {
        return restTemplate.withBasicAuth("operator@demo.local", "operator-demo-password");
    }

    private TestRestTemplate alice() {
        return restTemplate.withBasicAuth("alice@demo.local", "alice-demo-password");
    }

    private TestRestTemplate bob() {
        return restTemplate.withBasicAuth("bob@demo.local", "bob-demo-password");
    }

    /**
     * Essentials' Money JSON: an object whose amount is a JSON number. A string amount is refused (400).
     */
    private static Map<String, Object> usd(String amount) {
        return Map.of("amount", new BigDecimal(amount), "currency", "USD");
    }

    private void ledger(String path, Map<String, Object> body) {
        var withAccount = new HashMap<>(body);
        withAccount.put("accountId", ACCOUNT);
        var response = operator().postForEntity("/api/admin/accounts/" + ACCOUNT + "/" + path, withAccount, String.class);
        assertThat(response.getStatusCode()).as("%s: %s", path, response.getBody()).isEqualTo(HttpStatus.OK);
    }

    private AccountOverview overview() {
        return alice().getForEntity("/api/accounts/" + ACCOUNT, AccountOverview.class).getBody();
    }

    private static void assertMoney(Money actual, String expected) {
        assertThat(actual.getAmount().value()).as("%s should be %s", actual, expected).isEqualByComparingTo(new BigDecimal(expected));
    }

    @Test
    void a_customer_account_from_opening_to_a_settled_trade_across_a_roll_of_the_books() {
        // A customer opens an account for themselves, and only for themselves
        assertThat(alice().postForEntity("/api/accounts", Map.of("accountId", ACCOUNT, "ownerId", "USR-ALICE", "currency", "USD"), Void.class)
                          .getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(alice().postForEntity("/api/accounts", Map.of("accountId", "ACC-FOR-BOB", "ownerId", "USR-BOB", "currency", "USD"), Void.class)
                          .getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        // Ledger commands are for operators
        assertThat(alice().postForEntity("/api/admin/accounts/" + ACCOUNT + "/deposits",
                                         Map.of("accountId", ACCOUNT, "depositId", "DEP-X", "amount", usd("1"), "businessDate", "2030-01-07"), String.class)
                          .getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);

        ledger("deposits", Map.of("depositId", "DEP-1", "amount", usd("10000"), "businessDate", "2030-01-07"));
        ledger("holds", Map.of("holdId", "ORD-1", "purpose", "ORDER", "amount", usd("1010")));
        // Beyond buying power: refused, not a server error
        var tooMuch = operator().postForEntity("/api/admin/accounts/" + ACCOUNT + "/holds",
                                               Map.of("accountId", ACCOUNT, "holdId", "ORD-2", "purpose", "ORDER", "amount", usd("9000")), String.class);
        assertThat(tooMuch.getStatusCode().value()).isEqualTo(422);

        ledger("trade-bookings", Map.of("executionId", "EX-1", "instrumentId", "ACME", "side", "BUY", "quantity", 10, "price", usd("100.50"),
                                        "tradeDate", "2030-01-07", "settlementDate", "2030-01-08", "holdId", "ORD-1"));
        ledger("hold-releases", Map.of("holdId", "ORD-1"));

        await().atMost(VIEW_TIMEOUT).untilAsserted(() -> {
            var account = overview();
            assertThat(account).isNotNull();
            assertMoney(account.settledCash(), "10000");
            assertMoney(account.pendingCashOut(), "1005");
            assertMoney(account.held(), "0");
            assertMoney(account.buyingPower(), "8995");
            assertMoney(account.withdrawable(), "0");
        });

        // Roll the books with the trade still unsettled: the next generation must carry it and settle it
        assertThat(operator().postForEntity("/api/admin/accounts/" + ACCOUNT + "/generations?nextPeriodId=2030-01-08", null, Void.class)
                             .getStatusCode()).isEqualTo(HttpStatus.OK);
        ledger("trade-settlements", Map.of("executionId", "EX-1"));
        ledger("deposit-clearances", Map.of("depositId", "DEP-1"));

        await().atMost(VIEW_TIMEOUT).untilAsserted(() -> {
            var account = overview();
            assertThat(account.generation()).isEqualTo(2);
            assertMoney(account.settledCash(), "8995");
            assertMoney(account.pendingCashOut(), "0");
            assertMoney(account.buyingPower(), "8995");
            assertMoney(account.withdrawable(), "8995");
        });
        await().atMost(VIEW_TIMEOUT).untilAsserted(() -> {
            var positions = alice().exchange("/api/accounts/" + ACCOUNT + "/positions", HttpMethod.GET, null,
                                             new ParameterizedTypeReference<List<PositionView>>() {}).getBody();
            assertThat(positions).singleElement().satisfies(position -> {
                assertThat(position.settled().value()).isEqualTo(10);
                assertThat(position.pendingIn().value()).isZero();
                assertMoney(position.costBasis(), "1005");
                assertMoney(position.averageCost(), "100.50");
            });
        });
        await().atMost(VIEW_TIMEOUT).untilAsserted(() -> {
            var activity = alice().exchange("/api/accounts/" + ACCOUNT + "/activity", HttpMethod.GET, null,
                                            new ParameterizedTypeReference<List<ActivityEntry>>() {}).getBody();
            assertThat(activity).extracting(ActivityEntry::kind)
                                .containsSubsequence("DEPOSIT_CLEARED", "TRADE_SETTLED", "OPENED", "BOOKS_CLOSED", "HOLD_RELEASED", "TRADE", "HOLD", "DEPOSIT", "OPENED");
        });

        // Somebody else's account does not exist, as far as Bob can tell; an operator sees it
        assertThat(bob().getForEntity("/api/accounts/" + ACCOUNT, String.class).getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(bob().getForEntity("/api/accounts/" + ACCOUNT + "/positions", String.class).getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(operator().getForEntity("/api/accounts/" + ACCOUNT, String.class).getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(alice().exchange("/api/accounts", HttpMethod.GET, null, new ParameterizedTypeReference<List<AccountOverview>>() {}).getBody())
                .extracting(account -> account.accountId().toString()).containsExactly(ACCOUNT);
    }
}
