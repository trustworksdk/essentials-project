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

package dk.trustworks.essentials.examples.trading.orders.views.orders;

import dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.eventstream.AggregateType;
import dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.processor.*;
import dk.trustworks.essentials.components.foundation.messaging.MessageHandler;
import dk.trustworks.essentials.components.foundation.messaging.queue.OrderedMessage;
import dk.trustworks.essentials.examples.trading.config.*;
import dk.trustworks.essentials.examples.trading.orders.events.*;
import dk.trustworks.essentials.examples.trading.orders.types.OrdersAggregateTypes;
import org.springframework.stereotype.Service;

import java.util.List;

import static dk.trustworks.essentials.shared.FailFast.requireNonNull;

/**
 * Feeds {@link OrdersProjection} on the classic engine: a {@link ViewEventProcessor} that hands each event, with its
 * {@code EventOrder} ({@link OrderedMessage#getOrder()}), to the projection with the handle of the {@code UnitOfWork}
 * it handles the event in, so the row commits together with the processor's progress. Keeps the processor name the
 * view always had.
 */
// Deliberately not handled: the startup summary warns about every sealed-hierarchy leaf a view neither handles nor lists here
@IgnoresEventTypes({OrderFilledAfterClose.class})
@ClassicEngine
@Service
public class OrdersClassicView extends TradingDemoViewEventProcessor {
    private final OrdersProjection projection;

    public OrdersClassicView(ViewEventProcessorDependencies dependencies, OrdersProjection projection) {
        super(dependencies);
        this.projection = requireNonNull(projection, "No projection provided");
    }

    @Override
    public String getProcessorName() {
        return "OrdersProjection";
    }

    @Override
    protected List<AggregateType> reactsToEventsRelatedToAggregateTypes() {
        return List.of(OrdersAggregateTypes.ORDERS);
    }

    @MessageHandler
    void handle(OrderPlaced e, OrderedMessage message) {
        projection.on(getRequiredUnitOfWork().handle(), e, message.getOrder());
    }

    @MessageHandler
    void handle(OrderSubmitted e, OrderedMessage message) {
        projection.on(getRequiredUnitOfWork().handle(), e, message.getOrder());
    }

    @MessageHandler
    void handle(OrderAccepted e, OrderedMessage message) {
        projection.on(getRequiredUnitOfWork().handle(), e, message.getOrder());
    }

    @MessageHandler
    void handle(OrderRejected e, OrderedMessage message) {
        projection.on(getRequiredUnitOfWork().handle(), e, message.getOrder());
    }

    @MessageHandler
    void handle(OrderFilled e, OrderedMessage message) {
        projection.on(getRequiredUnitOfWork().handle(), e, message.getOrder());
    }

    @MessageHandler
    void handle(OrderCancelRequested e, OrderedMessage message) {
        projection.on(getRequiredUnitOfWork().handle(), e, message.getOrder());
    }

    @MessageHandler
    void handle(OrderCancelled e, OrderedMessage message) {
        projection.on(getRequiredUnitOfWork().handle(), e, message.getOrder());
    }

    @MessageHandler
    void handle(OrderExpiryRequested e, OrderedMessage message) {
        projection.on(getRequiredUnitOfWork().handle(), e, message.getOrder());
    }

    @MessageHandler
    void handle(OrderExpired e, OrderedMessage message) {
        projection.on(getRequiredUnitOfWork().handle(), e, message.getOrder());
    }

    @MessageHandler
    void handle(OrderClosed e, OrderedMessage message) {
        projection.on(getRequiredUnitOfWork().handle(), e, message.getOrder());
    }
}
