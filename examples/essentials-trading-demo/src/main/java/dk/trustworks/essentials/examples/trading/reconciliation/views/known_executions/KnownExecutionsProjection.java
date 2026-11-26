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

package dk.trustworks.essentials.examples.trading.reconciliation.views.known_executions;

import dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.transaction.*;
import dk.trustworks.essentials.examples.trading.orders.events.*;
import dk.trustworks.essentials.examples.trading.config.TradingDemoSchema;
import org.jdbi.v3.core.Handle;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.sql.Date;

import static dk.trustworks.essentials.shared.FailFast.requireNonNull;

/**
 * {@code reconciliation}'s own record of the brokerage's executions, projected from {@code orders}' {@link OrderFilled}
 * - a cross-BC {@code events/} import: instrument, side, quantity, amount and trade date per execution. What a clearing
 * statement is checked against.
 * <p>
 * Engine-neutral: the tables, the event logic and the queries, on either event store engine. What feeds it is the
 * engine's processor - {@link KnownExecutionsClassicView} or {@link KnownExecutionsEventLogView} - which hands each event here with
 * the handle of the transaction it handles the event in, so the row commits together with that processor's progress.
 * The constructor's DDL opens a unit of work of its own.
 */
@Service
public class KnownExecutionsProjection {
    private final EventStoreUnitOfWorkFactory<? extends EventStoreUnitOfWork> unitOfWorkFactory;

    public KnownExecutionsProjection(EventStoreUnitOfWorkFactory<? extends EventStoreUnitOfWork> unitOfWorkFactory) {
        this.unitOfWorkFactory = requireNonNull(unitOfWorkFactory, "No unitOfWorkFactory provided");
        unitOfWorkFactory.usingUnitOfWork(uow -> TradingDemoSchema.ddl(uow.handle(), """
                                                                              CREATE TABLE IF NOT EXISTS projection_reconciliation_execution (
                                                                                  execution_id VARCHAR PRIMARY KEY,
                                                                                  instrument_id VARCHAR NOT NULL,
                                                                                  side VARCHAR NOT NULL,
                                                                                  quantity BIGINT NOT NULL,
                                                                                  amount NUMERIC(24, 6) NOT NULL,
                                                                                  trade_date DATE NOT NULL
                                                                              )
                                                                              """));
        unitOfWorkFactory.usingUnitOfWork(uow -> TradingDemoSchema.ddl(uow.handle(), "CREATE INDEX IF NOT EXISTS projection_reconciliation_execution_trade_date ON projection_reconciliation_execution (trade_date)"));
    }

    void on(Handle handle, OrderFilled e) {
        handle.createUpdate("""
                                INSERT INTO projection_reconciliation_execution (execution_id, instrument_id, side, quantity, amount, trade_date)
                                VALUES (:executionId, :instrumentId, :side, :quantity, :amount, :tradeDate) ON CONFLICT (execution_id) DO NOTHING
                                """)
              .bind("executionId", e.executionId().toString())
              .bind("instrumentId", e.instrumentId().toString())
              .bind("side", e.side().name())
              .bind("quantity", e.quantity().value())
              .bind("amount", e.price().getAmount().value().multiply(BigDecimal.valueOf(e.quantity().value())))
              .bind("tradeDate", Date.valueOf(e.tradeDate()))
              .execute();
    }
}
