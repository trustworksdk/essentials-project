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

package dk.trustworks.essentials.examples.trading.orders.external_systems.exchange;

import dk.trustworks.essentials.examples.trading.integration.ExternalEventEnvelope;
import dk.trustworks.essentials.examples.trading.orders.types.OrderId;
import dk.trustworks.essentials.examples.trading.orders.use_cases.record_cancellation.RecordCancellation;
import dk.trustworks.essentials.examples.trading.orders.use_cases.record_exchange_answer.RecordExchangeAnswer;
import tools.jackson.databind.JsonNode;

import java.util.*;

/**
 * The exchange's order lifecycle events, translated to {@code orders}' own report commands - the only place the
 * exchange's event schema appears on the receiving side (design §4, "Inbound").
 * <p>
 * The order is named by the event's {@code clientOrderId}, which is our {@code OrderId} (D8), so no lookup is needed.
 * <p>
 * Fills are not translated yet: the exchange's fill events carry neither the execution's quantity and price nor,
 * except on a resting order, its {@code executionId}, so a fill cannot be booked from them (SR-31). Until the exchange
 * sends them, fills come only from the submission's answer ({@link SimulatorExchangeGateway}). {@code OrderExpired}
 * waits for the exchange's own {@code DAY} expiry to replace the brokerage-side stopgap.
 */
final class ExchangeOrderEventTranslation {
    static final String ORDER_ACCEPTED  = "OrderAccepted";
    static final String ORDER_REJECTED  = "OrderRejected";
    static final String ORDER_CANCELLED = "OrderCancelled";

    /**
     * The event types the subscription is declared for (keep in step with {@code simulators/exchange.yaml}).
     */
    static final Set<String> EVENT_TYPES = Set.of(ORDER_ACCEPTED, ORDER_REJECTED, ORDER_CANCELLED);

    private ExchangeOrderEventTranslation() {
    }

    /**
     * Whether a delivered copy carries everything its translation needs. A copy damaged by the exchange's fault
     * injection fails this, and is answered so that the exchange retries with a fresh one.
     */
    static boolean isComplete(ExternalEventEnvelope envelope) {
        var payload = envelope.payload();
        if (text(payload, "clientOrderId").isEmpty()) {
            return false;
        }
        return switch (envelope.eventType()) {
            case ORDER_ACCEPTED -> true;
            case ORDER_REJECTED -> text(payload, "rejectReason").isPresent();
            case ORDER_CANCELLED -> text(payload, "cancelReason").isPresent();
            default -> false;
        };
    }

    /**
     * @return the report command for a complete event of one of {@link #EVENT_TYPES}
     */
    static Object toReport(ExternalEventEnvelope envelope) {
        var payload = envelope.payload();
        var orderId = OrderId.of(text(payload, "clientOrderId").orElseThrow());
        return switch (envelope.eventType()) {
            case ORDER_ACCEPTED -> RecordExchangeAnswer.accepted(orderId);
            case ORDER_REJECTED -> RecordExchangeAnswer.rejected(orderId, text(payload, "rejectReason").orElseThrow());
            case ORDER_CANCELLED -> new RecordCancellation(orderId, text(payload, "cancelReason").orElseThrow());
            default -> throw new IllegalArgumentException("Not an order lifecycle event this slice translates: " + envelope.eventType());
        };
    }

    private static Optional<String> text(JsonNode payload, String field) {
        var node = payload.get(field);
        return node != null && node.isString() && !node.asString().isBlank() ? Optional.of(node.asString()) : Optional.empty();
    }
}
