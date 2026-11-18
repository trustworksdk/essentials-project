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

package dk.trustworks.essentials.examples.trading.settlement.views.settlements;

import dk.trustworks.essentials.components.eventlog.integration.EventLogProcessorDependencies;
import dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.transaction.*;
import dk.trustworks.essentials.components.foundation.messaging.MessageHandler;
import dk.trustworks.essentials.examples.trading.config.*;
import dk.trustworks.essentials.examples.trading.settlement.aggregates.TradeSettlements;
import dk.trustworks.essentials.examples.trading.settlement.events.*;
import org.springframework.stereotype.Service;

import java.util.List;

import static dk.trustworks.essentials.shared.FailFast.requireNonNull;

/**
 * Feeds {@link SettlementsProjection} on the event-log engine: an {@code EventLogViewProcessor} that hands each
 * event to the projection with the handle of the engine's page transaction, so the row commits together with the
 * subscription's progress. Same processor name as {@link SettlementsClassicView}.
 */
@EventLogEngine
@Service
public class SettlementsEventLogView extends TradingDemoEventLogViewProcessor {
    private final SettlementsProjection projection;

    public SettlementsEventLogView(EventLogProcessorDependencies dependencies,
                                   EventStoreUnitOfWorkFactory<? extends EventStoreUnitOfWork> unitOfWorkFactory,
                                   SettlementsProjection projection) {
        super("SettlementsProjection", List.of(TradeSettlements.AGGREGATE_TYPE), dependencies, unitOfWorkFactory);
        this.projection = requireNonNull(projection, "No projection provided");
    }

    @MessageHandler
    void handle(SettlementInstructed e) {
        projection.on(pageHandle(), e);
    }

    @MessageHandler
    void handle(SettlementPostponed e) {
        projection.on(pageHandle(), e);
    }

    @MessageHandler
    void handle(SettlementSettled e) {
        projection.on(pageHandle(), e);
    }

    @MessageHandler
    void handle(SettlementAbandoned e) {
        projection.on(pageHandle(), e);
    }

    @MessageHandler
    void handle(SettlementAmountChanged e) {
        projection.on(pageHandle(), e);
    }
}
