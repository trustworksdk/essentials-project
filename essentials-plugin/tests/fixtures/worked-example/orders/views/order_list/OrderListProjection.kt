package {{packagePath}}.orders.views.order_list

import {{packagePath}}.orders.events.OrderCancelled
import {{packagePath}}.orders.events.OrderPlaced
import {{packagePath}}.orders.types.OrderStatus
import dk.trustworks.essentials.components.document_db.Version
import dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.eventstream.AggregateType
import dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.processor.ViewEventProcessor
import dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.processor.ViewEventProcessorDependencies
import dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.types.GlobalEventOrder
import dk.trustworks.essentials.components.foundation.messaging.MessageHandler
import dk.trustworks.essentials.components.foundation.messaging.queue.OrderedMessage
import org.springframework.stereotype.Service

/**
 * VIEW slice — projects the Orders stream into the [OrderListView] read model. A view never
 * produces events.
 *
 * `ViewEventProcessor`: asynchronous and eventually consistent, which is right for a list screen.
 * Each handler takes [OrderedMessage] because `message.order` is the event's `EventOrder`: it is
 * stored as the row's `version`, and an event whose order is not newer than that is a replay.
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
        if (repository.existsById(event.id)) return          // replay — the row already exists
        repository.save(
            OrderListView(event.id, event.sku, event.quantity, OrderStatus.PLACED),
            Version(message.order)
        )
    }

    @MessageHandler
    fun on(event: OrderCancelled, message: OrderedMessage) {
        val row = repository.findById(event.id) ?: return
        if (row.version.value >= message.order) return         // replay — already applied
        row.status = OrderStatus.CANCELLED
        row.cancelReason = event.reason
        repository.update(row, Version(message.order))
    }

    /** Called once per subscribed aggregate type; this view subscribes to one, so wipe it all. */
    override fun onSubscriptionsReset(
        aggregateType: AggregateType,
        resubscribeFromAndIncluding: GlobalEventOrder
    ) {
        repository.deleteAll()
    }
}
