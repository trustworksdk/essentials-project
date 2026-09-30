package com.acme.multi.catalog.use_cases.list_product;

import com.acme.multi.catalog.types.ProductId;

public record ListProduct(ProductId id, String name, long priceMinor) {
}
