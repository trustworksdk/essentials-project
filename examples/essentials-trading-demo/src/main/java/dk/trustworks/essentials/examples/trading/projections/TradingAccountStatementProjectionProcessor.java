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
import dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.processor.ViewEventProcessor;
import dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.processor.ViewEventProcessorDependencies;
import dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.types.GlobalEventOrder;
import dk.trustworks.essentials.components.foundation.messaging.MessageHandler;
import dk.trustworks.essentials.examples.trading.accounts.TradingAccountEvent;
import dk.trustworks.essentials.examples.trading.config.TradingDemoAggregateConfiguration;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * Durable DB-backed read model for the latest trading-account statement state.
 */
@Service
public class TradingAccountStatementProjectionProcessor extends ViewEventProcessor {
    private final JdbcTemplate jdbcTemplate;

    public TradingAccountStatementProjectionProcessor(ViewEventProcessorDependencies dependencies,
                                                     JdbcTemplate jdbcTemplate) {
        super(dependencies);
        this.jdbcTemplate = jdbcTemplate;
        this.jdbcTemplate.execute("""
                                          CREATE TABLE IF NOT EXISTS projection_trading_account_statement (
                                              logical_account_id VARCHAR PRIMARY KEY,
                                              owner_id VARCHAR NOT NULL,
                                              period_id VARCHAR NOT NULL,
                                              current_generation INTEGER NOT NULL,
                                              generation_count INTEGER NOT NULL,
                                              cash_balance NUMERIC(19, 4) NOT NULL,
                                              reserved_funds NUMERIC(19, 4) NOT NULL,
                                              realized_pnl NUMERIC(19, 4) NOT NULL,
                                              books_closed BOOLEAN NOT NULL,
                                              updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP
                                          )
                                          """);
    }

    @Override
    public String getProcessorName() {
        return "TradingAccountStatementProjection";
    }

    @Override
    protected List<AggregateType> reactsToEventsRelatedToAggregateTypes() {
        return List.of(TradingDemoAggregateConfiguration.TRADING_ACCOUNTS);
    }

    @MessageHandler
    void handle(TradingAccountEvent.TradingAccountOpened event) {
        var generation = generationNumberFrom(event.tradingAccountStreamId.toString());
        jdbcTemplate.update("""
                                    INSERT INTO projection_trading_account_statement (
                                        logical_account_id,
                                        owner_id,
                                        period_id,
                                        current_generation,
                                        generation_count,
                                        cash_balance,
                                        reserved_funds,
                                        realized_pnl,
                                        books_closed,
                                        updated_at
                                    )
                                    VALUES (?, ?, ?, ?, ?, ?, 0, ?, false, CURRENT_TIMESTAMP)
                                    ON CONFLICT (logical_account_id) DO UPDATE
                                    SET owner_id = EXCLUDED.owner_id,
                                        period_id = EXCLUDED.period_id,
                                        current_generation = GREATEST(projection_trading_account_statement.current_generation, EXCLUDED.current_generation),
                                        generation_count = GREATEST(projection_trading_account_statement.generation_count, EXCLUDED.generation_count),
                                        cash_balance = EXCLUDED.cash_balance,
                                        reserved_funds = 0,
                                        realized_pnl = EXCLUDED.realized_pnl,
                                        books_closed = false,
                                        updated_at = CURRENT_TIMESTAMP
                                    """,
                            event.logicalAccountId.toString(),
                            event.ownerId,
                            event.periodId,
                            generation,
                            generation,
                            event.openingCashBalance,
                            event.openingRealizedPnl);
    }

    @MessageHandler
    void handle(TradingAccountEvent.CashDeposited event) {
        jdbcTemplate.update("""
                                    UPDATE projection_trading_account_statement
                                    SET cash_balance = cash_balance + ?,
                                        updated_at = CURRENT_TIMESTAMP
                                    WHERE logical_account_id = ?
                                    """,
                            event.amount,
                            event.logicalAccountId.toString());
    }

    @MessageHandler
    void handle(TradingAccountEvent.FundsReserved event) {
        jdbcTemplate.update("""
                                    UPDATE projection_trading_account_statement
                                    SET reserved_funds = reserved_funds + ?,
                                        updated_at = CURRENT_TIMESTAMP
                                    WHERE logical_account_id = ?
                                    """,
                            event.amount,
                            event.logicalAccountId.toString());
    }

    @MessageHandler
    void handle(TradingAccountEvent.FundsReleased event) {
        jdbcTemplate.update("""
                                    UPDATE projection_trading_account_statement
                                    SET reserved_funds = reserved_funds - ?,
                                        updated_at = CURRENT_TIMESTAMP
                                    WHERE logical_account_id = ?
                                    """,
                            event.amount,
                            event.logicalAccountId.toString());
    }

    @MessageHandler
    void handle(TradingAccountEvent.TradeSettlementApplied event) {
        jdbcTemplate.update("""
                                    UPDATE projection_trading_account_statement
                                    SET cash_balance = cash_balance + ?,
                                        realized_pnl = realized_pnl + ?,
                                        updated_at = CURRENT_TIMESTAMP
                                    WHERE logical_account_id = ?
                                    """,
                            event.cashDelta,
                            event.realizedPnlDelta,
                            event.logicalAccountId.toString());
    }

    @MessageHandler
    void handle(TradingAccountEvent.AccountBooksClosed event) {
        jdbcTemplate.update("""
                                    UPDATE projection_trading_account_statement
                                    SET books_closed = true,
                                        reserved_funds = 0,
                                        updated_at = CURRENT_TIMESTAMP
                                    WHERE logical_account_id = ?
                                    """,
                            event.logicalAccountId.toString());
    }

    @Override
    protected void onSubscriptionsReset(AggregateType aggregateType,
                                        GlobalEventOrder resubscribeFromAndIncluding) {
        jdbcTemplate.execute("TRUNCATE TABLE projection_trading_account_statement");
    }

    private int generationNumberFrom(String streamId) {
        var separator = streamId.lastIndexOf('#');
        if (separator < 0 || separator == streamId.length() - 1) {
            return 1;
        }
        try {
            return Integer.parseInt(streamId.substring(separator + 1));
        } catch (NumberFormatException ignored) {
            return 1;
        }
    }
}
