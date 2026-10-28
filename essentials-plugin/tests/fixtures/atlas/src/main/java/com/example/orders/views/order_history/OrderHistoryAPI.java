package com.example.orders.views.order_history;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/orders/history")
public class OrderHistoryAPI {

    @GetMapping
    public Object history() {
        return null;
    }
}
