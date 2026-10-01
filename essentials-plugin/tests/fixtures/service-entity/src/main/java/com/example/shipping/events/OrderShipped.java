package com.example.shipping.events;

public record OrderShipped(String orderId) implements ShippingEvent {
}
