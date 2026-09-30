package com.acme.billing.events;

import com.acme.billing.types.InvoiceId;

public record InvoiceIssued(InvoiceId invoiceId) implements InvoiceEvent {
}
