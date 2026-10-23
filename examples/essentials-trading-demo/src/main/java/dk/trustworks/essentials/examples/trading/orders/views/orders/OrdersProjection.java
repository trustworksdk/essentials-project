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

package dk.trustworks.essentials.examples.trading.orders.views.orders;

import dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.eventstream.AggregateType;
import dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.processor.*;
import dk.trustworks.essentials.components.foundation.messaging.MessageHandler;
import dk.trustworks.essentials.components.foundation.messaging.queue.OrderedMessage;
import dk.trustworks.essentials.examples.trading.orders.events.*;
import dk.trustworks.essentials.examples.trading.orders.types.*;
import dk.trustworks.essentials.examples.trading.config.TradingDemoViewEventProcessor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.util.List;

import static dk.trustworks.essentials.shared.FailFast.requireNonNull;

/**
 * Projects orders into {@code projection_orders}, one row per order. Every event sets absolute values, guarded by the
 * row's last applied {@code EventOrder}, so a redelivered or late event never moves the status back.
 */
@Service
public class OrdersProjection extends TradingDemoViewEventProcessor {
    private final JdbcTemplate jdbcTemplate;

    public OrdersProjection(ViewEventProcessorDependencies dependencies, JdbcTemplate jdbcTemplate) {
        super(dependencies);
        this.jdbcTemplate = requireNonNull(jdbcTemplate, "No jdbcTemplate provided");
        this.jdbcTemplate.execute("""
                                          CREATE TABLE IF NOT EXISTS projection_orders (
                                              order_id VARCHAR PRIMARY KEY,
                                              account_id VARCHAR NOT NULL,
                                              instrument_id VARCHAR NOT NULL,
                                              side VARCHAR NOT NULL,
                                              type VARCHAR NOT NULL,
                                              quantity BIGINT NOT NULL,
                                              limit_price NUMERIC(24, 6),
                                              currency VARCHAR,
                                              time_in_force VARCHAR NOT NULL,
                                              status VARCHAR NOT NULL,
                                              filled_quantity BIGINT NOT NULL,
                                              average_fill_price NUMERIC(24, 6),
                                              exchange_order_id VARCHAR,
                                              reason VARCHAR,
                                              last_event_order BIGINT NOT NULL,
                                              placed_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
                                              updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP
                                          )
                                          """);
        // Added after the table first shipped; a demo database from before keeps working
        this.jdbcTemplate.execute("ALTER TABLE projection_orders ADD COLUMN IF NOT EXISTS average_fill_price NUMERIC(24, 6)");
        this.jdbcTemplate.execute("CREATE INDEX IF NOT EXISTS projection_orders_account ON projection_orders (account_id, placed_at)");
    }

    @Override
    public String getProcessorName() {
        return "OrdersProjection";
    }

    @Override
    protected List<AggregateType> reactsToEventsRelatedToAggregateTypes() {
        return List.of(OrdersAggregateTypes.ORDERS);
    }

    @MessageHandler
    void handle(OrderPlaced e, OrderedMessage message) {
        jdbcTemplate.update("""
                                    INSERT INTO projection_orders (order_id, account_id, instrument_id, side, type, quantity, limit_price, currency,
                                                                   time_in_force, status, filled_quantity, last_event_order)
                                    VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 0, ?)
                                    ON CONFLICT (order_id) DO NOTHING
                                    """,
                            e.orderId().toString(),
                            e.accountId().toString(),
                            e.instrumentId().toString(),
                            e.side().name(),
                            e.type().name(),
                            e.quantity().value(),
                            e.limitPrice() == null ? null : e.limitPrice().getAmount().value(),
                            e.limitPrice() == null ? null : e.limitPrice().getCurrency().toString(),
                            e.timeInForce().name(),
                            OrderStatus.PENDING.name(),
                            message.getOrder());
    }

    @MessageHandler
    void handle(OrderSubmitted e, OrderedMessage message) {
        update(e, message, "exchange_order_id = ?", e.exchangeOrderId().toString());
    }

    @MessageHandler
    void handle(OrderAccepted e, OrderedMessage message) {
        update(e, message, "status = ?", OrderStatus.OPEN.name());
    }

    @MessageHandler
    void handle(OrderRejected e, OrderedMessage message) {
        update(e, message, "status = ?, reason = ?", OrderStatus.REJECTED.name(), e.reason());
    }

    @MessageHandler
    void handle(OrderFilled e, OrderedMessage message) {
        update(e, message, "filled_quantity = ?, average_fill_price = ?, currency = ?",
               e.filledQuantity().value(), e.averagePrice().getAmount().value(), e.averagePrice().getCurrency().toString());
    }

    @MessageHandler
    void handle(OrderCancelRequested e, OrderedMessage message) {
        update(e, message, "status = ?", OrderStatus.CANCEL_PENDING.name());
    }

    @MessageHandler
    void handle(OrderCancelled e, OrderedMessage message) {
        update(e, message, "status = ?, reason = ?", OrderStatus.CANCELLED.name(), e.reason());
    }

    @MessageHandler
    void handle(OrderExpiryRequested e, OrderedMessage message) {
        update(e, message, "status = ?", OrderStatus.CANCEL_PENDING.name());
    }

    @MessageHandler
    void handle(OrderExpired e, OrderedMessage message) {
        update(e, message, "status = ?, reason = ?", OrderStatus.EXPIRED.name(), "END_OF_DAY " + e.businessDate());
    }

    /**
     * The final status: also what a fill that completes the order resolves to.
     */
    @MessageHandler
    void handle(OrderClosed e, OrderedMessage message) {
        update(e, message, "status = ?, filled_quantity = ?", e.finalStatus().name(), e.filledQuantity().value());
    }

    private void update(OrderEvent e, OrderedMessage message, String set, Object... values) {
        var args = new Object[values.length + 3];
        System.arraycopy(values, 0, args, 0, values.length);
        args[values.length] = message.getOrder();
        args[values.length + 1] = e.orderId().toString();
        args[values.length + 2] = message.getOrder();
        jdbcTemplate.update("UPDATE projection_orders SET " + set + ", last_event_order = ?, updated_at = CURRENT_TIMESTAMP " +
                            "WHERE order_id = ? AND last_event_order < ?", args);
    }
}
