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

import dk.trustworks.essentials.examples.trading.accounts.types.*;
import dk.trustworks.essentials.examples.trading.accounts.views.account_overview.AccountOverview;
import dk.trustworks.essentials.examples.trading.orders.external_systems.exchange.ExchangeReports;
import dk.trustworks.essentials.examples.trading.orders.types.*;
import dk.trustworks.essentials.examples.trading.orders.use_cases.record_fill.RecordFill;
import dk.trustworks.essentials.examples.trading.orders.views.orders.OrderView;
import dk.trustworks.essentials.examples.trading.orders.views.unknown_exchange_orders.UnknownExchangeFill;
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
import java.time.*;
import java.util.*;
import java.util.concurrent.*;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

/**
 * The order core against the stub exchange, over HTTP as the seeded customer Alice: an order takes its hold with it,
 * fills are booked on the account and what is left of the hold is released when the order closes. The stub fills at
 * the synthetic feed's quote for {@code ACME}, which is held still. {@code RESTING}
 * orders are accepted and never fill, for the cancel and expiry paths and the buying-power race; {@code HALTED} orders
 * are refused by the exchange, and {@code DOWN} orders never reach it.
 */
@Testcontainers
@SpringBootTest(properties = {
        "trading-demo.simulation.enabled=false",
        "trading-demo.load.enabled=false",
        "trading-demo.queue-load.enabled=false",
        "trading-demo.orders.stub-exchange.resting-instruments=RESTING",
        "trading-demo.orders.stub-exchange.rejected-instruments=HALTED",
        "trading-demo.orders.stub-exchange.unreachable-instruments=DOWN",
        "trading-demo.orders.exchange-submission.initial-backoff=10ms",
        // ACME quoted still at bid 99.95 / ask 100.05, so fill prices are exact
        "trading-demo.market-data.synthetic.instruments.ACME=100.00",
        "trading-demo.market-data.synthetic.volatility=0",
        "trading-demo.market-data.synthetic.tick-interval=200ms"
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
    @Autowired
    private ExchangeReports  exchangeReports;

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
    void a_limit_buy_fills_at_the_ask_and_the_rest_of_its_hold_comes_back() {
        openFundedAccount("ACC-ORD-FILL", "10000");

        var placed = placeOrder(alice(), "ACC-ORD-FILL", "ORD-FILL-1", "ACME", "BUY", 10, "101");
        assertThat(placed.getStatusCode()).as("%s", placed.getBody()).isEqualTo(HttpStatus.CREATED);
        // The same orderId again places nothing
        assertThat(placeOrder(alice(), "ACC-ORD-FILL", "ORD-FILL-1", "ACME", "BUY", 10, "101").getStatusCode()).isEqualTo(HttpStatus.CREATED);

        // Held 10 x 101 = 1010; filled 10 @ 100.05 = 1000.50; the remaining 9.50 released
        await().atMost(TIMEOUT).untilAsserted(() -> {
            var order = projectedOrder("ACC-ORD-FILL", "ORD-FILL-1");
            assertThat(order.status()).isEqualTo(OrderStatus.FILLED);
            assertThat(order.filledQuantity().value()).isEqualTo(10L);
            assertThat((Object) order.exchangeOrderId()).isNotNull();
            assertMoney(order.averageFillPrice(), "100.05");

            var account = overview("ACC-ORD-FILL");
            assertMoney(account.pendingCashOut(), "1000.50");
            assertMoney(account.held(), "0");
            assertMoney(account.buyingPower(), "8999.50");
        });
        assertThat(alice().exchange("/api/accounts/ACC-ORD-FILL/orders", HttpMethod.GET, null, new ParameterizedTypeReference<List<OrderView>>() {})
                          .getBody()).hasSize(1);
    }

    @Test
    void a_market_buy_holds_the_ask_plus_the_collar_and_fills_at_the_ask() {
        openFundedAccount("ACC-ORD-MARKET", "1950");
        await().atMost(TIMEOUT).until(() -> alice().getForEntity("/api/market-data/quotes/ACME", String.class).getStatusCode() == HttpStatus.OK);

        // 19 at the ask would cost 1900.95, which 1950 covers; but the hold is 19 x 100.05 x 1.05 = 1996.00, which it does not
        var tooBig = alice().postForEntity("/api/accounts/ACC-ORD-MARKET/orders", market("ORD-MARKET-0", "ACC-ORD-MARKET", "ACME", "BUY", 19), String.class);
        assertThat(tooBig.getStatusCode().value()).as("%s", tooBig.getBody()).isEqualTo(422);

        // 10 holds 1050.53 (1050.525 rounded up), and fills at 100.05
        var placed = alice().postForEntity("/api/accounts/ACC-ORD-MARKET/orders", market("ORD-MARKET-1", "ACC-ORD-MARKET", "ACME", "BUY", 10), String.class);
        assertThat(placed.getStatusCode()).as("%s", placed.getBody()).isEqualTo(HttpStatus.CREATED);

        await().atMost(TIMEOUT).untilAsserted(() -> {
            assertThat(projectedOrder("ACC-ORD-MARKET", "ORD-MARKET-1").status()).isEqualTo(OrderStatus.FILLED);
            var account = overview("ACC-ORD-MARKET");
            assertMoney(account.pendingCashOut(), "1000.50");
            assertMoney(account.held(), "0");
            assertMoney(account.buyingPower(), "949.50");
        });
    }

    private static Map<String, Object> market(String orderId, String accountId, String instrument, String side, long quantity) {
        return Map.of("orderId", orderId, "accountId", accountId, "instrumentId", instrument, "side", side, "type", "MARKET", "quantity", quantity);
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
        // A market order on an instrument with no current quote cannot be sized
        var unquoted = alice().postForEntity("/api/accounts/ACC-ORD-REFUSED/orders", market("ORD-REFUSED-3", "ACC-ORD-REFUSED", "UNQUOTED", "BUY", 1), String.class);
        assertThat(unquoted.getStatusCode().value()).isEqualTo(422);
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

    @Test
    void an_order_the_exchange_cannot_be_reached_for_is_rejected_after_the_retry_budget_and_gives_its_hold_back() {
        openFundedAccount("ACC-ORD-DOWN", "1000");

        assertThat(placeOrder(alice(), "ACC-ORD-DOWN", "ORD-DOWN-1", "DOWN", "BUY", 5, "100").getStatusCode()).isEqualTo(HttpStatus.CREATED);
        await().atMost(TIMEOUT).untilAsserted(() -> {
            var order = projectedOrder("ACC-ORD-DOWN", "ORD-DOWN-1");
            assertThat(order.status()).isEqualTo(OrderStatus.REJECTED);
            assertThat(order.reason()).isEqualTo("EXCHANGE_UNAVAILABLE");
            assertThat((Object) order.exchangeOrderId()).isNull();
            var account = overview("ACC-ORD-DOWN");
            assertMoney(account.held(), "0");
            assertMoney(account.buyingPower(), "1000");
        });
    }

    /**
     * Until the exchange expires its own DAY orders (SR-17), the end of the business day makes the brokerage cancel every
     * open DAY order at the exchange; a GTC order stays.
     */
    @Test
    void the_end_of_the_business_day_expires_open_day_orders_and_gives_their_hold_back() {
        openFundedAccount("ACC-ORD-EXPIRY", "5000");

        assertThat(placeOrder(alice(), "ACC-ORD-EXPIRY", "ORD-EXPIRY-DAY", "RESTING", "BUY", 10, "100").getStatusCode()).isEqualTo(HttpStatus.CREATED);
        var gtc = alice().postForEntity("/api/accounts/ACC-ORD-EXPIRY/orders",
                                        Map.of("orderId", "ORD-EXPIRY-GTC", "accountId", "ACC-ORD-EXPIRY", "instrumentId", "RESTING", "side", "BUY", "type", "LIMIT",
                                               "quantity", 5, "limitPrice", usd("100"), "timeInForce", "GTC"),
                                        String.class);
        assertThat(gtc.getStatusCode()).as("%s", gtc.getBody()).isEqualTo(HttpStatus.CREATED);
        await().atMost(TIMEOUT).untilAsserted(() -> {
            assertThat(projectedOrder("ACC-ORD-EXPIRY", "ORD-EXPIRY-DAY").status()).isEqualTo(OrderStatus.OPEN);
            assertThat(projectedOrder("ACC-ORD-EXPIRY", "ORD-EXPIRY-GTC").status()).isEqualTo(OrderStatus.OPEN);
            assertMoney(overview("ACC-ORD-EXPIRY").held(), "1500");
        });

        var closed = operator().postForEntity("/api/admin/orders/business-day-closed", Map.of("businessDate", "2030-01-07"), String.class);
        assertThat(closed.getStatusCode()).as("%s", closed.getBody()).isEqualTo(HttpStatus.ACCEPTED);

        await().atMost(TIMEOUT).untilAsserted(() -> {
            var expired = projectedOrder("ACC-ORD-EXPIRY", "ORD-EXPIRY-DAY");
            assertThat(expired.status()).isEqualTo(OrderStatus.EXPIRED);
            assertThat(expired.reason()).isEqualTo("END_OF_DAY 2030-01-07");
            assertMoney(overview("ACC-ORD-EXPIRY").held(), "500");
        });
        assertThat(projectedOrder("ACC-ORD-EXPIRY", "ORD-EXPIRY-GTC").status()).isEqualTo(OrderStatus.OPEN);
        // Customers cannot end the day
        assertThat(alice().postForEntity("/api/admin/orders/business-day-closed", Map.of("businessDate", "2030-01-07"), String.class).getStatusCode())
                .isEqualTo(HttpStatus.FORBIDDEN);
    }

    @Test
    void a_fill_for_an_order_the_brokerage_does_not_know_becomes_an_operator_case_once() {
        var fill = new RecordFill(OrderId.of("ORD-NOBODY-1"), ExecutionId.of("EXE-NOBODY-1"), Quantity.of(7), Money.of("42.10", "USD"),
                                  LocalDate.of(2030, 1, 7), LocalDate.of(2030, 1, 8));
        exchangeReports.received(fill);
        exchangeReports.received(fill); // the exchange's copy of the same execution

        await().atMost(TIMEOUT).untilAsserted(() -> {
            var cases = operator().exchange("/api/admin/orders/unknown-exchange-fills", HttpMethod.GET, null,
                                            new ParameterizedTypeReference<List<UnknownExchangeFill>>() {}).getBody();
            assertThat(cases).filteredOn(c -> c.orderId().equals(OrderId.of("ORD-NOBODY-1")))
                             .singleElement()
                             .satisfies(c -> {
                                 assertThat((Object) c.executionId()).isEqualTo(ExecutionId.of("EXE-NOBODY-1"));
                                 assertThat(c.quantity()).isEqualTo(Quantity.of(7));
                                 assertMoney(c.price(), "42.10");
                             });
        });
        assertThat(alice().getForEntity("/api/admin/orders/unknown-exchange-fills", String.class).getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
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
