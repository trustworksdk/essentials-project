package com.example.shop.orders.views.order_list

import com.example.shop.orders.events.OrderPlaced
import dk.trustworks.essentials.components.document_db.Version
import dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.eventstream.AggregateType
import dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.processor.ViewEventProcessor
import dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.processor.ViewEventProcessorDependencies
import dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.types.GlobalEventOrder
import dk.trustworks.essentials.components.foundation.messaging.MessageHandler
import dk.trustworks.essentials.components.foundation.messaging.queue.OrderedMessage
import org.springframework.stereotype.Service

/**
 * Projector for the order_list view slice — events in, read model out. A view slice NEVER produces
 * events (rules/slice-design.md § The four slice kinds).
 *
 * PROCESSOR CHOICE — the load-bearing decision for a view slice:
 *   - `ViewEventProcessor` (this template): async, low latency, eventually consistent, replayable.
 *     Correct for almost every read model.
 *   - `InTransactionEventProcessor`: synchronous, strongly consistent with the event append.
 *     Use ONLY when the read model must be current the moment the command API returns
 *     (and for transaction-time uniqueness enforcement).
 *   - Plain `EventProcessor`: never for a view — that is for external integrations.
 *
 * WHY EVERY HANDLER HERE TAKES `OrderedMessage`: the second parameter is optional to the dispatcher —
 * a single-argument `@MessageHandler` is invoked perfectly well. It is a *correctness* requirement
 * for a projection specifically: `message.order` is the `EventOrder` compared against the row's
 * stored `version`, and that comparison is what makes the projection idempotent under redelivery.
 * Drop the parameter here and you have no way to detect a replay. Handlers that need no ordering
 * (a fire-and-forget publisher, say) may omit it.
 */
@Service
class OrderListProjection(
    dependencies: ViewEventProcessorDependencies,
    private val repository: OrderListRepository
) : ViewEventProcessor(dependencies) {

    override fun getProcessorName(): String = "OrderListProjection"

    override fun reactsToEventsRelatedToAggregateTypes(): List<AggregateType> =
        listOf(AggregateType.of("Orders"))

    @MessageHandler
    fun on(event: OrderPlaced, message: OrderedMessage) {
        // version = EventOrder. NOT repository.update(entity) — that auto-increments, which is CRUD
        // semantics, not projection semantics, and loses idempotency under redelivery.
        // NOTE: use the Version(value) constructor. `Version.of()` does not exist.
        val existing = repository.findById(event.id)
        if (existing == null) {
            repository.save(
                OrderListView(orderId = event.id, status = "TODO"),
                Version(message.order)
            )
        } else {
            if (existing.version.value >= message.order) return   // replay — already applied
            existing.status = "TODO"
            repository.update(existing, Version(message.order))
        }
    }

    // TODO: one handler per event this view projects. Add each to slice.yaml `projections.from`.

    /**
     * Rebuild support: wipe the read model so a subscription reset replays cleanly.
     *
     * Called **once per subscribed [AggregateType]**, not once per reset. The blanket `deleteAll()`
     * below is only correct because this projection subscribes to a single aggregate type. If you
     * add a second type to `reactsToEventsRelatedToAggregateTypes()`, delete only the rows belonging
     * to [aggregateType] here — otherwise resetting one subscription wipes the other's rows and they
     * are never replayed.
     *
     * [resubscribeFromAndIncluding] is the [GlobalEventOrder] the replay restarts from; a partial
     * reset should delete only from that point forward rather than everything.
     */
    override fun onSubscriptionsReset(
        aggregateType: AggregateType,
        resubscribeFromAndIncluding: GlobalEventOrder
    ) {
        repository.deleteAll()
    }
}
