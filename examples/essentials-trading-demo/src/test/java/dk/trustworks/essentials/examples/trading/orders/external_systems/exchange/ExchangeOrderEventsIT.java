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

package dk.trustworks.essentials.examples.trading.orders.external_systems.exchange;

import dk.trustworks.essentials.examples.trading.integration.SimulatorImages;
import dk.trustworks.essentials.examples.trading.integration.exchange_simulator.client.ApiClient;
import dk.trustworks.essentials.examples.trading.integration.exchange_simulator.client.api.*;
import dk.trustworks.essentials.examples.trading.integration.exchange_simulator.client.model.DeliveryStatus;
import dk.trustworks.essentials.examples.trading.integration.exchange_simulator.client.model.WebhookRequest;
import dk.trustworks.essentials.examples.trading.orders.types.OrderStatus;
import dk.trustworks.essentials.examples.trading.orders.views.orders.OrderView;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIf;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.resttestclient.TestRestTemplate;
import org.springframework.boot.resttestclient.autoconfigure.AutoConfigureTestRestTemplate;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpStatus;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.*;
import org.springframework.web.client.RestClient;
import org.testcontainers.Testcontainers;
import org.testcontainers.containers.*;
import org.testcontainers.junit.jupiter.Container;

import java.io.*;
import java.math.BigDecimal;
import java.net.ServerSocket;
import java.nio.file.*;
import java.time.Duration;
import java.util.*;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

/**
 * The inbound half of the exchange adapter against the real exchange-simulator: an order cancelled <em>at the exchange</em>,
 * not through the brokerage, so that only the exchange's {@code OrderCancelled} event can close it here. Once through the
 * event-log catch-up reader alone (no webhook reaches this test's application yet), once through a signed webhook.
 * <p>
 * Skipped unless the {@code exchange-simulator:dev} image exists locally (see {@link SimulatorImages}).
 */
@org.testcontainers.junit.jupiter.Testcontainers
@EnabledIf("dk.trustworks.essentials.examples.trading.integration.SimulatorImages#exchangeAvailable")
@SpringBootTest(properties = {
        "trading-demo.simulation.enabled=false",
        "trading-demo.load.enabled=false",
        "trading-demo.queue-load.enabled=false",
        "trading-demo.simulators.webhook-secret-file=simulators/secrets/webhook-secret.txt"
}, webEnvironment = SpringBootTest.WebEnvironment.DEFINED_PORT)
@AutoConfigureTestRestTemplate
@DirtiesContext // close while this class's containers are still up; a cached context outlives them
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class ExchangeOrderEventsIT {
    private static final Duration TIMEOUT = Duration.ofSeconds(60);
    private static final String   ACCOUNT = "ACC-EXCHANGE-EVENTS";

    /**
     * The port the application listens on, chosen before any container starts: Testcontainers makes a host port
     * reachable only from containers started after {@link Testcontainers#exposeHostPorts(int...)}.
     */
    private static final int APPLICATION_PORT = freePort();

    static {
        Testcontainers.exposeHostPorts(APPLICATION_PORT);
    }

    @Container
    static final PostgreSQLContainer postgres = new PostgreSQLContainer("postgres:18.4")
            .withDatabaseName("trading-demo-exchange-events-it-db")
            .withUsername("test")
            .withPassword("test");

    @Container
    static final GenericContainer<?> exchange = SimulatorImages.exchangeAsTheDemoRunsIt();

    @DynamicPropertySource
    static void registerProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
        registry.add("spring.datasource.driver-class-name", postgres::getDriverClassName);
        registry.add("server.port", () -> APPLICATION_PORT);
        registry.add("trading-demo.simulators.exchange.base-url", ExchangeOrderEventsIT::exchangeUrl);
    }

    @Autowired
    private TestRestTemplate restTemplate;

    @Test
    @Order(1)
    void an_order_cancelled_at_the_exchange_is_closed_from_the_exchange_event_log() {
        openFundedAccountOnce();
        var orderId = "ORD-EXCHANGE-CANCEL-LOG";
        placeRestingOrder(orderId);

        orders().cancelOrderByClientOrderId("DEMO-BROKER", orderId);

        await().atMost(TIMEOUT).untilAsserted(() -> assertThat(projectedOrder(orderId).status()).isEqualTo(OrderStatus.CANCELLED));
    }

    @Test
    @Order(2)
    void an_order_cancelled_at_the_exchange_is_closed_from_its_signed_webhook() throws IOException {
        openFundedAccountOnce();
        var subscription = new WebhooksApi(apiClient()).registerWebhook(new WebhookRequest().url(java.net.URI.create("http://host.testcontainers.internal:" + APPLICATION_PORT + "/webhooks/exchange/orders"))
                                                                                        .eventTypes(List.of("OrderAccepted", "OrderRejected", "OrderCancelled"))
                                                                                        .participants(List.of("DEMO-BROKER"))
                                                                                        .secret(Files.readString(Path.of("simulators/secrets/webhook-secret.txt")).trim()));
        var orderId = "ORD-EXCHANGE-CANCEL-WEBHOOK";
        placeRestingOrder(orderId);

        orders().cancelOrderByClientOrderId("DEMO-BROKER", orderId);

        await().atMost(TIMEOUT).untilAsserted(() -> assertThat(projectedOrder(orderId).status()).isEqualTo(OrderStatus.CANCELLED));
        await().atMost(TIMEOUT).untilAsserted(() -> assertThat(new WebhooksApi(apiClient()).listWebhookDeliveries(subscription.getId(), null, null, null))
                .as("the webhook delivered the events, signature verified and accepted")
                .isNotEmpty()
                .allSatisfy(delivery -> assertThat(delivery.getStatus()).isEqualTo(DeliveryStatus.DELIVERED)));
    }

    private boolean accountOpened;

    private void openFundedAccountOnce() {
        if (accountOpened) {
            return;
        }
        accountOpened = true;
        var opened = alice().postForEntity("/api/accounts", Map.of("accountId", ACCOUNT, "ownerId", "USR-ALICE", "currency", "USD"), Void.class);
        if (opened.getStatusCode() != HttpStatus.CREATED) {
            return; // opened by the first test; JUnit makes a new instance per test
        }
        var deposit = operator().postForEntity("/api/admin/accounts/" + ACCOUNT + "/deposits",
                                               Map.of("accountId", ACCOUNT, "depositId", "DEP-" + ACCOUNT, "amount", usd("10000.00"), "businessDate", "2030-01-07"),
                                               String.class);
        assertThat(deposit.getStatusCode()).as("%s", deposit.getBody()).isEqualTo(HttpStatus.OK);
        await().atMost(TIMEOUT).until(() -> alice().getForEntity("/api/accounts/" + ACCOUNT + "/orders", String.class).getStatusCode() == HttpStatus.OK);
    }

    /**
     * A buy far below the exchange's prices, so it rests there until it is cancelled.
     */
    private void placeRestingOrder(String orderId) {
        var placed = alice().postForEntity("/api/accounts/" + ACCOUNT + "/orders",
                                           Map.of("orderId", orderId, "accountId", ACCOUNT, "instrumentId", "ACME", "side", "BUY", "type", "LIMIT",
                                                  "quantity", 5, "limitPrice", usd("1.00"), "timeInForce", "GTC"),
                                           String.class);
        assertThat(placed.getStatusCode()).as("%s", placed.getBody()).isEqualTo(HttpStatus.CREATED);
        await().atMost(TIMEOUT).untilAsserted(() -> assertThat(projectedOrder(orderId).status()).isEqualTo(OrderStatus.OPEN));
    }

    private OrderView projectedOrder(String orderId) {
        var order = alice().getForEntity("/api/accounts/" + ACCOUNT + "/orders/" + orderId, OrderView.class).getBody();
        assertThat(order).as("order %s not projected yet", orderId).isNotNull();
        return order;
    }

    private TestRestTemplate operator() {
        return restTemplate.withBasicAuth("operator@demo.local", "operator-demo-password");
    }

    private TestRestTemplate alice() {
        return restTemplate.withBasicAuth("alice@demo.local", "alice-demo-password");
    }

    private static OrdersApi orders() {
        return new OrdersApi(apiClient());
    }

    private static ApiClient apiClient() {
        var apiClient = new ApiClient(RestClient.builder().build());
        apiClient.setBasePath(exchangeUrl());
        return apiClient;
    }

    private static String exchangeUrl() {
        return "http://" + exchange.getHost() + ":" + exchange.getMappedPort(8080);
    }

    private static Map<String, Object> usd(String amount) {
        return Map.of("amount", new BigDecimal(amount), "currency", "USD");
    }

    private static int freePort() {
        try (var socket = new ServerSocket(0)) {
            return socket.getLocalPort();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
