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

package dk.trustworks.essentials.spring.examples.postgresql.cqrs.shipping.views.order_status;

import dk.trustworks.essentials.components.document_db.DocumentDbRepository;
import dk.trustworks.essentials.components.foundation.reactive.command.DurableLocalCommandBus;
import dk.trustworks.essentials.components.foundation.transaction.UnitOfWork;
import dk.trustworks.essentials.components.foundation.transaction.UnitOfWorkFactory;
import dk.trustworks.essentials.spring.examples.postgresql.cqrs.Application;
import dk.trustworks.essentials.spring.examples.postgresql.cqrs.TestConfiguration;
import dk.trustworks.essentials.spring.examples.postgresql.cqrs.shipping.types.OrderId;
import dk.trustworks.essentials.spring.examples.postgresql.cqrs.shipping.types.ShippingDestinationAddress;
import dk.trustworks.essentials.spring.examples.postgresql.cqrs.shipping.use_cases.register_shipping_order.RegisterShippingOrder;
import dk.trustworks.essentials.spring.examples.postgresql.cqrs.shipping.use_cases.ship_order.ShipOrder;
import org.awaitility.Awaitility;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static dk.trustworks.essentials.spring.examples.postgresql.cqrs.ExampleTestImages.*;

/**
 * Covers the {@code shipping.order_status} view slice: that the projection catches up through the order's
 * lifecycle, and that the status filter reads the model rather than scanning it.
 * <p>
 * Note what the read model does that the write model cannot: {@code ShippingOrder} holds a
 * {@code boolean shipped}, because that is all its invariant needs. This slice projects a named status,
 * because that is what a caller asking about an order wants.
 */
@SpringBootTest(classes = {Application.class, TestConfiguration.class})
@Testcontainers
@DirtiesContext
public class OrderStatusViewIT {

    @Container
    static PostgreSQLContainer<?> postgreSQLContainer = new PostgreSQLContainer<>(POSTGRES_IMAGE)
            .withDatabaseName("test")
            .withPassword("test")
            .withUsername("test");

    @Container
    static org.testcontainers.kafka.KafkaContainer kafkaContainer = new org.testcontainers.kafka.KafkaContainer(KAFKA_IMAGE)
            .withStartupAttempts(2);

    @DynamicPropertySource
    static void setProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", postgreSQLContainer::getJdbcUrl);
        registry.add("spring.datasource.password", postgreSQLContainer::getPassword);
        registry.add("spring.datasource.username", postgreSQLContainer::getUsername);
        registry.add("spring.kafka.bootstrap-servers", kafkaContainer::getBootstrapServers);
    }

    @Autowired
    private DurableLocalCommandBus commandBus;

    @Autowired
    private DocumentDbRepository<OrderStatusView, String> orderStatusRepository;

    @Autowired
    private UnitOfWorkFactory<? extends UnitOfWork> unitOfWorkFactory;

    @Test
    void order_status_view_follows_the_shipping_order_lifecycle() {
        var orderId     = OrderId.random();
        var destination = ShippingDestinationAddress.builder()
                                                    .setRecipientName("John Doe")
                                                    .setStreet("Test Street 1")
                                                    .setZipCode("1234")
                                                    .setCity("Test City")
                                                    .build();

        commandBus.send(new RegisterShippingOrder(orderId, destination));

        Awaitility.waitAtMost(Duration.ofSeconds(15))
                  .untilAsserted(() -> {
                      var view = findView(orderId);
                      assertThat(view).isNotNull();
                      assertThat(view.getStatus()).isEqualTo(OrderStatusView.REGISTERED);
                  });

        assertThat(findView(orderId).getDestinationAddress()).isEqualTo(destination);

        commandBus.send(new ShipOrder(orderId));

        Awaitility.waitAtMost(Duration.ofSeconds(15))
                  .untilAsserted(() -> assertThat(findView(orderId).getStatus()).isEqualTo(OrderStatusView.SHIPPED));
    }

    @Test
    void orders_can_be_filtered_by_status() {
        var orderId = OrderId.random();
        commandBus.send(new RegisterShippingOrder(orderId,
                                                  ShippingDestinationAddress.builder()
                                                                            .setRecipientName("Jane Roe")
                                                                            .setStreet("Other Street 2")
                                                                            .setZipCode("4321")
                                                                            .setCity("Other City")
                                                                            .build()));

        Awaitility.waitAtMost(Duration.ofSeconds(15))
                  .untilAsserted(() -> assertThat(findView(orderId)).isNotNull());

        var registered = unitOfWorkFactory.withUnitOfWork(
                uow -> orderStatusRepository.find(orderStatusRepository.queryBuilder()
                                                                       .where(orderStatusRepository.condition()
                                                                                                   .eq("status", OrderStatusView.REGISTERED))));

        assertThat(registered).extracting(OrderStatusView::getOrderId)
                              .contains(orderId.toString());
    }

    private OrderStatusView findView(OrderId orderId) {
        return unitOfWorkFactory.withUnitOfWork(uow -> orderStatusRepository.findById(orderId.toString()));
    }
}
