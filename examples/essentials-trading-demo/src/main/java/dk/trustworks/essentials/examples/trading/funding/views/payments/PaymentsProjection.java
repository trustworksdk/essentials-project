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

import dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.eventstream.AggregateType;
import dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.processor.*;
import dk.trustworks.essentials.components.foundation.messaging.MessageHandler;
import dk.trustworks.essentials.examples.trading.funding.aggregates.*;
import dk.trustworks.essentials.examples.trading.funding.events.*;
import dk.trustworks.essentials.examples.trading.funding.types.*;
import dk.trustworks.essentials.examples.trading.config.TradingDemoViewEventProcessor;
import org.springframework.stereotype.Service;

import java.sql.Date;
import java.util.List;

/**
 * Every deposit and withdrawal, its status and value date. A status only moves forward along the payment's life
 * ({@code status_rank}), so a redelivered or reordered event never sets an older status back.
 * <p>
 * Every handler write goes through {@code getRequiredUnitOfWork().handle()…}, the {@code UnitOfWork} a
 * {@link ViewEventProcessor} handles each event in, so the row commits together with the processor's progress by construction - not, as
 * with Spring's {@code JdbcTemplate}, only while the Spring wiring binds both to one connection. The
 * constructor's DDL runs where none is active, so it goes through {@code getUnitOfWorkFactory().usingUnitOfWork(uow -> …)},
 * which opens one.
 */
@Service
public class PaymentsProjection extends TradingDemoViewEventProcessor {
    public PaymentsProjection(ViewEventProcessorDependencies dependencies) {
        super(dependencies);
        getUnitOfWorkFactory().usingUnitOfWork(uow -> uow.handle().execute("""
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
        getUnitOfWorkFactory().usingUnitOfWork(uow -> uow.handle().execute("CREATE INDEX IF NOT EXISTS projection_funding_payment_account ON projection_funding_payment (account_id, requested_at)"));
    }

    @Override
    public String getProcessorName() {
        return "PaymentsProjection";
    }

    @Override
    protected List<AggregateType> reactsToEventsRelatedToAggregateTypes() {
        return List.of(Deposits.AGGREGATE_TYPE, Withdrawals.AGGREGATE_TYPE);
    }

    @MessageHandler
    void handle(DepositRequested e) {
        requested(PaymentKind.DEPOSIT, e.depositId().toString(), e.accountId().toString(), e.amount(), e.bankAccount());
    }

    @MessageHandler
    void handle(WithdrawalRequested e) {
        requested(PaymentKind.WITHDRAWAL, e.withdrawalId().toString(), e.accountId().toString(), e.amount(), e.bankAccount());
    }

    @MessageHandler
    void handle(DepositInitiated e) {
        getRequiredUnitOfWork().handle()
                                .createUpdate("UPDATE projection_funding_payment SET payment_id = :paymentId WHERE kind = :kind AND reference = :reference")
                                .bind("paymentId", e.paymentId().toString())
                                .bind("kind", PaymentKind.DEPOSIT.name())
                                .bind("reference", e.depositId().toString())
                                .execute();
        status(PaymentKind.DEPOSIT, e.depositId().toString(), PaymentStatus.INITIATED, null, null);
    }

    @MessageHandler
    void handle(WithdrawalInitiated e) {
        getRequiredUnitOfWork().handle()
                                .createUpdate("UPDATE projection_funding_payment SET payment_id = :paymentId WHERE kind = :kind AND reference = :reference")
                                .bind("paymentId", e.paymentId().toString())
                                .bind("kind", PaymentKind.WITHDRAWAL.name())
                                .bind("reference", e.withdrawalId().toString())
                                .execute();
        status(PaymentKind.WITHDRAWAL, e.withdrawalId().toString(), PaymentStatus.INITIATED, null, null);
    }

    @MessageHandler
    void handle(DepositCompleted e) {
        status(PaymentKind.DEPOSIT, e.depositId().toString(), PaymentStatus.COMPLETED, e.valueDate(), null);
    }

    @MessageHandler
    void handle(WithdrawalCompleted e) {
        status(PaymentKind.WITHDRAWAL, e.withdrawalId().toString(), PaymentStatus.COMPLETED, e.valueDate(), null);
    }

    @MessageHandler
    void handle(DepositFailed e) {
        status(PaymentKind.DEPOSIT, e.depositId().toString(), PaymentStatus.FAILED, null, e.reason());
    }

    @MessageHandler
    void handle(WithdrawalFailed e) {
        status(PaymentKind.WITHDRAWAL, e.withdrawalId().toString(), PaymentStatus.FAILED, null, e.reason());
    }

    @MessageHandler
    void handle(DepositReturnedByBank e) {
        status(PaymentKind.DEPOSIT, e.depositId().toString(), PaymentStatus.RETURNED, e.valueDate(), e.reason());
    }

    @MessageHandler
    void handle(WithdrawalReturnedByBank e) {
        status(PaymentKind.WITHDRAWAL, e.withdrawalId().toString(), PaymentStatus.RETURNED, e.valueDate(), e.reason());
    }

    private void requested(PaymentKind kind, String reference, String accountId, dk.trustworks.essentials.types.Money amount, BankAccount bankAccount) {
        getRequiredUnitOfWork().handle().createUpdate("""
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
    private void status(PaymentKind kind, String reference, PaymentStatus status, java.time.LocalDate valueDate, String reason) {
        getRequiredUnitOfWork().handle().createUpdate("""
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
