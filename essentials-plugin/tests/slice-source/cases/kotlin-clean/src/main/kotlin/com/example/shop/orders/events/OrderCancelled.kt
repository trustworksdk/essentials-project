package com.example.shop.orders.events

import com.example.shop.orders.types.OrderId

data class OrderCancelled(override val id: OrderId, val reason: String?) : OrderEvent
