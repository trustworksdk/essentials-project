package com.example.billing;

import dk.trustworks.essentials.components.document_db.DocumentDbRepositoryFactory;
import dk.trustworks.essentials.components.foundation.json.JSONSerializer;
import dk.trustworks.essentials.components.foundation.transaction.jdbi.HandleAwareUnitOfWork;
import dk.trustworks.essentials.components.foundation.transaction.jdbi.HandleAwareUnitOfWorkFactory;
import org.jdbi.v3.core.Jdbi;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

// S5: the document store's factory is not auto-configured.
@Configuration
public class DocumentDbConfig {
    @Bean
    public DocumentDbRepositoryFactory documentDbRepositoryFactory(Jdbi jdbi,
                                                                   HandleAwareUnitOfWorkFactory<? extends HandleAwareUnitOfWork> unitOfWorkFactory,
                                                                   JSONSerializer jsonSerializer) {
        return new DocumentDbRepositoryFactory(jdbi, unitOfWorkFactory, jsonSerializer);
    }
}
