package com.acme.multi.catalog.use_cases.reprice_product;

import com.acme.multi.catalog.types.ProductId;

public record RepriceProduct(ProductId id, long priceMinor) {
}
