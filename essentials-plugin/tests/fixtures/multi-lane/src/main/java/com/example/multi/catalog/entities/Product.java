package com.example.multi.catalog.entities;

import dk.trustworks.essentials.components.document_db.JavaVersionedEntity;
import dk.trustworks.essentials.components.document_db.Version;
import dk.trustworks.essentials.components.document_db.annotations.DocumentEntity;
import dk.trustworks.essentials.components.document_db.annotations.Id;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;

@DocumentEntity(tableName = "catalog_product")
public class Product extends JavaVersionedEntity<String, Product> {

    @Id
    public String productId;

    private String name;
    private long priceMinor;

    private long version = Version.NOT_SAVED_YET_VALUE;
    private OffsetDateTime lastUpdated = OffsetDateTime.now(ZoneOffset.UTC);

    public Product() {
    }

    public Product(String productId, String name, long priceMinor) {
        requirePositive(priceMinor);
        this.productId = productId;
        this.name = name;
        this.priceMinor = priceMinor;
    }

    /** Returns false when the price is unchanged, so repeating a command is a no-op. */
    public boolean reprice(long newPriceMinor) {
        requirePositive(newPriceMinor);
        if (newPriceMinor == priceMinor) {
            return false;
        }
        priceMinor = newPriceMinor;
        return true;
    }

    private static void requirePositive(long priceMinor) {
        if (priceMinor <= 0) {
            throw new IllegalArgumentException("A price must be positive");
        }
    }

    @Override public long getVersionValue()                          { return version; }
    @Override public void setVersionValue(long version)              { this.version = version; }
    @Override public OffsetDateTime getLastUpdated()                 { return lastUpdated; }
    @Override public void setLastUpdated(OffsetDateTime lastUpdated) { this.lastUpdated = lastUpdated; }
}
