package com.acme.billing.views.invoice_list;

public record InvoiceListView(String invoiceId, boolean paid, long amountMinor) {
}
