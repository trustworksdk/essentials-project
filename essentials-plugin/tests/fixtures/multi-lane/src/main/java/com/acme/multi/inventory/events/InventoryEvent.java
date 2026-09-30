package com.acme.multi.inventory.events;

import com.acme.multi.inventory.types.Sku;

public sealed interface InventoryEvent permits StockAdjusted {
    Sku sku();
}
