package com.acme.shop.orders.views.order_list

import com.acme.shop.orders.types.OrderId
import dk.trustworks.essentials.components.document_db.Version
import dk.trustworks.essentials.components.document_db.VersionedEntity
import dk.trustworks.essentials.components.document_db.annotations.DocumentEntity
import dk.trustworks.essentials.components.document_db.annotations.Id
import dk.trustworks.essentials.components.document_db.annotations.Indexed
import java.time.OffsetDateTime
import java.time.ZoneOffset.UTC

/**
 * Read model for the order_list view slice. Owned by THIS slice — no other slice reads or writes it.
 *
 * `version` carries the projected event's `EventOrder`, which is what makes the projection
 * idempotent under redelivery: an event whose order is not newer than the stored version is a
 * replay and must not be applied twice.
 *
 * `version` and `lastUpdated` MUST be `var` — the DocumentDB reflection layer fails at build time
 * on read-only properties, and the field names are hardcoded, so do not rename them.
 */
@DocumentEntity("orders_order_list")
data class OrderListView(
    @Id val orderId: OrderId,
    // TODO: replace with the fields this view serves
    @Indexed var status: String,
    override var version: Version = Version.NOT_SAVED_YET,
    override var lastUpdated: OffsetDateTime = OffsetDateTime.now(UTC)
) : VersionedEntity<OrderId, OrderListView>
