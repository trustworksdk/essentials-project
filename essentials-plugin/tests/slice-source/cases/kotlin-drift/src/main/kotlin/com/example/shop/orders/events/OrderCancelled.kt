package com.example.shop.orders.events

import com.example.shop.orders.types.OrderId

data class OrderCancelled(val orderId: OrderId)
