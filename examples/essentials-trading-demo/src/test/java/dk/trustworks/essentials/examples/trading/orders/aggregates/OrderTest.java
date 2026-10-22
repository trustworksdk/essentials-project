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

import dk.trustworks.essentials.examples.trading.accounts.types.*;
import dk.trustworks.essentials.examples.trading.market_data.types.InstrumentId;
import dk.trustworks.essentials.examples.trading.orders.events.*;
import dk.trustworks.essentials.examples.trading.orders.types.*;
import dk.trustworks.essentials.types.*;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;

import static org.assertj.core.api.Assertions.*;

/**
 * The order's life, on the aggregate alone (design {@code docs/design/phase-3-orders-and-execution.md} §2).
 */
class OrderTest {
    private static final OrderId    ORDER      = OrderId.of("ORD-1");
    private static final AccountId  ACCOUNT    = AccountId.of("ACC-1");
    private static final LocalDate  TRADE_DATE = LocalDate.of(2030, 1, 7);
    private static final LocalDate  SETTLES_ON = TRADE_DATE.plusDays(1);

    private static Money usd(String amount) {
        return Money.of(amount, CurrencyCode.USD);
    }

    private static Order placeBuy(long quantity) {
        return new Order(ORDER, ACCOUNT, InstrumentId.of("ACME"), TradeSide.BUY, OrderType.LIMIT, Quantity.of(quantity), usd("101"),
                         TimeInForce.DAY, usd(String.valueOf(101 * quantity)));
    }

    private static void fill(Order order, String executionId, long quantity) {
        fill(order, executionId, quantity, "100.50");
    }

    private static void fill(Order order, String executionId, long quantity, String price) {
        order.recordFill(ExecutionId.of(executionId), Quantity.of(quantity), usd(price), TRADE_DATE, SETTLES_ON);
    }

    @Test
    void a_fill_before_the_acceptance_implies_it_and_a_complete_fill_closes_the_order_once() {
        var order = placeBuy(10);
        order.markSubmitted(ExchangeOrderId.of("XO-1"));

        fill(order, "EX-1", 4);
        fill(order, "EX-1", 4); // redelivered: nothing
        fill(order, "EX-2", 6);
        order.markAccepted();   // late: nothing
        order.markCancelled("REQUESTED"); // too late: nothing

        var events = order.getUncommittedChanges().events;
        assertThat(events).extracting(e -> e.getClass().getSimpleName())
                          .containsExactly("OrderPlaced", "OrderSubmitted", "OrderAccepted", "OrderFilled", "OrderFilled", "OrderClosed");
        assertThat(((OrderFilled) events.get(4)).filledQuantity()).isEqualTo(Quantity.of(10));
        var closed = (OrderClosed) events.get(5);
        assertThat(closed.finalStatus()).isEqualTo(OrderStatus.FILLED);
        assertThat(closed.filledQuantity()).isEqualTo(Quantity.of(10));
    }

    @Test
    void a_fill_after_a_cancel_request_is_still_recorded_and_the_cancellation_closes_with_what_was_filled() {
        var order = placeBuy(10);
        order.markAccepted();
        order.requestCancel(ACCOUNT);
        order.requestCancel(ACCOUNT); // repeated: nothing
        fill(order, "EX-1", 3);
        order.markCancelled("REQUESTED");
        order.markCancelled("REQUESTED"); // repeated: nothing

        var events = order.getUncommittedChanges().events;
        assertThat(events).extracting(e -> e.getClass().getSimpleName())
                          .containsExactly("OrderPlaced", "OrderAccepted", "OrderCancelRequested", "OrderFilled", "OrderCancelled", "OrderClosed");
        var closed = (OrderClosed) events.getLast();
        assertThat(closed.finalStatus()).isEqualTo(OrderStatus.CANCELLED);
        assertThat(closed.filledQuantity()).isEqualTo(Quantity.of(3));
    }

    @Test
    void a_rejection_closes_the_order_and_a_closed_order_cannot_be_cancelled() {
        var order = placeBuy(10);
        order.reject("INSTRUMENT_NOT_TRADABLE");
        order.reject("INSTRUMENT_NOT_TRADABLE");

        assertThat(order.getUncommittedChanges().events).extracting(e -> e.getClass().getSimpleName())
                                                        .containsExactly("OrderPlaced", "OrderRejected", "OrderClosed");
        assertThatThrownBy(() -> order.requestCancel(ACCOUNT)).isInstanceOf(OrderRuleViolation.class);
    }

    @Test
    void a_fill_after_a_rejection_is_recorded_and_flagged_but_the_order_stays_rejected_and_closes_only_once() {
        var order = placeBuy(10);
        order.reject("EXCHANGE_UNAVAILABLE");
        fill(order, "EX-1", 10);
        fill(order, "EX-1", 10); // redelivered: nothing, not flagged twice

        var events = order.getUncommittedChanges().events;
        assertThat(events).extracting(e -> e.getClass().getSimpleName())
                          .containsExactly("OrderPlaced", "OrderRejected", "OrderClosed", "OrderFilled", "OrderFilledAfterClose");
        var flagged = (OrderFilledAfterClose) events.getLast();
        assertThat(flagged.closedAs()).isEqualTo(OrderStatus.REJECTED);
        assertThat(flagged.quantity()).isEqualTo(Quantity.of(10));
        assertThatThrownBy(() -> order.requestCancel(ACCOUNT)).hasMessageContaining("REJECTED");
    }

    @Test
    void a_fill_beyond_the_order_quantity_is_flagged_and_the_completing_fill_is_not() {
        var order = placeBuy(10);
        fill(order, "EX-1", 10);
        fill(order, "EX-2", 2);

        var events = order.getUncommittedChanges().events;
        assertThat(events).extracting(e -> e.getClass().getSimpleName())
                          .containsExactly("OrderPlaced", "OrderAccepted", "OrderFilled", "OrderClosed", "OrderFilled", "OrderFilledAfterClose");
        assertThat(((OrderFilledAfterClose) events.getLast()).closedAs()).isEqualTo(OrderStatus.FILLED);
    }

    @Test
    void every_fill_carries_the_quantity_weighted_average_price_of_the_fills_so_far() {
        var order = placeBuy(10);
        fill(order, "EX-1", 4, "100.50");
        fill(order, "EX-2", 3, "101.00");
        fill(order, "EX-2", 3, "101.00"); // redelivered: does not count twice
        fill(order, "EX-3", 3, "100.00");

        var fills = order.getUncommittedChanges().events.stream().filter(OrderFilled.class::isInstance).map(OrderFilled.class::cast).toList();
        assertThat(fills).extracting(f -> f.averagePrice().getAmount().value())
                         .usingElementComparator(BigDecimal::compareTo)
                         // 402 / 4; (402 + 303) / 7 = 100.714285714...; (705 + 300) / 10
                         .containsExactly(new BigDecimal("100.50"), new BigDecimal("100.714286"), new BigDecimal("100.50"));
        assertThat((Object) fills.getLast().averagePrice().getCurrency()).isEqualTo(CurrencyCode.USD);
    }

    @Test
    void a_day_order_expires_when_the_exchange_confirms_the_end_of_day_cancellation_and_keeps_what_was_filled() {
        var order = placeBuy(10);
        order.markAccepted();
        fill(order, "EX-1", 3);
        order.expire(TRADE_DATE);
        order.expire(TRADE_DATE);        // repeated: nothing
        order.requestCancel(ACCOUNT);    // already on its way off the market: nothing
        order.markCancelled("REQUESTED");

        var events = order.getUncommittedChanges().events;
        assertThat(events).extracting(e -> e.getClass().getSimpleName())
                          .containsExactly("OrderPlaced", "OrderAccepted", "OrderFilled", "OrderExpiryRequested", "OrderExpired", "OrderClosed");
        assertThat(((OrderExpired) events.get(4)).businessDate()).isEqualTo(TRADE_DATE);
        var closed = (OrderClosed) events.getLast();
        assertThat(closed.finalStatus()).isEqualTo(OrderStatus.EXPIRED);
        assertThat(closed.filledQuantity()).isEqualTo(Quantity.of(3));
    }

    @Test
    void a_day_order_that_fills_before_the_exchange_cancels_it_is_filled_not_expired() {
        var order = placeBuy(10);
        order.markAccepted();
        order.expire(TRADE_DATE);
        fill(order, "EX-1", 10);
        order.markCancelled("REQUESTED"); // too late: nothing

        var events = order.getUncommittedChanges().events;
        assertThat(events).extracting(e -> e.getClass().getSimpleName())
                          .containsExactly("OrderPlaced", "OrderAccepted", "OrderExpiryRequested", "OrderFilled", "OrderClosed");
        assertThat(((OrderClosed) events.getLast()).finalStatus()).isEqualTo(OrderStatus.FILLED);
    }

    @Test
    void the_exchanges_expiry_closes_the_order_expired_with_what_was_filled_and_only_once() {
        var order = placeBuy(10);
        order.markAccepted();
        fill(order, "EX-1", 4);
        order.markExpired(TRADE_DATE);
        order.markExpired(TRADE_DATE);    // a redelivered report: nothing
        order.markCancelled("REQUESTED"); // closed already: nothing

        var events = order.getUncommittedChanges().events;
        assertThat(events).extracting(e -> e.getClass().getSimpleName())
                          .containsExactly("OrderPlaced", "OrderAccepted", "OrderFilled", "OrderExpired", "OrderClosed");
        assertThat(((OrderExpired) events.get(3)).businessDate()).isEqualTo(TRADE_DATE);
        var closed = (OrderClosed) events.getLast();
        assertThat(closed.finalStatus()).isEqualTo(OrderStatus.EXPIRED);
        assertThat(closed.filledQuantity()).isEqualTo(Quantity.of(4));
    }

    @Test
    void a_filled_order_is_not_expired() {
        var order = placeBuy(10);
        fill(order, "EX-1", 10);
        order.markExpired(TRADE_DATE);

        assertThat(order.getUncommittedChanges().events).noneMatch(OrderExpired.class::isInstance);
    }

    @Test
    void only_an_open_day_order_expires() {
        var gtc = new Order(ORDER, ACCOUNT, InstrumentId.of("ACME"), TradeSide.BUY, OrderType.LIMIT, Quantity.of(10), usd("101"),
                            TimeInForce.GTC, usd("1010"));
        gtc.markAccepted();
        gtc.expire(TRADE_DATE);

        var cancelling = placeBuy(10);
        cancelling.requestCancel(ACCOUNT);
        cancelling.expire(TRADE_DATE);
        cancelling.markCancelled("REQUESTED"); // the customer's cancel, not an expiry

        var filled = placeBuy(10);
        fill(filled, "EX-1", 10);
        filled.expire(TRADE_DATE);

        assertThat(gtc.getUncommittedChanges().events).extracting(e -> e.getClass().getSimpleName())
                                                      .containsExactly("OrderPlaced", "OrderAccepted");
        assertThat(cancelling.getUncommittedChanges().events).extracting(e -> e.getClass().getSimpleName())
                                                             .containsExactly("OrderPlaced", "OrderCancelRequested", "OrderCancelled", "OrderClosed");
        assertThat(filled.getUncommittedChanges().events).extracting(e -> e.getClass().getSimpleName())
                                                         .containsExactly("OrderPlaced", "OrderAccepted", "OrderFilled", "OrderClosed");
    }

    @Test
    void an_order_is_cancelled_only_through_its_own_account() {
        var order = placeBuy(10);
        assertThatThrownBy(() -> order.requestCancel(AccountId.of("ACC-OTHER"))).isInstanceOf(OrderRuleViolation.class);
    }

    @Test
    void a_limit_order_needs_a_positive_quantity_and_limit_price() {
        assertThatThrownBy(() -> new Order(ORDER, ACCOUNT, InstrumentId.of("ACME"), TradeSide.BUY, OrderType.LIMIT, Quantity.of(0), usd("1"),
                                           TimeInForce.DAY, usd("0")))
                .isInstanceOf(OrderRuleViolation.class);
        assertThatThrownBy(() -> new Order(ORDER, ACCOUNT, InstrumentId.of("ACME"), TradeSide.BUY, OrderType.LIMIT, Quantity.of(1), null,
                                           TimeInForce.DAY, usd("0")))
                .isInstanceOf(OrderRuleViolation.class);
    }
}
