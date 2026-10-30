package com.example.cart.baskets.use_cases.open_basket

import com.example.cart.baskets.events.BasketEvent
import com.example.cart.baskets.events.BasketOpened
import dk.trustworks.essentials.components.eventsourced.aggregates.decider.Decider

class OpenBasketDecider : Decider<OpenBasket, BasketEvent> {
    fun handle(cmd: OpenBasket, events: List<BasketEvent>): BasketEvent? = BasketOpened(cmd.id)
}
