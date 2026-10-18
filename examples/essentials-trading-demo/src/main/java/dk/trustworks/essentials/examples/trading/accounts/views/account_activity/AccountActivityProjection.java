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

import dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.eventstream.AggregateType;
import dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.processor.*;
import dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.types.GlobalEventOrder;
import dk.trustworks.essentials.components.foundation.messaging.MessageHandler;
import dk.trustworks.essentials.components.foundation.messaging.queue.OrderedMessage;
import dk.trustworks.essentials.examples.trading.accounts.events.*;
import dk.trustworks.essentials.examples.trading.accounts.types.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.util.List;

import static dk.trustworks.essentials.shared.FailFast.requireNonNull;

/**
 * The statement: one row per account event, keyed by its stream and {@code EventOrder}, so a redelivery inserts nothing.
 * Insert-only — no versioning needed.
 */
@Service
public class AccountActivityProjection extends ViewEventProcessor {
    private final JdbcTemplate jdbcTemplate;

    public AccountActivityProjection(ViewEventProcessorDependencies dependencies, JdbcTemplate jdbcTemplate) {
        super(dependencies);
        this.jdbcTemplate = requireNonNull(jdbcTemplate, "No jdbcTemplate provided");
        this.jdbcTemplate.execute("""
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
                                          """);
        this.jdbcTemplate.execute("CREATE INDEX IF NOT EXISTS projection_account_activity_account ON projection_account_activity (account_id, generation, event_order)");
    }

    @Override
    public String getProcessorName() {
        return "AccountActivityProjection";
    }

    @Override
    protected List<AggregateType> reactsToEventsRelatedToAggregateTypes() {
        return List.of(AccountsAggregateTypes.ACCOUNTS);
    }

    @MessageHandler
    void handle(AccountOpened e, OrderedMessage m) {
        record(e, m, "OPENED", e.periodId().toString(), "Books opened for " + e.periodId(), null);
    }

    @MessageHandler
    void handle(CashAdjusted e, OrderedMessage m) {
        record(e, m, "ADJUSTMENT", e.adjustmentId().toString(), e.reason() + " (by " + e.adjustedBy() + ")", e.amount().getAmount().value());
    }

    @MessageHandler
    void handle(DepositCredited e, OrderedMessage m) {
        record(e, m, "DEPOSIT", e.depositId().toString(), "Deposit, withdrawable from " + e.clearsOn(), e.amount().getAmount().value());
    }

    @MessageHandler
    void handle(DepositCleared e, OrderedMessage m) {
        record(e, m, "DEPOSIT_CLEARED", e.depositId().toString(), "Deposit cleared", null);
    }

    @MessageHandler
    void handle(DepositReturned e, OrderedMessage m) {
        record(e, m, "DEPOSIT_RETURNED", e.depositId().toString(), "Deposit returned by the bank", e.amount().getAmount().value().negate());
    }

    @MessageHandler
    void handle(FundsHeld e, OrderedMessage m) {
        record(e, m, "HOLD", e.holdId().toString(), e.purpose() + " hold of " + e.amount(), null);
    }

    @MessageHandler
    void handle(FundsReleased e, OrderedMessage m) {
        record(e, m, "HOLD_RELEASED", e.holdId().toString(), "Hold released, " + e.amount() + " remaining", null);
    }

    @MessageHandler
    void handle(CashWithdrawn e, OrderedMessage m) {
        record(e, m, "WITHDRAWAL", e.withdrawalId().toString(), "Withdrawal", e.amount().getAmount().value().negate());
    }

    @MessageHandler
    void handle(SharesReserved e, OrderedMessage m) {
        record(e, m, "RESERVATION", e.reservationId().toString(), e.quantity() + " " + e.instrumentId() + " reserved", null);
    }

    @MessageHandler
    void handle(SharesReleased e, OrderedMessage m) {
        record(e, m, "RESERVATION_RELEASED", e.reservationId().toString(), e.quantity() + " " + e.instrumentId() + " released", null);
    }

    @MessageHandler
    void handle(TradeBooked e, OrderedMessage m) {
        var t = e.trade();
        var gross = t.grossAmount().getAmount().value();
        record(e, m, "TRADE", t.executionId().toString(),
               t.side() + " " + t.quantity() + " " + t.instrumentId() + " @ " + e.price() + ", trade date " + t.tradeDate() + ", settles " + t.settlementDate(),
               t.side() == TradeSide.BUY ? gross.negate() : gross);
    }

    @MessageHandler
    void handle(TradeSettled e, OrderedMessage m) {
        record(e, m, "TRADE_SETTLED", e.trade().executionId().toString(), "Settled", null);
    }

    @MessageHandler
    void handle(TradeBookingReversed e, OrderedMessage m) {
        var gross = e.trade().grossAmount().getAmount().value();
        record(e, m, "TRADE_REVERSED", e.trade().executionId().toString(), "Reversed: " + e.reason(), e.trade().side() == TradeSide.BUY ? gross : gross.negate());
    }

    @MessageHandler
    void handle(AccountBooksClosed e, OrderedMessage m) {
        record(e, m, "BOOKS_CLOSED", e.nextPeriodId().toString(), "Books closed; next period " + e.nextPeriodId(), null);
    }

    private void record(AccountEvent e, OrderedMessage m, String kind, String reference, String description, BigDecimal cashEffect) {
        jdbcTemplate.update("""
                                    INSERT INTO projection_account_activity (account_stream_id, event_order, account_id, generation, kind, reference, description, cash_effect)
                                    VALUES (?, ?, ?, ?, ?, ?, ?, ?)
                                    ON CONFLICT (account_stream_id, event_order) DO NOTHING
                                    """,
                            e.accountStreamId().toString(), m.getOrder(), e.accountId().toString(), e.accountStreamId().generation(),
                            kind, reference, description, cashEffect);
    }

    @Override
    protected void onSubscriptionsReset(AggregateType aggregateType, GlobalEventOrder resubscribeFromAndIncluding) {
        jdbcTemplate.execute("TRUNCATE TABLE projection_account_activity");
    }
}
