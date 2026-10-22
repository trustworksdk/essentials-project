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

import dk.trustworks.essentials.components.foundation.fencedlock.FencedLockManager;
import dk.trustworks.essentials.components.foundation.messaging.RedeliveryPolicy;
import dk.trustworks.essentials.components.foundation.messaging.eip.store_and_forward.*;
import dk.trustworks.essentials.components.foundation.messaging.queue.Message;
import jakarta.annotation.PreDestroy;
import org.slf4j.*;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.event.EventListener;
import org.springframework.http.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.*;

import java.sql.Timestamp;
import java.time.*;
import java.util.*;

import static dk.trustworks.essentials.shared.FailFast.requireNonNull;

/**
 * The business days as the exchange closes them (D10, SR-26): a table of closed business days, fed by the exchange's
 * {@code BusinessDayClosed} through the {@code demo-business-calendar} webhook subscription
 * ({@code simulators/exchange.yaml}) and a catch-up reader of its event log. The demo's only source of business dates
 * that are not on an event already - never worked out from a clock. The "table fed by {@code BusinessDayClosed}" D24
 * plans for EF-1, which will read it too.
 * <p>
 * Each newly closed day is announced in process as a {@link BusinessDayClosedNotice}, published inside the unit of work
 * that records it. A listener should take it after commit ({@code @TransactionalEventListener}), so its own failure
 * cannot roll the recorded day back, and must be idempotent and not rely on seeing every notice: a crash after the
 * commit loses one, and another instance may have recorded the day. Acting on {@link #latestClosedBusinessDate()} at
 * startup, as well, makes up for both.
 * <p>
 * Active under the {@code simulators} profile only; without the exchange there is no calendar, and
 * {@link #latestClosedBusinessDate()} answers empty.
 */
@RestController
@ConditionalOnProperty(prefix = "trading-demo.simulators", name = {"exchange.base-url", "webhook-secret-file"})
public class BusinessCalendar {
    private static final Logger    log          = LoggerFactory.getLogger(BusinessCalendar.class);
    static final         String    CONSUMER     = "integration.business-calendar";
    private static final InboxName INBOX_NAME   = InboxName.of("Integration:BusinessCalendar");
    private static final String    EVENT_TYPE   = "BusinessDayClosed";

    private final Inboxes                   inboxes;
    private final WebhookSignatureVerifier  verifier;
    private final ExternalEventIntake       intake;
    private final Dependencies              dependencies;
    private final RestClient                exchange;
    private volatile SignedWebhookReceiver   receiver;
    private volatile ExternalEventLogCatchUp catchUp;
    private final    NavigableSet<LocalDate> businessDays = new java.util.concurrent.ConcurrentSkipListSet<>();
    /**
     * {@code [from, to]} the cached {@link #businessDays} cover; {@code null} before the first fetch.
     */
    private volatile LocalDate[]             calendarRange;

    /**
     * A business day the exchange has closed.
     *
     * @param businessDate     the day that ended
     * @param nextBusinessDate the business day that started
     */
    public record BusinessDayClosedNotice(LocalDate businessDate, LocalDate nextBusinessDate) {
        public BusinessDayClosedNotice {
            requireNonNull(businessDate, "No businessDate provided");
            requireNonNull(nextBusinessDate, "No nextBusinessDate provided");
        }
    }

    /**
     * What the calendar needs beyond the receiving mechanics.
     */
    @Component
    @ConditionalOnProperty(prefix = "trading-demo.simulators", name = {"exchange.base-url", "webhook-secret-file"})
    public record Dependencies(FencedLockManager fencedLockManager,
                               JdbcTemplate jdbcTemplate,
                               ObjectMapper objectMapper,
                               RestClient.Builder restClientBuilder,
                               ApplicationEventPublisher publisher,
                               SimulatorProperties properties) {
        public Dependencies {
            requireNonNull(fencedLockManager, "No fencedLockManager provided");
            requireNonNull(jdbcTemplate, "No jdbcTemplate provided");
            requireNonNull(objectMapper, "No objectMapper provided");
            requireNonNull(restClientBuilder, "No restClientBuilder provided");
            requireNonNull(publisher, "No publisher provided");
            requireNonNull(properties, "No properties provided");
        }
    }

    public BusinessCalendar(Inboxes inboxes, WebhookSignatureVerifier verifier, ExternalEventIntake intake, Dependencies dependencies) {
        this.inboxes = requireNonNull(inboxes, "No inboxes provided");
        this.verifier = requireNonNull(verifier, "No verifier provided");
        this.intake = requireNonNull(intake, "No intake provided");
        this.dependencies = requireNonNull(dependencies, "No dependencies provided");
        this.exchange = dependencies.restClientBuilder().clone().baseUrl(dependencies.properties().getExchange().getBaseUrl()).build();
        dependencies.jdbcTemplate().execute("""
                                                    CREATE TABLE IF NOT EXISTS business_day_closed (
                                                        business_date DATE PRIMARY KEY,
                                                        next_business_date DATE NOT NULL,
                                                        cutoff_at TIMESTAMPTZ NOT NULL,
                                                        time_zone VARCHAR NOT NULL,
                                                        received_at TIMESTAMPTZ NOT NULL
                                                    )
                                                    """);
    }

    /**
     * @return the last business day the exchange has closed, or empty before the first close is known
     */
    public Optional<LocalDate> latestClosedBusinessDate() {
        return Optional.ofNullable(dependencies.jdbcTemplate().queryForObject("SELECT max(business_date) FROM business_day_closed", LocalDate.class));
    }

    /**
     * The {@code n}th business day after {@code date} on the exchange's calendar, holidays included (SR-35,
     * {@code GET /api/calendar}) - for a date ahead, such as a deposit's return window, which no close has named yet.
     * The calendar is fetched a year at a time and cached; the exchange's holidays change only with its configuration.
     *
     * @return empty if the exchange's calendar cannot be read; the caller decides what to fall back to
     */
    public Optional<LocalDate> businessDaysAfter(LocalDate date, int n) {
        requireNonNull(date, "No date provided");
        if (n < 0) {
            throw new IllegalArgumentException("n must be >= 0, was " + n);
        }
        if (n == 0) {
            return Optional.of(date);
        }
        var found = nthBusinessDayAfter(date, n);
        if (found.isPresent()) {
            return found;
        }
        try {
            loadCalendar(date, date.plusDays(400L + n * 2L));
        } catch (RuntimeException e) {
            log.warn("Cannot read the exchange's business-day calendar: {}", e.toString());
            return Optional.empty();
        }
        return nthBusinessDayAfter(date, n);
    }

    private Optional<LocalDate> nthBusinessDayAfter(LocalDate date, int n) {
        var range = calendarRange;
        if (range == null || date.isBefore(range[0])) {
            return Optional.empty();
        }
        var after = businessDays.tailSet(date, false).stream().skip(n - 1L).findFirst();
        return after.filter(day -> !day.isAfter(range[1]));
    }

    private synchronized void loadCalendar(LocalDate from, LocalDate to) {
        var calendar = exchange.get()
                               .uri(uri -> uri.path("/api/calendar").queryParam("from", from).queryParam("to", to).build())
                               .retrieve()
                               .body(JsonNode.class);
        for (var day : Objects.requireNonNull(calendar, "No calendar returned").get("businessDays")) {
            businessDays.add(LocalDate.parse(day.get("date").asString()));
        }
        var range = calendarRange;
        calendarRange = range == null ? new LocalDate[]{from, to}
                                      : new LocalDate[]{from.isBefore(range[0]) ? from : range[0], to.isAfter(range[1]) ? to : range[1]};
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
        var settings = new SignedWebhookReceiver.Settings(CONSUMER, Set.of(EVENT_TYPE), BusinessCalendar::isComplete, inbox);
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

    @PostMapping("/webhooks/exchange/business-days")
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
        return date(payload, "businessDate").isPresent()
                && date(payload, "nextBusinessDate").isPresent()
                && text(payload, "timeZone").isPresent()
                && text(payload, "cutoffAt").map(BusinessCalendar::instant).isPresent();
    }

    private void record(Message message) {
        var payload          = ((ExternalEventEnvelope) message.getPayload()).payload();
        var businessDate     = date(payload, "businessDate").orElseThrow();
        var nextBusinessDate = date(payload, "nextBusinessDate").orElseThrow();
        var recorded = dependencies.jdbcTemplate().update("""
                                                                  INSERT INTO business_day_closed (business_date, next_business_date, cutoff_at, time_zone, received_at)
                                                                  VALUES (?, ?, ?, ?, ?)
                                                                  ON CONFLICT (business_date) DO NOTHING
                                                                  """,
                                                          businessDate,
                                                          nextBusinessDate,
                                                          Timestamp.from(text(payload, "cutoffAt").map(BusinessCalendar::instant).orElseThrow()),
                                                          text(payload, "timeZone").orElseThrow(),
                                                          Timestamp.from(Instant.now()));
        if (recorded == 0) {
            return;
        }
        log.debug("Business day {} closed; {} is open", businessDate, nextBusinessDate);
        dependencies.publisher().publishEvent(new BusinessDayClosedNotice(businessDate, nextBusinessDate));
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

    private static Instant instant(String text) {
        try {
            return OffsetDateTime.parse(text).toInstant();
        } catch (DateTimeException e) {
            return null;
        }
    }
}
