package com.acme.multi.catalog.events;

import com.acme.multi.catalog.types.ProductId;

public record ProductRepriced(ProductId id, long priceMinor) implements CatalogEvent {
}
