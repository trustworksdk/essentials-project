package com.acme.shipping.use_cases.register_shipping_order;

import com.acme.shipping.types.OrderId;

/** The command IS the request body (R2). Slice-private. */
public record RegisterShippingOrder(OrderId orderId, String destination) {
}
