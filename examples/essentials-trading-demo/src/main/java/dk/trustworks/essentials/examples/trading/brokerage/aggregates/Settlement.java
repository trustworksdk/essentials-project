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

package dk.trustworks.essentials.examples.trading.brokerage.aggregates;

import dk.trustworks.essentials.components.eventsourced.aggregates.EventHandler;
import dk.trustworks.essentials.components.eventsourced.aggregates.stateful.modern.AggregateRoot;
import dk.trustworks.essentials.examples.trading.brokerage.events.ClearingConfirmed;
import dk.trustworks.essentials.examples.trading.brokerage.events.ClearingRequested;
import dk.trustworks.essentials.examples.trading.brokerage.events.SettlementClosed;
import dk.trustworks.essentials.examples.trading.brokerage.events.SettlementCreated;
import dk.trustworks.essentials.examples.trading.brokerage.events.SettlementEvent;
import dk.trustworks.essentials.examples.trading.brokerage.events.SettlementMarkedSettled;
import dk.trustworks.essentials.examples.trading.brokerage.events.SettlementReconciled;
import dk.trustworks.essentials.examples.trading.brokerage.types.SettlementId;
import dk.trustworks.essentials.examples.trading.brokerage.types.TradeId;
import dk.trustworks.essentials.examples.trading.brokerage.types.TradingAccountId;
import dk.trustworks.essentials.types.Amount;

import static dk.trustworks.essentials.shared.FailFast.requireNonNull;

/**
 * The post-trade lifecycle of one trade -- created, clearing requested, cleared, settled, reconciled, closed -- and the
 * consistency boundary for it.
 *
 * <p>An event-sourced {@link AggregateRoot}: its methods do not assign state, they {@code apply} a
 * {@link SettlementEvent}, and the {@code @EventHandler} methods at the bottom are the only place its state is ever
 * written.
 *
 * <p>The invariant it enforces is that the six steps happen in order and only while the settlement is open. Each guard
 * throws <em>before</em> any event is applied, so a rejected command leaves no trace in the stream, and each step is
 * separately idempotent -- repeating a step is a no-op, skipping one is not. {@link #closeSettlement()} is the one
 * exception to the idempotence: it has no "already closed" short-circuit because {@link #assertOpen()} has already
 * rejected the call by then.
 *
 * <p>This carried an {@code @AggregateClosingBooksPolicy} declaring EXPLICIT_COMMAND rollover, which never did
 * anything. A closing-books aggregate is keyed on a per-generation id with a separate logical id spanning generations,
 * the way {@link TradingAccount} is keyed on {@code TradingAccountGenerationId} alongside {@code TradingAccountId};
 * this one is keyed directly on {@link SettlementId}, so it has no generations to roll and none of the supporting
 * wiring existed. The annotation was removed rather than left advertising a capability the demo does not have.
 * Demonstrating EXPLICIT_COMMAND rollover here would mean introducing a settlement generation id and reworking the
 * settlement slices and projections around it.
 *
 * <p>Reached through {@link Settlements}. Commands are unpacked by the slice that handles them, so this class never
 * names a command type.
 */
public class Settlement extends AggregateRoot<SettlementId, SettlementEvent, Settlement> {
    private TradeId          tradeId;
    private TradingAccountId accountId;
    private Amount           grossAmount;
    private boolean          clearingRequested;
    private boolean          clearingConfirmed;
    private boolean          settled;
    private boolean          reconciled;
    private boolean          closed;

    /**
     * Only for a JSON deserializer restoring this aggregate. Not the rehydration constructor and not a creating one --
     * it exists so Jackson populates the fields directly instead of picking one of the two public constructors as an
     * implicit creator, which under Jackson 3 it otherwise would.
     */
    protected Settlement() {
    }

    /**
     * Used for rehydration
     */
    public Settlement(SettlementId settlementId) {
        super(settlementId);
    }

    public Settlement(SettlementId settlementId,
                      TradeId tradeId,
                      TradingAccountId accountId,
                      Amount grossAmount) {
        this(settlementId);
        requireNonNull(tradeId, "No tradeId provided");
        requireNonNull(accountId, "No accountId provided");
        requireNonNull(grossAmount, "No grossAmount provided");

        apply(new SettlementCreated(settlementId,
                                    tradeId,
                                    accountId,
                                    grossAmount));
    }

    public void requestClearing() {
        assertOpen();
        if (clearingRequested) {
            return;
        }
        apply(new ClearingRequested(aggregateId()));
    }

    public void confirmClearing() {
        assertOpen();
        if (!clearingRequested) {
            throw new IllegalStateException("Cannot confirm clearing before it has been requested");
        }
        if (clearingConfirmed) {
            return;
        }
        apply(new ClearingConfirmed(aggregateId()));
    }

    public void markSettled() {
        assertOpen();
        if (!clearingConfirmed) {
            throw new IllegalStateException("Cannot settle before clearing has been confirmed");
        }
        if (settled) {
            return;
        }
        apply(new SettlementMarkedSettled(aggregateId()));
    }

    public void reconcile() {
        assertOpen();
        if (!settled) {
            throw new IllegalStateException("Cannot reconcile before settlement has completed");
        }
        if (reconciled) {
            return;
        }
        apply(new SettlementReconciled(aggregateId()));
    }

    public void closeSettlement() {
        assertOpen();
        if (!reconciled) {
            throw new IllegalStateException("Cannot close settlement before reconciliation is complete");
        }
        apply(new SettlementClosed(aggregateId()));
    }

    private void assertOpen() {
        if (closed) {
            throw new IllegalStateException("Settlement is already closed");
        }
    }

    @EventHandler
    private void on(SettlementCreated event) {
        tradeId = event.tradeId();
        accountId = event.accountId();
        grossAmount = event.grossAmount();
        clearingRequested = false;
        clearingConfirmed = false;
        settled = false;
        reconciled = false;
        closed = false;
    }

    @EventHandler
    private void on(ClearingRequested event) {
        clearingRequested = true;
    }

    @EventHandler
    private void on(ClearingConfirmed event) {
        clearingConfirmed = true;
    }

    @EventHandler
    private void on(SettlementMarkedSettled event) {
        settled = true;
    }

    @EventHandler
    private void on(SettlementReconciled event) {
        reconciled = true;
    }

    @EventHandler
    private void on(SettlementClosed event) {
        closed = true;
    }
}
