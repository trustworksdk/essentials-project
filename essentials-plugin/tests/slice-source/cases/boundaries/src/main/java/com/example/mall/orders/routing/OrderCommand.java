package com.example.mall.orders.routing;

import com.example.mall.orders.types.OrderId;

public interface OrderCommand {
    OrderId id();
}
