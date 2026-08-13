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

package dk.trustworks.essentials.spring.examples.mongodb.messaging.shipping.use_cases.register_shipping_order;

import dk.trustworks.essentials.spring.examples.mongodb.messaging.shipping.types.OrderId;
import dk.trustworks.essentials.spring.examples.mongodb.messaging.shipping.types.ShippingDestinationAddress;

import static dk.trustworks.essentials.shared.FailFast.requireNonNull;

/**
 * Register a shipping order for an order that is to be delivered to the given address.
 *
 * <p>This record is both the command dispatched on the {@code CommandBus} and the request body of
 * {@code POST /shipping/register-order} -- there is no separate DTO to keep in step.
 *
 * <p>It is durably persisted: the command bus is a {@code DurableLocalCommandBus}, so this payload is serialized into
 * a queue table. Renaming a component is therefore a wire-format change, because Jackson 3 derives the JSON property
 * names from the canonical constructor's parameter names.
 */
public record RegisterShippingOrder(OrderId orderId,
                                    ShippingDestinationAddress destinationAddress) {
    public RegisterShippingOrder {
        requireNonNull(orderId, "No orderId provided");
        requireNonNull(destinationAddress, "No destinationAddress provided");
    }
}
