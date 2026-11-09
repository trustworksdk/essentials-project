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

package dk.trustworks.essentials.examples.trading.settlement.external_systems.exchange;

import dk.trustworks.essentials.examples.trading.accounts.types.ExecutionId;
import dk.trustworks.essentials.examples.trading.integration.ExternalEventEnvelope;
import dk.trustworks.essentials.examples.trading.settlement.use_cases.record_settlement_report.RecordSettlementReport;
import dk.trustworks.essentials.examples.trading.settlement.use_cases.record_settlement_report.RecordSettlementReport.Kind;
import dk.trustworks.essentials.types.Money;
import tools.jackson.databind.JsonNode;

import java.math.BigDecimal;
import java.time.*;
import java.util.*;

/**
 * The exchange's settlement events, translated to {@link RecordSettlementReport} - the only place the settlement
 * simulator's schema appears on the receiving side. The execution is named by {@code executionId}, the same id the
 * order's fill carried.
 */
final class SettlementEventTranslation {
    static final Map<String, Kind> KINDS = Map.of("SettlementPending", Kind.PENDING,
                                                  "SettlementDelayed", Kind.DELAYED,
                                                  "SettlementCompleted", Kind.COMPLETED,
                                                  "SettlementFailed", Kind.FAILED,
                                                  "SettlementCancelled", Kind.CANCELLED,
                                                  "SettlementRejected", Kind.REJECTED,
                                                  "SettlementCorrected", Kind.CORRECTED);

    /**
     * The event types the subscription is declared for (keep in step with {@code simulators/exchange.yaml}).
     */
    static final Set<String> EVENT_TYPES = KINDS.keySet();

    private SettlementEventTranslation() {
    }

    static boolean isComplete(ExternalEventEnvelope envelope) {
        var payload = envelope.payload();
        var kind    = KINDS.get(envelope.eventType());
        if (kind == null || text(payload, "executionId").isEmpty() || money(payload, "settlementAmount").isEmpty()
                || date(payload, "intendedSettlementDate").isEmpty()) {
            return false;
        }
        return switch (kind) {
            case CORRECTED -> money(payload, "previousSettlementAmount").isPresent();
            case FAILED, CANCELLED, REJECTED -> text(payload, "reason").isPresent();
            default -> true;
        };
    }

    static RecordSettlementReport toReport(ExternalEventEnvelope envelope) {
        var payload = envelope.payload();
        var kind    = KINDS.get(envelope.eventType());
        if (kind == null) {
            throw new IllegalArgumentException("Not a settlement event this slice translates: " + envelope.eventType());
        }
        var attempt = payload.get("attempt");
        return new RecordSettlementReport(ExecutionId.of(text(payload, "executionId").orElseThrow()),
                                          kind,
                                          money(payload, "settlementAmount").orElseThrow(),
                                          date(payload, "intendedSettlementDate").orElseThrow(),
                                          attempt != null && attempt.canConvertToInt() ? attempt.asInt() : 0,
                                          kind == Kind.CORRECTED ? money(payload, "previousSettlementAmount").orElseThrow() : null,
                                          text(payload, "reason").orElse(null));
    }

    private static Optional<Money> money(JsonNode payload, String field) {
        var currency = text(payload, "currency");
        try {
            return currency.flatMap(c -> text(payload, field).map(amount -> Money.of(new BigDecimal(amount), c)));
        } catch (NumberFormatException e) {
            return Optional.empty();
        }
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
