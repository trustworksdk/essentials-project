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

import dk.trustworks.essentials.components.eventlog.processor.*;
import dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.transaction.*;
import dk.trustworks.essentials.components.foundation.messaging.MessageHandler;
import dk.trustworks.essentials.examples.trading._demo_harness.demo_control.ViewFaultSwitch;
import dk.trustworks.essentials.examples.trading.config.*;
import dk.trustworks.essentials.examples.trading.orders.events.*;
import dk.trustworks.essentials.examples.trading.orders.types.OrdersAggregateTypes;
import org.springframework.stereotype.Service;

import java.util.List;

import static dk.trustworks.essentials.shared.FailFast.requireNonNull;

/**
 * Feeds {@link OrdersProjection} on the event-log engine: an {@code EventLogViewProcessor} that hands each event, with
 * its {@code EventOrder} ({@link DeliveredEvent#eventOrder()}, what the classic engine's {@code OrderedMessage#getOrder()}
 * carries), to the projection with the handle of the engine's page transaction, so the row commits together with the
 * subscription's progress. Same processor name as {@link OrdersClassicView}.
 */
@EventLogEngine
@Service
public class OrdersEventLogView extends TradingDemoEventLogViewProcessor {
    private final OrdersProjection projection;
    private final ViewFaultSwitch  viewFault;

    public OrdersEventLogView(EventLogProcessorDependencies dependencies,
                              EventStoreUnitOfWorkFactory<? extends EventStoreUnitOfWork> unitOfWorkFactory,
                              OrdersProjection projection,
                              ViewFaultSwitch viewFault) {
        super("OrdersProjection", List.of(OrdersAggregateTypes.ORDERS), dependencies, unitOfWorkFactory);
        this.projection = requireNonNull(projection, "No projection provided");
        this.viewFault = requireNonNull(viewFault, "No viewFault provided");
    }

    @MessageHandler
    void handle(OrderPlaced e, DeliveredEvent event) {
        // The operator page's demo fault: parks newly placed orders, retrying or terminal (demo harness only)
        viewFault.check("the orders view");
        projection.on(pageHandle(), e, event.eventOrder().longValue());
    }

    @MessageHandler
    void handle(OrderSubmitted e, DeliveredEvent event) {
        projection.on(pageHandle(), e, event.eventOrder().longValue());
    }

    @MessageHandler
    void handle(OrderAccepted e, DeliveredEvent event) {
        projection.on(pageHandle(), e, event.eventOrder().longValue());
    }

    @MessageHandler
    void handle(OrderRejected e, DeliveredEvent event) {
        projection.on(pageHandle(), e, event.eventOrder().longValue());
    }

    @MessageHandler
    void handle(OrderFilled e, DeliveredEvent event) {
        projection.on(pageHandle(), e, event.eventOrder().longValue());
    }

    @MessageHandler
    void handle(OrderCancelRequested e, DeliveredEvent event) {
        projection.on(pageHandle(), e, event.eventOrder().longValue());
    }

    @MessageHandler
    void handle(OrderCancelled e, DeliveredEvent event) {
        projection.on(pageHandle(), e, event.eventOrder().longValue());
    }

    @MessageHandler
    void handle(OrderExpiryRequested e, DeliveredEvent event) {
        projection.on(pageHandle(), e, event.eventOrder().longValue());
    }

    @MessageHandler
    void handle(OrderExpired e, DeliveredEvent event) {
        projection.on(pageHandle(), e, event.eventOrder().longValue());
    }

    @MessageHandler
    void handle(OrderClosed e, DeliveredEvent event) {
        projection.on(pageHandle(), e, event.eventOrder().longValue());
    }
}
