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
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

import java.time.*;

import static org.assertj.core.api.Assertions.assertThat;

class PaymentEventTranslationTest {
    private static final JsonMapper JSON = JsonMapper.builder().build();

    @Test
    void each_payment_event_becomes_the_outcome_of_the_payment_its_idempotency_key_names() {
        assertThat(PaymentEventTranslation.toReport(event("PaymentPending", """
                {"idempotencyKey": "DEP-1", "type": "DEPOSIT", "paymentId": "P-1", "status": "PENDING"}""")))
                .isEqualTo(RecordPaymentOutcome.initiated(PaymentKind.DEPOSIT, "DEP-1", PaymentId.of("P-1")));
        assertThat(PaymentEventTranslation.toReport(event("PaymentCompleted", """
                {"idempotencyKey": "WDR-1", "type": "WITHDRAWAL", "status": "COMPLETED", "valueDate": "2030-01-08"}""")))
                .isEqualTo(RecordPaymentOutcome.completed(PaymentKind.WITHDRAWAL, "WDR-1", LocalDate.of(2030, 1, 8)));
        assertThat(PaymentEventTranslation.toReport(event("PaymentFailed", """
                {"idempotencyKey": "WDR-1", "type": "WITHDRAWAL", "status": "FAILED", "reason": "ACCOUNT_CLOSED"}""")))
                .isEqualTo(RecordPaymentOutcome.failed(PaymentKind.WITHDRAWAL, "WDR-1", "ACCOUNT_CLOSED"));
        assertThat(PaymentEventTranslation.toReport(event("PaymentReturned", """
                {"idempotencyKey": "DEP-1", "type": "DEPOSIT", "status": "RETURNED", "reason": "RETURNED_BY_BANK", "valueDate": "2030-01-10"}""")))
                .isEqualTo(RecordPaymentOutcome.returned(PaymentKind.DEPOSIT, "DEP-1", LocalDate.of(2030, 1, 10), "RETURNED_BY_BANK"));
    }

    @Test
    void a_completion_or_return_without_its_value_date_is_incomplete() {
        assertThat(PaymentEventTranslation.isComplete(event("PaymentCompleted", """
                {"idempotencyKey": "DEP-1", "type": "DEPOSIT", "status": "COMPLETED", "completedAt": "2030-01-08T15:00:00Z"}"""))).isFalse();
        assertThat(PaymentEventTranslation.isComplete(event("PaymentReturned", """
                {"idempotencyKey": "DEP-1", "type": "DEPOSIT", "status": "RETURNED", "reason": "RETURNED_BY_BANK"}"""))).isFalse();
        assertThat(PaymentEventTranslation.isComplete(event("PaymentFailed", """
                {"idempotencyKey": "DEP-1", "type": "SOMETHING", "reason": "X"}"""))).as("unknown type").isFalse();
    }

    private static ExternalEventEnvelope event(String eventType, String payload) {
        return new ExternalEventEnvelope("e-1", 1, eventType, 1, "payment-gateway", null, "payment-1", null, OffsetDateTime.parse("2030-01-07T15:00:00Z"), JSON.readTree(payload));
    }
}
