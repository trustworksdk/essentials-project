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

package dk.trustworks.essentials.examples.trading.reconciliation.views.known_payments;

import dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.transaction.*;
import dk.trustworks.essentials.examples.trading.config.TradingDemoSchema;
import dk.trustworks.essentials.examples.trading.funding.events.*;
import dk.trustworks.essentials.types.Money;
import org.jdbi.v3.core.Handle;
import org.springframework.stereotype.Service;

import java.sql.Date;
import java.time.LocalDate;

import static dk.trustworks.essentials.shared.FailFast.requireNonNull;

/**
 * {@code reconciliation}'s own record of the brokerage's payments, projected from {@code funding}'s events - a
 * cross-BC {@code events/} import, so reconciliation never calls into {@code funding}: what was requested (kind,
 * amount), the gateway's id it was taken under, and the value dates of its completion and return. What a bank statement is checked against.
 * <p>
 * Engine-neutral: the tables, the event logic and the queries, on either event store engine. What feeds it is the
 * engine's processor - {@link KnownPaymentsClassicView} or {@link KnownPaymentsEventLogView} - which hands each event here with
 * the handle of the transaction it handles the event in, so the row commits together with that processor's progress.
 * The constructor's DDL opens a unit of work of its own.
 */
@Service
public class KnownPaymentsProjection {
    private final EventStoreUnitOfWorkFactory<? extends EventStoreUnitOfWork> unitOfWorkFactory;

    public KnownPaymentsProjection(EventStoreUnitOfWorkFactory<? extends EventStoreUnitOfWork> unitOfWorkFactory) {
        this.unitOfWorkFactory = requireNonNull(unitOfWorkFactory, "No unitOfWorkFactory provided");
        unitOfWorkFactory.usingUnitOfWork(uow -> TradingDemoSchema.ddl(uow.handle(), """
                                                                              CREATE TABLE IF NOT EXISTS projection_reconciliation_payment (
                                                                                  reference VARCHAR PRIMARY KEY,
                                                                                  kind VARCHAR NOT NULL,
                                                                                  amount NUMERIC(24, 6) NOT NULL,
                                                                                  currency VARCHAR NOT NULL,
                                                                                  payment_id VARCHAR NULL,
                                                                                  completed_on DATE NULL,
                                                                                  returned_on DATE NULL
                                                                              )
                                                                              """));
    }

    void on(Handle handle, DepositRequested e) {
        requested(handle, e.depositId().toString(), "DEPOSIT", e.amount());
    }

    void on(Handle handle, WithdrawalRequested e) {
        requested(handle, e.withdrawalId().toString(), "WITHDRAWAL", e.amount());
    }

    void on(Handle handle, DepositInitiated e) {
        handle.createUpdate("UPDATE projection_reconciliation_payment SET payment_id = :paymentId WHERE reference = :reference")
              .bind("paymentId", e.paymentId().toString())
              .bind("reference", e.depositId().toString())
              .execute();
    }

    void on(Handle handle, WithdrawalInitiated e) {
        handle.createUpdate("UPDATE projection_reconciliation_payment SET payment_id = :paymentId WHERE reference = :reference")
              .bind("paymentId", e.paymentId().toString())
              .bind("reference", e.withdrawalId().toString())
              .execute();
    }

    void on(Handle handle, DepositCompleted e) {
        setDate(handle, "completed_on", e.depositId().toString(), e.valueDate());
    }

    void on(Handle handle, WithdrawalCompleted e) {
        setDate(handle, "completed_on", e.withdrawalId().toString(), e.valueDate());
    }

    void on(Handle handle, DepositReturnedByBank e) {
        setDate(handle, "returned_on", e.depositId().toString(), e.valueDate());
    }

    void on(Handle handle, WithdrawalReturnedByBank e) {
        setDate(handle, "returned_on", e.withdrawalId().toString(), e.valueDate());
    }

    private void requested(Handle handle, String reference, String kind, Money amount) {
        handle.createUpdate("INSERT INTO projection_reconciliation_payment (reference, kind, amount, currency) VALUES (:reference, :kind, :amount, :currency) ON CONFLICT (reference) DO NOTHING")
              .bind("reference", reference)
              .bind("kind", kind)
              .bind("amount", amount.getAmount().value())
              .bind("currency", amount.getCurrency().toString())
              .execute();
    }

    private void setDate(Handle handle, String column, String reference, LocalDate valueDate) {
        handle.createUpdate("UPDATE projection_reconciliation_payment SET " + column + " = :valueDate WHERE reference = :reference")
              .bind("valueDate", Date.valueOf(valueDate))
              .bind("reference", reference)
              .execute();
    }
}
