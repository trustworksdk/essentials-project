package com.example.multi.inventory.use_cases.adjust_stock;

import com.example.multi.inventory.types.Sku;

public record AdjustStock(Sku sku, long delta) {
}
