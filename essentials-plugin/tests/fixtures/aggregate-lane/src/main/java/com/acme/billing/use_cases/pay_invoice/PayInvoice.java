package com.acme.billing.use_cases.pay_invoice;

import com.acme.billing.types.InvoiceId;

public record PayInvoice(InvoiceId id, long paidMinor) {
}
