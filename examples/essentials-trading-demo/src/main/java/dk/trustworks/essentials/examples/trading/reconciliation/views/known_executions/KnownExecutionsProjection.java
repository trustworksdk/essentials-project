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

import dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.eventstream.AggregateType;
import dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.processor.*;
import dk.trustworks.essentials.components.foundation.messaging.MessageHandler;
import dk.trustworks.essentials.examples.trading.orders.events.OrderFilled;
import dk.trustworks.essentials.examples.trading.orders.types.OrdersAggregateTypes;
import dk.trustworks.essentials.examples.trading.config.TradingDemoViewEventProcessor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.sql.Date;
import java.util.List;

import static dk.trustworks.essentials.shared.FailFast.requireNonNull;

/**
 * {@code reconciliation}'s own record of the brokerage's executions, projected from {@code orders}' {@link OrderFilled}
 * - a cross-BC {@code events/} import: instrument, side, quantity, amount and trade date per execution. What a clearing
 * statement is checked against.
 */
@Service
public class KnownExecutionsProjection extends TradingDemoViewEventProcessor {
    private final JdbcTemplate jdbcTemplate;

    public KnownExecutionsProjection(ViewEventProcessorDependencies dependencies, JdbcTemplate jdbcTemplate) {
        super(dependencies);
        this.jdbcTemplate = requireNonNull(jdbcTemplate, "No jdbcTemplate provided");
        this.jdbcTemplate.execute("""
                                          CREATE TABLE IF NOT EXISTS projection_reconciliation_execution (
                                              execution_id VARCHAR PRIMARY KEY,
                                              instrument_id VARCHAR NOT NULL,
                                              side VARCHAR NOT NULL,
                                              quantity BIGINT NOT NULL,
                                              amount NUMERIC(24, 6) NOT NULL,
                                              trade_date DATE NOT NULL
                                          )
                                          """);
        this.jdbcTemplate.execute("CREATE INDEX IF NOT EXISTS projection_reconciliation_execution_trade_date ON projection_reconciliation_execution (trade_date)");
    }

    @Override
    public String getProcessorName() {
        return "ReconciliationKnownExecutionsProjection";
    }

    @Override
    protected List<AggregateType> reactsToEventsRelatedToAggregateTypes() {
        return List.of(OrdersAggregateTypes.ORDERS);
    }

    @MessageHandler
    void handle(OrderFilled e) {
        jdbcTemplate.update("""
                                    INSERT INTO projection_reconciliation_execution (execution_id, instrument_id, side, quantity, amount, trade_date)
                                    VALUES (?, ?, ?, ?, ?, ?) ON CONFLICT (execution_id) DO NOTHING
                                    """,
                            e.executionId().toString(), e.instrumentId().toString(), e.side().name(), e.quantity().value(),
                            e.price().getAmount().value().multiply(BigDecimal.valueOf(e.quantity().value())), Date.valueOf(e.tradeDate()));
    }
}
