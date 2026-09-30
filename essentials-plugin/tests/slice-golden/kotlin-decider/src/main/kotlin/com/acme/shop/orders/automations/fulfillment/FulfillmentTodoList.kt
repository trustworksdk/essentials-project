package com.acme.shop.orders.automations.fulfillment

import dk.trustworks.essentials.components.document_db.Version
import dk.trustworks.essentials.components.document_db.VersionedEntity
import dk.trustworks.essentials.components.document_db.annotations.DocumentEntity
import dk.trustworks.essentials.components.document_db.annotations.Id
import java.time.OffsetDateTime
import java.time.ZoneOffset.UTC

/**
 * Explicit process state for the fulfillment automation — what has happened and what may happen next.
 *
 * Make the guards explicit (`canProceed()`) rather than scattering boolean conditions across
 * handlers: the guard is the process's rule, and it is the thing worth testing.
 *
 * A stateless automation (one event in, one command out) does not need this file — delete it and
 * the repository if the process has no memory.
 */
@DocumentEntity("orders_fulfillment_todo")
data class FulfillmentTodoList(
    @Id val id: String,
    var started: Boolean = false,
    var dispatched: Boolean = false,
    var completed: Boolean = false,
    var attempts: Int = 0,
    override var version: Version = Version.NOT_SAVED_YET,
    override var lastUpdated: OffsetDateTime = OffsetDateTime.now(UTC)
) : VersionedEntity<String, FulfillmentTodoList> {

    /** TODO: the real precondition for the next step. Bound the retries. */
    fun canProceed(): Boolean = started && !dispatched && attempts < 3
}
