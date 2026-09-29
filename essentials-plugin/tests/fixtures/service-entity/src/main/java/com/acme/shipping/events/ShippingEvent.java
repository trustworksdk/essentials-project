package com.acme.shipping.events;

/** The BC's public surface. Bus-delivered integration facts — never appended to a stream. */
public sealed interface ShippingEvent permits ShippingOrderRegistered, OrderShipped {
    String orderId();
}
