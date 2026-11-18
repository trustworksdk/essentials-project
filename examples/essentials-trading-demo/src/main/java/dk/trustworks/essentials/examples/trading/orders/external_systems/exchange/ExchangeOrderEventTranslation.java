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

import dk.trustworks.essentials.examples.trading.accounts.types.*;
import dk.trustworks.essentials.examples.trading.integration.ExternalEventEnvelope;
import dk.trustworks.essentials.examples.trading.orders.types.OrderId;
import dk.trustworks.essentials.examples.trading.orders.use_cases.record_cancellation.RecordCancellation;
import dk.trustworks.essentials.examples.trading.orders.use_cases.record_exchange_answer.RecordExchangeAnswer;
import dk.trustworks.essentials.examples.trading.orders.use_cases.record_expiry.RecordExpiry;
import dk.trustworks.essentials.examples.trading.orders.use_cases.record_fill.RecordFill;
import dk.trustworks.essentials.types.Money;
import tools.jackson.databind.JsonNode;

import java.math.BigDecimal;
import java.time.*;
import java.util.*;

/**
 * The exchange's order lifecycle events, translated to {@code orders}' own report commands - the only place the
 * exchange's event schema appears on the receiving side (design §4, "Inbound").
 * <p>
 * The order is named by the event's {@code clientOrderId}, which is our {@code OrderId} (D8), so no lookup is needed.
 * <p>
 * A fill event names its execution and what it filled (SR-31, FIX ExecID/LastQty/LastPx), with the execution's trade
 * and settlement dates (SR-29). The submission's answer reports the same executions of a marketable order
 * ({@link SimulatorExchangeGateway}); the order adds an {@code executionId} only once. The price is in USD, the only
 * currency the exchange lists in (D3).
 * <p>
 * {@code OrderExpired} names the business day that ended (SR-32), never worked out from {@code occurredAt} (D10).
 */
final class ExchangeOrderEventTranslation {
    static final String ORDER_ACCEPTED         = "OrderAccepted";
    static final String ORDER_REJECTED         = "OrderRejected";
    static final String ORDER_CANCELLED        = "OrderCancelled";
    static final String ORDER_EXPIRED          = "OrderExpired";
    static final String ORDER_PARTIALLY_FILLED = "OrderPartiallyFilled";
    static final String ORDER_FILLED           = "OrderFilled";

    /**
     * The event types the subscription is declared for (keep in step with {@code simulators/exchange.yaml}).
     */
    static final Set<String> EVENT_TYPES = Set.of(ORDER_ACCEPTED, ORDER_REJECTED, ORDER_CANCELLED, ORDER_EXPIRED, ORDER_PARTIALLY_FILLED, ORDER_FILLED);

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
            case ORDER_EXPIRED -> text(payload, "businessDate").flatMap(ExchangeOrderEventTranslation::date).isPresent();
            case ORDER_PARTIALLY_FILLED, ORDER_FILLED -> text(payload, "executionId").isPresent()
                    && positiveLong(payload, "lastQuantity").isPresent()
                    && text(payload, "lastPrice").flatMap(ExchangeOrderEventTranslation::decimal).isPresent()
                    && text(payload, "tradeDate").flatMap(ExchangeOrderEventTranslation::date).isPresent()
                    && text(payload, "intendedSettlementDate").flatMap(ExchangeOrderEventTranslation::date).isPresent();
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
            case ORDER_EXPIRED -> new RecordExpiry(orderId, text(payload, "businessDate").flatMap(ExchangeOrderEventTranslation::date).orElseThrow());
            case ORDER_PARTIALLY_FILLED, ORDER_FILLED -> new RecordFill(orderId,
                                                                        ExecutionId.of(text(payload, "executionId").orElseThrow()),
                                                                        Quantity.of(positiveLong(payload, "lastQuantity").orElseThrow()),
                                                                        Money.of(text(payload, "lastPrice").flatMap(ExchangeOrderEventTranslation::decimal).orElseThrow(), "USD"),
                                                                        text(payload, "tradeDate").flatMap(ExchangeOrderEventTranslation::date).orElseThrow(),
                                                                        text(payload, "intendedSettlementDate").flatMap(ExchangeOrderEventTranslation::date).orElseThrow());
            default -> throw new IllegalArgumentException("Not an order lifecycle event this slice translates: " + envelope.eventType());
        };
    }

    private static Optional<BigDecimal> decimal(String text) {
        try {
            return Optional.of(new BigDecimal(text));
        } catch (NumberFormatException e) {
            return Optional.empty();
        }
    }

    private static Optional<Long> positiveLong(JsonNode payload, String field) {
        var node = payload.get(field);
        return node != null && node.isIntegralNumber() && node.asLong() > 0 ? Optional.of(node.asLong()) : Optional.empty();
    }

    private static Optional<LocalDate> date(String text) {
        try {
            return Optional.of(LocalDate.parse(text));
        } catch (DateTimeException e) {
            return Optional.empty();
        }
    }

    private static Optional<String> text(JsonNode payload, String field) {
        var node = payload.get(field);
        return node != null && node.isString() && !node.asString().isBlank() ? Optional.of(node.asString()) : Optional.empty();
    }
}
