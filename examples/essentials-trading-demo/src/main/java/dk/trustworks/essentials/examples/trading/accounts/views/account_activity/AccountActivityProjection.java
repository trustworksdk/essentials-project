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

package dk.trustworks.essentials.examples.trading.accounts.views.account_activity;

import dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.transaction.*;
import dk.trustworks.essentials.examples.trading.accounts.events.*;
import dk.trustworks.essentials.examples.trading.accounts.types.TradeSide;
import dk.trustworks.essentials.examples.trading.config.TradingDemoSchema;
import org.jdbi.v3.core.Handle;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;

import static dk.trustworks.essentials.shared.FailFast.requireNonNull;

/**
 * The statement: one row per account event, keyed by its stream and {@code EventOrder}, so a redelivery inserts nothing.
 * Insert-only — no versioning needed.
 * <p>
 * Engine-neutral: the table and the event logic, on either event store engine. What feeds it is the engine's
 * partitioned processor - {@link AccountActivityClassicView} or {@link AccountActivityEventLogView} - which hands each
 * event here, with its {@code EventOrder} in the account stream, on the handle of the unit of work it handles the event
 * in. The constructor's DDL and {@link #truncate()} open (or join) a unit of work of their own.
 */
@Service
public class AccountActivityProjection {
    private final EventStoreUnitOfWorkFactory<? extends EventStoreUnitOfWork> unitOfWorkFactory;

    public AccountActivityProjection(EventStoreUnitOfWorkFactory<? extends EventStoreUnitOfWork> unitOfWorkFactory) {
        this.unitOfWorkFactory = requireNonNull(unitOfWorkFactory, "No unitOfWorkFactory provided");
        unitOfWorkFactory.usingUnitOfWork(uow -> TradingDemoSchema.ddl(uow.handle(), """
                                                                          CREATE TABLE IF NOT EXISTS projection_account_activity (
                                                                              account_stream_id VARCHAR NOT NULL,
                                                                              event_order BIGINT NOT NULL,
                                                                              account_id VARCHAR NOT NULL,
                                                                              generation BIGINT NOT NULL,
                                                                              kind VARCHAR NOT NULL,
                                                                              reference VARCHAR NULL,
                                                                              description VARCHAR NOT NULL,
                                                                              cash_effect NUMERIC(24, 6) NULL,
                                                                              recorded_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
                                                                              PRIMARY KEY (account_stream_id, event_order)
                                                                          )
                                                                          """));
        unitOfWorkFactory.usingUnitOfWork(uow -> TradingDemoSchema.ddl(uow.handle(), "CREATE INDEX IF NOT EXISTS projection_account_activity_account ON projection_account_activity (account_id, generation, event_order)"));
    }

    void on(Handle handle, AccountOpened e, long eventOrder) {
        record(handle, e, eventOrder, "OPENED", e.periodId().toString(), "Books opened for " + e.periodId(), null);
    }

    void on(Handle handle, CashAdjusted e, long eventOrder) {
        record(handle, e, eventOrder, "ADJUSTMENT", e.adjustmentId().toString(), e.reason() + " (by " + e.adjustedBy() + ")", e.amount().getAmount().value());
    }

    void on(Handle handle, DepositCredited e, long eventOrder) {
        record(handle, e, eventOrder, "DEPOSIT", e.depositId().toString(), "Deposit, withdrawable from " + e.clearsOn(), e.amount().getAmount().value());
    }

    void on(Handle handle, DepositCleared e, long eventOrder) {
        record(handle, e, eventOrder, "DEPOSIT_CLEARED", e.depositId().toString(), "Deposit cleared", null);
    }

    void on(Handle handle, DepositReturned e, long eventOrder) {
        record(handle, e, eventOrder, "DEPOSIT_RETURNED", e.depositId().toString(), "Deposit returned by the bank", e.amount().getAmount().value().negate());
    }

    void on(Handle handle, FundsHeld e, long eventOrder) {
        record(handle, e, eventOrder, "HOLD", e.holdId().toString(), e.purpose() + " hold of " + e.amount(), null);
    }

    void on(Handle handle, FundsReleased e, long eventOrder) {
        record(handle, e, eventOrder, "HOLD_RELEASED", e.holdId().toString(), "Hold released, " + e.amount() + " remaining", null);
    }

    void on(Handle handle, CashWithdrawn e, long eventOrder) {
        record(handle, e, eventOrder, "WITHDRAWAL", e.withdrawalId().toString(), "Withdrawal", e.amount().getAmount().value().negate());
    }

    void on(Handle handle, SettlementAmountCorrected e, long eventOrder) {
        record(handle, e, eventOrder, "SETTLEMENT_CORRECTED", e.executionId().toString(), "Settlement of " + e.side() + " " + e.instrumentId() + " corrected", e.cashChange().getAmount().value());
    }

    void on(Handle handle, WithdrawalReturnCredited e, long eventOrder) {
        record(handle, e, eventOrder, "WITHDRAWAL_RETURNED", e.withdrawalId().toString(), "Withdrawal returned by the bank", e.amount().getAmount().value());
    }

    void on(Handle handle, SharesReserved e, long eventOrder) {
        record(handle, e, eventOrder, "RESERVATION", e.reservationId().toString(), e.quantity() + " " + e.instrumentId() + " reserved", null);
    }

    void on(Handle handle, SharesReleased e, long eventOrder) {
        record(handle, e, eventOrder, "RESERVATION_RELEASED", e.reservationId().toString(), e.quantity() + " " + e.instrumentId() + " released", null);
    }

    void on(Handle handle, TradeBooked e, long eventOrder) {
        var t = e.trade();
        var gross = t.grossAmount().getAmount().value();
        record(handle, e, eventOrder, "TRADE", t.executionId().toString(),
               t.side() + " " + t.quantity() + " " + t.instrumentId() + " @ " + e.price() + ", trade date " + t.tradeDate() + ", settles " + t.settlementDate(),
               t.side() == TradeSide.BUY ? gross.negate() : gross);
    }

    void on(Handle handle, TradeSettled e, long eventOrder) {
        record(handle, e, eventOrder, "TRADE_SETTLED", e.trade().executionId().toString(), "Settled", null);
    }

    void on(Handle handle, TradeBookingReversed e, long eventOrder) {
        var gross = e.trade().grossAmount().getAmount().value();
        record(handle, e, eventOrder, "TRADE_REVERSED", e.trade().executionId().toString(), "Reversed: " + e.reason(), e.trade().side() == TradeSide.BUY ? gross : gross.negate());
    }

    void on(Handle handle, UnknownReferenceIgnored e, long eventOrder) {
        record(handle, e, eventOrder, "IGNORED", e.reference(), e.kind() + " for a reference this account never opened - changed nothing", null);
    }

    void on(Handle handle, AccountBooksClosed e, long eventOrder) {
        record(handle, e, eventOrder, "BOOKS_CLOSED", e.nextPeriodId().toString(), "Books closed; next period " + e.nextPeriodId(), null);
    }

    /**
     * Empties the table, for a classic subscription reset.
     */
    void truncate() {
        unitOfWorkFactory.usingUnitOfWork(uow -> truncate(uow.handle()));
    }

    /** Empties the view on the caller's transaction: the event-log twin's rebuild */
    void truncate(Handle handle) {
        handle.execute("TRUNCATE TABLE projection_account_activity");
    }

    private static void record(Handle handle, AccountEvent e, long eventOrder, String kind, String reference, String description, BigDecimal cashEffect) {
        handle.createUpdate("""
                                INSERT INTO projection_account_activity (account_stream_id, event_order, account_id, generation, kind, reference, description, cash_effect)
                                VALUES (:accountStreamId, :eventOrder, :accountId, :generation, :kind, :reference, :description, :cashEffect)
                                ON CONFLICT (account_stream_id, event_order) DO NOTHING
                                """)
              .bind("accountStreamId", e.accountStreamId().toString())
              .bind("eventOrder", eventOrder)
              .bind("accountId", e.accountId().toString())
              .bind("generation", e.accountStreamId().generation())
              .bind("kind", kind)
              .bind("reference", reference)
              .bind("description", description)
              .bind("cashEffect", cashEffect)
              .execute();
    }
}
