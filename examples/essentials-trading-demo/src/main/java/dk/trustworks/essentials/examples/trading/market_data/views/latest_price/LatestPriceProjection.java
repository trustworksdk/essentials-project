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
import dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.processor.EventProcessorDependencies;
import dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.processor.InTransactionEventProcessor;
import dk.trustworks.essentials.components.foundation.messaging.MessageHandler;
import dk.trustworks.essentials.components.foundation.messaging.queue.OrderedMessage;
import dk.trustworks.essentials.examples.trading.market_data.aggregates.InstrumentPrices;
import dk.trustworks.essentials.examples.trading.market_data.events.PriceInitialized;
import dk.trustworks.essentials.examples.trading.market_data.events.PriceUpdated;
import dk.trustworks.essentials.examples.trading.market_data.types.InstrumentId;
import dk.trustworks.essentials.types.Amount;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.util.List;

import static dk.trustworks.essentials.shared.FailFast.requireNonNull;

/**
 * Projects {@code InstrumentPriceEvent} into this slice's own read model, one row per instrument.
 *
 * <h2>Why in-transaction</h2>
 * The demo's bootstrap decides whether to seed by asking this slice whether a price already exists. An eventually
 * consistent answer could say "absent" on a restart against a populated database, and the bootstrap would seed a
 * second time on top of it. An {@link InTransactionEventProcessor} writes the row in the same transaction that
 * appends the price event, so the row exists exactly when the event does, and the slice can answer strongly
 * consistently without reading the {@code InstrumentPrice} aggregate. It is non-exclusive, so every node projects
 * the events it appends itself; an exclusive subscription would leave a node without the lock appending events its
 * own read model never sees.
 * <p>
 * Each row stores the {@code EventOrder} it was last written from, and a write never moves it backwards, so an event
 * projected twice cannot put an older price back.
 */
@Service
public class LatestPriceProjection extends InTransactionEventProcessor {
    private final JdbcTemplate jdbcTemplate;

    public LatestPriceProjection(EventProcessorDependencies eventProcessorDependencies,
                                 JdbcTemplate jdbcTemplate) {
        super(eventProcessorDependencies);
        this.jdbcTemplate = requireNonNull(jdbcTemplate, "No jdbcTemplate provided");
        this.jdbcTemplate.execute("""
                                          CREATE TABLE IF NOT EXISTS projection_latest_price (
                                              instrument_id VARCHAR PRIMARY KEY,
                                              latest_price NUMERIC(19, 4) NOT NULL,
                                              event_order BIGINT NOT NULL,
                                              updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP
                                          )
                                          """);
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
        jdbcTemplate.update("""
                                    INSERT INTO projection_latest_price (instrument_id, latest_price, event_order, updated_at)
                                    VALUES (?, ?, ?, CURRENT_TIMESTAMP)
                                    ON CONFLICT (instrument_id) DO UPDATE
                                    SET latest_price = EXCLUDED.latest_price,
                                        event_order = EXCLUDED.event_order,
                                        updated_at = CURRENT_TIMESTAMP
                                    WHERE projection_latest_price.event_order < EXCLUDED.event_order
                                    """,
                            instrumentId.toString(),
                            price.value(),
                            eventOrder);
    }
}
