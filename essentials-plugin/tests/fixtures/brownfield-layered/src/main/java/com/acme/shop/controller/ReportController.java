package com.acme.shop.controller;

import com.acme.shop.repository.OrderRepository;
import org.springframework.web.bind.annotation.*;
import java.util.Map;
import java.util.stream.Collectors;

// TRAP: read-only over the same entity as OrderController's GETs, but a
// DIFFERENT read shape (aggregated counts, not order rows). Must NOT be
// merged into the order-list view slice.
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
