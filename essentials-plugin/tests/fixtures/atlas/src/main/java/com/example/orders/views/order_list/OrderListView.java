package com.example.orders.views.order_list;

public record OrderListView(String orderId, String customerId, int quantity, String reason, String discount, long version) {
}
