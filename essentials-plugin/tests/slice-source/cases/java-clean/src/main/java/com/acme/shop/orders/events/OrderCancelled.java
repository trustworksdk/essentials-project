package com.acme.shop.orders.events;

import com.acme.shop.orders.types.OrderId;

public record OrderCancelled(OrderId id, String reason) implements OrderEvent {
}
