package com.acme.shop.orders.automations.fulfillment

import dk.trustworks.essentials.components.document_db.DelegatingDocumentDbRepository
import dk.trustworks.essentials.components.document_db.DocumentDbRepositoryFactory
import org.springframework.stereotype.Repository

/**
 * Persistence for this automation's process state. Owned by THIS slice — no other slice reads it.
 *
 * Delete this file (and the TodoList) if the automation is stateless.
 */
@Repository
class FulfillmentRepository(factory: DocumentDbRepositoryFactory) :
    DelegatingDocumentDbRepository<FulfillmentTodoList, String>(
        factory.createForStringId(FulfillmentTodoList::class)
    )
