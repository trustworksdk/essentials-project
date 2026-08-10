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

package dk.trustworks.essentials.spring.examples.postgresql.cqrs.shipping.use_cases.ship_order;

import dk.trustworks.essentials.spring.examples.postgresql.cqrs.shipping.types.OrderId;

import static dk.trustworks.essentials.shared.FailFast.requireNonNull;

/**
 * Mark an already-registered shipping order as shipped.
 *
 * <p>Both the command and the request body of {@code POST /shipping/ship-order}. It reaches its handler from two
 * directions -- that endpoint, and the {@code order_management} translation slice when order-management accepts an
 * order -- which is one slice with two triggers, not two slices.
 *
 * <p>The second path is at-least-once, so this command can be handled more than once for the same order; the guard
 * that makes that harmless (INV-SO-1) lives on {@code ShippingOrder}.
 */
public record ShipOrder(OrderId orderId) {
    public ShipOrder {
        requireNonNull(orderId, "No orderId provided");
    }
}
