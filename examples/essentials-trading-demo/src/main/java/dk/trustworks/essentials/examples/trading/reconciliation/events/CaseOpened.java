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

package dk.trustworks.essentials.examples.trading.reconciliation.events;

import dk.trustworks.essentials.examples.trading.reconciliation.types.CaseId;

import java.time.LocalDate;

import static dk.trustworks.essentials.shared.FailFast.requireNonNull;

/**
 * A break was still there at the next business day's close (Q-1), and an operator must look at it.
 *
 * @param kind      the break's kind: a {@code ClearingBreak.Kind}, a {@code StatementBreak.Kind}, or
 *                  {@code MISSING_STATEMENT}
 * @param reference the execution, payment, obligation or business date the break is about
 * @param detail    what the statement says against what the brokerage has, for the operator to read
 */
public record CaseOpened(CaseId caseId, String statementId, LocalDate businessDate, String kind, String reference, String detail) implements ReconciliationCaseEvent {
    public CaseOpened {
        requireNonNull(caseId, "No caseId provided");
        requireNonNull(statementId, "No statementId provided");
        requireNonNull(businessDate, "No businessDate provided");
        requireNonNull(kind, "No kind provided");
        requireNonNull(reference, "No reference provided");
        requireNonNull(detail, "No detail provided");
    }
}
