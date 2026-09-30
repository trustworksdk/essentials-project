package com.acme.shipping.events;

public record OrderShipped(String orderId) implements ShippingEvent {
}
