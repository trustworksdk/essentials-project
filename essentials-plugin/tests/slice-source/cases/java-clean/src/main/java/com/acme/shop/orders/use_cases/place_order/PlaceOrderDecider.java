package com.acme.shop.orders.use_cases.place_order;

import com.acme.shop.orders.events.OrderEvent;
import com.acme.shop.orders.events.OrderPlaced;
import dk.trustworks.essentials.components.eventsourced.aggregates.eventstream.EventStreamDecider;

import java.util.List;
import java.util.Optional;

/** RULE (handles/publishes): the first type argument is the command; the event constructed here is published. */
public class PlaceOrderDecider implements EventStreamDecider<PlaceOrder, OrderEvent> {

    @Override
    public Optional<OrderEvent> handle(PlaceOrder cmd, List<OrderEvent> events) {
        if (!events.isEmpty()) {
            return Optional.empty();
        }
        return Optional.of(new OrderPlaced(cmd.customerId(), cmd.id(), cmd.sku(), cmd.quantity()));
    }

    @Override
    public boolean canHandle(Class<?> command) {
        return PlaceOrder.class == command;
    }
}
