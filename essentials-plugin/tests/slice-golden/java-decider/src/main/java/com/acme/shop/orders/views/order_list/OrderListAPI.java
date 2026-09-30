package com.acme.shop.orders.views.order_list;

import dk.trustworks.essentials.components.document_db.DocumentDbRepository;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * Read API for THIS view slice only (rules/slice-design.md §R2).
 *
 * One API file, owned by this slice. One query method by default — add more when they interrogate
 * this slice's OWN read model (filters, sorts, pagination, lookup-by-id). A query serving a
 * different purpose over a different read-model shape is a different slice; needing one more event
 * is NOT — that is this slice evolving (§ Evolving a view slice).
 *
 * The read model IS the response (§R2) — return the view entity. No mirror response type, no mapper.
 *
 * Declare every method you add in slice.yaml {@code serves} + {@code endpoints}.
 *
 * Never touches the event store, never calls a Decider, never reads another slice's repository.
 */
@RestController
@RequestMapping("/api/orders")
public class OrderListAPI {

    private final DocumentDbRepository<OrderListView, String> repository;

    public OrderListAPI(DocumentDbRepository<OrderListView, String> repository) {
        this.repository = repository;
    }

    @GetMapping
    public List<OrderListView> orderList() {
        return repository.findAll();
    }
}
