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

package dk.trustworks.essentials.examples.trading.accounts.views.positions;

import dk.trustworks.essentials.components.eventlog.processor.*;
import dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.transaction.*;
import dk.trustworks.essentials.components.foundation.messaging.MessageHandler;
import dk.trustworks.essentials.examples.trading.accounts.events.*;
import dk.trustworks.essentials.examples.trading.accounts.types.AccountsAggregateTypes;
import dk.trustworks.essentials.examples.trading.config.*;
import org.springframework.stereotype.Service;

import java.util.List;

import static dk.trustworks.essentials.shared.FailFast.requireNonNull;

/**
 * Feeds {@link PositionsProjection} on the event-log engine: an {@code EventLogViewProcessor} that hands each event,
 * with its {@code EventOrder} ({@link DeliveredEvent#eventOrder()}, what the classic engine's
 * {@code OrderedMessage#getOrder()} carries), to the projection with the handle of the engine's page transaction, so
 * the rows commit together with the subscription's progress. Same processor name as {@link PositionsClassicView}.
 */
@EventLogEngine
@Service
public class PositionsEventLogView extends TradingDemoEventLogViewProcessor {
    private final PositionsProjection projection;

    public PositionsEventLogView(EventLogProcessorDependencies dependencies,
                                 EventStoreUnitOfWorkFactory<? extends EventStoreUnitOfWork> unitOfWorkFactory,
                                 PositionsProjection projection) {
        super("PositionsProjection", List.of(AccountsAggregateTypes.ACCOUNTS), dependencies, unitOfWorkFactory);
        this.projection = requireNonNull(projection, "No projection provided");
    }

    /** Empties the view in the rebuild's transaction, as {@code onSubscriptionsReset} does on the classic engine */
    @Override
    protected void onRebuild(ProjectionContext context) {
        projection.truncate(pageHandle());
    }

    @MessageHandler
    void handle(AccountOpened event, DeliveredEvent delivered) {
        projection.on(pageHandle(), event, delivered.eventOrder().longValue());
    }

    @MessageHandler
    void handle(SharesReserved event, DeliveredEvent delivered) {
        projection.on(pageHandle(), event, delivered.eventOrder().longValue());
    }

    @MessageHandler
    void handle(SharesReleased event, DeliveredEvent delivered) {
        projection.on(pageHandle(), event, delivered.eventOrder().longValue());
    }

    @MessageHandler
    void handle(TradeBooked event, DeliveredEvent delivered) {
        projection.on(pageHandle(), event, delivered.eventOrder().longValue());
    }

    @MessageHandler
    void handle(TradeSettled event, DeliveredEvent delivered) {
        projection.on(pageHandle(), event, delivered.eventOrder().longValue());
    }

    @MessageHandler
    void handle(SettlementAmountCorrected event, DeliveredEvent delivered) {
        projection.on(pageHandle(), event, delivered.eventOrder().longValue());
    }

    @MessageHandler
    void handle(TradeBookingReversed event, DeliveredEvent delivered) {
        projection.on(pageHandle(), event, delivered.eventOrder().longValue());
    }
}
