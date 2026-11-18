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

package dk.trustworks.essentials.examples.trading.integration;

import tools.jackson.databind.JsonNode;

import java.time.*;
import java.util.Optional;

/**
 * The canonical event envelope both simulators store, replay from {@code GET /api/events} and deliver over webhooks.
 * Identical for the exchange-simulator and the payment-gateway.
 * <p>
 * The payload is kept as a {@link JsonNode}: interpreting it is the job of the translation slice that consumes the
 * event type, which is the only place an external schema may appear. This record is also what an {@code Inbox}
 * persists, so its component names are a persisted contract.
 *
 * @param eventId     unique per event; the deduplication key
 * @param sequence    the event's position in the source's log; monotonic, not contiguous per consumer
 * @param eventType   e.g. {@code TradeExecuted}
 * @param source      {@code exchange}, {@code settlement-simulator}, {@code clearinghouse}, {@code payment-gateway}, ...
 * @param aggregateId the source's own aggregate id (an order, an execution, a payment)
 * @param occurredAt  on the source's clock, which may be a virtual one
 */
public record ExternalEventEnvelope(String eventId,
                                    long sequence,
                                    String eventType,
                                    int schemaVersion,
                                    String source,
                                    String simulationId,
                                    String aggregateId,
                                    String correlationId,
                                    OffsetDateTime occurredAt,
                                    JsonNode payload) {

    /**
     * Reads an envelope from parsed JSON, checking the fields every consumer relies on.
     *
     * @return empty when a required field is missing or malformed, which the simulators' fault injection produces on
     * purpose. The caller answers non-2xx so the source retries with a fresh copy.
     */
    public static Optional<ExternalEventEnvelope> from(JsonNode json) {
        if (json == null || !json.isObject()) {
            return Optional.empty();
        }
        try {
            var eventId     = requiredText(json, "eventId");
            var eventType   = requiredText(json, "eventType");
            var source      = requiredText(json, "source");
            var aggregateId = requiredText(json, "aggregateId");
            var sequence    = json.get("sequence");
            var occurredAt  = requiredText(json, "occurredAt");
            var payload     = json.get("payload");
            if (eventId == null || eventType == null || source == null || aggregateId == null || occurredAt == null
                    || sequence == null || !sequence.canConvertToLong() || sequence.asLong() < 1
                    || payload == null || !payload.isObject()) {
                return Optional.empty();
            }
            var schemaVersion = json.get("schemaVersion");
            return Optional.of(new ExternalEventEnvelope(eventId,
                                                         sequence.asLong(),
                                                         eventType,
                                                         schemaVersion != null && schemaVersion.canConvertToInt() ? schemaVersion.asInt() : 1,
                                                         source,
                                                         optionalText(json, "simulationId"),
                                                         aggregateId,
                                                         optionalText(json, "correlationId"),
                                                         OffsetDateTime.parse(occurredAt),
                                                         payload));
        } catch (DateTimeException e) {
            return Optional.empty();
        }
    }

    public Instant occurredAtInstant() {
        return occurredAt.toInstant();
    }

    private static String requiredText(JsonNode json, String field) {
        var node = json.get(field);
        return node != null && node.isString() && !node.asString().isBlank() ? node.asString() : null;
    }

    private static String optionalText(JsonNode json, String field) {
        var node = json.get(field);
        return node != null && node.isString() ? node.asString() : null;
    }
}
