package com.example.mall.orders.events;

import com.example.mall.orders.types.OrderId;
import com.example.mall.orders.use_cases.place_order.PlaceOrder;

public record OrderPlaced(OrderId id, int lines) implements OrderEvent {
    public static OrderPlaced of(PlaceOrder cmd) {
        return new OrderPlaced(cmd.id(), cmd.lines());
    }
}
