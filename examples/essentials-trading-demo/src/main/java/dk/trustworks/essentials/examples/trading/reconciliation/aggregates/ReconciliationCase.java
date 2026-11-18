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

import dk.trustworks.essentials.components.eventsourced.aggregates.EventHandler;
import dk.trustworks.essentials.components.eventsourced.aggregates.stateful.modern.AggregateRoot;
import dk.trustworks.essentials.examples.trading.accounts.types.AdjustmentId;
import dk.trustworks.essentials.examples.trading.reconciliation.events.*;
import dk.trustworks.essentials.examples.trading.reconciliation.types.*;

import java.time.LocalDate;
import java.util.Objects;

import static dk.trustworks.essentials.shared.FailFast.requireNonNull;

/**
 * An operator case for one reconciliation break (design phase 5 §4), id = the break's key. Opened by
 * {@code automations/open_cases}, assigned and resolved by an operator, or cleared when the break goes away first.
 * <p>
 * Resolving never books anything (Q-2): a {@link CaseResolution#LEDGER_CORRECTED} case only records the adjustment the
 * operator posted. Repeating the last request is a no-op, so an operator's retry is safe; changing a closed case is
 * refused.
 */
public class ReconciliationCase extends AggregateRoot<CaseId, ReconciliationCaseEvent, ReconciliationCase> {
    private CaseState      state;
    private String         assignee;
    private CaseResolution resolution;

    protected ReconciliationCase() {
    }

    /**
     * Used for rehydration
     */
    public ReconciliationCase(CaseId caseId) {
        super(caseId);
    }

    public ReconciliationCase(CaseId caseId, String statementId, LocalDate businessDate, String kind, String reference, String detail) {
        super(caseId);
        apply(new CaseOpened(caseId, statementId, businessDate, kind, reference, detail));
    }

    public void assign(String assignee, String assignedBy) {
        requireNonNull(assignee, "No assignee provided");
        requireNonNull(assignedBy, "No assignedBy provided");
        if (state.isClosed()) {
            throw new CaseRuleViolation("Case '" + aggregateId() + "' is " + state + " and cannot be assigned");
        }
        if (assignee.equals(this.assignee)) {
            return;
        }
        apply(new CaseAssigned(aggregateId(), assignee, assignedBy));
    }

    public void resolve(CaseResolution resolution, String note, AdjustmentId adjustmentId, String resolvedBy) {
        requireNonNull(resolution, "No resolution provided");
        requireNonNull(note, "No note provided");
        requireNonNull(resolvedBy, "No resolvedBy provided");
        if (state == CaseState.RESOLVED && resolution == this.resolution) {
            return;
        }
        if (state.isClosed()) {
            throw new CaseRuleViolation("Case '" + aggregateId() + "' is " + state + " and cannot be resolved");
        }
        if (resolution == CaseResolution.LEDGER_CORRECTED && adjustmentId == null) {
            throw new CaseRuleViolation("A " + resolution + " resolution names the adjustment that corrected the books");
        }
        apply(new CaseResolved(aggregateId(), resolution, note, resolution == CaseResolution.LEDGER_CORRECTED ? adjustmentId : null, resolvedBy));
    }

    /**
     * The break is gone. A no-op for a closed case: a resolved case stays resolved whatever the books say now.
     */
    public void clear() {
        if (state.isClosed()) {
            return;
        }
        apply(new CaseCleared(aggregateId()));
    }

    public CaseState caseState() {
        return state;
    }

    @EventHandler
    private void on(CaseOpened e) {
        state = CaseState.OPEN;
    }

    @EventHandler
    private void on(CaseAssigned e) {
        assignee = e.assignee();
        state = CaseState.ASSIGNED;
    }

    @EventHandler
    private void on(CaseResolved e) {
        resolution = e.resolution();
        state = CaseState.RESOLVED;
    }

    @EventHandler
    private void on(CaseCleared e) {
        state = CaseState.CLEARED;
    }
}
