package com.example.shipping.events;

/** Integration facts published on the EventBus. */
public sealed interface ShippingEvent permits ShippingOrderRegistered, OrderShipped {
    String orderId();
}
