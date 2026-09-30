package com.acme.billing.events;

import com.acme.billing.types.InvoiceId;

public record InvoiceVoided(InvoiceId invoiceId) implements InvoiceEvent {
}
