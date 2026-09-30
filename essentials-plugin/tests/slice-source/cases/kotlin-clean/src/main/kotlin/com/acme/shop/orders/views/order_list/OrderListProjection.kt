package com.acme.shop.orders.views.order_list

import com.acme.shop.orders.config.OrdersConfiguration
import com.acme.shop.orders.events.OrderPlaced as Placed
import dk.trustworks.essentials.components.document_db.Version
import dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.eventstream.AggregateType
import dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.processor.ViewEventProcessor
import dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.processor.ViewEventProcessorDependencies
import dk.trustworks.essentials.components.foundation.messaging.MessageHandler
import dk.trustworks.essentials.components.foundation.messaging.queue.OrderedMessage
import org.springframework.stereotype.Service

/**
 * TRAP: KDoc naming a handler — `@MessageHandler fun on(event: OrderShipped)` — is not one.
 */
@Service
class OrderListProjection(
    dependencies: ViewEventProcessorDependencies,
    private val repository: OrderListRepository,
) : ViewEventProcessor(dependencies) {

    override fun getProcessorName(): String = "OrderListProjection"

    override fun reactsToEventsRelatedToAggregateTypes(): List<AggregateType> =
        listOf(OrdersConfiguration.AGGREGATE_TYPE)

    /** RULE (11(b)): `Placed` is an import alias; the declared type is OrderPlaced. */
    @MessageHandler
    fun on(event: Placed, message: OrderedMessage) {
        val label = "${if (event.quantity > 1) "}" else "{"} @MessageHandler fun on(e: Bogus) {"
        repository.save(OrderListView(event.id, "PLACED", label), Version(message.order))
    }

    /** RULE (11(b)): a fully-qualified parameter type. */
    @MessageHandler
    fun on(event: com.acme.shop.orders.events.OrderCancelled, message: OrderedMessage) {
        val row = repository.findById(event.id) ?: return
        row.status = """CANCELLED ${'$'}{"}"}"""
        repository.update(row, Version(message.order))
    }
}
