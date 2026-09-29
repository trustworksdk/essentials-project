package com.acme.billing.events;

import com.acme.billing.types.InvoiceId;
import com.fasterxml.jackson.annotation.JsonTypeName;

@JsonTypeName("InvoicePaid")
public record InvoicePaid(InvoiceId id, long amountMinor) implements InvoiceEvent {
}
