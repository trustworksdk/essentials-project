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

package dk.trustworks.essentials.spring.examples.postgresql.cqrs.shipping.events;

import dk.trustworks.essentials.spring.examples.postgresql.cqrs.shipping.types.OrderId;
import dk.trustworks.essentials.spring.examples.postgresql.cqrs.shipping.types.ShippingDestinationAddress;

import static dk.trustworks.essentials.shared.FailFast.requireNonNull;

/**
 * Jackson 3 derives the JSON property names of an event from its constructor parameter names, and for a record that
 * constructor is the canonical one — so the record components double as the persisted property names. The canonical
 * constructor is deliberately the <em>only</em> one, which is what keeps that mapping unambiguous.
 * <p>
 * This event knows nothing about the command that causes it. {@code events/} is half of the bounded context's
 * importable surface (§R4), so a reference to {@code use_cases/…} here would drag a slice's internals into every
 * context that imports this type. The emitting slice constructs it.
 */
public record ShippingOrderRegistered(OrderId orderId,
                                      ShippingDestinationAddress destinationAddress) implements ShippingEvent {
    public ShippingOrderRegistered {
        requireNonNull(orderId, "No orderId provided");
        requireNonNull(destinationAddress, "No destinationAddress provided");
    }
}
