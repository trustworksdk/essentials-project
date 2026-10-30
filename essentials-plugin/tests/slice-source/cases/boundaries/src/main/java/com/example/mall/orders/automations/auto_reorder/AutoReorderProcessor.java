package com.example.mall.orders.automations.auto_reorder;

import com.example.mall.orders.events.OrderPlaced;
import com.example.mall.orders.use_cases.place_order.PlaceOrder;
import dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.processor.EventProcessor;
import dk.trustworks.essentials.components.foundation.messaging.MessageHandler;

public class AutoReorderProcessor extends EventProcessor {
    private PlaceOrder previous;

    @MessageHandler
    void on(OrderPlaced e) {
        previous = new PlaceOrder(e.id(), e.lines());
        getCommandBus().send(previous);
    }
}
