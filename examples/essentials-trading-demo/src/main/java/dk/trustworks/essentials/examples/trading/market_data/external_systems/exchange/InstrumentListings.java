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

package dk.trustworks.essentials.examples.trading.market_data.external_systems.exchange;

import dk.trustworks.essentials.components.foundation.fencedlock.FencedLockManager;
import dk.trustworks.essentials.components.foundation.messaging.RedeliveryPolicy;
import dk.trustworks.essentials.components.foundation.messaging.eip.store_and_forward.*;
import dk.trustworks.essentials.components.foundation.messaging.queue.Message;
import dk.trustworks.essentials.examples.trading.integration.*;
import dk.trustworks.essentials.examples.trading.market_data.types.*;
import dk.trustworks.essentials.examples.trading.market_data.use_cases.register_instrument.RegisterInstrument;
import dk.trustworks.essentials.examples.trading.market_data.use_cases.suspend_instrument.SuspendInstrument;
import dk.trustworks.essentials.reactive.command.CommandBus;
import jakarta.annotation.PreDestroy;
import org.slf4j.*;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.http.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.*;

import java.time.*;
import java.util.*;

import static dk.trustworks.essentials.shared.FailFast.requireNonNull;

/**
 * The exchange's instrument events into {@code market_data} (D18, D21, SR-28): the exchange owns the instruments, and
 * the brokerage decides which it offers. {@code InstrumentListed} registers an instrument here - not offered until
 * {@code risk_approve_instrument} has approved it - and {@code InstrumentUpdated} with {@code enabled: false}
 * suspends it. The {@code demo-instruments} webhook subscription ({@code simulators/exchange.yaml}) and a catch-up
 * reader of the exchange's event log, which on a fresh database replays every listing. Simulators profile only:
 * without the exchange, instruments are registered through {@code POST /api/admin/instruments}.
 * <p>
 * The exchange names no display name; the symbol stands in. {@code RegisterInstrument} is idempotent, so a listing
 * seen again (after an exchange reset) registers nothing.
 */
@RestController
@ConditionalOnProperty(prefix = "trading-demo.simulators", name = {"exchange.base-url", "webhook-secret-file"})
public class InstrumentListings {
    static final         String      CONSUMER    = "market-data.instruments";
    private static final InboxName   INBOX_NAME  = InboxName.of("MarketData:Instruments");
    private static final String      LISTED      = "InstrumentListed";
    private static final String      UPDATED     = "InstrumentUpdated";

    private final Inboxes                  inboxes;
    private final WebhookSignatureVerifier verifier;
    private final SimulatorEventSchemas schemas;
    private final ExternalEventIntake      intake;
    private final Dependencies             dependencies;
    private final RestClient               exchange;
    private volatile SignedWebhookReceiver   receiver;
    private volatile ExternalEventLogCatchUp catchUp;

    @Component
    @ConditionalOnProperty(prefix = "trading-demo.simulators", name = {"exchange.base-url", "webhook-secret-file"})
    public record Dependencies(FencedLockManager fencedLockManager,
                               JdbcTemplate jdbcTemplate,
                               ObjectMapper objectMapper,
                               RestClient.Builder restClientBuilder,
                               SimulatorProperties properties,
                               CommandBus commandBus) {
        public Dependencies {
            requireNonNull(fencedLockManager, "No fencedLockManager provided");
            requireNonNull(jdbcTemplate, "No jdbcTemplate provided");
            requireNonNull(objectMapper, "No objectMapper provided");
            requireNonNull(restClientBuilder, "No restClientBuilder provided");
            requireNonNull(properties, "No properties provided");
            requireNonNull(commandBus, "No commandBus provided");
        }
    }

    public InstrumentListings(Inboxes inboxes, WebhookSignatureVerifier verifier, SimulatorEventSchemas schemas, ExternalEventIntake intake, Dependencies dependencies) {
        this.inboxes = requireNonNull(inboxes, "No inboxes provided");
        this.verifier = requireNonNull(verifier, "No verifier provided");
        this.schemas = requireNonNull(schemas, "No schemas provided");
        this.intake = requireNonNull(intake, "No intake provided");
        this.dependencies = requireNonNull(dependencies, "No dependencies provided");
        this.exchange = dependencies.restClientBuilder().clone().baseUrl(dependencies.properties().getExchange().getBaseUrl()).build();
    }

    @EventListener(ApplicationReadyEvent.class)
    public void startReceiving() {
        var inbox = inboxes.getOrCreateInbox(InboxConfig.builder()
                                                        .inboxName(INBOX_NAME)
                                                        .messageConsumptionMode(MessageConsumptionMode.GlobalCompetingConsumers)
                                                        .numberOfParallelMessageConsumers(1)
                                                        .redeliveryPolicy(RedeliveryPolicy.fixedBackoff(Duration.ofMillis(200), 20))
                                                        .build(),
                                             this::record);
        var settings = new SignedWebhookReceiver.Settings(CONSUMER, Set.of(LISTED, UPDATED), InstrumentListings::isComplete, inbox);
        receiver = new SignedWebhookReceiver(settings, verifier, schemas, intake, dependencies.objectMapper());
        catchUp = new ExternalEventLogCatchUp(new ExternalEventLogCatchUp.Settings("exchange:" + CONSUMER, exchange, settings, Duration.ofSeconds(5), 500),
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

    @PostMapping("/webhooks/exchange/instruments")
    public ResponseEntity<Void> receive(@RequestHeader(name = "X-Signature-Timestamp", required = false) String signatureTimestamp,
                                        @RequestHeader(name = "X-Signature", required = false) String signature,
                                        @RequestBody byte[] rawBody) {
        var current = receiver;
        if (current == null) {
            return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).build();
        }
        return current.receive(signatureTimestamp, signature, rawBody);
    }

    static boolean isComplete(ExternalEventEnvelope envelope) {
        var payload = envelope.payload();
        var enabled = payload.get("enabled");
        return text(payload, "instrumentId").isPresent() && text(payload, "symbol").isPresent() && enabled != null && enabled.isBoolean();
    }

    private void record(Message message) {
        var envelope     = (ExternalEventEnvelope) message.getPayload();
        var payload      = envelope.payload();
        var instrumentId = InstrumentId.of(text(payload, "instrumentId").orElseThrow());
        var symbol       = text(payload, "symbol").orElseThrow();
        if (LISTED.equals(envelope.eventType())) {
            dependencies.commandBus().send(new RegisterInstrument(instrumentId, Symbol.of(symbol), symbol));
        } else if (!payload.get("enabled").asBoolean()) {
            dependencies.commandBus().send(new SuspendInstrument(instrumentId, "DISABLED_AT_EXCHANGE"));
        }
    }

    private static Optional<String> text(JsonNode payload, String field) {
        var node = payload.get(field);
        return node != null && node.isString() && !node.asString().isBlank() ? Optional.of(node.asString()) : Optional.empty();
    }
}
