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

package dk.trustworks.essentials.spring.examples.postgresql.cqrs.shipping.adapters.kafka.outgoing;

import dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.eventstream.AggregateType;
import dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.processor.EventProcessor;
import dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.processor.EventProcessorDependencies;
import dk.trustworks.essentials.components.foundation.messaging.MessageDeliveryErrorHandler;
import dk.trustworks.essentials.components.foundation.messaging.MessageHandler;
import dk.trustworks.essentials.components.foundation.messaging.RedeliveryPolicy;
import dk.trustworks.essentials.components.foundation.messaging.queue.OrderedMessage;
import dk.trustworks.essentials.spring.examples.postgresql.cqrs.shipping.domain.ShippingOrders;
import dk.trustworks.essentials.spring.examples.postgresql.cqrs.shipping.domain.events.OrderShipped;
import jakarta.validation.ConstraintViolationException;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Service;
import org.springframework.web.client.HttpClientErrorException;

import java.time.Duration;
import java.util.List;

import static dk.trustworks.essentials.shared.FailFast.requireNonNull;

@Service
public class ShippingEventKafkaPublisher extends EventProcessor {
    private static final Logger log = LoggerFactory.getLogger(ShippingEventKafkaPublisher.class);

    public static final String                        SHIPPING_EVENTS_TOPIC_NAME = "shipping-events";
    private final       KafkaTemplate<String, Object> kafkaTemplate;


    public ShippingEventKafkaPublisher(EventProcessorDependencies eventProcessorDependencies,
                                       KafkaTemplate<String, Object> kafkaTemplate) {
        super(eventProcessorDependencies);
        requireNonNull(kafkaTemplate, "No kafkaTemplate provided");
        this.kafkaTemplate = kafkaTemplate;
    }

    @Override
    public String getProcessorName() {
        return "ShippingEventsKafkaPublisher";
    }

    @Override
    protected int getNumberOfParallelInboxMessageConsumers() {
        return 1;
    }

    @Override
    protected List<AggregateType> reactsToEventsRelatedToAggregateTypes() {
        return List.of(ShippingOrders.AGGREGATE_TYPE);
    }

    @Override
    protected RedeliveryPolicy getInboxRedeliveryPolicy() {
        // Example of a custom inbox redelivery policy which doesn't perform retries in case message handling experiences a ConstraintViolationException
        return RedeliveryPolicy.exponentialBackoff()
                               .setInitialRedeliveryDelay(Duration.ofMillis(200))
                               .setFollowupRedeliveryDelay(Duration.ofMillis(200))
                               .setFollowupRedeliveryDelayMultiplier(1.1d)
                               .setMaximumFollowupRedeliveryDelayThreshold(Duration.ofSeconds(3))
                               .setMaximumNumberOfRedeliveries(20)
                               .setDeliveryErrorHandler(
                                       MessageDeliveryErrorHandler.stopRedeliveryOn(
                                               ConstraintViolationException.class,
                                               HttpClientErrorException.BadRequest.class))
                               .build();
    }

    @MessageHandler
    void handle(OrderShipped e, OrderedMessage eventMessage) {
        log.info("*** Received {} for Order '{}' and adding it to the Outbox as a {} message", e.getClass().getSimpleName(), e.orderId, ExternalOrderShipped.class.getSimpleName());
        var externalEvent = new ExternalOrderShipped(e.orderId, eventMessage.getOrder());
        log.info("*** Forwarding {} message to Kafka. Order '{}'", externalEvent.getClass().getSimpleName(), externalEvent.orderId);
        var producerRecord = new ProducerRecord<String, Object>(SHIPPING_EVENTS_TOPIC_NAME,
                                                                externalEvent.orderId.toString(),
                                                                externalEvent);
        kafkaTemplate.send(producerRecord);
        log.info("*** Completed sending event {} to Kafka. Order '{}'", externalEvent.getClass().getSimpleName(), externalEvent.orderId);
    }
}
