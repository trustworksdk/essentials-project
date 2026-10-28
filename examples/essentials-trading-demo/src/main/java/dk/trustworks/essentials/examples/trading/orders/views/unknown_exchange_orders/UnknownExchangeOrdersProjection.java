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
package dk.trustworks.essentials.examples.trading.orders.views.unknown_exchange_orders;

import dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.eventstream.AggregateType;
import dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.processor.*;
import dk.trustworks.essentials.components.foundation.messaging.MessageHandler;
import dk.trustworks.essentials.examples.trading.orders.events.UnknownExchangeOrderFilled;
import dk.trustworks.essentials.examples.trading.orders.types.OrdersAggregateTypes;
import dk.trustworks.essentials.examples.trading.config.TradingDemoViewEventProcessor;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * Projects the {@code UnknownExchangeOrder} operator cases into {@code projection_unknown_exchange_fills}, one row per
 * execution, so an operator sees every fill nobody booked.
 * <p>
 * Every handler write goes through {@code getRequiredUnitOfWork().handle()…}, the {@code UnitOfWork} a
 * {@link ViewEventProcessor} handles each event in, so the row and the processor's progress commit together. The
 * constructor's DDL runs where none is active, so it goes through {@code getUnitOfWorkFactory().usingUnitOfWork(uow -> …)},
 * which opens one.
 */
@Service
public class UnknownExchangeOrdersProjection extends TradingDemoViewEventProcessor {
    public UnknownExchangeOrdersProjection(ViewEventProcessorDependencies dependencies) {
        super(dependencies);
        getUnitOfWorkFactory().usingUnitOfWork(uow -> uow.handle().execute("""
                                                                                   CREATE TABLE IF NOT EXISTS projection_unknown_exchange_fills (
                                                                                       execution_id VARCHAR PRIMARY KEY,
                                                                                       order_id VARCHAR NOT NULL,
                                                                                       quantity BIGINT NOT NULL,
                                                                                       price NUMERIC(24, 6) NOT NULL,
                                                                                       currency VARCHAR NOT NULL,
                                                                                       trade_date DATE NOT NULL,
                                                                                       settlement_date DATE NOT NULL,
                                                                                       recorded_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP
                                                                                   )
                                                                                   """));
    }

    @Override
    public String getProcessorName() {
        return "UnknownExchangeOrdersProjection";
    }

    @Override
    protected List<AggregateType> reactsToEventsRelatedToAggregateTypes() {
        return List.of(OrdersAggregateTypes.UNKNOWN_EXCHANGE_ORDERS);
    }

    @MessageHandler
    void handle(UnknownExchangeOrderFilled e) {
        getRequiredUnitOfWork().handle().createUpdate("""
                                                          INSERT INTO projection_unknown_exchange_fills (execution_id, order_id, quantity, price, currency, trade_date, settlement_date)
                                                          VALUES (:executionId, :orderId, :quantity, :price, :currency, :tradeDate, :settlementDate)
                                                          ON CONFLICT (execution_id) DO NOTHING
                                                          """)
                                        .bind("executionId", e.executionId().toString())
                                        .bind("orderId", e.orderId().toString())
                                        .bind("quantity", e.quantity().value())
                                        .bind("price", e.price().getAmount().value())
                                        .bind("currency", e.price().getCurrency().toString())
                                        .bind("tradeDate", e.tradeDate())
                                        .bind("settlementDate", e.settlementDate())
                                        .execute();
    }
}
