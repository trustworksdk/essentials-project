package com.acme.shop.orders.use_cases.cancel_order

import com.acme.shop.orders.events.OrderCancelled
import com.acme.shop.orders.events.OrderEvent
import dk.trustworks.essentials.components.kotlin.eventsourcing.Decider

class CancelOrderDecider : Decider<CancelOrder, OrderEvent> {
    override fun handle(cmd: CancelOrder, events: List<OrderEvent>): OrderEvent? =
        if (events.isEmpty()) null else OrderCancelled(cmd.id, cmd.reason)

    override fun canHandle(cmd: Any): Boolean = cmd is CancelOrder
}
