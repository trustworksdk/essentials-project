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
package dk.trustworks.essentials.examples.trading;

import dk.trustworks.essentials.components.eventlog.processor.*;
import dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.processor.*;
import dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.subscription.EventStoreSubscriptionManager;
import dk.trustworks.essentials.examples.trading.accounts.types.*;
import dk.trustworks.essentials.examples.trading.accounts.views.account_activity.*;
import dk.trustworks.essentials.examples.trading.accounts.views.account_overview.*;
import dk.trustworks.essentials.examples.trading.accounts.views.uncleared_deposits.UnclearedDepositsProjection;
import dk.trustworks.essentials.examples.trading.funding.types.*;
import dk.trustworks.essentials.examples.trading.funding.use_cases.link_bank_account.LinkBankAccount;
import dk.trustworks.essentials.examples.trading.funding.use_cases.request_deposit.RequestDeposit;
import dk.trustworks.essentials.examples.trading.identity.views.users.UsersEventLogView;
import dk.trustworks.essentials.examples.trading.orders.types.OrderStatus;
import dk.trustworks.essentials.examples.trading.orders.views.orders.OrderView;
import dk.trustworks.essentials.reactive.command.CommandBus;
import dk.trustworks.essentials.types.Money;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.resttestclient.TestRestTemplate;
import org.springframework.boot.resttestclient.autoconfigure.AutoConfigureTestRestTemplate;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.*;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.*;
import org.testcontainers.junit.jupiter.*;
import org.testcontainers.postgresql.PostgreSQLContainer;

import javax.sql.DataSource;
import java.math.BigDecimal;
import java.sql.SQLException;
import java.time.*;
import java.util.*;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

/**
 * The whole demo on the new event store engine, nothing mocked: the {@code event-log} profile starts the full
 * application context, and one customer journey runs end to end through all three processor paths - a funded account
 * places a limit buy that the stub exchange fills.
 * <ul>
 *     <li>reaction (event log -> forwarder -> inbox): the requested deposit reaches the stub payment gateway and is
 *         credited to the account, and the order is submitted to the exchange and its fill booked;</li>
 *     <li>view (page transaction): the order view and the uncleared-deposits view;</li>
 *     <li>partitioned view (event log -> forwarder -> keyed durable queue): the account overview and the statement.</li>
 * </ul>
 * And nothing of the classic engine's processing runs: no classic processor bean, no {@link EventStoreSubscriptionManager}.
 * A view rebuild clears its table through {@code onRebuild} and replays it, as the classic twin's reset does.
 */
@Testcontainers
@SpringBootTest(properties = {
        "trading-demo.queue-load.enabled=false",
        // ACME quoted still at bid 99.95 / ask 100.05, so fill prices are exact
        "trading-demo.market-data.synthetic.instruments.ACME=100.00",
        "trading-demo.market-data.synthetic.volatility=0",
        "trading-demo.market-data.synthetic.tick-interval=200ms"
}, webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureTestRestTemplate
@ActiveProfiles("event-log")
@DirtiesContext // close while this class's container is still up; a cached context outlives it and stalls the next context switch
class TradingDemoEventLogIT {
    private static final Duration TIMEOUT = Duration.ofSeconds(60);
    private static final String   ACCOUNT = "ACC-EL-JOURNEY";
    private static final String   ORDER   = "ORD-EL-JOURNEY";

    @Container
    static final PostgreSQLContainer postgres = new PostgreSQLContainer("postgres:18.4")
            .withDatabaseName("trading-demo-event-log-it-db")
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
    private TestRestTemplate            restTemplate;
    @Autowired
    private CommandBus                  commandBus;
    @Autowired
    private UnclearedDepositsProjection unclearedDeposits;
    @Autowired
    private ApplicationContext          context;
    @Autowired
    private UsersEventLogView           usersView;
    @Autowired
    private DataSource                  dataSource;

    private TestRestTemplate alice() {
        return restTemplate.withBasicAuth("alice@demo.local", "alice-demo-password");
    }

    private static Map<String, Object> usd(String amount) {
        return Map.of("amount", new BigDecimal(amount), "currency", "USD");
    }

    private static void assertMoney(Money actual, String expected) {
        assertThat(actual.getAmount().value()).as("%s should be %s", actual, expected).isEqualByComparingTo(new BigDecimal(expected));
    }

    @Test
    void the_whole_demo_runs_on_the_event_log_engine() throws SQLException {
        // Only new-engine processing is wired
        assertThat(context.getBeansOfType(AbstractEventProcessor.class)).as("classic processors").isEmpty();
        assertThat(context.getBeanNamesForType(EventStoreSubscriptionManager.class)).as("EventStoreSubscriptionManager beans").isEmpty();
        assertThat(context.getBeansOfType(EventLogViewProcessor.class)).as("event-log views").isNotEmpty();
        assertThat(context.getBeansOfType(EventLogPartitionedViewProcessor.class).values())
                .extracting(EventLogPartitionedViewProcessor::name)
                .containsExactlyInAnyOrder("AccountOverviewProjection", "AccountActivityProjection");
        assertThat(context.getBean(AccountOverviewEventLogView.class).isStarted()).isTrue();
        assertThat(context.getBean(AccountActivityEventLogView.class).isStarted()).isTrue();

        // Open an account for Alice, link her bank account and request a deposit: the payment gateway (stub) completes it at once
        assertThat(alice().postForEntity("/api/accounts", Map.of("accountId", ACCOUNT, "ownerId", "USR-ALICE", "currency", "USD"), Void.class)
                          .getStatusCode()).isEqualTo(HttpStatus.CREATED);
        commandBus.send(new LinkBankAccount(AccountId.of(ACCOUNT), new BankAccount("GB82WEST12345698765432", "Alice Customer")));
        commandBus.send(new RequestDeposit(DepositId.of("DEP-EL-JOURNEY"), AccountId.of(ACCOUNT), Money.of("10000", "USD")));

        // Reaction path: PaymentRouter -> gateway -> FundingLedger credited the deposit; view path: it is held uncleared
        await().atMost(TIMEOUT).untilAsserted(() -> assertThat(unclearedDeposits.clearingOnOrBefore(LocalDate.now().plusYears(1)))
                .extracting(UnclearedDepositsProjection.UnclearedDeposit::depositId)
                .contains(DepositId.of("DEP-EL-JOURNEY")));
        // Partitioned path: the overview has the uncleared deposit
        await().atMost(TIMEOUT).untilAsserted(() -> {
            var account = overview();
            assertThat(account).as("account overview not projected yet").isNotNull();
            assertMoney(account.uncleared(), "10000");
            assertMoney(account.buyingPower(), "10000");
        });
        // Order entry knows Alice owns the account once its own view has it
        await().atMost(TIMEOUT).until(() -> alice().getForEntity("/api/accounts/" + ACCOUNT + "/orders", String.class).getStatusCode() == HttpStatus.OK);

        // A limit buy of 10 ACME at 101: held 1010, filled at the ask 100.05 = 1000.50, the remaining 9.50 released
        var placed = alice().postForEntity("/api/accounts/" + ACCOUNT + "/orders",
                                           Map.of("orderId", ORDER, "accountId", ACCOUNT, "instrumentId", "ACME", "side", "BUY", "type", "LIMIT",
                                                  "quantity", 10, "limitPrice", usd("101")),
                                           String.class);
        assertThat(placed.getStatusCode()).as("%s", placed.getBody()).isEqualTo(HttpStatus.CREATED);

        await().atMost(TIMEOUT).untilAsserted(() -> {
            var order = alice().getForEntity("/api/accounts/" + ACCOUNT + "/orders/" + ORDER, OrderView.class).getBody();
            assertThat(order).as("order not projected yet").isNotNull();
            assertThat(order.status()).isEqualTo(OrderStatus.FILLED);
            assertMoney(order.averageFillPrice(), "100.05");
            var account = overview();
            assertMoney(account.pendingCashOut(), "1000.50");
            assertMoney(account.held(), "0");
            assertMoney(account.buyingPower(), "8999.50");
        });
        await().atMost(TIMEOUT).untilAsserted(() -> {
            var activity = alice().exchange("/api/accounts/" + ACCOUNT + "/activity", HttpMethod.GET, null, new ParameterizedTypeReference<List<ActivityEntry>>() {})
                                  .getBody();
            assertThat(activity).extracting(ActivityEntry::kind).contains("OPENED", "DEPOSIT", "HOLD", "TRADE");
            // One row per event, in the account stream's order
            assertThat(activity).extracting(ActivityEntry::eventOrder).doesNotHaveDuplicates();
        });

        // A partitioned view's rebuild truncates its table, purges its queue and replays: the stale row goes, the account's rows return
        try (var connection = dataSource.getConnection(); var statement = connection.createStatement()) {
            statement.executeUpdate("INSERT INTO projection_account_activity (account_stream_id, event_order, account_id, generation, kind, description)"
                                    + " VALUES ('ACC-STALE', 0, 'ACC-STALE', 0, 'OPENED', 'stale')");
        }
        var activityView = context.getBean(AccountActivityEventLogView.class);
        assertThat(activityView.rebuild()).isTrue();
        assertThat(activityView.isStarted()).isTrue();
        await().atMost(TIMEOUT).untilAsserted(() -> {
            assertThat(activityAccounts()).doesNotContain("ACC-STALE");
            var activity = alice().exchange("/api/accounts/" + ACCOUNT + "/activity", HttpMethod.GET, null, new ParameterizedTypeReference<List<ActivityEntry>>() {})
                                  .getBody();
            assertThat(activity).extracting(ActivityEntry::kind).contains("OPENED", "DEPOSIT", "HOLD", "TRADE");
            assertThat(activity).extracting(ActivityEntry::eventOrder).doesNotHaveDuplicates();
        });
    }

    private Set<String> activityAccounts() throws SQLException {
        var accounts = new TreeSet<String>();
        try (var connection = dataSource.getConnection(); var statement = connection.createStatement();
             var rows = statement.executeQuery("SELECT DISTINCT account_stream_id FROM projection_account_activity")) {
            while (rows.next()) {
                accounts.add(rows.getString(1));
            }
        }
        return accounts;
    }

    private AccountOverview overview() {
        return alice().getForEntity("/api/accounts/" + ACCOUNT, AccountOverview.class).getBody();
    }

    @Test
    void a_view_rebuild_clears_its_table_and_replays_it() throws SQLException {
        // The seeded users are projected; a row no event wrote stands in for stale state
        await().atMost(TIMEOUT).until(() -> users().contains("USR-ALICE"));
        try (var connection = dataSource.getConnection(); var statement = connection.createStatement()) {
            statement.executeUpdate("INSERT INTO projection_users (user_id, email, display_name, roles) VALUES ('USR-STALE', 'stale@demo.local', 'Stale', '')");
        }

        assertThat(usersView.rebuild()).isTrue();

        assertThat(usersView.isStarted()).isTrue();
        await().atMost(TIMEOUT).untilAsserted(() -> assertThat(users()).contains("USR-ALICE", "USR-BOB").doesNotContain("USR-STALE"));
    }

    private Set<String> users() throws SQLException {
        var users = new TreeSet<String>();
        try (var connection = dataSource.getConnection(); var statement = connection.createStatement();
             var rows = statement.executeQuery("SELECT user_id FROM projection_users")) {
            while (rows.next()) {
                users.add(rows.getString(1));
            }
        }
        return users;
    }
}
