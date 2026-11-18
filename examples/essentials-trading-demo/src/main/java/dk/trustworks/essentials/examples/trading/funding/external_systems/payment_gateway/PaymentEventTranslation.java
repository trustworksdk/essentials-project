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

import dk.trustworks.essentials.examples.trading.funding.types.*;
import dk.trustworks.essentials.examples.trading.funding.use_cases.record_payment_outcome.RecordPaymentOutcome;
import dk.trustworks.essentials.examples.trading.integration.ExternalEventEnvelope;
import tools.jackson.databind.JsonNode;

import java.time.*;
import java.util.*;

/**
 * The payment gateway's payment events, translated to {@code funding}'s {@link RecordPaymentOutcome} - the only place
 * the gateway's event schema appears on the receiving side. The payment is named by its {@code idempotencyKey}, which
 * is our {@code DepositId} / {@code WithdrawalId}, and its {@code type}; no lookup is needed.
 * <p>
 * A completion and a return carry the bank's value date (SR-33); the business date of a credit is never worked out from
 * {@code completedAt} (D10). A copy without it is incomplete, answered so that the gateway retries.
 */
final class PaymentEventTranslation {
    static final String PAYMENT_PENDING   = "PaymentPending";
    static final String PAYMENT_COMPLETED = "PaymentCompleted";
    static final String PAYMENT_FAILED    = "PaymentFailed";
    static final String PAYMENT_RETURNED  = "PaymentReturned";

    /**
     * The event types the subscription is declared for (keep in step with {@code simulators/gateway.yaml}).
     */
    static final Set<String> EVENT_TYPES = Set.of(PAYMENT_PENDING, PAYMENT_COMPLETED, PAYMENT_FAILED, PAYMENT_RETURNED);

    private PaymentEventTranslation() {
    }

    static boolean isComplete(ExternalEventEnvelope envelope) {
        var payload = envelope.payload();
        if (text(payload, "idempotencyKey").isEmpty() || kind(payload).isEmpty()) {
            return false;
        }
        return switch (envelope.eventType()) {
            case PAYMENT_PENDING -> text(payload, "paymentId").isPresent();
            case PAYMENT_COMPLETED -> date(payload, "valueDate").isPresent();
            case PAYMENT_FAILED -> text(payload, "reason").isPresent();
            case PAYMENT_RETURNED -> date(payload, "valueDate").isPresent() && text(payload, "reason").isPresent();
            default -> false;
        };
    }

    static RecordPaymentOutcome toReport(ExternalEventEnvelope envelope) {
        var payload   = envelope.payload();
        var kind      = kind(payload).orElseThrow();
        var reference = text(payload, "idempotencyKey").orElseThrow();
        return switch (envelope.eventType()) {
            case PAYMENT_PENDING -> RecordPaymentOutcome.initiated(kind, reference, PaymentId.of(text(payload, "paymentId").orElseThrow()));
            case PAYMENT_COMPLETED -> RecordPaymentOutcome.completed(kind, reference, date(payload, "valueDate").orElseThrow());
            case PAYMENT_FAILED -> RecordPaymentOutcome.failed(kind, reference, text(payload, "reason").orElseThrow());
            case PAYMENT_RETURNED -> RecordPaymentOutcome.returned(kind, reference, date(payload, "valueDate").orElseThrow(), text(payload, "reason").orElseThrow());
            default -> throw new IllegalArgumentException("Not a payment event this slice translates: " + envelope.eventType());
        };
    }

    private static Optional<PaymentKind> kind(JsonNode payload) {
        return text(payload, "type").flatMap(type -> switch (type) {
            case "DEPOSIT" -> Optional.of(PaymentKind.DEPOSIT);
            case "WITHDRAWAL" -> Optional.of(PaymentKind.WITHDRAWAL);
            default -> Optional.empty();
        });
    }

    private static Optional<LocalDate> date(JsonNode payload, String field) {
        try {
            return text(payload, field).map(LocalDate::parse);
        } catch (DateTimeException e) {
            return Optional.empty();
        }
    }

    private static Optional<String> text(JsonNode payload, String field) {
        var node = payload.get(field);
        return node != null && node.isString() && !node.asString().isBlank() ? Optional.of(node.asString()) : Optional.empty();
    }
}
