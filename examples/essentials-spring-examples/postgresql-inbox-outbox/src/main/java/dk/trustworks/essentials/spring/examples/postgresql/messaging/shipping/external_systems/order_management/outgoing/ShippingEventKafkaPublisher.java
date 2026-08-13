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

package dk.trustworks.essentials.spring.examples.postgresql.messaging.shipping.external_systems.order_management.outgoing;

import dk.trustworks.essentials.components.foundation.messaging.RedeliveryPolicy;
import dk.trustworks.essentials.components.foundation.messaging.eip.store_and_forward.*;
import dk.trustworks.essentials.reactive.AnnotatedEventHandler;
import dk.trustworks.essentials.reactive.Handler;
import dk.trustworks.essentials.spring.examples.postgresql.messaging.shipping.events.OrderShipped;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Service;

import java.time.Duration;

import static dk.trustworks.essentials.shared.FailFast.requireNonNull;

/**
 * The outbound half of the anti-corruption boundary to order-management.
 *
 * <p>It subscribes to the {@code EventBus} <em>synchronously, inside the transaction that produced the event</em>,
 * converts an internal {@code OrderShipped} into the published {@code ExternalOrderShipped} -- turning shipping's
 * {@code OrderId} back into a plain {@code String} on the way out -- and hands it to an {@code Outbox}.
 *
 * <p>That combination is the point of the pattern. Because the {@code Outbox} insert joins the same database
 * transaction as the state change, the order is marked shipped and the outgoing message is recorded atomically:
 * neither can happen without the other. A separate consumer then forwards the message to the {@code shipping-events}
 * topic and retries until the broker acknowledges, which is what makes publishing survive a Kafka outage without a
 * distributed transaction.
 */
@Service
public class ShippingEventKafkaPublisher extends AnnotatedEventHandler {
    private static final Logger log = LoggerFactory.getLogger(ShippingEventKafkaPublisher.class);

    public static final String SHIPPING_EVENTS_TOPIC_NAME = "shipping-events";

    private final Outbox kafkaOutbox;

    public ShippingEventKafkaPublisher(Outboxes outboxes,
                                       KafkaTemplate<String, Object> kafkaTemplate) {
        requireNonNull(outboxes, "No outboxes provided");
        requireNonNull(kafkaTemplate, "No kafkaTemplate provided");
        // Setup the outbox to forward to Kafka
        kafkaOutbox = outboxes.getOrCreateOutbox(OutboxConfig.builder()
                                                             .setOutboxName(OutboxName.of("ShippingOrder:KafkaShippingEvents"))
                                                             .setRedeliveryPolicy(RedeliveryPolicy.fixedBackoff(Duration.ofMillis(100), 10))
                                                             .setMessageConsumptionMode(MessageConsumptionMode.SingleGlobalConsumer)
                                                             .setNumberOfParallelMessageConsumers(1)
                                                             .build(),
                                                 msg -> {
                                                     var e = (ExternalOrderShippingEvent) msg.getPayload();
                                                     log.info("*** Forwarding Outbox {} message to Kafka. Order '{}'", e.getClass().getSimpleName(), e.orderId());
                                                     var producerRecord = new ProducerRecord<String, Object>(SHIPPING_EVENTS_TOPIC_NAME,
                                                                                                             e.orderId(),
                                                                                                             e);
                                                     kafkaTemplate.send(producerRecord);
                                                     log.info("*** Completed sending event {} to Kafka. Order '{}'", e.getClass().getSimpleName(), e.orderId());
                                                 });
    }

    @Handler
    private void handle(OrderShipped e) {
        log.info("*** Received {} for Order '{}' and adding it to the Outbox as a {} message", e.getClass().getSimpleName(), e.orderId(), ExternalOrderShipped.class.getSimpleName());
        // Since we're listening to the EventBus synchronously and the Message handling is transactional then adding the message to the Outbox joins in on the same underlying transaction.
        // This is the translation: shipping's OrderId becomes a plain String on the way out, and nowhere else.
        kafkaOutbox.sendMessage(new ExternalOrderShipped(e.orderId().toString()));
    }

    /**
     * Only used for testing purposes
     */
    public Outbox getKafkaOutbox() {
        return kafkaOutbox;
    }
}
