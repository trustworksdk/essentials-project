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

import dk.trustworks.essentials.components.foundation.fencedlock.FencedLockManager;
import dk.trustworks.essentials.examples.trading.integration.*;
import jakarta.annotation.PreDestroy;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.http.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.ObjectMapper;

import java.time.Duration;
import java.util.Set;

import static dk.trustworks.essentials.shared.FailFast.requireNonNull;

/**
 * The receiving side of the exchange's order lifecycle events (design §4, "Inbound"): the webhook endpoint of the
 * {@code demo-orders} subscription declared in {@code simulators/exchange.yaml}, and the catch-up reader of the
 * exchange's event log behind it. Both feed {@code Orders:ExchangeReports} through {@link ExternalEventIntake}, which
 * deduplicates per event, and {@link ExchangeReports} translates each event as it is consumed.
 * <p>
 * Active under the {@code simulators} profile only (exchange base URL and webhook secret configured). Built once the
 * application is ready, as {@link ExchangeReports} creates its inbox then; a delivery that arrives before is answered
 * {@code 503}, and the exchange retries it.
 */
@RestController
@ConditionalOnProperty(prefix = "trading-demo.simulators", name = {"exchange.base-url", "webhook-secret-file"})
public class ExchangeOrderEvents {
    static final String CONSUMER = "orders.exchange-order-events";

    private final ExchangeReports          reports;
    private final WebhookSignatureVerifier verifier;
    private final SimulatorEventSchemas schemas;
    private final ExternalEventIntake      intake;
    private final FencedLockManager        fencedLockManager;
    private final JdbcTemplate             jdbcTemplate;
    private final ObjectMapper             objectMapper;
    private final RestClient               exchange;
    private final String                   participantId;
    private volatile SignedWebhookReceiver  receiver;
    private volatile ExternalEventLogCatchUp catchUp;

    public ExchangeOrderEvents(ExchangeReports reports,
                               WebhookSignatureVerifier verifier,
                               SimulatorEventSchemas schemas,
                               ExternalEventIntake intake,
                               ExternalEventsDependencies dependencies) {
        this.reports = requireNonNull(reports, "No reports provided");
        this.verifier = requireNonNull(verifier, "No verifier provided");
        this.schemas = requireNonNull(schemas, "No schemas provided");
        this.intake = requireNonNull(intake, "No intake provided");
        requireNonNull(dependencies, "No dependencies provided");
        this.fencedLockManager = dependencies.fencedLockManager();
        this.jdbcTemplate = dependencies.jdbcTemplate();
        this.objectMapper = dependencies.objectMapper();
        this.exchange = dependencies.restClientBuilder().clone().baseUrl(dependencies.properties().getExchange().getBaseUrl()).build();
        this.participantId = dependencies.properties().getExchange().getParticipantId();
    }

    /**
     * What the receiver and the catch-up reader need beyond the slice's own collaborators.
     */
    @Component
    @ConditionalOnProperty(prefix = "trading-demo.simulators", name = {"exchange.base-url", "webhook-secret-file"})
    public record ExternalEventsDependencies(FencedLockManager fencedLockManager,
                                             JdbcTemplate jdbcTemplate,
                                             ObjectMapper objectMapper,
                                             RestClient.Builder restClientBuilder,
                                             SimulatorProperties properties) {
        public ExternalEventsDependencies {
            requireNonNull(fencedLockManager, "No fencedLockManager provided");
            requireNonNull(jdbcTemplate, "No jdbcTemplate provided");
            requireNonNull(objectMapper, "No objectMapper provided");
            requireNonNull(restClientBuilder, "No restClientBuilder provided");
            requireNonNull(properties, "No properties provided");
        }
    }

    @EventListener(ApplicationReadyEvent.class)
    public void startReceiving() {
        var settings = new SignedWebhookReceiver.Settings(CONSUMER,
                                                          ExchangeOrderEventTranslation.EVENT_TYPES,
                                                          ExchangeOrderEventTranslation::isComplete,
                                                          reports.inbox());
        receiver = new SignedWebhookReceiver(settings, verifier, schemas, intake, objectMapper);
        catchUp = new ExternalEventLogCatchUp(new ExternalEventLogCatchUp.Settings("exchange:" + CONSUMER, exchange, settings, Duration.ofSeconds(5), 500,
                                                                                         Set.of(participantId)),
                                              intake,
                                              fencedLockManager,
                                              jdbcTemplate,
                                              objectMapper);
        catchUp.start();
    }

    @PreDestroy
    public void stopReceiving() {
        var current = catchUp;
        if (current != null) {
            current.stop();
        }
    }

    @PostMapping("/webhooks/exchange/orders")
    public ResponseEntity<Void> receive(@RequestHeader(name = "X-Signature-Timestamp", required = false) String signatureTimestamp,
                                        @RequestHeader(name = "X-Signature", required = false) String signature,
                                        @RequestBody byte[] rawBody) {
        var current = receiver;
        if (current == null) {
            return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).build();
        }
        return current.receive(signatureTimestamp, signature, rawBody);
    }
}
