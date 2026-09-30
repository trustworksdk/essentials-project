package com.acme.shop.orders.events

import com.acme.shop.orders.types.OrderId

sealed interface OrderEvent {
    val id: OrderId
}
