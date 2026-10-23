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
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.sql.Date;
import java.util.List;

import static dk.trustworks.essentials.shared.FailFast.requireNonNull;

/**
 * Every execution's settlement: state, intended date, amount, and for an abandoned one the exchange's outcome and
 * reason - the operator's settlement exceptions (Q-1).
 */
@Service
public class SettlementsProjection extends TradingDemoViewEventProcessor {
    private final JdbcTemplate jdbcTemplate;

    public SettlementsProjection(ViewEventProcessorDependencies dependencies, JdbcTemplate jdbcTemplate) {
        super(dependencies);
        this.jdbcTemplate = requireNonNull(jdbcTemplate, "No jdbcTemplate provided");
        this.jdbcTemplate.execute("""
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
                                          """);
        this.jdbcTemplate.execute("CREATE INDEX IF NOT EXISTS projection_settlement_account ON projection_settlement (account_id)");
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
        jdbcTemplate.update("""
                                    INSERT INTO projection_settlement (execution_id, account_id, instrument_id, side, amount, currency, intended_settlement_date, state)
                                    VALUES (?, ?, ?, ?, ?, ?, ?, ?) ON CONFLICT (execution_id) DO NOTHING
                                    """,
                            e.executionId().toString(), e.accountId().toString(), e.instrumentId().toString(), e.side().name(),
                            e.amount().getAmount().value(), e.amount().getCurrency().toString(), Date.valueOf(e.intendedSettlementDate()),
                            SettlementState.INSTRUCTED.name());
    }

    @MessageHandler
    void handle(SettlementPostponed e) {
        jdbcTemplate.update("UPDATE projection_settlement SET intended_settlement_date = ?, updated_at = CURRENT_TIMESTAMP WHERE execution_id = ? AND state = ?",
                            Date.valueOf(e.intendedSettlementDate()), e.executionId().toString(), SettlementState.INSTRUCTED.name());
    }

    @MessageHandler
    void handle(SettlementSettled e) {
        jdbcTemplate.update("UPDATE projection_settlement SET state = ?, updated_at = CURRENT_TIMESTAMP WHERE execution_id = ?", SettlementState.SETTLED.name(), e.executionId().toString());
    }

    @MessageHandler
    void handle(SettlementAbandoned e) {
        jdbcTemplate.update("UPDATE projection_settlement SET state = ?, outcome = ?, reason = ?, updated_at = CURRENT_TIMESTAMP WHERE execution_id = ?",
                            SettlementState.ABANDONED.name(), e.outcome(), e.reason(), e.executionId().toString());
    }

    @MessageHandler
    void handle(SettlementAmountChanged e) {
        jdbcTemplate.update("UPDATE projection_settlement SET amount = ?, updated_at = CURRENT_TIMESTAMP WHERE execution_id = ?", e.amount().getAmount().value(), e.executionId().toString());
    }
}
