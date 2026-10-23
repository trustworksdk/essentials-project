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

import dk.trustworks.essentials.components.foundation.fencedlock.*;
import org.slf4j.*;
import org.springframework.context.SmartLifecycle;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.ObjectMapper;

import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicLong;

import static dk.trustworks.essentials.shared.FailFast.*;

/**
 * Reads a source's canonical event log ({@code GET /api/events?after=}) and feeds every event a consumer wants through
 * the same {@link ExternalEventIntake} the webhook uses. Webhooks are the low-latency path; this is the guarantee.
 * <p>
 * It covers what webhook delivery alone cannot: a delivery dead-lettered at the source after its retries, events
 * committed while this application was down past the source's retry span, and a subscription that was paused or
 * misconfigured. Because the intake deduplicates per {@code (consumer, eventId)}, overlap with webhook deliveries is
 * harmless — it costs a primary-key probe per event, which is why the interval is seconds, not milliseconds.
 * <p>
 * One instance in the cluster reads each log, behind a {@link FencedLock}; the cursor is stored, so a takeover or a
 * restart continues where the last reader committed. The cursor is stored after each page; a crash between a page's
 * intake and its cursor re-reads that page, which the deduplication absorbs.
 * <p>
 * The read asks for the consumer's event types only, and the cursor moves on to the sequence the source says it looked
 * at ({@code Event-Log-Scanned-Through}, SR-34), so a consumer of a few event types does not page through every other
 * consumer's events. The log's {@code sequence} is source-wide, so one cursor per consumer is enough.
 * <p>
 * Spring's {@code @Scheduled} is not used: this application has no {@code @EnableScheduling}, where it would bind and
 * never fire (see {@code _demo_harness/CLAUDE.md}).
 */
public final class ExternalEventLogCatchUp implements SmartLifecycle {
    private static final Logger log = LoggerFactory.getLogger(ExternalEventLogCatchUp.class);

    private final Settings              settings;
    private final ExternalEventIntake   intake;
    private final FencedLockManager     fencedLockManager;
    private final JdbcTemplate          jdbcTemplate;
    private final ObjectMapper          objectMapper;
    private final AtomicLong            eventsAccepted = new AtomicLong();
    private final AtomicLong            eventsSkipped  = new AtomicLong();
    private volatile ScheduledExecutorService executor;
    private volatile boolean                  running;

    /**
     * @param name         the reader's name: its lock and its stored cursor. Stable across releases, or the log is read
     *                     again from the start (harmless, but slow)
     * @param sourceClient a client whose base URL is the source, e.g. {@code http://exchange:8080}
     * @param target       the consumer this reader feeds — the same settings its webhook receiver uses
     * @param interval     pause between reads once the reader has caught up
     * @param pageSize     events per request, 1 to 1000
     * @param participants the participants whose events to read (the exchange's {@code participant}, SR-39), as the
     *                     consumer's webhook subscription lists them; empty for every participant's, and for a source
     *                     without the filter (the payment gateway)
     */
    public record Settings(String name, RestClient sourceClient, SignedWebhookReceiver.Settings target, Duration interval, int pageSize, Set<String> participants) {
        public Settings {
            requireNonNull(name, "No name provided");
            requireNonNull(sourceClient, "No sourceClient provided");
            requireNonNull(target, "No target provided");
            requireNonNull(interval, "No interval provided");
            requireTrue(pageSize >= 1 && pageSize <= 1000, "pageSize must be 1..1000");
            participants = Set.copyOf(requireNonNull(participants, "No participants provided"));
        }

        /**
         * Every participant's events.
         */
        public Settings(String name, RestClient sourceClient, SignedWebhookReceiver.Settings target, Duration interval, int pageSize) {
            this(name, sourceClient, target, interval, pageSize, Set.of());
        }
    }

    public ExternalEventLogCatchUp(Settings settings,
                                   ExternalEventIntake intake,
                                   FencedLockManager fencedLockManager,
                                   JdbcTemplate jdbcTemplate,
                                   ObjectMapper objectMapper) {
        this.settings = requireNonNull(settings, "No settings provided");
        this.intake = requireNonNull(intake, "No intake provided");
        this.fencedLockManager = requireNonNull(fencedLockManager, "No fencedLockManager provided");
        this.jdbcTemplate = requireNonNull(jdbcTemplate, "No jdbcTemplate provided");
        this.objectMapper = requireNonNull(objectMapper, "No objectMapper provided");
        this.jdbcTemplate.execute("""
                                          CREATE TABLE IF NOT EXISTS external_event_log_cursor (
                                              name VARCHAR PRIMARY KEY,
                                              last_sequence BIGINT NOT NULL,
                                              updated_at TIMESTAMPTZ NOT NULL
                                          )
                                          """);
    }

    @Override
    public void start() {
        running = true;
        fencedLockManager.acquireLockAsync(lockName(), new LockCallback() {
            @Override
            public void lockAcquired(FencedLock lock) {
                log.info("[{}] Acquired the catch-up lock; reading the event log from sequence {}", settings.name(), cursor());
                var started = Executors.newSingleThreadScheduledExecutor(Thread.ofPlatform().name("catch-up-" + settings.name()).daemon().factory());
                started.scheduleWithFixedDelay(ExternalEventLogCatchUp.this::readUntilCaughtUp, 0, settings.interval().toMillis(), TimeUnit.MILLISECONDS);
                executor = started;
            }

            @Override
            public void lockReleased(FencedLock lock) {
                log.info("[{}] Released the catch-up lock", settings.name());
                stopExecutor();
            }
        });
    }

    @Override
    public void stop() {
        running = false;
        fencedLockManager.cancelAsyncLockAcquiring(lockName());
        stopExecutor();
    }

    @Override
    public boolean isRunning() {
        return running;
    }

    /**
     * Reads pages until one comes back short. Public so a test can drive it without the lock and the schedule.
     *
     * @return the number of events accepted as new
     */
    public int readUntilCaughtUp() {
        var accepted = 0;
        try {
            while (true) {
                var page = readPage();
                accepted += page.accepted();
                if (page.size() < settings.pageSize()) {
                    return accepted;
                }
            }
        } catch (RuntimeException e) {
            // Keep the schedule alive: the source being down is the ordinary case this reader exists for.
            log.warn("[{}] Reading the event log failed; retrying in {}: {}", settings.name(), settings.interval(), e.toString());
            return accepted;
        }
    }

    public long cursor() {
        var stored = jdbcTemplate.query("SELECT last_sequence FROM external_event_log_cursor WHERE name = ?",
                                        rs -> rs.next() ? rs.getLong(1) : null,
                                        settings.name());
        return stored == null ? 0 : stored;
    }

    public long eventsAccepted() {
        return eventsAccepted.get();
    }

    public long eventsSkipped() {
        return eventsSkipped.get();
    }

    private record Page(int size, int accepted) {
    }

    private Page readPage() {
        var after  = cursor();
        var target = settings.target();
        var response = settings.sourceClient()
                               .get()
                               .uri(uri -> uri.path("/api/events")
                                              .queryParam("after", after)
                                              .queryParam("limit", settings.pageSize())
                                              .queryParam("eventType", target.eventTypes().stream().sorted().toArray())
                                              .queryParamIfPresent("participant", settings.participants().isEmpty()
                                                                                  ? Optional.empty()
                                                                                  : Optional.of(String.join(",", settings.participants().stream().sorted().toList())))
                                              .build())
                               .retrieve()
                               .toEntity(byte[].class);
        var body   = response.getBody();
        var events = objectMapper.readTree(body == null ? "[]".getBytes() : body);
        // How far the source looked (SR-34): a filtered read may skip a long stretch of other consumers' events, and
        // continuing from here spares reading it again. Absent from a source without the header.
        var scannedThrough = scannedThrough(response.getHeaders().getFirst("Event-Log-Scanned-Through"));
        if (!events.isArray() || events.isEmpty()) {
            if (scannedThrough > after) {
                storeCursor(scannedThrough);
            }
            return new Page(0, 0);
        }
        var accepted = 0;
        var last     = after;
        for (var i = 0; i < events.size(); i++) {
            var json     = events.get(i);
            var envelope = ExternalEventEnvelope.from(json).orElse(null);
            if (envelope == null) {
                // The log is canonical; an unreadable entry is a source defect, not a transient fault. Skip it loudly
                // rather than stop the reader behind it forever.
                eventsSkipped.incrementAndGet();
                log.error("[{}] Skipping an unreadable event log entry after sequence {}: {}", settings.name(), last, json);
                continue;
            }
            last = Math.max(last, envelope.sequence());
            if (!target.eventTypes().contains(envelope.eventType())) {
                continue;
            }
            if (!target.validator().test(envelope)) {
                eventsSkipped.incrementAndGet();
                log.error("[{}] Skipping canonical event '{}' ({} #{}) that fails validation", settings.name(), envelope.eventId(), envelope.eventType(), envelope.sequence());
                continue;
            }
            if (intake.accept(target.consumer(), envelope, target.inbox())) {
                accepted++;
            }
        }
        storeCursor(Math.max(last, scannedThrough));
        eventsAccepted.addAndGet(accepted);
        if (accepted > 0) {
            log.info("[{}] Caught up {} event(s) the webhook had not delivered, up to sequence {}", settings.name(), accepted, last);
        }
        return new Page(events.size(), accepted);
    }

    private static long scannedThrough(String header) {
        try {
            return header == null ? 0 : Long.parseLong(header.trim());
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    private void storeCursor(long lastSequence) {
        jdbcTemplate.update("""
                                    INSERT INTO external_event_log_cursor (name, last_sequence, updated_at)
                                    VALUES (?, ?, now())
                                    ON CONFLICT (name) DO UPDATE
                                    SET last_sequence = GREATEST(external_event_log_cursor.last_sequence, EXCLUDED.last_sequence),
                                        updated_at = EXCLUDED.updated_at
                                    """,
                            settings.name(),
                            lastSequence);
    }

    private LockName lockName() {
        return LockName.of("external-event-log-catch-up:" + settings.name());
    }

    private void stopExecutor() {
        var current = executor;
        executor = null;
        if (current != null) {
            current.shutdownNow();
        }
    }
}
