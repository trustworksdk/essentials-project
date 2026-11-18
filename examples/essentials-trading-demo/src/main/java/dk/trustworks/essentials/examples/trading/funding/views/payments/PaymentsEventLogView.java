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

package dk.trustworks.essentials.examples.trading.funding.views.payments;

import dk.trustworks.essentials.components.eventlog.integration.EventLogProcessorDependencies;
import dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.transaction.*;
import dk.trustworks.essentials.components.foundation.messaging.MessageHandler;
import dk.trustworks.essentials.examples.trading.config.*;
import dk.trustworks.essentials.examples.trading.funding.aggregates.*;
import dk.trustworks.essentials.examples.trading.funding.events.*;
import org.springframework.stereotype.Service;

import java.util.List;

import static dk.trustworks.essentials.shared.FailFast.requireNonNull;

/**
 * Feeds {@link PaymentsProjection} on the event-log engine: an {@code EventLogViewProcessor} that hands each
 * event to the projection with the handle of the engine's page transaction, so the row commits together with the
 * subscription's progress. Same processor name as {@link PaymentsClassicView}.
 */
@EventLogEngine
@Service
public class PaymentsEventLogView extends TradingDemoEventLogViewProcessor {
    private final PaymentsProjection projection;

    public PaymentsEventLogView(EventLogProcessorDependencies dependencies,
                                EventStoreUnitOfWorkFactory<? extends EventStoreUnitOfWork> unitOfWorkFactory,
                                PaymentsProjection projection) {
        super("PaymentsProjection", List.of(Deposits.AGGREGATE_TYPE, Withdrawals.AGGREGATE_TYPE), dependencies, unitOfWorkFactory);
        this.projection = requireNonNull(projection, "No projection provided");
    }

    @MessageHandler
    void handle(DepositRequested e) {
        projection.on(pageHandle(), e);
    }

    @MessageHandler
    void handle(WithdrawalRequested e) {
        projection.on(pageHandle(), e);
    }

    @MessageHandler
    void handle(DepositInitiated e) {
        projection.on(pageHandle(), e);
    }

    @MessageHandler
    void handle(WithdrawalInitiated e) {
        projection.on(pageHandle(), e);
    }

    @MessageHandler
    void handle(DepositCompleted e) {
        projection.on(pageHandle(), e);
    }

    @MessageHandler
    void handle(WithdrawalCompleted e) {
        projection.on(pageHandle(), e);
    }

    @MessageHandler
    void handle(DepositFailed e) {
        projection.on(pageHandle(), e);
    }

    @MessageHandler
    void handle(WithdrawalFailed e) {
        projection.on(pageHandle(), e);
    }

    @MessageHandler
    void handle(DepositReturnedByBank e) {
        projection.on(pageHandle(), e);
    }

    @MessageHandler
    void handle(WithdrawalReturnedByBank e) {
        projection.on(pageHandle(), e);
    }
}
