package com.example.shop.orders.views.order_list;

import com.example.shop.orders.types.OrderId;
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
 *
 * The path variable is the BC's semantic id, not a {@code String} — the typed edge is the default
 * shape (rules/slice-design.md § The command and the view *are* the contract). It binds because the
 * Essentials converter is registered ({@code references/stack/stack-contract.md} S4); a missing
 * converter surfaces as HTTP 500, not 400. Only the query stays {@code String}-keyed, because the
 * entity's {@code @Id} is a {@code String} ({@code entities/CLAUDE.md}), so the id is unwrapped with
 * {@code toString()} at the one call that needs it.
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
    public OrderListView byOrderId(@PathVariable OrderId orderId) {
        return queries.findOrderById(orderId.toString()).orElseThrow();
    }
}
