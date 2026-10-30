package com.example.cart.baskets.views.basket_list

import com.example.cart.baskets.events.BasketOpened
import com.example.cart.baskets.use_cases.open_basket.OpenBasketDecider as Opener
import dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.processor.ViewEventProcessor
import dk.trustworks.essentials.components.foundation.messaging.MessageHandler

class BasketListProjection(private val opener: Opener) : ViewEventProcessor() {
    @MessageHandler
    fun on(e: BasketOpened) {
    }
}
