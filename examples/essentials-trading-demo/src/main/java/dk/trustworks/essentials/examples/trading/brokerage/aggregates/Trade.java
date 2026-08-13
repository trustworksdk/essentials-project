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
import dk.trustworks.essentials.examples.trading.brokerage.events.SettlementRequested;
import dk.trustworks.essentials.examples.trading.brokerage.events.TradeEvent;
import dk.trustworks.essentials.examples.trading.brokerage.events.TradeExecuted;
import dk.trustworks.essentials.examples.trading.brokerage.events.TradePlaced;
import dk.trustworks.essentials.examples.trading.brokerage.events.TradeSettled;
import dk.trustworks.essentials.examples.trading.brokerage.types.Quantity;
import dk.trustworks.essentials.examples.trading.brokerage.types.SettlementId;
import dk.trustworks.essentials.examples.trading.brokerage.types.TradeId;
import dk.trustworks.essentials.examples.trading.brokerage.types.TradeSide;
import dk.trustworks.essentials.examples.trading.brokerage.types.TradingAccountId;
import dk.trustworks.essentials.examples.trading.market_data.types.InstrumentId;
import dk.trustworks.essentials.types.Amount;

import static dk.trustworks.essentials.shared.FailFast.requireNonNull;

/**
 * A single order, and the consistency boundary for its progress from placed to settled.
 *
 * <p>An event-sourced {@link AggregateRoot}: its methods do not assign state, they {@code apply} a
 * {@link TradeEvent}, and the {@code @EventHandler} methods at the bottom are the only place its state is ever
 * written.
 *
 * <p>The invariant it enforces is the order of the lifecycle -- settlement cannot be requested before execution, and
 * a trade cannot be marked settled before a settlement was requested. Both guards throw <em>before</em> any event is
 * applied, so a rejected command leaves no trace in the stream.
 *
 * <p>Each step is separately idempotent: executing an executed trade, re-requesting a settlement, or re-marking a
 * settled trade are all no-ops rather than failures. That is what makes the trade safe to drive from a retried
 * message. Note the asymmetry in {@link #markSettled()} -- it is a no-op if already settled, but still throws if
 * settlement was never requested. Idempotence forgives a repeat, not a skipped step.
 *
 * <p>{@code settlementId} is the trade's only link to the {@code Settlement} aggregate; the two are separate
 * boundaries and nothing writes both in one transaction.
 *
 * <p>Reached through {@link Trades}. Commands are unpacked by the slice that handles them, so this class never names
 * a command type.
 */
public class Trade extends AggregateRoot<TradeId, TradeEvent, Trade> {
    private TradingAccountId accountId;
    private InstrumentId     instrumentId;
    private TradeSide        side;
    private Quantity         quantity;
    private Amount           price;
    private Amount           grossAmount;
    private boolean          executed;
    private boolean          settlementRequested;
    private boolean          settled;
    private SettlementId     settlementId;

    /**
     * Only for a JSON deserializer restoring this aggregate. Not the rehydration constructor and not a creating one --
     * it exists so Jackson populates the fields directly instead of picking one of the two public constructors as an
     * implicit creator, which under Jackson 3 it otherwise would.
     */
    protected Trade() {
    }

    /**
     * Used for rehydration
     */
    public Trade(TradeId tradeId) {
        super(tradeId);
    }

    public Trade(TradeId tradeId,
                 TradingAccountId accountId,
                 InstrumentId instrumentId,
                 TradeSide side,
                 Quantity quantity,
                 Amount price) {
        this(tradeId);
        requireNonNull(accountId, "No accountId provided");
        requireNonNull(instrumentId, "No instrumentId provided");
        requireNonNull(side, "No side provided");
        requireNonNull(quantity, "No quantity provided");
        requireNonNull(price, "No price provided");

        apply(new TradePlaced(tradeId,
                              accountId,
                              instrumentId,
                              side,
                              quantity,
                              price,
                              Amount.of(quantity.value().multiply(price.value()))));
    }

    public void execute() {
        if (executed) {
            return;
        }
        apply(new TradeExecuted(aggregateId()));
    }

    public void requestSettlement(SettlementId settlementId) {
        requireNonNull(settlementId, "No settlementId provided");
        if (!executed) {
            throw new IllegalStateException("Cannot request settlement before the trade has been executed");
        }
        if (settlementRequested) {
            return;
        }
        apply(new SettlementRequested(aggregateId(), settlementId));
    }

    public void markSettled() {
        if (!settlementRequested) {
            throw new IllegalStateException("Cannot mark trade as settled before settlement has been requested");
        }
        if (settled) {
            return;
        }
        apply(new TradeSettled(aggregateId()));
    }

    @EventHandler
    private void on(TradePlaced event) {
        accountId = event.accountId();
        instrumentId = event.instrumentId();
        side = event.side();
        quantity = event.quantity();
        price = event.price();
        grossAmount = event.grossAmount();
        executed = false;
        settlementRequested = false;
        settled = false;
        settlementId = null;
    }

    @EventHandler
    private void on(TradeExecuted event) {
        executed = true;
    }

    @EventHandler
    private void on(SettlementRequested event) {
        settlementRequested = true;
        settlementId = event.settlementId();
    }

    @EventHandler
    private void on(TradeSettled event) {
        settled = true;
    }
}
