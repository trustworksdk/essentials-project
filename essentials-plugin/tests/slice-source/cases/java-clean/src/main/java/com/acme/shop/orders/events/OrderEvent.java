package com.acme.shop.orders.events;

import com.acme.shop.orders.types.OrderId;

/** RULE: `permits` names subtypes, never supertypes. */
public sealed interface OrderEvent permits OrderPlaced, OrderCancelled, OrderShipped {
    OrderId id();
}
