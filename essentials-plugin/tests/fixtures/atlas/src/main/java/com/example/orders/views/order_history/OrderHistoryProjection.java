package com.example.orders.views.order_history;

import com.example.orders.events.OrderPlaced;
import dk.trustworks.essentials.components.foundation.messaging.MessageHandler;

public class OrderHistoryProjection {

    @MessageHandler
    void on(OrderPlaced event) {
    }
}
