package com.example.shop.orders.events

import com.example.shop.orders.types.OrderId

sealed interface OrderEvent {
    val id: OrderId
}
