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

package dk.trustworks.essentials.examples.trading.reconciliation.external_systems.clearinghouse;

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
 * The clearinghouse's end-of-day statements of the firm's trades (design phase 5 §1-§2): {@code ClearingStatementIssued}
 * from the exchange, by the {@code demo-clearing} webhook subscription ({@code simulators/exchange.yaml},
 * participant-filtered to the firm) and a catch-up reader of its event log, filtered the same way (SR-39). Stored as
 * they are - tables
 * {@code clearing_statement}, {@code clearing_trade} and {@code clearing_obligation}. Reference data, as the bank's
 * statements are; the breaks are worked out when read ({@code views/clearing_statement_breaks}).
 * <p>
 * Active under the {@code simulators} profile only.
 */
@RestController
@ConditionalOnProperty(prefix = "trading-demo.simulators", name = {"exchange.base-url", "webhook-secret-file"})
public class ClearingStatements {
    static final         String    CONSUMER   = "reconciliation.clearing-statements";
    private static final InboxName INBOX_NAME = InboxName.of("Reconciliation:ClearingStatements");
    private static final String    EVENT_TYPE = "ClearingStatementIssued";

    private final Inboxes                  inboxes;
    private final WebhookSignatureVerifier verifier;
    private final SimulatorEventSchemas schemas;
    private final ExternalEventIntake      intake;
    private final Dependencies             dependencies;
    private final RestClient               exchange;
    private volatile SignedWebhookReceiver   receiver;
    private volatile ExternalEventLogCatchUp catchUp;
    private volatile Inbox                   inbox;

    @Component
    @ConditionalOnProperty(prefix = "trading-demo.simulators", name = {"exchange.base-url", "webhook-secret-file"})
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

    public ClearingStatements(Inboxes inboxes, WebhookSignatureVerifier verifier, SimulatorEventSchemas schemas, ExternalEventIntake intake, Dependencies dependencies) {
        this.inboxes = requireNonNull(inboxes, "No inboxes provided");
        this.verifier = requireNonNull(verifier, "No verifier provided");
        this.schemas = requireNonNull(schemas, "No schemas provided");
        this.intake = requireNonNull(intake, "No intake provided");
        this.dependencies = requireNonNull(dependencies, "No dependencies provided");
        this.exchange = dependencies.restClientBuilder().clone().baseUrl(dependencies.properties().getExchange().getBaseUrl()).build();
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
        var settings = new SignedWebhookReceiver.Settings(CONSUMER, Set.of(EVENT_TYPE), ClearingStatements::isComplete, inbox);
        receiver = new SignedWebhookReceiver(settings, verifier, schemas, intake, dependencies.objectMapper());
        catchUp = new ExternalEventLogCatchUp(new ExternalEventLogCatchUp.Settings("exchange:" + CONSUMER, exchange, settings, Duration.ofSeconds(5), 500,
                                                                                         Set.of(dependencies.properties().getExchange().getParticipantId())),
                                              intake,
                                              dependencies.fencedLockManager(),
                                              dependencies.jdbcTemplate(),
                                              dependencies.objectMapper());
        catchUp.start();
    }

    /**
     * Reads the exchange's event log to its end and waits, up to {@code timeout}, until every statement found there is
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

    @PostMapping("/webhooks/exchange/clearing-statements")
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
     * A statement copy is complete when it and every trade line and obligation carry what reconciliation reads.
     */
    static boolean isComplete(ExternalEventEnvelope envelope) {
        var payload = envelope.payload();
        if (text(payload, "statementId").isEmpty() || date(payload, "businessDate").isEmpty() || text(payload, "participantId").isEmpty()
                || text(payload, "issuedAt").isEmpty()) {
            return false;
        }
        var trades      = payload.get("trades");
        var obligations = payload.get("obligations");
        if (trades == null || !trades.isArray() || obligations == null || !obligations.isArray()) {
            return false;
        }
        for (var trade : trades) {
            if (text(trade, "executionId").isEmpty() || text(trade, "instrumentId").isEmpty() || text(trade, "side").isEmpty()
                    || integer(trade, "quantity").isEmpty() || decimal(trade, "amount").isEmpty() || date(trade, "tradeDate").isEmpty()
                    || text(trade, "obligationId").isEmpty()) {
                return false;
            }
        }
        for (var obligation : obligations) {
            if (text(obligation, "obligationId").isEmpty() || text(obligation, "instrumentId").isEmpty() || integer(obligation, "securitiesNet").isEmpty()
                    || decimal(obligation, "cashNet").isEmpty() || integer(obligation, "tradeCount").isEmpty()) {
                return false;
            }
        }
        return true;
    }

    private void record(Message message) {
        var statement   = ((ExternalEventEnvelope) message.getPayload()).payload();
        var jdbc        = dependencies.jdbcTemplate();
        var statementId = text(statement, "statementId").orElseThrow();
        if (!dependencies.properties().getExchange().getParticipantId().equals(text(statement, "participantId").orElseThrow())) {
            // Both the subscription and the catch-up read (SR-39) ask for the firm's statements only; this keeps a
            // statement of another participant, the liquidity provider's say, out should either ever not
            return;
        }
        var stored = jdbc.update("""
                                         INSERT INTO clearing_statement (statement_id, business_date, participant_id, issued_at, received_at)
                                         VALUES (?, ?, ?, ?, ?)
                                         ON CONFLICT (statement_id) DO NOTHING
                                         """,
                                 statementId,
                                 Date.valueOf(date(statement, "businessDate").orElseThrow()),
                                 text(statement, "participantId").orElseThrow(),
                                 Timestamp.from(OffsetDateTime.parse(text(statement, "issuedAt").orElseThrow()).toInstant()),
                                 Timestamp.from(Instant.now()));
        if (stored == 0) {
            return;
        }
        for (var trade : statement.get("trades")) {
            jdbc.update("""
                                INSERT INTO clearing_trade (statement_id, execution_id, instrument_id, side, quantity, amount, trade_date, obligation_id)
                                VALUES (?, ?, ?, ?, ?, ?, ?, ?)
                                ON CONFLICT (statement_id, execution_id) DO NOTHING
                                """,
                        statementId,
                        text(trade, "executionId").orElseThrow(),
                        text(trade, "instrumentId").orElseThrow(),
                        text(trade, "side").orElseThrow(),
                        integer(trade, "quantity").orElseThrow(),
                        decimal(trade, "amount").orElseThrow().abs(),
                        Date.valueOf(date(trade, "tradeDate").orElseThrow()),
                        text(trade, "obligationId").orElseThrow());
        }
        for (var obligation : statement.get("obligations")) {
            jdbc.update("""
                                INSERT INTO clearing_obligation (obligation_id, statement_id, instrument_id, securities_net, cash_net, trade_count)
                                VALUES (?, ?, ?, ?, ?, ?)
                                ON CONFLICT (obligation_id) DO NOTHING
                                """,
                        text(obligation, "obligationId").orElseThrow(),
                        statementId,
                        text(obligation, "instrumentId").orElseThrow(),
                        integer(obligation, "securitiesNet").orElseThrow(),
                        decimal(obligation, "cashNet").orElseThrow(),
                        integer(obligation, "tradeCount").orElseThrow());
        }
    }

    private static Optional<Long> integer(JsonNode node, String field) {
        var value = node.get(field);
        return value != null && value.isIntegralNumber() ? Optional.of(value.asLong()) : Optional.empty();
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
