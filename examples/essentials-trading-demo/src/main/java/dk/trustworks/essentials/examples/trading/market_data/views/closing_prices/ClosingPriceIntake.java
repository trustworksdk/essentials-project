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

package dk.trustworks.essentials.examples.trading.market_data.views.closing_prices;

import dk.trustworks.essentials.components.foundation.fencedlock.FencedLockManager;
import dk.trustworks.essentials.components.foundation.messaging.RedeliveryPolicy;
import dk.trustworks.essentials.components.foundation.messaging.eip.store_and_forward.*;
import dk.trustworks.essentials.components.foundation.messaging.queue.Message;
import dk.trustworks.essentials.examples.trading.integration.*;
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

import java.math.BigDecimal;
import java.sql.Date;
import java.time.*;
import java.util.*;

import static dk.trustworks.essentials.shared.FailFast.requireNonNull;

/**
 * The exchange's {@code ClosingPriceIssued} (SR-14) into table {@code closing_price}, which {@link StoredClosingPrices}
 * reads: the {@code demo-closing-prices} webhook subscription ({@code simulators/exchange.yaml}) and a catch-up reader of
 * the exchange's event log. One row per instrument and business day; a price is reference data from outside, stored
 * as issued. Simulators profile only.
 */
@RestController
@ConditionalOnProperty(prefix = "trading-demo.simulators", name = {"exchange.base-url", "webhook-secret-file"})
public class ClosingPriceIntake {
    static final         String    CONSUMER   = "market-data.closing-prices";
    private static final InboxName INBOX_NAME = InboxName.of("MarketData:ClosingPrices");
    private static final String    EVENT_TYPE = "ClosingPriceIssued";

    private final Inboxes                  inboxes;
    private final WebhookSignatureVerifier verifier;
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
                               StoredClosingPrices store) {
        public Dependencies {
            requireNonNull(fencedLockManager, "No fencedLockManager provided");
            requireNonNull(jdbcTemplate, "No jdbcTemplate provided");
            requireNonNull(objectMapper, "No objectMapper provided");
            requireNonNull(restClientBuilder, "No restClientBuilder provided");
            requireNonNull(properties, "No properties provided");
            requireNonNull(store, "No store provided");
        }
    }

    public ClosingPriceIntake(Inboxes inboxes, WebhookSignatureVerifier verifier, ExternalEventIntake intake, Dependencies dependencies) {
        this.inboxes = requireNonNull(inboxes, "No inboxes provided");
        this.verifier = requireNonNull(verifier, "No verifier provided");
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
        var settings = new SignedWebhookReceiver.Settings(CONSUMER, Set.of(EVENT_TYPE), ClosingPriceIntake::isComplete, inbox);
        receiver = new SignedWebhookReceiver(settings, verifier, intake, dependencies.objectMapper());
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

    @PostMapping("/webhooks/exchange/closing-prices")
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
        return text(payload, "instrumentId").isPresent() && date(payload, "businessDate").isPresent() && decimal(payload, "closingPrice").isPresent()
                && text(payload, "currency").isPresent() && text(payload, "basis").isPresent();
    }

    private void record(Message message) {
        var payload = ((ExternalEventEnvelope) message.getPayload()).payload();
        var volume  = payload.get("volume");
        dependencies.jdbcTemplate().update("""
                                                   INSERT INTO closing_price (instrument_id, business_date, price, currency, basis, volume)
                                                   VALUES (?, ?, ?, ?, ?, ?)
                                                   ON CONFLICT (instrument_id, business_date) DO NOTHING
                                                   """,
                                           text(payload, "instrumentId").orElseThrow(),
                                           Date.valueOf(date(payload, "businessDate").orElseThrow()),
                                           decimal(payload, "closingPrice").orElseThrow(),
                                           text(payload, "currency").orElseThrow(),
                                           text(payload, "basis").orElseThrow(),
                                           volume != null && volume.canConvertToLong() ? volume.asLong() : 0L);
    }

    private static Optional<String> text(JsonNode payload, String field) {
        var node = payload.get(field);
        return node != null && node.isString() && !node.asString().isBlank() ? Optional.of(node.asString()) : Optional.empty();
    }

    private static Optional<LocalDate> date(JsonNode payload, String field) {
        try {
            return text(payload, field).map(LocalDate::parse);
        } catch (DateTimeException e) {
            return Optional.empty();
        }
    }

    private static Optional<BigDecimal> decimal(JsonNode payload, String field) {
        try {
            return text(payload, field).map(BigDecimal::new);
        } catch (NumberFormatException e) {
            return Optional.empty();
        }
    }
}
