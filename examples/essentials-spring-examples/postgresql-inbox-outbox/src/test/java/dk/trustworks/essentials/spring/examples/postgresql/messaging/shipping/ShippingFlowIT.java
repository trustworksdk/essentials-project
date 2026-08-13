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

package dk.trustworks.essentials.spring.examples.postgresql.messaging.shipping;

import dk.trustworks.essentials.spring.examples.postgresql.messaging.AbstractIntegrationTest;
import dk.trustworks.essentials.spring.examples.postgresql.messaging.shipping.external_systems.order_management.incoming.OrderAccepted;
import dk.trustworks.essentials.spring.examples.postgresql.messaging.shipping.external_systems.order_management.incoming.OrderEventsKafkaListener;
import dk.trustworks.essentials.spring.examples.postgresql.messaging.shipping.external_systems.order_management.outgoing.ExternalOrderShipped;
import dk.trustworks.essentials.spring.examples.postgresql.messaging.shipping.external_systems.order_management.outgoing.ShippingEventKafkaPublisher;
import dk.trustworks.essentials.spring.examples.postgresql.messaging.shipping.types.OrderId;
import dk.trustworks.essentials.spring.examples.postgresql.messaging.shipping.use_cases.register_shipping_order.RegisterShippingOrder;
import dk.trustworks.essentials.spring.examples.postgresql.messaging.shipping.use_cases.ship_order.ShipOrder;
import dk.trustworks.essentials.spring.examples.postgresql.messaging.shipping.types.ShippingDestinationAddress;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.awaitility.Awaitility;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.listener.ContainerProperties;
import org.springframework.kafka.listener.KafkaMessageListenerContainer;
import org.springframework.kafka.listener.MessageListener;

import java.time.Duration;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.assertj.core.api.Assertions.assertThat;

public class ShippingFlowIT extends AbstractIntegrationTest {

    private static final Logger log = LoggerFactory.getLogger(ShippingFlowIT.class);

    @BeforeEach
    void setup() {
        shippingRecordsReceived = new CopyOnWriteArrayList<>();
        var containerProperties = new ContainerProperties(ShippingEventKafkaPublisher.SHIPPING_EVENTS_TOPIC_NAME);
        containerProperties.setGroupId("ordershipping.test.consumer");
        kafkaListenerContainer = new KafkaMessageListenerContainer<>(kafkaConsumerFactory,
                                                                     containerProperties);
        kafkaListenerContainer.setupMessageListener((MessageListener<String, Object>) record -> {
            log.debug("Received '{}' record: {}", ShippingEventKafkaPublisher.SHIPPING_EVENTS_TOPIC_NAME, record);
            shippingRecordsReceived.add(record);
        });
        kafkaListenerContainer.start();
    }

    @AfterEach
    void cleanup() {
        if (kafkaListenerContainer != null) kafkaListenerContainer.stop();
    }

    @Test
    void receiving_an_OrderAccepted_event_for_a_registered_ShippingOrder_results_in_the_ShippingOrder_being_marked_as_shipped() throws InterruptedException {
        // Given
        var orderId = OrderId.random();
        commandBus.send(new RegisterShippingOrder(orderId,
                                                  ShippingDestinationAddress.builder()
                                                                            .setRecipientName("Test Tester")
                                                                            .setStreet("Test Street 1")
                                                                            .setZipCode("1234")
                                                                            .setCity("Test City")
                                                                            .build()));

        // When
        Thread.sleep(2000); // Wait for Kafka to be ready :(
        // The foreign contract carries a plain String id - OrderId is ours, and never crosses the wire
        var orderAccepted = new OrderAccepted(orderId.toString(), 1000);
        kafkaTemplate.send(new ProducerRecord<>(OrderEventsKafkaListener.ORDER_EVENTS_TOPIC_NAME,
                                                orderId.toString(),
                                                orderAccepted));
        log.info("*** Sent {} to Kafka", orderAccepted.getClass().getSimpleName());

        // Then
        Awaitility.waitAtMost(Duration.ofSeconds(10))
                  .untilAsserted(() -> assertThat(shippingRecordsReceived.size()).isEqualTo(1));
        assertThat(shippingRecordsReceived.get(0).value()).isInstanceOf(ExternalOrderShipped.class);
        assertThat(((ExternalOrderShipped) shippingRecordsReceived.get(0).value()).orderId()).isEqualTo(orderId.toString());

        // Verify that both the DurableLocalCommandBus and Outbox are empty
        var commandQueueName = commandBus.getCommandQueueName();
        assertThat(durableQueues.getTotalMessagesQueuedFor(commandQueueName)).isEqualTo(0);
        assertThat(shippingEventKafkaPublisher.getKafkaOutbox().getNumberOfOutgoingMessages()).isEqualTo(0);
    }

    /**
     * {@code ShipOrder} arrives over an at-least-once {@code Inbox}, so the same command can be handled more than
     * once. The guard lives on {@code ShippingOrder#markOrderAsShipped()}, and {@code ShipOrderHandler} writes the
     * mutation back explicitly rather than relying on JPA dirty checking - which is what the MongoDB sibling could
     * not do, and why it published a duplicate event until it was fixed.
     */
    @Test
    void shipping_an_already_shipped_order_publishes_no_further_OrderShipped_event() throws InterruptedException {
        // Given
        var orderId = OrderId.random();
        commandBus.send(new RegisterShippingOrder(orderId,
                                                  ShippingDestinationAddress.builder()
                                                                            .setRecipientName("Test Tester")
                                                                            .setStreet("Test Street 1")
                                                                            .setZipCode("1234")
                                                                            .setCity("Test City")
                                                                            .build()));
        Thread.sleep(2000); // Wait for Kafka to be ready :(

        // When the same ShipOrder is handled twice, as an at-least-once Inbox redelivery would
        commandBus.send(new ShipOrder(orderId));
        commandBus.send(new ShipOrder(orderId));

        // Then only the first is an actual state change, so exactly one event reaches Kafka - and stays that way
        Awaitility.await()
                  .during(Duration.ofSeconds(2))
                  .atMost(Duration.ofSeconds(15))
                  .untilAsserted(() -> assertThat(shippingRecordsReceived).hasSize(1));
        assertThat(shippingRecordsReceived.get(0).value()).isInstanceOf(ExternalOrderShipped.class);
    }
}
