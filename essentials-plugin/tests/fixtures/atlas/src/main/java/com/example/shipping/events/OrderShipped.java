package com.example.shipping.events;

public record OrderShipped(String orderId, String carrier) {
}
