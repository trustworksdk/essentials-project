package com.example.shop.orders.events;

import com.example.shop.orders.types.OrderId;

/** RULE: `permits` names subtypes, never supertypes. */
public sealed interface OrderEvent permits OrderPlaced, OrderCancelled, OrderShipped {
    OrderId id();
}
