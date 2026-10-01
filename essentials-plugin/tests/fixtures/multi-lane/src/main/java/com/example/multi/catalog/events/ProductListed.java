package com.example.multi.catalog.events;

import com.example.multi.catalog.types.ProductId;

public record ProductListed(ProductId id, String name, long priceMinor) implements CatalogEvent {
}
