package com.example.shop.orders.views.order_list

import com.example.shop.orders.types.OrderId
import dk.trustworks.essentials.components.document_db.DelegatingDocumentDbRepository
import dk.trustworks.essentials.components.document_db.DocumentDbRepositoryFactory
import org.springframework.stereotype.Repository

/**
 * Repository for THIS view slice's read model.
 *
 * Extends [DelegatingDocumentDbRepository] so slice-specific queries live here rather than leaking
 * into the projector or the API handler. Indexes are added once, in `init` — never per query.
 *
 * `factory.create(...)` is correct because the `@Id` is a Kotlin `StringValueType`. If your id is a
 * plain `String` use `createForStringId(...)`; for any other id type use
 * `createForCompositeId(..., idSerializer)`.
 */
@Repository
class OrderListRepository(factory: DocumentDbRepositoryFactory) :
    DelegatingDocumentDbRepository<OrderListView, OrderId>(factory.create(OrderListView::class)) {

    init {
        // TODO: add the indexes this view's queries actually need.
        // addIndexByPaths("orders_order_list_status", "status")
    }

    // TODO: slice-specific finders, e.g.
    // fun findByStatus(status: String): List<OrderListView> =
    //     find(queryBuilder().where(condition().eq("status", status)))
}
