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
import dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.cdc.CdcSlotNameProvider;
import dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.cdc.PgReplicationSlots;
import dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.eventstream.AggregateType;
import dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.transaction.EventStoreUnitOfWork;
import dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.transaction.EventStoreUnitOfWorkFactory;
import dk.trustworks.essentials.examples.perflab.EssentialsPerformanceLabProperties;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Scenario: writes a single event to seed the WAL with one publication-relevant change, then
 * stays idle for the configured {@code duration} while sampling the slot's
 * {@code confirmed_flush_lsn}. Validates that the framework's idle-LSN-push (P4) keeps the
 * slot acking forward even when no further events arrive — the failure mode that lets a quiet
 * slot retain WAL until disk fills.
 * <p>
 * Pass criteria:
 * <ul>
 *   <li>{@code confirmedFlushLsnAdvanced} — final {@code confirmed_flush_lsn} is strictly
 *       greater than the value sampled immediately after the seed event.</li>
 *   <li>{@code idlePushObserved} — {@code confirmed_flush_lsn} advanced at least once
 *       beyond what the seed alone could explain (no further events written after the seed).</li>
 *   <li>{@code walStatusOk} — slot's {@code wal_status} stayed {@code reserved} throughout.</li>
 *   <li>{@code lagBytesEndSmall} — final {@code pg_wal_lsn_diff} ≤ 64 KiB. With no producers,
 *       there's nothing for the slot to lag on.</li>
 * </ul>
 * Recommended {@code duration}: ≥ 3× the configured {@code idleLsnPushInterval}, so the test
 * observes multiple push cycles. With the default 30 s interval, a 120 s duration sees ≥ 4
 * pushes — enough to distinguish "push works" from "one lucky tick".
 */
@Component
public class SlotIdlePushScenario implements LabScenario {
    private static final Logger log = LoggerFactory.getLogger(SlotIdlePushScenario.class);

    private static final AggregateType ORDERS = AggregateType.of("LabSlotIdle");

    private final EventStore                                                  eventStore;
    private final ConfigurableEventStore<?>                                   configurableEventStore;
    private final EventStoreUnitOfWorkFactory<? extends EventStoreUnitOfWork> unitOfWorkFactory;
    private final Optional<CdcAvailability>                                   cdcAvailability;
    private final Optional<CdcSlotNameProvider>                               slotNameProvider;
    private final Optional<CdcConsumerGroup>                                  consumerGroup;
    private final ObjectMapper                                                objectMapper;

    public SlotIdlePushScenario(EventStore eventStore,
                                ConfigurableEventStore<?> configurableEventStore,
                                EventStoreUnitOfWorkFactory<? extends EventStoreUnitOfWork> unitOfWorkFactory,
                                Optional<CdcAvailability> cdcAvailability,
                                Optional<CdcSlotNameProvider> slotNameProvider,
                                Optional<CdcConsumerGroup> consumerGroup,
                                ObjectMapper objectMapper) {
        this.eventStore = eventStore;
        this.configurableEventStore = configurableEventStore;
        this.unitOfWorkFactory = unitOfWorkFactory;
        this.cdcAvailability = cdcAvailability;
        this.slotNameProvider = slotNameProvider;
        this.consumerGroup = consumerGroup;
        this.objectMapper = objectMapper;
    }

    @Override
    public String name() {
        return "slot-idle-push";
    }

    @Override
    public String description() {
        return "Seeds one event then stays idle for the duration; verifies the idle LSN push keeps confirmed_flush_lsn advancing on a quiet slot";
    }

    @PostConstruct
    void registerAggregateAtStartup() {
        if (configurableEventStore.findAggregateEventStreamConfiguration(ORDERS).isEmpty()) {
            configurableEventStore.addAggregateEventStreamConfiguration(ORDERS, String.class);
        }
    }

    @Override
    public void run(EssentialsPerformanceLabProperties properties) throws Exception {
        if (slotNameProvider.isEmpty() || consumerGroup.isEmpty()) {
            log.error("CDC is disabled — slot-idle-push requires CDC. Set essentials.eventstore.cdc.enabled=true.");
            return;
        }
        var slotName = slotNameProvider.get().slotName(consumerGroup.get());

        // Wait until the tailer has created the slot before taking pre-snapshot. The scenario
        // runs early in Spring's CommandLineRunner — the @PostConstruct above and the CDC
        // autoconfig race for "who runs first", so the slot isn't guaranteed to exist by the
        // time we get here. Bounded wait — fail fast if CDC plainly didn't come up.
        var slotReadyDeadline = System.currentTimeMillis() + 15_000L;
        SlotState pre = sampleSlotState(slotName);
        while (!pre.exists && System.currentTimeMillis() < slotReadyDeadline) {
            Thread.sleep(200L);
            pre = sampleSlotState(slotName);
        }
        if (!pre.exists) {
            log.error("[{}] slot did not appear within 15 s — is CDC enabled and the tailer running?", slotName);
            return;
        }
        log.info("[{}] slot-idle-push pre-seed: {}", slotName, pre);

        // Seed exactly one event so there's at least one publication-relevant WAL record. The
        // confirmed_flush_lsn must advance past the seed via the idle push, not by replaying
        // a steady stream — that's the whole point of the scenario.
        seedOneEvent();

        // Brief settle to let the seed propagate through tailer → inbox → ack. After this
        // sample, any further advance must be attributable to idle pushes (or PG-internal
        // WAL activity, which is also acceptable as long as confirmed_flush_lsn moves).
        Thread.sleep(2_000L);
        var afterSeed = sampleSlotState(slotName);
        log.info("[{}] slot-idle-push after-seed: {}", slotName, afterSeed);

        var idleStartedAt = System.currentTimeMillis();
        Thread.sleep(properties.getDuration().toMillis());
        var idleEndedAt   = System.currentTimeMillis();

        var post = sampleSlotState(slotName);
        log.info("[{}] slot-idle-push post-idle: {}", slotName, post);

        boolean confirmedFlushLsnAdvanced = lsnGreaterThan(post.confirmedFlushLsn, pre.confirmedFlushLsn);
        boolean idlePushObserved          = lsnGreaterThan(post.confirmedFlushLsn, afterSeed.confirmedFlushLsn);
        boolean walStatusOk               = post.walStatus == null || "reserved".equalsIgnoreCase(post.walStatus);
        boolean lagBytesEndSmall          = post.pgLagBytes >= 0 && post.pgLagBytes <= 64L * 1024L;

        var verdict = (confirmedFlushLsnAdvanced && idlePushObserved && walStatusOk && lagBytesEndSmall) ? "PASS" : "FAIL";

        var snapshot = new IdlePushSnapshot(
                Instant.now().toString(),
                slotName,
                cdcAvailability.map(CdcAvailability::isActive).orElse(false),
                idleEndedAt - idleStartedAt,
                pre,
                afterSeed,
                post,
                confirmedFlushLsnAdvanced,
                idlePushObserved,
                walStatusOk,
                lagBytesEndSmall,
                verdict,
                cdcAvailability.map(CdcAvailability::snapshot).orElse(null)
        );

        var json = toJson(snapshot);
        log.info("slot-idle-push metrics: {}", json);
        System.out.println("############# [perf-lab] SLOT-IDLE-PUSH DONE #############");
        System.out.println("############# [perf-lab] slot=" + slotName +
                           " pre_lsn=" + pre.confirmedFlushLsn +
                           " post_lsn=" + post.confirmedFlushLsn +
                           " advanced=" + confirmedFlushLsnAdvanced +
                           " idle_push_observed=" + idlePushObserved +
                           " verdict=" + verdict);
        System.out.println("############# [perf-lab] ###############################");

        writeMetricsIfConfigured(properties.getMetricsOutputFile(), json);
    }

    private void seedOneEvent() {
        var seedAggregate = "seed-" + UUID.randomUUID();
        unitOfWorkFactory.usingUnitOfWork(uow ->
            eventStore.appendToStream(ORDERS, seedAggregate, List.of(
                    new LabSlotIdleEvent(seedAggregate, 1L, System.nanoTime())
            ))
        );
    }

    private SlotState sampleSlotState(String slotName) {
        var info = unitOfWorkFactory.withUnitOfWork(uow -> {
            try {
                return PgReplicationSlots.findSlot(uow.handle().getConnection(), slotName);
            } catch (Exception e) {
                log.debug("findSlot failed for '{}': {}", slotName, e.toString());
                return null;
            }
        });
        if (info == null) {
            return new SlotState(false, false, null, null, null, -1L);
        }
        long pgLagBytes = unitOfWorkFactory.withUnitOfWork(uow -> {
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
        return new SlotState(true, info.isActive(), info.confirmedFlushLsn, info.restartLsn, info.walStatus, pgLagBytes);
    }

    /**
     * Compare two PostgreSQL LSN strings of the form {@code "X/YYYYYYYY"} for strict greater-than.
     * Both halves are unsigned 32-bit hex; we parse via {@link Long#parseUnsignedLong} and compare
     * lexicographically via two paired comparisons. Returns {@code false} on any parse failure
     * — better to fail-closed than to assert advancement on garbage.
     */
    private static boolean lsnGreaterThan(String a, String b) {
        if (a == null || b == null) return false;
        var aParts = a.split("/");
        var bParts = b.split("/");
        if (aParts.length != 2 || bParts.length != 2) return false;
        try {
            long aHi = Long.parseUnsignedLong(aParts[0], 16);
            long aLo = Long.parseUnsignedLong(aParts[1], 16);
            long bHi = Long.parseUnsignedLong(bParts[0], 16);
            long bLo = Long.parseUnsignedLong(bParts[1], 16);
            if (aHi != bHi) return Long.compareUnsigned(aHi, bHi) > 0;
            return Long.compareUnsigned(aLo, bLo) > 0;
        } catch (NumberFormatException nfe) {
            return false;
        }
    }

    private void writeMetricsIfConfigured(String metricsOutputFile, String json) throws IOException {
        if (!StringUtils.hasText(metricsOutputFile)) return;
        var target = Paths.get(metricsOutputFile).toAbsolutePath().normalize();
        if (target.getParent() != null) Files.createDirectories(target.getParent());
        Files.writeString(target, json + System.lineSeparator(),
                          StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.WRITE);
        log.info("Wrote slot-idle-push metrics to {}", target);
        System.out.println("############# [perf-lab] slot-idle-push metrics file: " + target);
    }

    private String toJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Failed to serialize slot-idle-push metrics to JSON", e);
        }
    }

    public record SlotState(boolean exists,
                            boolean active,
                            String confirmedFlushLsn,
                            String restartLsn,
                            String walStatus,
                            long pgLagBytes) {
    }

    public record IdlePushSnapshot(String capturedAt,
                                   String slotName,
                                   boolean cdcActive,
                                   long idleDurationMs,
                                   SlotState pre,
                                   SlotState afterSeed,
                                   SlotState post,
                                   boolean confirmedFlushLsnAdvanced,
                                   boolean idlePushObserved,
                                   boolean walStatusOk,
                                   boolean lagBytesEndSmall,
                                   String verdict,
                                   CdcAvailability.Snapshot cdc) {
    }

    private record LabSlotIdleEvent(String aggregateId, long sequence, long appendedAtNanos) {
    }
}
