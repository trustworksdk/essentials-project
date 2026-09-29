package com.acme.billing.events;

import com.acme.billing.types.InvoiceId;
import com.fasterxml.jackson.annotation.JsonTypeName;

@JsonTypeName("InvoiceIssued")
public record InvoiceIssued(InvoiceId id, long amountMinor) implements InvoiceEvent {
}
