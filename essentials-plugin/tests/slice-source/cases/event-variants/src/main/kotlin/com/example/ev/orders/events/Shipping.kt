package com.example.ev.orders.events

// two top-level variants in one file: splitting keeps both class names
data class OrderShipped(override val id: String) : OrderEvent

data object OrderDelivered : OrderEvent {
    override val id: String = "delivered"
}

// a helper, not an event: no supertype in events/
class ShippingLabel(val text: String)
