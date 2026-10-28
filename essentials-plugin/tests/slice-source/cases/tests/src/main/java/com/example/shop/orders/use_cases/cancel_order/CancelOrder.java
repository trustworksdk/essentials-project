package com.example.shop.orders.use_cases.cancel_order;

import org.junit.jupiter.api.Test;

/** RULE: main source is never part of the test index, whatever it carries. */
public record CancelOrder(String orderId) {
    @Test
    void notATest() {
    }
}
