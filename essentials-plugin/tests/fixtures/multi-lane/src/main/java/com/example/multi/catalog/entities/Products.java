package com.example.multi.catalog.entities;

import dk.trustworks.essentials.components.document_db.DelegatingDocumentDbRepository;
import dk.trustworks.essentials.components.document_db.DocumentDbRepositoryFactory;
import org.springframework.stereotype.Repository;

@Repository
public class Products extends DelegatingDocumentDbRepository<Product, String> {
    public Products(DocumentDbRepositoryFactory factory) {
        super(factory.createForStringId(Product.class));
    }
}
