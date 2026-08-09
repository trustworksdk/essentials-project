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
import dk.trustworks.essentials.spring.examples.postgresql.cqrs.shipping.use_cases.register_shipping_order.RegisterShippingOrder;
import dk.trustworks.essentials.spring.examples.postgresql.cqrs.shipping.events.OrderShipped;
import dk.trustworks.essentials.spring.examples.postgresql.cqrs.shipping.events.ShippingEvent;
import dk.trustworks.essentials.spring.examples.postgresql.cqrs.shipping.events.ShippingOrderRegistered;

public class ShippingOrder extends AggregateRoot<OrderId, ShippingEvent, ShippingOrder> {
    private boolean shipped;
    public ShippingOrder(OrderId aggregateId) {
        super(aggregateId);
    }

    public ShippingOrder(RegisterShippingOrder cmd) {
        super(cmd.orderId());
        apply(ShippingOrderRegistered.from(cmd));
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
