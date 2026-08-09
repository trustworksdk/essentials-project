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

package dk.trustworks.essentials.spring.examples.postgresql.cqrs.shipping;

import dk.trustworks.essentials.components.foundation.messaging.queue.DurableQueues;
import dk.trustworks.essentials.components.foundation.reactive.command.DurableLocalCommandBus;
import dk.trustworks.essentials.spring.examples.postgresql.cqrs.Application;
import dk.trustworks.essentials.spring.examples.postgresql.cqrs.TestConfiguration;
import dk.trustworks.essentials.spring.examples.postgresql.cqrs.shipping.external_systems.order_management.incoming.OrderAccepted;
import dk.trustworks.essentials.spring.examples.postgresql.cqrs.shipping.external_systems.order_management.incoming.OrderEventsKafkaListener;
import dk.trustworks.essentials.spring.examples.postgresql.cqrs.shipping.external_systems.order_management.outgoing.ExternalOrderShipped;
import dk.trustworks.essentials.spring.examples.postgresql.cqrs.shipping.external_systems.order_management.outgoing.ShippingEventKafkaPublisher;
import dk.trustworks.essentials.spring.examples.postgresql.cqrs.shipping.use_cases.register_shipping_order.RegisterShippingOrder;
import dk.trustworks.essentials.spring.examples.postgresql.cqrs.shipping.use_cases.register_shipping_order.RegisterShippingOrderHandler;
import dk.trustworks.essentials.spring.examples.postgresql.cqrs.shipping.use_cases.ship_order.ShipOrderHandler;
import dk.trustworks.essentials.spring.examples.postgresql.cqrs.shipping.types.OrderId;
import dk.trustworks.essentials.spring.examples.postgresql.cqrs.shipping.types.ShippingDestinationAddress;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.awaitility.Awaitility;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.kafka.core.ConsumerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.listener.ContainerProperties;
import org.springframework.kafka.listener.KafkaMessageListenerContainer;
import org.springframework.kafka.listener.MessageListener;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(classes = {Application.class, TestConfiguration.class})
@Testcontainers
@DirtiesContext
public class OrderShippingProcessorIT {
    private static final Logger log = LoggerFactory.getLogger(OrderShippingProcessorIT.class);

    @Container
    static PostgreSQLContainer<?> postgreSQLContainer = new PostgreSQLContainer<>("postgres:latest")
            .withDatabaseName("test")
            .withPassword("test")
            .withUsername("test");

    @Container
    static  org.testcontainers.kafka.KafkaContainer       kafkaContainer = new org.testcontainers.kafka.KafkaContainer("apache/kafka-native:latest")
            .withEnv("KAFKA_LISTENERS", "PLAINTEXT://:9092,BROKER://:9093,CONTROLLER://:9094");
    private KafkaMessageListenerContainer<String, Object> kafkaListenerContainer;

    @DynamicPropertySource
    static void setProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", postgreSQLContainer::getJdbcUrl);
        registry.add("spring.datasource.password", postgreSQLContainer::getPassword);
        registry.add("spring.datasource.username", postgreSQLContainer::getUsername);

        registry.add("spring.kafka.bootstrap-servers", kafkaContainer::getBootstrapServers);
    }

    @Autowired
    private KafkaTemplate<String, Object> kafkaTemplate;


    // Injected so the test fails fast if either command slice split out of the old OrderShippingProcessor
    // is unwired -- an unregistered @CmdHandler compiles and passes every unit test.
    @Autowired
    private RegisterShippingOrderHandler registerShippingOrderHandler;

    @Autowired
    private ShipOrderHandler shipOrderHandler;

    @Autowired
    private ShippingEventKafkaPublisher shippingEventKafkaPublisher;

    @Autowired
    private DurableLocalCommandBus commandBus;

    @Autowired
    private DurableQueues durableQueues;

    @Autowired
    ConsumerFactory<String, Object> kafkaConsumerFactory;

    private List<ConsumerRecord<String, Object>> shippingRecordsReceived;

    @BeforeEach
    void setup() {
        shippingRecordsReceived = new ArrayList<>();
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
        var orderAccepted = new OrderAccepted(orderId, 1000);
        kafkaTemplate.send(new ProducerRecord<>(OrderEventsKafkaListener.ORDER_EVENTS_TOPIC_NAME,
                                                orderId.toString(),
                                                orderAccepted));
        log.info("*** Sent {} to Kafka", orderAccepted.getClass().getSimpleName());

        // Then
        Awaitility.waitAtMost(Duration.ofSeconds(10))
                  .untilAsserted(() -> {
                      assertThat(shippingRecordsReceived.size()).isEqualTo(1);
                  });
        assertThat(shippingRecordsReceived.get(0).value()).isInstanceOf(ExternalOrderShipped.class);
        assertThat((CharSequence) ((ExternalOrderShipped) shippingRecordsReceived.get(0).value()).orderId()).isEqualTo(orderId);
        assertThat(((ExternalOrderShipped) shippingRecordsReceived.get(0).value()).eventOrder()).isEqualTo(1L);

        // Verify that both the DurableLocalCommandBus and Outbox are empty
        var commandQueueName = commandBus.getCommandQueueName();
        assertThat(durableQueues.getTotalMessagesQueuedFor(commandQueueName)).isEqualTo(0);
    }
}
