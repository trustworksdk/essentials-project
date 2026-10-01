package com.example.billing.events;

import com.example.billing.types.InvoiceId;

public record InvoicePaid(InvoiceId invoiceId) implements InvoiceEvent {
}
