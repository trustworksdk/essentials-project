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

package dk.trustworks.essentials.examples.perflab.scenario;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.ConfigurableEventStore;
import dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.EventStore;
import dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.cdc.CdcAvailability;
import dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.cdc.CdcConsumerGroup;
import dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.cdc.CdcDispatcher;
import dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.cdc.CdcSlotNameProvider;
import dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.cdc.PgReplicationSlots;
import dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.eventstream.AggregateType;
import dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.persistence.OptimisticAppendToStreamException;
import dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.transaction.EventStoreUnitOfWork;
import dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.transaction.EventStoreUnitOfWorkFactory;
import dk.trustworks.essentials.examples.perflab.EssentialsPerformanceLabProperties;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import javax.sql.DataSource;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.Random;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Scenario: deliberately invalidates the replication slot by tightening
 * {@code max_slot_wal_keep_size} on the running server, stopping the dispatcher (so
 * {@code confirmed_flush_lsn} can't advance), and pushing enough WAL through to exceed
 * the bound. Validates that the framework correctly detects the degraded slot and
 * — at least — flips {@link CdcAvailability} to non-ACTIVE so subscribers fall back
 * to polling.
 * <p>
 * Pass criteria:
 * <ul>
 *   <li>{@code walStatusDegraded} — at some point during the run, {@code wal_status}
 *       was anything other than {@code reserved} ({@code extended}, {@code unreserved},
 *       or {@code lost}).</li>
 *   <li>{@code availabilityFlipped} — {@link CdcAvailability#isActive()} returned
 *       {@code false} at least once after invalidation. The tailer's reconnect path
 *       hits {@code PgReplicationSlots.validateSlotHealthOrThrow} (P2) and either
 *       throws or stalls, both of which are signalled via availability.</li>
 *   <li>{@code subscribersStayedCorrect} — no events were lost from the perspective
 *       of the underlying event store. Polling fallback is the load-bearing safety net,
 *       and we sanity-check it by counting appended-vs-found rows in the aggregate
 *       table.</li>
 * </ul>
 * <p>
 * <b>Cleanup:</b> on completion (success, failure, or exception) the scenario resets
 * {@code max_slot_wal_keep_size} via {@code ALTER SYSTEM RESET}. The slot itself is
 * left invalidated — the lab's {@code recreate-on-start=true} setting handles that on
 * the next JVM start. <b>Don't run this against production.</b> It needs superuser
 * privileges and intentionally damages the slot.
 */
@Component
public class SlotInvalidationScenario implements LabScenario {
    private static final Logger log = LoggerFactory.getLogger(SlotInvalidationScenario.class);

    private static final AggregateType ORDERS = AggregateType.of("LabSlotInvalidation");

    /**
     * Tightened bound for the test. PostgreSQL's documented minimum is implementation-
     * defined but is typically a multiple of the WAL segment size (16 MiB). Setting
     * 4 MiB gets us reliably past the bound with a few thousand events of test data
     * while keeping the test under ~30 seconds.
     */
    private static final String INVALIDATION_KEEP_SIZE = "4MB";

    private final EventStore                                                  eventStore;
    private final ConfigurableEventStore<?>                                   configurableEventStore;
    private final EventStoreUnitOfWorkFactory<? extends EventStoreUnitOfWork> unitOfWorkFactory;
    private final DataSource                                                  dataSource;
    private final Optional<CdcDispatcher>                                     dispatcher;
    private final Optional<CdcAvailability>                                   cdcAvailability;
    private final Optional<CdcSlotNameProvider>                               slotNameProvider;
    private final Optional<CdcConsumerGroup>                                  consumerGroup;
    private final ObjectMapper                                                objectMapper;

    public SlotInvalidationScenario(EventStore eventStore,
                                    ConfigurableEventStore<?> configurableEventStore,
                                    EventStoreUnitOfWorkFactory<? extends EventStoreUnitOfWork> unitOfWorkFactory,
                                    DataSource dataSource,
                                    Optional<CdcDispatcher> dispatcher,
                                    Optional<CdcAvailability> cdcAvailability,
                                    Optional<CdcSlotNameProvider> slotNameProvider,
                                    Optional<CdcConsumerGroup> consumerGroup,
                                    ObjectMapper objectMapper) {
        this.eventStore = eventStore;
        this.configurableEventStore = configurableEventStore;
        this.unitOfWorkFactory = unitOfWorkFactory;
        this.dataSource = dataSource;
        this.dispatcher = dispatcher;
        this.cdcAvailability = cdcAvailability;
        this.slotNameProvider = slotNameProvider;
        this.consumerGroup = consumerGroup;
        this.objectMapper = objectMapper;
    }

    @Override
    public String name() {
        return "slot-invalidation";
    }

    @Override
    public String description() {
        return "Force-invalidates the slot via tight max_slot_wal_keep_size + paused dispatcher; verifies framework detects degraded slot and falls back to polling. DESTRUCTIVE — dev/test only.";
    }

    @PostConstruct
    void registerAggregateAtStartup() {
        if (configurableEventStore.findAggregateEventStreamConfiguration(ORDERS).isEmpty()) {
            configurableEventStore.addAggregateEventStreamConfiguration(ORDERS, String.class);
        }
    }

    @Override
    public void run(EssentialsPerformanceLabProperties properties) throws Exception {
        if (slotNameProvider.isEmpty() || consumerGroup.isEmpty() || dispatcher.isEmpty() || cdcAvailability.isEmpty()) {
            log.error("CDC components not present — slot-invalidation requires CDC enabled in INBOX delivery mode.");
            return;
        }
        var slotName     = slotNameProvider.get().slotName(consumerGroup.get());
        var disp         = dispatcher.get();
        var availability = cdcAvailability.get();

        var pre = sampleSlotState(slotName);
        log.info("[{}] slot-invalidation pre: {}", slotName, pre);

        boolean walStatusDegraded = false;
        boolean availabilityFlipped = false;
        long appended = 0L;
        long appendErrors = 0L;
        SlotState mid = pre;
        SlotState post = pre;
        Throwable failureToReport = null;

        try {
            // 1. Tighten the server-side bound. Requires superuser; the lab's POSTGRES_USER
            //    is the bootstrap user, so this works against the compose profile.
            execute("ALTER SYSTEM SET max_slot_wal_keep_size = '" + INVALIDATION_KEEP_SIZE + "'");
            execute("SELECT pg_reload_conf()");
            log.info("[{}] tightened max_slot_wal_keep_size = {}", slotName, INVALIDATION_KEEP_SIZE);

            // 2. Stop the dispatcher so confirmed_flush_lsn can't advance — without this,
            //    a healthy dispatcher would drain WAL fast enough that the bound never bites.
            disp.stop();
            log.info("[{}] dispatcher stopped — slot drainage halted", slotName);

            // 3. Force a checkpoint to establish a clean baseline before the WAL flood.
            execute("CHECKPOINT");

            // 4. Generate enough WAL to overflow the bound. Each event is ~500 bytes serialized;
            //    50_000 events ≈ 25 MiB, comfortably past the 4 MiB bound. The producer respects
            //    `duration` as a safety cap so we don't run forever on slow Macs.
            var deadlineNanos = System.nanoTime() + properties.getDuration().toNanos();
            var random        = new Random(properties.getRandomSeed());
            var cardinality   = Math.max(1, properties.getAggregateCardinality());
            var producedCount = new AtomicLong();
            var errorCount    = new AtomicLong();
            int targetEvents  = 50_000;
            while (System.nanoTime() < deadlineNanos && producedCount.get() < targetEvents) {
                var aggregateId = "order-" + random.nextInt(cardinality);
                var event = new LabInvalidationEvent(aggregateId,
                                                     producedCount.incrementAndGet(),
                                                     System.nanoTime());
                try {
                    unitOfWorkFactory.withUnitOfWork(() -> {
                        eventStore.appendToStream(ORDERS, aggregateId, List.of(event));
                        return null;
                    });
                } catch (Exception e) {
                    if (!(e.getCause() instanceof OptimisticAppendToStreamException)) {
                        errorCount.incrementAndGet();
                    }
                }

                // Periodic checkpoint nudges PG to evaluate max_slot_wal_keep_size against
                // the slot's restart_lsn — without checkpoints the invalidation check
                // never fires regardless of how much WAL we've written.
                if (producedCount.get() % 5_000 == 0) {
                    try { execute("CHECKPOINT"); } catch (Exception ignored) {}
                }

                // Sample availability + slot state every 1000 events to capture the
                // transition window. We're cheap with the I/O — once we see the degraded
                // state and the availability flip we have what we need.
                if (producedCount.get() % 1_000 == 0) {
                    if (!availability.isActive()) availabilityFlipped = true;
                    var s = sampleSlotState(slotName);
                    if (s.exists && s.walStatus != null && !"reserved".equalsIgnoreCase(s.walStatus)) {
                        walStatusDegraded = true;
                        mid = s;
                        log.info("[{}] slot degraded mid-run: walStatus={} after {} events",
                                 slotName, s.walStatus, producedCount.get());
                    }
                }
            }
            appended = producedCount.get();
            appendErrors = errorCount.get();

            // 5. Final checkpoint + brief settle so the server's invalidator runs at least once
            //    after our last write. Then sample the final slot state.
            execute("CHECKPOINT");
            Thread.sleep(2_000L);
            post = sampleSlotState(slotName);
            if (post.exists && post.walStatus != null && !"reserved".equalsIgnoreCase(post.walStatus)) {
                walStatusDegraded = true;
            }
            // Re-check availability — between mid-loop samples and the final settle, the tailer
            // may have hit its reconnect cycle and tripped the P2 health check.
            if (!availability.isActive()) availabilityFlipped = true;
        } catch (Throwable t) {
            failureToReport = t;
            log.error("[{}] slot-invalidation aborted: {}", slotName, t.toString(), t);
        } finally {
            // Always restore the server config — leaving max_slot_wal_keep_size tight would
            // affect every subsequent test against this PG.
            try {
                execute("ALTER SYSTEM RESET max_slot_wal_keep_size");
                execute("SELECT pg_reload_conf()");
                log.info("[{}] restored max_slot_wal_keep_size = unbounded", slotName);
            } catch (Exception e) {
                log.warn("[{}] failed to reset max_slot_wal_keep_size — manual cleanup needed: {}",
                         slotName, e.toString());
            }
        }

        // Subscribers-stayed-correct: count rows in the aggregate's event-stream table.
        // It must equal the number we appended (minus genuine non-conflict errors). The
        // CDC slot can be invalidated all day — the underlying event store is the
        // authoritative record and pollEvents will catch up once availability is restored.
        long persistedRowCount = countPersisted();
        boolean subscribersStayedCorrect = persistedRowCount >= appended - appendErrors;

        var verdict = (walStatusDegraded && availabilityFlipped && subscribersStayedCorrect)
                      ? "PASS"
                      : "FAIL";

        var snapshot = new SlotInvalidationSnapshot(
                Instant.now().toString(),
                slotName,
                INVALIDATION_KEEP_SIZE,
                appended,
                appendErrors,
                persistedRowCount,
                walStatusDegraded,
                availabilityFlipped,
                subscribersStayedCorrect,
                failureToReport == null ? null : failureToReport.toString(),
                verdict,
                pre,
                mid,
                post,
                cdcAvailability.map(CdcAvailability::snapshot).orElse(null)
        );

        var json = toJson(snapshot);
        log.info("slot-invalidation metrics: {}", json);
        System.out.println("############# [perf-lab] SLOT-INVALIDATION DONE #############");
        System.out.println("############# [perf-lab] slot=" + slotName +
                           " pre_wal_status=" + pre.walStatus +
                           " mid_wal_status=" + mid.walStatus +
                           " post_wal_status=" + post.walStatus +
                           " degraded=" + walStatusDegraded +
                           " availability_flipped=" + availabilityFlipped +
                           " appended=" + appended +
                           " persisted=" + persistedRowCount +
                           " verdict=" + verdict);
        System.out.println("############# [perf-lab] ##################################");

        writeMetricsIfConfigured(properties.getMetricsOutputFile(), json);
    }

    private SlotState sampleSlotState(String slotName) {
        var info = unitOfWorkFactory.withUnitOfWork(uow -> {
            try {
                return PgReplicationSlots.findSlot(uow.handle().getConnection(), slotName);
            } catch (Exception e) {
                return null;
            }
        });
        if (info == null) return new SlotState(false, false, null, null, null, -1L);
        long lag = unitOfWorkFactory.withUnitOfWork(uow -> {
            try (var ps = uow.handle().getConnection().prepareStatement(
                    "SELECT pg_wal_lsn_diff(pg_current_wal_lsn(), confirmed_flush_lsn) FROM pg_replication_slots WHERE slot_name = ?")) {
                ps.setString(1, slotName);
                try (var rs = ps.executeQuery()) {
                    return rs.next() ? rs.getLong(1) : -1L;
                }
            } catch (Exception e) {
                return -1L;
            }
        });
        return new SlotState(true, info.isActive(), info.confirmedFlushLsn, info.walStatus, info.invalidationReason, lag);
    }

    private long countPersisted() {
        return unitOfWorkFactory.withUnitOfWork(uow -> {
            try (var ps = uow.handle().getConnection().prepareStatement(
                    "SELECT count(*) FROM labslotinvalidation_events")) {
                try (var rs = ps.executeQuery()) {
                    return rs.next() ? rs.getLong(1) : 0L;
                }
            } catch (Exception e) {
                return 0L;
            }
        });
    }

    /**
     * Run a single SQL statement against an autocommit connection. Bypasses
     * {@code unitOfWorkFactory} on purpose — {@code ALTER SYSTEM} fails with
     * {@code "ALTER SYSTEM cannot run inside a transaction block"} when the connection has
     * {@code autoCommit=false}, which is the default for managed unit-of-work connections.
     * Same applies to {@code CHECKPOINT} on some PG versions.
     */
    private void execute(String sql) {
        try (var conn = dataSource.getConnection()) {
            conn.setAutoCommit(true);
            try (var stmt = conn.createStatement()) {
                stmt.execute(sql);
            }
        } catch (Exception e) {
            throw new RuntimeException("Failed to execute: " + sql, e);
        }
    }

    private void writeMetricsIfConfigured(String metricsOutputFile, String json) throws IOException {
        if (!StringUtils.hasText(metricsOutputFile)) return;
        var target = Paths.get(metricsOutputFile).toAbsolutePath().normalize();
        if (target.getParent() != null) Files.createDirectories(target.getParent());
        Files.writeString(target, json + System.lineSeparator(),
                          StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.WRITE);
        log.info("Wrote slot-invalidation metrics to {}", target);
        System.out.println("############# [perf-lab] slot-invalidation metrics file: " + target);
    }

    private String toJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Failed to serialize slot-invalidation metrics to JSON", e);
        }
    }

    public record SlotState(boolean exists,
                            boolean active,
                            String confirmedFlushLsn,
                            String walStatus,
                            String invalidationReason,
                            long pgLagBytes) {
    }

    public record SlotInvalidationSnapshot(String capturedAt,
                                           String slotName,
                                           String tightenedKeepSize,
                                           long eventsAppended,
                                           long appendErrors,
                                           long persistedRowCount,
                                           boolean walStatusDegraded,
                                           boolean availabilityFlipped,
                                           boolean subscribersStayedCorrect,
                                           String runException,
                                           String verdict,
                                           SlotState pre,
                                           SlotState mid,
                                           SlotState post,
                                           CdcAvailability.Snapshot cdc) {
    }

    @SuppressWarnings("unused")
    private record LabInvalidationEvent(String aggregateId, long sequence, long appendedAtNanos) {
        // Padded payload so each event consumes a meaningful amount of WAL — keeps the
        // 50k-event cap realistic even with PG's own batching/compression of WAL records.
        public String padding() {
            return "x".repeat(400);
        }
    }
}
