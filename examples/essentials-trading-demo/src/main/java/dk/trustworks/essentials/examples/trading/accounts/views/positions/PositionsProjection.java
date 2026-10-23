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

package dk.trustworks.essentials.examples.trading.accounts.views.positions;

import dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.eventstream.AggregateType;
import dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.processor.*;
import dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.types.GlobalEventOrder;
import dk.trustworks.essentials.components.foundation.messaging.MessageHandler;
import dk.trustworks.essentials.components.foundation.messaging.queue.OrderedMessage;
import dk.trustworks.essentials.examples.trading.accounts.events.*;
import dk.trustworks.essentials.examples.trading.accounts.types.*;
import dk.trustworks.essentials.examples.trading.market_data.types.InstrumentId;
import dk.trustworks.essentials.examples.trading.config.TradingDemoViewEventProcessor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.util.List;

import static dk.trustworks.essentials.shared.FailFast.requireNonNull;

/**
 * Projects positions into {@code projection_positions}, one row per account and instrument. Versioned per account in
 * {@code projection_positions_version} — the same generation + last {@code EventOrder} guard as the overview, for the
 * same reason (deltas, redelivery). The guard and the row update commit together in the view's unit of work.
 */
@Service
public class PositionsProjection extends TradingDemoViewEventProcessor {
    private final JdbcTemplate jdbcTemplate;

    public PositionsProjection(ViewEventProcessorDependencies dependencies, JdbcTemplate jdbcTemplate) {
        super(dependencies);
        this.jdbcTemplate = requireNonNull(jdbcTemplate, "No jdbcTemplate provided");
        this.jdbcTemplate.execute("""
                                          CREATE TABLE IF NOT EXISTS projection_positions_version (
                                              account_id VARCHAR PRIMARY KEY,
                                              currency VARCHAR NOT NULL,
                                              generation BIGINT NOT NULL,
                                              last_event_order BIGINT NOT NULL
                                          )
                                          """);
        this.jdbcTemplate.execute("""
                                          CREATE TABLE IF NOT EXISTS projection_positions (
                                              account_id VARCHAR NOT NULL,
                                              instrument_id VARCHAR NOT NULL,
                                              settled BIGINT NOT NULL DEFAULT 0,
                                              pending_in BIGINT NOT NULL DEFAULT 0,
                                              pending_out BIGINT NOT NULL DEFAULT 0,
                                              reserved BIGINT NOT NULL DEFAULT 0,
                                              cost_basis NUMERIC(24, 6) NOT NULL DEFAULT 0,
                                              currency VARCHAR NOT NULL,
                                              PRIMARY KEY (account_id, instrument_id)
                                          )
                                          """);
    }

    @Override
    public String getProcessorName() {
        return "PositionsProjection";
    }

    @Override
    protected List<AggregateType> reactsToEventsRelatedToAggregateTypes() {
        return List.of(AccountsAggregateTypes.ACCOUNTS);
    }

    @MessageHandler
    void handle(AccountOpened event, OrderedMessage message) {
        var generation = event.accountStreamId().generation();
        var newer = jdbcTemplate.update("""
                                                INSERT INTO projection_positions_version (account_id, currency, generation, last_event_order) VALUES (?, ?, ?, ?)
                                                ON CONFLICT (account_id) DO UPDATE SET generation = EXCLUDED.generation, last_event_order = EXCLUDED.last_event_order
                                                WHERE projection_positions_version.generation < EXCLUDED.generation
                                                """,
                                        event.accountId().toString(), event.currency().toString(), generation, message.getOrder());
        if (newer == 0) {
            return;
        }
        var accountId = event.accountId().toString();
        var currency  = event.currency().toString();
        jdbcTemplate.update("DELETE FROM projection_positions WHERE account_id = ?", accountId);
        for (var position : event.openingBalances().positions()) {
            jdbcTemplate.update("""
                                        INSERT INTO projection_positions (account_id, instrument_id, settled, pending_in, pending_out, reserved, cost_basis, currency)
                                        VALUES (?, ?, ?, ?, ?, 0, ?, ?)
                                        """,
                                accountId, position.instrumentId().toString(), position.settled().value(), position.pendingIn().value(),
                                position.pendingOut().value(), position.costBasis().getAmount().value(), currency);
        }
        for (var reservation : event.openingBalances().reservations()) {
            change(accountId, reservation.instrumentId(), currency, 0, 0, 0, reservation.remaining().value(), BigDecimal.ZERO);
        }
    }

    @MessageHandler
    void handle(SharesReserved event, OrderedMessage message) {
        if (advance(event, message)) {
            change(event, event.instrumentId(), 0, 0, 0, event.quantity().value(), BigDecimal.ZERO);
        }
    }

    @MessageHandler
    void handle(SharesReleased event, OrderedMessage message) {
        if (advance(event, message)) {
            change(event, event.instrumentId(), 0, 0, 0, -event.quantity().value(), BigDecimal.ZERO);
        }
    }

    @MessageHandler
    void handle(TradeBooked event, OrderedMessage message) {
        if (!advance(event, message)) {
            return;
        }
        var trade = event.trade();
        var cost  = trade.costBasisChange().getAmount().value();
        if (trade.side() == TradeSide.BUY) {
            change(event, trade.instrumentId(), 0, trade.quantity().value(), 0, 0, cost);
        } else {
            change(event, trade.instrumentId(), 0, 0, trade.quantity().value(), -event.reservationConsumed().value(), cost);
        }
    }

    @MessageHandler
    void handle(TradeSettled event, OrderedMessage message) {
        if (!advance(event, message)) {
            return;
        }
        var trade = event.trade();
        var q     = trade.quantity().value();
        if (trade.side() == TradeSide.BUY) {
            change(event, trade.instrumentId(), q, -q, 0, 0, BigDecimal.ZERO);
        } else {
            change(event, trade.instrumentId(), -q, 0, -q, 0, BigDecimal.ZERO);
        }
    }

    @MessageHandler
    void handle(SettlementAmountCorrected event, OrderedMessage message) {
        if (!advance(event, message)) {
            return;
        }
        if (event.costBasisChange().signum() != 0) {
            change(event, event.instrumentId(), 0, 0, 0, 0, event.costBasisChange().getAmount().value());
        }
    }

    @MessageHandler
    void handle(TradeBookingReversed event, OrderedMessage message) {
        if (!advance(event, message)) {
            return;
        }
        var trade = event.trade();
        var q     = trade.quantity().value();
        var cost  = trade.costBasisChange().getAmount().value().negate();
        if (trade.side() == TradeSide.BUY) {
            change(event, trade.instrumentId(), 0, -q, 0, 0, cost);
        } else {
            change(event, trade.instrumentId(), 0, 0, -q, 0, cost);
        }
    }

    /**
     * Claims the event for this account's version row: true once, for the current generation, above the last applied
     * order. Cash events never come here (no handler matches them, which a {@code ViewEventProcessor} allows), so the
     * stored order skips them — harmless, because the guard only needs to be monotonic.
     */
    private boolean advance(AccountEvent event, OrderedMessage message) {
        return jdbcTemplate.update("""
                                           UPDATE projection_positions_version SET last_event_order = ?
                                           WHERE account_id = ? AND generation = ? AND last_event_order < ?
                                           """,
                                   message.getOrder(), event.accountId().toString(), event.accountStreamId().generation(), message.getOrder()) == 1;
    }

    private void change(AccountEvent event, InstrumentId instrumentId, long settled, long pendingIn, long pendingOut, long reserved, BigDecimal cost) {
        var currency = jdbcTemplate.queryForObject("SELECT currency FROM projection_positions_version WHERE account_id = ?", String.class, event.accountId().toString());
        change(event.accountId().toString(), instrumentId, currency, settled, pendingIn, pendingOut, reserved, cost);
    }

    private void change(String accountId, InstrumentId instrumentId, String currency, long settled, long pendingIn, long pendingOut, long reserved, BigDecimal cost) {
        jdbcTemplate.update("""
                                    INSERT INTO projection_positions (account_id, instrument_id, settled, pending_in, pending_out, reserved, cost_basis, currency)
                                    VALUES (?, ?, ?, ?, ?, ?, ?, ?)
                                    ON CONFLICT (account_id, instrument_id) DO UPDATE
                                    SET settled = projection_positions.settled + EXCLUDED.settled,
                                        pending_in = projection_positions.pending_in + EXCLUDED.pending_in,
                                        pending_out = projection_positions.pending_out + EXCLUDED.pending_out,
                                        reserved = projection_positions.reserved + EXCLUDED.reserved,
                                        cost_basis = projection_positions.cost_basis + EXCLUDED.cost_basis
                                    """,
                            accountId, instrumentId.toString(), settled, pendingIn, pendingOut, reserved, cost, currency);
    }

    @Override
    protected void onSubscriptionsReset(AggregateType aggregateType, GlobalEventOrder resubscribeFromAndIncluding) {
        jdbcTemplate.execute("TRUNCATE TABLE projection_positions");
        jdbcTemplate.execute("TRUNCATE TABLE projection_positions_version");
    }
}
