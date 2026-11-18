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

package dk.trustworks.essentials.examples.trading.reconciliation.views.cases;

import dk.trustworks.essentials.components.eventlog.integration.EventLogProcessorDependencies;
import dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.transaction.*;
import dk.trustworks.essentials.components.foundation.messaging.MessageHandler;
import dk.trustworks.essentials.examples.trading.config.*;
import dk.trustworks.essentials.examples.trading.reconciliation.aggregates.ReconciliationCases;
import dk.trustworks.essentials.examples.trading.reconciliation.events.*;
import org.springframework.stereotype.Service;

import java.util.List;

import static dk.trustworks.essentials.shared.FailFast.requireNonNull;

/**
 * Feeds {@link CasesProjection} on the event-log engine: an {@code EventLogViewProcessor} that hands each
 * event to the projection with the handle of the engine's page transaction, so the row commits together with the
 * subscription's progress. Same processor name as {@link CasesClassicView}.
 */
@EventLogEngine
@Service
public class CasesEventLogView extends TradingDemoEventLogViewProcessor {
    private final CasesProjection projection;

    public CasesEventLogView(EventLogProcessorDependencies dependencies,
                             EventStoreUnitOfWorkFactory<? extends EventStoreUnitOfWork> unitOfWorkFactory,
                             CasesProjection projection) {
        super("ReconciliationCasesProjection", List.of(ReconciliationCases.AGGREGATE_TYPE), dependencies, unitOfWorkFactory);
        this.projection = requireNonNull(projection, "No projection provided");
    }

    @MessageHandler
    void handle(CaseOpened e) {
        projection.on(pageHandle(), e);
    }

    @MessageHandler
    void handle(CaseAssigned e) {
        projection.on(pageHandle(), e);
    }

    @MessageHandler
    void handle(CaseResolved e) {
        projection.on(pageHandle(), e);
    }

    @MessageHandler
    void handle(CaseCleared e) {
        projection.on(pageHandle(), e);
    }
}
