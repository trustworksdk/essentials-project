package com.example.mall.orders.use_cases.place_order;

import com.example.mall.orders.routing.OrderCommand;
import com.example.mall.orders.types.OrderId;

public record PlaceOrder(OrderId id, int lines) implements OrderCommand {
}
