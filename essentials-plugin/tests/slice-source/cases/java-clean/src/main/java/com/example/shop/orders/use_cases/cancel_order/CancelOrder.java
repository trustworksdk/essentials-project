package com.example.shop.orders.use_cases.cancel_order;

import com.example.shop.orders.routing.OrderCommand;
import com.example.shop.orders.types.OrderId;

public record CancelOrder(OrderId id, String reason) implements OrderCommand {
}
