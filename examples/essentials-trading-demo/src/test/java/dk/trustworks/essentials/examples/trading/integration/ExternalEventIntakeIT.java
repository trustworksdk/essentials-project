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
import dk.trustworks.essentials.components.foundation.messaging.queue.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.*;
import org.springframework.web.client.RestClient;
import org.testcontainers.junit.jupiter.*;
import org.testcontainers.postgresql.PostgreSQLContainer;
import tools.jackson.databind.ObjectMapper;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.*;
import java.util.*;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

/**
 * The receiving side of a simulator subscription, end to end against a real database and inbox: a signed webhook
 * delivery and the catch-up read of the event log both land in the consumer's inbox, once per {@code eventId}, and a
 * damaged copy lands nowhere. The source is a stub HTTP server serving {@code /api/events}; no simulator image needed.
 */
@Testcontainers
@SpringBootTest(properties = {
        "trading-demo.queue-load.enabled=false"
})
@DirtiesContext // close while this class's container is still up; a cached context outlives it and stalls the next context switch
class ExternalEventIntakeIT {
    private static final String SECRET   = "whsec_test_0123456789";
    private static final String CONSUMER = "IntakeIT:orders";

    @Container
    static final PostgreSQLContainer postgres = new PostgreSQLContainer("postgres:18.4")
            .withDatabaseName("trading-demo-intake-test-db")
            .withUsername("test")
            .withPassword("test");

    @DynamicPropertySource
    static void registerProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
        registry.add("spring.datasource.driver-class-name", postgres::getDriverClassName);
    }

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

    /**
     * Static on purpose: {@code getOrCreateInbox} returns the inbox the first test method created, whose consumer
     * captured that method's test instance. An instance field would leave every later method watching a list nothing
     * writes to.
     */
    private static final List<ExternalEventEnvelope> received = new CopyOnWriteArrayList<>();
    private final AtomicReference<String>     sourceLog  = new AtomicReference<>("[]");
    private       HttpServer                  source;
    private       SignedWebhookReceiver       receiver;
    private       ExternalEventLogCatchUp     catchUp;
    private       WebhookSignatureVerifier    verifier;

    @BeforeEach
    void setUp() throws Exception {
        jdbcTemplate.execute("DELETE FROM external_inbound_event");
        received.clear();

        source = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        source.createContext("/api/events", exchange -> {
            var after = Long.parseLong(Optional.ofNullable(exchange.getRequestURI().getQuery()).orElse("after=0")
                                               .replaceAll(".*after=(\\d+).*", "$1"));
            var page  = objectMapper.createArrayNode();
            var all   = objectMapper.readTree(sourceLog.get());
            for (var i = 0; i < all.size(); i++) {
                if (all.get(i).get("sequence").asLong() > after) {
                    page.add(all.get(i));
                }
            }
            var body = objectMapper.writeValueAsBytes(page);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        source.start();

        var inbox = inboxes.getOrCreateInbox(InboxConfig.builder()
                                                        .inboxName(InboxName.of(CONSUMER))
                                                        .messageConsumptionMode(MessageConsumptionMode.SingleGlobalConsumer)
                                                        .redeliveryPolicy(RedeliveryPolicy.fixedBackoff(Duration.ofMillis(100), 3))
                                                        .build(),
                                             message -> received.add((ExternalEventEnvelope) message.getPayload()));
        var target = new SignedWebhookReceiver.Settings(CONSUMER,
                                                        Set.of("OrderAccepted", "OrderFilled"),
                                                        envelope -> envelope.payload().hasNonNull("clientOrderId"),
                                                        inbox);
        verifier = new WebhookSignatureVerifier(SECRET, Duration.ofMinutes(5), Clock.systemUTC());
        receiver = new SignedWebhookReceiver(target, verifier, SimulatorEventSchemas.load(objectMapper), intake, objectMapper);
        catchUp = new ExternalEventLogCatchUp(new ExternalEventLogCatchUp.Settings("IntakeIT-" + UUID.randomUUID(),
                                                                                   RestClient.create("http://127.0.0.1:" + source.getAddress().getPort()),
                                                                                   target,
                                                                                   Duration.ofSeconds(1),
                                                                                   2),
                                              intake,
                                              fencedLockManager,
                                              jdbcTemplate,
                                              objectMapper);
    }

    @AfterEach
    void tearDown() {
        source.stop(0);
    }

    @Test
    void a_signed_delivery_is_queued_once_however_often_it_is_delivered() {
        var event = event("e-1", 1, "OrderAccepted", "O-1");

        assertThat(deliver(event).getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);
        assertThat(deliver(event).getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);

        await().atMost(Duration.ofSeconds(10)).until(() -> received.size() == 1);
        assertThat(received.getFirst().eventId()).isEqualTo("e-1");
        assertThat(received.getFirst().occurredAt()).isEqualTo(OffsetDateTime.parse("2030-01-07T09:00:00Z"));
        assertThat(recordedEventIds()).containsExactly("e-1");
    }

    @Test
    void a_bad_signature_is_refused_and_records_nothing() {
        var body = event("e-2", 2, "OrderAccepted", "O-2").getBytes(StandardCharsets.UTF_8);
        var now  = String.valueOf(Instant.now().getEpochSecond());

        var response = receiver.receive(now, "sha256=00", body);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(recordedEventIds()).isEmpty();
    }

    @Test
    void a_damaged_copy_is_refused_unrecorded_so_the_good_copy_of_the_retry_still_lands() {
        var damaged = """
                {"eventId":"e-3","sequence":3,"eventType":"OrderAccepted","schemaVersion":1,"source":"exchange",
                 "simulationId":null,"aggregateId":"O-3","correlationId":null,"occurredAt":"2030-01-07T09:00:00Z",
                 "payload":{"clientOrderId":null}}""";

        assertThat(deliver(damaged).getStatusCode().value()).isEqualTo(422);
        assertThat(recordedEventIds()).isEmpty();

        assertThat(deliver(event("e-3", 3, "OrderAccepted", "O-3")).getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);
        await().atMost(Duration.ofSeconds(10)).until(() -> received.size() == 1);
    }

    @Test
    void an_event_type_the_subscription_does_not_process_is_acknowledged_and_ignored() {
        assertThat(deliver(event("e-4", 4, "SettlementPending", "X-4")).getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);
        assertThat(recordedEventIds()).isEmpty();
    }

    @Test
    void the_catch_up_read_fills_in_what_the_webhook_missed_and_nothing_else() {
        deliver(event("e-10", 10, "OrderAccepted", "O-10"));
        sourceLog.set("[" + String.join(",",
                                        event("e-10", 10, "OrderAccepted", "O-10"),     // already delivered by webhook
                                        event("e-11", 11, "TradeExecuted", "X-11"),     // a type this consumer does not take
                                        event("e-12", 12, "OrderFilled", "O-10"),       // dead-lettered at the source
                                        event("e-13", 13, "OrderAccepted", "O-13")) + "]");

        // Page size 2, so this also walks more than one page
        assertThat(catchUp.readUntilCaughtUp()).isEqualTo(2);
        assertThat(catchUp.cursor()).isEqualTo(13);
        assertThat(catchUp.readUntilCaughtUp()).isZero();

        await().atMost(Duration.ofSeconds(10)).until(() -> received.size() == 3);
        assertThat(recordedEventIds()).containsExactlyInAnyOrder("e-10", "e-12", "e-13");
    }

    private org.springframework.http.ResponseEntity<Void> deliver(String json) {
        var body      = json.getBytes(StandardCharsets.UTF_8);
        var timestamp = String.valueOf(Instant.now().getEpochSecond());
        return receiver.receive(timestamp, verifier.sign(timestamp, body), body);
    }

    private List<String> recordedEventIds() {
        return jdbcTemplate.queryForList("SELECT event_id FROM external_inbound_event WHERE consumer = ?", String.class, CONSUMER);
    }

    /**
     * A canonical event of the type, captured from the exchange ({@code simulator-events/canonical-events.json}), so it
     * carries everything its schema requires and the receiver takes it; ids, order, aggregate and time are this test's.
     */
    private static String event(String eventId, long sequence, String eventType, String aggregateId) {
        var mapper = tools.jackson.databind.json.JsonMapper.builder().build();
        try (var in = ExternalEventIntakeIT.class.getClassLoader().getResourceAsStream("simulator-events/canonical-events.json")) {
            for (var canonical : mapper.readTree(in).path("exchange")) {
                if (canonical.path("eventType").asString().equals(eventType)) {
                    var event = (tools.jackson.databind.node.ObjectNode) canonical.deepCopy();
                    event.put("eventId", eventId).put("sequence", sequence).put("aggregateId", aggregateId).put("occurredAt", "2030-01-07T09:00:00Z");
                    if (event.path("payload").has("clientOrderId")) {
                        ((tools.jackson.databind.node.ObjectNode) event.get("payload")).put("clientOrderId", aggregateId);
                    }
                    return mapper.writeValueAsString(event);
                }
            }
        } catch (java.io.IOException e) {
            throw new java.io.UncheckedIOException(e);
        }
        throw new IllegalArgumentException("No canonical '" + eventType + "' event in the fixture");
    }
}
