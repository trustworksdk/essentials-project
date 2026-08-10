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

package dk.trustworks.essentials.spring.examples.postgresql.messaging.shipping.external_systems.order_management.incoming;

import dk.trustworks.essentials.reactive.command.CommandBus;
import dk.trustworks.essentials.spring.examples.postgresql.messaging.shipping.types.OrderId;
import dk.trustworks.essentials.spring.examples.postgresql.messaging.shipping.use_cases.ship_order.ShipOrder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import static dk.trustworks.essentials.shared.FailFast.requireNonNull;

/**
 * The inbound half of the anti-corruption boundary to order-management.
 *
 * <p>It consumes the {@code order-events} topic and turns an {@code OrderAccepted} into shipping's own
 * {@code ShipOrder} command -- translating the foreign {@code String} id into an {@code OrderId} as it goes. That
 * conversion is the boundary's entire purpose, and this is the only place in the module allowed to know both shapes.
 *
 * <p>Dispatch is {@code sendAndDontWait} on a {@code DurableLocalCommandBus}, so the command is written to a queue
 * table inside this listener's transaction and handled asynchronously afterwards -- durable in the same sense an
 * {@code Inbox} would be, and at-least-once for the same reason, which is why the idempotency guard exists on
 * {@code ShippingOrder}. The MongoDB sibling routes the same step through an explicit {@code Inbox} instead, to show
 * both forms.
 */
@Service
public class OrderEventsKafkaListener {
    private static final Logger log = LoggerFactory.getLogger(OrderEventsKafkaListener.class);

    public static final String ORDER_EVENTS_TOPIC_NAME = "order-events";

    private final CommandBus commandBus;

    public OrderEventsKafkaListener(CommandBus commandBus) {
        requireNonNull(commandBus, "No commandBus provided");
        this.commandBus = commandBus;
    }

    @KafkaListener(topics = ORDER_EVENTS_TOPIC_NAME, groupId = "order-processing", containerFactory = "kafkaListenerContainerFactory")
    @Transactional
    public void handle(OrderEvent event) {
        if (event instanceof OrderAccepted) {
            log.info("*** Since Order '{}' is Accepted we can start Shipping the Order. Forwarding {} to CommandBus",
                     event.id(),
                     ShipOrder.class.getSimpleName());
            // This is the translation: the foreign String id becomes shipping's OrderId here, and nowhere else.
            commandBus.sendAndDontWait(new ShipOrder(OrderId.of(event.id())));
        } else {
            log.debug("Ignoring {}: {}", event.getClass().getSimpleName(), event);
        }
    }
}
