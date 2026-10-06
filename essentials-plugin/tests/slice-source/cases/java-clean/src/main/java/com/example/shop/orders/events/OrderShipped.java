package com.example.shop.orders.events;

import com.example.shop.orders.types.OrderId;

public record OrderShipped(OrderId id) implements OrderEvent {
}
