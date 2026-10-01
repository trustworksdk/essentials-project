package com.example.shipping.events;

import com.example.shipping.use_cases.register_shipping_order.RegisterShippingOrder;

public record ShippingOrderRegistered(String orderId, String destination) implements ShippingEvent {

    public static ShippingOrderRegistered from(RegisterShippingOrder cmd) {
        return new ShippingOrderRegistered(cmd.orderId().value(), cmd.destination());
    }
}
