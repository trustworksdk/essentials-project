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

package dk.trustworks.essentials.examples.trading.settlements;

import dk.trustworks.essentials.components.eventsourced.aggregates.EventHandler;
import dk.trustworks.essentials.components.eventsourced.aggregates.stateful.modern.AggregateRoot;

import java.math.BigDecimal;

import static dk.trustworks.essentials.shared.FailFast.requireNonNull;

/**
 * Settlement aggregate, demonstrating a multi-step lifecycle — created, clearing requested, cleared, settled,
 * reconciled, closed — without snapshotting or closing books.
 * <p>
 * This carried an {@code @AggregateClosingBooksPolicy} declaring EXPLICIT_COMMAND rollover, which never did anything.
 * A closing-books aggregate is keyed on a per-generation id with a separate logical id spanning generations, the way
 * {@code TradingAccount} is keyed on {@code TradingAccountGenerationId} alongside {@code TradingAccountId}; this one is
 * keyed directly on {@code SettlementId}, so it has no generations to roll and none of the supporting wiring existed.
 * The annotation was removed rather than left advertising a capability the demo does not have. Demonstrating
 * EXPLICIT_COMMAND rollover here would mean introducing a settlement generation id and reworking the settlement
 * service and projections around it.
 */
public class Settlement extends AggregateRoot<SettlementId, SettlementEvent, Settlement> {
    public String tradeId;
    public String accountId;
    public BigDecimal grossAmount;
    public boolean clearingRequested;
    public boolean clearingConfirmed;
    public boolean settled;
    public boolean reconciled;
    public boolean closed;

    protected Settlement() {
    }

    /**
     * Used for rehydration.
     */
    public Settlement(SettlementId settlementId) {
        super(settlementId);
    }

    public Settlement(SettlementId settlementId,
                      String tradeId,
                      String accountId,
                      BigDecimal grossAmount) {
        this(settlementId);
        requireNonNull(tradeId, "No tradeId provided");
        requireNonNull(accountId, "No accountId provided");
        requireNonNull(grossAmount, "No grossAmount provided");

        apply(new SettlementEvent.SettlementCreated(settlementId,
                                                    tradeId,
                                                    accountId,
                                                    grossAmount));
    }

    public void requestClearing() {
        assertOpen();
        if (clearingRequested) {
            return;
        }
        apply(new SettlementEvent.ClearingRequested(aggregateId()));
    }

    public void confirmClearing() {
        assertOpen();
        if (!clearingRequested) {
            throw new IllegalStateException("Cannot confirm clearing before it has been requested");
        }
        if (clearingConfirmed) {
            return;
        }
        apply(new SettlementEvent.ClearingConfirmed(aggregateId()));
    }

    public void markSettled() {
        assertOpen();
        if (!clearingConfirmed) {
            throw new IllegalStateException("Cannot settle before clearing has been confirmed");
        }
        if (settled) {
            return;
        }
        apply(new SettlementEvent.SettlementMarkedSettled(aggregateId()));
    }

    public void reconcile() {
        assertOpen();
        if (!settled) {
            throw new IllegalStateException("Cannot reconcile before settlement has completed");
        }
        if (reconciled) {
            return;
        }
        apply(new SettlementEvent.SettlementReconciled(aggregateId()));
    }

    public void closeSettlement() {
        assertOpen();
        if (!reconciled) {
            throw new IllegalStateException("Cannot close settlement before reconciliation is complete");
        }
        apply(new SettlementEvent.SettlementClosed(aggregateId()));
    }

    private void assertOpen() {
        if (closed) {
            throw new IllegalStateException("Settlement is already closed");
        }
    }

    @EventHandler
    private void on(SettlementEvent.SettlementCreated event) {
        tradeId = event.tradeId;
        accountId = event.accountId;
        grossAmount = event.grossAmount;
        clearingRequested = false;
        clearingConfirmed = false;
        settled = false;
        reconciled = false;
        closed = false;
    }

    @EventHandler
    private void on(SettlementEvent.ClearingRequested event) {
        clearingRequested = true;
    }

    @EventHandler
    private void on(SettlementEvent.ClearingConfirmed event) {
        clearingConfirmed = true;
    }

    @EventHandler
    private void on(SettlementEvent.SettlementMarkedSettled event) {
        settled = true;
    }

    @EventHandler
    private void on(SettlementEvent.SettlementReconciled event) {
        reconciled = true;
    }

    @EventHandler
    private void on(SettlementEvent.SettlementClosed event) {
        closed = true;
    }
}
