package com.example.mall.stock.automations.restock;

import com.example.mall.billing.views.invoice_feed.InvoiceFeedProjection;
import com.example.mall.stock.events.StockReserved;

public class RestockProcessor {
    InvoiceFeedProjection feed;

    void on(StockReserved e) {
    }
}
