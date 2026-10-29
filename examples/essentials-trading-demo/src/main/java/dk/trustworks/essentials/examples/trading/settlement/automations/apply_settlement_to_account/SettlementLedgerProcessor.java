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

package dk.trustworks.essentials.examples.trading.settlement.automations.apply_settlement_to_account;

import dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.eventstream.AggregateType;
import dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.processor.*;
import dk.trustworks.essentials.components.foundation.messaging.eip.store_and_forward.MessageConsumptionMode;
import dk.trustworks.essentials.components.foundation.messaging.MessageHandler;
import dk.trustworks.essentials.examples.trading.accounts.use_cases.correct_settlement_amount.CorrectSettlementAmount;
import dk.trustworks.essentials.examples.trading.accounts.use_cases.reverse_trade_booking.ReverseTradeBooking;
import dk.trustworks.essentials.examples.trading.accounts.use_cases.settle_trade.SettleTrade;
import dk.trustworks.essentials.examples.trading.settlement.aggregates.TradeSettlements;
import dk.trustworks.essentials.examples.trading.settlement.events.*;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * Books each settlement outcome on the trading account with the ledger's own command (design §3-§5): settled →
 * {@code SettleTrade}; abandoned → {@code ReverseTradeBooking} with the exchange's reason (Q-1); a corrected amount →
 * {@code CorrectSettlementAmount} with the change (Q-2). Every ledger command is idempotent per execution.
 */
@Service
public class SettlementLedgerProcessor extends EventProcessor {

    public SettlementLedgerProcessor(EventProcessorDependencies dependencies) {
        super(dependencies);
    }

    @Override
    public String getProcessorName() {
        return "SettlementLedgerProcessor";
    }

    /**
     * Every instance handles this processor's inbox, not only the one holding its lock: on the shard-owned engine one
     * aggregate's messages stay in order across instances (one owner per unit, fenced), so only the subscription has to
     * be exclusive. On {@code PostgresqlDurableQueues}, which keeps order on one node only, this would not be safe.
     */
    @Override
    protected MessageConsumptionMode getInboxMessageConsumptionMode() {
        return MessageConsumptionMode.GlobalCompetingConsumers;
    }

    @Override
    protected List<AggregateType> reactsToEventsRelatedToAggregateTypes() {
        return List.of(TradeSettlements.AGGREGATE_TYPE);
    }

    @MessageHandler
    void on(SettlementSettled e) {
        getCommandBus().send(new SettleTrade(e.accountId(), e.executionId()));
    }

    @MessageHandler
    void on(SettlementAbandoned e) {
        getCommandBus().send(new ReverseTradeBooking(e.accountId(), e.executionId(), "SETTLEMENT_" + e.outcome() + ": " + e.reason()));
    }

    @MessageHandler
    void on(SettlementAmountChanged e) {
        getCommandBus().send(new CorrectSettlementAmount(e.accountId(), e.executionId(), e.instrumentId(), e.side(), e.amount().subtract(e.previousAmount())));
    }
}
