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

package dk.trustworks.essentials.spring.examples.mongodb.messaging.shipping.external_systems.order_management.incoming;

import dk.trustworks.essentials.components.foundation.messaging.RedeliveryPolicy;
import dk.trustworks.essentials.components.foundation.messaging.eip.store_and_forward.*;
import dk.trustworks.essentials.reactive.command.CommandBus;
import dk.trustworks.essentials.spring.examples.mongodb.messaging.shipping.types.OrderId;
import dk.trustworks.essentials.spring.examples.mongodb.messaging.shipping.use_cases.ship_order.ShipOrder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;

import static dk.trustworks.essentials.shared.FailFast.requireNonNull;

/**
 * The inbound half of the anti-corruption boundary to order-management.
 *
 * <p>It consumes the {@code order-events} topic and turns an {@code OrderAccepted} into shipping's own
 * {@code ShipOrder} command -- translating the foreign {@code String} id into an {@code OrderId} as it goes. That
 * conversion is the boundary's entire purpose, and this is the only place in the module allowed to know both shapes.
 *
 * <p>Rather than dispatching straight onto the {@code CommandBus}, the command is handed to an {@link Inbox}, which
 * durably stores it inside the Kafka listener's transaction and forwards it asynchronously. That is what makes
 * consumption survive a crash between receiving the record and handling it -- at the price of at-least-once delivery,
 * which is why the idempotency guard exists on {@code ShippingOrder}.
 */
@Service
public class OrderEventsKafkaListener {
    private static final Logger log = LoggerFactory.getLogger(OrderEventsKafkaListener.class);

    public static final String ORDER_EVENTS_TOPIC_NAME = "order-events";

    private Inbox shipOrdersInbox;

    public OrderEventsKafkaListener(Inboxes inboxes,
                                    CommandBus commandBus) {
        requireNonNull(inboxes, "No inboxes provided");
        requireNonNull(commandBus, "No commandBus provided");
        // Create an Inbox that durably and asynchronously forwards any messages queued onto the CommandBus instance
        shipOrdersInbox = inboxes.getOrCreateInbox(InboxConfig.builder()
                                                              .inboxName(InboxName.of("OrderService:OrderEvents"))
                                                              .redeliveryPolicy(RedeliveryPolicy.fixedBackoff()
                                                                                                .setRedeliveryDelay(Duration.ofMillis(100))
                                                                                                .setMaximumNumberOfRedeliveries(10)
                                                                                                .build())
                                                              .messageConsumptionMode(MessageConsumptionMode.SingleGlobalConsumer)
                                                              .numberOfParallelMessageConsumers(5)
                                                              .build(),
                                                   commandBus); // <---- Forward to the commandBus
    }

    @KafkaListener(topics = ORDER_EVENTS_TOPIC_NAME, groupId = "order-processing", containerFactory = "kafkaListenerContainerFactory")
    @Transactional
    public void handle(OrderEvent event) {
        if (event instanceof OrderAccepted) {
            log.info("*** Since Order '{}' is Accepted we can start Shipping the Order. Forwarding {} to CommandBus",
                     event.id(),
                     ShipOrder.class.getSimpleName());

            // Since we're using the DurableLocalCommandBus we could just have issued a sendAndDontWait call:
            //commandBus.sendAndDontWait(new ShipOrder(OrderId.of(event.id())));

            // Instead we will here use the Inbox concept, to showcase how it can be used.
            // This is the translation: the foreign String id becomes shipping's OrderId here, and nowhere else.
            shipOrdersInbox.addMessageReceived(new ShipOrder(OrderId.of(event.id())));
        } else {
            log.debug("Ignoring {}: {}", event.getClass().getSimpleName(), event);
        }
    }

    /**
     * Only used for testing purposes
     */
    public Inbox getShipOrdersInbox() {
        return shipOrdersInbox;
    }
}
