package com.example.shop.orders.entities;

import org.springframework.data.repository.Repository;

import java.util.Optional;

/** Stand-in for the hand-written write repository — the contract in entities/CLAUDE.md. */
public interface OrderRepository extends Repository<Order, String> {

    Optional<Order> findById(String id);

    Order save(Order order);

    void delete(Order order);
}
