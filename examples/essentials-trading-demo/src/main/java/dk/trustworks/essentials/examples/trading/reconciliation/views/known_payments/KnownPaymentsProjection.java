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

import dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.eventstream.AggregateType;
import dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.processor.*;
import dk.trustworks.essentials.components.foundation.messaging.MessageHandler;
import dk.trustworks.essentials.examples.trading.funding.events.*;
import dk.trustworks.essentials.examples.trading.funding.types.FundingAggregateTypes;
import dk.trustworks.essentials.types.Money;
import dk.trustworks.essentials.examples.trading.config.TradingDemoViewEventProcessor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.sql.Date;
import java.time.LocalDate;
import java.util.List;

import static dk.trustworks.essentials.shared.FailFast.requireNonNull;

/**
 * {@code reconciliation}'s own record of the brokerage's payments, projected from {@code funding}'s events - a
 * cross-BC {@code events/} import, so reconciliation never calls into {@code funding}: what was requested (kind,
 * amount), the gateway's id it was taken under, and the value dates of its completion and return. What a bank statement is checked against.
 */
@Service
public class KnownPaymentsProjection extends TradingDemoViewEventProcessor {
    private final JdbcTemplate jdbcTemplate;

    public KnownPaymentsProjection(ViewEventProcessorDependencies dependencies, JdbcTemplate jdbcTemplate) {
        super(dependencies);
        this.jdbcTemplate = requireNonNull(jdbcTemplate, "No jdbcTemplate provided");
        this.jdbcTemplate.execute("""
                                          CREATE TABLE IF NOT EXISTS projection_reconciliation_payment (
                                              reference VARCHAR PRIMARY KEY,
                                              kind VARCHAR NOT NULL,
                                              amount NUMERIC(24, 6) NOT NULL,
                                              currency VARCHAR NOT NULL,
                                              payment_id VARCHAR NULL,
                                              completed_on DATE NULL,
                                              returned_on DATE NULL
                                          )
                                          """);
    }

    @Override
    public String getProcessorName() {
        return "ReconciliationKnownPaymentsProjection";
    }

    @Override
    protected List<AggregateType> reactsToEventsRelatedToAggregateTypes() {
        return List.of(FundingAggregateTypes.DEPOSITS, FundingAggregateTypes.WITHDRAWALS);
    }

    @MessageHandler
    void handle(DepositRequested e) {
        requested(e.depositId().toString(), "DEPOSIT", e.amount());
    }

    @MessageHandler
    void handle(WithdrawalRequested e) {
        requested(e.withdrawalId().toString(), "WITHDRAWAL", e.amount());
    }

    @MessageHandler
    void handle(DepositInitiated e) {
        jdbcTemplate.update("UPDATE projection_reconciliation_payment SET payment_id = ? WHERE reference = ?", e.paymentId().toString(), e.depositId().toString());
    }

    @MessageHandler
    void handle(WithdrawalInitiated e) {
        jdbcTemplate.update("UPDATE projection_reconciliation_payment SET payment_id = ? WHERE reference = ?", e.paymentId().toString(), e.withdrawalId().toString());
    }

    @MessageHandler
    void handle(DepositCompleted e) {
        on("completed_on", e.depositId().toString(), e.valueDate());
    }

    @MessageHandler
    void handle(WithdrawalCompleted e) {
        on("completed_on", e.withdrawalId().toString(), e.valueDate());
    }

    @MessageHandler
    void handle(DepositReturnedByBank e) {
        on("returned_on", e.depositId().toString(), e.valueDate());
    }

    @MessageHandler
    void handle(WithdrawalReturnedByBank e) {
        on("returned_on", e.withdrawalId().toString(), e.valueDate());
    }

    private void requested(String reference, String kind, Money amount) {
        jdbcTemplate.update("INSERT INTO projection_reconciliation_payment (reference, kind, amount, currency) VALUES (?, ?, ?, ?) ON CONFLICT (reference) DO NOTHING",
                            reference, kind, amount.getAmount().value(), amount.getCurrency().toString());
    }

    private void on(String column, String reference, LocalDate valueDate) {
        jdbcTemplate.update("UPDATE projection_reconciliation_payment SET " + column + " = ? WHERE reference = ?", Date.valueOf(valueDate), reference);
    }
}
