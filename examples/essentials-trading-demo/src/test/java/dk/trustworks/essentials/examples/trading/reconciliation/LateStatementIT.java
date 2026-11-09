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
package dk.trustworks.essentials.examples.trading.reconciliation;

import dk.trustworks.essentials.examples.trading.integration.SimulatorImages;
import dk.trustworks.essentials.examples.trading.integration.exchange_simulator.client.ApiClient;
import dk.trustworks.essentials.examples.trading.integration.exchange_simulator.client.api.TestControlApi;
import dk.trustworks.essentials.examples.trading.integration.exchange_simulator.client.model.ClockAdvance;
import dk.trustworks.essentials.examples.trading.reconciliation.types.CaseState;
import dk.trustworks.essentials.examples.trading.reconciliation.views.cases.CaseView;
import dk.trustworks.essentials.examples.trading.reconciliation.views.clearing_statement_breaks.*;
import dk.trustworks.essentials.examples.trading.reconciliation.views.missing_statements.MissingStatement;
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
 * A late clearing statement (design phase 5 §3, Q-3): the clearinghouse issues it 1 to 60 minutes after the cutoff
 * (seeded at probability 1). With no grace it is missing until it arrives; once it has, nothing is missing and the
 * next close opens no case. The demo's grace of 2 hours never sees one missing.
 * <p>
 * Skipped unless the {@code exchange-simulator:dev} image exists locally (see {@link SimulatorImages}).
 */
@Testcontainers
@EnabledIf("dk.trustworks.essentials.examples.trading.integration.SimulatorImages#exchangeAvailable")
@SpringBootTest(properties = {
        "trading-demo.queue-load.enabled=false",
        "trading-demo.simulators.webhook-secret-file=simulators/secrets/webhook-secret.txt",
        "trading-demo.reconciliation.statement-grace=0s"
}, webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureTestRestTemplate
@DirtiesContext // close while this class's containers are still up; a cached context outlives them
class LateStatementIT {
    private static final Duration TIMEOUT = Duration.ofSeconds(90);

    @Container
    static final PostgreSQLContainer postgres = new PostgreSQLContainer("postgres:18.4")
            .withDatabaseName("trading-demo-late-statement-it-db")
            .withUsername("test")
            .withPassword("test");

    /**
     * Frozen, so the test moves the business days on.
     */
    @Container
    static final GenericContainer<?> exchange = SimulatorImages.exchangeAsTheDemoRunsIt()
                                                              .withEnv("EXCHANGE__CLOCK__RUNNING", "false")
                                                              .withEnv("EXCHANGE__CLEARING__BREAKS__LATE_PROBABILITY", "1.0");

    @DynamicPropertySource
    static void registerProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
        registry.add("spring.datasource.driver-class-name", postgres::getDriverClassName);
        registry.add("trading-demo.simulators.exchange.base-url", LateStatementIT::exchangeUrl);
    }

    @Autowired
    private TestRestTemplate restTemplate;

    @Test
    void a_late_statement_is_missing_until_it_arrives_and_never_becomes_a_case() {
        buyAcme("ACC-LATE", 3, 4);

        // The clock starts on Monday 08:00 New York (simulators/exchange.yaml); Monday's cutoff is 16:30, and the late
        // statement comes at least a minute after it
        testControl().advanceClock(new ClockAdvance().days(0).hours(8).minutes(30).seconds(20));

        await().atMost(TIMEOUT).untilAsserted(() -> assertThat(missingStatements()).singleElement().satisfies(missing -> {
            assertThat(missing.source()).isEqualTo(MissingStatement.Source.CLEARING);
            assertThat(missing.businessDate()).hasToString("2030-01-07");
        }));
        assertThat(statements()).isEmpty();

        testControl().advanceClock(new ClockAdvance().days(0).hours(1).minutes(1).seconds(0)); // past the latest a late statement comes

        await().atMost(TIMEOUT).untilAsserted(() -> {
            assertThat(statements()).singleElement().satisfies(s -> assertThat(breaksOf(s.statementId())).isEmpty());
            assertThat(missingStatements()).isEmpty();
        });
        assertThat(testControl().listClearingBreaks(statements().getFirst().businessDate(), statements().getFirst().participantId()).stream()
                                .flatMap(statementBreaks -> statementBreaks.getBreaks().stream())
                                .map(seededBreak -> seededBreak.getKind().getValue()))
                .containsExactly("LATE_STATEMENT");

        advanceExchangeClock(1); // Tuesday's close

        await().pollDelay(Duration.ofSeconds(5)).atMost(TIMEOUT).untilAsserted(() -> assertThat(cases()).isEmpty());
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
     * An account with 100 000 credited, and market buys of ACME filled at the exchange - several executions on one
     * business day.
     */
    private void buyAcme(String accountId, int... quantities) {
        assertThat(alice().postForEntity("/api/accounts", Map.of("accountId", accountId, "ownerId", "USR-ALICE", "currency", "USD"), Void.class)
                          .getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(operator().postForEntity("/api/admin/accounts/" + accountId + "/deposits",
                                            Map.of("accountId", accountId, "depositId", "DEP-" + accountId, "amount", usd("100000.00"), "businessDate", "2030-01-07"),
                                            String.class).getStatusCode()).isEqualTo(HttpStatus.OK);
        await().atMost(TIMEOUT).until(() -> alice().getForEntity("/api/accounts/" + accountId + "/orders", String.class).getStatusCode() == HttpStatus.OK);
        for (var i = 0; i < quantities.length; i++) {
            var orderId = "ORD-" + accountId + "-" + i;
            var quantity = quantities[i];
            await().atMost(TIMEOUT).untilAsserted(() -> {
                var placed = alice().postForEntity("/api/accounts/" + accountId + "/orders",
                                                   Map.of("orderId", orderId, "accountId", accountId, "instrumentId", "ACME", "side", "BUY", "type", "MARKET", "quantity", quantity),
                                                   String.class);
                assertThat(placed.getStatusCode()).as("%s", placed.getBody()).isEqualTo(HttpStatus.CREATED);
            });
            await().atMost(TIMEOUT).untilAsserted(() -> assertThat(alice().getForEntity("/api/accounts/" + accountId + "/orders/" + orderId, String.class).getBody()).contains("\"FILLED\""));
        }
    }

    private List<ClearingStatementBreaksQuery.StatementSummary> statements() {
        return Arrays.asList(Objects.requireNonNull(operator().getForEntity("/api/admin/reconciliation/clearing-statements", ClearingStatementBreaksQuery.StatementSummary[].class).getBody()));
    }

    private List<ClearingBreak> breaksOf(String statementId) {
        return Arrays.asList(Objects.requireNonNull(operator().getForEntity("/api/admin/reconciliation/clearing-statements/" + statementId + "/breaks", ClearingBreak[].class).getBody()));
    }

    private List<CaseView> cases() {
        return Arrays.asList(Objects.requireNonNull(operator().getForEntity("/api/admin/reconciliation/cases", CaseView[].class).getBody()));
    }

    private List<MissingStatement> missingStatements() {
        return Arrays.asList(Objects.requireNonNull(operator().getForEntity("/api/admin/reconciliation/missing-statements", MissingStatement[].class).getBody()));
    }

    private static TestControlApi testControl() {
        var apiClient = new ApiClient(RestClient.builder().build());
        apiClient.setBasePath(exchangeUrl());
        return new TestControlApi(apiClient);
    }

    private static void advanceExchangeClock(int days) {
        testControl().advanceClock(new ClockAdvance().days(days).hours(0).minutes(0).seconds(0));
    }

    private static String exchangeUrl() {
        return "http://" + exchange.getHost() + ":" + exchange.getMappedPort(8080);
    }
}
