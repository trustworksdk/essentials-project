package com.acme.multi.inventory.use_cases.adjust_stock;

import com.acme.multi.inventory.entities.StockItem;
import com.acme.multi.inventory.entities.StockItems;
import com.acme.multi.inventory.events.StockAdjusted;
import dk.trustworks.essentials.components.document_db.Version;
import dk.trustworks.essentials.reactive.EventBus;
import dk.trustworks.essentials.reactive.command.AnnotatedCommandHandler;
import dk.trustworks.essentials.reactive.command.CmdHandler;
import org.springframework.stereotype.Component;

@Component
public class AdjustStockHandler extends AnnotatedCommandHandler {
    private final StockItems stockItems;
    private final EventBus eventBus;

    public AdjustStockHandler(StockItems stockItems, EventBus eventBus) {
        this.stockItems = stockItems;
        this.eventBus = eventBus;
    }

    @CmdHandler
    public void handle(AdjustStock cmd) {
        var existing = stockItems.findById(cmd.sku().toString());
        var item = existing != null ? existing : new StockItem(cmd.sku().toString());
        var onHand = item.adjust(cmd.delta());
        if (existing == null) {
            stockItems.save(item, Version.ZERO_VALUE);
        } else {
            stockItems.update(item);
        }
        eventBus.publish(new StockAdjusted(cmd.sku(), onHand));
    }
}
