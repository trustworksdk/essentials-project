package com.example.multi.inventory.events;

import com.example.multi.inventory.types.Sku;

public sealed interface InventoryEvent permits StockAdjusted {
    Sku sku();
}
