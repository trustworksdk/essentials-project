package com.example.shop.orders.use_cases.place_order

import com.example.shop.orders.routing.OrderCommand
import com.example.shop.orders.types.OrderId

data class PlaceOrder(override val id: OrderId, val sku: String, val quantity: Int) : OrderCommand
