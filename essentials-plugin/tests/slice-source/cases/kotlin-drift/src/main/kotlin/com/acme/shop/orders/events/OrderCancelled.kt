package com.acme.shop.orders.events

import com.acme.shop.orders.types.OrderId

data class OrderCancelled(val orderId: OrderId)
