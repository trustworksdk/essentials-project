package com.example.shop.orders.automations.notify_customer

import com.example.shop.orders.events.OrderPlaced as Placed
import com.example.shop.orders.events.OrderCancelled
import dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.processor.EventProcessor
import dk.trustworks.essentials.components.foundation.messaging.MessageHandler

class NotifyCustomerProcessor : EventProcessor() {

    /**
     * FINDING (11(b)): the manifest declares `consumes: [Placed]` — the ALIAS, not the type. A reader comparing
     * the written name would call this clean; the declared type OrderPlaced is not in the manifest.
     */
    @MessageHandler
    fun on(event: Placed) {
    }

    /** RULE: a fully-qualified Kotlin parameter, declared — no finding. */
    @MessageHandler
    fun on(event: com.example.shop.orders.events.OrderCancelled) {
    }
}
