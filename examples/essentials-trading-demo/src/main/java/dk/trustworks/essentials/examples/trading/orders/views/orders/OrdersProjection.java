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

import dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.transaction.*;
import dk.trustworks.essentials.examples.trading.config.TradingDemoSchema;
import dk.trustworks.essentials.examples.trading.orders.events.*;
import dk.trustworks.essentials.examples.trading.orders.types.*;
import org.jdbi.v3.core.Handle;
import org.springframework.stereotype.Service;

import static dk.trustworks.essentials.shared.FailFast.requireNonNull;

/**
 * Projects orders into {@code projection_orders}, one row per order. Every event sets absolute values, guarded by the
 * row's last applied {@code EventOrder}, so a redelivered or late event never moves the status back.
 * <p>
 * Engine-neutral: the tables, the event logic and the queries, on either event store engine. What feeds it is the
 * engine's processor - {@link OrdersClassicView} or {@link OrdersEventLogView} - which hands each event here with
 * the handle of the transaction it handles the event in and the event's {@code EventOrder} in its stream, so the row
 * commits together with that processor's progress. The constructor's DDL opens a unit of work of its own.
 */
@Service
public class OrdersProjection {
    private final EventStoreUnitOfWorkFactory<? extends EventStoreUnitOfWork> unitOfWorkFactory;

    public OrdersProjection(EventStoreUnitOfWorkFactory<? extends EventStoreUnitOfWork> unitOfWorkFactory) {
        this.unitOfWorkFactory = requireNonNull(unitOfWorkFactory, "No unitOfWorkFactory provided");
        unitOfWorkFactory.usingUnitOfWork(uow -> TradingDemoSchema.ddl(uow.handle(), """
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
                                                                              """));
        // Added after the table first shipped; a demo database from before keeps working
        unitOfWorkFactory.usingUnitOfWork(uow -> TradingDemoSchema.ddl(uow.handle(), "ALTER TABLE projection_orders ADD COLUMN IF NOT EXISTS average_fill_price NUMERIC(24, 6)"));
        unitOfWorkFactory.usingUnitOfWork(uow -> TradingDemoSchema.ddl(uow.handle(), "CREATE INDEX IF NOT EXISTS projection_orders_account ON projection_orders (account_id, placed_at)"));
    }

    void on(Handle handle, OrderPlaced e, long eventOrder) {
        handle.createUpdate("""
                                INSERT INTO projection_orders (order_id, account_id, instrument_id, side, type, quantity, limit_price, currency,
                                                               time_in_force, status, filled_quantity, last_event_order)
                                VALUES (:orderId, :accountId, :instrumentId, :side, :type, :quantity, :limitPrice, :currency, :timeInForce, :status, 0, :lastEventOrder)
                                ON CONFLICT (order_id) DO NOTHING
                                """)
              .bind("orderId", e.orderId().toString())
              .bind("accountId", e.accountId().toString())
              .bind("instrumentId", e.instrumentId().toString())
              .bind("side", e.side().name())
              .bind("type", e.type().name())
              .bind("quantity", e.quantity().value())
              .bind("limitPrice", e.limitPrice() == null ? null : e.limitPrice().getAmount().value())
              .bind("currency", e.limitPrice() == null ? null : e.limitPrice().getCurrency().toString())
              .bind("timeInForce", e.timeInForce().name())
              .bind("status", OrderStatus.PENDING.name())
              .bind("lastEventOrder", eventOrder)
              .execute();
    }

    void on(Handle handle, OrderSubmitted e, long eventOrder) {
        update(handle, e, eventOrder, "exchange_order_id = ?", e.exchangeOrderId().toString());
    }

    void on(Handle handle, OrderAccepted e, long eventOrder) {
        update(handle, e, eventOrder, "status = ?", OrderStatus.OPEN.name());
    }

    void on(Handle handle, OrderRejected e, long eventOrder) {
        update(handle, e, eventOrder, "status = ?, reason = ?", OrderStatus.REJECTED.name(), e.reason());
    }

    void on(Handle handle, OrderFilled e, long eventOrder) {
        update(handle, e, eventOrder, "filled_quantity = ?, average_fill_price = ?, currency = ?",
               e.filledQuantity().value(), e.averagePrice().getAmount().value(), e.averagePrice().getCurrency().toString());
    }

    void on(Handle handle, OrderCancelRequested e, long eventOrder) {
        update(handle, e, eventOrder, "status = ?", OrderStatus.CANCEL_PENDING.name());
    }

    void on(Handle handle, OrderCancelled e, long eventOrder) {
        update(handle, e, eventOrder, "status = ?, reason = ?", OrderStatus.CANCELLED.name(), e.reason());
    }

    void on(Handle handle, OrderExpiryRequested e, long eventOrder) {
        update(handle, e, eventOrder, "status = ?", OrderStatus.CANCEL_PENDING.name());
    }

    void on(Handle handle, OrderExpired e, long eventOrder) {
        update(handle, e, eventOrder, "status = ?, reason = ?", OrderStatus.EXPIRED.name(), "END_OF_DAY " + e.businessDate());
    }

    /**
     * The final status: also what a fill that completes the order resolves to.
     */
    void on(Handle handle, OrderClosed e, long eventOrder) {
        update(handle, e, eventOrder, "status = ?, filled_quantity = ?", e.finalStatus().name(), e.filledQuantity().value());
    }

    private void update(Handle handle, OrderEvent e, long eventOrder, String set, Object... values) {
        var args = new Object[values.length + 3];
        System.arraycopy(values, 0, args, 0, values.length);
        args[values.length] = eventOrder;
        args[values.length + 1] = e.orderId().toString();
        args[values.length + 2] = eventOrder;
        var update = handle.createUpdate("UPDATE projection_orders SET " + set + ", last_event_order = ?, updated_at = CURRENT_TIMESTAMP " +
                                         "WHERE order_id = ? AND last_event_order < ?");
        for (var i = 0; i < args.length; i++) {
            update.bind(i, args[i]);
        }
        update.execute();
    }
}
