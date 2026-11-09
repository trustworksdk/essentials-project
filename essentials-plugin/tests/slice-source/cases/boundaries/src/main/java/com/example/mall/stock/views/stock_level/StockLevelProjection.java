package com.example.mall.stock.views.stock_level;

import com.example.mall.stock.aggregates.Stock;
import com.example.mall.stock.events.StockReserved;
import dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.processor.ViewEventProcessor;
import dk.trustworks.essentials.components.foundation.messaging.MessageHandler;
import java.util.List;

public class StockLevelProjection extends ViewEventProcessor {
    protected List<Object> reactsToEventsRelatedToAggregateTypes() {
        return List.of(Stock.STOCK);
    }

    @MessageHandler
    void on(StockReserved e) {
    }
}
