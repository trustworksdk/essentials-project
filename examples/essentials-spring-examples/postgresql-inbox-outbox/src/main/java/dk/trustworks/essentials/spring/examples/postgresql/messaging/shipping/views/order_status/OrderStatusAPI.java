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

package dk.trustworks.essentials.spring.examples.postgresql.messaging.shipping.views.order_status;

import dk.trustworks.essentials.spring.examples.postgresql.messaging.shipping.types.OrderId;
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
 * <em>this</em> slice owns, which is exactly what §R2 groups together. Splitting them into three slices would mean
 * three slices sharing one read model - the ownership violation §R4 forbids.
 * <p>
 * <strong>Reads here are strongly consistent.</strong> There is no projector and no eventual consistency to wait out:
 * the view reads the same table the command slices write, so an order fetched immediately after
 * {@code POST /shipping/ship-order} returns already shows {@code shipped=true}. That is what the service-entity lane
 * buys, and it is the opposite of the event-sourced {@code postgresql-cqrs} sibling.
 */
@RestController
@RequestMapping(path = "/shipping/orders")
public class OrderStatusAPI {
    private final OrderStatusQueries queries;

    public OrderStatusAPI(OrderStatusQueries queries) {
        this.queries = requireNonNull(queries, "No queries provided");
    }

    @GetMapping
    public List<OrderStatusView> listOrders() {
        return queries.findAllBy();
    }

    @GetMapping("/{orderId}")
    public ResponseEntity<OrderStatusView> getOrderStatus(@PathVariable OrderId orderId) {
        // The typed signature is §R2's stronger form; it binds because config/WebConfiguration imports
        // types-spring-web's EssentialsWebMvcConfigurer.
        //
        // Unwrapping to String on the next line is not an oversight: this module's ShippingOrder deliberately keeps
        // a plain String @Id (see the BC's CLAUDE.md), so OrderStatusView.getId() is a String and the query takes
        // one. The HTTP contract is still typed, which is where it matters. The mongodb sibling uses @Id OrderId and
        // has no unwrapping here
        return queries.findOrderStatusById(orderId.toString())
                      .map(ResponseEntity::ok)
                      .orElseGet(() -> ResponseEntity.notFound().build());
    }

    @GetMapping(params = "shipped")
    public List<OrderStatusView> findOrdersByShippedStatus(@RequestParam boolean shipped) {
        return queries.findByShipped(shipped);
    }
}
