package com.example.orders.events;

public record OrderCancelled(String orderId, String reason) implements OrderEvent {
}
