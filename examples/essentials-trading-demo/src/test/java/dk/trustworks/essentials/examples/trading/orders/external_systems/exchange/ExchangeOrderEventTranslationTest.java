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
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

import java.time.OffsetDateTime;

import static org.assertj.core.api.Assertions.*;

class ExchangeOrderEventTranslationTest {
    private static final JsonMapper JSON = JsonMapper.builder().build();

    @Test
    void an_acceptance_is_recorded_on_the_order_named_by_the_client_order_id() {
        var event = event("OrderAccepted", """
                {"clientOrderId": "ORD-1", "status": "OPEN"}""");

        assertThat(ExchangeOrderEventTranslation.isComplete(event)).isTrue();
        assertThat(ExchangeOrderEventTranslation.toReport(event)).isEqualTo(RecordExchangeAnswer.accepted(OrderId.of("ORD-1")));
    }

    @Test
    void a_rejection_carries_the_exchange_reason() {
        var event = event("OrderRejected", """
                {"clientOrderId": "ORD-1", "status": "REJECTED", "rejectReason": "MARKET_CLOSED"}""");

        assertThat(ExchangeOrderEventTranslation.toReport(event)).isEqualTo(RecordExchangeAnswer.rejected(OrderId.of("ORD-1"), "MARKET_CLOSED"));
    }

    @Test
    void a_cancellation_carries_the_exchange_reason() {
        var event = event("OrderCancelled", """
                {"clientOrderId": "ORD-1", "status": "CANCELLED", "cancelReason": "IOC_REMAINDER"}""");

        assertThat(ExchangeOrderEventTranslation.toReport(event)).isEqualTo(new RecordCancellation(OrderId.of("ORD-1"), "IOC_REMAINDER"));
    }

    @Test
    void a_copy_missing_what_its_translation_needs_is_incomplete() {
        assertThat(ExchangeOrderEventTranslation.isComplete(event("OrderAccepted", """
                {"status": "OPEN"}"""))).as("no clientOrderId").isFalse();
        assertThat(ExchangeOrderEventTranslation.isComplete(event("OrderAccepted", """
                {"clientOrderId": null, "status": "OPEN"}"""))).as("null clientOrderId").isFalse();
        assertThat(ExchangeOrderEventTranslation.isComplete(event("OrderRejected", """
                {"clientOrderId": "ORD-1"}"""))).as("no rejectReason").isFalse();
        assertThat(ExchangeOrderEventTranslation.isComplete(event("OrderCancelled", """
                {"clientOrderId": "ORD-1", "cancelReason": 7}"""))).as("cancelReason not a string").isFalse();
    }

    @Test
    void fill_events_are_not_translated_until_they_carry_the_execution() {
        var event = event("OrderFilled", """
                {"clientOrderId": "ORD-1", "status": "FILLED", "filledQuantity": 5}""");

        assertThat(ExchangeOrderEventTranslation.EVENT_TYPES).doesNotContain("OrderFilled", "OrderPartiallyFilled");
        assertThat(ExchangeOrderEventTranslation.isComplete(event)).isFalse();
        assertThatThrownBy(() -> ExchangeOrderEventTranslation.toReport(event)).isInstanceOf(IllegalArgumentException.class);
    }

    private static ExternalEventEnvelope event(String eventType, String payload) {
        return new ExternalEventEnvelope("e-1", 1, eventType, 1, "exchange", null, "exchange-order-1", null, OffsetDateTime.parse("2030-01-07T15:00:00Z"), JSON.readTree(payload));
    }
}
