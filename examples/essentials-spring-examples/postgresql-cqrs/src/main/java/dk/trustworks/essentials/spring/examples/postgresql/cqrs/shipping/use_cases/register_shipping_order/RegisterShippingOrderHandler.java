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

package dk.trustworks.essentials.spring.examples.postgresql.cqrs.shipping.use_cases.register_shipping_order;

import dk.trustworks.essentials.reactive.command.AnnotatedCommandHandler;
import dk.trustworks.essentials.reactive.command.CmdHandler;
import dk.trustworks.essentials.spring.examples.postgresql.cqrs.shipping.aggregates.ShippingOrder;
import dk.trustworks.essentials.spring.examples.postgresql.cqrs.shipping.aggregates.ShippingOrders;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import static dk.trustworks.essentials.shared.FailFast.requireNonNull;

/**
 * The single decision component of the {@code shipping.register_shipping_order} slice — one command, one
 * handler (rules/slice-design.md §R1).
 */
@Service
public class RegisterShippingOrderHandler extends AnnotatedCommandHandler {
    private static final Logger log = LoggerFactory.getLogger(RegisterShippingOrderHandler.class);

    private final ShippingOrders shippingOrders;

    public RegisterShippingOrderHandler(ShippingOrders shippingOrders) {
        this.shippingOrders = requireNonNull(shippingOrders, "No shippingOrders provided");
    }

    // Automatically runs in a transaction as it's forwarded by the DurableLocalCommandBus
    @CmdHandler
    void handle(RegisterShippingOrder cmd) {
        var existingOrder = shippingOrders.findOrder(cmd.orderId());
        if (existingOrder.isEmpty()) {
            log.debug("===> Requesting New ShippingOrder '{}'", cmd.orderId());
            shippingOrders.registerNewOrder(new ShippingOrder(cmd));
        }
    }
}
