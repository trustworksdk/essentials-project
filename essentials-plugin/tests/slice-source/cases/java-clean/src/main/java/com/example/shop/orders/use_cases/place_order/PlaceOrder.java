package com.example.shop.orders.use_cases.place_order;

import com.example.shop.orders.routing.OrderCommand;
import com.example.shop.orders.types.CustomerId;
import com.example.shop.orders.types.OrderId;

public record PlaceOrder(OrderId id, CustomerId customerId, String sku, int quantity) implements OrderCommand {
}
