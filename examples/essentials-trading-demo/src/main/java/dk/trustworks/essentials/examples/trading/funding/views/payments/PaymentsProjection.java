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
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.sql.Date;
import java.util.List;

import static dk.trustworks.essentials.shared.FailFast.requireNonNull;

/**
 * Every deposit and withdrawal, its status and value date. A status only moves forward along the payment's life
 * ({@code status_rank}), so a redelivered or reordered event never sets an older status back.
 */
@Service
public class PaymentsProjection extends ViewEventProcessor {
    private final JdbcTemplate jdbcTemplate;

    public PaymentsProjection(ViewEventProcessorDependencies dependencies, JdbcTemplate jdbcTemplate) {
        super(dependencies);
        this.jdbcTemplate = requireNonNull(jdbcTemplate, "No jdbcTemplate provided");
        this.jdbcTemplate.execute("""
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
                                          """);
        this.jdbcTemplate.execute("CREATE INDEX IF NOT EXISTS projection_funding_payment_account ON projection_funding_payment (account_id, requested_at)");
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
        jdbcTemplate.update("UPDATE projection_funding_payment SET payment_id = ? WHERE kind = ? AND reference = ?", e.paymentId().toString(), PaymentKind.DEPOSIT.name(), e.depositId().toString());
        status(PaymentKind.DEPOSIT, e.depositId().toString(), PaymentStatus.INITIATED, null, null);
    }

    @MessageHandler
    void handle(WithdrawalInitiated e) {
        jdbcTemplate.update("UPDATE projection_funding_payment SET payment_id = ? WHERE kind = ? AND reference = ?", e.paymentId().toString(), PaymentKind.WITHDRAWAL.name(), e.withdrawalId().toString());
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
        jdbcTemplate.update("""
                                    INSERT INTO projection_funding_payment (reference, kind, account_id, amount, currency, status, status_rank, iban)
                                    VALUES (?, ?, ?, ?, ?, ?, ?, ?)
                                    ON CONFLICT (kind, reference) DO NOTHING
                                    """,
                            reference, kind.name(), accountId, amount.getAmount().value(), amount.getCurrency().toString(),
                            PaymentStatus.REQUESTED.name(), PaymentStatus.REQUESTED.ordinal(), bankAccount.iban());
    }

    /**
     * A completion after a failure moves forward too: it can only follow the brokerage's own {@code GATEWAY_UNAVAILABLE}.
     */
    private void status(PaymentKind kind, String reference, PaymentStatus status, java.time.LocalDate valueDate, String reason) {
        jdbcTemplate.update("""
                                    UPDATE projection_funding_payment
                                    SET status = ?, status_rank = ?, value_date = COALESCE(?, value_date), reason = COALESCE(?, reason), updated_at = CURRENT_TIMESTAMP
                                    WHERE kind = ? AND reference = ? AND (status_rank < ? OR (status = 'FAILED' AND ? = 'COMPLETED'))
                                    """,
                            status.name(), status.ordinal(), valueDate == null ? null : Date.valueOf(valueDate), reason,
                            kind.name(), reference, status.ordinal(), status.name());
    }
}
