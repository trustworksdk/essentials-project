package {{packagePath}}.orders.views.order_list

import {{packagePath}}.orders.types.OrderId
import {{packagePath}}.orders.types.OrderStatus
import dk.trustworks.essentials.components.document_db.DelegatingDocumentDbRepository
import dk.trustworks.essentials.components.document_db.DocumentDbRepositoryFactory
import org.springframework.stereotype.Repository

/**
 * Repository for THIS view slice's read model. Slice-specific queries live here, not in the
 * projection or the API.
 *
 * `factory.create(...)` fits because the `@Id` is a Kotlin `StringValueType`.
 */
@Repository
class OrderListRepository(factory: DocumentDbRepositoryFactory) :
    DelegatingDocumentDbRepository<OrderListView, OrderId>(factory.create(OrderListView::class)) {

    fun findByStatus(status: OrderStatus): List<OrderListView> =
        queryBuilder()
            .where(condition().matching { OrderListView::status eq status })
            .find()
}
