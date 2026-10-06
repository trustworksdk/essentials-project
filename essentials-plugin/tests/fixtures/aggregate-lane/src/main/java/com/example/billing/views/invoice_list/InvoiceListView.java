package com.example.billing.views.invoice_list;

import dk.trustworks.essentials.components.document_db.JavaVersionedEntity;
import dk.trustworks.essentials.components.document_db.Version;
import dk.trustworks.essentials.components.document_db.annotations.DocumentEntity;
import dk.trustworks.essentials.components.document_db.annotations.Id;
import dk.trustworks.essentials.components.document_db.annotations.Indexed;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;

/**
 * Read model row for the invoice list. Owned by this view slice.
 * {@code version} holds the EventOrder of the last event applied to the row.
 */
@DocumentEntity(tableName = "billing_invoice_list")
public class InvoiceListView extends JavaVersionedEntity<String, InvoiceListView> {

    @Id
    public String invoiceId;

    private long amountMinor;
    @Indexed
    private boolean paid;

    private long version = Version.NOT_SAVED_YET_VALUE;
    private OffsetDateTime lastUpdated = OffsetDateTime.now(ZoneOffset.UTC);

    public InvoiceListView() {
    }

    public InvoiceListView(String invoiceId, long amountMinor) {
        this.invoiceId = invoiceId;
        this.amountMinor = amountMinor;
    }

    @Override public long getVersionValue()                          { return version; }
    @Override public void setVersionValue(long version)              { this.version = version; }
    @Override public OffsetDateTime getLastUpdated()                 { return lastUpdated; }
    @Override public void setLastUpdated(OffsetDateTime lastUpdated) { this.lastUpdated = lastUpdated; }

    public String getInvoiceId()          { return invoiceId; }
    public long getAmountMinor()          { return amountMinor; }
    public boolean isPaid()               { return paid; }
    public void setPaid(boolean paid)     { this.paid = paid; }
}
