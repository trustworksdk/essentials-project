package com.example.billing.automations.recurring_billing;

import dk.trustworks.essentials.components.document_db.JavaVersionedEntity;
import dk.trustworks.essentials.components.document_db.Version;
import dk.trustworks.essentials.components.document_db.annotations.DocumentEntity;
import dk.trustworks.essentials.components.document_db.annotations.Id;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;

/**
 * Process state for one invoice: has it been paid, and has the next period's invoice been ordered.
 * One row per invoice, so {@code version} can carry that invoice stream's EventOrder.
 */
@DocumentEntity(tableName = "billing_recurring_billing_todo")
public class RecurringBillingTodo extends JavaVersionedEntity<String, RecurringBillingTodo> {

    @Id
    public String invoiceId;

    private boolean paid;
    private boolean nextInvoiceRequested;

    private long version = Version.NOT_SAVED_YET_VALUE;
    private OffsetDateTime lastUpdated = OffsetDateTime.now(ZoneOffset.UTC);

    public RecurringBillingTodo() {
    }

    public RecurringBillingTodo(String invoiceId) {
        this.invoiceId = invoiceId;
    }

    /** The process rule: one follow-up invoice per paid invoice, never before it is paid. */
    public boolean mayRequestNextInvoice() {
        return paid && !nextInvoiceRequested;
    }

    @Override public long getVersionValue()                          { return version; }
    @Override public void setVersionValue(long version)              { this.version = version; }
    @Override public OffsetDateTime getLastUpdated()                 { return lastUpdated; }
    @Override public void setLastUpdated(OffsetDateTime lastUpdated) { this.lastUpdated = lastUpdated; }

    public String getInvoiceId()                          { return invoiceId; }
    public boolean isPaid()                               { return paid; }
    public void setPaid(boolean paid)                     { this.paid = paid; }
    public boolean isNextInvoiceRequested()               { return nextInvoiceRequested; }
    public void setNextInvoiceRequested(boolean request)  { this.nextInvoiceRequested = request; }
}
