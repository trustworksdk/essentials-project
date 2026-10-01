package com.example.shop.orders.views.order_list;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * Read API for THIS view slice only (rules/slice-design.md §R2).
 *
 * One API file, owned by this slice. **Several query methods are legitimate** when they interrogate
 * the same read shape — filters, sorts, pagination, lookup-by-id. A query serving a *different*
 * purpose over a *different* shape is a different slice. Declare every method in slice.yaml `serves`
 * + `endpoints`.
 *
 * The read shape IS the response (§R2) — return {@link OrderListView}. No mirror type, no mapper.
 *
 * Never inject `OrderRepository` (the BC's write repository), never call `save`/`delete`, never
 * touch another slice's queries. This slice reads its own table through its own interface, and that
 * is the entire allowance.
 */
@RestController
@RequestMapping("/api/orders")
public class OrderListAPI {

    private final OrderListQueries queries;

    public OrderListAPI(OrderListQueries queries) {
        this.queries = queries;
    }

    @GetMapping
    public List<OrderListView> orderList() {
        return queries.findAllBy();
    }

    @GetMapping("/{orderId}")
    public OrderListView byOrderId(@PathVariable String orderId) {
        return queries.findOrderById(orderId).orElseThrow();
    }
}
