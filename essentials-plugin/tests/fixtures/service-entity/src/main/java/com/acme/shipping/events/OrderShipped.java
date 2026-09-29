package com.acme.shipping.events;

/** Clean: takes the fields it needs, names no command type. */
public record OrderShipped(String orderId) implements ShippingEvent {
}
