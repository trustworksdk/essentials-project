package {{packagePath}}.orders.automations.screen_order

import dk.trustworks.essentials.components.document_db.Version
import dk.trustworks.essentials.components.document_db.VersionedEntity
import dk.trustworks.essentials.components.document_db.annotations.DocumentEntity
import dk.trustworks.essentials.components.document_db.annotations.Id
import java.time.OffsetDateTime
import java.time.ZoneOffset.UTC

/**
 * Process state for one order's screening: what has happened and what may happen next.
 * One row per order, keyed by the order id, so `version` can carry that order stream's `EventOrder`.
 */
@DocumentEntity("orders_screen_order_todo")
data class ScreenOrderTodo(
    @Id val orderId: String,
    var cancelRequested: Boolean = false,
    var closed: Boolean = false,
    override var version: Version = Version.NOT_SAVED_YET,
    override var lastUpdated: OffsetDateTime = OffsetDateTime.now(UTC)
) : VersionedEntity<String, ScreenOrderTodo> {

    /** The process rule: cancel at most once, and never an order that is already closed. */
    fun mayRequestCancel(): Boolean = !cancelRequested && !closed
}
