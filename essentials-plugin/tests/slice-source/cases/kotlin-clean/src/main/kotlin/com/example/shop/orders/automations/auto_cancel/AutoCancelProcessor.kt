package com.example.shop.orders.automations.auto_cancel

import com.example.shop.orders.events.OrderPlaced as Placed
import com.example.shop.orders.use_cases.cancel_order.CancelOrder
import dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.eventstream.AggregateType
import dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.processor.EventProcessor
import dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.processor.EventProcessorDependencies
import dk.trustworks.essentials.components.foundation.messaging.MessageHandler
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Service

@Service
class AutoCancelProcessor(dependencies: EventProcessorDependencies) : EventProcessor(dependencies) {
    private val pending = mutableSetOf<Placed>()

    override fun getProcessorName() = "AutoCancelProcessor"

    override fun reactsToEventsRelatedToAggregateTypes() = listOf(AggregateType.of("Orders"))

    @MessageHandler
    fun on(event: Placed) {
        pending += event
    }

    /** RULE (dispatches): a local `val` resolves to the type it constructs. */
    @Scheduled(cron = "0 0 3 * * *")
    fun sweep() {
        for (event in pending) {
            val cmd = CancelOrder(event.id, "no payment by 03:00")
            commandBus.sendAndDontWait(cmd)
        }
    }
}
