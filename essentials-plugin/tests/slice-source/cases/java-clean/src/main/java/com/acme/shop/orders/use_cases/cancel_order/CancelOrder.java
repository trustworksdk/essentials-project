package com.acme.shop.orders.use_cases.cancel_order;

import com.acme.shop.orders.routing.OrderCommand;
import com.acme.shop.orders.types.OrderId;

public record CancelOrder(OrderId id, String reason) implements OrderCommand {
}
