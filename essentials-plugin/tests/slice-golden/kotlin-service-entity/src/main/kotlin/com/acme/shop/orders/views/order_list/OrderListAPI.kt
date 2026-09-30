package com.acme.shop.orders.views.order_list

import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

/**
 * Read API for THIS view slice only (rules/slice-design.md §R2).
 *
 * One API file, owned by this slice. **Several query methods are legitimate** when they interrogate
 * the same read shape — filters, sorts, pagination, lookup-by-id. A query serving a *different*
 * purpose over a *different* shape is a different slice. Declare every method in slice.yaml `serves`
 * + `endpoints`.
 *
 * The read shape IS the response (§R2) — return [OrderListView]. No mirror type, no mapper.
 *
 * Never inject `OrderRepository` (the BC's write repository), never call `save`/`delete`, never
 * touch another slice's queries.
 */
@RestController
@RequestMapping("/api/orders")
class OrderListAPI(private val queries: OrderListQueries) {

    @GetMapping
    fun orderList(): List<OrderListView> = queries.findAllBy()

    @GetMapping("/{orderId}")
    fun byOrderId(@PathVariable orderId: String): OrderListView =
        queries.findOrderById(orderId)
            ?: throw NoSuchElementException(orderId)
}
