package com.example.ids.orders.use_cases.ship_order;

import com.example.ids.orders.types.OrderId;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * TRAP (6 raw id): the id is typed at the edge. Neither this comment's
 * {@code @PathVariable String orderId} nor the string below is a parameter.
 */
@RestController
@RequestMapping("/api/orders")
public class ShipOrderAPI {

    @PostMapping("/{id}/ship")
    public void ship(@PathVariable("id") OrderId orderId) {
        var note = "@PathVariable String orderId";
    }
}
