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
import dk.trustworks.essentials.reactive.command.CommandBus;
import dk.trustworks.essentials.examples.trading.accounts.use_cases.correct_settlement_amount.CorrectSettlementAmount;
import dk.trustworks.essentials.examples.trading.accounts.use_cases.reverse_trade_booking.ReverseTradeBooking;
import dk.trustworks.essentials.examples.trading.accounts.use_cases.settle_trade.SettleTrade;
import dk.trustworks.essentials.examples.trading.settlement.aggregates.TradeSettlements;
import dk.trustworks.essentials.examples.trading.settlement.events.*;
import org.springframework.stereotype.Service;

import java.util.List;

import static dk.trustworks.essentials.shared.FailFast.requireNonNull;

/**
 * Books each settlement outcome on the trading account with the ledger's own command (design §3-§5): settled →
 * {@code SettleTrade}; abandoned → {@code ReverseTradeBooking} with the exchange's reason (Q-1); a corrected amount →
 * {@code CorrectSettlementAmount} with the change (Q-2). Every ledger command is idempotent per execution.
 * <p>
 * Engine-neutral: the handler bodies, fed by {@link SettlementLedgerClassicReaction} on the classic engine and
 * {@link SettlementLedgerEventLogReaction} on the event-log engine under the processor name {@value #PROCESSOR_NAME}. The
 * wrappers declare each handler's {@code UnitOfWorkMode}, so a body runs in the unit of work its wrapper opens.
 */
@Service
public class SettlementLedgerProcessor {
    static final String              PROCESSOR_NAME  = "SettlementLedgerProcessor";
    static final List<AggregateType> AGGREGATE_TYPES = List.of(TradeSettlements.AGGREGATE_TYPE);

    private final CommandBus commandBus;

    public SettlementLedgerProcessor(CommandBus commandBus) {
        this.commandBus = requireNonNull(commandBus, "No commandBus provided");
    }

    void on(SettlementSettled e) {
        commandBus.send(new SettleTrade(e.accountId(), e.executionId()));
    }

    void on(SettlementAbandoned e) {
        commandBus.send(new ReverseTradeBooking(e.accountId(), e.executionId(), "SETTLEMENT_" + e.outcome() + ": " + e.reason()));
    }

    void on(SettlementAmountChanged e) {
        commandBus.send(new CorrectSettlementAmount(e.accountId(), e.executionId(), e.instrumentId(), e.side(), e.amount().subtract(e.previousAmount())));
    }
}
