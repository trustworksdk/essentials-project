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
import dk.trustworks.essentials.components.eventsourced.aggregates.closingbooks.AggregateClosingBooksPolicy;
import dk.trustworks.essentials.components.eventsourced.aggregates.closingbooks.ClosingBooksDefaultPolicyType;
import dk.trustworks.essentials.components.eventsourced.aggregates.closingbooks.ClosingBooksTimeBoundary;
import dk.trustworks.essentials.components.eventsourced.aggregates.closingbooks.ClosingBooksTriggerMode;
import dk.trustworks.essentials.components.eventsourced.aggregates.snapshot.AggregateSnapshotPolicy;
import dk.trustworks.essentials.components.eventsourced.aggregates.snapshot.SnapshotExecutionMode;
import dk.trustworks.essentials.components.eventsourced.aggregates.stateful.modern.AggregateRoot;
import dk.trustworks.essentials.examples.trading.brokerage.events.AccountBooksClosed;
import dk.trustworks.essentials.examples.trading.brokerage.events.CashDeposited;
import dk.trustworks.essentials.examples.trading.brokerage.events.FundsReleased;
import dk.trustworks.essentials.examples.trading.brokerage.events.FundsReserved;
import dk.trustworks.essentials.examples.trading.brokerage.events.TradeSettlementApplied;
import dk.trustworks.essentials.examples.trading.brokerage.events.TradingAccountEvent;
import dk.trustworks.essentials.examples.trading.brokerage.events.TradingAccountOpened;
import dk.trustworks.essentials.examples.trading.brokerage.types.OwnerId;
import dk.trustworks.essentials.examples.trading.brokerage.types.PeriodId;
import dk.trustworks.essentials.examples.trading.brokerage.types.TradeId;
import dk.trustworks.essentials.examples.trading.brokerage.types.TradingAccountGenerationId;
import dk.trustworks.essentials.examples.trading.brokerage.types.TradingAccountId;
import dk.trustworks.essentials.types.Amount;

import static dk.trustworks.essentials.shared.FailFast.requireNonNull;

/**
 * A trading account's cash position for one accounting period, and the consistency boundary for every change to it.
 *
 * <p>An event-sourced {@link AggregateRoot}: its methods do not assign state, they {@code apply} a
 * {@link TradingAccountEvent}, and the {@code @EventHandler} methods at the bottom are the only place the balances are
 * ever written. The same handlers run on rehydration, so replaying history and handling a new command follow the
 * identical path.
 *
 * <p>Two ids, deliberately. It is keyed on {@link TradingAccountGenerationId} -- the stream of one books generation --
 * while {@link TradingAccountId} is the account the caller knows and spans every generation. Closing books does not
 * mutate this aggregate into a new period; it seals this stream and opens the next one, with
 * {@code TradingAccountNextGenerationFactory} deciding what carries across.
 *
 * <p>The invariants it enforces, all of them <em>before</em> any event is applied so a rejected command leaves no
 * trace in the stream:
 * <ul>
 *   <li>nothing may be booked once the books are closed ({@link #assertBooksOpen()})</li>
 *   <li>funds may not be reserved beyond the cash that is not already reserved</li>
 *   <li>more may not be released than is currently reserved</li>
 *   <li>deposits and reservations are strictly positive -- enforced by the event records themselves</li>
 * </ul>
 *
 * <p>{@link #closeBooks} is an idempotent no-op once closed, rather than a failure: an ON_ACCESS rollover can reach
 * an account another caller has already rolled, and that is not an error.
 *
 * <p>Reached through {@link TradingAccounts}. Commands are unpacked by the slice that handles them, so this class
 * never names a command type.
 */
@AggregateSnapshotPolicy(aggregateType = "TradingAccounts",
                         mode = SnapshotExecutionMode.SYNC,
                         everyNEvents = 100)
@AggregateClosingBooksPolicy(aggregateType = "TradingAccounts",
                             triggerMode = ClosingBooksTriggerMode.ON_ACCESS,
                             defaultPolicy = ClosingBooksDefaultPolicyType.EVENT_COUNT_OR_TIME_BOUNDARY,
                             eventThreshold = 100,
                             timeBoundary = ClosingBooksTimeBoundary.END_OF_MONTH,
                             zoneId = "Europe/Copenhagen")
public class TradingAccount extends AggregateRoot<TradingAccountGenerationId, TradingAccountEvent, TradingAccount> {
    private TradingAccountId logicalAccountId;
    private OwnerId          ownerId;
    private PeriodId         periodId;
    private Amount           cashBalance;
    private Amount           reservedFunds;
    private Amount           realizedPnl;
    private boolean          booksClosed;

    /**
     * Only for the JSON deserializer that restores an aggregate snapshot. Not the rehydration constructor and not a
     * creating one -- it exists so Jackson populates the fields directly instead of picking one of the two public
     * constructors as an implicit creator, which under Jackson 3 it otherwise would.
     */
    protected TradingAccount() {
    }

    /**
     * Used for rehydration
     */
    public TradingAccount(TradingAccountGenerationId streamAggregateId) {
        super(streamAggregateId);
    }

    public TradingAccount(TradingAccountGenerationId streamAggregateId,
                          TradingAccountId logicalAccountId,
                          OwnerId ownerId,
                          PeriodId periodId) {
        this(streamAggregateId,
             logicalAccountId,
             ownerId,
             periodId,
             Amount.ZERO,
             Amount.ZERO);
    }

    public TradingAccount(TradingAccountGenerationId streamAggregateId,
                          TradingAccountId logicalAccountId,
                          OwnerId ownerId,
                          PeriodId periodId,
                          Amount openingCashBalance,
                          Amount openingRealizedPnl) {
        this(streamAggregateId);
        requireNonNull(logicalAccountId, "No logicalAccountId provided");
        requireNonNull(ownerId, "No ownerId provided");
        requireNonNull(periodId, "No periodId provided");
        requireNonNull(openingCashBalance, "No openingCashBalance provided");
        requireNonNull(openingRealizedPnl, "No openingRealizedPnl provided");

        apply(new TradingAccountOpened(streamAggregateId,
                                       logicalAccountId,
                                       ownerId,
                                       periodId,
                                       openingCashBalance,
                                       openingRealizedPnl));
    }

    public void depositCash(Amount amount) {
        assertBooksOpen();
        apply(new CashDeposited(aggregateId(),
                                logicalAccountId,
                                amount));
    }

    public void reserveFunds(Amount amount) {
        assertBooksOpen();
        requireNonNull(amount, "No amount provided");
        if (cashBalance.subtract(reservedFunds).compareTo(amount) < 0) {
            throw new IllegalStateException("Insufficient available cash to reserve funds");
        }
        apply(new FundsReserved(aggregateId(),
                                logicalAccountId,
                                amount));
    }

    public void releaseFunds(Amount amount) {
        assertBooksOpen();
        requireNonNull(amount, "No amount provided");
        if (reservedFunds.compareTo(amount) < 0) {
            throw new IllegalStateException("Cannot release more funds than currently reserved");
        }
        apply(new FundsReleased(aggregateId(),
                                logicalAccountId,
                                amount));
    }

    public void applyTradeSettlement(TradeId tradeId,
                                     Amount cashDelta,
                                     Amount realizedPnlDelta) {
        assertBooksOpen();
        apply(new TradeSettlementApplied(aggregateId(),
                                         logicalAccountId,
                                         tradeId,
                                         cashDelta,
                                         realizedPnlDelta));
    }

    public void closeBooks(PeriodId nextPeriodId) {
        if (booksClosed) {
            return;
        }
        apply(eventOrder -> new AccountBooksClosed(aggregateId(),
                                                   logicalAccountId,
                                                   nextPeriodId,
                                                   eventOrder));
    }

    /**
     * The owner, carried forward unchanged when the books roll. Package-private: only
     * {@link TradingAccountNextGenerationFactory} needs it, and it lives in this package.
     */
    OwnerId ownerId() {
        return ownerId;
    }

    /**
     * The period the books are currently open in. Package-private: only {@link TradingAccountClosingBooksPolicy} needs
     * it, to decide whether a time boundary has been crossed.
     */
    PeriodId periodId() {
        return periodId;
    }

    /**
     * The cash the next generation opens on when the books roll. Package-private: only
     * {@link TradingAccountNextGenerationFactory} needs it.
     */
    Amount cashBalance() {
        return cashBalance;
    }

    private void assertBooksOpen() {
        if (booksClosed) {
            throw new IllegalStateException("Trading account books are already closed for period '" + periodId + "'");
        }
    }

    @EventHandler
    private void on(TradingAccountOpened event) {
        logicalAccountId = event.logicalAccountId();
        ownerId = event.ownerId();
        periodId = event.periodId();
        cashBalance = event.openingCashBalance();
        reservedFunds = Amount.ZERO;
        realizedPnl = event.openingRealizedPnl();
        booksClosed = false;
    }

    @EventHandler
    private void on(CashDeposited event) {
        cashBalance = cashBalance.add(event.amount());
    }

    @EventHandler
    private void on(FundsReserved event) {
        reservedFunds = reservedFunds.add(event.amount());
    }

    @EventHandler
    private void on(FundsReleased event) {
        reservedFunds = reservedFunds.subtract(event.amount());
    }

    @EventHandler
    private void on(TradeSettlementApplied event) {
        cashBalance = cashBalance.add(event.cashDelta());
        realizedPnl = realizedPnl.add(event.realizedPnlDelta());
    }

    @EventHandler
    private void on(AccountBooksClosed event) {
        booksClosed = true;
        reservedFunds = Amount.ZERO;
    }
}
