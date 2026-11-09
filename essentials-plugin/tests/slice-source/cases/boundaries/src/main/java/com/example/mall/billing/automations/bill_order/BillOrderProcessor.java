package com.example.mall.billing.automations.bill_order;

import com.example.mall.orders.automations.nudge_buyer.NudgeBuyerProcessor;
import com.example.mall.orders.routing.OrderCommand;
import com.example.mall.stock.aggregates.Stock;

public class BillOrderProcessor {
    private NudgeBuyerProcessor nudges;
    private OrderCommand lastCommand;

    void reserve(Stock stock) {
        stock.reserve(1);
    }
}
