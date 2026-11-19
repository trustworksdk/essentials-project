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

import dk.trustworks.essentials.components.eventlog.integration.*;
import dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.transaction.*;
import dk.trustworks.essentials.components.foundation.messaging.MessageHandler;
import dk.trustworks.essentials.examples.trading.config.*;
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
 * Feeds {@link InstrumentDetailsProjection} on the event-log engine: an {@code EventLogViewProcessor} that hands each
 * event to the projection with the handle of the engine's page transaction, so the row commits together with the
 * subscription's progress. Same processor name as {@link InstrumentDetailsClassicView}.
 */
@EventLogEngine
@Service
public class InstrumentDetailsEventLogView extends TradingDemoEventLogViewProcessor {
    private final InstrumentDetailsProjection projection;

    public InstrumentDetailsEventLogView(EventLogProcessorDependencies dependencies,
                                         EventStoreUnitOfWorkFactory<? extends EventStoreUnitOfWork> unitOfWorkFactory,
                                         InstrumentDetailsProjection projection) {
        super("InstrumentDetailsProjection", List.of(Instruments.AGGREGATE_TYPE), dependencies, unitOfWorkFactory);
        this.projection = requireNonNull(projection, "No projection provided");
    }

    /** Empties the view in the rebuild's transaction, as {@code onSubscriptionsReset} does on the classic engine */
    @Override
    protected void onRebuild(ProjectionContext context) {
        projection.truncate(pageHandle());
    }

    @MessageHandler
    void handle(InstrumentRegistered event) {
        projection.on(pageHandle(), event);
    }

    @MessageHandler
    void handle(InstrumentRenamed event) {
        projection.on(pageHandle(), event);
    }

    @MessageHandler
    void handle(InstrumentSuspended event) {
        projection.on(pageHandle(), event);
    }

    @MessageHandler
    void handle(InstrumentRiskApproved event) {
        projection.on(pageHandle(), event);
    }

    @MessageHandler
    void handle(InstrumentRiskRejected event) {
        projection.on(pageHandle(), event);
    }
}
