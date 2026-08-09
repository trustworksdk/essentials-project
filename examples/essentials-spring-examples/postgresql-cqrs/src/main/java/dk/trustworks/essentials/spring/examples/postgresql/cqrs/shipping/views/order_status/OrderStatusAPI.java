/*
 * Copyright 2021-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package dk.trustworks.essentials.spring.examples.postgresql.cqrs.shipping.views.order_status;

import dk.trustworks.essentials.components.document_db.DocumentDbRepository;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

import static dk.trustworks.essentials.shared.FailFast.requireNonNull;

/**
 * The read API of the {@code shipping.order_status} view slice, and of no other.
 * <p>
 * Three query methods, one slice: list, lookup-by-id and filter-by-status all interrogate the read model
 * <em>this</em> slice owns, which is exactly what §R2 groups together. Splitting them into three slices would
 * mean three slices sharing one read model — the ownership violation R4 forbids.
 * <p>
 * Reads are eventually consistent: the projection is an asynchronous {@code ViewEventProcessor}, so an order
 * fetched immediately after {@code POST /shipping/ship-order} may still read {@code REGISTERED}.
 */
@RestController
@RequestMapping(path = "/shipping/orders")
public class OrderStatusAPI {
    private final DocumentDbRepository<OrderStatusView, String> repository;

    public OrderStatusAPI(DocumentDbRepository<OrderStatusView, String> orderStatusRepository) {
        this.repository = requireNonNull(orderStatusRepository, "No orderStatusRepository provided");
    }

    @GetMapping
    public List<OrderStatusView> listOrders() {
        return repository.findAll();
    }

    @GetMapping("/{orderId}")
    public ResponseEntity<OrderStatusView> getOrderStatus(@PathVariable String orderId) {
        var view = repository.findById(orderId);
        return view == null ? ResponseEntity.notFound().build() : ResponseEntity.ok(view);
    }

    @GetMapping(params = "status")
    public List<OrderStatusView> findOrdersByStatus(@RequestParam String status) {
        return repository.find(repository.queryBuilder()
                                         .where(repository.condition().eq("status", status)));
    }
}
