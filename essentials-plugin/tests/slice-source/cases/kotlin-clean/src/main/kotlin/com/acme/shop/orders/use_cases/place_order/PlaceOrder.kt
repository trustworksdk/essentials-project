package com.acme.shop.orders.use_cases.place_order

import com.acme.shop.orders.routing.OrderCommand
import com.acme.shop.orders.types.OrderId

data class PlaceOrder(override val id: OrderId, val sku: String, val quantity: Int) : OrderCommand
