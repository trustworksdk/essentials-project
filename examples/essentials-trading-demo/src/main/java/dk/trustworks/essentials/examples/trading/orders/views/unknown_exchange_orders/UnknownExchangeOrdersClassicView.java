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

package dk.trustworks.essentials.examples.trading.orders.views.unknown_exchange_orders;

import dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.eventstream.AggregateType;
import dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.processor.*;
import dk.trustworks.essentials.components.foundation.messaging.MessageHandler;
import dk.trustworks.essentials.examples.trading.config.*;
import dk.trustworks.essentials.examples.trading.orders.events.UnknownExchangeOrderFilled;
import dk.trustworks.essentials.examples.trading.orders.types.OrdersAggregateTypes;
import org.springframework.stereotype.Service;

import java.util.List;

import static dk.trustworks.essentials.shared.FailFast.requireNonNull;

/**
 * Feeds {@link UnknownExchangeOrdersProjection} on the classic engine: a {@link ViewEventProcessor} that hands each event
 * to the projection with the handle of the {@code UnitOfWork} it handles the event in, so the row commits together with
 * the processor's progress. Keeps the processor name the view always had.
 */
@ClassicEngine
@Service
public class UnknownExchangeOrdersClassicView extends TradingDemoViewEventProcessor {
    private final UnknownExchangeOrdersProjection projection;

    public UnknownExchangeOrdersClassicView(ViewEventProcessorDependencies dependencies, UnknownExchangeOrdersProjection projection) {
        super(dependencies);
        this.projection = requireNonNull(projection, "No projection provided");
    }

    @Override
    public String getProcessorName() {
        return "UnknownExchangeOrdersProjection";
    }

    @Override
    protected List<AggregateType> reactsToEventsRelatedToAggregateTypes() {
        return List.of(OrdersAggregateTypes.UNKNOWN_EXCHANGE_ORDERS);
    }

    @MessageHandler
    void handle(UnknownExchangeOrderFilled e) {
        projection.on(getRequiredUnitOfWork().handle(), e);
    }
}
