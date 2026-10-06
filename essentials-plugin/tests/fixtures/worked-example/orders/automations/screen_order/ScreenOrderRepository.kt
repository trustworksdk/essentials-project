package {{packagePath}}.orders.automations.screen_order

import dk.trustworks.essentials.components.document_db.DelegatingDocumentDbRepository
import dk.trustworks.essentials.components.document_db.DocumentDbRepositoryFactory
import org.springframework.stereotype.Repository

/** Persistence for this automation's process state. Owned by THIS slice — no other slice reads it. */
@Repository
class ScreenOrderRepository(factory: DocumentDbRepositoryFactory) :
    DelegatingDocumentDbRepository<ScreenOrderTodo, String>(factory.createForStringId(ScreenOrderTodo::class))
