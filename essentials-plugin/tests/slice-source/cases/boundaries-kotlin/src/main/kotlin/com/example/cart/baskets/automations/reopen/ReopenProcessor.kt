package com.example.cart.baskets.automations.reopen

import com.example.cart.baskets.events.BasketOpened
import com.example.cart.baskets.use_cases.open_basket.OpenBasket
import dk.trustworks.essentials.components.foundation.commandbus.CommandBus
import dk.trustworks.essentials.components.foundation.messaging.MessageHandler

class ReopenProcessor(private val commandBus: CommandBus) {
    @MessageHandler
    fun on(e: BasketOpened) {
        commandBus.send(OpenBasket(e.id))
    }
}
