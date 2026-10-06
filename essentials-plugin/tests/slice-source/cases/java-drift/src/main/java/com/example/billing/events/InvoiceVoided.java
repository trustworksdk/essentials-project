package com.example.billing.events;

import com.example.billing.types.InvoiceId;

public record InvoiceVoided(InvoiceId invoiceId) implements InvoiceEvent {
}
