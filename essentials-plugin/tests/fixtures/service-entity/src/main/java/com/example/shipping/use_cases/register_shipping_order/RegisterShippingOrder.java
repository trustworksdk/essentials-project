package com.example.shipping.use_cases.register_shipping_order;

import com.example.shipping.types.OrderId;

public record RegisterShippingOrder(OrderId orderId, String destination) {
}
