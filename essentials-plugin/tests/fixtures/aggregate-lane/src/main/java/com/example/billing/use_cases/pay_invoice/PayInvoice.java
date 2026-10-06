package com.example.billing.use_cases.pay_invoice;

import com.example.billing.types.InvoiceId;

public record PayInvoice(InvoiceId id, long paidMinor) {
}
