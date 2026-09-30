package com.acme.shop.orders.entities

import org.springframework.data.repository.Repository

/** Stand-in for the hand-written write repository — the contract in entities/CLAUDE.md. */
interface OrderRepository : Repository<Order, String> {

    fun findById(id: String): Order?

    fun save(order: Order): Order

    fun delete(order: Order)
}
