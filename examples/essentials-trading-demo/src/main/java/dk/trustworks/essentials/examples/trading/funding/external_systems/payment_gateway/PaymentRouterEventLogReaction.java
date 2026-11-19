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
package dk.trustworks.essentials.examples.trading.funding.external_systems.payment_gateway;

import dk.trustworks.essentials.components.eventlog.processor.*;
import dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.eventstream.AggregateType;
import dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.transaction.*;
import dk.trustworks.essentials.components.foundation.messaging.*;
import dk.trustworks.essentials.components.foundation.messaging.eip.store_and_forward.Inboxes;
import dk.trustworks.essentials.examples.trading.funding.events.*;
import dk.trustworks.essentials.examples.trading.config.*;
import org.springframework.stereotype.Service;

import static dk.trustworks.essentials.shared.FailFast.requireNonNull;

/**
 * Feeds {@link PaymentRouter} on the event-log engine: a {@link EventLogInboxProcessor} that forwards the events
 * from the event log into its inbox and hands each one to {@link PaymentRouter}, each handler in its own unit of work as
 * declared. Same processor name, inbox and handlers as {@link PaymentRouterClassicReaction}.
 */
@EventLogEngine
@Service
public class PaymentRouterEventLogReaction extends EventLogInboxProcessor {
    private final PaymentRouter router;

    public PaymentRouterEventLogReaction(EventLogProcessorDependencies dependencies,
                                         Inboxes inboxes,
                                         EventStoreUnitOfWorkFactory<? extends EventStoreUnitOfWork> unitOfWorkFactory,
                                         PaymentRouter router) {
        super(PaymentRouter.PROCESSOR_NAME, PaymentRouter.AGGREGATE_TYPES.stream().map(AggregateType::toString).toList(), dependencies, inboxes, unitOfWorkFactory);
        this.router = requireNonNull(router, "No router provided");
    }

    @MessageHandler(unitOfWork = UnitOfWorkMode.NONE)
    void on(DepositRequested e) {
        router.on(e);
    }

    @MessageHandler(unitOfWork = UnitOfWorkMode.NONE)
    void on(WithdrawalRequested e) {
        router.on(e);
    }
}
