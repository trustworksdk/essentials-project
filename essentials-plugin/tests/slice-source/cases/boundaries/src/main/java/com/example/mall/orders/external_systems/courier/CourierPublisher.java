package com.example.mall.orders.external_systems.courier;

import com.example.mall.orders.views.order_board.OrderBoardRow;

public class CourierPublisher {
    String label(OrderBoardRow row) {
        return row.id();
    }
}
