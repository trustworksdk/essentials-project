package com.acme.billing.use_cases.issue_invoice;

import com.acme.billing.types.InvoiceId;

public record IssueInvoice(InvoiceId invoiceId, long amountMinor) {
}
