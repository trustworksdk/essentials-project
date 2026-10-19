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

package dk.trustworks.essentials.examples.trading.orders.aggregates;

import dk.trustworks.essentials.components.eventsourced.aggregates.EventHandler;
import dk.trustworks.essentials.components.eventsourced.aggregates.stateful.modern.AggregateRoot;
import dk.trustworks.essentials.examples.trading.accounts.types.*;
import dk.trustworks.essentials.examples.trading.market_data.types.InstrumentId;
import dk.trustworks.essentials.examples.trading.orders.events.*;
import dk.trustworks.essentials.examples.trading.orders.types.*;
import dk.trustworks.essentials.types.Money;

import java.time.LocalDate;
import java.util.*;

import static dk.trustworks.essentials.shared.FailFast.requireNonNull;

/**
 * One customer order and its life at the exchange (design {@code phase-3-orders-and-execution.md} §2).
 * <p>
 * Everything the exchange reports is a fact and applied in whatever order it arrives (D11): a fill before the
 * acceptance implies it, a fill after a cancel request is still booked, a repeated fill (same {@code executionId}) is a
 * no-op. The order closes — {@link OrderClosed} — exactly once, when it first reaches a terminal status, and that is
 * the release automation's trigger. Reached through {@link Orders}.
 */
public class Order extends AggregateRoot<OrderId, OrderEvent, Order> {
    private AccountId          accountId;
    private InstrumentId       instrumentId;
    private TradeSide          side;
    private Quantity           quantity;
    private Quantity           filledQuantity;
    private OrderStatus        status;
    private ExchangeOrderId    exchangeOrderId;
    private boolean            closed;
    private Set<ExecutionId>   executions = new HashSet<>();

    protected Order() {
    }

    /**
     * Used for rehydration
     */
    public Order(OrderId orderId) {
        super(orderId);
    }

    public Order(OrderId orderId,
                 AccountId accountId,
                 InstrumentId instrumentId,
                 TradeSide side,
                 OrderType type,
                 Quantity quantity,
                 Money limitPrice,
                 TimeInForce timeInForce,
                 Money holdAmount) {
        super(orderId);
        requireNonNull(quantity, "No quantity provided");
        if (!quantity.isPositive()) {
            throw new OrderRuleViolation("Quantity must be positive, was " + quantity);
        }
        if (type == OrderType.LIMIT && (limitPrice == null || limitPrice.signum() <= 0)) {
            throw new OrderRuleViolation("A limit order needs a positive limit price");
        }
        if (type == OrderType.MARKET && limitPrice != null) {
            throw new OrderRuleViolation("A market order takes no limit price");
        }
        apply(new OrderPlaced(orderId, accountId, instrumentId, side, type, quantity, limitPrice, timeInForce, holdAmount));
    }

    public void markSubmitted(ExchangeOrderId exchangeOrderId) {
        requireNonNull(exchangeOrderId, "No exchangeOrderId provided");
        if (this.exchangeOrderId != null) {
            return;
        }
        apply(new OrderSubmitted(aggregateId(), exchangeOrderId));
    }

    public void reject(String reason) {
        requireNonNull(reason, "No reason provided");
        if (status.isTerminal()) {
            return;
        }
        apply(new OrderRejected(aggregateId(), reason));
        close();
    }

    public void markAccepted() {
        if (status != OrderStatus.PENDING) {
            return;
        }
        apply(new OrderAccepted(aggregateId()));
    }

    /**
     * A fact from the exchange: never refused, never applied twice. Implies acceptance when it arrives first.
     */
    public void recordFill(ExecutionId executionId, Quantity fillQuantity, Money price, LocalDate tradeDate, LocalDate settlementDate) {
        requireNonNull(executionId, "No executionId provided");
        requireNonNull(fillQuantity, "No fillQuantity provided");
        if (executions.contains(executionId)) {
            return;
        }
        markAccepted();
        var total = filledQuantity.plus(fillQuantity);
        apply(new OrderFilled(aggregateId(), accountId, instrumentId, side, executionId, fillQuantity, price, tradeDate, settlementDate, total));
        if (status == OrderStatus.FILLED) {
            close();
        }
    }

    public void requestCancel(AccountId accountId) {
        requireNonNull(accountId, "No accountId provided");
        if (!accountId.equals(this.accountId)) {
            throw new OrderRuleViolation("Order '" + aggregateId() + "' does not belong to account '" + accountId + "'");
        }
        if (status.isTerminal()) {
            throw new OrderRuleViolation("Order '" + aggregateId() + "' is already " + status);
        }
        if (status == OrderStatus.CANCEL_PENDING) {
            return;
        }
        apply(new OrderCancelRequested(aggregateId()));
    }

    public void markCancelled(String reason) {
        requireNonNull(reason, "No reason provided");
        if (status.isTerminal()) {
            return;
        }
        apply(new OrderCancelled(aggregateId(), reason));
        close();
    }

    private void close() {
        if (closed) {
            return;
        }
        apply(new OrderClosed(aggregateId(), accountId, instrumentId, side, status, filledQuantity));
    }

    @EventHandler
    private void on(OrderPlaced e) {
        accountId = e.accountId();
        instrumentId = e.instrumentId();
        side = e.side();
        quantity = e.quantity();
        filledQuantity = Quantity.ZERO;
        status = OrderStatus.PENDING;
        executions = new HashSet<>();
    }

    @EventHandler
    private void on(OrderSubmitted e) {
        exchangeOrderId = e.exchangeOrderId();
    }

    @EventHandler
    private void on(OrderRejected e) {
        status = OrderStatus.REJECTED;
    }

    @EventHandler
    private void on(OrderAccepted e) {
        status = OrderStatus.OPEN;
    }

    @EventHandler
    private void on(OrderFilled e) {
        executions.add(e.executionId());
        filledQuantity = e.filledQuantity();
        if (status.isTerminal()) {
            // A late fact on a closed order (Q-3): recorded and booked, but the order stays what it was
            return;
        }
        if (!quantity.isGreaterThan(filledQuantity)) {
            status = OrderStatus.FILLED;
        } else if (status != OrderStatus.CANCEL_PENDING) {
            // A pending cancel stays pending: the exchange decides what is left
            status = OrderStatus.PARTIALLY_FILLED;
        }
    }

    @EventHandler
    private void on(OrderCancelRequested e) {
        status = OrderStatus.CANCEL_PENDING;
    }

    @EventHandler
    private void on(OrderCancelled e) {
        status = OrderStatus.CANCELLED;
    }

    @EventHandler
    private void on(OrderClosed e) {
        closed = true;
    }

    /**
     * Package-private, for {@link Orders#exchangeStateOf}.
     */
    ExchangeOrderId exchangeOrderId() {
        return exchangeOrderId;
    }

    OrderStatus status() {
        return status;
    }
}
