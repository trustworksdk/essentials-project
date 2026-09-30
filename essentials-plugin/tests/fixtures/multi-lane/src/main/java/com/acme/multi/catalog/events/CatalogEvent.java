package com.acme.multi.catalog.events;

import com.acme.multi.catalog.types.ProductId;

public sealed interface CatalogEvent permits ProductListed, ProductRepriced {
    ProductId id();
}
