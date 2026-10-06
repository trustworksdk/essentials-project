package com.example.shop.orders.use_cases.cancel_order

import com.example.shop.orders.routing.OrderCommand
import com.example.shop.orders.types.OrderId

data class CancelOrder(override val id: OrderId, val reason: String) : OrderCommand
