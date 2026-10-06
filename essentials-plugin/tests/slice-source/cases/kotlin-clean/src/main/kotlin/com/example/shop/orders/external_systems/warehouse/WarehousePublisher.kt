package com.example.shop.orders.external_systems.warehouse

import com.example.shop.orders.config.OrdersConfiguration
import com.example.shop.orders.events.OrderCancelled
import com.example.shop.orders.events.OrderPlaced
import dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.eventstream.AggregateType
import dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.processor.EventProcessor
import dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.processor.EventProcessorDependencies
import dk.trustworks.essentials.components.foundation.messaging.MessageHandler
import org.springframework.stereotype.Service

/** RULE (11(b)): a file-private typealias resolves to its target. */
private typealias Placement = OrderPlaced

@Service
class WarehousePublisher(dependencies: EventProcessorDependencies) : EventProcessor(dependencies) {
    override fun getProcessorName(): String = "WarehousePublisher"

    override fun reactsToEventsRelatedToAggregateTypes(): List<AggregateType> = listOf(OrdersConfiguration.AGGREGATE_TYPE)

    @MessageHandler
    fun `forward placement`(event: Placement) = println("placed ${event.id}")

    @MessageHandler
    fun on(event: OrderCancelled?) {
        println("cancelled '${event?.reason ?: "}"}'")
    }
}
