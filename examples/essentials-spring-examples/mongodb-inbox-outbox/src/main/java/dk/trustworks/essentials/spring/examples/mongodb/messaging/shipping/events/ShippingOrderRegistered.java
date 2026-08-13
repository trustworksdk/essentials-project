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

package dk.trustworks.essentials.spring.examples.mongodb.messaging.shipping.events;

import dk.trustworks.essentials.spring.examples.mongodb.messaging.shipping.types.OrderId;
import dk.trustworks.essentials.spring.examples.mongodb.messaging.shipping.types.ShippingDestinationAddress;

import static dk.trustworks.essentials.shared.FailFast.requireNonNull;

/**
 * Jackson 3 derives the JSON property names of an event from its constructor parameter names, and for a record that
 * constructor is the canonical one — so the record components double as the persisted property names. Renaming a
 * component here is a wire-format change.
 * <p>
 * There is deliberately no {@code from(RegisterShippingOrder)} factory: {@code events/} is this bounded context's
 * importable surface, so naming a command type here would drag one slice's wire contract into every foreign consumer
 * of the event (§R4). The {@code register_shipping_order} slice unpacks its own command.
 */
public record ShippingOrderRegistered(OrderId orderId,
                                      ShippingDestinationAddress destinationAddress) implements ShippingEvent {
    public ShippingOrderRegistered {
        requireNonNull(orderId, "No orderId provided");
        requireNonNull(destinationAddress, "No destinationAddress provided");
    }
}
