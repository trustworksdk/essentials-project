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

package dk.trustworks.essentials.examples.trading.settlement.views.settlements;

import dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.eventstream.AggregateType;
import dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.processor.*;
import dk.trustworks.essentials.components.foundation.messaging.MessageHandler;
import dk.trustworks.essentials.examples.trading.settlement.aggregates.TradeSettlements;
import dk.trustworks.essentials.examples.trading.settlement.events.*;
import dk.trustworks.essentials.examples.trading.settlement.types.SettlementState;
import dk.trustworks.essentials.examples.trading.config.TradingDemoViewEventProcessor;
import org.springframework.stereotype.Service;

import java.sql.Date;
import java.util.List;

/**
 * Every execution's settlement: state, intended date, amount, and for an abandoned one the exchange's outcome and
 * reason - the operator's settlement exceptions (Q-1).
 * <p>
 * Every handler write goes through {@code getRequiredUnitOfWork().handle()…}, the {@code UnitOfWork} a
 * {@link ViewEventProcessor} handles each event in, so the row and the processor's progress commit together. The
 * constructor's DDL runs where none is active, so it goes through {@code getUnitOfWorkFactory().usingUnitOfWork(uow -> …)},
 * which opens one.
 */
@Service
public class SettlementsProjection extends TradingDemoViewEventProcessor {
    public SettlementsProjection(ViewEventProcessorDependencies dependencies) {
        super(dependencies);
        getUnitOfWorkFactory().usingUnitOfWork(uow -> uow.handle().execute("""
                                                                                   CREATE TABLE IF NOT EXISTS projection_settlement (
                                                                                       execution_id VARCHAR PRIMARY KEY,
                                                                                       account_id VARCHAR NOT NULL,
                                                                                       instrument_id VARCHAR NOT NULL,
                                                                                       side VARCHAR NOT NULL,
                                                                                       amount NUMERIC(24, 6) NOT NULL,
                                                                                       currency VARCHAR NOT NULL,
                                                                                       intended_settlement_date DATE NOT NULL,
                                                                                       state VARCHAR NOT NULL,
                                                                                       outcome VARCHAR NULL,
                                                                                       reason VARCHAR NULL,
                                                                                       updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP
                                                                                   )
                                                                                   """));
        getUnitOfWorkFactory().usingUnitOfWork(uow -> uow.handle().execute("CREATE INDEX IF NOT EXISTS projection_settlement_account ON projection_settlement (account_id)"));
    }

    @Override
    public String getProcessorName() {
        return "SettlementsProjection";
    }

    @Override
    protected List<AggregateType> reactsToEventsRelatedToAggregateTypes() {
        return List.of(TradeSettlements.AGGREGATE_TYPE);
    }

    @MessageHandler
    void handle(SettlementInstructed e) {
        getRequiredUnitOfWork().handle().createUpdate("""
                                                          INSERT INTO projection_settlement (execution_id, account_id, instrument_id, side, amount, currency, intended_settlement_date, state)
                                                          VALUES (:executionId, :accountId, :instrumentId, :side, :amount, :currency, :intendedSettlementDate, :state) ON CONFLICT (execution_id) DO NOTHING
                                                          """)
                                        .bind("executionId", e.executionId().toString())
                                        .bind("accountId", e.accountId().toString())
                                        .bind("instrumentId", e.instrumentId().toString())
                                        .bind("side", e.side().name())
                                        .bind("amount", e.amount().getAmount().value())
                                        .bind("currency", e.amount().getCurrency().toString())
                                        .bind("intendedSettlementDate", Date.valueOf(e.intendedSettlementDate()))
                                        .bind("state", SettlementState.INSTRUCTED.name())
                                        .execute();
    }

    @MessageHandler
    void handle(SettlementPostponed e) {
        getRequiredUnitOfWork().handle()
                                .createUpdate("UPDATE projection_settlement SET intended_settlement_date = :intendedSettlementDate, updated_at = CURRENT_TIMESTAMP WHERE execution_id = :executionId AND state = :state")
                                .bind("intendedSettlementDate", Date.valueOf(e.intendedSettlementDate()))
                                .bind("executionId", e.executionId().toString())
                                .bind("state", SettlementState.INSTRUCTED.name())
                                .execute();
    }

    @MessageHandler
    void handle(SettlementSettled e) {
        getRequiredUnitOfWork().handle()
                                .createUpdate("UPDATE projection_settlement SET state = :state, updated_at = CURRENT_TIMESTAMP WHERE execution_id = :executionId")
                                .bind("state", SettlementState.SETTLED.name())
                                .bind("executionId", e.executionId().toString())
                                .execute();
    }

    @MessageHandler
    void handle(SettlementAbandoned e) {
        getRequiredUnitOfWork().handle()
                                .createUpdate("UPDATE projection_settlement SET state = :state, outcome = :outcome, reason = :reason, updated_at = CURRENT_TIMESTAMP WHERE execution_id = :executionId")
                                .bind("state", SettlementState.ABANDONED.name())
                                .bind("outcome", e.outcome())
                                .bind("reason", e.reason())
                                .bind("executionId", e.executionId().toString())
                                .execute();
    }

    @MessageHandler
    void handle(SettlementAmountChanged e) {
        getRequiredUnitOfWork().handle()
                                .createUpdate("UPDATE projection_settlement SET amount = :amount, updated_at = CURRENT_TIMESTAMP WHERE execution_id = :executionId")
                                .bind("amount", e.amount().getAmount().value())
                                .bind("executionId", e.executionId().toString())
                                .execute();
    }
}
