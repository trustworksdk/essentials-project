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

package dk.trustworks.essentials.spring.examples.postgresql.cqrs.shipping.aggregates;

import dk.trustworks.essentials.components.eventsourced.aggregates.EventHandler;
import dk.trustworks.essentials.components.eventsourced.aggregates.stateful.modern.AggregateRoot;
import dk.trustworks.essentials.spring.examples.postgresql.cqrs.shipping.types.OrderId;
import dk.trustworks.essentials.spring.examples.postgresql.cqrs.shipping.types.ShippingDestinationAddress;
import dk.trustworks.essentials.spring.examples.postgresql.cqrs.shipping.events.OrderShipped;
import dk.trustworks.essentials.spring.examples.postgresql.cqrs.shipping.events.ShippingEvent;
import dk.trustworks.essentials.spring.examples.postgresql.cqrs.shipping.events.ShippingOrderRegistered;

/**
 * An order to be shipped, and the consistency boundary for its dispatch.
 *
 * <p>An event-sourced {@link AggregateRoot}: {@code markOrderAsShipped()} does not assign state, it applies an
 * {@link OrderShipped}, and the {@code @EventHandler} below is the only place {@code shipped} is written. The same
 * handler runs during rehydration, so the flag is reconstructed from the stream rather than stored.
 *
 * <p><strong>INV-SO-1 -- an order ships at most once.</strong> The guard in {@code markOrderAsShipped()} makes a
 * second call a no-op instead of a second event. That matters because {@code ShipOrder} arrives at least once: the
 * {@code order_management} translation slice delivers it through an Inbox that retries. Without the guard a
 * redelivery would publish a duplicate {@code ExternalOrderShipped} to Kafka.
 *
 * <p>The MongoDB and JPA siblings model the same rule on a state-stored entity, where the equivalent method must
 * <em>return</em> whether anything changed -- an event-sourced aggregate does not need to, because applying no event
 * is itself the answer.
 *
 * <p>Reached through {@link ShippingOrders}. Commands are unpacked by the slice that handles them, so this class
 * never names a command type.
 */
public class ShippingOrder extends AggregateRoot<OrderId, ShippingEvent, ShippingOrder> {
    private boolean shipped;
    public ShippingOrder(OrderId aggregateId) {
        super(aggregateId);
    }

    public ShippingOrder(OrderId orderId,
                         ShippingDestinationAddress destinationAddress) {
        super(orderId);
        apply(new ShippingOrderRegistered(orderId, destinationAddress));
    }

    public void markOrderAsShipped() {
        // Idempotency check
        if (!shipped) {
            apply(new OrderShipped(aggregateId()));
        }
    }

    @EventHandler
    private void handle(OrderShipped e) {
        shipped = true;
    }
}
