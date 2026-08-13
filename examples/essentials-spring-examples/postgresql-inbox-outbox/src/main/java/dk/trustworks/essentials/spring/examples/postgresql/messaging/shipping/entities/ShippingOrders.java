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

package dk.trustworks.essentials.spring.examples.postgresql.messaging.shipping.entities;

import dk.trustworks.essentials.spring.examples.postgresql.messaging.shipping.types.OrderId;
import org.springframework.data.repository.Repository;

import java.util.Optional;

import static dk.trustworks.essentials.shared.FailFast.requireNonNull;

/**
 * The <strong>write</strong> repository for {@link ShippingOrder}, living beside the entity it persists rather than in
 * a {@code repositories/} folder - that directory would be a layer, this one is named for a domain concept (§R5).
 * <p>
 * Its surface is deliberately load-by-id and save, and it extends the bare {@link Repository} marker rather than
 * {@code JpaRepository} so that {@code findAll}, {@code deleteAll} and friends are not on offer. It used to carry
 * {@code findByShipped(boolean)}, {@code findByIdIn(List)} and {@code findAllOrderIds()}: the first belongs to
 * {@code views/order_status}, which declares its own narrow query interface over the same table; the second had no
 * callers at all; the third served only the load-test harness and moved to test scope with it.
 */
public interface ShippingOrders extends Repository<ShippingOrder, String> {
    Optional<ShippingOrder> findById(String id);

    ShippingOrder save(ShippingOrder order);

    default Optional<ShippingOrder> findOrder(OrderId orderId) {
        requireNonNull(orderId, "No orderId provided");
        return findById(orderId.toString());
    }

    default ShippingOrder getOrder(OrderId orderId) {
        requireNonNull(orderId, "No orderId provided");
        return findOrder(orderId).get();
    }

    default void registerNewOrder(ShippingOrder order) {
        requireNonNull(order, "No order provided");
        save(order);
    }
}
