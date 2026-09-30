package com.acme.shop.orders.automations.cancel_unpaid;

import com.acme.shop.orders.types.OrderId;

import java.util.List;

public interface CancelUnpaidRepository {
    void remember(OrderId id);

    List<OrderId> overdue();
}
