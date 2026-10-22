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

import dk.trustworks.essentials.examples.trading.accounts.views.account_overview.AccountOverview;
import dk.trustworks.essentials.examples.trading.integration.SimulatorImages;
import dk.trustworks.essentials.examples.trading.integration.payment_gateway.client.ApiClient;
import dk.trustworks.essentials.examples.trading.integration.payment_gateway.client.api.TestControlApi;
import dk.trustworks.essentials.examples.trading.integration.payment_gateway.client.model.ClockAdvance;
import dk.trustworks.essentials.examples.trading.integration.payment_gateway.client.model.ForcePaymentOutcomeRequest;
import dk.trustworks.essentials.examples.trading.funding.views.payments.PaymentView;
import dk.trustworks.essentials.examples.trading.reconciliation.automations.open_cases.CaseOpening;
import dk.trustworks.essentials.examples.trading.reconciliation.views.bank_statement_breaks.*;
import dk.trustworks.essentials.examples.trading.reconciliation.views.cases.CaseView;
import dk.trustworks.essentials.examples.trading.integration.payment_gateway.client.model.StatementBreaks;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIf;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.resttestclient.TestRestTemplate;
import org.springframework.boot.resttestclient.autoconfigure.AutoConfigureTestRestTemplate;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.*;
import org.springframework.web.client.RestClient;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.*;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.*;
import org.testcontainers.postgresql.PostgreSQLContainer;

import java.math.BigDecimal;
import java.time.Duration;
import java.util.*;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

/**
 * The first reconciliation against the real payment-gateway simulator, with its seeded statement breaks on: every
 * entry's amount altered by one minor unit and one phantom entry per statement. A deposit is completed at the gateway
 * and its business day closed there; the statement then shows both breaks, and they match the gateway's own list of
 * what it seeded, one for one. At the next close each becomes a case - driven here directly, since there is no
 * exchange to close the day.
 * <p>
 * Skipped unless the {@code payment-gateway:dev} image exists locally (see {@link SimulatorImages}).
 */
@Testcontainers
@EnabledIf("dk.trustworks.essentials.examples.trading.integration.SimulatorImages#paymentGatewayAvailable")
@SpringBootTest(properties = {
        "trading-demo.queue-load.enabled=false",
        "trading-demo.simulators.webhook-secret-file=simulators/secrets/webhook-secret.txt"
}, webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureTestRestTemplate
@DirtiesContext // close while this class's containers are still up; a cached context outlives them
class BankStatementReconciliationIT {
    private static final Duration TIMEOUT = Duration.ofSeconds(60);

    @Container
    static final PostgreSQLContainer postgres = new PostgreSQLContainer("postgres:18.4")
            .withDatabaseName("trading-demo-reconciliation-it-db")
            .withUsername("test")
            .withPassword("test");

    @Container
    static final GenericContainer<?> gateway = SimulatorImages.paymentGatewayAsTheDemoRunsIt()
                                                              .withEnv("GATEWAY__STATEMENT__BREAKS__ALTER_PROBABILITY", "1.0")
                                                              .withEnv("GATEWAY__STATEMENT__BREAKS__PHANTOM_PROBABILITY", "1.0");

    @DynamicPropertySource
    static void registerProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
        registry.add("spring.datasource.driver-class-name", postgres::getDriverClassName);
        registry.add("trading-demo.simulators.payment-gateway.base-url", BankStatementReconciliationIT::gatewayUrl);
    }

    @Autowired
    private TestRestTemplate restTemplate;
    @Autowired
    private CaseOpening      caseOpening;

    @Test
    void the_seeded_statement_breaks_are_found_one_for_one() {
        openAccount("ACC-RECON");
        linkBankAccount("ACC-RECON");
        assertThat(requestDeposit("ACC-RECON", "DEP-RECON-1", "250.00").getStatusCode()).isEqualTo(HttpStatus.CREATED);
        await().atMost(TIMEOUT).untilAsserted(() -> assertThat(payment("ACC-RECON", "DEP-RECON-1").paymentId()).isNotNull());
        var testControl = new TestControlApi(apiClient());
        testControl.forcePaymentOutcome(UUID.fromString(payment("ACC-RECON", "DEP-RECON-1").paymentId()),
                                        new ForcePaymentOutcomeRequest().outcome(ForcePaymentOutcomeRequest.OutcomeEnum.COMPLETED).reason(null).returnAfterSeconds(null));

        // Past the deposit's delay and, whatever weekday the clock started on, past the next business day's cutoff
        testControl.advanceClock(new ClockAdvance().days(4).hours(0).minutes(0).seconds(0));

        await().atMost(TIMEOUT).untilAsserted(() -> {
            assertThat(statements()).isNotEmpty();
            assertThat(statements().stream().flatMap(statement -> breaksOf(statement.statementId()).stream()))
                    .filteredOn(b -> b.kind() == StatementBreak.Kind.AMOUNT_MISMATCH)
                    .singleElement()
                    .satisfies(b -> {
                        assertThat(b.reference()).isEqualTo("DEP-RECON-1");
                        assertThat(b.ourAmount()).isEqualByComparingTo("250.00");
                        assertThat(b.statementAmount()).isNotEqualByComparingTo("250.00");
                    });
        });

        // Every statement's breaks, one for one with what the gateway seeded into it
        for (var statement : statements()) {
            var ours = breaksOf(statement.statementId()).stream()
                                                        .map(b -> switch (b.kind()) {
                                                            case AMOUNT_MISMATCH -> "AMOUNT_CHANGED";
                                                            case UNKNOWN_ENTRY -> "PHANTOM_ENTRY";
                                                            case MISSING_ENTRY -> "OMITTED_ENTRY";
                                                            case KIND_MISMATCH -> "KIND_MISMATCH";
                                                        })
                                                        .toList();
            var seeded = testControl.listStatementBreaks(statement.businessDate(), statement.currency()).stream()
                                    .flatMap(statementBreaks -> statementBreaks.getBreaks().stream())
                                    .map(seededBreak -> seededBreak.getKind().getValue())
                                    .toList();
            assertThat(ours).as("breaks of %s", statement.statementId()).containsExactlyInAnyOrderElementsOf(seeded);
        }

        var expectedCases = statements().stream()
                                        .flatMap(statement -> breaksOf(statement.statementId()).stream().map(b -> statement.statementId() + ":" + b.kind() + ":" + b.reference()))
                                        .toList();
        var latest = statements().stream().map(BankStatementBreaksQuery.StatementSummary::businessDate).max(Comparator.naturalOrder()).orElseThrow();
        caseOpening.reconcile(latest.plusDays(1));
        await().atMost(TIMEOUT).untilAsserted(() -> assertThat(cases()).extracting(CaseView::caseId).containsExactlyInAnyOrderElementsOf(expectedCases));
    }

    private List<CaseView> cases() {
        return Arrays.asList(Objects.requireNonNull(operator().getForEntity("/api/admin/reconciliation/cases", CaseView[].class).getBody()));
    }

    private List<BankStatementBreaksQuery.StatementSummary> statements() {
        return Arrays.asList(Objects.requireNonNull(operator().getForEntity("/api/admin/reconciliation/bank-statements", BankStatementBreaksQuery.StatementSummary[].class).getBody()));
    }

    private List<StatementBreak> breaksOf(String statementId) {
        return Arrays.asList(Objects.requireNonNull(operator().getForEntity("/api/admin/reconciliation/bank-statements/" + statementId + "/breaks", StatementBreak[].class).getBody()));
    }

    private static ApiClient apiClient() {
        var apiClient = new ApiClient(RestClient.builder().build());
        apiClient.setBasePath(gatewayUrl());
        return apiClient;
    }

    private static String gatewayUrl() {
        return "http://" + gateway.getHost() + ":" + gateway.getMappedPort(8081);
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

    private void openAccount(String accountId) {
        assertThat(alice().postForEntity("/api/accounts", Map.of("accountId", accountId, "ownerId", "USR-ALICE", "currency", "USD"), Void.class)
                          .getStatusCode()).isEqualTo(HttpStatus.CREATED);
        await().atMost(TIMEOUT).until(() -> alice().getForEntity("/api/accounts/" + accountId + "/payments", String.class).getStatusCode() == HttpStatus.OK);
    }

    private void linkBankAccount(String accountId) {
        alice().put("/api/accounts/" + accountId + "/bank-account", Map.of("accountId", accountId, "bankAccount", Map.of("iban", "GB82WEST12345698765432", "holderName", "Alice Customer")));
    }

    private ResponseEntity<String> requestDeposit(String accountId, String depositId, String amount) {
        return alice().postForEntity("/api/accounts/" + accountId + "/deposits", Map.of("depositId", depositId, "accountId", accountId, "amount", usd(amount)), String.class);
    }

    private ResponseEntity<String> requestWithdrawal(String accountId, String withdrawalId, String amount) {
        return alice().postForEntity("/api/accounts/" + accountId + "/withdrawals", Map.of("withdrawalId", withdrawalId, "accountId", accountId, "amount", usd(amount)), String.class);
    }

    private PaymentView payment(String accountId, String reference) {
        var payment = alice().getForEntity("/api/accounts/" + accountId + "/payments/" + reference, PaymentView.class).getBody();
        assertThat(payment).as("payment %s not projected yet", reference).isNotNull();
        return payment;
    }

    private AccountOverview overview(String accountId) {
        return alice().getForEntity("/api/accounts/" + accountId, AccountOverview.class).getBody();
    }

    /**
     * Credits and clears a deposit through the ledger's operator endpoints, so the account has withdrawable cash.
     */
    private void creditClearedCash(String accountId, String amount) {
        assertThat(operator().postForEntity("/api/admin/accounts/" + accountId + "/deposits",
                                            Map.of("accountId", accountId, "depositId", "DEP-SEED-" + accountId, "amount", usd(amount), "businessDate", "2030-01-07"),
                                            String.class).getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(operator().postForEntity("/api/admin/accounts/" + accountId + "/deposit-clearances",
                                            Map.of("accountId", accountId, "depositId", "DEP-SEED-" + accountId),
                                            String.class).getStatusCode()).isEqualTo(HttpStatus.OK);
        await().atMost(TIMEOUT).untilAsserted(() -> assertThat(overview(accountId).withdrawable().getAmount().value()).isEqualByComparingTo(amount));
    }
}
