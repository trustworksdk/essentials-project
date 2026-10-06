package com.example.billing.events;

import com.example.billing.types.InvoiceId;

public record InvoiceIssued(InvoiceId invoiceId) implements InvoiceEvent {
}
