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

package dk.trustworks.essentials.examples.trading.funding;

import dk.trustworks.essentials.examples.trading.accounts.views.account_overview.AccountOverview;
import dk.trustworks.essentials.examples.trading.funding.types.PaymentStatus;
import dk.trustworks.essentials.examples.trading.funding.views.payments.PaymentView;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.resttestclient.TestRestTemplate;
import org.springframework.boot.resttestclient.autoconfigure.AutoConfigureTestRestTemplate;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.*;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.*;
import org.testcontainers.junit.jupiter.*;
import org.testcontainers.postgresql.PostgreSQLContainer;

import java.math.BigDecimal;
import java.time.Duration;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

/**
 * Deposits and withdrawals end to end against the stub payment gateway, which completes every payment at once: the
 * customer links a bank account, deposits, withdraws, and the ledger books each outcome. The refusals: no linked bank
 * account, and a withdrawal beyond withdrawable cash, which leaves no withdrawal behind.
 */
@Testcontainers
@SpringBootTest(properties = {
        "trading-demo.simulation.enabled=false",
        "trading-demo.load.enabled=false",
        "trading-demo.queue-load.enabled=false"
}, webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureTestRestTemplate
@DirtiesContext // close while this class's container is still up; a cached context outlives it
class FundingIT {
    private static final Duration TIMEOUT = Duration.ofSeconds(60);

    @Container
    static final PostgreSQLContainer postgres = new PostgreSQLContainer("postgres:18.4")
            .withDatabaseName("trading-demo-funding-it-db")
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

    @Test
    void a_deposit_from_the_linked_bank_account_is_credited_and_uncleared() {
        openAccount("ACC-FUND-DEPOSIT");
        linkBankAccount("ACC-FUND-DEPOSIT");

        assertThat(requestDeposit("ACC-FUND-DEPOSIT", "DEP-FUND-1", "1000.00").getStatusCode()).isEqualTo(HttpStatus.CREATED);

        await().atMost(TIMEOUT).untilAsserted(() -> {
            assertThat(payment("ACC-FUND-DEPOSIT", "DEP-FUND-1").status()).isEqualTo(PaymentStatus.COMPLETED);
            var account = overview("ACC-FUND-DEPOSIT");
            assertThat(account.uncleared().getAmount().value()).isEqualByComparingTo("1000.00");
            assertThat(account.withdrawable().getAmount().value()).isEqualByComparingTo("0");
        });
    }

    @Test
    void a_withdrawal_holds_then_debits_what_was_withdrawable() {
        openAccount("ACC-FUND-WITHDRAW");
        linkBankAccount("ACC-FUND-WITHDRAW");
        creditClearedCash("ACC-FUND-WITHDRAW", "1000.00");

        assertThat(requestWithdrawal("ACC-FUND-WITHDRAW", "WDR-FUND-1", "300.00").getStatusCode()).isEqualTo(HttpStatus.CREATED);

        await().atMost(TIMEOUT).untilAsserted(() -> {
            assertThat(payment("ACC-FUND-WITHDRAW", "WDR-FUND-1").status()).isEqualTo(PaymentStatus.COMPLETED);
            var account = overview("ACC-FUND-WITHDRAW");
            assertThat(account.settledCash().getAmount().value()).isEqualByComparingTo("700.00");
            assertThat(account.held().getAmount().value()).isEqualByComparingTo("0");
            assertThat(account.withdrawable().getAmount().value()).isEqualByComparingTo("700.00");
        });
    }

    @Test
    void a_withdrawal_beyond_withdrawable_cash_is_refused_and_leaves_nothing_behind() {
        openAccount("ACC-FUND-REFUSED");
        linkBankAccount("ACC-FUND-REFUSED");
        creditClearedCash("ACC-FUND-REFUSED", "100.00");

        assertThat(requestWithdrawal("ACC-FUND-REFUSED", "WDR-FUND-2", "100.01").getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_CONTENT);

        assertThat(alice().getForEntity("/api/accounts/ACC-FUND-REFUSED/payments/WDR-FUND-2", String.class).getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(overview("ACC-FUND-REFUSED").held().getAmount().value()).isEqualByComparingTo("0");
    }

    @Test
    void without_a_linked_bank_account_nothing_is_requested() {
        openAccount("ACC-FUND-UNLINKED");

        assertThat(requestDeposit("ACC-FUND-UNLINKED", "DEP-FUND-2", "10.00").getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_CONTENT);
        assertThat(requestWithdrawal("ACC-FUND-UNLINKED", "WDR-FUND-3", "10.00").getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_CONTENT);
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
