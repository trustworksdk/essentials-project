package com.example.shop.controller;

import com.example.shop.repository.OrderRepository;
import org.springframework.web.bind.annotation.*;
import java.util.Map;
import java.util.stream.Collectors;

@RestController
@RequestMapping("/api/reports")
public class ReportController {
    private final OrderRepository orders;

    public ReportController(OrderRepository orders) { this.orders = orders; }

    @GetMapping("/orders-by-status")
    public Map<String, Long> ordersByStatus() {
        return orders.findAll().stream()
                .collect(Collectors.groupingBy(o -> o.getStatus(), Collectors.counting()));
    }
}
