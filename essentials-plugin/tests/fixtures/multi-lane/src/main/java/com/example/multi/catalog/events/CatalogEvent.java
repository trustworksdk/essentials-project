package com.example.multi.catalog.events;

import com.example.multi.catalog.types.ProductId;

public sealed interface CatalogEvent permits ProductListed, ProductRepriced {
    ProductId id();
}
