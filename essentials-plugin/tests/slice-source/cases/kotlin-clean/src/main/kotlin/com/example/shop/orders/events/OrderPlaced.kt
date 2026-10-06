package com.example.shop.orders.events

import com.example.shop.orders.types.OrderId

data class OrderPlaced(
    override val id: OrderId,
    val sku: String,
    val quantity: Int,
) : OrderEvent
