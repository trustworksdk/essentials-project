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

import dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.eventstream.AggregateType;
import dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.processor.*;
import dk.trustworks.essentials.components.foundation.messaging.MessageHandler;
import dk.trustworks.essentials.examples.trading.accounts.types.*;
import dk.trustworks.essentials.examples.trading.market_data.types.InstrumentId;
import dk.trustworks.essentials.examples.trading.orders.events.OrderFilled;
import dk.trustworks.essentials.examples.trading.orders.types.OrdersAggregateTypes;
import dk.trustworks.essentials.examples.trading.config.TradingDemoViewEventProcessor;
import org.springframework.stereotype.Service;

import java.util.*;

import static dk.trustworks.essentials.shared.FailFast.requireNonNull;

/**
 * {@code settlement}'s own record of which account, instrument and side each execution belongs to, projected from
 * {@code orders}' {@link OrderFilled} - a cross-BC {@code events/} import, so settlement never calls into
 * {@code orders}. Eventually consistent: a settlement report for a fill not projected yet is redelivered.
 * <p>
 * Every handler write goes through {@code getRequiredUnitOfWork().handle()…}, the {@code UnitOfWork} a
 * {@link ViewEventProcessor} handles each event in, so the row commits together with the processor's progress by construction - not, as
 * with Spring's {@code JdbcTemplate}, only while the Spring wiring binds both to one connection. The
 * constructor's DDL runs where none is active, so it goes through {@code getUnitOfWorkFactory().usingUnitOfWork(uow -> …)},
 * which opens one; {@link #fill(ExecutionId)} reads through {@code getUnitOfWorkFactory().withUnitOfWork(uow -> …)}, which
 * joins the caller's unit of work or opens one.
 */
@Service
public class SettlementFillsProjection extends TradingDemoViewEventProcessor {

    public record Fill(ExecutionId executionId, AccountId accountId, InstrumentId instrumentId, TradeSide side) {
    }

    public SettlementFillsProjection(ViewEventProcessorDependencies dependencies) {
        super(dependencies);
        getUnitOfWorkFactory().usingUnitOfWork(uow -> uow.handle().execute("""
                                                                                   CREATE TABLE IF NOT EXISTS projection_settlement_fill (
                                                                                       execution_id VARCHAR PRIMARY KEY,
                                                                                       account_id VARCHAR NOT NULL,
                                                                                       instrument_id VARCHAR NOT NULL,
                                                                                       side VARCHAR NOT NULL
                                                                                   )
                                                                                   """));
    }

    @Override
    public String getProcessorName() {
        return "SettlementFillsProjection";
    }

    @Override
    protected List<AggregateType> reactsToEventsRelatedToAggregateTypes() {
        return List.of(OrdersAggregateTypes.ORDERS);
    }

    @MessageHandler
    void handle(OrderFilled e) {
        getRequiredUnitOfWork().handle()
                                .createUpdate("INSERT INTO projection_settlement_fill (execution_id, account_id, instrument_id, side) VALUES (:executionId, :accountId, :instrumentId, :side) ON CONFLICT (execution_id) DO NOTHING")
                                .bind("executionId", e.executionId().toString())
                                .bind("accountId", e.accountId().toString())
                                .bind("instrumentId", e.instrumentId().toString())
                                .bind("side", e.side().name())
                                .execute();
    }

    public Optional<Fill> fill(ExecutionId executionId) {
        requireNonNull(executionId, "No executionId provided");
        return getUnitOfWorkFactory().withUnitOfWork(uow -> uow.handle()
                                                               .createQuery("SELECT account_id, instrument_id, side FROM projection_settlement_fill WHERE execution_id = :executionId")
                                                               .bind("executionId", executionId.toString())
                                                               .map((rs, ctx) -> new Fill(executionId, AccountId.of(rs.getString(1)), InstrumentId.of(rs.getString(2)), TradeSide.valueOf(rs.getString(3))))
                                                               .findFirst());
    }
}
