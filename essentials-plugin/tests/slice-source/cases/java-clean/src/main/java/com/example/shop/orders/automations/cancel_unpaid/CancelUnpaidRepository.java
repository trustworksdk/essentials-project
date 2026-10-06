package com.example.shop.orders.automations.cancel_unpaid;

import com.example.shop.orders.types.OrderId;

import java.util.List;

public interface CancelUnpaidRepository {
    void remember(OrderId id);

    List<OrderId> overdue();
}
