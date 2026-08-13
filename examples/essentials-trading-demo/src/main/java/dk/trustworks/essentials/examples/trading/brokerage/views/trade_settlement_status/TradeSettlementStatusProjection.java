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

package dk.trustworks.essentials.examples.trading.brokerage.views.trade_settlement_status;

import dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.eventstream.AggregateType;
import dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.processor.EventProcessor;
import dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.processor.EventProcessorDependencies;
import dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.types.GlobalEventOrder;
import dk.trustworks.essentials.components.foundation.messaging.MessageHandler;
import dk.trustworks.essentials.examples.trading.brokerage.aggregates.Settlements;
import dk.trustworks.essentials.examples.trading.brokerage.aggregates.Trades;
import dk.trustworks.essentials.examples.trading.brokerage.events.ClearingConfirmed;
import dk.trustworks.essentials.examples.trading.brokerage.events.ClearingRequested;
import dk.trustworks.essentials.examples.trading.brokerage.events.SettlementClosed;
import dk.trustworks.essentials.examples.trading.brokerage.events.SettlementCreated;
import dk.trustworks.essentials.examples.trading.brokerage.events.SettlementMarkedSettled;
import dk.trustworks.essentials.examples.trading.brokerage.events.SettlementReconciled;
import dk.trustworks.essentials.examples.trading.brokerage.events.SettlementRequested;
import dk.trustworks.essentials.examples.trading.brokerage.events.TradeExecuted;
import dk.trustworks.essentials.examples.trading.brokerage.events.TradePlaced;
import dk.trustworks.essentials.examples.trading.brokerage.events.TradeSettled;
import dk.trustworks.essentials.examples.trading.brokerage.types.SettlementStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.util.List;

import static dk.trustworks.essentials.shared.FailFast.requireNonNull;

/**
 * The projector of the {@code brokerage.trade_settlement_status} view slice — events in, read model out. A view slice
 * never produces events (rules/slice-design.md § The four slice kinds).
 * <p>
 * <b>This is the demo's multi-stream join.</b> A trade and its settlement are two separate consistency boundaries with
 * two separate event streams, and no transaction writes both. Their combined state exists nowhere on the write side;
 * this projection is where it comes into being, which is why it subscribes to two {@link AggregateType}s.
 * <p>
 * The two sides meet on the row keyed by {@code trade_id}, and either one may arrive first — so both entry points
 * ({@link #handle(TradePlaced)} and {@link #handle(SettlementCreated)}) upsert, and the settlement side only sets the
 * columns it actually knows. That is also why the settlement id carries a partial unique index rather than being a
 * second primary key: it is unique when present and absent for a trade with no settlement yet.
 * <p>
 * Idempotency is carried by the SQL: every statement is an upsert or an idempotent flag/status assignment, so a
 * redelivery restates the row rather than compounding. Nothing here is a delta, unlike the account-statement
 * projection.
 */
@Service
public class TradeSettlementStatusProjection extends EventProcessor {
    private final JdbcTemplate jdbcTemplate;

    public TradeSettlementStatusProjection(EventProcessorDependencies dependencies,
                                           JdbcTemplate jdbcTemplate) {
        super(dependencies);
        this.jdbcTemplate = requireNonNull(jdbcTemplate, "No jdbcTemplate provided");
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

    /**
     * Unchanged from the pre-slice processor on purpose: the processor name is the key its subscription progress is
     * stored under, so renaming it would silently restart the projection from the beginning of time.
     */
    @Override
    public String getProcessorName() {
        return "TradeSettlementProjection";
    }

    @Override
    protected List<AggregateType> reactsToEventsRelatedToAggregateTypes() {
        return List.of(Trades.AGGREGATE_TYPE,
                       Settlements.AGGREGATE_TYPE);
    }

    @MessageHandler
    void handle(TradePlaced event) {
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
                            event.tradeId().toString(),
                            event.accountId().toString(),
                            event.instrumentId().toString(),
                            event.side().name(),
                            event.quantity().value(),
                            event.price().value(),
                            event.grossAmount().value());
    }

    @MessageHandler
    void handle(TradeExecuted event) {
        jdbcTemplate.update("""
                                    UPDATE projection_trade_settlement
                                    SET executed = true,
                                        updated_at = CURRENT_TIMESTAMP
                                    WHERE trade_id = ?
                                    """,
                            event.tradeId().toString());
    }

    @MessageHandler
    void handle(SettlementRequested event) {
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
                            event.settlementId().toString(),
                            event.tradeId().toString());
    }

    @MessageHandler
    void handle(TradeSettled event) {
        jdbcTemplate.update("""
                                    UPDATE projection_trade_settlement
                                    SET settled = true,
                                        updated_at = CURRENT_TIMESTAMP
                                    WHERE trade_id = ?
                                    """,
                            event.tradeId().toString());
    }

    /**
     * The settlement side of the join. It may arrive before {@link TradePlaced} has been projected — the two streams
     * are independent — so it inserts the row it needs and fills in only the columns a settlement knows about.
     */
    @MessageHandler
    void handle(SettlementCreated event) {
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
                            event.tradeId().toString(),
                            event.accountId().toString(),
                            event.grossAmount().value(),
                            event.settlementId().toString());
    }

    @MessageHandler
    void handle(ClearingRequested event) {
        updateSettlementStatus(event.settlementId().toString(), SettlementStatus.CLEARING_REQUESTED);
    }

    @MessageHandler
    void handle(ClearingConfirmed event) {
        updateSettlementStatus(event.settlementId().toString(), SettlementStatus.CLEARING_CONFIRMED);
    }

    @MessageHandler
    void handle(SettlementMarkedSettled event) {
        updateSettlementStatus(event.settlementId().toString(), SettlementStatus.SETTLED);
    }

    @MessageHandler
    void handle(SettlementReconciled event) {
        updateSettlementStatus(event.settlementId().toString(), SettlementStatus.RECONCILED);
    }

    @MessageHandler
    void handle(SettlementClosed event) {
        updateSettlementStatus(event.settlementId().toString(), SettlementStatus.CLOSED);
    }

    /**
     * Rebuild support: wipe the read model so a subscription reset replays cleanly. Called once per
     * {@link AggregateType} this processor subscribes to — and it truncates the whole table each time, which is
     * correct precisely because the row is a <em>join</em> of the two: a row half-owned by trades and half by
     * settlements cannot be deleted per aggregate type.
     */
    @Override
    protected void onSubscriptionsReset(AggregateType aggregateType,
                                        GlobalEventOrder resubscribeFromAndIncluding) {
        jdbcTemplate.execute("TRUNCATE TABLE projection_trade_settlement");
    }

    /**
     * The column stores {@link SettlementStatus#name()} and is read back with {@code valueOf}, so the enum is the
     * contract rather than a set of string literals scattered across handlers.
     */
    private void updateSettlementStatus(String settlementId, SettlementStatus status) {
        jdbcTemplate.update("""
                                    UPDATE projection_trade_settlement
                                    SET settlement_status = ?,
                                        updated_at = CURRENT_TIMESTAMP
                                    WHERE settlement_id = ?
                                    """,
                            status.name(),
                            settlementId);
    }
}
