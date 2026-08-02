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

package dk.trustworks.essentials.examples.trading.projections;

import dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.eventstream.AggregateType;
import dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.processor.EventProcessor;
import dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.processor.EventProcessorDependencies;
import dk.trustworks.essentials.components.foundation.messaging.MessageHandler;
import dk.trustworks.essentials.examples.trading.config.TradingDemoAggregateConfiguration;
import dk.trustworks.essentials.examples.trading.settlements.SettlementEvent;
import dk.trustworks.essentials.examples.trading.trades.TradeEvent;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * Multi-stream async projection that joins trade and settlement lifecycle state.
 */
@Service
public class TradeSettlementProjectionProcessor extends EventProcessor {
    private final JdbcTemplate jdbcTemplate;

    public TradeSettlementProjectionProcessor(EventProcessorDependencies dependencies,
                                             JdbcTemplate jdbcTemplate) {
        super(dependencies);
        this.jdbcTemplate = jdbcTemplate;
        this.jdbcTemplate.execute("""
                                          CREATE TABLE IF NOT EXISTS projection_trade_settlement (
                                              trade_id VARCHAR PRIMARY KEY,
                                              account_id VARCHAR NULL,
                                              instrument_id VARCHAR NULL,
                                              side VARCHAR NULL,
                                              quantity NUMERIC(19, 4) NULL,
                                              price NUMERIC(19, 4) NULL,
                                              gross_amount NUMERIC(19, 4) NULL,
                                              executed BOOLEAN NOT NULL DEFAULT FALSE,
                                              settlement_requested BOOLEAN NOT NULL DEFAULT FALSE,
                                              settled BOOLEAN NOT NULL DEFAULT FALSE,
                                              settlement_id VARCHAR NULL,
                                              settlement_status VARCHAR NOT NULL DEFAULT 'NONE',
                                              updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP
                                          )
                                          """);
        this.jdbcTemplate.execute("""
                                          CREATE UNIQUE INDEX IF NOT EXISTS projection_trade_settlement_settlement_id_uq
                                          ON projection_trade_settlement (settlement_id)
                                          WHERE settlement_id IS NOT NULL
                                          """);
    }

    @Override
    public String getProcessorName() {
        return "TradeSettlementProjection";
    }

    @Override
    protected List<AggregateType> reactsToEventsRelatedToAggregateTypes() {
        return List.of(TradingDemoAggregateConfiguration.TRADES,
                       TradingDemoAggregateConfiguration.SETTLEMENTS);
    }

    @MessageHandler
    void handle(TradeEvent.TradePlaced event) {
        jdbcTemplate.update("""
                                    INSERT INTO projection_trade_settlement (
                                        trade_id,
                                        account_id,
                                        instrument_id,
                                        side,
                                        quantity,
                                        price,
                                        gross_amount,
                                        executed,
                                        settlement_requested,
                                        settled,
                                        settlement_status,
                                        updated_at
                                    )
                                    VALUES (?, ?, ?, ?, ?, ?, ?, false, false, false, 'NONE', CURRENT_TIMESTAMP)
                                    ON CONFLICT (trade_id) DO UPDATE
                                    SET account_id = EXCLUDED.account_id,
                                        instrument_id = EXCLUDED.instrument_id,
                                        side = EXCLUDED.side,
                                        quantity = EXCLUDED.quantity,
                                        price = EXCLUDED.price,
                                        gross_amount = EXCLUDED.gross_amount,
                                        updated_at = CURRENT_TIMESTAMP
                                    """,
                            event.tradeId.toString(),
                            event.accountId.toString(),
                            event.instrumentId.toString(),
                            event.side,
                            event.quantity,
                            event.price,
                            event.grossAmount);
    }

    @MessageHandler
    void handle(TradeEvent.TradeExecuted event) {
        jdbcTemplate.update("""
                                    UPDATE projection_trade_settlement
                                    SET executed = true,
                                        updated_at = CURRENT_TIMESTAMP
                                    WHERE trade_id = ?
                                    """,
                            event.tradeId.toString());
    }

    @MessageHandler
    void handle(TradeEvent.SettlementRequested event) {
        jdbcTemplate.update("""
                                    UPDATE projection_trade_settlement
                                    SET settlement_requested = true,
                                        settlement_id = ?,
                                        settlement_status = CASE
                                            WHEN settlement_status = 'NONE' THEN 'REQUESTED'
                                            ELSE settlement_status
                                        END,
                                        updated_at = CURRENT_TIMESTAMP
                                    WHERE trade_id = ?
                                    """,
                            event.settlementId,
                            event.tradeId.toString());
    }

    @MessageHandler
    void handle(TradeEvent.TradeSettled event) {
        jdbcTemplate.update("""
                                    UPDATE projection_trade_settlement
                                    SET settled = true,
                                        updated_at = CURRENT_TIMESTAMP
                                    WHERE trade_id = ?
                                    """,
                            event.tradeId.toString());
    }

    @MessageHandler
    void handle(SettlementEvent.SettlementCreated event) {
        jdbcTemplate.update("""
                                    INSERT INTO projection_trade_settlement (
                                        trade_id,
                                        account_id,
                                        gross_amount,
                                        settlement_requested,
                                        settlement_id,
                                        settlement_status,
                                        updated_at
                                    )
                                    VALUES (?, ?, ?, true, ?, 'CREATED', CURRENT_TIMESTAMP)
                                    ON CONFLICT (trade_id) DO UPDATE
                                    SET account_id = EXCLUDED.account_id,
                                        gross_amount = EXCLUDED.gross_amount,
                                        settlement_requested = true,
                                        settlement_id = EXCLUDED.settlement_id,
                                        settlement_status = 'CREATED',
                                        updated_at = CURRENT_TIMESTAMP
                                    """,
                            event.tradeId,
                            event.accountId,
                            event.grossAmount,
                            event.settlementId.toString());
    }

    @MessageHandler
    void handle(SettlementEvent.ClearingRequested event) {
        updateSettlementStatus(event.settlementId.toString(), "CLEARING_REQUESTED");
    }

    @MessageHandler
    void handle(SettlementEvent.ClearingConfirmed event) {
        updateSettlementStatus(event.settlementId.toString(), "CLEARING_CONFIRMED");
    }

    @MessageHandler
    void handle(SettlementEvent.SettlementMarkedSettled event) {
        updateSettlementStatus(event.settlementId.toString(), "SETTLED");
    }

    @MessageHandler
    void handle(SettlementEvent.SettlementReconciled event) {
        updateSettlementStatus(event.settlementId.toString(), "RECONCILED");
    }

    @MessageHandler
    void handle(SettlementEvent.SettlementClosed event) {
        updateSettlementStatus(event.settlementId.toString(), "CLOSED");
    }

    @Override
    protected void onSubscriptionsReset(AggregateType aggregateType,
                                        dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.types.GlobalEventOrder resubscribeFromAndIncluding) {
        jdbcTemplate.execute("TRUNCATE TABLE projection_trade_settlement");
    }

    private void updateSettlementStatus(String settlementId, String status) {
        jdbcTemplate.update("""
                                    UPDATE projection_trade_settlement
                                    SET settlement_status = ?,
                                        updated_at = CURRENT_TIMESTAMP
                                    WHERE settlement_id = ?
                                    """,
                            status,
                            settlementId);
    }
}
