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
import dk.trustworks.essentials.examples.trading.market_data.aggregates.Instruments;
import dk.trustworks.essentials.examples.trading.market_data.events.InstrumentRegistered;
import dk.trustworks.essentials.examples.trading.market_data.events.InstrumentRenamed;
import dk.trustworks.essentials.examples.trading.market_data.events.InstrumentSuspended;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.util.List;

import static dk.trustworks.essentials.shared.FailFast.requireNonNull;

/**
 * Projects {@code InstrumentEvent} into this slice's own read model.
 * <p>
 * Unlike {@code views/latest_price}, this one projects rather than reading the aggregate: nothing needs an
 * instrument's reference data <em>strongly</em> consistently, and the aggregate exposes no accessors — which is the
 * normal arrangement, and why the price slice's exception is documented so loudly.
 */
@Service
public class InstrumentDetailsProjection extends ViewEventProcessor {
    private final JdbcTemplate jdbcTemplate;

    public InstrumentDetailsProjection(ViewEventProcessorDependencies dependencies,
                                       JdbcTemplate jdbcTemplate) {
        super(dependencies);
        this.jdbcTemplate = requireNonNull(jdbcTemplate, "No jdbcTemplate provided");
        this.jdbcTemplate.execute("""
                                          CREATE TABLE IF NOT EXISTS projection_instrument_details (
                                              instrument_id VARCHAR PRIMARY KEY,
                                              symbol VARCHAR NOT NULL,
                                              display_name VARCHAR NOT NULL,
                                              suspended BOOLEAN NOT NULL DEFAULT FALSE,
                                              suspension_reason VARCHAR NULL,
                                              updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP
                                          )
                                          """);
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
        jdbcTemplate.update("""
                                    INSERT INTO projection_instrument_details (
                                        instrument_id, symbol, display_name, suspended, suspension_reason, updated_at
                                    )
                                    VALUES (?, ?, ?, false, NULL, CURRENT_TIMESTAMP)
                                    ON CONFLICT (instrument_id) DO UPDATE
                                    SET symbol = EXCLUDED.symbol,
                                        display_name = EXCLUDED.display_name,
                                        updated_at = CURRENT_TIMESTAMP
                                    """,
                            event.instrumentId().toString(),
                            event.symbol().toString(),
                            event.displayName());
    }

    @MessageHandler
    void handle(InstrumentRenamed event) {
        jdbcTemplate.update("""
                                    UPDATE projection_instrument_details
                                    SET display_name = ?,
                                        updated_at = CURRENT_TIMESTAMP
                                    WHERE instrument_id = ?
                                    """,
                            event.displayName(),
                            event.instrumentId().toString());
    }

    @MessageHandler
    void handle(InstrumentSuspended event) {
        jdbcTemplate.update("""
                                    UPDATE projection_instrument_details
                                    SET suspended = true,
                                        suspension_reason = ?,
                                        updated_at = CURRENT_TIMESTAMP
                                    WHERE instrument_id = ?
                                    """,
                            event.reason(),
                            event.instrumentId().toString());
    }

    @Override
    protected void onSubscriptionsReset(AggregateType aggregateType,
                                        GlobalEventOrder resubscribeFromAndIncluding) {
        jdbcTemplate.execute("TRUNCATE TABLE projection_instrument_details");
    }
}
