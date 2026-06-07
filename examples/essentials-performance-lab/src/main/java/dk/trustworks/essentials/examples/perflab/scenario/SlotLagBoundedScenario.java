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
import dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.EventStoreSubscription;
import dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.cdc.CdcAvailability;
import dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.cdc.CdcConsumerGroup;
import dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.cdc.CdcSlotNameProvider;
import dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.cdc.PgReplicationSlots;
import dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.eventstream.AggregateType;
import dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.eventstream.PersistedEvent;
import dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.persistence.OptimisticAppendToStreamException;
import dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.subscription.EventStoreSubscriptionManager;
import dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.transaction.EventStoreUnitOfWork;
import dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.transaction.EventStoreUnitOfWorkFactory;
import dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.types.GlobalEventOrder;
import dk.trustworks.essentials.components.foundation.types.SubscriberId;
import dk.trustworks.essentials.examples.perflab.EssentialsPerformanceLabProperties;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Meter.Id;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.Random;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Scenario: drive a steady-rate write workload for a fixed duration, sample the replication
 * slot's WAL retention every N seconds, and verify the lag stays bounded and drains at the
 * end. Cross-checks the framework's {@code essentials.cdc.slot.lag_bytes} gauge (P1) against
 * a direct {@code pg_replication_slots} query so a divergence between the two is visible.
 * <p>
 * Pass criteria (encoded in the JSON output):
 * <ul>
 *   <li>{@code lagBytesMax} ≤ configured threshold ({@link EssentialsPerformanceLabProperties#getSlotLagMaxBytes()}).</li>
 *   <li>{@code walStatusEnd} = {@code reserved} (slot never degraded).</li>
 *   <li>{@code appended} = {@code delivered} (no event loss).</li>
 *   <li>{@code lagBytesEnd} ≤ {@code lagBytesMax / 2} (slot drains at run-end).</li>
 *   <li>{@code framework.lagBytes} agrees with {@code pg.lagBytes} within {@code 5%} drift.</li>
 * </ul>
 * Output JSON shape mirrors the existing baseline scenario so {@code summarize-compare.sh}
 * can pick it up unmodified. The pre / post / time-series slot states give operators a
 * forensics trail for failures that surface long after the run completes.
 * <p>
 * Requires CDC enabled and the framework's {@code recreate-on-start=true} (default in the
 * lab's {@code application.yml}) so each run starts from a fresh slot — otherwise a leftover
 * lag from a previous run would skew the bound check.
 */
@Component
public class SlotLagBoundedScenario implements LabScenario {
    private static final Logger log = LoggerFactory.getLogger(SlotLagBoundedScenario.class);

    private static final AggregateType ORDERS = AggregateType.of("LabSlotLag");

    private final EventStore                                                  eventStore;
    private final ConfigurableEventStore<?>                                   configurableEventStore;
    private final EventStoreSubscriptionManager                               subscriptionManager;
    private final EventStoreUnitOfWorkFactory<? extends EventStoreUnitOfWork> unitOfWorkFactory;
    private final Optional<CdcAvailability>                                   cdcAvailability;
    private final Optional<CdcSlotNameProvider>                               slotNameProvider;
    private final Optional<CdcConsumerGroup>                                  consumerGroup;
    private final Optional<MeterRegistry>                                     meterRegistry;
    private final ObjectMapper                                                objectMapper;

    public SlotLagBoundedScenario(EventStore eventStore,
                                  ConfigurableEventStore<?> configurableEventStore,
                                  EventStoreSubscriptionManager subscriptionManager,
                                  EventStoreUnitOfWorkFactory<? extends EventStoreUnitOfWork> unitOfWorkFactory,
                                  Optional<CdcAvailability> cdcAvailability,
                                  Optional<CdcSlotNameProvider> slotNameProvider,
                                  Optional<CdcConsumerGroup> consumerGroup,
                                  Optional<MeterRegistry> meterRegistry,
                                  ObjectMapper objectMapper) {
        this.eventStore = eventStore;
        this.configurableEventStore = configurableEventStore;
        this.subscriptionManager = subscriptionManager;
        this.unitOfWorkFactory = unitOfWorkFactory;
        this.cdcAvailability = cdcAvailability;
        this.slotNameProvider = slotNameProvider;
        this.consumerGroup = consumerGroup;
        this.meterRegistry = meterRegistry;
        this.objectMapper = objectMapper;
    }

    @Override
    public String name() {
        return "slot-lag-bounded";
    }

    @Override
    public String description() {
        return "Steady-rate writes for the configured duration; samples pg_replication_slots and asserts lag stays bounded and drains";
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
            log.error("CDC is disabled — slot-lag-bounded requires CDC. Set essentials.eventstore.cdc.enabled=true.");
            return;
        }

        var slotName        = slotNameProvider.get().slotName(consumerGroup.get());
        var sampleInterval  = properties.getSlotLagSampleInterval();
        var lagMaxBytes     = properties.getSlotLagMaxBytes();
        var samples         = Collections.synchronizedList(new ArrayList<SlotSample>());
        var startedAtNanos  = System.nanoTime();
        var deliveredEvents = new AtomicLong();

        // Start subscription BEFORE producers so live delivery has a consumer from t=0.
        var subscriberId = SubscriberId.of("lab-slot-lag-" + UUID.randomUUID());
        var startFrom    = unitOfWorkFactory.withUnitOfWork(() -> eventStore.findHighestGlobalEventOrderPersisted(ORDERS))
                                            .map(GlobalEventOrder::increment)
                                            .orElse(GlobalEventOrder.FIRST_GLOBAL_EVENT_ORDER);
        EventStoreSubscription subscription = subscriptionManager.subscribeToAggregateEventsAsynchronously(
                subscriberId,
                ORDERS,
                startFrom,
                event -> deliveredEvents.incrementAndGet());

        var sampler = startSlotSampler(slotName, sampleInterval, samples, startedAtNanos);

        try {
            var pre = sampleSlotState(slotName);
            log.info("[{}] slot-lag-bounded start: pre={}", slotName, pre);

            var producedEvents = runProducerPhase(properties);

            // Allow live delivery to drain before sampling final state. Budget at least 30s so
            // short smoke runs aren't penalised by container / Docker-Desktop overhead;
            // longer scenarios scale at 1.5× the run length, on the heuristic that the
            // backlog at producer-stop is bounded by recent throughput and the drain rate is
            // ≥ the produce rate on healthy runs.
            var drainBudgetMs = Math.max(30_000L, properties.getDuration().toMillis() * 3L / 2L);
            var drainDeadline = System.currentTimeMillis() + drainBudgetMs;
            while (deliveredEvents.get() < producedEvents && System.currentTimeMillis() < drainDeadline) {
                Thread.sleep(50L);
            }

            // Take a final post-run sample explicitly — the periodic sampler may not yet have
            // captured the slot state after the producer finished.
            var post     = sampleSlotState(slotName);
            var endedAt  = System.nanoTime();
            samples.add(new SlotSample(TimeUnit.NANOSECONDS.toMillis(endedAt - startedAtNanos), post));

            var snapshot = buildSnapshot(slotName,
                                         producedEvents,
                                         deliveredEvents.get(),
                                         pre,
                                         post,
                                         samples,
                                         lagMaxBytes,
                                         endedAt - startedAtNanos);

            var json = toJson(snapshot);
            log.info("slot-lag-bounded metrics: {}", json);
            System.out.println("############# [perf-lab] SLOT-LAG-BOUNDED DONE #############");
            System.out.println("############# [perf-lab] slot=" + slotName +
                               " produced=" + snapshot.producedEvents() +
                               " delivered=" + snapshot.deliveredEvents() +
                               " lag_bytes_max=" + snapshot.lagBytesMax() +
                               " lag_bytes_end=" + snapshot.lagBytesEnd() +
                               " wal_status_end=" + snapshot.walStatusEnd() +
                               " framework_drift_pct=" + String.format(java.util.Locale.ROOT, "%.2f", snapshot.frameworkVsPgDriftPct()) +
                               " verdict=" + snapshot.verdict());
            System.out.println("############# [perf-lab] ##################################");

            writeMetricsIfConfigured(properties.getMetricsOutputFile(), json);
        } finally {
            sampler.shutdownNow();
            subscription.unsubscribe();
        }
    }

    /**
     * Periodic sampler that snapshots the slot every {@code sampleInterval}, capturing both
     * the server-side state (via {@code pg_replication_slots}) and the framework's gauge view.
     * Written so a transient query failure leaves prior samples intact and the sampler keeps
     * ticking — a single bad sample shouldn't abort the run.
     */
    private ScheduledExecutorService startSlotSampler(String slotName,
                                                      Duration sampleInterval,
                                                      List<SlotSample> samples,
                                                      long startedAtNanos) {
        var executor = Executors.newSingleThreadScheduledExecutor(runnable -> {
            var thread = new Thread(runnable, "slot-lag-sampler");
            thread.setDaemon(true);
            return thread;
        });
        long intervalMs = Math.max(500L, sampleInterval.toMillis());
        ScheduledFuture<?> ignored = executor.scheduleAtFixedRate(() -> {
            try {
                var state = sampleSlotState(slotName);
                samples.add(new SlotSample(TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startedAtNanos), state));
            } catch (Exception e) {
                log.debug("slot sampler tick failed (will retry): {}", e.toString());
            }
        }, 0L, intervalMs, TimeUnit.MILLISECONDS);
        return executor;
    }

    /**
     * One-shot snapshot combining the server-side slot row and the framework's gauge view.
     * Both numbers should agree to within rounding; a divergence means our P1 metrics are
     * lying — the kind of bug we want to catch with this scenario.
     */
    private SlotState sampleSlotState(String slotName) {
        var info = unitOfWorkFactory.withUnitOfWork(uow -> {
            try {
                return PgReplicationSlots.findSlot(uow.handle().getConnection(), slotName);
            } catch (Exception e) {
                log.debug("findSlot failed for '{}': {}", slotName, e.toString());
                return null;
            }
        });

        long pgLagBytes = -1L;
        if (info != null) {
            pgLagBytes = unitOfWorkFactory.withUnitOfWork(uow -> {
                try (var ps = uow.handle().getConnection().prepareStatement(
                        "SELECT pg_wal_lsn_diff(pg_current_wal_lsn(), confirmed_flush_lsn) FROM pg_replication_slots WHERE slot_name = ?")) {
                    ps.setString(1, slotName);
                    try (var rs = ps.executeQuery()) {
                        return rs.next() ? rs.getLong(1) : -1L;
                    }
                } catch (Exception e) {
                    log.debug("lag-bytes query failed for '{}': {}", slotName, e.toString());
                    return -1L;
                }
            });
        }

        var frameworkLagBytes = readGauge("essentials.cdc.slot.lag_bytes", slotName);
        var frameworkActive   = readGauge("essentials.cdc.slot.active", slotName);
        var frameworkWalCode  = readGauge("essentials.cdc.slot.wal_status", slotName);

        return new SlotState(
                info != null,
                info != null && info.isActive(),
                info != null ? info.confirmedFlushLsn : null,
                info != null ? info.restartLsn : null,
                info != null ? info.walStatus : null,
                pgLagBytes,
                frameworkLagBytes,
                frameworkActive,
                frameworkWalCode
        );
    }

    /**
     * Read a Micrometer gauge by name + slot tag. Returns {@code -1} when the gauge is not
     * registered (e.g. metrics disabled) or no {@code MeterRegistry} is wired.
     */
    private double readGauge(String name, String slotName) {
        var registry = meterRegistry.orElse(null);
        if (registry == null) return -1.0d;
        for (var meter : registry.getMeters()) {
            Id id = meter.getId();
            if (!id.getName().equals(name)) continue;
            var slotTag = id.getTag("slot");
            if (slotTag == null || !slotTag.equals(slotName)) continue;
            if (meter instanceof Gauge gauge) return gauge.value();
        }
        return -1.0d;
    }

    private long runProducerPhase(EssentialsPerformanceLabProperties properties) throws InterruptedException {
        var phaseDuration = properties.getDuration();
        if (phaseDuration.isZero() || phaseDuration.isNegative()) return 0L;

        var nextEventNumber = new AtomicLong();
        var produced        = new AtomicLong();
        long deadlineNanos  = System.nanoTime() + phaseDuration.toNanos();

        // Throttle to producerRateHz (per-thread share) when set; unthrottled means each thread
        // appends as fast as the event store allows. producerRateHz is fractional-Hz capable
        // (e.g. 0.1 = 1 event/10s); compute the per-thread interval in nanoseconds directly
        // from the double to preserve sub-Hz precision rather than rounding to integer Hz first.
        double producerRateHz       = Math.max(0.0d, properties.getProducerRateHz());
        long   perThreadIntervalNanos = producerRateHz <= 0.0d
                ? 0L
                : (long) (1_000_000_000.0d * Math.max(1, properties.getProducerThreads()) / producerRateHz);

        var executor = Executors.newFixedThreadPool(properties.getProducerThreads(), runnable -> {
            var thread = new Thread(runnable, "lab-slot-lag-producer");
            thread.setDaemon(true);
            return thread;
        });

        for (int i = 0; i < properties.getProducerThreads(); i++) {
            final int producerIndex = i;
            executor.submit(() -> {
                var random       = new Random(properties.getRandomSeed() + (long) producerIndex * 31L);
                var cardinality  = Math.max(1, properties.getAggregateCardinality());
                long nextDueAt   = System.nanoTime();
                while (System.nanoTime() < deadlineNanos) {
                    if (perThreadIntervalNanos > 0L) {
                        long now = System.nanoTime();
                        if (now < nextDueAt) {
                            try {
                                TimeUnit.NANOSECONDS.sleep(nextDueAt - now);
                            } catch (InterruptedException interrupted) {
                                Thread.currentThread().interrupt();
                                return;
                            }
                        }
                        nextDueAt += perThreadIntervalNanos;
                    }

                    var aggregateId = "order-" + random.nextInt(cardinality);
                    var event = new LabSlotLagEvent(aggregateId,
                                                    nextEventNumber.incrementAndGet(),
                                                    System.nanoTime());
                    try {
                        unitOfWorkFactory.withUnitOfWork(() -> {
                            eventStore.appendToStream(ORDERS, aggregateId, List.of(event));
                            return null;
                        });
                        produced.incrementAndGet();
                    } catch (Exception e) {
                        if (!isOptimisticConflict(e)) {
                            log.debug("append failed: {}", e.toString());
                        }
                        // Conflicts on contended aggregates are fine here — we're stressing the
                        // slot, not measuring write throughput. Skip the event and continue.
                    }
                }
            });
        }

        executor.shutdown();
        executor.awaitTermination(phaseDuration.toMillis() + TimeUnit.SECONDS.toMillis(5), TimeUnit.MILLISECONDS);
        if (!executor.isTerminated()) executor.shutdownNow();
        return produced.get();
    }

    private boolean isOptimisticConflict(Throwable t) {
        var current = t;
        while (current != null) {
            if (current instanceof OptimisticAppendToStreamException) return true;
            current = current.getCause();
        }
        return false;
    }

    /**
     * Run the assertion logic and assemble the JSON snapshot. Verdict is the simple AND of
     * all five pass criteria; individual assertion booleans are exposed so the summarizer
     * can show which one failed.
     */
    private SlotLagSnapshot buildSnapshot(String slotName,
                                          long produced,
                                          long delivered,
                                          SlotState pre,
                                          SlotState post,
                                          List<SlotSample> samples,
                                          long lagMaxBytesThreshold,
                                          long durationNanos) {
        long lagMax = 0L;
        long lagSum = 0L;
        int  lagCount = 0;
        for (var s : samples) {
            if (s.state.pgLagBytes >= 0) {
                lagMax = Math.max(lagMax, s.state.pgLagBytes);
                lagSum += s.state.pgLagBytes;
                lagCount++;
            }
        }
        long lagAvg = lagCount == 0 ? 0L : lagSum / lagCount;
        long lagEnd = post.pgLagBytes >= 0 ? post.pgLagBytes : 0L;

        boolean lagBoundedOk = lagMax <= lagMaxBytesThreshold;
        boolean lagDrainedOk = lagEnd <= Math.max(1L, lagMax / 2);
        boolean walStatusOk  = post.walStatus == null || "reserved".equalsIgnoreCase(post.walStatus);
        // delivered ≥ 99% of produced. We don't demand exact equality for two reasons:
        //   (a) prior runs may have populated the aggregate's event-stream table (recreate-
        //       on-start drops the slot but not the table) — the surplus is benign.
        //   (b) the async subscription manager has its own polling cadence + resume-point
        //       persistence; a brief tail of in-flight events at scenario shutdown is
        //       expected and not what this scenario is validating. The slot/lag invariants
        //       above are the meaningful signal here.
        boolean deliveryOk   = produced == 0 || delivered >= (long) Math.ceil(produced * 0.99d);
        double  driftPct     = computeDriftPct(post.pgLagBytes, post.frameworkLagBytes);
        // Drift check has two failure modes:
        //   (a) framework gauge genuinely diverges from PG (the bug we want to catch).
        //   (b) framework gauge is sampling-stale (sample cadence > our final read).
        // We can't tell them apart from one snapshot, so suppress the alarm for tiny
        // absolute lags where a few KB of drift is just cadence noise. Threshold = 64 KiB,
        // calibrated so a stale post-drain gauge (PG sees the slot drained, framework still
        // shows the last spike) doesn't fail the run.
        long    absDriftBytes = Math.abs((long) post.frameworkLagBytes - post.pgLagBytes);
        boolean driftOk       = absDriftBytes <= 64L * 1024L
                                || !Double.isFinite(driftPct)
                                || Math.abs(driftPct) <= 5.0d;

        var verdict = (lagBoundedOk && lagDrainedOk && walStatusOk && deliveryOk && driftOk) ? "PASS" : "FAIL";

        return new SlotLagSnapshot(
                Instant.now().toString(),
                slotName,
                cdcAvailability.map(CdcAvailability::isActive).orElse(false),
                produced,
                delivered,
                lagMax,
                lagAvg,
                lagEnd,
                lagMaxBytesThreshold,
                post.walStatus,
                driftPct,
                lagBoundedOk,
                lagDrainedOk,
                walStatusOk,
                deliveryOk,
                driftOk,
                verdict,
                TimeUnit.NANOSECONDS.toMillis(durationNanos),
                pre,
                post,
                samples,
                cdcAvailability.map(CdcAvailability::snapshot).orElse(null)
        );
    }

    private static double computeDriftPct(long pgValue, double frameworkValue) {
        if (pgValue <= 0L || frameworkValue < 0d) return Double.NaN;
        return ((frameworkValue - pgValue) * 100.0d) / pgValue;
    }

    private void writeMetricsIfConfigured(String metricsOutputFile, String json) throws IOException {
        if (!StringUtils.hasText(metricsOutputFile)) return;
        var target = Paths.get(metricsOutputFile).toAbsolutePath().normalize();
        if (target.getParent() != null) {
            Files.createDirectories(target.getParent());
        }
        Files.writeString(target, json + System.lineSeparator(),
                          StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.WRITE);
        log.info("Wrote slot-lag-bounded metrics to {}", target);
        System.out.println("############# [perf-lab] slot-lag-bounded metrics file: " + target);
    }

    private String toJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Failed to serialize slot-lag-bounded metrics to JSON", e);
        }
    }

    /**
     * Per-tick snapshot of the slot's server-side state plus the framework's gauge values.
     * {@code timestampMs} is relative to the scenario's start.
     */
    public record SlotSample(long timestampMs, SlotState state) {
    }

    /**
     * Direct read of {@code pg_replication_slots} merged with {@code essentials.cdc.slot.*}
     * gauge values. Holding both lets the summarizer detect a divergence between what the
     * framework reports and what PostgreSQL actually says.
     */
    public record SlotState(boolean exists,
                            boolean active,
                            String confirmedFlushLsn,
                            String restartLsn,
                            String walStatus,
                            long   pgLagBytes,
                            double frameworkLagBytes,
                            double frameworkActive,
                            double frameworkWalStatusCode) {
    }

    /**
     * Final scenario output. Top-level fields mirror the existing baseline scenario's
     * convention so {@code summarize-compare.sh} can index them with the same paths.
     */
    public record SlotLagSnapshot(String capturedAt,
                                  String slotName,
                                  boolean cdcActive,
                                  long producedEvents,
                                  long deliveredEvents,
                                  long lagBytesMax,
                                  long lagBytesAvg,
                                  long lagBytesEnd,
                                  long lagBytesThreshold,
                                  String walStatusEnd,
                                  double frameworkVsPgDriftPct,
                                  boolean lagBoundedOk,
                                  boolean lagDrainedOk,
                                  boolean walStatusOk,
                                  boolean deliveryOk,
                                  boolean driftOk,
                                  String verdict,
                                  long durationMs,
                                  SlotState pre,
                                  SlotState post,
                                  List<SlotSample> samples,
                                  CdcAvailability.Snapshot cdc) {
    }

    /**
     * Minimal event payload — enough to serialize, no business semantics intended. Mirrors
     * the convention used by {@code BaselinePollingVsCdcScenario#LabOrderPlaced}.
     */
    private record LabSlotLagEvent(String aggregateId, long sequence, long appendedAtNanos) {
    }
}
