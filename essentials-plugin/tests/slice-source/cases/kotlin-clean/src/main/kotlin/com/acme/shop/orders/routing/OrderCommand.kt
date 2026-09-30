package com.acme.shop.orders.routing

import com.acme.shop.orders.types.OrderId

interface OrderCommand {
    val id: OrderId
}
