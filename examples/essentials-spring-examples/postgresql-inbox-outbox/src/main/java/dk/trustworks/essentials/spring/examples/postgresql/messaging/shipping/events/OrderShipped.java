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

package dk.trustworks.essentials.spring.examples.postgresql.messaging.shipping.events;

import dk.trustworks.essentials.spring.examples.postgresql.messaging.shipping.types.OrderId;

import static dk.trustworks.essentials.shared.FailFast.requireNonNull;

/**
 * A registered shipping order has been dispatched.
 *
 * <p>Published on the {@code EventBus} by the {@code ship_order} slice, and only when
 * {@code ShippingOrder.markOrderAsShipped()} reported an actual state change -- so exactly one of these is emitted per
 * order however many times the command is redelivered. The translation slice converts it to the external
 * {@code ExternalOrderShipped} inside the same transaction.
 *
 * <p>On this write style the event is an integration fact, not a stored one: it is never appended to a stream and
 * never replayed.
 */
public record OrderShipped(OrderId orderId) implements ShippingEvent {
    public OrderShipped {
        requireNonNull(orderId, "No orderId provided");
    }
}
