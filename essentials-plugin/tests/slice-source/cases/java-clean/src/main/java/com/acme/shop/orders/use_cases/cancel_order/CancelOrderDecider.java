package com.acme.shop.orders.use_cases.cancel_order;

import com.acme.shop.orders.events.OrderCancelled;
import com.acme.shop.orders.events.OrderEvent;
import dk.trustworks.essentials.components.eventsourced.aggregates.eventstream.EventStreamDecider;

import java.util.List;
import java.util.Optional;

public class CancelOrderDecider implements EventStreamDecider<CancelOrder, OrderEvent> {

    @Override
    public Optional<OrderEvent> handle(CancelOrder cmd, List<OrderEvent> events) {
        return Optional.of(new OrderCancelled(cmd.id(), cmd.reason()));
    }

    @Override
    public boolean canHandle(Class<?> command) {
        return CancelOrder.class == command;
    }
}
