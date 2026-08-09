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

package dk.trustworks.essentials.spring.examples.postgresql.cqrs.shipping.domain.events;

import dk.trustworks.essentials.spring.examples.postgresql.cqrs.shipping.OrderId;
import dk.trustworks.essentials.spring.examples.postgresql.cqrs.shipping.commands.RegisterShippingOrder;
import dk.trustworks.essentials.spring.examples.postgresql.cqrs.shipping.domain.ShippingDestinationAddress;

import static dk.trustworks.essentials.shared.FailFast.requireNonNull;

/**
 * Jackson 3 derives the JSON property names of an event from its constructor parameter names, and for a record that
 * constructor is the canonical one — so the record components double as the persisted property names. Convenience
 * construction from a command therefore goes through {@link #from(RegisterShippingOrder)} rather than through a
 * second constructor.
 */
public record ShippingOrderRegistered(OrderId orderId,
                                      ShippingDestinationAddress destinationAddress) implements ShippingEvent {
    public ShippingOrderRegistered {
        requireNonNull(orderId, "No orderId provided");
        requireNonNull(destinationAddress, "No destinationAddress provided");
    }

    public static ShippingOrderRegistered from(RegisterShippingOrder cmd) {
        requireNonNull(cmd, "No cmd provided");
        return new ShippingOrderRegistered(cmd.orderId(), cmd.destinationAddress());
    }
}
