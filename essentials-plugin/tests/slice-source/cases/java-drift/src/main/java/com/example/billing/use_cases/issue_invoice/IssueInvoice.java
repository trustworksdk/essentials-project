package com.example.billing.use_cases.issue_invoice;

import com.example.billing.types.InvoiceId;

public record IssueInvoice(InvoiceId invoiceId, long amountMinor) {
}
