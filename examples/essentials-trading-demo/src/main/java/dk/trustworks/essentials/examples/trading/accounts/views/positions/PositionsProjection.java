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

import dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.transaction.*;
import dk.trustworks.essentials.examples.trading.accounts.events.*;
import dk.trustworks.essentials.examples.trading.accounts.types.*;
import dk.trustworks.essentials.examples.trading.config.TradingDemoSchema;
import dk.trustworks.essentials.examples.trading.market_data.types.InstrumentId;
import org.jdbi.v3.core.Handle;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;

import static dk.trustworks.essentials.shared.FailFast.requireNonNull;

/**
 * Projects positions into {@code projection_positions}, one row per account and instrument. Versioned per account in
 * {@code projection_positions_version} — the same generation + last {@code EventOrder} guard as the overview, for the
 * same reason (deltas, redelivery). The guard and the row update commit together in the view's unit of work.
 * <p>
 * Engine-neutral: the tables, the event logic and the queries, on either event store engine. What feeds it is the
 * engine's processor - {@link PositionsClassicView} or {@link PositionsEventLogView} - which hands each event here with
 * the handle of the transaction it handles the event in and the event's {@code EventOrder} in its stream, so the rows
 * commit together with that processor's progress. The constructor's DDL and {@link #truncate()}, which the classic
 * processor calls when its subscriptions are reset, open a unit of work of their own.
 */
@Service
public class PositionsProjection {
    private final EventStoreUnitOfWorkFactory<? extends EventStoreUnitOfWork> unitOfWorkFactory;

    public PositionsProjection(EventStoreUnitOfWorkFactory<? extends EventStoreUnitOfWork> unitOfWorkFactory) {
        this.unitOfWorkFactory = requireNonNull(unitOfWorkFactory, "No unitOfWorkFactory provided");
        unitOfWorkFactory.usingUnitOfWork(uow -> TradingDemoSchema.ddl(uow.handle(), """
                                                                              CREATE TABLE IF NOT EXISTS projection_positions_version (
                                                                                  account_id VARCHAR PRIMARY KEY,
                                                                                  currency VARCHAR NOT NULL,
                                                                                  generation BIGINT NOT NULL,
                                                                                  last_event_order BIGINT NOT NULL
                                                                              )
                                                                              """));
        unitOfWorkFactory.usingUnitOfWork(uow -> TradingDemoSchema.ddl(uow.handle(), """
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
                                                                              """));
    }

    void on(Handle handle, AccountOpened event, long eventOrder) {
        var generation = event.accountStreamId().generation();
        var newer = handle.createUpdate("""
                                                INSERT INTO projection_positions_version (account_id, currency, generation, last_event_order)
                                                VALUES (:accountId, :currency, :generation, :lastEventOrder)
                                                ON CONFLICT (account_id) DO UPDATE SET generation = EXCLUDED.generation, last_event_order = EXCLUDED.last_event_order
                                                WHERE projection_positions_version.generation < EXCLUDED.generation
                                                """)
                          .bind("accountId", event.accountId().toString())
                          .bind("currency", event.currency().toString())
                          .bind("generation", generation)
                          .bind("lastEventOrder", eventOrder)
                          .execute();
        if (newer == 0) {
            return;
        }
        var accountId = event.accountId().toString();
        var currency  = event.currency().toString();
        handle.createUpdate("DELETE FROM projection_positions WHERE account_id = :accountId")
              .bind("accountId", accountId)
              .execute();
        for (var position : event.openingBalances().positions()) {
            handle.createUpdate("""
                                        INSERT INTO projection_positions (account_id, instrument_id, settled, pending_in, pending_out, reserved, cost_basis, currency)
                                        VALUES (:accountId, :instrumentId, :settled, :pendingIn, :pendingOut, 0, :costBasis, :currency)
                                        """)
                  .bind("accountId", accountId)
                  .bind("instrumentId", position.instrumentId().toString())
                  .bind("settled", position.settled().value())
                  .bind("pendingIn", position.pendingIn().value())
                  .bind("pendingOut", position.pendingOut().value())
                  .bind("costBasis", position.costBasis().getAmount().value())
                  .bind("currency", currency)
                  .execute();
        }
        for (var reservation : event.openingBalances().reservations()) {
            change(handle, accountId, reservation.instrumentId(), currency, 0, 0, 0, reservation.remaining().value(), BigDecimal.ZERO);
        }
    }

    void on(Handle handle, SharesReserved event, long eventOrder) {
        if (advance(handle, event, eventOrder)) {
            change(handle, event, event.instrumentId(), 0, 0, 0, event.quantity().value(), BigDecimal.ZERO);
        }
    }

    void on(Handle handle, SharesReleased event, long eventOrder) {
        if (advance(handle, event, eventOrder)) {
            change(handle, event, event.instrumentId(), 0, 0, 0, -event.quantity().value(), BigDecimal.ZERO);
        }
    }

    void on(Handle handle, TradeBooked event, long eventOrder) {
        if (!advance(handle, event, eventOrder)) {
            return;
        }
        var trade = event.trade();
        var cost  = trade.costBasisChange().getAmount().value();
        if (trade.side() == TradeSide.BUY) {
            change(handle, event, trade.instrumentId(), 0, trade.quantity().value(), 0, 0, cost);
        } else {
            change(handle, event, trade.instrumentId(), 0, 0, trade.quantity().value(), -event.reservationConsumed().value(), cost);
        }
    }

    void on(Handle handle, TradeSettled event, long eventOrder) {
        if (!advance(handle, event, eventOrder)) {
            return;
        }
        var trade = event.trade();
        var q     = trade.quantity().value();
        if (trade.side() == TradeSide.BUY) {
            change(handle, event, trade.instrumentId(), q, -q, 0, 0, BigDecimal.ZERO);
        } else {
            change(handle, event, trade.instrumentId(), -q, 0, -q, 0, BigDecimal.ZERO);
        }
    }

    void on(Handle handle, SettlementAmountCorrected event, long eventOrder) {
        if (!advance(handle, event, eventOrder)) {
            return;
        }
        if (event.costBasisChange().signum() != 0) {
            change(handle, event, event.instrumentId(), 0, 0, 0, 0, event.costBasisChange().getAmount().value());
        }
    }

    void on(Handle handle, TradeBookingReversed event, long eventOrder) {
        if (!advance(handle, event, eventOrder)) {
            return;
        }
        var trade = event.trade();
        var q     = trade.quantity().value();
        var cost  = trade.costBasisChange().getAmount().value().negate();
        if (trade.side() == TradeSide.BUY) {
            change(handle, event, trade.instrumentId(), 0, -q, 0, 0, cost);
        } else {
            change(handle, event, trade.instrumentId(), 0, 0, -q, 0, cost);
        }
    }

    /**
     * Claims the event for this account's version row: true once, for the current generation, above the last applied
     * order. Cash events never come here (no handler matches them, which either engine's view allows), so the
     * stored order skips them — harmless, because the guard only needs to be monotonic.
     */
    private boolean advance(Handle handle, AccountEvent event, long eventOrder) {
        return handle.createUpdate("""
                                       UPDATE projection_positions_version SET last_event_order = :eventOrder
                                       WHERE account_id = :accountId AND generation = :generation AND last_event_order < :eventOrder
                                       """)
                     .bind("eventOrder", eventOrder)
                     .bind("accountId", event.accountId().toString())
                     .bind("generation", event.accountStreamId().generation())
                     .execute() == 1;
    }

    private void change(Handle handle, AccountEvent event, InstrumentId instrumentId, long settled, long pendingIn, long pendingOut, long reserved, BigDecimal cost) {
        var currency = handle.createQuery("SELECT currency FROM projection_positions_version WHERE account_id = :accountId")
                             .bind("accountId", event.accountId().toString())
                             .mapTo(String.class)
                             .one();
        change(handle, event.accountId().toString(), instrumentId, currency, settled, pendingIn, pendingOut, reserved, cost);
    }

    private void change(Handle handle, String accountId, InstrumentId instrumentId, String currency, long settled, long pendingIn, long pendingOut, long reserved, BigDecimal cost) {
        handle.createUpdate("""
                                INSERT INTO projection_positions (account_id, instrument_id, settled, pending_in, pending_out, reserved, cost_basis, currency)
                                VALUES (:accountId, :instrumentId, :settled, :pendingIn, :pendingOut, :reserved, :costBasis, :currency)
                                ON CONFLICT (account_id, instrument_id) DO UPDATE
                                SET settled = projection_positions.settled + EXCLUDED.settled,
                                    pending_in = projection_positions.pending_in + EXCLUDED.pending_in,
                                    pending_out = projection_positions.pending_out + EXCLUDED.pending_out,
                                    reserved = projection_positions.reserved + EXCLUDED.reserved,
                                    cost_basis = projection_positions.cost_basis + EXCLUDED.cost_basis
                                """)
              .bind("accountId", accountId)
              .bind("instrumentId", instrumentId.toString())
              .bind("settled", settled)
              .bind("pendingIn", pendingIn)
              .bind("pendingOut", pendingOut)
              .bind("reserved", reserved)
              .bind("costBasis", cost)
              .bind("currency", currency)
              .execute();
    }

    /**
     * Empties the view, for a processor whose subscriptions are reset to replay it from the start.
     */
    void truncate() {
        unitOfWorkFactory.usingUnitOfWork(uow -> uow.handle().execute("TRUNCATE TABLE projection_positions"));
        unitOfWorkFactory.usingUnitOfWork(uow -> uow.handle().execute("TRUNCATE TABLE projection_positions_version"));
    }
}
