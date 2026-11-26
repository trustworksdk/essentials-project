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

package dk.trustworks.essentials.examples.trading.settlement.views.fills;

import dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.transaction.*;
import dk.trustworks.essentials.examples.trading.orders.events.*;
import dk.trustworks.essentials.examples.trading.accounts.types.*;
import dk.trustworks.essentials.examples.trading.config.TradingDemoSchema;
import dk.trustworks.essentials.examples.trading.market_data.types.InstrumentId;
import org.jdbi.v3.core.Handle;
import org.springframework.stereotype.Service;

import java.util.*;

import static dk.trustworks.essentials.shared.FailFast.requireNonNull;

/**
 * {@code settlement}'s own record of which account, instrument and side each execution belongs to, projected from
 * {@code orders}' {@link OrderFilled} - a cross-BC {@code events/} import, so settlement never calls into
 * {@code orders}. Eventually consistent: a settlement report for a fill not projected yet is redelivered.
 * <p>
 * Engine-neutral: the tables, the event logic and the queries, on either event store engine. What feeds it is the
 * engine's processor - {@link SettlementFillsClassicView} or {@link SettlementFillsEventLogView} - which hands each event here with
 * the handle of the transaction it handles the event in, so the row commits together with that processor's progress.
 * The constructor's DDL and {@link #fill(ExecutionId)} open (or join) a unit of work of their own.
 */
@Service
public class SettlementFillsProjection {
    private final EventStoreUnitOfWorkFactory<? extends EventStoreUnitOfWork> unitOfWorkFactory;


    public record Fill(ExecutionId executionId, AccountId accountId, InstrumentId instrumentId, TradeSide side) {
    }

    public SettlementFillsProjection(EventStoreUnitOfWorkFactory<? extends EventStoreUnitOfWork> unitOfWorkFactory) {
        this.unitOfWorkFactory = requireNonNull(unitOfWorkFactory, "No unitOfWorkFactory provided");
        unitOfWorkFactory.usingUnitOfWork(uow -> TradingDemoSchema.ddl(uow.handle(), """
                                                                              CREATE TABLE IF NOT EXISTS projection_settlement_fill (
                                                                                  execution_id VARCHAR PRIMARY KEY,
                                                                                  account_id VARCHAR NOT NULL,
                                                                                  instrument_id VARCHAR NOT NULL,
                                                                                  side VARCHAR NOT NULL
                                                                              )
                                                                              """));
    }

    void on(Handle handle, OrderFilled e) {
        handle.createUpdate("INSERT INTO projection_settlement_fill (execution_id, account_id, instrument_id, side) VALUES (:executionId, :accountId, :instrumentId, :side) ON CONFLICT (execution_id) DO NOTHING")
              .bind("executionId", e.executionId().toString())
              .bind("accountId", e.accountId().toString())
              .bind("instrumentId", e.instrumentId().toString())
              .bind("side", e.side().name())
              .execute();
    }

    public Optional<Fill> fill(ExecutionId executionId) {
        requireNonNull(executionId, "No executionId provided");
        return unitOfWorkFactory.withUnitOfWork(uow -> uow.handle()
                                                          .createQuery("SELECT account_id, instrument_id, side FROM projection_settlement_fill WHERE execution_id = :executionId")
                                                          .bind("executionId", executionId.toString())
                                                          .map((rs, ctx) -> new Fill(executionId, AccountId.of(rs.getString(1)), InstrumentId.of(rs.getString(2)), TradeSide.valueOf(rs.getString(3))))
                                                          .findFirst());
    }
}
