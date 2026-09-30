package com.acme.billing.automations.recurring_billing;

import dk.trustworks.essentials.components.document_db.DocumentDbRepository;
import dk.trustworks.essentials.components.document_db.DocumentDbRepositoryFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** Persistence for this automation's process state. The bean method name differs from the class name on purpose. */
@Configuration
public class RecurringBillingRepository {

    @Bean
    public DocumentDbRepository<RecurringBillingTodo, String> recurringBillingTodoRepository(DocumentDbRepositoryFactory factory) {
        return factory.createForStringId(RecurringBillingTodo.class);
    }
}
