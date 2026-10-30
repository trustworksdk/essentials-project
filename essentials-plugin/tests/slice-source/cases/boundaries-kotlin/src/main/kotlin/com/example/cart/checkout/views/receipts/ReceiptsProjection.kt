package com.example.cart.checkout.views.receipts

import com.example.cart.stores.aggregates.Store
import com.example.cart.baskets.config.BasketRules
import com.example.cart.baskets.config.roundCents
import com.example.cart.baskets.events.BasketOpened
import dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.processor.ViewEventProcessor
import dk.trustworks.essentials.components.foundation.messaging.MessageHandler

class ReceiptsProjection : ViewEventProcessor() {
    fun reactsToEventsRelatedToAggregateTypes() = listOf(Store.AGGREGATE_TYPE)

    @MessageHandler
    fun on(e: BasketOpened) {
        val limit = BasketRules.MAX_ITEMS
        val cents = roundCents(105)
    }
}
