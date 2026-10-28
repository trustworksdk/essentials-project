package com.example.orders.views.order_history;

public record OrderHistoryView(String orderId, String customerId, int quantity, String lastModified) {
}
