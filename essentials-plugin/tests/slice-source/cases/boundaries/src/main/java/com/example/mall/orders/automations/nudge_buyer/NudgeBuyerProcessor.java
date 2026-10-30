package com.example.mall.orders.automations.nudge_buyer;

import com.example.mall.orders.events.OrderPlaced;
import com.example.mall.orders.use_cases.place_order.PlaceOrder;
import dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.processor.EventProcessor;
import dk.trustworks.essentials.components.foundation.messaging.MessageHandler;

public class NudgeBuyerProcessor extends EventProcessor {
    @MessageHandler
    void on(OrderPlaced e) {
        PlaceOrder again = new PlaceOrder(e.id(), e.lines());
        getCommandBus().sendAndDontWait(again);
    }
}
