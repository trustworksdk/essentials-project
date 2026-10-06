package com.example.multi.inventory.events;

import com.example.multi.inventory.types.Sku;

public record StockAdjusted(Sku sku, long onHand) implements InventoryEvent {
}
