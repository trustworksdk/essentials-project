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
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

import java.time.*;

import static org.assertj.core.api.Assertions.assertThat;

class SettlementEventTranslationTest {
    private static final JsonMapper JSON = JsonMapper.builder().build();

    @Test
    void a_correction_carries_the_amount_before_and_after() {
        var event = event("SettlementCorrected", """
                {"executionId": "EX-1", "settlementAmount": "100.09", "previousSettlementAmount": "100.10", "currency": "USD",
                 "intendedSettlementDate": "2030-01-08", "attempt": 1, "status": "CORRECTED", "reason": "SIMULATED_CORRECTION"}""");

        assertThat(SettlementEventTranslation.isComplete(event)).isTrue();
        assertThat(SettlementEventTranslation.toReport(event))
                .isEqualTo(new RecordSettlementReport(ExecutionId.of("EX-1"), Kind.CORRECTED, Money.of("100.09", "USD"), LocalDate.of(2030, 1, 8), 1,
                                                      Money.of("100.10", "USD"), "SIMULATED_CORRECTION"));
    }

    @Test
    void a_failure_without_its_reason_or_a_copy_without_its_amount_is_incomplete() {
        assertThat(SettlementEventTranslation.isComplete(event("SettlementFailed", """
                {"executionId": "EX-1", "settlementAmount": "100.10", "currency": "USD", "intendedSettlementDate": "2030-01-08"}"""))).isFalse();
        assertThat(SettlementEventTranslation.isComplete(event("SettlementCompleted", """
                {"executionId": "EX-1", "currency": "USD", "intendedSettlementDate": "2030-01-08"}"""))).isFalse();
        assertThat(SettlementEventTranslation.isComplete(event("SettlementCompleted", """
                {"executionId": "EX-1", "settlementAmount": "100.10", "currency": "USD", "intendedSettlementDate": "2030-01-08"}"""))).isTrue();
    }

    private static ExternalEventEnvelope event(String eventType, String payload) {
        return new ExternalEventEnvelope("e-1", 1, eventType, 1, "settlement-simulator", null, "EX-1", null, OffsetDateTime.parse("2030-01-08T22:00:00Z"), JSON.readTree(payload));
    }
}
