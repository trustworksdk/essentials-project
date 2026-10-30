package com.example.mall.billing.views.invoice_feed;

import static com.example.mall.orders.config.OrderDefaults.MAX_LINES;

import com.example.mall.orders.config.OrderLimits;
import com.example.mall.orders.events.OrderPlaced;
import com.example.mall.orders.types.OrderId;
import com.example.mall.shared.ids.TenantId;
import com.example.mall.stock.aggregates.Stock;
import dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.processor.EventProcessor;
import dk.trustworks.essentials.components.foundation.messaging.MessageHandler;
import java.util.List;

public class InvoiceFeedProjection extends EventProcessor {
    private OrderLimits limits;
    private TenantId tenant;

    protected List<Object> reactsToEventsRelatedToAggregateTypes() {
        return List.of(Stock.STOCK);
    }

    @MessageHandler
    void on(OrderPlaced e) {
        OrderId id = e.id();
        boolean big = e.lines() > MAX_LINES || e.lines() > limits.maxLines();
    }
}
