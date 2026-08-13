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

package dk.trustworks.essentials.spring.examples.postgresql.messaging.shipping.use_cases.ship_order;

import dk.trustworks.essentials.reactive.EventBus;
import dk.trustworks.essentials.reactive.command.AnnotatedCommandHandler;
import dk.trustworks.essentials.reactive.command.CmdHandler;
import dk.trustworks.essentials.spring.examples.postgresql.messaging.shipping.entities.ShippingOrders;
import dk.trustworks.essentials.spring.examples.postgresql.messaging.shipping.events.OrderShipped;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import static dk.trustworks.essentials.shared.FailFast.requireNonNull;

/**
 * The single decision component of the {@code shipping.ship_order} slice (§R1).
 * <p>
 * {@code ShipOrder} reaches this handler from two places - {@code POST /shipping/ship-order} and the
 * {@code external_systems/order_management} translation slice - and the second delivers through an at-least-once
 * {@code Inbox}. That is why the idempotency guard sits on the entity rather than here.
 */
@Service
public class ShipOrderHandler extends AnnotatedCommandHandler {
    private static final Logger log = LoggerFactory.getLogger(ShipOrderHandler.class);

    private final ShippingOrders shippingOrders;
    private final EventBus       eventBus;

    public ShipOrderHandler(ShippingOrders shippingOrders,
                            EventBus eventBus) {
        this.shippingOrders = requireNonNull(shippingOrders, "No shippingOrders provided");
        this.eventBus = requireNonNull(eventBus, "No eventBus provided");
    }

    // Automatically runs in a transaction as it's forwarded by the DurableLocalCommandBus / the Inbox
    @CmdHandler
    void handle(ShipOrder cmd) {
        log.debug("===> Initiating Shipping of Order '{}'", cmd.orderId());
        var existingOrder = shippingOrders.getOrder(cmd.orderId());
        if (existingOrder.markOrderAsShipped()) {
            // JPA would flush this at commit anyway, because the loaded entity is managed. The save is explicit
            // regardless: relying on dirty checking is exactly what made the identical handler body silently wrong
            // in the mongodb-inbox-outbox sibling, where Spring Data does no such tracking
            shippingOrders.save(existingOrder);
            eventBus.publish(new OrderShipped(cmd.orderId()));
        }
    }
}
