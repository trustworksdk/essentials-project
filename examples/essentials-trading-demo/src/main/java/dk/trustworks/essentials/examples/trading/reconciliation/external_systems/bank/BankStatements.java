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

package dk.trustworks.essentials.examples.trading.reconciliation.external_systems.bank;

import dk.trustworks.essentials.components.foundation.fencedlock.FencedLockManager;
import dk.trustworks.essentials.components.foundation.messaging.RedeliveryPolicy;
import dk.trustworks.essentials.components.foundation.messaging.eip.store_and_forward.*;
import dk.trustworks.essentials.components.foundation.messaging.queue.Message;
import dk.trustworks.essentials.examples.trading.integration.*;
import jakarta.annotation.PreDestroy;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.http.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.*;

import java.math.BigDecimal;
import java.sql.Date;
import java.sql.Timestamp;
import java.time.*;
import java.util.*;

import static dk.trustworks.essentials.shared.FailFast.requireNonNull;

/**
 * The bank's end-of-day statements of the firm's client-money account (design phase 2 §7): {@code BankStatementIssued}
 * from the payment gateway, by the {@code demo-reconciliation} webhook subscription ({@code simulators/gateway.yaml})
 * and a catch-up reader of its event log, stored as they are - tables {@code bank_statement} and
 * {@code bank_statement_entry}. A statement is reference data from outside, not a decision, so it is stored rather than
 * event-sourced; the breaks are worked out when they are read ({@code views/bank_statement_breaks}), never at intake.
 * <p>
 * Active under the {@code simulators} profile only.
 */
@RestController
@ConditionalOnProperty(prefix = "trading-demo.simulators", name = {"payment-gateway.base-url", "webhook-secret-file"})
public class BankStatements {
    static final         String    CONSUMER   = "reconciliation.bank-statements";
    private static final InboxName INBOX_NAME = InboxName.of("Reconciliation:BankStatements");
    private static final String    EVENT_TYPE = "BankStatementIssued";

    private final Inboxes                  inboxes;
    private final WebhookSignatureVerifier verifier;
    private final ExternalEventIntake      intake;
    private final Dependencies             dependencies;
    private final RestClient               gateway;
    private volatile SignedWebhookReceiver   receiver;
    private volatile ExternalEventLogCatchUp catchUp;
    private volatile Inbox                   inbox;

    @Component
    @ConditionalOnProperty(prefix = "trading-demo.simulators", name = {"payment-gateway.base-url", "webhook-secret-file"})
    public record Dependencies(FencedLockManager fencedLockManager,
                               JdbcTemplate jdbcTemplate,
                               ObjectMapper objectMapper,
                               RestClient.Builder restClientBuilder,
                               SimulatorProperties properties) {
        public Dependencies {
            requireNonNull(fencedLockManager, "No fencedLockManager provided");
            requireNonNull(jdbcTemplate, "No jdbcTemplate provided");
            requireNonNull(objectMapper, "No objectMapper provided");
            requireNonNull(restClientBuilder, "No restClientBuilder provided");
            requireNonNull(properties, "No properties provided");
        }
    }

    public BankStatements(Inboxes inboxes, WebhookSignatureVerifier verifier, ExternalEventIntake intake, Dependencies dependencies) {
        this.inboxes = requireNonNull(inboxes, "No inboxes provided");
        this.verifier = requireNonNull(verifier, "No verifier provided");
        this.intake = requireNonNull(intake, "No intake provided");
        this.dependencies = requireNonNull(dependencies, "No dependencies provided");
        this.gateway = dependencies.restClientBuilder().clone().baseUrl(dependencies.properties().getPaymentGateway().getBaseUrl()).build();
    }

    @EventListener(ApplicationReadyEvent.class)
    public void startReceiving() {
        inbox = inboxes.getOrCreateInbox(InboxConfig.builder()
                                                        .inboxName(INBOX_NAME)
                                                        .messageConsumptionMode(MessageConsumptionMode.GlobalCompetingConsumers)
                                                        .numberOfParallelMessageConsumers(1)
                                                        .redeliveryPolicy(RedeliveryPolicy.fixedBackoff(Duration.ofMillis(200), 20))
                                                        .build(),
                                             this::record);
        var settings = new SignedWebhookReceiver.Settings(CONSUMER, Set.of(EVENT_TYPE), BankStatements::isComplete, inbox);
        receiver = new SignedWebhookReceiver(settings, verifier, intake, dependencies.objectMapper());
        catchUp = new ExternalEventLogCatchUp(new ExternalEventLogCatchUp.Settings("payment-gateway:" + CONSUMER, gateway, settings, Duration.ofSeconds(5), 500),
                                              intake,
                                              dependencies.fencedLockManager(),
                                              dependencies.jdbcTemplate(),
                                              dependencies.objectMapper());
        catchUp.start();
    }

    /**
     * Reads the payment gateway's event log to its end and waits, up to {@code timeout}, until every statement found there is
     * stored. For a caller about to decide that a statement is missing: the business calendar reads the same source with
     * a reader of its own, so a close can be seen before the statement issued just ahead of it.
     *
     * @return whether everything read was stored in time; {@code false} before receiving has started
     */
    public boolean catchUp(Duration timeout) {
        requireNonNull(timeout, "No timeout provided");
        var currentCatchUp = catchUp;
        var currentInbox   = inbox;
        if (currentCatchUp == null || currentInbox == null) {
            return false;
        }
        currentCatchUp.readUntilCaughtUp();
        var deadline = System.nanoTime() + timeout.toNanos();
        while (currentInbox.getNumberOfUndeliveredMessages() > 0) {
            if (System.nanoTime() > deadline) {
                return false;
            }
            try {
                Thread.sleep(100);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return false;
            }
        }
        return true;
    }

    @PreDestroy
    public void stopReceiving() {
        var current = catchUp;
        if (current != null) {
            current.stop();
        }
    }

    @PostMapping("/webhooks/payment-gateway/bank-statements")
    public ResponseEntity<Void> receive(@RequestHeader(name = "X-Signature-Timestamp", required = false) String signatureTimestamp,
                                        @RequestHeader(name = "X-Signature", required = false) String signature,
                                        @RequestBody byte[] rawBody) {
        var current = receiver;
        if (current == null) {
            return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).build();
        }
        return current.receive(signatureTimestamp, signature, rawBody);
    }

    /**
     * A statement copy is complete when it and every entry carry what reconciliation reads.
     */
    static boolean isComplete(ExternalEventEnvelope envelope) {
        var payload = envelope.payload();
        if (text(payload, "statementId").isEmpty() || date(payload, "businessDate").isEmpty() || text(payload, "currency").isEmpty()
                || decimal(payload, "openingBalance").isEmpty() || decimal(payload, "closingBalance").isEmpty()
                || text(payload, "issuedAt").isEmpty()) {
            return false;
        }
        var entries = payload.get("entries");
        if (entries == null || !entries.isArray()) {
            return false;
        }
        for (var entry : entries) {
            if (text(entry, "entryId").isEmpty() || text(entry, "endToEndId").isEmpty() || text(entry, "paymentId").isEmpty()
                    || text(entry, "kind").isEmpty() || decimal(entry, "amount").isEmpty() || date(entry, "valueDate").isEmpty()) {
                return false;
            }
        }
        return true;
    }

    private void record(Message message) {
        var statement   = ((ExternalEventEnvelope) message.getPayload()).payload();
        var jdbc        = dependencies.jdbcTemplate();
        var statementId = text(statement, "statementId").orElseThrow();
        var stored = jdbc.update("""
                                         INSERT INTO bank_statement (statement_id, business_date, currency, opening_balance, closing_balance, issued_at, received_at)
                                         VALUES (?, ?, ?, ?, ?, ?, ?)
                                         ON CONFLICT (statement_id) DO NOTHING
                                         """,
                                 statementId,
                                 Date.valueOf(date(statement, "businessDate").orElseThrow()),
                                 text(statement, "currency").orElseThrow(),
                                 decimal(statement, "openingBalance").orElseThrow(),
                                 decimal(statement, "closingBalance").orElseThrow(),
                                 Timestamp.from(OffsetDateTime.parse(text(statement, "issuedAt").orElseThrow()).toInstant()),
                                 Timestamp.from(Instant.now()));
        if (stored == 0) {
            return;
        }
        for (var entry : statement.get("entries")) {
            jdbc.update("""
                                INSERT INTO bank_statement_entry (entry_id, statement_id, end_to_end_id, payment_id, kind, amount, value_date)
                                VALUES (?, ?, ?, ?, ?, ?, ?)
                                ON CONFLICT (entry_id) DO NOTHING
                                """,
                        text(entry, "entryId").orElseThrow(),
                        statementId,
                        text(entry, "endToEndId").orElseThrow(),
                        text(entry, "paymentId").orElseThrow(),
                        text(entry, "kind").orElseThrow(),
                        decimal(entry, "amount").orElseThrow().abs(),
                        Date.valueOf(date(entry, "valueDate").orElseThrow()));
        }
    }

    private static Optional<String> text(JsonNode node, String field) {
        var value = node.get(field);
        return value != null && value.isString() && !value.asString().isBlank() ? Optional.of(value.asString()) : Optional.empty();
    }

    private static Optional<LocalDate> date(JsonNode node, String field) {
        try {
            return text(node, field).map(LocalDate::parse);
        } catch (DateTimeException e) {
            return Optional.empty();
        }
    }

    private static Optional<BigDecimal> decimal(JsonNode node, String field) {
        try {
            return text(node, field).map(BigDecimal::new);
        } catch (NumberFormatException e) {
            return Optional.empty();
        }
    }
}
