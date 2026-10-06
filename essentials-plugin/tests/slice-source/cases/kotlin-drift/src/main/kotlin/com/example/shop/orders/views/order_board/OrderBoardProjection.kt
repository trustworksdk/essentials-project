package com.example.shop.orders.views.order_board

import com.example.shop.orders.events.OrderPlaced as Placed
import com.example.shop.orders.events.OrderShipped as Shipped
import dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.processor.ViewEventProcessor
import dk.trustworks.essentials.components.foundation.messaging.MessageHandler

class OrderBoardProjection : ViewEventProcessor() {

    /** RULE (the rename trap): `Placed` is OrderPlaced, which the manifest declares — no finding. */
    @MessageHandler
    fun on(event: Placed) {
    }

    /** FINDING (11(b)): `Shipped` is OrderShipped, which the manifest does not declare. Report the declared name. */
    @MessageHandler
    fun on(event: Shipped) {
    }
}
