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

package dk.trustworks.essentials.examples.trading.market_data.views.instrument_details;

import dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.transaction.*;
import dk.trustworks.essentials.examples.trading.config.TradingDemoSchema;
import dk.trustworks.essentials.examples.trading.market_data.events.InstrumentRegistered;
import dk.trustworks.essentials.examples.trading.market_data.events.InstrumentRenamed;
import dk.trustworks.essentials.examples.trading.market_data.events.InstrumentRiskApproved;
import dk.trustworks.essentials.examples.trading.market_data.events.InstrumentRiskRejected;
import dk.trustworks.essentials.examples.trading.market_data.events.InstrumentSuspended;
import org.jdbi.v3.core.Handle;
import org.springframework.stereotype.Service;


import static dk.trustworks.essentials.shared.FailFast.requireNonNull;

/**
 * Projects {@code InstrumentEvent} into this slice's own read model.
 * <p>
 * Fed by a view processor, so the model is eventually consistent and can be rebuilt by replay: nothing needs an
 * instrument's reference data <em>strongly</em> consistently. It never reads the aggregate.
 * <p>
 * Engine-neutral: the tables, the event logic and the queries, on either event store engine. What feeds it is the
 * engine's processor - {@link InstrumentDetailsClassicView} or {@link InstrumentDetailsEventLogView} - which hands each event here with
 * the handle of the transaction it handles the event in, so the row commits together with that processor's progress.
 * The constructor's DDL and {@link #truncate()}, which the classic processor calls when its subscriptions are reset,
 * open a unit of work of their own.
 */
@Service
public class InstrumentDetailsProjection {
    private final EventStoreUnitOfWorkFactory<? extends EventStoreUnitOfWork> unitOfWorkFactory;

    public InstrumentDetailsProjection(EventStoreUnitOfWorkFactory<? extends EventStoreUnitOfWork> unitOfWorkFactory) {
        this.unitOfWorkFactory = requireNonNull(unitOfWorkFactory, "No unitOfWorkFactory provided");
        unitOfWorkFactory.usingUnitOfWork(uow -> TradingDemoSchema.ddl(uow.handle(), """
                                                                          CREATE TABLE IF NOT EXISTS projection_instrument_details (
                                                                              instrument_id VARCHAR PRIMARY KEY,
                                                                              symbol VARCHAR NOT NULL,
                                                                              display_name VARCHAR NOT NULL,
                                                                              suspended BOOLEAN NOT NULL DEFAULT FALSE,
                                                                              suspension_reason VARCHAR NULL,
                                                                              risk_status VARCHAR NOT NULL DEFAULT 'PENDING',
                                                                              risk_detail VARCHAR NULL,
                                                                              updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP
                                                                          )
                                                                          """));
        // The two risk columns arrived after the table did, and CREATE TABLE IF NOT EXISTS is a no-op against a demo
        // database that already has it. Both statements are idempotent, so they cost one round trip at startup and
        // spare anyone with an existing demo database an "column risk_status does not exist" on the first query.
        unitOfWorkFactory.usingUnitOfWork(uow -> TradingDemoSchema.ddl(uow.handle(), "ALTER TABLE projection_instrument_details ADD COLUMN IF NOT EXISTS risk_status VARCHAR NOT NULL DEFAULT 'PENDING'"));
        unitOfWorkFactory.usingUnitOfWork(uow -> TradingDemoSchema.ddl(uow.handle(), "ALTER TABLE projection_instrument_details ADD COLUMN IF NOT EXISTS risk_detail VARCHAR NULL"));
    }

    void on(Handle handle, InstrumentRegistered event) {
        /*
         * The upsert deliberately leaves risk_status alone on conflict. An instrument is registered once, but the
         * event can be redelivered — and by then the risk automation may already have recorded its decision, which a
         * blanket reset to PENDING would erase.
         */
        handle.createUpdate("""
                                INSERT INTO projection_instrument_details (
                                    instrument_id, symbol, display_name, suspended, suspension_reason,
                                    risk_status, risk_detail, updated_at
                                )
                                VALUES (:instrumentId, :symbol, :displayName, false, NULL, 'PENDING', NULL, CURRENT_TIMESTAMP)
                                ON CONFLICT (instrument_id) DO UPDATE
                                SET symbol = EXCLUDED.symbol,
                                    display_name = EXCLUDED.display_name,
                                    updated_at = CURRENT_TIMESTAMP
                                """)
              .bind("instrumentId", event.instrumentId().toString())
              .bind("symbol", event.symbol().toString())
              .bind("displayName", event.displayName())
              .execute();
    }

    void on(Handle handle, InstrumentRenamed event) {
        handle.createUpdate("""
                                UPDATE projection_instrument_details
                                SET display_name = :displayName,
                                    updated_at = CURRENT_TIMESTAMP
                                WHERE instrument_id = :instrumentId
                                """)
              .bind("displayName", event.displayName())
              .bind("instrumentId", event.instrumentId().toString())
              .execute();
    }

    void on(Handle handle, InstrumentSuspended event) {
        handle.createUpdate("""
                                UPDATE projection_instrument_details
                                SET suspended = true,
                                    suspension_reason = :suspensionReason,
                                    updated_at = CURRENT_TIMESTAMP
                                WHERE instrument_id = :instrumentId
                                """)
              .bind("suspensionReason", event.reason())
              .bind("instrumentId", event.instrumentId().toString())
              .execute();
    }

    void on(Handle handle, InstrumentRiskApproved event) {
        handle.createUpdate("""
                                UPDATE projection_instrument_details
                                SET risk_status = 'APPROVED',
                                    risk_detail = :riskDetail,
                                    updated_at = CURRENT_TIMESTAMP
                                WHERE instrument_id = :instrumentId
                                """)
              .bind("riskDetail", event.riskRating().toString())
              .bind("instrumentId", event.instrumentId().toString())
              .execute();
    }

    void on(Handle handle, InstrumentRiskRejected event) {
        handle.createUpdate("""
                                UPDATE projection_instrument_details
                                SET risk_status = 'REJECTED',
                                    risk_detail = :riskDetail,
                                    updated_at = CURRENT_TIMESTAMP
                                WHERE instrument_id = :instrumentId
                                """)
              .bind("riskDetail", event.reason())
              .bind("instrumentId", event.instrumentId().toString())
              .execute();
    }

    /**
     * Empties the view, for a processor whose subscriptions are reset to replay it from the start.
     */
    void truncate() {
        unitOfWorkFactory.usingUnitOfWork(uow -> uow.handle().execute("TRUNCATE TABLE projection_instrument_details"));
    }

}
