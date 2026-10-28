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

package dk.trustworks.essentials.examples.trading.market_data.views.latest_price;

import dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.eventstream.AggregateType;
import dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.processor.ViewEventProcessor;
import dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.processor.ViewEventProcessorDependencies;
import dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.types.GlobalEventOrder;
import dk.trustworks.essentials.components.foundation.messaging.MessageHandler;
import dk.trustworks.essentials.components.foundation.messaging.queue.OrderedMessage;
import dk.trustworks.essentials.examples.trading.market_data.aggregates.InstrumentPrices;
import dk.trustworks.essentials.examples.trading.market_data.events.PriceInitialized;
import dk.trustworks.essentials.examples.trading.market_data.events.PriceUpdated;
import dk.trustworks.essentials.examples.trading.market_data.types.InstrumentId;
import dk.trustworks.essentials.types.Amount;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * Projects {@code InstrumentPriceEvent} into this slice's own read model, one row per instrument.
 * <p>
 * A {@link ViewEventProcessor}, so the model is eventually consistent and can be rebuilt: a new subscription starts at
 * the first price event, so a database that has price events and no rows backfills on its own, and a subscription
 * reset truncates the table and replays it. Nothing reads this model strongly consistently. The bootstrap's "is the
 * demo already seeded?" probe asks the {@code InstrumentPrices} repository instead, because a lagging view could answer
 * "absent" on a restart against a populated database.
 * <p>
 * Each row stores the {@code EventOrder} it was last written from, and a write never moves it backwards, so an event
 * redelivered from the processor's retry queue cannot put an older price back.
 * <p>
 * The write goes through {@code getRequiredUnitOfWork().handle()…}, the {@code UnitOfWork} a {@link ViewEventProcessor}
 * handles each event in, so the row and the processor's progress commit together. The constructor's DDL and the reset
 * run where none is active, so they go through {@code getUnitOfWorkFactory().usingUnitOfWork(uow -> …)}, which opens
 * one.
 */
@Service
public class LatestPriceProjection extends ViewEventProcessor {
    public LatestPriceProjection(ViewEventProcessorDependencies dependencies) {
        super(dependencies);
        getUnitOfWorkFactory().usingUnitOfWork(uow -> uow.handle().execute("""
                                                                               CREATE TABLE IF NOT EXISTS projection_latest_price (
                                                                                   instrument_id VARCHAR PRIMARY KEY,
                                                                                   latest_price NUMERIC(19, 4) NOT NULL,
                                                                                   event_order BIGINT NOT NULL,
                                                                                   updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP
                                                                               )
                                                                               """));
    }

    @Override
    public String getProcessorName() {
        return "LatestPriceProjection";
    }

    @Override
    protected List<AggregateType> reactsToEventsRelatedToAggregateTypes() {
        return List.of(InstrumentPrices.AGGREGATE_TYPE);
    }

    @MessageHandler
    void handle(PriceInitialized event, OrderedMessage message) {
        project(event.instrumentId(), event.price(), message.getOrder());
    }

    @MessageHandler
    void handle(PriceUpdated event, OrderedMessage message) {
        project(event.instrumentId(), event.price(), message.getOrder());
    }

    private void project(InstrumentId instrumentId, Amount price, long eventOrder) {
        getRequiredUnitOfWork().handle().createUpdate("""
                                                          INSERT INTO projection_latest_price (instrument_id, latest_price, event_order, updated_at)
                                                          VALUES (:instrumentId, :latestPrice, :eventOrder, CURRENT_TIMESTAMP)
                                                          ON CONFLICT (instrument_id) DO UPDATE
                                                          SET latest_price = EXCLUDED.latest_price,
                                                              event_order = EXCLUDED.event_order,
                                                              updated_at = CURRENT_TIMESTAMP
                                                          WHERE projection_latest_price.event_order < EXCLUDED.event_order
                                                          """)
                                        .bind("instrumentId", instrumentId.toString())
                                        .bind("latestPrice", price.value())
                                        .bind("eventOrder", eventOrder)
                                        .execute();
    }

    @Override
    protected void onSubscriptionsReset(AggregateType aggregateType,
                                        GlobalEventOrder resubscribeFromAndIncluding) {
        getUnitOfWorkFactory().usingUnitOfWork(uow -> uow.handle().execute("TRUNCATE TABLE projection_latest_price"));
    }
}
