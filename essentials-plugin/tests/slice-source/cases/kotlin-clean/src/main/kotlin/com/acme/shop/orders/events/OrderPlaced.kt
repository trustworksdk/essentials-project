package com.acme.shop.orders.events

import com.acme.shop.orders.types.OrderId

data class OrderPlaced(
    override val id: OrderId,
    val sku: String,
    val quantity: Int,
) : OrderEvent
