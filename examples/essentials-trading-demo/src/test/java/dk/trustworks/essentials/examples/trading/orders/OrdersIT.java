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

package dk.trustworks.essentials.examples.trading.orders;

import dk.trustworks.essentials.examples.trading.accounts.views.account_overview.AccountOverview;
import dk.trustworks.essentials.examples.trading.orders.types.OrderStatus;
import dk.trustworks.essentials.examples.trading.orders.views.orders.OrderView;
import dk.trustworks.essentials.types.Money;
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
import java.util.concurrent.*;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

/**
 * The order core against the stub exchange, over HTTP as the seeded customer Alice: an order takes its hold with it,
 * fills are booked on the account and what is left of the hold is released when the order closes. {@code RESTING}
 * orders are accepted and never fill, for the cancel path and the buying-power race.
 */
@Testcontainers
@SpringBootTest(properties = {
        "trading-demo.simulation.enabled=false",
        "trading-demo.load.enabled=false",
        "trading-demo.queue-load.enabled=false",
        "trading-demo.orders.stub-exchange.resting-instruments=RESTING",
        "trading-demo.orders.stub-exchange.rejected-instruments=HALTED"
}, webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureTestRestTemplate
@DirtiesContext // close while this class's container is still up; a cached context outlives it and stalls the next context switch
class OrdersIT {
    private static final Duration TIMEOUT = Duration.ofSeconds(60);

    @Container
    static final PostgreSQLContainer postgres = new PostgreSQLContainer("postgres:18.4")
            .withDatabaseName("trading-demo-orders-it-db")
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

    private static Map<String, Object> usd(String amount) {
        return Map.of("amount", new BigDecimal(amount), "currency", "USD");
    }

    private static void assertMoney(Money actual, String expected) {
        assertThat(actual.getAmount().value()).as("%s should be %s", actual, expected).isEqualByComparingTo(new BigDecimal(expected));
    }

    /**
     * Opens an account for Alice, funds it, and waits until order entry knows she owns it.
     */
    private void openFundedAccount(String accountId, String cash) {
        assertThat(alice().postForEntity("/api/accounts", Map.of("accountId", accountId, "ownerId", "USR-ALICE", "currency", "USD"), Void.class)
                          .getStatusCode()).isEqualTo(HttpStatus.CREATED);
        var deposit = operator().postForEntity("/api/admin/accounts/" + accountId + "/deposits",
                                               Map.of("accountId", accountId, "depositId", "DEP-" + accountId, "amount", usd(cash), "businessDate", "2030-01-07"),
                                               String.class);
        assertThat(deposit.getStatusCode()).as("%s", deposit.getBody()).isEqualTo(HttpStatus.OK);
        await().atMost(TIMEOUT).until(() -> alice().getForEntity("/api/accounts/" + accountId + "/orders", String.class).getStatusCode() == HttpStatus.OK);
    }

    private ResponseEntity<String> placeOrder(TestRestTemplate as, String accountId, String orderId, String instrument, String side, long quantity, String limit) {
        return as.postForEntity("/api/accounts/" + accountId + "/orders",
                                Map.of("orderId", orderId, "accountId", accountId, "instrumentId", instrument, "side", side, "type", "LIMIT",
                                       "quantity", quantity, "limitPrice", usd(limit)),
                                String.class);
    }

    private OrderView order(String accountId, String orderId) {
        return alice().getForEntity("/api/accounts/" + accountId + "/orders/" + orderId, OrderView.class).getBody();
    }

    /**
     * For an await: the order view is eventually consistent, and an assertion error is what makes the await retry.
     */
    private OrderView projectedOrder(String accountId, String orderId) {
        var order = order(accountId, orderId);
        assertThat(order).as("order %s not projected yet", orderId).isNotNull();
        return order;
    }

    private AccountOverview overview(String accountId) {
        return alice().getForEntity("/api/accounts/" + accountId, AccountOverview.class).getBody();
    }

    @Test
    void a_limit_buy_is_filled_by_the_exchange_and_booked_on_the_account() {
        openFundedAccount("ACC-ORD-FILL", "10000");

        var placed = placeOrder(alice(), "ACC-ORD-FILL", "ORD-FILL-1", "ACME", "BUY", 10, "101");
        assertThat(placed.getStatusCode()).as("%s", placed.getBody()).isEqualTo(HttpStatus.CREATED);
        // The same orderId again places nothing
        assertThat(placeOrder(alice(), "ACC-ORD-FILL", "ORD-FILL-1", "ACME", "BUY", 10, "101").getStatusCode()).isEqualTo(HttpStatus.CREATED);

        await().atMost(TIMEOUT).untilAsserted(() -> {
            var order = projectedOrder("ACC-ORD-FILL", "ORD-FILL-1");
            assertThat(order.status()).isEqualTo(OrderStatus.FILLED);
            assertThat(order.filledQuantity().value()).isEqualTo(10L);
            assertThat((Object) order.exchangeOrderId()).isNotNull();

            var account = overview("ACC-ORD-FILL");
            assertMoney(account.pendingCashOut(), "1010");
            assertMoney(account.held(), "0");
            assertMoney(account.buyingPower(), "8990");
        });
        assertThat(alice().exchange("/api/accounts/ACC-ORD-FILL/orders", HttpMethod.GET, null, new ParameterizedTypeReference<List<OrderView>>() {})
                          .getBody()).hasSize(1);
    }

    @Test
    void a_cancelled_order_gives_its_hold_back() {
        openFundedAccount("ACC-ORD-CANCEL", "5000");

        assertThat(placeOrder(alice(), "ACC-ORD-CANCEL", "ORD-CANCEL-1", "RESTING", "BUY", 10, "100").getStatusCode()).isEqualTo(HttpStatus.CREATED);
        await().atMost(TIMEOUT).untilAsserted(() -> {
            assertThat(projectedOrder("ACC-ORD-CANCEL", "ORD-CANCEL-1").status()).isEqualTo(OrderStatus.OPEN);
            assertMoney(overview("ACC-ORD-CANCEL").held(), "1000");
        });

        assertThat(alice().postForEntity("/api/accounts/ACC-ORD-CANCEL/orders/ORD-CANCEL-1/cancellation", null, Void.class).getStatusCode())
                .isEqualTo(HttpStatus.ACCEPTED);

        await().atMost(TIMEOUT).untilAsserted(() -> {
            assertThat(projectedOrder("ACC-ORD-CANCEL", "ORD-CANCEL-1").status()).isEqualTo(OrderStatus.CANCELLED);
            var account = overview("ACC-ORD-CANCEL");
            assertMoney(account.held(), "0");
            assertMoney(account.buyingPower(), "5000");
        });
        // A closed order cannot be cancelled again
        assertThat(alice().postForEntity("/api/accounts/ACC-ORD-CANCEL/orders/ORD-CANCEL-1/cancellation", null, String.class).getStatusCode().value())
                .isEqualTo(422);
    }

    @Test
    void an_order_beyond_buying_power_or_holdings_is_refused_and_nothing_is_placed() {
        openFundedAccount("ACC-ORD-REFUSED", "500");

        assertThat(placeOrder(alice(), "ACC-ORD-REFUSED", "ORD-REFUSED-1", "ACME", "BUY", 10, "100").getStatusCode().value()).isEqualTo(422);
        assertThat(placeOrder(alice(), "ACC-ORD-REFUSED", "ORD-REFUSED-2", "ACME", "SELL", 1, "100").getStatusCode().value()).isEqualTo(422);
        var market = alice().postForEntity("/api/accounts/ACC-ORD-REFUSED/orders",
                                           Map.of("orderId", "ORD-REFUSED-3", "accountId", "ACC-ORD-REFUSED", "instrumentId", "ACME", "side", "BUY",
                                                  "type", "MARKET", "quantity", 1),
                                           String.class);
        assertThat(market.getStatusCode().value()).isEqualTo(422);
        // Somebody else's account is not found
        assertThat(placeOrder(bob(), "ACC-ORD-REFUSED", "ORD-REFUSED-4", "ACME", "BUY", 1, "1").getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);

        assertThat(order("ACC-ORD-REFUSED", "ORD-REFUSED-1")).isNull();
        assertThat(order("ACC-ORD-REFUSED", "ORD-REFUSED-2")).isNull();
    }

    @Test
    void an_order_the_exchange_rejects_gives_its_hold_back() {
        openFundedAccount("ACC-ORD-REJECT", "1000");

        assertThat(placeOrder(alice(), "ACC-ORD-REJECT", "ORD-REJECT-1", "HALTED", "BUY", 5, "100").getStatusCode()).isEqualTo(HttpStatus.CREATED);
        await().atMost(TIMEOUT).untilAsserted(() -> {
            var order = projectedOrder("ACC-ORD-REJECT", "ORD-REJECT-1");
            assertThat(order.status()).isEqualTo(OrderStatus.REJECTED);
            assertThat(order.reason()).isEqualTo("INSTRUMENT_NOT_TRADABLE");
            assertMoney(overview("ACC-ORD-REJECT").held(), "0");
        });
    }

    /**
     * The account lock that is not needed: eight orders race for buying power that covers one. The account stream's
     * optimistic append lets one through; each loser is decided again against the new balance and refused.
     */
    @Test
    void concurrent_orders_cannot_spend_the_same_buying_power_twice() throws Exception {
        openFundedAccount("ACC-ORD-RACE", "1000");

        var contenders = 8;
        var start      = new CountDownLatch(1);
        var pool       = Executors.newFixedThreadPool(contenders);
        try {
            var answers = new ArrayList<Future<Integer>>();
            for (var i = 0; i < contenders; i++) {
                var orderId = "ORD-RACE-" + i;
                answers.add(pool.submit(() -> {
                    start.await();
                    return placeOrder(alice(), "ACC-ORD-RACE", orderId, "RESTING", "BUY", 10, "100").getStatusCode().value();
                }));
            }
            start.countDown();
            var statuses = new ArrayList<Integer>();
            for (var answer : answers) {
                statuses.add(answer.get(60, TimeUnit.SECONDS));
            }
            assertThat(statuses).as("%s", statuses).isSubsetOf(201, 422, 409);
            assertThat(statuses.stream().filter(status -> status == 201).count()).as("%s", statuses).isEqualTo(1);
        } finally {
            pool.shutdownNow();
        }

        await().atMost(TIMEOUT).untilAsserted(() -> {
            assertMoney(overview("ACC-ORD-RACE").held(), "1000");
            assertMoney(overview("ACC-ORD-RACE").buyingPower(), "0");
            assertThat(alice().exchange("/api/accounts/ACC-ORD-RACE/orders", HttpMethod.GET, null, new ParameterizedTypeReference<List<OrderView>>() {})
                              .getBody()).hasSize(1);
        });
    }
}
