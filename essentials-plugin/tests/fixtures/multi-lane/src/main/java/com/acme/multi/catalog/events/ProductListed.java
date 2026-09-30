package com.acme.multi.catalog.events;

import com.acme.multi.catalog.types.ProductId;

public record ProductListed(ProductId id, String name, long priceMinor) implements CatalogEvent {
}
