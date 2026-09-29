package com.acme.shop.repository;

import com.acme.shop.model.Order;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.List;

public interface OrderRepository extends JpaRepository<Order, String> {
    List<Order> findByCustomerId(String customerId);
    List<Order> findByStatus(String status);
}
