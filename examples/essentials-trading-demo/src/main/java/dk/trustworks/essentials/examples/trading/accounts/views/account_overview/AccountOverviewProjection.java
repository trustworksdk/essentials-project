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

import dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.eventstream.AggregateType;
import dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.processor.*;
import dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.types.GlobalEventOrder;
import dk.trustworks.essentials.components.foundation.messaging.MessageHandler;
import dk.trustworks.essentials.components.foundation.messaging.queue.OrderedMessage;
import dk.trustworks.essentials.examples.trading.accounts.events.*;
import dk.trustworks.essentials.examples.trading.accounts.types.*;
import dk.trustworks.essentials.types.Money;
import dk.trustworks.essentials.examples.trading.config.TradingDemoViewEventProcessor;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.util.List;

/**
 * Projects the ledger's buckets into {@code projection_account_overview}, one row per account.
 *
 * <h2>Versioned, because it applies deltas</h2>
 * A {@code ViewEventProcessor} may redeliver an event, and a delta applied twice is wrong money. So the row carries the
 * generation and the last applied {@code EventOrder}, and every update is guarded (the "Version = EventOrder" pattern
 * of the event-store docs):
 * <ul>
 *     <li>{@link AccountOpened} of generation <i>g</i> sets every bucket absolutely, if <i>g</i> is newer than the row's;</li>
 *     <li>any other event applies its delta only for the row's generation and only above its last applied order.</li>
 * </ul>
 * A late event from an older generation is therefore ignored, which is right: the newer generation's opening balances
 * already include it.
 * <p>
 * Every handler write goes through {@code getRequiredUnitOfWork().handle()…}, the {@code UnitOfWork} a
 * {@link ViewEventProcessor} handles each event in, so the row commits together with the processor's progress by construction - not, as
 * with Spring's {@code JdbcTemplate}, only while the Spring wiring binds both to one connection. The
 * constructor's DDL and the reset run where none is active, so they go through
 * {@code getUnitOfWorkFactory().usingUnitOfWork(uow -> …)}, which opens one.
 */
@Service
public class AccountOverviewProjection extends TradingDemoViewEventProcessor {
    private static final BigDecimal ZERO = BigDecimal.ZERO;

    public AccountOverviewProjection(ViewEventProcessorDependencies dependencies) {
        super(dependencies);
        getUnitOfWorkFactory().usingUnitOfWork(uow -> uow.handle().execute("""
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
        getUnitOfWorkFactory().usingUnitOfWork(uow -> uow.handle().execute("CREATE INDEX IF NOT EXISTS projection_account_overview_owner ON projection_account_overview (owner_id)"));
    }

    @Override
    public String getProcessorName() {
        return "AccountOverviewProjection";
    }

    @Override
    protected List<AggregateType> reactsToEventsRelatedToAggregateTypes() {
        return List.of(AccountsAggregateTypes.ACCOUNTS);
    }

    @MessageHandler
    void handle(AccountOpened event, OrderedMessage message) {
        var opening = event.openingBalances();
        getRequiredUnitOfWork().handle().createUpdate("""
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
                                        .bind("lastEventOrder", message.getOrder())
                                        .bind("settled", value(opening.settledCash()))
                                        .bind("pendingIn", value(opening.pendingCashIn()))
                                        .bind("pendingOut", value(opening.pendingCashOut()))
                                        .bind("held", opening.holds().stream().map(hold -> value(hold.remaining())).reduce(ZERO, BigDecimal::add))
                                        .bind("uncleared", opening.unclearedDeposits().stream().map(deposit -> value(deposit.amount())).reduce(ZERO, BigDecimal::add))
                                        .execute();
    }

    @MessageHandler
    void handle(CashAdjusted event, OrderedMessage message) {
        delta(event, message, value(event.amount()), ZERO, ZERO, ZERO, ZERO, ZERO);
    }

    @MessageHandler
    void handle(DepositCredited event, OrderedMessage message) {
        delta(event, message, value(event.amount()), ZERO, ZERO, ZERO, value(event.amount()), ZERO);
    }

    @MessageHandler
    void handle(DepositCleared event, OrderedMessage message) {
        delta(event, message, ZERO, ZERO, ZERO, ZERO, value(event.amount()).negate(), ZERO);
    }

    @MessageHandler
    void handle(DepositReturned event, OrderedMessage message) {
        delta(event, message, value(event.amount()).negate(), ZERO, ZERO, ZERO, value(event.unclearedReleased()).negate(), ZERO);
    }

    @MessageHandler
    void handle(FundsHeld event, OrderedMessage message) {
        delta(event, message, ZERO, ZERO, ZERO, value(event.amount()), ZERO, ZERO);
    }

    @MessageHandler
    void handle(FundsReleased event, OrderedMessage message) {
        delta(event, message, ZERO, ZERO, ZERO, value(event.amount()).negate(), ZERO, ZERO);
    }

    @MessageHandler
    void handle(CashWithdrawn event, OrderedMessage message) {
        delta(event, message, value(event.amount()).negate(), ZERO, ZERO, value(event.holdConsumed()).negate(), ZERO, ZERO);
    }

    @MessageHandler
    void handle(SettlementAmountCorrected event, OrderedMessage message) {
        delta(event, message, value(event.cashChange()), ZERO, ZERO, ZERO, ZERO, value(event.realizedPnlChange()));
    }

    @MessageHandler
    void handle(WithdrawalReturnCredited event, OrderedMessage message) {
        delta(event, message, value(event.amount()), ZERO, ZERO, ZERO, ZERO, ZERO);
    }

    @MessageHandler
    void handle(TradeBooked event, OrderedMessage message) {
        var trade = event.trade();
        if (trade.side() == TradeSide.BUY) {
            delta(event, message, ZERO, ZERO, value(trade.grossAmount()), value(event.holdConsumed()).negate(), ZERO, ZERO);
        } else {
            delta(event, message, ZERO, value(trade.grossAmount()), ZERO, ZERO, ZERO, value(trade.realizedPnl()));
        }
    }

    @MessageHandler
    void handle(TradeSettled event, OrderedMessage message) {
        var gross = value(event.trade().grossAmount());
        if (event.trade().side() == TradeSide.BUY) {
            delta(event, message, gross.negate(), ZERO, gross.negate(), ZERO, ZERO, ZERO);
        } else {
            delta(event, message, gross, gross.negate(), ZERO, ZERO, ZERO, ZERO);
        }
    }

    @MessageHandler
    void handle(TradeBookingReversed event, OrderedMessage message) {
        var trade = event.trade();
        if (trade.side() == TradeSide.BUY) {
            delta(event, message, ZERO, ZERO, value(trade.grossAmount()).negate(), ZERO, ZERO, ZERO);
        } else {
            delta(event, message, ZERO, value(trade.grossAmount()).negate(), ZERO, ZERO, ZERO, value(trade.realizedPnl()).negate());
        }
    }

    @MessageHandler
    void handle(AccountBooksClosed event, OrderedMessage message) {
        delta(event, message, ZERO, ZERO, ZERO, ZERO, ZERO, ZERO);
    }

    @MessageHandler
    void handle(SharesReserved event, OrderedMessage message) {
        delta(event, message, ZERO, ZERO, ZERO, ZERO, ZERO, ZERO);
    }

    @MessageHandler
    void handle(SharesReleased event, OrderedMessage message) {
        delta(event, message, ZERO, ZERO, ZERO, ZERO, ZERO, ZERO);
    }

    private void delta(AccountEvent event, OrderedMessage message,
                       BigDecimal settled, BigDecimal pendingIn, BigDecimal pendingOut, BigDecimal held, BigDecimal uncleared, BigDecimal realizedPnl) {
        getRequiredUnitOfWork().handle().createUpdate("""
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
                                        .bind("eventOrder", message.getOrder())
                                        .bind("accountId", event.accountId().toString())
                                        .bind("generation", event.accountStreamId().generation())
                                        .execute();
    }

    private static BigDecimal value(Money money) {
        return money.getAmount().value();
    }

    @Override
    protected void onSubscriptionsReset(AggregateType aggregateType, GlobalEventOrder resubscribeFromAndIncluding) {
        getUnitOfWorkFactory().usingUnitOfWork(uow -> uow.handle().execute("TRUNCATE TABLE projection_account_overview"));
    }
}
