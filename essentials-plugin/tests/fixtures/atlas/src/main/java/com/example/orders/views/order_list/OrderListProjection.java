package com.example.orders.views.order_list;

import com.example.orders.events.OrderCancelled;
import com.example.orders.events.OrderPlaced;
import dk.trustworks.essentials.components.foundation.messaging.MessageHandler;

public class OrderListProjection {

    @MessageHandler
    void on(OrderPlaced event) {
    }

    @MessageHandler
    void on(OrderCancelled event) {
    }
}
