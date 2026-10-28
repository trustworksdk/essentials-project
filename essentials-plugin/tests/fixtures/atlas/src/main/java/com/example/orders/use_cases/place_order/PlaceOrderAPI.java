package com.example.orders.use_cases.place_order;

import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/orders")
public class PlaceOrderAPI {

    @PostMapping
    public Object place() {
        return null;
    }
}
