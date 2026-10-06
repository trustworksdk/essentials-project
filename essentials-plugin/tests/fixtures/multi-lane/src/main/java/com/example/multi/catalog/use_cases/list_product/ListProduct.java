package com.example.multi.catalog.use_cases.list_product;

import com.example.multi.catalog.types.ProductId;

public record ListProduct(ProductId id, String name, long priceMinor) {
}
