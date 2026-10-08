package com.example.orders.events;

public record OrderPlaced(String orderId, String customerId, int quantity) implements OrderEvent {
}
