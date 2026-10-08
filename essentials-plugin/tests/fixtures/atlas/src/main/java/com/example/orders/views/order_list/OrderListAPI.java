package com.example.orders.views.order_list;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/orders")
public class OrderListAPI {

    @GetMapping
    public Object list() {
        return null;
    }
}
