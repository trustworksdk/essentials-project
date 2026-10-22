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

package dk.trustworks.essentials.examples.trading.funding.external_systems.payment_gateway;

import dk.trustworks.essentials.examples.trading.accounts.views.account_overview.AccountOverview;
import dk.trustworks.essentials.examples.trading.integration.SimulatorImages;
import dk.trustworks.essentials.examples.trading.integration.payment_gateway.client.ApiClient;
import dk.trustworks.essentials.examples.trading.integration.payment_gateway.client.api.TestControlApi;
import dk.trustworks.essentials.examples.trading.integration.payment_gateway.client.model.ClockAdvance;
import dk.trustworks.essentials.examples.trading.integration.payment_gateway.client.model.ForcePaymentOutcomeRequest;
import dk.trustworks.essentials.examples.trading.funding.types.PaymentStatus;
import dk.trustworks.essentials.examples.trading.funding.views.payments.PaymentView;
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
 * Funding against the real payment-gateway simulator: payments are initiated through its generated client and their
 * outcomes, forced per payment with its test control (SR-22), come back through its event log (no webhook reaches this
 * test's application). A forced failure of a withdrawal releases its hold. Completions need the gateway to send the
 * value date (SR-33); until its specification has {@code valueDate}, that case skips itself.
 * <p>
 * Skipped unless the {@code payment-gateway:dev} image exists locally (see {@link SimulatorImages}).
 */
@Testcontainers
@EnabledIf("dk.trustworks.essentials.examples.trading.integration.SimulatorImages#paymentGatewayAvailable")
@SpringBootTest(properties = {
        "trading-demo.simulation.enabled=false",
        "trading-demo.load.enabled=false",
        "trading-demo.queue-load.enabled=false",
        "trading-demo.simulators.webhook-secret-file=simulators/secrets/webhook-secret.txt"
}, webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureTestRestTemplate
@DirtiesContext // close while this class's containers are still up; a cached context outlives them
class PaymentGatewayIT {
    private static final Duration TIMEOUT = Duration.ofSeconds(60);

    @Container
    static final PostgreSQLContainer postgres = new PostgreSQLContainer("postgres:18.4")
            .withDatabaseName("trading-demo-payment-gateway-it-db")
            .withUsername("test")
            .withPassword("test");

    @Container
    static final GenericContainer<?> gateway = SimulatorImages.paymentGatewayAsTheDemoRunsIt();

    @DynamicPropertySource
    static void registerProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
        registry.add("spring.datasource.driver-class-name", postgres::getDriverClassName);
        registry.add("trading-demo.simulators.payment-gateway.base-url", PaymentGatewayIT::gatewayUrl);
    }

    @Autowired
    private TestRestTemplate restTemplate;

    @Test
    void a_withdrawal_the_bank_refuses_releases_its_hold() {
        openAccount("ACC-GW-REFUSED");
        linkBankAccount("ACC-GW-REFUSED");
        creditClearedCash("ACC-GW-REFUSED", "500.00");
        assertThat(requestWithdrawal("ACC-GW-REFUSED", "WDR-GW-1", "200.00").getStatusCode()).isEqualTo(HttpStatus.CREATED);
        var paymentId = awaitInitiated("ACC-GW-REFUSED", "WDR-GW-1");
        assertThat(overview("ACC-GW-REFUSED").held().getAmount().value()).isEqualByComparingTo("200.00");

        force(paymentId, ForcePaymentOutcomeRequest.OutcomeEnum.FAILED, "ACCOUNT_CLOSED");

        await().atMost(TIMEOUT).untilAsserted(() -> {
            var payment = payment("ACC-GW-REFUSED", "WDR-GW-1");
            assertThat(payment.status()).isEqualTo(PaymentStatus.FAILED);
            assertThat(payment.reason()).isEqualTo("ACCOUNT_CLOSED");
            var account = overview("ACC-GW-REFUSED");
            assertThat(account.held().getAmount().value()).isEqualByComparingTo("0");
            assertThat(account.withdrawable().getAmount().value()).isEqualByComparingTo("500.00");
        });
    }

    @Test
    void a_completed_deposit_is_credited_for_the_banks_value_date() {
        Assumptions.assumeTrue(gatewaySendsValueDates(), "the payment gateway does not send valueDate yet (SR-33)");
        openAccount("ACC-GW-DEPOSIT");
        linkBankAccount("ACC-GW-DEPOSIT");
        assertThat(requestDeposit("ACC-GW-DEPOSIT", "DEP-GW-1", "750.00").getStatusCode()).isEqualTo(HttpStatus.CREATED);
        var paymentId = awaitInitiated("ACC-GW-DEPOSIT", "DEP-GW-1");

        force(paymentId, ForcePaymentOutcomeRequest.OutcomeEnum.COMPLETED, null);

        await().atMost(TIMEOUT).untilAsserted(() -> {
            var payment = payment("ACC-GW-DEPOSIT", "DEP-GW-1");
            assertThat(payment.status()).isEqualTo(PaymentStatus.COMPLETED);
            assertThat(payment.valueDate()).isNotNull();
            assertThat(overview("ACC-GW-DEPOSIT").uncleared().getAmount().value()).isEqualByComparingTo("750.00");
        });
    }

    private String awaitInitiated(String accountId, String reference) {
        await().atMost(TIMEOUT).untilAsserted(() -> assertThat(payment(accountId, reference).paymentId()).isNotNull());
        return payment(accountId, reference).paymentId();
    }

    /**
     * Forces the payment's outcome, then moves the gateway's frozen clock past the longest payment delay (4 hours for a
     * withdrawal), so the outcome happens.
     */
    private static void force(String paymentId, ForcePaymentOutcomeRequest.OutcomeEnum outcome, String reason) {
        var testControl = new TestControlApi(apiClient());
        testControl.forcePaymentOutcome(UUID.fromString(paymentId), new ForcePaymentOutcomeRequest().outcome(outcome).reason(reason).returnAfterSeconds(null));
        testControl.advanceClock(new ClockAdvance().days(0).hours(5).minutes(0).seconds(0));
    }

    /**
     * Whether the gateway's own specification has {@code valueDate} on its payment fields (SR-33).
     */
    private static boolean gatewaySendsValueDates() {
        var spec = RestClient.create(gatewayUrl()).get().uri("/openapi.yaml").retrieve().body(String.class);
        if (spec == null || !spec.contains("\n    PaymentFields:")) {
            return false;
        }
        var paymentFields = spec.substring(spec.indexOf("\n    PaymentFields:") + 1);
        var nextSchema    = java.util.regex.Pattern.compile("\n    [A-Za-z]+:").matcher(paymentFields);
        return (nextSchema.find() ? paymentFields.substring(0, nextSchema.start()) : paymentFields).contains("valueDate");
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
        alice().put("/api/accounts/" + accountId + "/bank-account", Map.of("accountId", accountId, "bankAccount", Map.of("iban", "DK5000400440116243", "holderName", "Alice Customer")));
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
