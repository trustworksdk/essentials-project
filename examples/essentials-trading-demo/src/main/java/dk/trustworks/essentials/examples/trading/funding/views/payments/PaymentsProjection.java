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

package dk.trustworks.essentials.examples.trading.funding.views.payments;

import dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.transaction.*;
import dk.trustworks.essentials.examples.trading.config.TradingDemoSchema;
import dk.trustworks.essentials.examples.trading.funding.events.*;
import dk.trustworks.essentials.examples.trading.funding.types.*;
import org.jdbi.v3.core.Handle;
import org.springframework.stereotype.Service;

import java.sql.Date;

import static dk.trustworks.essentials.shared.FailFast.requireNonNull;

/**
 * Every deposit and withdrawal, its status and value date. A status only moves forward along the payment's life
 * ({@code status_rank}), so a redelivered or reordered event never sets an older status back.
 * <p>
 * Engine-neutral: the tables, the event logic and the queries, on either event store engine. What feeds it is the
 * engine's processor - {@link PaymentsClassicView} or {@link PaymentsEventLogView} - which hands each event here with
 * the handle of the transaction it handles the event in, so the row commits together with that processor's progress.
 * The constructor's DDL opens a unit of work of its own.
 */
@Service
public class PaymentsProjection {
    private final EventStoreUnitOfWorkFactory<? extends EventStoreUnitOfWork> unitOfWorkFactory;

    public PaymentsProjection(EventStoreUnitOfWorkFactory<? extends EventStoreUnitOfWork> unitOfWorkFactory) {
        this.unitOfWorkFactory = requireNonNull(unitOfWorkFactory, "No unitOfWorkFactory provided");
        unitOfWorkFactory.usingUnitOfWork(uow -> TradingDemoSchema.ddl(uow.handle(), """
                                                                              CREATE TABLE IF NOT EXISTS projection_funding_payment (
                                                                                  reference VARCHAR NOT NULL,
                                                                                  kind VARCHAR NOT NULL,
                                                                                  account_id VARCHAR NOT NULL,
                                                                                  amount NUMERIC(24, 6) NOT NULL,
                                                                                  currency VARCHAR NOT NULL,
                                                                                  status VARCHAR NOT NULL,
                                                                                  status_rank INT NOT NULL,
                                                                                  payment_id VARCHAR NULL,
                                                                                  value_date DATE NULL,
                                                                                  reason VARCHAR NULL,
                                                                                  iban VARCHAR NOT NULL,
                                                                                  requested_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
                                                                                  updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
                                                                                  PRIMARY KEY (kind, reference)
                                                                              )
                                                                              """));
        unitOfWorkFactory.usingUnitOfWork(uow -> TradingDemoSchema.ddl(uow.handle(), "CREATE INDEX IF NOT EXISTS projection_funding_payment_account ON projection_funding_payment (account_id, requested_at)"));
    }

    void on(Handle handle, DepositRequested e) {
        requested(handle, PaymentKind.DEPOSIT, e.depositId().toString(), e.accountId().toString(), e.amount(), e.bankAccount());
    }

    void on(Handle handle, WithdrawalRequested e) {
        requested(handle, PaymentKind.WITHDRAWAL, e.withdrawalId().toString(), e.accountId().toString(), e.amount(), e.bankAccount());
    }

    void on(Handle handle, DepositInitiated e) {
        handle.createUpdate("UPDATE projection_funding_payment SET payment_id = :paymentId WHERE kind = :kind AND reference = :reference")
              .bind("paymentId", e.paymentId().toString())
              .bind("kind", PaymentKind.DEPOSIT.name())
              .bind("reference", e.depositId().toString())
              .execute();
        status(handle, PaymentKind.DEPOSIT, e.depositId().toString(), PaymentStatus.INITIATED, null, null);
    }

    void on(Handle handle, WithdrawalInitiated e) {
        handle.createUpdate("UPDATE projection_funding_payment SET payment_id = :paymentId WHERE kind = :kind AND reference = :reference")
              .bind("paymentId", e.paymentId().toString())
              .bind("kind", PaymentKind.WITHDRAWAL.name())
              .bind("reference", e.withdrawalId().toString())
              .execute();
        status(handle, PaymentKind.WITHDRAWAL, e.withdrawalId().toString(), PaymentStatus.INITIATED, null, null);
    }

    void on(Handle handle, DepositCompleted e) {
        status(handle, PaymentKind.DEPOSIT, e.depositId().toString(), PaymentStatus.COMPLETED, e.valueDate(), null);
    }

    void on(Handle handle, WithdrawalCompleted e) {
        status(handle, PaymentKind.WITHDRAWAL, e.withdrawalId().toString(), PaymentStatus.COMPLETED, e.valueDate(), null);
    }

    void on(Handle handle, DepositFailed e) {
        status(handle, PaymentKind.DEPOSIT, e.depositId().toString(), PaymentStatus.FAILED, null, e.reason());
    }

    void on(Handle handle, WithdrawalFailed e) {
        status(handle, PaymentKind.WITHDRAWAL, e.withdrawalId().toString(), PaymentStatus.FAILED, null, e.reason());
    }

    void on(Handle handle, DepositReturnedByBank e) {
        status(handle, PaymentKind.DEPOSIT, e.depositId().toString(), PaymentStatus.RETURNED, e.valueDate(), e.reason());
    }

    void on(Handle handle, WithdrawalReturnedByBank e) {
        status(handle, PaymentKind.WITHDRAWAL, e.withdrawalId().toString(), PaymentStatus.RETURNED, e.valueDate(), e.reason());
    }

    private void requested(Handle handle, PaymentKind kind, String reference, String accountId, dk.trustworks.essentials.types.Money amount, BankAccount bankAccount) {
        handle.createUpdate("""
                                INSERT INTO projection_funding_payment (reference, kind, account_id, amount, currency, status, status_rank, iban)
                                VALUES (:reference, :kind, :accountId, :amount, :currency, :status, :statusRank, :iban)
                                ON CONFLICT (kind, reference) DO NOTHING
                                """)
              .bind("reference", reference)
              .bind("kind", kind.name())
              .bind("accountId", accountId)
              .bind("amount", amount.getAmount().value())
              .bind("currency", amount.getCurrency().toString())
              .bind("status", PaymentStatus.REQUESTED.name())
              .bind("statusRank", PaymentStatus.REQUESTED.ordinal())
              .bind("iban", bankAccount.iban())
              .execute();
    }

    /**
     * A completion after a failure moves forward too: it can only follow the brokerage's own {@code GATEWAY_UNAVAILABLE}.
     */
    private void status(Handle handle, PaymentKind kind, String reference, PaymentStatus status, java.time.LocalDate valueDate, String reason) {
        handle.createUpdate("""
                                UPDATE projection_funding_payment
                                SET status = :status, status_rank = :statusRank, value_date = COALESCE(:valueDate, value_date), reason = COALESCE(:reason, reason), updated_at = CURRENT_TIMESTAMP
                                WHERE kind = :kind AND reference = :reference AND (status_rank < :statusRank OR (status = 'FAILED' AND :status = 'COMPLETED'))
                                """)
              .bind("status", status.name())
              .bind("statusRank", status.ordinal())
              .bind("valueDate", valueDate == null ? null : Date.valueOf(valueDate))
              .bind("reason", reason)
              .bind("kind", kind.name())
              .bind("reference", reference)
              .execute();
    }
}
