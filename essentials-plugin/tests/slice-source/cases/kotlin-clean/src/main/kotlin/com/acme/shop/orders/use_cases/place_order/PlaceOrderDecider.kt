package com.acme.shop.orders.use_cases.place_order

import com.acme.shop.orders.events.OrderEvent
import com.acme.shop.orders.events.OrderPlaced
import dk.trustworks.essentials.components.kotlin.eventsourcing.Decider

class PlaceOrderDecider : Decider<PlaceOrder, OrderEvent> {
    override fun handle(cmd: PlaceOrder, events: List<OrderEvent>): OrderEvent? {
        if (events.any { it is OrderPlaced }) return null
        return OrderPlaced(cmd.id, cmd.sku, cmd.quantity)
    }

    override fun canHandle(cmd: Any): Boolean = cmd is PlaceOrder
}
