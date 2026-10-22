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
 * Clearing reconciliation against the real exchange-simulator with its seeded clearing breaks on (design phase 5 §5):
 * trade lines omitted and altered at random, one phantom line per statement. Every statement's breaks map one for one
 * onto the clearinghouse's own list of what it seeded, by kind and execution; at the next close each becomes a case,
 * which an operator then works.
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
class ClearingReconciliationIT {
    private static final Duration TIMEOUT = Duration.ofSeconds(90);

    @Container
    static final PostgreSQLContainer postgres = new PostgreSQLContainer("postgres:18.4")
            .withDatabaseName("trading-demo-clearing-recon-it-db")
            .withUsername("test")
            .withPassword("test");

    /**
     * Frozen, so the test moves the business days on.
     */
    @Container
    static final GenericContainer<?> exchange = SimulatorImages.exchangeAsTheDemoRunsIt()
                                                              .withEnv("EXCHANGE__CLOCK__RUNNING", "false")
                                                              .withEnv("EXCHANGE__CLEARING__BREAKS__OMIT_PROBABILITY", "0.3")
                                                              .withEnv("EXCHANGE__CLEARING__BREAKS__ALTER_PROBABILITY", "0.3")
                                                              .withEnv("EXCHANGE__CLEARING__BREAKS__PHANTOM_PROBABILITY", "1.0");

    @DynamicPropertySource
    static void registerProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
        registry.add("spring.datasource.driver-class-name", postgres::getDriverClassName);
        registry.add("trading-demo.simulators.exchange.base-url", ClearingReconciliationIT::exchangeUrl);
    }

    @Autowired
    private TestRestTemplate restTemplate;

    @Test
    void the_seeded_clearing_breaks_are_found_one_for_one_and_become_cases_at_the_next_close() {
        buyAcme("ACC-CLEAR", 3, 4, 5, 6, 7, 8);

        // The clock starts on Monday 08:00 New York (simulators/exchange.yaml): 12 hours on is past Monday's cutoff and
        // its statement's grace, and before Tuesday's close
        testControl().advanceClock(new ClockAdvance().days(0).hours(12).minutes(0).seconds(0));

        await().atMost(TIMEOUT).untilAsserted(() -> assertThat(statements().stream().flatMap(statement -> breaksOf(statement.statementId()).stream()))
                .anySatisfy(b -> assertThat(b.kind()).isEqualTo(ClearingBreak.Kind.UNKNOWN_TRADE)));

        var expectedCases = new HashSet<String>();
        for (var statement : statements()) {
            var ours = breaksOf(statement.statementId()).stream()
                                                        .map(b -> switch (b.kind()) {
                                                            case UNKNOWN_TRADE -> "PHANTOM_TRADE";
                                                            case MISSING_TRADE -> "OMITTED_TRADE";
                                                            case QUANTITY_MISMATCH -> "QUANTITY_CHANGED";
                                                            case PRICE_MISMATCH -> "PRICE_CHANGED";
                                                            case SIDE_MISMATCH, OBLIGATION_MISMATCH -> b.kind().name();
                                                        } + ":" + b.reference())
                                                        .toList();
            var seeded = testControl().listClearingBreaks(statement.businessDate(), statement.participantId()).stream()
                                      .flatMap(statementBreaks -> statementBreaks.getBreaks().stream())
                                      .map(seededBreak -> seededBreak.getKind().getValue() + ":" + seededBreak.getExecutionId())
                                      .toList();
            assertThat(ours).as("breaks of %s", statement.statementId()).containsExactlyInAnyOrderElementsOf(seeded);
            breaksOf(statement.statementId()).forEach(b -> expectedCases.add(statement.statementId() + ":" + b.kind() + ":" + b.reference()));
        }
        assertThat(cases()).as("no case before the next close").isEmpty();

        advanceExchangeClock(1); // Tuesday's close: the breaks are still there

        await().atMost(TIMEOUT).untilAsserted(() -> assertThat(cases()).extracting(CaseView::caseId).containsExactlyInAnyOrderElementsOf(expectedCases));
        assertThat(missingStatements()).isEmpty();

        var phantom = cases().stream().filter(c -> c.kind().equals("UNKNOWN_TRADE")).findFirst().orElseThrow();
        assertThat(operator().postForEntity("/api/admin/reconciliation/cases/" + phantom.caseId() + "/assignment", Map.of("assignee", "ops-1"), String.class)
                             .getStatusCode()).isEqualTo(HttpStatus.OK);
        var refused = operator().postForEntity("/api/admin/reconciliation/cases/" + phantom.caseId() + "/resolution", Map.of("resolution", "LEDGER_CORRECTED", "note", "booked"), String.class);
        assertThat(refused.getStatusCode()).as("a ledger correction names its adjustment").isEqualTo(HttpStatus.UNPROCESSABLE_CONTENT);
        assertThat(operator().postForEntity("/api/admin/reconciliation/cases/" + phantom.caseId() + "/resolution",
                                            Map.of("resolution", "COUNTERPARTY_ERROR", "note", "no such execution of ours"), String.class)
                             .getStatusCode()).isEqualTo(HttpStatus.OK);

        await().atMost(TIMEOUT).untilAsserted(() -> {
            var resolved = operator().getForEntity("/api/admin/reconciliation/cases/" + phantom.caseId(), CaseView.class).getBody();
            assertThat(resolved).isNotNull();
            assertThat(resolved.state()).isEqualTo(CaseState.RESOLVED);
            assertThat(resolved.assignee()).isEqualTo("ops-1");
            assertThat(resolved.history()).extracting(CaseView.HistoryEntry::event).containsExactly("OPENED", "ASSIGNED", "RESOLVED");
        });

        advanceExchangeClock(1); // a further close leaves a resolved case resolved and opens nothing twice
        await().pollDelay(Duration.ofSeconds(5)).atMost(TIMEOUT).untilAsserted(() -> {
            assertThat(cases()).hasSameSizeAs(expectedCases);
            assertThat(cases()).filteredOn(c -> c.caseId().equals(phantom.caseId())).singleElement().satisfies(c -> assertThat(c.state()).isEqualTo(CaseState.RESOLVED));
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
