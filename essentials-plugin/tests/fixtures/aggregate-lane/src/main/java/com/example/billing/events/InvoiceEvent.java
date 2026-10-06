package com.example.billing.events;

import com.example.billing.types.InvoiceId;
import com.fasterxml.jackson.annotation.JsonTypeInfo;

@JsonTypeInfo(use = JsonTypeInfo.Id.NAME, include = JsonTypeInfo.As.PROPERTY, property = "@type")
public sealed interface InvoiceEvent permits InvoiceIssued, InvoicePaid {
    InvoiceId id();
}
