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

import dk.trustworks.essentials.examples.trading.funding.views.payments.PaymentView;
import dk.trustworks.essentials.examples.trading.integration.SimulatorImages;
import dk.trustworks.essentials.examples.trading.integration.payment_gateway.client.ApiClient;
import dk.trustworks.essentials.examples.trading.integration.payment_gateway.client.api.TestControlApi;
import dk.trustworks.essentials.examples.trading.integration.payment_gateway.client.model.ClockAdvance;
import dk.trustworks.essentials.examples.trading.integration.payment_gateway.client.model.ForcePaymentOutcomeRequest;
import dk.trustworks.essentials.examples.trading.reconciliation.views.bank_statement_breaks.*;
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
 * The gateway's phantom entry copies a real entry's {@code endToEndId}, kind and amount under a payment that does not
 * exist - and the entry it copies may be one it also omitted. With one deposit on the statement, every entry omitted
 * and a phantom on every statement, the phantom can only copy the omitted deposit: the statement must show it as an
 * unknown entry AND the deposit as missing, not let the copy stand in for the original. Found by phase 6's F-5 run,
 * where 3 of 17 seeded omissions went unreported that way.
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
class PhantomOfAnOmittedEntryIT {
    private static final Duration TIMEOUT = Duration.ofSeconds(60);

    @Container
    static final PostgreSQLContainer postgres = new PostgreSQLContainer("postgres:18.4")
            .withDatabaseName("trading-demo-phantom-omitted-it-db")
            .withUsername("test")
            .withPassword("test");

    @Container
    static final GenericContainer<?> gateway = SimulatorImages.paymentGatewayAsTheDemoRunsIt()
                                                              .withEnv("GATEWAY__STATEMENT__BREAKS__OMIT_PROBABILITY", "1.0")
                                                              .withEnv("GATEWAY__STATEMENT__BREAKS__PHANTOM_PROBABILITY", "1.0");

    @DynamicPropertySource
    static void registerProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
        registry.add("spring.datasource.driver-class-name", postgres::getDriverClassName);
        registry.add("trading-demo.simulators.payment-gateway.base-url", PhantomOfAnOmittedEntryIT::gatewayUrl);
    }

    @Autowired
    private TestRestTemplate restTemplate;

    @Test
    void a_phantom_copied_from_an_omitted_entry_does_not_hide_the_omission() {
        openAccount("ACC-PHANTOM");
        linkBankAccount("ACC-PHANTOM");
        assertThat(requestDeposit("ACC-PHANTOM", "DEP-PHANTOM-1", "250.00").getStatusCode()).isEqualTo(HttpStatus.CREATED);
        await().atMost(TIMEOUT).untilAsserted(() -> assertThat(payment("ACC-PHANTOM", "DEP-PHANTOM-1").paymentId()).isNotNull());
        var testControl = new TestControlApi(apiClient());
        testControl.forcePaymentOutcome(UUID.fromString(payment("ACC-PHANTOM", "DEP-PHANTOM-1").paymentId()),
                                        new ForcePaymentOutcomeRequest().outcome(ForcePaymentOutcomeRequest.OutcomeEnum.COMPLETED).reason(null).returnAfterSeconds(null));

        // Past the deposit's delay and, whatever weekday the clock started on, past the next business day's cutoff
        testControl.advanceClock(new ClockAdvance().days(4).hours(0).minutes(0).seconds(0));

        await().atMost(TIMEOUT).untilAsserted(() -> {
            var breaks = statements().stream().flatMap(statement -> breaksOf(statement.statementId()).stream()).toList();
            assertThat(breaks).filteredOn(b -> "DEP-PHANTOM-1".equals(b.reference()))
                              .extracting(StatementBreak::kind)
                              .containsExactlyInAnyOrder(StatementBreak.Kind.UNKNOWN_ENTRY, StatementBreak.Kind.MISSING_ENTRY);
        });

        // One for one with what the gateway seeded, on every statement
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

    private void openAccount(String accountId) {
        assertThat(alice().postForEntity("/api/accounts", Map.of("accountId", accountId, "ownerId", "USR-ALICE", "currency", "USD"), Void.class)
                          .getStatusCode()).isEqualTo(HttpStatus.CREATED);
        await().atMost(TIMEOUT).until(() -> alice().getForEntity("/api/accounts/" + accountId + "/payments", String.class).getStatusCode() == HttpStatus.OK);
    }

    private void linkBankAccount(String accountId) {
        alice().put("/api/accounts/" + accountId + "/bank-account", Map.of("accountId", accountId, "bankAccount", Map.of("iban", "GB82WEST12345698765432", "holderName", "Alice Customer")));
    }

    private ResponseEntity<String> requestDeposit(String accountId, String depositId, String amount) {
        return alice().postForEntity("/api/accounts/" + accountId + "/deposits",
                                     Map.of("depositId", depositId, "accountId", accountId, "amount", Map.of("amount", new BigDecimal(amount), "currency", "USD")), String.class);
    }

    private PaymentView payment(String accountId, String reference) {
        var payment = alice().getForEntity("/api/accounts/" + accountId + "/payments/" + reference, PaymentView.class).getBody();
        assertThat(payment).as("payment %s not projected yet", reference).isNotNull();
        return payment;
    }
}
