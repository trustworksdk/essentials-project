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

package dk.trustworks.essentials.examples.trading.orders.use_cases.place_order;

import dk.trustworks.essentials.examples.trading.accounts.types.*;
import dk.trustworks.essentials.examples.trading.accounts.use_cases.hold_funds.HoldFunds;
import dk.trustworks.essentials.examples.trading.accounts.use_cases.reserve_shares.ReserveShares;
import dk.trustworks.essentials.examples.trading.market_data.types.MarketQuotes;
import dk.trustworks.essentials.examples.trading.orders.aggregates.*;
import dk.trustworks.essentials.examples.trading.orders.types.*;
import dk.trustworks.essentials.reactive.command.*;
import dk.trustworks.essentials.types.Money;
import org.springframework.stereotype.Service;

import java.math.*;

import static dk.trustworks.essentials.shared.FailFast.requireNonNull;

/**
 * The single decision component of {@code orders.place_order}: the order and its hold are one decision.
 * <p>
 * The order is saved and the account's hold (buy) or reservation (sell) is requested by sending the ledger's own
 * command on the bus, which joins this unit of work. A {@code LedgerRuleViolation} — not enough buying power, not
 * enough shares — therefore rolls the order back with it, and nothing reaches the exchange. Two orders racing for the
 * same buying power both append to the account's stream at the same event order; one wins, the other's unit of work
 * fails with an optimistic-append conflict and is retried by the caller against the new balance. That is the account
 * lock (design {@code phase-3-orders-and-execution.md}, "Why no account lock is needed").
 * <p>
 * The hold's id is the order's id, so the booking and release automations never need a mapping.
 * <p>
 * A market buy is sized on {@code market_data}'s current best ask, read through its published seam
 * {@link MarketQuotes}; without a fresh quote it is refused rather than held on a guess.
 */
@Service
public class PlaceOrderHandler extends AnnotatedCommandHandler {
    private final Orders               orders;
    private final CommandBus           commandBus;
    private final MarketQuotes         marketQuotes;
    private final PlaceOrderProperties properties;

    public PlaceOrderHandler(Orders orders, CommandBus commandBus, MarketQuotes marketQuotes, PlaceOrderProperties properties) {
        this.orders = requireNonNull(orders, "No orders provided");
        this.commandBus = requireNonNull(commandBus, "No commandBus provided");
        this.marketQuotes = requireNonNull(marketQuotes, "No marketQuotes provided");
        this.properties = requireNonNull(properties, "No properties provided");
    }

    @CmdHandler
    public void handle(PlaceOrder cmd) {
        requireNonNull(cmd, "No cmd provided");
        if (orders.findOrder(cmd.orderId()).isPresent()) {
            return;
        }
        var holdAmount = cmd.side() == TradeSide.BUY ? holdFor(cmd) : null;
        orders.placeNewOrder(new Order(cmd.orderId(), cmd.accountId(), cmd.instrumentId(), cmd.side(), cmd.type(), cmd.quantity(),
                                       cmd.limitPrice(), cmd.timeInForce(), holdAmount));
        if (cmd.side() == TradeSide.BUY) {
            commandBus.send(new HoldFunds(cmd.accountId(), HoldId.of(cmd.orderId()), HoldPurpose.ORDER, holdAmount));
        } else {
            commandBus.send(new ReserveShares(cmd.accountId(), ReservationId.of(cmd.orderId()), cmd.instrumentId(), cmd.quantity()));
        }
    }

    /**
     * The most the order can cost. Limit: quantity x limit price. Market: quantity x best ask x (1 + collar), rounded up
     * to the cent. No fees yet.
     */
    private Money holdFor(PlaceOrder cmd) {
        var quantity = BigDecimal.valueOf(cmd.quantity().value());
        if (cmd.type() == OrderType.LIMIT) {
            if (cmd.limitPrice() == null) {
                throw new OrderRuleViolation("A limit order needs a positive limit price");
            }
            return Money.of(cmd.limitPrice().getAmount().value().multiply(quantity), cmd.limitPrice().getCurrency());
        }
        var ask = marketQuotes.bestAsk(cmd.instrumentId())
                              .orElseThrow(() -> new OrderRuleViolation("No current price for '" + cmd.instrumentId() + "'; place a limit order"));
        var amount = ask.getAmount().value()
                        .multiply(quantity)
                        .multiply(BigDecimal.ONE.add(properties.getMarketOrderCollar()))
                        .setScale(2, RoundingMode.UP);
        return Money.of(amount, ask.getCurrency());
    }
}
