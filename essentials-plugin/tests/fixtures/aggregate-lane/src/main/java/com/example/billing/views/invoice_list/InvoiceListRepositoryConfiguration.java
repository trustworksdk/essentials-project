package com.example.billing.views.invoice_list;

import dk.trustworks.essentials.components.document_db.DocumentDbRepository;
import dk.trustworks.essentials.components.document_db.DocumentDbRepositoryFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** Repository wiring for this view slice's read model. */
@Configuration
public class InvoiceListRepositoryConfiguration {

    @Bean
    public DocumentDbRepository<InvoiceListView, String> invoiceListRepository(DocumentDbRepositoryFactory factory) {
        var repository = factory.createForStringId(InvoiceListView.class);
        repository.addIndexByPaths("billing_invoice_list_amount", "amountMinor");
        return repository;
    }
}
