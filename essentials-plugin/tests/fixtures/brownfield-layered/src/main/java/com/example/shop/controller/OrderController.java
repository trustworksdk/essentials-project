package com.example.shop.controller;

import com.example.shop.model.*;
import com.example.shop.service.OrderService;
import org.springframework.web.bind.annotation.*;
import java.util.List;

@RestController
@RequestMapping("/api/orders")
public class OrderController {
    private final OrderService orderService;

    public OrderController(OrderService orderService) { this.orderService = orderService; }

    @PostMapping
    public String place(@RequestBody PlaceOrderRequest body) {
        return orderService.placeOrder(body.customerId, body.lines);
    }

    @PostMapping("/{orderId}/cancel")
    public void cancel(@PathVariable String orderId, @RequestBody CancelRequest body) {
        orderService.cancelOrder(orderId, body.reason);
    }

    @PostMapping("/{orderId}/ship")
    public void ship(@PathVariable String orderId) {
        orderService.markShipped(orderId);
    }

    @GetMapping
    public List<Order> list(@RequestParam String customerId) {
        return orderService.listOrders(customerId);
    }

    @GetMapping("/{orderId}")
    public Order get(@PathVariable String orderId) {
        return orderService.getOrder(orderId);
    }

    public static class PlaceOrderRequest {
        public String customerId;
        public List<OrderLine> lines;
    }

    public static class CancelRequest {
        public String reason;
    }
}
