package com.example.shop.orders.views.order_list

import com.example.shop.orders.types.OrderId
import dk.trustworks.essentials.components.document_db.Version
import dk.trustworks.essentials.components.document_db.VersionedEntity
import dk.trustworks.essentials.components.document_db.annotations.DocumentEntity
import dk.trustworks.essentials.components.document_db.annotations.Id
import dk.trustworks.essentials.components.document_db.annotations.Indexed
import java.time.OffsetDateTime

@DocumentEntity("orders_order_list")
data class OrderListView(
    @Id val orderId: OrderId,
    @Indexed var status: String,
    var label: String,
    override var version: Version = Version.NOT_SAVED_YET,
    override var lastUpdated: OffsetDateTime = OffsetDateTime.now(),
) : VersionedEntity<OrderId, OrderListView> {
    companion object {
        const val TABLE = "orders_order_list"
    }
}
