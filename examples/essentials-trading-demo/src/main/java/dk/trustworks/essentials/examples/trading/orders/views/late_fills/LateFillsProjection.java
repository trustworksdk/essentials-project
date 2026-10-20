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
package dk.trustworks.essentials.examples.trading.orders.views.late_fills;

import dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.eventstream.AggregateType;
import dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.processor.*;
import dk.trustworks.essentials.components.foundation.messaging.MessageHandler;
import dk.trustworks.essentials.examples.trading.orders.events.OrderFilledAfterClose;
import dk.trustworks.essentials.examples.trading.orders.types.OrdersAggregateTypes;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.util.List;

import static dk.trustworks.essentials.shared.FailFast.requireNonNull;

/**
 * Projects the late-fill operator cases (design Q-3) into {@code projection_late_fills}, one row per execution.
 */
@Service
public class LateFillsProjection extends ViewEventProcessor {
    private final JdbcTemplate jdbcTemplate;

    public LateFillsProjection(ViewEventProcessorDependencies dependencies, JdbcTemplate jdbcTemplate) {
        super(dependencies);
        this.jdbcTemplate = requireNonNull(jdbcTemplate, "No jdbcTemplate provided");
        this.jdbcTemplate.execute("""
                                          CREATE TABLE IF NOT EXISTS projection_late_fills (
                                              execution_id VARCHAR PRIMARY KEY,
                                              order_id VARCHAR NOT NULL,
                                              account_id VARCHAR NOT NULL,
                                              instrument_id VARCHAR NOT NULL,
                                              side VARCHAR NOT NULL,
                                              quantity BIGINT NOT NULL,
                                              price NUMERIC(24, 6) NOT NULL,
                                              currency VARCHAR NOT NULL,
                                              closed_as VARCHAR NOT NULL,
                                              recorded_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP
                                          )
                                          """);
    }

    @Override
    public String getProcessorName() {
        return "LateFillsProjection";
    }

    @Override
    protected List<AggregateType> reactsToEventsRelatedToAggregateTypes() {
        return List.of(OrdersAggregateTypes.ORDERS);
    }

    @MessageHandler
    void handle(OrderFilledAfterClose e) {
        jdbcTemplate.update("""
                                    INSERT INTO projection_late_fills (execution_id, order_id, account_id, instrument_id, side, quantity, price, currency, closed_as)
                                    VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
                                    ON CONFLICT (execution_id) DO NOTHING
                                    """,
                            e.executionId().toString(),
                            e.orderId().toString(),
                            e.accountId().toString(),
                            e.instrumentId().toString(),
                            e.side().name(),
                            e.quantity().value(),
                            e.price().getAmount().value(),
                            e.price().getCurrency().toString(),
                            e.closedAs().name());
    }
}
