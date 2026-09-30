package com.acme.multi.inventory.entities;

import dk.trustworks.essentials.components.document_db.DelegatingDocumentDbRepository;
import dk.trustworks.essentials.components.document_db.DocumentDbRepositoryFactory;
import org.springframework.stereotype.Repository;

@Repository
public class StockItems extends DelegatingDocumentDbRepository<StockItem, String> {
    public StockItems(DocumentDbRepositoryFactory factory) {
        super(factory.createForStringId(StockItem.class));
    }
}
