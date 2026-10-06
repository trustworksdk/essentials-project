package com.example.billing.events;

import com.example.billing.types.InvoiceId;
import com.fasterxml.jackson.annotation.JsonTypeName;

@JsonTypeName("InvoicePaid")
public record InvoicePaid(InvoiceId id, long amountMinor) implements InvoiceEvent {
}
