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

package dk.trustworks.essentials.examples.trading.accounts;

import dk.trustworks.essentials.components.eventsourced.aggregates.EventHandler;
import dk.trustworks.essentials.components.eventsourced.aggregates.closingbooks.AggregateClosingBooksPolicy;
import dk.trustworks.essentials.components.eventsourced.aggregates.closingbooks.ClosingBooksDefaultPolicyType;
import dk.trustworks.essentials.components.eventsourced.aggregates.closingbooks.ClosingBooksTimeBoundary;
import dk.trustworks.essentials.components.eventsourced.aggregates.closingbooks.ClosingBooksTriggerMode;
import dk.trustworks.essentials.components.eventsourced.aggregates.snapshot.AggregateSnapshotPolicy;
import dk.trustworks.essentials.components.eventsourced.aggregates.snapshot.SnapshotExecutionMode;
import dk.trustworks.essentials.components.eventsourced.aggregates.stateful.modern.AggregateRoot;

import java.math.BigDecimal;

import static dk.trustworks.essentials.shared.FailFast.requireNonNull;

/**
 * Trading account aggregate used to demonstrate a realistic aggregate that benefits from
 * both snapshots and closing books.
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
    public TradingAccountId logicalAccountId;
    public String ownerId;
    public String periodId;
    public BigDecimal cashBalance;
    public BigDecimal reservedFunds;
    public BigDecimal realizedPnl;
    public boolean booksClosed;

    protected TradingAccount() {
    }

    /**
     * Used for rehydration.
     */
    public TradingAccount(TradingAccountGenerationId streamAggregateId) {
        super(streamAggregateId);
    }

    public TradingAccount(TradingAccountGenerationId streamAggregateId,
                          TradingAccountId logicalAccountId,
                          String ownerId,
                          String periodId) {
        this(streamAggregateId,
             logicalAccountId,
             ownerId,
             periodId,
             BigDecimal.ZERO,
             BigDecimal.ZERO);
    }

    public TradingAccount(TradingAccountGenerationId streamAggregateId,
                          TradingAccountId logicalAccountId,
                          String ownerId,
                          String periodId,
                          BigDecimal openingCashBalance,
                          BigDecimal openingRealizedPnl) {
        this(streamAggregateId);
        requireNonNull(logicalAccountId, "No logicalAccountId provided");
        requireNonNull(ownerId, "No ownerId provided");
        requireNonNull(periodId, "No periodId provided");
        requireNonNull(openingCashBalance, "No openingCashBalance provided");
        requireNonNull(openingRealizedPnl, "No openingRealizedPnl provided");

        apply(new TradingAccountEvent.TradingAccountOpened(streamAggregateId,
                                                           logicalAccountId,
                                                           ownerId,
                                                           periodId,
                                                           openingCashBalance,
                                                           openingRealizedPnl));
    }

    public void depositCash(BigDecimal amount) {
        assertBooksOpen();
        apply(new TradingAccountEvent.CashDeposited(aggregateId(),
                                                    logicalAccountId,
                                                    amount));
    }

    public void reserveFunds(BigDecimal amount) {
        assertBooksOpen();
        requireNonNull(amount, "No amount provided");
        if (cashBalance.subtract(reservedFunds).compareTo(amount) < 0) {
            throw new IllegalStateException("Insufficient available cash to reserve funds");
        }
        apply(new TradingAccountEvent.FundsReserved(aggregateId(),
                                                    logicalAccountId,
                                                    amount));
    }

    public void releaseFunds(BigDecimal amount) {
        assertBooksOpen();
        requireNonNull(amount, "No amount provided");
        if (reservedFunds.compareTo(amount) < 0) {
            throw new IllegalStateException("Cannot release more funds than currently reserved");
        }
        apply(new TradingAccountEvent.FundsReleased(aggregateId(),
                                                    logicalAccountId,
                                                    amount));
    }

    public void applyTradeSettlement(String tradeId,
                                     BigDecimal cashDelta,
                                     BigDecimal realizedPnlDelta) {
        assertBooksOpen();
        apply(new TradingAccountEvent.TradeSettlementApplied(aggregateId(),
                                                             logicalAccountId,
                                                             tradeId,
                                                             cashDelta,
                                                             realizedPnlDelta));
    }

    public void closeBooks(String nextPeriodId) {
        if (booksClosed) {
            return;
        }
        apply(eventOrder -> new TradingAccountEvent.AccountBooksClosed(aggregateId(),
                                                                       logicalAccountId,
                                                                       nextPeriodId,
                                                                       eventOrder));
    }

    private void assertBooksOpen() {
        if (booksClosed) {
            throw new IllegalStateException("Trading account books are already closed for period '" + periodId + "'");
        }
    }

    @EventHandler
    private void on(TradingAccountEvent.TradingAccountOpened event) {
        logicalAccountId = event.logicalAccountId;
        ownerId = event.ownerId;
        periodId = event.periodId;
        cashBalance = event.openingCashBalance;
        reservedFunds = BigDecimal.ZERO;
        realizedPnl = event.openingRealizedPnl;
        booksClosed = false;
    }

    @EventHandler
    private void on(TradingAccountEvent.CashDeposited event) {
        cashBalance = cashBalance.add(event.amount);
    }

    @EventHandler
    private void on(TradingAccountEvent.FundsReserved event) {
        reservedFunds = reservedFunds.add(event.amount);
    }

    @EventHandler
    private void on(TradingAccountEvent.FundsReleased event) {
        reservedFunds = reservedFunds.subtract(event.amount);
    }

    @EventHandler
    private void on(TradingAccountEvent.TradeSettlementApplied event) {
        cashBalance = cashBalance.add(event.cashDelta);
        realizedPnl = realizedPnl.add(event.realizedPnlDelta);
    }

    @EventHandler
    private void on(TradingAccountEvent.AccountBooksClosed event) {
        booksClosed = true;
        reservedFunds = BigDecimal.ZERO;
    }
}
