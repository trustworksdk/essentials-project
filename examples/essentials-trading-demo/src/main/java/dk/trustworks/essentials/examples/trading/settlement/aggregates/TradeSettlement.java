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

package dk.trustworks.essentials.examples.trading.settlement.aggregates;

import dk.trustworks.essentials.components.eventsourced.aggregates.EventHandler;
import dk.trustworks.essentials.components.eventsourced.aggregates.stateful.modern.AggregateRoot;
import dk.trustworks.essentials.examples.trading.accounts.types.*;
import dk.trustworks.essentials.examples.trading.market_data.types.InstrumentId;
import dk.trustworks.essentials.examples.trading.settlement.events.*;
import dk.trustworks.essentials.examples.trading.settlement.types.*;
import dk.trustworks.essentials.types.Money;

import java.time.LocalDate;

import static dk.trustworks.essentials.shared.FailFast.requireNonNull;

/**
 * One execution's settlement, as the exchange's settlement simulator reports it (design {@code phase-4-settlement.md}
 * §3). One stream per execution; created by the first report, whichever it is.
 * <p>
 * Every report is a fact, recorded once and in whatever order it arrives: a completion before the instruction implies
 * it, a delay after the outcome is ignored. Settled and abandoned are final, and the first of them wins. The one report
 * that cannot be recorded early is a correction before its completion; it is refused with
 * {@link SettlementNotYetRecordable} and redelivered.
 */
public class TradeSettlement extends AggregateRoot<ExecutionId, TradeSettlementEvent, TradeSettlement> {
    private AccountId       accountId;
    private InstrumentId    instrumentId;
    private TradeSide       side;
    private Money           amount;
    private SettlementState state;

    protected TradeSettlement() {
    }

    /**
     * Used for rehydration
     */
    public TradeSettlement(ExecutionId executionId) {
        super(executionId);
    }

    public TradeSettlement(ExecutionId executionId, AccountId accountId, InstrumentId instrumentId, TradeSide side, Money amount, LocalDate intendedSettlementDate) {
        super(executionId);
        apply(new SettlementInstructed(executionId, accountId, instrumentId, side, amount, intendedSettlementDate));
    }

    public void postpone(LocalDate intendedSettlementDate, int attempt) {
        requireNonNull(intendedSettlementDate, "No intendedSettlementDate provided");
        if (state != SettlementState.INSTRUCTED) {
            return;
        }
        apply(new SettlementPostponed(aggregateId(), accountId, intendedSettlementDate, attempt));
    }

    public void settle() {
        if (state != SettlementState.INSTRUCTED) {
            return;
        }
        apply(new SettlementSettled(aggregateId(), accountId));
    }

    public void abandon(String outcome, String reason) {
        requireNonNull(outcome, "No outcome provided");
        requireNonNull(reason, "No reason provided");
        if (state != SettlementState.INSTRUCTED) {
            return;
        }
        apply(new SettlementAbandoned(aggregateId(), accountId, instrumentId, side, outcome, reason));
    }

    public void correctAmount(Money previousAmount, Money newAmount) {
        requireNonNull(previousAmount, "No previousAmount provided");
        requireNonNull(newAmount, "No newAmount provided");
        if (state != SettlementState.SETTLED) {
            throw new SettlementNotYetRecordable("Settlement of '" + aggregateId() + "' corrected before it was recorded settled (" + state + ")");
        }
        if (newAmount.equals(amount)) {
            return;
        }
        apply(new SettlementAmountChanged(aggregateId(), accountId, instrumentId, side, amount, newAmount));
    }

    public SettlementState settlementState() {
        return state;
    }

    @EventHandler
    private void on(SettlementInstructed e) {
        accountId = e.accountId();
        instrumentId = e.instrumentId();
        side = e.side();
        amount = e.amount();
        state = SettlementState.INSTRUCTED;
    }

    @EventHandler
    private void on(SettlementSettled e) {
        state = SettlementState.SETTLED;
    }

    @EventHandler
    private void on(SettlementAbandoned e) {
        state = SettlementState.ABANDONED;
    }

    @EventHandler
    private void on(SettlementAmountChanged e) {
        amount = e.amount();
    }
}
