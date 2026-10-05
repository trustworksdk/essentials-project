package com.example.shipping.views.order_status;

import com.example.shipping.types.OrderId;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/shipping/order-status")
public class OrderStatusAPI {

    private final OrderStatusQueries queries;

    public OrderStatusAPI(OrderStatusQueries queries) {
        this.queries = queries;
    }

    @GetMapping
    public List<OrderStatusView> list(@RequestParam(defaultValue = "false") boolean shipped) {
        return queries.findByShipped(shipped);
    }

    @GetMapping(params = "status")
    public List<OrderStatusView> byStatus(@RequestParam String status) {
        return queries.findByStatus(status);
    }

    @GetMapping("/{orderId}")
    public OrderStatusView get(@PathVariable OrderId orderId) {
        return queries.findByOrderId(orderId.value()).orElseThrow();
    }
}
