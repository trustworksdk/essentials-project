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
package dk.trustworks.essentials.examples.trading.reconciliation.aggregates;

import dk.trustworks.essentials.examples.trading.accounts.types.AdjustmentId;
import dk.trustworks.essentials.examples.trading.reconciliation.events.*;
import dk.trustworks.essentials.examples.trading.reconciliation.types.*;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;

import static org.assertj.core.api.Assertions.*;

class ReconciliationCaseTest {
    private static final CaseId CASE = CaseId.of("CS:2030-01-07:DEMO-BROKER", "UNKNOWN_TRADE", "EX-PHANTOM");

    private static ReconciliationCase opened() {
        return new ReconciliationCase(CASE, "CS:2030-01-07:DEMO-BROKER", LocalDate.of(2030, 1, 7), "UNKNOWN_TRADE", "EX-PHANTOM", "statement: 4 for 400.4; ours: none");
    }

    @Test
    void a_case_is_assigned_then_resolved_and_a_repeated_request_is_a_no_op() {
        var reconciliationCase = opened();
        reconciliationCase.assign("ops-1", "operator@demo.local");
        reconciliationCase.assign("ops-1", "operator@demo.local");
        reconciliationCase.resolve(CaseResolution.COUNTERPARTY_ERROR, "phantom line", null, "operator@demo.local");
        reconciliationCase.resolve(CaseResolution.COUNTERPARTY_ERROR, "phantom line", null, "operator@demo.local");

        assertThat(reconciliationCase.getUncommittedChanges().events).hasExactlyElementsOfTypes(CaseOpened.class, CaseAssigned.class, CaseResolved.class);
        assertThat(reconciliationCase.caseState()).isEqualTo(CaseState.RESOLVED);
    }

    @Test
    void a_resolved_case_is_neither_cleared_reassigned_nor_resolved_otherwise() {
        var reconciliationCase = opened();
        reconciliationCase.resolve(CaseResolution.WRITTEN_OFF, "immaterial", null, "operator@demo.local");
        reconciliationCase.clear();

        assertThatThrownBy(() -> reconciliationCase.assign("ops-2", "operator@demo.local")).isInstanceOf(CaseRuleViolation.class);
        assertThatThrownBy(() -> reconciliationCase.resolve(CaseResolution.COUNTERPARTY_ERROR, "", null, "operator@demo.local")).isInstanceOf(CaseRuleViolation.class);
        assertThat(reconciliationCase.getUncommittedChanges().events).hasExactlyElementsOfTypes(CaseOpened.class, CaseResolved.class);
    }

    @Test
    void a_ledger_correction_names_its_adjustment_and_records_it() {
        var reconciliationCase = opened();
        assertThatThrownBy(() -> reconciliationCase.resolve(CaseResolution.LEDGER_CORRECTED, "booked", null, "operator@demo.local")).isInstanceOf(CaseRuleViolation.class);
        reconciliationCase.resolve(CaseResolution.LEDGER_CORRECTED, "booked", AdjustmentId.of("ADJ-1"), "operator@demo.local");

        assertThat(reconciliationCase.getUncommittedChanges().events).filteredOn(CaseResolved.class::isInstance).singleElement()
                                                                     .satisfies(e -> assertThat((Object) ((CaseResolved) e).adjustmentId()).isEqualTo(AdjustmentId.of("ADJ-1")));
    }

    @Test
    void a_case_whose_break_has_gone_is_cleared_once() {
        var reconciliationCase = opened();
        reconciliationCase.assign("ops-1", "operator@demo.local");
        reconciliationCase.clear();
        reconciliationCase.clear();

        assertThat(reconciliationCase.getUncommittedChanges().events).hasExactlyElementsOfTypes(CaseOpened.class, CaseAssigned.class, CaseCleared.class);
        assertThat(reconciliationCase.caseState()).isEqualTo(CaseState.CLEARED);
    }
}
