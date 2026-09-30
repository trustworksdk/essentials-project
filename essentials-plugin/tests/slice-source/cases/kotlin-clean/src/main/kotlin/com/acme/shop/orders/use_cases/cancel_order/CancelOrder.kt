package com.acme.shop.orders.use_cases.cancel_order

import com.acme.shop.orders.routing.OrderCommand
import com.acme.shop.orders.types.OrderId

data class CancelOrder(override val id: OrderId, val reason: String) : OrderCommand
