package com.acme.billing.aggregates;

import com.acme.billing.events.InvoiceEvent;
import com.acme.billing.events.InvoiceIssued;
import com.acme.billing.events.InvoicePaid;
import com.acme.billing.types.InvoiceId;
import dk.trustworks.essentials.components.eventsourced.aggregates.EventHandler;
import dk.trustworks.essentials.components.eventsourced.aggregates.stateful.modern.AggregateRoot;

public class Invoice extends AggregateRoot<InvoiceId, InvoiceEvent, Invoice> {
    private long amountMinor;
    private boolean paid;

    public Invoice(InvoiceId aggregateId) { super(aggregateId); }

    public Invoice(InvoiceId invoiceId, long amountMinor) {
        super(invoiceId);
        if (amountMinor <= 0) {
            throw new IllegalArgumentException("Invoice amount must be positive");
        }
        apply(new InvoiceIssued(invoiceId, amountMinor));
    }

    public boolean pay(long paidMinor) {
        if (paid) {
            return false;
        }
        if (paidMinor != amountMinor) {
            throw new IllegalArgumentException("Partial payment not allowed");
        }
        apply(new InvoicePaid(aggregateId(), paidMinor));
        return true;
    }

    public boolean isPaid() { return paid; }

    @EventHandler
    private void on(InvoiceIssued e) { amountMinor = e.amountMinor(); }

    @EventHandler
    private void on(InvoicePaid e) { paid = true; }
}
