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

package dk.trustworks.essentials.examples.trading.brokerage.views.account_statement;

import dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.eventstream.AggregateType;
import dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.processor.ViewEventProcessor;
import dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.processor.ViewEventProcessorDependencies;
import dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.types.GlobalEventOrder;
import dk.trustworks.essentials.components.foundation.messaging.MessageHandler;
import dk.trustworks.essentials.examples.trading.brokerage.aggregates.TradingAccounts;
import dk.trustworks.essentials.examples.trading.brokerage.events.AccountBooksClosed;
import dk.trustworks.essentials.examples.trading.brokerage.events.CashDeposited;
import dk.trustworks.essentials.examples.trading.brokerage.events.FundsReleased;
import dk.trustworks.essentials.examples.trading.brokerage.events.FundsReserved;
import dk.trustworks.essentials.examples.trading.brokerage.events.TradeSettlementApplied;
import dk.trustworks.essentials.examples.trading.brokerage.events.TradingAccountOpened;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.util.List;

import static dk.trustworks.essentials.shared.FailFast.requireNonNull;

/**
 * The projector of the {@code brokerage.account_statement} view slice — events in, read model out. A view slice never
 * produces events (rules/slice-design.md § The four slice kinds).
 * <p>
 * {@link ViewEventProcessor} is the right processor here: asynchronous, replayable, eventually consistent. A statement
 * balance is precisely the kind of figure that does not have to be current the instant a command API returns.
 * <p>
 * <b>One row per <em>logical</em> account, not per stream.</b> A trading account closes its books by sealing its
 * stream and opening the next one, so its events arrive under a succession of
 * {@code TradingAccountGenerationId}s while the statement stays one row keyed on
 * {@code TradingAccountEvent.logicalAccountId()}. {@code current_generation} is the only place the stream id is looked
 * at, and it is read through
 * {@link dk.trustworks.essentials.examples.trading.brokerage.types.TradingAccountGenerationId#generation()} — the
 * {@code #} convention lives on the id type and is not re-parsed here. This projection used to carry its own
 * {@code lastIndexOf('#')} copy of that parse, which could drift from the concatenation that produced the id.
 * <p>
 * Idempotency is carried by the SQL rather than by an {@code OrderedMessage} version check: the opening event upserts
 * (so a replay restates the row instead of failing), and every other handler is a delta. A replay therefore has to go
 * through {@link #onSubscriptionsReset}, which truncates — re-applying a delta on top of a live row would double it.
 */
@Service
public class AccountStatementProjection extends ViewEventProcessor {
    private final JdbcTemplate jdbcTemplate;

    public AccountStatementProjection(ViewEventProcessorDependencies dependencies,
                                      JdbcTemplate jdbcTemplate) {
        super(dependencies);
        this.jdbcTemplate = requireNonNull(jdbcTemplate, "No jdbcTemplate provided");
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

    /**
     * Unchanged from the pre-slice processor on purpose: the processor name is the key its subscription progress is
     * stored under, so renaming it would silently restart the projection from the beginning of time.
     */
    @Override
    public String getProcessorName() {
        return "TradingAccountStatementProjection";
    }

    @Override
    protected List<AggregateType> reactsToEventsRelatedToAggregateTypes() {
        return List.of(TradingAccounts.AGGREGATE_TYPE);
    }

    @MessageHandler
    void handle(TradingAccountOpened event) {
        var generation = event.tradingAccountStreamId().generation();
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
                            event.logicalAccountId().toString(),
                            event.ownerId().toString(),
                            event.periodId().toString(),
                            generation,
                            generation,
                            event.openingCashBalance().value(),
                            event.openingRealizedPnl().value());
    }

    @MessageHandler
    void handle(CashDeposited event) {
        jdbcTemplate.update("""
                                    UPDATE projection_trading_account_statement
                                    SET cash_balance = cash_balance + ?,
                                        updated_at = CURRENT_TIMESTAMP
                                    WHERE logical_account_id = ?
                                    """,
                            event.amount().value(),
                            event.logicalAccountId().toString());
    }

    @MessageHandler
    void handle(FundsReserved event) {
        jdbcTemplate.update("""
                                    UPDATE projection_trading_account_statement
                                    SET reserved_funds = reserved_funds + ?,
                                        updated_at = CURRENT_TIMESTAMP
                                    WHERE logical_account_id = ?
                                    """,
                            event.amount().value(),
                            event.logicalAccountId().toString());
    }

    @MessageHandler
    void handle(FundsReleased event) {
        jdbcTemplate.update("""
                                    UPDATE projection_trading_account_statement
                                    SET reserved_funds = reserved_funds - ?,
                                        updated_at = CURRENT_TIMESTAMP
                                    WHERE logical_account_id = ?
                                    """,
                            event.amount().value(),
                            event.logicalAccountId().toString());
    }

    @MessageHandler
    void handle(TradeSettlementApplied event) {
        jdbcTemplate.update("""
                                    UPDATE projection_trading_account_statement
                                    SET cash_balance = cash_balance + ?,
                                        realized_pnl = realized_pnl + ?,
                                        updated_at = CURRENT_TIMESTAMP
                                    WHERE logical_account_id = ?
                                    """,
                            event.cashDelta().value(),
                            event.realizedPnlDelta().value(),
                            event.logicalAccountId().toString());
    }

    @MessageHandler
    void handle(AccountBooksClosed event) {
        jdbcTemplate.update("""
                                    UPDATE projection_trading_account_statement
                                    SET books_closed = true,
                                        reserved_funds = 0,
                                        updated_at = CURRENT_TIMESTAMP
                                    WHERE logical_account_id = ?
                                    """,
                            event.logicalAccountId().toString());
    }

    /**
     * Rebuild support: wipe the read model so a subscription reset replays cleanly. Called once per
     * {@link AggregateType} this processor subscribes to — there is only one here, so a blanket truncate is correct.
     */
    @Override
    protected void onSubscriptionsReset(AggregateType aggregateType,
                                        GlobalEventOrder resubscribeFromAndIncluding) {
        jdbcTemplate.execute("TRUNCATE TABLE projection_trading_account_statement");
    }
}
