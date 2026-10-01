package com.example.multi.catalog.events;

import com.example.multi.catalog.types.ProductId;

public record ProductRepriced(ProductId id, long priceMinor) implements CatalogEvent {
}
