/*
 *  Copyright 2021-2025 the original author or authors.
 *
 *  Licensed under the Apache License, Version 2.0 (the "License");
 *  you may not use this file except in compliance with the License.
 *  You may obtain a copy of the License at
 *
 *       https://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 */
package dk.trustworks.essentials.examples.trading.market_data.use_cases.risk_approve_instrument;

import dk.trustworks.essentials.components.eventlog.integration.*;
import dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.eventstream.AggregateType;
import dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.transaction.*;
import dk.trustworks.essentials.components.foundation.messaging.*;
import dk.trustworks.essentials.components.foundation.messaging.eip.store_and_forward.Inboxes;
import dk.trustworks.essentials.examples.trading.market_data.events.InstrumentRegistered;
import dk.trustworks.essentials.examples.trading.config.*;
import org.springframework.stereotype.Service;

import static dk.trustworks.essentials.shared.FailFast.requireNonNull;

/**
 * Feeds {@link InstrumentRiskApprovalProcessor} on the event-log engine: a {@link EventLogInboxProcessor} that forwards the events
 * from the event log into its inbox and hands each one to {@link InstrumentRiskApprovalProcessor}, each handler in its own unit of work as
 * declared. Same processor name, inbox and handlers as {@link InstrumentRiskApprovalClassicReaction}.
 */
@EventLogEngine
@Service
public class InstrumentRiskApprovalEventLogReaction extends EventLogInboxProcessor {
    private final InstrumentRiskApprovalProcessor processor;

    public InstrumentRiskApprovalEventLogReaction(EventLogProcessorDependencies dependencies,
                                                  Inboxes inboxes,
                                                  EventStoreUnitOfWorkFactory<? extends EventStoreUnitOfWork> unitOfWorkFactory,
                                                  InstrumentRiskApprovalProcessor processor) {
        super(InstrumentRiskApprovalProcessor.PROCESSOR_NAME, InstrumentRiskApprovalProcessor.AGGREGATE_TYPES.stream().map(AggregateType::toString).toList(), dependencies, inboxes, unitOfWorkFactory);
        this.processor = requireNonNull(processor, "No processor provided");
    }

    @MessageHandler(unitOfWork = UnitOfWorkMode.NONE)
    void on(InstrumentRegistered e) {
        processor.on(e);
    }
}
