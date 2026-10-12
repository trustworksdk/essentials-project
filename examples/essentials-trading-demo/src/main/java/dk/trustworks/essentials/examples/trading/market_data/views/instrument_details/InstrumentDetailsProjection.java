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

import dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.eventstream.AggregateType;
import dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.processor.ViewEventProcessor;
import dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.processor.ViewEventProcessorDependencies;
import dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.types.GlobalEventOrder;
import dk.trustworks.essentials.components.foundation.messaging.MessageHandler;
import dk.trustworks.essentials.components.foundation.transaction.jdbi.HandleAwareUnitOfWork;
import dk.trustworks.essentials.components.foundation.transaction.jdbi.HandleAwareUnitOfWorkFactory;
import dk.trustworks.essentials.examples.trading.market_data.aggregates.Instruments;
import dk.trustworks.essentials.examples.trading.market_data.events.InstrumentRegistered;
import dk.trustworks.essentials.examples.trading.market_data.events.InstrumentRenamed;
import dk.trustworks.essentials.examples.trading.market_data.events.InstrumentRiskApproved;
import dk.trustworks.essentials.examples.trading.market_data.events.InstrumentRiskRejected;
import dk.trustworks.essentials.examples.trading.market_data.events.InstrumentSuspended;
import org.springframework.stereotype.Service;

import java.util.List;

import static dk.trustworks.essentials.shared.FailFast.requireNonNull;

/**
 * Projects {@code InstrumentEvent} into this slice's own read model.
 * <p>
 * A {@link ViewEventProcessor}, so the model is eventually consistent and can be rebuilt by replay: nothing needs an
 * instrument's reference data <em>strongly</em> consistently. Like {@code views/latest_price}, it never reads the
 * aggregate.
 * <p>
 * Every write goes through {@code unitOfWorkFactory.usingUnitOfWork(uow -> uow.handle()…)}, which joins the
 * {@code UnitOfWork} a {@link ViewEventProcessor} handles each event in, so the row and the processor's progress commit
 * together. Where none is active (the DDL in the constructor) the same call opens one.
 */
@Service
public class InstrumentDetailsProjection extends ViewEventProcessor {
    private final HandleAwareUnitOfWorkFactory<? extends HandleAwareUnitOfWork> unitOfWorkFactory;

    public InstrumentDetailsProjection(ViewEventProcessorDependencies dependencies,
                                       HandleAwareUnitOfWorkFactory<? extends HandleAwareUnitOfWork> unitOfWorkFactory) {
        super(dependencies);
        this.unitOfWorkFactory = requireNonNull(unitOfWorkFactory, "No unitOfWorkFactory provided");
        unitOfWorkFactory.usingUnitOfWork(uow -> uow.handle().execute("""
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
        unitOfWorkFactory.usingUnitOfWork(uow -> uow.handle().execute("ALTER TABLE projection_instrument_details ADD COLUMN IF NOT EXISTS risk_status VARCHAR NOT NULL DEFAULT 'PENDING'"));
        unitOfWorkFactory.usingUnitOfWork(uow -> uow.handle().execute("ALTER TABLE projection_instrument_details ADD COLUMN IF NOT EXISTS risk_detail VARCHAR NULL"));
    }

    @Override
    public String getProcessorName() {
        return "InstrumentDetailsProjection";
    }

    @Override
    protected List<AggregateType> reactsToEventsRelatedToAggregateTypes() {
        return List.of(Instruments.AGGREGATE_TYPE);
    }

    @MessageHandler
    void handle(InstrumentRegistered event) {
        /*
         * The upsert deliberately leaves risk_status alone on conflict. An instrument is registered once, but the
         * event can be redelivered — and by then the risk automation may already have recorded its decision, which a
         * blanket reset to PENDING would erase.
         */
        unitOfWorkFactory.usingUnitOfWork(uow -> uow.handle().createUpdate("""
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
                                                             .execute());
    }

    @MessageHandler
    void handle(InstrumentRenamed event) {
        unitOfWorkFactory.usingUnitOfWork(uow -> uow.handle().createUpdate("""
                                                                               UPDATE projection_instrument_details
                                                                               SET display_name = :displayName,
                                                                                   updated_at = CURRENT_TIMESTAMP
                                                                               WHERE instrument_id = :instrumentId
                                                                               """)
                                                             .bind("displayName", event.displayName())
                                                             .bind("instrumentId", event.instrumentId().toString())
                                                             .execute());
    }

    @MessageHandler
    void handle(InstrumentSuspended event) {
        unitOfWorkFactory.usingUnitOfWork(uow -> uow.handle().createUpdate("""
                                                                               UPDATE projection_instrument_details
                                                                               SET suspended = true,
                                                                                   suspension_reason = :suspensionReason,
                                                                                   updated_at = CURRENT_TIMESTAMP
                                                                               WHERE instrument_id = :instrumentId
                                                                               """)
                                                             .bind("suspensionReason", event.reason())
                                                             .bind("instrumentId", event.instrumentId().toString())
                                                             .execute());
    }

    @MessageHandler
    void handle(InstrumentRiskApproved event) {
        unitOfWorkFactory.usingUnitOfWork(uow -> uow.handle().createUpdate("""
                                                                               UPDATE projection_instrument_details
                                                                               SET risk_status = 'APPROVED',
                                                                                   risk_detail = :riskDetail,
                                                                                   updated_at = CURRENT_TIMESTAMP
                                                                               WHERE instrument_id = :instrumentId
                                                                               """)
                                                             .bind("riskDetail", event.riskRating().toString())
                                                             .bind("instrumentId", event.instrumentId().toString())
                                                             .execute());
    }

    @MessageHandler
    void handle(InstrumentRiskRejected event) {
        unitOfWorkFactory.usingUnitOfWork(uow -> uow.handle().createUpdate("""
                                                                               UPDATE projection_instrument_details
                                                                               SET risk_status = 'REJECTED',
                                                                                   risk_detail = :riskDetail,
                                                                                   updated_at = CURRENT_TIMESTAMP
                                                                               WHERE instrument_id = :instrumentId
                                                                               """)
                                                             .bind("riskDetail", event.reason())
                                                             .bind("instrumentId", event.instrumentId().toString())
                                                             .execute());
    }

    @Override
    protected void onSubscriptionsReset(AggregateType aggregateType,
                                        GlobalEventOrder resubscribeFromAndIncluding) {
        unitOfWorkFactory.usingUnitOfWork(uow -> uow.handle().execute("TRUNCATE TABLE projection_instrument_details"));
    }
}
