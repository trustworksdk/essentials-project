package com.acme.billing.events;

public sealed interface InvoiceEvent permits InvoiceIssued, InvoicePaid, InvoiceVoided {
}
