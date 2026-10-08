package com.example.orders.use_cases.cancel_order;

import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/orders")
public class CancelOrderAPI {

    @PostMapping("/{id}/cancel")
    public Object cancel(@PathVariable String id) {
        return null;
    }
}
