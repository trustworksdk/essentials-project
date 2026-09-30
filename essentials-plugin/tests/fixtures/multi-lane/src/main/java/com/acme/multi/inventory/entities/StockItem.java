package com.acme.multi.inventory.entities;

import dk.trustworks.essentials.components.document_db.JavaVersionedEntity;
import dk.trustworks.essentials.components.document_db.Version;
import dk.trustworks.essentials.components.document_db.annotations.DocumentEntity;
import dk.trustworks.essentials.components.document_db.annotations.Id;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;

@DocumentEntity(tableName = "inventory_stock_item")
public class StockItem extends JavaVersionedEntity<String, StockItem> {

    @Id
    public String sku;

    private long onHand;

    private long version = Version.NOT_SAVED_YET_VALUE;
    private OffsetDateTime lastUpdated = OffsetDateTime.now(ZoneOffset.UTC);

    public StockItem() {
    }

    public StockItem(String sku) {
        this.sku = sku;
    }

    /** Stock never goes negative. Returns the new on-hand count. */
    public long adjust(long delta) {
        if (onHand + delta < 0) {
            throw new IllegalArgumentException("Stock cannot go negative");
        }
        onHand += delta;
        return onHand;
    }

    @Override public long getVersionValue()                          { return version; }
    @Override public void setVersionValue(long version)              { this.version = version; }
    @Override public OffsetDateTime getLastUpdated()                 { return lastUpdated; }
    @Override public void setLastUpdated(OffsetDateTime lastUpdated) { this.lastUpdated = lastUpdated; }
}
