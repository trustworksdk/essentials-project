package com.example.multi.catalog.use_cases.reprice_product;

import com.example.multi.catalog.types.ProductId;

public record RepriceProduct(ProductId id, long priceMinor) {
}
