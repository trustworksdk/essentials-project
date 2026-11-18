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

package dk.trustworks.essentials.examples.trading.integration;

import com.sun.net.httpserver.HttpServer;
import dk.trustworks.essentials.components.foundation.fencedlock.FencedLockManager;
import dk.trustworks.essentials.components.foundation.messaging.RedeliveryPolicy;
import dk.trustworks.essentials.components.foundation.messaging.eip.store_and_forward.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIf;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.*;
import org.springframework.web.client.RestClient;
import org.testcontainers.Testcontainers;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.postgresql.PostgreSQLContainer;
import tools.jackson.databind.ObjectMapper;

import java.net.InetSocketAddress;
import java.time.*;
import java.util.*;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

/**
 * The receiving side against the real exchange-simulator, with its fault injection on: deliveries are duplicated,
 * damaged (missing fields) and held back to arrive out of order. Whatever the exchange does to its copies, every
 * canonical event of a subscribed type must end up in the consumer's inbox exactly once.
 * <p>
 * Skipped unless the {@code exchange-simulator:dev} image exists locally (see {@link SimulatorImages}).
 */
@EnabledIf("dk.trustworks.essentials.examples.trading.integration.SimulatorImages#exchangeAvailable")
@org.testcontainers.junit.jupiter.Testcontainers
@SpringBootTest(properties = {
        "trading-demo.queue-load.enabled=false"
})
@DirtiesContext // close while this class's container is still up; a cached context outlives it and stalls the next context switch
class ExchangeSimulatorWebhookIT {
    private static final String      SECRET      = "it-webhook-secret-0123456789";
    private static final String      CONSUMER    = "ExchangeSimulatorWebhookIT";
    private static final Set<String> EVENT_TYPES = Set.of("OrderAccepted", "OrderPartiallyFilled", "OrderFilled", "TradeExecuted");
    private static final int         ORDER_PAIRS = 25;

    /**
     * The webhook endpoint the exchange delivers to, on this JVM. Created and exposed before the containers below are
     * declared, because Testcontainers makes a host port reachable ({@code host.testcontainers.internal}) only from
     * containers started after {@code exposeHostPorts}. The test sets {@link #receiver} once its beans exist.
     */
    private static final    HttpServer            receiverServer;
    private static volatile SignedWebhookReceiver receiver;

    static {
        try {
            receiverServer = HttpServer.create(new InetSocketAddress(0), 0);
        } catch (java.io.IOException e) {
            throw new java.io.UncheckedIOException(e);
        }
        receiverServer.createContext("/webhooks/exchange", http -> {
            var current = receiver;
            var status = current == null
                         ? 503
                         : current.receive(http.getRequestHeaders().getFirst("X-Signature-Timestamp"),
                                           http.getRequestHeaders().getFirst("X-Signature"),
                                           http.getRequestBody().readAllBytes()).getStatusCode().value();
            http.sendResponseHeaders(status, -1);
            http.close();
        });
        receiverServer.start();
        Testcontainers.exposeHostPorts(receiverServer.getAddress().getPort());
    }

    @Container
    static final PostgreSQLContainer postgres = new PostgreSQLContainer("postgres:18.4")
            .withDatabaseName("trading-demo-exchange-it-db")
            .withUsername("test")
            .withPassword("test");

    @Container
    static final GenericContainer<?> exchange = SimulatorImages.exchange();

    @DynamicPropertySource
    static void registerProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
        registry.add("spring.datasource.driver-class-name", postgres::getDriverClassName);
    }

    /**
     * Static: the inbox is created once by name and its consumer outlives the test instance that created it.
     */
    private static final List<ExternalEventEnvelope> received = new CopyOnWriteArrayList<>();

    @Autowired
    private ExternalEventIntake intake;
    @Autowired
    private Inboxes             inboxes;
    @Autowired
    private FencedLockManager   fencedLockManager;
    @Autowired
    private JdbcTemplate        jdbcTemplate;
    @Autowired
    private ObjectMapper        objectMapper;

    private RestClient exchangeApi;

    @AfterAll
    static void stopReceiver() {
        receiverServer.stop(0);
    }

    @Test
    void every_subscribed_event_reaches_the_inbox_exactly_once_despite_duplicates_damage_and_reordering() {
        exchangeApi = RestClient.create("http://" + exchange.getHost() + ":" + exchange.getMappedPort(8080));
        var inbox = inboxes.getOrCreateInbox(InboxConfig.builder()
                                                        .inboxName(InboxName.of(CONSUMER))
                                                        .messageConsumptionMode(MessageConsumptionMode.SingleGlobalConsumer)
                                                        .redeliveryPolicy(RedeliveryPolicy.fixedBackoff(Duration.ofMillis(100), 3))
                                                        .build(),
                                             message -> received.add((ExternalEventEnvelope) message.getPayload()));
        var target = new SignedWebhookReceiver.Settings(CONSUMER,
                                                        EVENT_TYPES,
                                                        envelope -> envelope.payload().hasNonNull("instrumentId"),
                                                        inbox);
        receiver = new SignedWebhookReceiver(target,
                                                 new WebhookSignatureVerifier(SECRET, Duration.ofMinutes(5), Clock.systemUTC()),
                                                 SimulatorEventSchemas.load(objectMapper),
                                                 intake,
                                                 objectMapper);
        var subscriptionId = subscribe();

        // Fault injection on this subscription's copies only; the canonical events are never changed
        exchangeApi.put().uri("/test/webhooks/{id}/faults", subscriptionId)
                   .contentType(MediaType.APPLICATION_JSON)
                   .body(Map.of("seed", 42,
                                "missing", Map.of("probability", 0.3, "paths", List.of("payload.instrumentId")),
                                "duplicateProbability", 0.3,
                                "outOfOrder", Map.of("probability", 0.3, "holdMs", 500)))
                   .retrieve().toBodilessEntity();

        trade();

        var canonical = canonicalEventIds();
        assertThat(canonical).as("the exchange produced events of the subscribed types").isNotEmpty();
        await().atMost(Duration.ofSeconds(90))
               .untilAsserted(() -> assertThat(received.stream().map(ExternalEventEnvelope::eventId).toList())
                       .containsExactlyInAnyOrderElementsOf(canonical));

        // The faults really happened: otherwise this test proved nothing about them
        var attempts = objectMapper.readTree(exchangeApi.get().uri("/test/attempts?subscriptionId={id}", subscriptionId).retrieve().body(byte[].class));
        var faults   = new HashSet<String>();
        for (var i = 0; i < attempts.size(); i++) {
            var attemptFaults = attempts.get(i).get("faults");
            for (var j = 0; j < attemptFaults.size(); j++) {
                faults.add(attemptFaults.get(j).asString().replaceAll(":.*", ""));
            }
        }
        assertThat(faults).as("fault kinds injected").contains("missing", "duplicate");

        // And the catch-up reader, run after the fact, finds nothing the webhook missed
        var catchUp = new ExternalEventLogCatchUp(new ExternalEventLogCatchUp.Settings(CONSUMER + "-catch-up", exchangeApi, target, Duration.ofSeconds(1), 100),
                                                  intake, fencedLockManager, jdbcTemplate, objectMapper);
        assertThat(catchUp.readUntilCaughtUp()).isZero();
        assertThat(received).hasSize(canonical.size());
    }

    private String subscribe() {
        var port = receiverServer.getAddress().getPort();
        var subscription = objectMapper.readTree(exchangeApi.post().uri("/api/webhooks")
                                                            .contentType(MediaType.APPLICATION_JSON)
                                                            .body(Map.of("url", "http://host.testcontainers.internal:" + port + "/webhooks/exchange",
                                                                         "eventTypes", List.copyOf(EVENT_TYPES),
                                                                         "secret", SECRET,
                                                                         "ordered", false))
                                                            .retrieve().body(byte[].class));
        return subscription.get("id").asString();
    }

    private void trade() {
        post("/api/instruments", Map.of("id", "ACME", "symbol", "ACME", "mic", "XNYS", "currency", "USD", "currencyScale", 2));
        post("/api/participants", Map.of("id", "FIRM", "name", "The demo brokerage"));
        post("/api/participants", Map.of("id", "STREET", "name", "Everyone else"));
        for (var i = 0; i < ORDER_PAIRS; i++) {
            post("/api/orders", Map.of("instrumentId", "ACME", "participantId", "STREET", "side", "SELL", "type", "LIMIT", "priceTicks", 10_000 + i, "quantity", 10));
            post("/api/orders", Map.of("clientOrderId", "O-" + i, "instrumentId", "ACME", "participantId", "FIRM", "side", "BUY", "type", "LIMIT", "priceTicks", 10_000 + i, "quantity", 5));
            post("/api/orders", Map.of("clientOrderId", "O-" + i + "-b", "instrumentId", "ACME", "participantId", "FIRM", "side", "BUY", "type", "MARKET", "quantity", 5));
        }
    }

    private void post(String path, Map<String, ?> body) {
        exchangeApi.post().uri(path).contentType(MediaType.APPLICATION_JSON).body(body).retrieve().toBodilessEntity();
    }

    private List<String> canonicalEventIds() {
        var ids   = new ArrayList<String>();
        var after = 0L;
        while (true) {
            var page = objectMapper.readTree(exchangeApi.get().uri("/api/events?after={after}&limit=1000", after).retrieve().body(byte[].class));
            if (page.isEmpty()) {
                return ids;
            }
            for (var i = 0; i < page.size(); i++) {
                var event = page.get(i);
                after = event.get("sequence").asLong();
                if (EVENT_TYPES.contains(event.get("eventType").asString())) {
                    ids.add(event.get("eventId").asString());
                }
            }
        }
    }
}
