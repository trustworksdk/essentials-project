package {{packagePath}}.orders.views.order_list

import {{packagePath}}.orders.types.OrderId
import {{packagePath}}.orders.types.OrderStatus
import dk.trustworks.essentials.components.document_db.Version
import dk.trustworks.essentials.components.document_db.VersionedEntity
import dk.trustworks.essentials.components.document_db.annotations.DocumentEntity
import dk.trustworks.essentials.components.document_db.annotations.Id
import dk.trustworks.essentials.components.document_db.annotations.Indexed
import java.time.OffsetDateTime
import java.time.ZoneOffset.UTC

/**
 * Read model row for the order-list view. Owned by this view slice — no other slice reads or
 * writes it.
 *
 * `version` carries the `EventOrder` of the last event projected into the row, which is what makes
 * [OrderListProjection] idempotent under redelivery. `version` and `lastUpdated` must stay `var`,
 * and keep those names: the DocumentDB reflection layer looks them up by name.
 */
@DocumentEntity("orders_order_list")
data class OrderListView(
    @Id val orderId: OrderId,
    val sku: String,
    val quantity: Int,
    @Indexed var status: OrderStatus,
    var cancelReason: String? = null,
    override var version: Version = Version.NOT_SAVED_YET,
    override var lastUpdated: OffsetDateTime = OffsetDateTime.now(UTC)
) : VersionedEntity<OrderId, OrderListView>
