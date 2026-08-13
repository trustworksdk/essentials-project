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

package dk.trustworks.essentials.spring.examples.postgresql.messaging.shipping.use_cases.register_shipping_order;

import dk.trustworks.essentials.spring.examples.postgresql.messaging.shipping.types.OrderId;
import dk.trustworks.essentials.spring.examples.postgresql.messaging.shipping.types.ShippingDestinationAddress;

import static dk.trustworks.essentials.shared.FailFast.requireNonNull;

/**
 * Only shallowly immutable: {@link ShippingDestinationAddress} stays a class in this module because it is
 * {@code @Embedded} in the {@code ShippingOrder} JPA entity, so it has non-final fields and a setter, and
 * {@code ShippingOrder(RegisterShippingOrder)} stores the very instance held here rather than a copy. Mutating the
 * entity's address therefore changes this command's {@code equals}/{@code hashCode} after construction.
 * <p>
 * That is harmless here — the command is discarded as soon as it has been handled — but a command that is retained,
 * used as a map key, or compared after handling has to defensive-copy the address in the compact constructor.
 */
public record RegisterShippingOrder(OrderId orderId,
                                    ShippingDestinationAddress destinationAddress) {
    public RegisterShippingOrder {
        requireNonNull(orderId, "No orderId provided");
        requireNonNull(destinationAddress, "No destinationAddress provided");
    }
}
