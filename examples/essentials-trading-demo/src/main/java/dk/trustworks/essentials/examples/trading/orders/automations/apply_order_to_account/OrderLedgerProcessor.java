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

package dk.trustworks.essentials.examples.trading.orders.automations.apply_order_to_account;

import dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.eventstream.AggregateType;
import dk.trustworks.essentials.reactive.command.CommandBus;
import dk.trustworks.essentials.examples.trading.accounts.types.*;
import dk.trustworks.essentials.examples.trading.accounts.use_cases.book_trade.BookTrade;
import dk.trustworks.essentials.examples.trading.accounts.use_cases.release_hold.ReleaseHold;
import dk.trustworks.essentials.examples.trading.accounts.use_cases.release_shares.ReleaseShares;
import dk.trustworks.essentials.examples.trading.orders.aggregates.Orders;
import dk.trustworks.essentials.examples.trading.orders.events.*;
import org.springframework.stereotype.Service;

import java.util.List;

import static dk.trustworks.essentials.shared.FailFast.requireNonNull;

/**
 * Carries an order's outcome to its account: every fill is booked ({@code BookTrade}, consuming the order's hold or
 * reservation), and when the order closes what is left of the hold or reservation is released.
 * <p>
 * Both reactions live in one processor on purpose. It delivers one order's events in order, so the release can never
 * overtake a booking of the same order — which would hand the hold back as buying power and then book the trade against
 * cash instead.
 * <p>
 * The ledger's commands are idempotent ({@code executionId}, hold and reservation ids), so a redelivered event books or
 * releases nothing twice. A collision on the account's stream is redelivered by the processor.
 * <p>
 * Engine-neutral: the handler bodies, fed by {@link OrderLedgerClassicReaction} on the classic engine and
 * {@link OrderLedgerEventLogReaction} on the event-log engine under the processor name {@value #PROCESSOR_NAME}. The
 * wrappers declare each handler's {@code UnitOfWorkMode}, so a body runs in the unit of work its wrapper opens.
 */
@Service
public class OrderLedgerProcessor {
    static final String              PROCESSOR_NAME  = "OrderLedgerProcessor";
    static final List<AggregateType> AGGREGATE_TYPES = List.of(Orders.AGGREGATE_TYPE);

    private final CommandBus commandBus;

    public OrderLedgerProcessor(CommandBus commandBus) {
        this.commandBus = requireNonNull(commandBus, "No commandBus provided");
    }

    void on(OrderFilled e) {
        var buy = e.side() == TradeSide.BUY;
        commandBus.send(new BookTrade(e.accountId(),
                                           e.executionId(),
                                           e.instrumentId(),
                                           e.side(),
                                           e.quantity(),
                                           e.price(),
                                           e.tradeDate(),
                                           e.settlementDate(),
                                           buy ? HoldId.of(e.orderId()) : null,
                                           buy ? null : ReservationId.of(e.orderId())));
    }

    void on(OrderClosed e) {
        if (e.side() == TradeSide.BUY) {
            commandBus.send(new ReleaseHold(e.accountId(), HoldId.of(e.orderId())));
        } else {
            commandBus.send(new ReleaseShares(e.accountId(), ReservationId.of(e.orderId())));
        }
    }
}
