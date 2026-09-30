package com.acme.multi.inventory.events;

import com.acme.multi.inventory.types.Sku;

public record StockAdjusted(Sku sku, long onHand) implements InventoryEvent {
}
