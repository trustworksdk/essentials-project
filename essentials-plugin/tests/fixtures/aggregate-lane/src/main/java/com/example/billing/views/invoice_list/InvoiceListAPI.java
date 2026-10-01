package com.example.billing.views.invoice_list;

import dk.trustworks.essentials.components.document_db.DocumentDbRepository;
import dk.trustworks.essentials.components.document_db.postgresql.DbType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/** Read API for the invoice list. */
@RestController
@RequestMapping("/api/billing/invoices")
public class InvoiceListAPI {

    private final DocumentDbRepository<InvoiceListView, String> repository;

    public InvoiceListAPI(DocumentDbRepository<InvoiceListView, String> repository) {
        this.repository = repository;
    }

    @GetMapping
    public List<InvoiceListView> list(@RequestParam(defaultValue = "100") int limit) {
        return repository.queryBuilder().limit(limit).find();
    }

    @GetMapping(params = "paid=true")
    public List<InvoiceListView> paidInvoices() {
        return repository.queryBuilder()
                         .where(repository.condition().eq("paid", true, DbType.BOOLEAN))
                         .find();
    }

    @GetMapping(params = {"minAmount", "maxAmount"})
    public List<InvoiceListView> byAmount(@RequestParam long minAmount, @RequestParam long maxAmount) {
        return repository.queryBuilder()
                         .where(repository.condition()
                                          .gte("amountMinor", minAmount, DbType.BIGINT)
                                          .lte("amountMinor", maxAmount, DbType.BIGINT))
                         .find();
    }
}
