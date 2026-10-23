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

package dk.trustworks.essentials.examples.trading.settlement.external_systems.exchange;

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
 * The receiving side of the payment gateway's payment events: the webhook endpoint of the {@code demo-funding}
 * subscription declared in {@code simulators/gateway.yaml}, and the catch-up reader of the gateway's event log behind
 * it. Both feed {@code Funding:SettlementReports} through {@link ExternalEventIntake}, as {@code orders}' exchange events
 * feed its reports.
 * <p>
 * Active under the {@code simulators} profile only (gateway base URL and webhook secret configured).
 */
@RestController
@ConditionalOnProperty(prefix = "trading-demo.simulators", name = {"exchange.base-url", "webhook-secret-file"})
public class SettlementEvents {
    static final String CONSUMER = "settlement.settlement-events";

    private final SettlementReports           reports;
    private final WebhookSignatureVerifier verifier;
    private final ExternalEventIntake      intake;
    private final Dependencies             dependencies;
    private final RestClient               gateway;
    private volatile SignedWebhookReceiver   receiver;
    private volatile ExternalEventLogCatchUp catchUp;

    @Component
    @ConditionalOnProperty(prefix = "trading-demo.simulators", name = {"exchange.base-url", "webhook-secret-file"})
    public record Dependencies(FencedLockManager fencedLockManager,
                               JdbcTemplate jdbcTemplate,
                               ObjectMapper objectMapper,
                               RestClient.Builder restClientBuilder,
                               SimulatorProperties properties) {
        public Dependencies {
            requireNonNull(fencedLockManager, "No fencedLockManager provided");
            requireNonNull(jdbcTemplate, "No jdbcTemplate provided");
            requireNonNull(objectMapper, "No objectMapper provided");
            requireNonNull(restClientBuilder, "No restClientBuilder provided");
            requireNonNull(properties, "No properties provided");
        }
    }

    public SettlementEvents(SettlementReports reports, WebhookSignatureVerifier verifier, ExternalEventIntake intake, Dependencies dependencies) {
        this.reports = requireNonNull(reports, "No reports provided");
        this.verifier = requireNonNull(verifier, "No verifier provided");
        this.intake = requireNonNull(intake, "No intake provided");
        this.dependencies = requireNonNull(dependencies, "No dependencies provided");
        this.gateway = dependencies.restClientBuilder().clone().baseUrl(dependencies.properties().getExchange().getBaseUrl()).build();
    }

    @EventListener(ApplicationReadyEvent.class)
    public void startReceiving() {
        var settings = new SignedWebhookReceiver.Settings(CONSUMER,
                                                          SettlementEventTranslation.EVENT_TYPES,
                                                          SettlementEventTranslation::isComplete,
                                                          reports.inbox());
        receiver = new SignedWebhookReceiver(settings, verifier, intake, dependencies.objectMapper());
        catchUp = new ExternalEventLogCatchUp(new ExternalEventLogCatchUp.Settings("exchange:" + CONSUMER, gateway, settings, Duration.ofSeconds(5), 500,
                                                                                         Set.of(dependencies.properties().getExchange().getParticipantId())),
                                              intake,
                                              dependencies.fencedLockManager(),
                                              dependencies.jdbcTemplate(),
                                              dependencies.objectMapper());
        catchUp.start();
    }

    @PreDestroy
    public void stopReceiving() {
        var current = catchUp;
        if (current != null) {
            current.stop();
        }
    }

    @PostMapping("/webhooks/exchange/settlements")
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
