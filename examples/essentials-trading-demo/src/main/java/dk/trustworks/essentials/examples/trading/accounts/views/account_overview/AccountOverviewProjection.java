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
package dk.trustworks.essentials.examples.trading.accounts.views.account_overview;

import dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.transaction.*;
import dk.trustworks.essentials.examples.trading.accounts.events.*;
import dk.trustworks.essentials.examples.trading.accounts.types.TradeSide;
import dk.trustworks.essentials.examples.trading.config.TradingDemoSchema;
import dk.trustworks.essentials.types.Money;
import org.jdbi.v3.core.Handle;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;

import static dk.trustworks.essentials.shared.FailFast.requireNonNull;

/**
 * Projects the ledger's buckets into {@code projection_account_overview}, one row per account.
 *
 * <h2>Versioned, because it applies deltas</h2>
 * A partitioned view may redeliver an event, and a delta applied twice is wrong money. So the row carries the
 * generation and the last applied {@code EventOrder}, and every update is guarded (the "Version = EventOrder" pattern
 * of the event-store docs):
 * <ul>
 *     <li>{@link AccountOpened} of generation <i>g</i> sets every bucket absolutely, if <i>g</i> is newer than the row's;</li>
 *     <li>any other event applies its delta only for the row's generation and only above its last applied order.</li>
 * </ul>
 * A late event from an older generation is therefore ignored, which is right: the newer generation's opening balances
 * already include it.
 * <p>
 * Engine-neutral: the table and the event logic, on either event store engine. What feeds it is the engine's
 * partitioned processor - {@link AccountOverviewClassicView} or {@link AccountOverviewEventLogView} - which hands each
 * event here, with its {@code EventOrder} in the account stream, on the handle of the unit of work it handles the event
 * in, so the row commits together with that event's queue message. The constructor's DDL and {@link #truncate()} open
 * (or join) a unit of work of their own.
 */
@Service
public class AccountOverviewProjection {
    private static final BigDecimal ZERO = BigDecimal.ZERO;

    private final EventStoreUnitOfWorkFactory<? extends EventStoreUnitOfWork> unitOfWorkFactory;

    public AccountOverviewProjection(EventStoreUnitOfWorkFactory<? extends EventStoreUnitOfWork> unitOfWorkFactory) {
        this.unitOfWorkFactory = requireNonNull(unitOfWorkFactory, "No unitOfWorkFactory provided");
        unitOfWorkFactory.usingUnitOfWork(uow -> TradingDemoSchema.ddl(uow.handle(), """
                                                                              CREATE TABLE IF NOT EXISTS projection_account_overview (
                                                                                  account_id VARCHAR PRIMARY KEY,
                                                                                  owner_id VARCHAR NOT NULL,
                                                                                  currency VARCHAR NOT NULL,
                                                                                  generation BIGINT NOT NULL,
                                                                                  period_id VARCHAR NOT NULL,
                                                                                  last_event_order BIGINT NOT NULL,
                                                                                  settled NUMERIC(24, 6) NOT NULL,
                                                                                  pending_in NUMERIC(24, 6) NOT NULL,
                                                                                  pending_out NUMERIC(24, 6) NOT NULL,
                                                                                  held NUMERIC(24, 6) NOT NULL,
                                                                                  uncleared NUMERIC(24, 6) NOT NULL,
                                                                                  realized_pnl NUMERIC(24, 6) NOT NULL,
                                                                                  updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP
                                                                              )
                                                                              """));
        unitOfWorkFactory.usingUnitOfWork(uow -> TradingDemoSchema.ddl(uow.handle(), "CREATE INDEX IF NOT EXISTS projection_account_overview_owner ON projection_account_overview (owner_id)"));
    }

    void on(Handle handle, AccountOpened event, long eventOrder) {
        var opening = event.openingBalances();
        handle.createUpdate("""
                                INSERT INTO projection_account_overview (account_id, owner_id, currency, generation, period_id, last_event_order,
                                                                         settled, pending_in, pending_out, held, uncleared, realized_pnl, updated_at)
                                VALUES (:accountId, :ownerId, :currency, :generation, :periodId, :lastEventOrder,
                                        :settled, :pendingIn, :pendingOut, :held, :uncleared, 0, CURRENT_TIMESTAMP)
                                ON CONFLICT (account_id) DO UPDATE
                                SET generation = EXCLUDED.generation, period_id = EXCLUDED.period_id, last_event_order = EXCLUDED.last_event_order,
                                    settled = EXCLUDED.settled, pending_in = EXCLUDED.pending_in, pending_out = EXCLUDED.pending_out,
                                    held = EXCLUDED.held, uncleared = EXCLUDED.uncleared, realized_pnl = 0, updated_at = CURRENT_TIMESTAMP
                                WHERE projection_account_overview.generation < EXCLUDED.generation
                                """)
              .bind("accountId", event.accountId().toString())
              .bind("ownerId", event.ownerId().toString())
              .bind("currency", event.currency().toString())
              .bind("generation", event.accountStreamId().generation())
              .bind("periodId", event.periodId().toString())
              .bind("lastEventOrder", eventOrder)
              .bind("settled", value(opening.settledCash()))
              .bind("pendingIn", value(opening.pendingCashIn()))
              .bind("pendingOut", value(opening.pendingCashOut()))
              .bind("held", opening.holds().stream().map(hold -> value(hold.remaining())).reduce(ZERO, BigDecimal::add))
              .bind("uncleared", opening.unclearedDeposits().stream().map(deposit -> value(deposit.amount())).reduce(ZERO, BigDecimal::add))
              .execute();
    }

    void on(Handle handle, CashAdjusted event, long eventOrder) {
        delta(handle, event, eventOrder, value(event.amount()), ZERO, ZERO, ZERO, ZERO, ZERO);
    }

    void on(Handle handle, DepositCredited event, long eventOrder) {
        delta(handle, event, eventOrder, value(event.amount()), ZERO, ZERO, ZERO, value(event.amount()), ZERO);
    }

    void on(Handle handle, DepositCleared event, long eventOrder) {
        delta(handle, event, eventOrder, ZERO, ZERO, ZERO, ZERO, value(event.amount()).negate(), ZERO);
    }

    void on(Handle handle, DepositReturned event, long eventOrder) {
        delta(handle, event, eventOrder, value(event.amount()).negate(), ZERO, ZERO, ZERO, value(event.unclearedReleased()).negate(), ZERO);
    }

    void on(Handle handle, FundsHeld event, long eventOrder) {
        delta(handle, event, eventOrder, ZERO, ZERO, ZERO, value(event.amount()), ZERO, ZERO);
    }

    void on(Handle handle, FundsReleased event, long eventOrder) {
        delta(handle, event, eventOrder, ZERO, ZERO, ZERO, value(event.amount()).negate(), ZERO, ZERO);
    }

    void on(Handle handle, CashWithdrawn event, long eventOrder) {
        delta(handle, event, eventOrder, value(event.amount()).negate(), ZERO, ZERO, value(event.holdConsumed()).negate(), ZERO, ZERO);
    }

    void on(Handle handle, SettlementAmountCorrected event, long eventOrder) {
        delta(handle, event, eventOrder, value(event.cashChange()), ZERO, ZERO, ZERO, ZERO, value(event.realizedPnlChange()));
    }

    void on(Handle handle, WithdrawalReturnCredited event, long eventOrder) {
        delta(handle, event, eventOrder, value(event.amount()), ZERO, ZERO, ZERO, ZERO, ZERO);
    }

    void on(Handle handle, TradeBooked event, long eventOrder) {
        var trade = event.trade();
        if (trade.side() == TradeSide.BUY) {
            delta(handle, event, eventOrder, ZERO, ZERO, value(trade.grossAmount()), value(event.holdConsumed()).negate(), ZERO, ZERO);
        } else {
            delta(handle, event, eventOrder, ZERO, value(trade.grossAmount()), ZERO, ZERO, ZERO, value(trade.realizedPnl()));
        }
    }

    void on(Handle handle, TradeSettled event, long eventOrder) {
        var gross = value(event.trade().grossAmount());
        if (event.trade().side() == TradeSide.BUY) {
            delta(handle, event, eventOrder, gross.negate(), ZERO, gross.negate(), ZERO, ZERO, ZERO);
        } else {
            delta(handle, event, eventOrder, gross, gross.negate(), ZERO, ZERO, ZERO, ZERO);
        }
    }

    void on(Handle handle, TradeBookingReversed event, long eventOrder) {
        var trade = event.trade();
        if (trade.side() == TradeSide.BUY) {
            delta(handle, event, eventOrder, ZERO, ZERO, value(trade.grossAmount()).negate(), ZERO, ZERO, ZERO);
        } else {
            delta(handle, event, eventOrder, ZERO, value(trade.grossAmount()).negate(), ZERO, ZERO, ZERO, value(trade.realizedPnl()).negate());
        }
    }

    /**
     * Events that move no bucket still advance the row's last applied order.
     */
    void advance(Handle handle, AccountEvent event, long eventOrder) {
        delta(handle, event, eventOrder, ZERO, ZERO, ZERO, ZERO, ZERO, ZERO);
    }

    /**
     * Empties the table, for a classic subscription reset.
     */
    void truncate() {
        unitOfWorkFactory.usingUnitOfWork(uow -> uow.handle().execute("TRUNCATE TABLE projection_account_overview"));
    }

    private static void delta(Handle handle, AccountEvent event, long eventOrder,
                              BigDecimal settled, BigDecimal pendingIn, BigDecimal pendingOut, BigDecimal held, BigDecimal uncleared, BigDecimal realizedPnl) {
        handle.createUpdate("""
                                UPDATE projection_account_overview
                                SET settled = settled + :settled, pending_in = pending_in + :pendingIn, pending_out = pending_out + :pendingOut,
                                    held = held + :held, uncleared = uncleared + :uncleared, realized_pnl = realized_pnl + :realizedPnl,
                                    last_event_order = :eventOrder, updated_at = CURRENT_TIMESTAMP
                                WHERE account_id = :accountId AND generation = :generation AND last_event_order < :eventOrder
                                """)
              .bind("settled", settled)
              .bind("pendingIn", pendingIn)
              .bind("pendingOut", pendingOut)
              .bind("held", held)
              .bind("uncleared", uncleared)
              .bind("realizedPnl", realizedPnl)
              .bind("eventOrder", eventOrder)
              .bind("accountId", event.accountId().toString())
              .bind("generation", event.accountStreamId().generation())
              .execute();
    }

    private static BigDecimal value(Money money) {
        return money.getAmount().value();
    }
}
