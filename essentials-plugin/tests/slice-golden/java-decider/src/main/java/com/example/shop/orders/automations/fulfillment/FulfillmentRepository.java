package com.example.shop.orders.automations.fulfillment;

import dk.trustworks.essentials.components.document_db.DocumentDbRepository;
import dk.trustworks.essentials.components.document_db.DocumentDbRepositoryFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Persistence for this automation's process state. Owned by THIS slice — no other slice reads it.
 *
 * Delete this file (and the TodoList) if the automation is stateless.
 *
 * NAMING — this {@code @Configuration} class registers a bean under its own decapitalised name
 * ({@code fulfillmentRepository}), which is why the {@code @Bean} method below is
 * {@code fulfillmentTodoRepository} and not {@code fulfillmentRepository}. Renaming the method
 * to match the class produces a {@code BeanDefinitionOverrideException} at context startup that
 * fails every {@code @SpringBootTest} in the project. Keep the two names distinct.
 */
@Configuration
public class FulfillmentRepository {

    @Bean
    public DocumentDbRepository<FulfillmentTodoList, String> fulfillmentTodoRepository(
            DocumentDbRepositoryFactory factory) {
        return factory.createForStringId(FulfillmentTodoList.class);
    }
}
