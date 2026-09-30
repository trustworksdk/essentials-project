package com.acme.billing.events;

import com.acme.billing.types.InvoiceId;

public record InvoicePaid(InvoiceId invoiceId) implements InvoiceEvent {
}
