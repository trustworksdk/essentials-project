package com.example.shop.service;

import com.example.shop.model.*;
import com.example.shop.repository.OrderRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.util.*;

@Service
public class OrderService {
    private final OrderRepository orders;

    public OrderService(OrderRepository orders) { this.orders = orders; }

    @Transactional
    public String placeOrder(String customerId, List<OrderLine> lines) {
        Order order = new Order();
        order.setId(UUID.randomUUID().toString());
        order.setCustomerId(customerId);
        order.setStatus("PLACED");
        order.setLines(lines);
        lines.forEach(l -> l.setOrder(order));
        orders.save(order);
        return order.getId();
    }

    @Transactional
    public void cancelOrder(String orderId, String reason) {
        Order order = orders.findById(orderId).orElseThrow();
        if ("SHIPPED".equals(order.getStatus())) {
            throw new IllegalStateException("Cannot cancel a shipped order");
        }
        order.setStatus("CANCELLED");
        order.setCancellationReason(reason);
        orders.save(order);
    }

    @Transactional
    public void markShipped(String orderId) {
        Order order = orders.findById(orderId).orElseThrow();
        order.setStatus("SHIPPED");
        orders.save(order);
    }

    public List<Order> listOrders(String customerId) { return orders.findByCustomerId(customerId); }

    public Order getOrder(String orderId) { return orders.findById(orderId).orElseThrow(); }
}
