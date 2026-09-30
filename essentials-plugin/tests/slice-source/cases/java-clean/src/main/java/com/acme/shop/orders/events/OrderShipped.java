package com.acme.shop.orders.events;

import com.acme.shop.orders.types.OrderId;

public record OrderShipped(OrderId id) implements OrderEvent {
}
