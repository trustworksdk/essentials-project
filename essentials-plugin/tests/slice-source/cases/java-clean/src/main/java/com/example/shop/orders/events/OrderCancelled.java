package com.example.shop.orders.events;

import com.example.shop.orders.types.OrderId;

public record OrderCancelled(OrderId id, String reason) implements OrderEvent {
}
