package com.example.mall.orders.use_cases.place_order;

import com.example.mall.orders.events.OrderEvent;
import com.example.mall.orders.events.OrderPlaced;
import com.example.mall.orders.use_cases._shared.OrderState;
import dk.trustworks.essentials.components.eventsourced.aggregates.decider.Decider;
import java.util.Optional;

public class PlaceOrderDecider implements Decider<PlaceOrder, OrderEvent> {
    OrderState state;

    public Optional<OrderEvent> handle(PlaceOrder cmd, java.util.List<OrderEvent> events) {
        return Optional.of(OrderPlaced.of(cmd));
    }
}
