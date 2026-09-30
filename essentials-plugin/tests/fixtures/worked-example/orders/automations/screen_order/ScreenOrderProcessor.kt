package {{packagePath}}.orders.automations.screen_order

import {{packagePath}}.orders.events.OrderCancelled
import {{packagePath}}.orders.events.OrderPlaced as Placed
import {{packagePath}}.orders.use_cases.cancel_order.CancelOrder
import dk.trustworks.essentials.components.document_db.Version
import dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.eventstream.AggregateType
import dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.processor.EventProcessor
import dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.processor.EventProcessorDependencies
import dk.trustworks.essentials.components.foundation.messaging.MessageHandler
import dk.trustworks.essentials.components.foundation.messaging.queue.OrderedMessage
import org.springframework.stereotype.Service

/**
 * AUTOMATION slice — screens every placed order and cancels the ones over the screening limit.
 * `OrderPlaced -> [ScreenOrderTodo] -> CancelOrder`.
 *
 * Screening is a policy applied after the order was accepted, not an invariant of placing it, so it
 * lives here rather than in `PlaceOrderDecider`. The automation has no API; it is reached only by
 * events, and it changes state only by sending `cancel_order`'s command on the command bus.
 *
 * Every handler is idempotent: the Inbox redelivers, and the todo's `version` (the stream's
 * `EventOrder`) tells a replay from a new event.
 */
@Service
class ScreenOrderProcessor(
    dependencies: EventProcessorDependencies,
    private val todos: ScreenOrderRepository
) : EventProcessor(dependencies) {

    override fun getProcessorName(): String = "ScreenOrderProcessor"

    override fun reactsToEventsRelatedToAggregateTypes(): List<AggregateType> =
        listOf(AggregateType.of("Orders"))

    @MessageHandler
    fun on(event: Placed, message: OrderedMessage) {
        if (todos.existsById(event.id.value)) return               // replay — already screened
        val todo = ScreenOrderTodo(event.id.value)
        if (event.quantity > SCREENING_LIMIT && todo.mayRequestCancel()) {
            commandBus.sendAndDontWait(CancelOrder(event.id, "Quantity over the screening limit"))
            todo.cancelRequested = true
        }
        todos.save(todo, Version(message.order))
    }

    @MessageHandler
    fun on(event: OrderCancelled, message: OrderedMessage) {
        val todo = todos.findById(event.id.value) ?: return
        if (todo.version.value >= message.order) return             // replay — already applied
        todo.closed = true
        todos.update(todo, Version(message.order))
    }

    companion object {
        const val SCREENING_LIMIT = 100
    }
}
