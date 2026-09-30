package com.acme.multi.inventory.use_cases.adjust_stock;

import com.acme.multi.inventory.types.Sku;

public record AdjustStock(Sku sku, long delta) {
}
