package com.example.billing.events;

public sealed interface InvoiceEvent permits InvoiceIssued, InvoicePaid, InvoiceVoided {
}
