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

package dk.trustworks.essentials.spring.examples.mongodb.messaging.shipping.domain;

import dk.trustworks.essentials.spring.examples.mongodb.messaging.shipping.OrderId;
import org.springframework.data.mongodb.repository.MongoRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;

import static dk.trustworks.essentials.shared.FailFast.requireNonNull;

@Repository
public interface ShippingOrders extends MongoRepository<ShippingOrder, OrderId> {
    default Optional<ShippingOrder> findOrder(OrderId orderId) {
        requireNonNull(orderId, "No orderId provided");
        return findById(orderId);
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
