package com.example.billing.events;

import com.example.billing.types.InvoiceId;
import com.fasterxml.jackson.annotation.JsonTypeName;

@JsonTypeName("InvoiceIssued")
public record InvoiceIssued(InvoiceId id, long amountMinor) implements InvoiceEvent {
}
