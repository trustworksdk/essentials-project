package com.example.mall.orders.views.order_board;

import com.example.mall.orders.events.OrderPlaced;
import com.example.mall.orders.use_cases._shared.OrderState;
import com.example.mall.orders.use_cases.place_order.PlaceOrderDecider;
import dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.processor.ViewEventProcessor;
import dk.trustworks.essentials.components.foundation.messaging.MessageHandler;

public class OrderBoardProjection extends ViewEventProcessor {
    private final PlaceOrderDecider decider = new PlaceOrderDecider();
    private OrderState last;

    @MessageHandler
    void on(OrderPlaced e) {
    }
}
