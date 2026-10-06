package com.example.ids.orders.use_cases.cancel_order;

import com.example.ids.orders.types.OrderId;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** RULE (6 raw id): the path id arrives as a String and is wrapped in the handler. */
@RestController
@RequestMapping("/api/orders")
public class CancelOrderAPI {

    @PostMapping("/{orderId}/cancel")
    public void cancel(@PathVariable String orderId) {
        var cmd = new CancelOrder(new OrderId(orderId));
    }
}
