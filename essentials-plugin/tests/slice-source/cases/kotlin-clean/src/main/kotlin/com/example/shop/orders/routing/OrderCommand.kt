package com.example.shop.orders.routing

import com.example.shop.orders.types.OrderId

interface OrderCommand {
    val id: OrderId
}
