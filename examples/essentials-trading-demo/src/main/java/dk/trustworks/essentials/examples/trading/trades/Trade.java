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

package dk.trustworks.essentials.examples.trading.trades;

import dk.trustworks.essentials.components.eventsourced.aggregates.EventHandler;
import dk.trustworks.essentials.components.eventsourced.aggregates.stateful.modern.AggregateRoot;
import dk.trustworks.essentials.examples.trading.accounts.TradingAccountId;
import dk.trustworks.essentials.examples.trading.instruments.InstrumentId;

import java.math.BigDecimal;

import static dk.trustworks.essentials.shared.FailFast.requireNonNull;

/**
 * Trade aggregate used to model order execution before settlement processing begins.
 */
public class Trade extends AggregateRoot<TradeId, TradeEvent, Trade> {
    public TradingAccountId accountId;
    public InstrumentId instrumentId;
    public String side;
    public BigDecimal quantity;
    public BigDecimal price;
    public BigDecimal grossAmount;
    public boolean executed;
    public boolean settlementRequested;
    public boolean settled;
    public String settlementId;

    protected Trade() {
    }

    /**
     * Used for rehydration.
     */
    public Trade(TradeId tradeId) {
        super(tradeId);
    }

    public Trade(TradeId tradeId,
                 TradingAccountId accountId,
                 InstrumentId instrumentId,
                 String side,
                 BigDecimal quantity,
                 BigDecimal price) {
        this(tradeId);
        requireNonNull(accountId, "No accountId provided");
        requireNonNull(instrumentId, "No instrumentId provided");
        requireNonNull(side, "No side provided");
        requireNonNull(quantity, "No quantity provided");
        requireNonNull(price, "No price provided");

        apply(new TradeEvent.TradePlaced(tradeId,
                                         accountId,
                                         instrumentId,
                                         side,
                                         quantity,
                                         price,
                                         quantity.multiply(price)));
    }

    public void execute() {
        if (executed) {
            return;
        }
        apply(new TradeEvent.TradeExecuted(aggregateId()));
    }

    public void requestSettlement(String settlementId) {
        requireNonNull(settlementId, "No settlementId provided");
        if (!executed) {
            throw new IllegalStateException("Cannot request settlement before the trade has been executed");
        }
        if (settlementRequested) {
            return;
        }
        apply(new TradeEvent.SettlementRequested(aggregateId(), settlementId));
    }

    public void markSettled() {
        if (!settlementRequested) {
            throw new IllegalStateException("Cannot mark trade as settled before settlement has been requested");
        }
        if (settled) {
            return;
        }
        apply(new TradeEvent.TradeSettled(aggregateId()));
    }

    @EventHandler
    private void on(TradeEvent.TradePlaced event) {
        accountId = event.accountId;
        instrumentId = event.instrumentId;
        side = event.side;
        quantity = event.quantity;
        price = event.price;
        grossAmount = event.grossAmount;
        executed = false;
        settlementRequested = false;
        settled = false;
        settlementId = null;
    }

    @EventHandler
    private void on(TradeEvent.TradeExecuted event) {
        executed = true;
    }

    @EventHandler
    private void on(TradeEvent.SettlementRequested event) {
        settlementRequested = true;
        settlementId = event.settlementId;
    }

    @EventHandler
    private void on(TradeEvent.TradeSettled event) {
        settled = true;
    }
}
