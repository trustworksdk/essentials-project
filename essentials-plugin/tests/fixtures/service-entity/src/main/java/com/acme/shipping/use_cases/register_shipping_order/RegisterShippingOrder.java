package com.acme.shipping.use_cases.register_shipping_order;

import com.acme.shipping.types.OrderId;

public record RegisterShippingOrder(OrderId orderId, String destination) {
}
