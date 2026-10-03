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

package dk.trustworks.essentials.examples.trading.brokerage.use_cases.settle_trade;

import dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.eventstream.AggregateType;
import dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.processor.*;
import dk.trustworks.essentials.components.foundation.messaging.*;
import dk.trustworks.essentials.examples.trading.brokerage.aggregates.*;
import dk.trustworks.essentials.examples.trading.brokerage.events.*;
import dk.trustworks.essentials.examples.trading.brokerage.types.SettlementId;
import dk.trustworks.essentials.examples.trading.brokerage.use_cases.apply_trade_settlement.ApplyTradeSettlement;
import dk.trustworks.essentials.examples.trading.brokerage.use_cases.close_settlement.CloseSettlement;
import dk.trustworks.essentials.examples.trading.brokerage.use_cases.confirm_clearing.ConfirmClearing;
import dk.trustworks.essentials.examples.trading.brokerage.use_cases.create_settlement.CreateSettlement;
import dk.trustworks.essentials.examples.trading.brokerage.use_cases.mark_settlement_settled.MarkSettlementSettled;
import dk.trustworks.essentials.examples.trading.brokerage.use_cases.mark_trade_settled.MarkTradeSettled;
import dk.trustworks.essentials.examples.trading.brokerage.use_cases.reconcile_settlement.ReconcileSettlement;
import dk.trustworks.essentials.examples.trading.brokerage.use_cases.request_clearing.RequestClearing;
import dk.trustworks.essentials.examples.trading.brokerage.use_cases.request_settlement.RequestSettlement;
import dk.trustworks.essentials.types.Amount;
import org.slf4j.*;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

import java.util.List;

import static dk.trustworks.essentials.shared.FailFast.requireNonNull;

/**
 * The {@code brokerage.settle_trade} automation slice: once a trade has executed, drive it through settlement to the
 * end by reacting to each step's event with the next step's command.
 *
 * <pre>
 * TradeExecuted           -> RequestSettlement
 * SettlementRequested     -> CreateSettlement
 * SettlementCreated       -> RequestClearing
 * ClearingRequested       -> (clearing house, outside any transaction) ConfirmClearing
 * ClearingConfirmed       -> MarkSettlementSettled
 * SettlementMarkedSettled -> ReconcileSettlement, MarkTradeSettled, ApplyTradeSettlement
 * SettlementReconciled    -> CloseSettlement
 * </pre>
 *
 * Each step is a command handled by its own slice, sent through the command bus from the handler of the event that
 * calls for it - so every event of a trade's settlement records the event that caused it, and one {@code TradeExecuted}
 * roots a causation tree across the {@code Trade}, {@code Settlement} and {@code TradingAccount} aggregates. That tree is
 * what the admin console's <i>Event causation</i> page shows.
 *
 * <p>Every step is safe to repeat, because a redelivered event repeats its command: the aggregates ignore a step that
 * already happened, and {@code CreateSettlement} ignores an existing settlement.
 *
 * <p>Active unless {@code trading-demo.simulation.trade-lifecycle=scripted}, which is how the benchmark scenarios keep driving
 * every step themselves. It starts from the latest event, so turning it on against an existing database does not
 * replay the lifecycle of trades settled long ago.
 */
@Service
@ConditionalOnProperty(prefix = "trading-demo.simulation", name = "trade-lifecycle", havingValue = "automated", matchIfMissing = true)
public class SettleTradeProcessor extends EventProcessor {
    private static final Logger log = LoggerFactory.getLogger(SettleTradeProcessor.class);

    private final ClearingHouseGateway clearingHouseGateway;

    public SettleTradeProcessor(EventProcessorDependencies eventProcessorDependencies,
                                ClearingHouseGateway clearingHouseGateway) {
        super(eventProcessorDependencies);
        this.clearingHouseGateway = requireNonNull(clearingHouseGateway, "No clearingHouseGateway provided");
    }

    @Override
    public String getProcessorName() {
        return "SettleTradeProcessor";
    }

    @Override
    protected List<AggregateType> reactsToEventsRelatedToAggregateTypes() {
        return List.of(Trades.AGGREGATE_TYPE, Settlements.AGGREGATE_TYPE);
    }

    @Override
    protected boolean isStartSubscriptionFromLatestEvent() {
        return true;
    }

    @MessageHandler
    void on(TradeExecuted e) {
        getCommandBus().send(new RequestSettlement(e.tradeId(), SettlementId.forTrade(e.tradeId())));
    }

    @MessageHandler
    void on(SettlementRequested e) {
        if (e.accountId() == null || e.grossAmount() == null) {
            log.warn("===> SettlementRequested for Trade '{}' was persisted before it carried the account and amount - not creating its settlement", e.tradeId());
            return;
        }
        getCommandBus().send(new CreateSettlement(e.settlementId(), e.tradeId(), e.accountId(), e.grossAmount()));
    }

    @MessageHandler
    void on(SettlementCreated e) {
        getCommandBus().send(new RequestClearing(e.settlementId()));
    }

    /**
     * The clearing house is an external system, so it is called with no UnitOfWork and therefore no database connection
     * held; the command that records its answer runs in a UnitOfWork of its own. The same shape as
     * {@code market_data.risk_approve_instrument}.
     */
    @MessageHandler(unitOfWork = UnitOfWorkMode.NONE)
    void on(ClearingRequested e) {
        clearingHouseGateway.confirmClearing(e.settlementId());
        usingUnitOfWork(() -> getCommandBus().send(new ConfirmClearing(e.settlementId())));
    }

    @MessageHandler
    void on(ClearingConfirmed e) {
        getCommandBus().send(new MarkSettlementSettled(e.settlementId()));
    }

    /**
     * Three follow-up steps on three aggregates. The cash moves by the gross amount; the automated lifecycle records no
     * realized P&L - only the scripted harness simulates one.
     */
    @MessageHandler
    void on(SettlementMarkedSettled e) {
        getCommandBus().send(new ReconcileSettlement(e.settlementId()));
        if (e.tradeId() == null || e.accountId() == null || e.grossAmount() == null) {
            log.warn("===> SettlementMarkedSettled for Settlement '{}' was persisted before it carried the trade, account and amount - not settling them", e.settlementId());
            return;
        }
        getCommandBus().send(new MarkTradeSettled(e.tradeId()));
        getCommandBus().send(new ApplyTradeSettlement(e.accountId(), e.tradeId(), e.grossAmount().negate(), Amount.ZERO));
    }

    @MessageHandler
    void on(SettlementReconciled e) {
        getCommandBus().send(new CloseSettlement(e.settlementId()));
    }

    @MessageHandler
    void on(TradePlaced e) {
        // Nothing to do until the trade executes
    }

    @MessageHandler
    void on(TradeSettled e) {
        // The end of the trade's side
    }

    @MessageHandler
    void on(SettlementClosed e) {
        // The end of the settlement's side
    }
}
