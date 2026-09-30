package com.acme.shop.orders.events;

import com.acme.shop.orders.types.CustomerId;
import com.acme.shop.orders.types.OrderId;

/** RULE (messages): identity is the component typed <Aggregate>Id, not the first one ending in Id. */
public record OrderPlaced(CustomerId customerId, OrderId id, String sku, int quantity) implements OrderEvent {
}
