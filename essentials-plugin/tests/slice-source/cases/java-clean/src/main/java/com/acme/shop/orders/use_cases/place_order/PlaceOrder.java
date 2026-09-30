package com.acme.shop.orders.use_cases.place_order;

import com.acme.shop.orders.routing.OrderCommand;
import com.acme.shop.orders.types.CustomerId;
import com.acme.shop.orders.types.OrderId;

public record PlaceOrder(OrderId id, CustomerId customerId, String sku, int quantity) implements OrderCommand {
}
