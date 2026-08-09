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

package dk.trustworks.essentials.spring.examples.mongodb.messaging.shipping.adapters.kafka.outgoing;

import dk.trustworks.essentials.components.foundation.messaging.RedeliveryPolicy;
import dk.trustworks.essentials.components.foundation.messaging.eip.store_and_forward.*;
import dk.trustworks.essentials.components.foundation.transaction.UnitOfWorkFactory;
import dk.trustworks.essentials.reactive.AnnotatedEventHandler;
import dk.trustworks.essentials.reactive.EventBus;
import dk.trustworks.essentials.reactive.Handler;
import dk.trustworks.essentials.spring.examples.mongodb.messaging.shipping.domain.events.OrderShipped;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Service;

import java.time.Duration;

import static dk.trustworks.essentials.shared.FailFast.requireNonNull;

/**
 * This Service subscribes to the EventBus for ShippingOrder events synchronously within the Transaction that created the event.
 * Any OrderShipped events are converted to ExternalOrderShipped event's and added to the Outbox, which durably and asynchronously forwards the
 * ExternalOrderShipped to a Kafka topic
 */
@Service
public class ShippingEventKafkaPublisher extends AnnotatedEventHandler {
    private static final Logger log = LoggerFactory.getLogger(ShippingEventKafkaPublisher.class);

    public static final String SHIPPING_EVENTS_TOPIC_NAME = "shipping-events";

    private final Outbox kafkaOutbox;

    public ShippingEventKafkaPublisher(Outboxes outboxes,
                                       EventBus eventBus,
                                       KafkaTemplate<String, Object> kafkaTemplate,
                                       UnitOfWorkFactory<?> unitOfWorkFactory) {
        requireNonNull(outboxes, "No outboxes provided");
        requireNonNull(eventBus, "No eventBus provided");
        requireNonNull(kafkaTemplate, "No kafkaTemplate provided");
        requireNonNull(unitOfWorkFactory, "No unitOfWorkFactory provided");
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
                                                                                                             e.orderId().toString(),
                                                                                                             e);
                                                     kafkaTemplate.send(producerRecord);
                                                     log.info("*** Completed sending event {} to Kafka. Order '{}'", e.getClass().getSimpleName(), e.orderId());
                                                 });
    }

    @Handler
    private void handle(OrderShipped e) {
        log.info("*** Received {} for Order '{}' and adding it to the Outbox as a {} message", e.getClass().getSimpleName(), e.orderId(), ExternalOrderShipped.class.getSimpleName());
        // Since we're listening to the EventBus synchronously and the Message handling is transactional then adding the message to the Outbox joins in on the same underlying transaction
        kafkaOutbox.sendMessage(new ExternalOrderShipped(e.orderId()));
    }

    /**
     * Only used for testing purposes
     */
    public Outbox getKafkaOutbox() {
        return kafkaOutbox;
    }
}
