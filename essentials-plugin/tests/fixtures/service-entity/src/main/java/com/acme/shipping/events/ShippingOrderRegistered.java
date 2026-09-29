package com.acme.shipping.events;

import com.acme.shipping.use_cases.register_shipping_order.RegisterShippingOrder;

public record ShippingOrderRegistered(String orderId, String destination) implements ShippingEvent {

    /**
     * FINDING (gate 8d), and the worse of the two: events/ is importable across bounded contexts,
     * so this drags a slice-private wire contract into every foreign consumer of the event.
     */
    public static ShippingOrderRegistered from(RegisterShippingOrder cmd) {
        return new ShippingOrderRegistered(cmd.orderId().value(), cmd.destination());
    }
}
