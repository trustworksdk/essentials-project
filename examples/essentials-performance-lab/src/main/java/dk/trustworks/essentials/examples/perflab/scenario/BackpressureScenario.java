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
import dk.trustworks.essentials.components.boot.autoconfigure.postgresql.eventstore.EssentialsEventStoreProperties;
import dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.cdc.CdcAvailability;
import dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.eventstream.AggregateType;
import dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.eventstream.PersistedEvent;
import dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.persistence.OptimisticAppendToStreamException;
import dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.subscription.EventStoreSubscriptionManager;
import dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.transaction.EventStoreUnitOfWork;
import dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.transaction.EventStoreUnitOfWorkFactory;
import dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.types.GlobalEventOrder;
import dk.trustworks.essentials.components.foundation.types.SubscriberId;
import dk.trustworks.essentials.examples.perflab.EssentialsPerformanceLabProperties;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Gauge;
import org.jdbi.v3.core.Jdbi;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.io.IOException;
import java.nio.file.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import java.util.concurrent.locks.LockSupport;

/**
 * Backpressure / slow-subscriber scenario. Validates that the CDC pipeline's bounded buffers hold
 * under sustained producer load when subscribers consume slower than producers produce.
 * <p>
 * The scenario adds an artificial sleep inside each subscriber handler
 * ({@code essentials.lab.subscriber-handler-delay-ms}) and samples, during the measurement window:
 * <ul>
 *   <li>peak {@code essentials.cdc.backfill_live.buffer.size} (the bounded live-event buffer
 *       inside {@code BackfillThenLiveOrdered})</li>
 *   <li>peak {@code RECEIVED}-status count in the CDC inbox (INBOX mode backlog)</li>
 *   <li>cumulative {@code essentials.cdc.dispatcher.tick.failures} and
 *       {@code essentials.cdc.dispatcher.conversion.failures}</li>
 *   <li>dispatcher gauge for {@code essentials.cdc.dispatcher.poison.rows} (conversion poison)</li>
 * </ul>
 * <p>
 * Invariants asserted at the end (failures logged at ERROR and reflected in the JSON output):
 * <ul>
 *   <li><b>invariantBoundedBufferHeld</b>: peak BackfillThenLiveOrdered buffer size ≤
 *       {@code eventBus.backpressureBufferSize} (default 8192) — validates the bounded-buffer fix.</li>
 *   <li><b>invariantNoEventsActuallyLost</b>: all produced events are durably persisted in the
 *       aggregate's event stream table by end of run. This is the true correctness invariant —
 *       "events went missing" vs. "events delayed past timeout".</li>
 *   <li><b>invariantCaughtUpWithinTimeout</b>: every produced event reached every subscriber
 *       before the catchup budget elapsed. False here means "backlog still draining when we gave
 *       up waiting" — typically indicates dispatcher starvation (e.g. stale inbox from a prior
 *       run). Does NOT imply data loss.</li>
 *   <li><b>invariantNoDispatcherTickFailures</b>: zero dispatcher tick failures during the run —
 *       validates the catch-all fix didn't mask real bugs.</li>
 * </ul>
 */
@Component
public class BackpressureScenario implements LabScenario {
    private static final Logger log = LoggerFactory.getLogger(BackpressureScenario.class);

    private static final AggregateType ORDERS = AggregateType.of("LabOrdersBackpressure");
    private static final Duration SAMPLE_INTERVAL = Duration.ofMillis(100);

    private final EventStore eventStore;
    private final ConfigurableEventStore<?> configurableEventStore;
    private final EventStoreSubscriptionManager subscriptionManager;
    private final EventStoreUnitOfWorkFactory<? extends EventStoreUnitOfWork> unitOfWorkFactory;
    private final Optional<CdcAvailability> cdcAvailability;
    private final Optional<MeterRegistry> meterRegistry;
    private final EssentialsEventStoreProperties eventStoreProperties;
    private final Jdbi jdbi;
    private final ObjectMapper objectMapper;

    public BackpressureScenario(EventStore eventStore,
                                ConfigurableEventStore<?> configurableEventStore,
                                EventStoreSubscriptionManager subscriptionManager,
                                EventStoreUnitOfWorkFactory<? extends EventStoreUnitOfWork> unitOfWorkFactory,
                                Optional<CdcAvailability> cdcAvailability,
                                Optional<MeterRegistry> meterRegistry,
                                EssentialsEventStoreProperties eventStoreProperties,
                                Jdbi jdbi,
                                ObjectMapper objectMapper) {
        this.eventStore = eventStore;
        this.configurableEventStore = configurableEventStore;
        this.subscriptionManager = subscriptionManager;
        this.unitOfWorkFactory = unitOfWorkFactory;
        this.cdcAvailability = cdcAvailability;
        this.meterRegistry = meterRegistry;
        this.eventStoreProperties = eventStoreProperties;
        this.jdbi = jdbi;
        this.objectMapper = objectMapper;
    }

    @Override
    public String name() {
        return "backpressure";
    }

    @Override
    public String description() {
        return "Slow-subscriber scenario that validates CDC bounded buffers hold under sustained producer pressure";
    }

    @Override
    public void run(EssentialsPerformanceLabProperties properties) throws Exception {
        ensureAggregateConfigured();

        long handlerDelayMs = Math.max(0L, properties.getSubscriberHandlerDelayMs());
        int  producerRateHz = Math.max(0, properties.getProducerRateHz());
        if (handlerDelayMs == 0) {
            log.warn("[backpressure] essentials.lab.subscriber-handler-delay-ms is 0 — this run won't exercise any backpressure. Set a non-zero value (e.g. 50) to simulate a slow subscriber.");
        }
        if (handlerDelayMs > 0 && producerRateHz == 0) {
            log.warn("[backpressure] essentials.lab.producer-rate-hz is 0 (unthrottled) with a slow subscriber (delay={}ms). "
                             + "The producer will outpace the subscriber and accumulate a backlog that may take far longer than the measurement window to drain. "
                             + "For a drainable matrix case, try producerRateHz ≈ 2 × 1000 / handlerDelayMs (= {} eps here).",
                     handlerDelayMs, 2_000L / Math.max(1L, handlerDelayMs));
        }

        var cdcProperties = eventStoreProperties.getCdc();
        int backpressureBufferSize = cdcProperties.getEventBus().getBackpressureBufferSize();
        log.info("[backpressure] handlerDelayMs={}ms producerRateHz={} backpressureBufferSize={}",
                 handlerDelayMs, producerRateHz, backpressureBufferSize);

        var subscriptions = new ArrayList<EventStoreSubscription>();
        var collector = new MetricsCollector();
        var sampler = new PressureSampler(meterRegistry, jdbi, cdcProperties.getInboxTableName());

        var startFrom = currentHighWatermark().map(GlobalEventOrder::increment)
                                              .orElse(GlobalEventOrder.FIRST_GLOBAL_EVENT_ORDER);

        for (int i = 0; i < properties.getSubscriberCount(); i++) {
            var subscriberId = SubscriberId.of("lab-backpressure-" + i + "-" + UUID.randomUUID());
            var subscription = subscriptionManager.subscribeToAggregateEventsAsynchronously(
                    subscriberId,
                    ORDERS,
                    startFrom,
                    ev -> {
                        collector.recordDelivery(ev);
                        if (handlerDelayMs > 0) {
                            try {
                                Thread.sleep(handlerDelayMs);
                            } catch (InterruptedException ie) {
                                Thread.currentThread().interrupt();
                            }
                        }
                    }
            );
            subscriptions.add(subscription);
        }

        ScheduledExecutorService samplerExec = Executors.newScheduledThreadPool(1, r -> {
            var t = new Thread(r, "lab-backpressure-sampler");
            t.setDaemon(true);
            return t;
        });

        // Progress reporter heartbeat. Long drain phases (up to max(3 × duration, 120s)) would
        // otherwise be silent for minutes — confusing for operators watching the matrix run.
        // Fields are updated by the main flow as it transitions through phases; the scheduler
        // reads them and emits a grep-friendly line every 10s.
        var progressPhase        = new AtomicReference<>("init");
        var progressPhaseStartNs = new AtomicLong(System.nanoTime());
        var progressDeadlineNs   = new AtomicLong(0L);
        var progressFuture       = samplerExec.scheduleAtFixedRate(
                () -> logProgress(progressPhase.get(), progressPhaseStartNs.get(), progressDeadlineNs.get(), collector, sampler),
                10, 10, TimeUnit.SECONDS);

        try {
            // Short warmup without delay to get the stream flowing.
            progressPhase.set("warmup");
            progressPhaseStartNs.set(System.nanoTime());
            var warmupProduced = runProducerPhase(properties.getWarmup(), properties, properties.getRandomSeed(), new MetricsCollector(), 0);
            waitForDeliveries(warmupProduced * properties.getSubscriberCount(), collector, TimeUnit.SECONDS.toMillis(10));

            collector.reset();
            sampler.reset();

            // Watermark captured *after* warmup-drain but *before* measurement-producer starts.
            // Events appended during measurement all have global_order > this value, so a post-run
            // count of rows with global_order > preMeasurementWatermark in the aggregate's event
            // stream table is the ground truth for "how many of our produced events are durably in
            // the DB". That lets us distinguish "delayed in backlog" from "actually lost".
            long preMeasurementWatermark = currentHighWatermark()
                    .map(GlobalEventOrder::longValue)
                    .orElse(0L);

            var samplerFuture = samplerExec.scheduleAtFixedRate(sampler::sample,
                                                                0,
                                                                SAMPLE_INTERVAL.toMillis(),
                                                                TimeUnit.MILLISECONDS);

            progressPhase.set("measurement");
            progressPhaseStartNs.set(System.nanoTime());
            var measurementStartedAtNanos = System.nanoTime();
            var measurementProduced = runProducerPhase(properties.getDuration(),
                                                       properties,
                                                       properties.getRandomSeed() + 10_000,
                                                       collector,
                                                       1);
            var producerStoppedAtNanos = System.nanoTime();

            long expectedDeliveries = measurementProduced * properties.getSubscriberCount();

            // Catchup window: bounded max(3 × duration, 120s) so a single case can never stall the
            // matrix, even when producers massively outpaced subscribers (e.g. unthrottled + slow
            // handler). Within that ceiling we still scale proportionally to the estimated drain
            // time — short runs don't waste minutes waiting when a few seconds will do.
            long catchupCeilingMs = Math.max(properties.getDuration().toMillis() * 3L, TimeUnit.SECONDS.toMillis(120));
            long drainEstimateMs  = handlerDelayMs > 0
                    ? (long) Math.ceil((double) measurementProduced * handlerDelayMs / Math.max(1, properties.getSubscriberCount()) * 1.5)
                      + TimeUnit.SECONDS.toMillis(15)
                    : TimeUnit.SECONDS.toMillis(30);
            long catchupBudgetMs = Math.min(catchupCeilingMs, Math.max(TimeUnit.SECONDS.toMillis(30), drainEstimateMs));
            log.info("[backpressure] catchup budget: {} ms (estimate={} ms, ceiling={} ms)",
                     catchupBudgetMs, drainEstimateMs, catchupCeilingMs);
            progressPhase.set("catchup");
            progressPhaseStartNs.set(System.nanoTime());
            progressDeadlineNs.set(System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(catchupBudgetMs));
            var catchup = waitForDeliveries(expectedDeliveries, collector, catchupBudgetMs);

            samplerFuture.cancel(false);
            progressFuture.cancel(false);
            sampler.sample(); // final reading — also populates finalInboxReceivedCount
            progressPhase.set("done");

            long eventsInDbCount = countEventsAppendedDuringMeasurement(preMeasurementWatermark);

            var snapshot = collector.snapshot(detectMode(),
                                              handlerDelayMs,
                                              producerRateHz,
                                              catchupBudgetMs,
                                              backpressureBufferSize,
                                              measurementProduced,
                                              eventsInDbCount,
                                              expectedDeliveries,
                                              properties,
                                              measurementStartedAtNanos,
                                              producerStoppedAtNanos,
                                              catchup,
                                              sampler.snapshot(),
                                              cdcAvailability.map(CdcAvailability::snapshot));
            assertInvariantsAndLog(snapshot);

            var json = toJson(snapshot);
            log.info("Backpressure scenario metrics: {}", json);
            System.out.println("############# [perf-lab] BACKPRESSURE DONE #############");
            System.out.println("############# [perf-lab] mode=" + snapshot.mode() +
                               " handler_delay_ms=" + snapshot.handlerDelayMs() +
                               " produced=" + snapshot.producedEvents() +
                               " eventsInDb=" + snapshot.eventsInDbCount() +
                               " delivered=" + snapshot.deliveredEvents() +
                               " peak_buffer=" + snapshot.pressure().peakBackfillLiveBufferSize() +
                               " buffer_bound=" + snapshot.backpressureBufferSize() +
                               " peak_inbox_backlog=" + snapshot.pressure().peakInboxReceivedCount() +
                               " final_inbox_backlog=" + snapshot.pressure().finalInboxReceivedCount() +
                               " bound_held=" + snapshot.invariantBoundedBufferHeld() +
                               " no_actual_loss=" + snapshot.invariantNoEventsActuallyLost() +
                               " caught_up=" + snapshot.invariantCaughtUpWithinTimeout() +
                               " no_tick_failures=" + snapshot.invariantNoDispatcherTickFailures());
            System.out.println("############# [perf-lab] ##############################");
            writeMetricsIfConfigured(properties.getMetricsOutputFile(), json);
        } finally {
            samplerExec.shutdownNow();
            subscriptions.forEach(EventStoreSubscription::unsubscribe);
        }
    }

    /**
     * Emits a grep-friendly progress heartbeat. Printed to both logger and stdout so it's visible
     * whether the run is followed via {@code tail -f} on the log file or via the matrix script's
     * watcher that greps the log. Keep the format stable — the matrix script parses it.
     */
    private void logProgress(String phase,
                             long phaseStartNs,
                             long deadlineNs,
                             MetricsCollector collector,
                             PressureSampler sampler) {
        if ("init".equals(phase) || "done".equals(phase)) return;
        long elapsedS = Math.max(0, TimeUnit.NANOSECONDS.toSeconds(System.nanoTime() - phaseStartNs));
        long delivered = collector.deliveredCount();
        var pressure = sampler.snapshot();
        String remaining = "";
        if ("catchup".equals(phase) && deadlineNs > 0) {
            long remainingS = Math.max(0, TimeUnit.NANOSECONDS.toSeconds(deadlineNs - System.nanoTime()));
            remaining = " remainingBudgetS=" + remainingS;
        }
        String line = String.format(java.util.Locale.ROOT,
                "[backpressure] progress phase=%s elapsedS=%d delivered=%d peakBuffer=%d peakInboxBacklog=%d tickFailures=%d%s",
                phase, elapsedS, delivered,
                pressure.peakBackfillLiveBufferSize(),
                pressure.peakInboxReceivedCount(),
                pressure.dispatcherTickFailuresDelta(),
                remaining);
        log.info(line);
        System.out.println(line);
    }

    private void assertInvariantsAndLog(BackpressureMetrics m) {
        if (!m.invariantBoundedBufferHeld()) {
            log.error("[backpressure] INVARIANT VIOLATED: BackfillThenLiveOrdered buffer exceeded configured bound (peak={} > bound={})",
                      m.pressure().peakBackfillLiveBufferSize(), m.backpressureBufferSize());
        }
        if (!m.invariantNoEventsActuallyLost()) {
            log.error("[backpressure] INVARIANT VIOLATED: events missing from DB (produced={} eventsInDb={}). "
                              + "This indicates actual data loss — not a delivery delay.",
                      m.producedEvents(), m.eventsInDbCount());
        }
        if (!m.invariantCaughtUpWithinTimeout()) {
            // Not an error — delivery-catchup timeout is common with slow-subscriber scenarios and stale inboxes.
            // Log at WARN so operators know the case ran its full catchup budget without converging, but don't
            // alarm them — invariantNoEventsActuallyLost is the real correctness signal.
            log.warn("[backpressure] catchup timed out: delivered={} of {} (lag={}, finalInboxBacklog={}). "
                             + "Events are durable in DB ({} / {}) — this is a delivery delay, not data loss.",
                     m.deliveredEvents(), m.expectedDeliveries(), m.deliveryLagEventsEnd(),
                     m.pressure().finalInboxReceivedCount(),
                     m.eventsInDbCount(), m.producedEvents());
        }
        if (!m.invariantNoDispatcherTickFailures()) {
            log.error("[backpressure] INVARIANT VIOLATED: dispatcher had {} tick failures during the run",
                      m.pressure().dispatcherTickFailuresDelta());
        }
    }

    /**
     * Counts rows in this scenario's aggregate event-stream table with {@code global_order} greater
     * than the pre-measurement watermark. Returned value equals {@code producedEvents} when all
     * the scenario's appends landed durably — a precondition for "nothing lost, just delayed".
     * <p>
     * Table name follows the convention established by {@link
     * dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.persistence.table_per_aggregate_type.SeparateTablePerAggregateTypeEventStreamConfigurationFactory#defaultConfiguration}:
     * lowercased aggregate-type name + {@code _events}.
     */
    private long countEventsAppendedDuringMeasurement(long preMeasurementWatermark) {
        String tableName = ORDERS.toString().toLowerCase(java.util.Locale.ROOT) + "_events";
        try {
            return jdbi.withHandle(h -> h.createQuery("select count(*) from " + tableName + " where global_order > :watermark")
                                          .bind("watermark", preMeasurementWatermark)
                                          .mapTo(Long.class)
                                          .findFirst()
                                          .orElse(0L));
        } catch (Exception e) {
            log.warn("[backpressure] Could not count rows in {} (this defeats the 'no actual loss' invariant): {}",
                     tableName, e.getMessage());
            return -1L;  // sentinel — invariant computation treats this as "unknown / fail closed".
        }
    }

    private String detectMode() {
        boolean cdcWrapper = eventStore.getClass().getSimpleName().contains("CdcEventStore");
        if (!cdcWrapper) return "polling";
        return cdcAvailability.map(CdcAvailability::isActive).orElse(false) ? "cdc-active" : "cdc-fallback";
    }

    private void ensureAggregateConfigured() {
        if (configurableEventStore.findAggregateEventStreamConfiguration(ORDERS).isEmpty()) {
            configurableEventStore.addAggregateEventStreamConfiguration(ORDERS, String.class);
        }
    }

    private Optional<GlobalEventOrder> currentHighWatermark() {
        return unitOfWorkFactory.withUnitOfWork(() -> eventStore.findHighestGlobalEventOrderPersisted(ORDERS));
    }

    private long runProducerPhase(Duration phaseDuration,
                                  EssentialsPerformanceLabProperties properties,
                                  long seed,
                                  MetricsCollector collector,
                                  int phaseIndex) throws InterruptedException {
        if (phaseDuration.isZero() || phaseDuration.isNegative()) return 0;

        var nextEventNumber = new AtomicLong();
        var produced = new AtomicLong();
        var appendConflictErrors = new AtomicLong();
        var appendInfrastructureErrors = new AtomicLong();
        var appendRetriedConflicts = new AtomicLong();
        long deadlineNanos = System.nanoTime() + phaseDuration.toNanos();

        int  producerRateHz         = Math.max(0, properties.getProducerRateHz());
        long perThreadIntervalNanos = producerRateHz > 0
                ? 1_000_000_000L * properties.getProducerThreads() / producerRateHz
                : 0L;

        var executor = Executors.newFixedThreadPool(properties.getProducerThreads(), runnable -> {
            var thread = new Thread(runnable, "lab-bp-producer-" + phaseIndex);
            thread.setDaemon(true);
            return thread;
        });

        for (int i = 0; i < properties.getProducerThreads(); i++) {
            final int producerIndex = i;
            executor.submit(() -> {
                var random = new Random(seed + (long) producerIndex * 31L + (long) phaseIndex * 997L);
                var aggregateCardinality = Math.max(1, properties.getAggregateCardinality());
                if (producerIndex >= aggregateCardinality) return;

                // Stagger start across threads so they don't all fire on the same tick.
                long nextAppendAtNanos = System.nanoTime()
                                         + (perThreadIntervalNanos > 0 ? perThreadIntervalNanos * producerIndex / properties.getProducerThreads() : 0L);

                while (System.nanoTime() < deadlineNanos) {
                    if (perThreadIntervalNanos > 0) {
                        long waitNanos = nextAppendAtNanos - System.nanoTime();
                        if (waitNanos > 0) {
                            LockSupport.parkNanos(waitNanos);
                        }
                        nextAppendAtNanos += perThreadIntervalNanos;
                    }

                    var aggregateId = "bp-order-" + nextAggregateIndex(random,
                                                                        producerIndex,
                                                                        properties.getProducerThreads(),
                                                                        aggregateCardinality);
                    var event = new LabOrderPlaced(aggregateId,
                                                   nextEventNumber.incrementAndGet(),
                                                   System.nanoTime());
                    var maxAttempts = Math.max(1, properties.getAppendMaxAttempts());
                    var retryBackoffMillis = Math.max(0L, properties.getAppendRetryBackoff().toMillis());
                    for (int attempt = 1; attempt <= maxAttempts; attempt++) {
                        try {
                            unitOfWorkFactory.withUnitOfWork(() -> {
                                eventStore.appendToStream(ORDERS, aggregateId, List.of(event));
                                return null;
                            });
                            produced.incrementAndGet();
                            break;
                        } catch (Exception e) {
                            var optimisticConflict = isOptimisticConflict(e);
                            var canRetry = optimisticConflict && attempt < maxAttempts;
                            if (canRetry) {
                                appendRetriedConflicts.incrementAndGet();
                                if (retryBackoffMillis > 0) {
                                    try {
                                        Thread.sleep(retryBackoffMillis * attempt);
                                    } catch (InterruptedException interruptedException) {
                                        Thread.currentThread().interrupt();
                                        return;
                                    }
                                }
                                continue;
                            }
                            if (optimisticConflict) {
                                appendConflictErrors.incrementAndGet();
                            } else {
                                appendInfrastructureErrors.incrementAndGet();
                            }
                            break;
                        }
                    }
                }
            });
        }

        executor.shutdown();
        executor.awaitTermination(phaseDuration.toMillis() + TimeUnit.SECONDS.toMillis(5), TimeUnit.MILLISECONDS);
        if (!executor.isTerminated()) executor.shutdownNow();

        collector.setAppendOutcomes(appendConflictErrors.get(),
                                    appendInfrastructureErrors.get(),
                                    appendRetriedConflicts.get());
        return produced.get();
    }

    private int nextAggregateIndex(Random random,
                                   int producerIndex,
                                   int producerThreads,
                                   int aggregateCardinality) {
        int step = Math.max(1, producerThreads);
        int partitions = Math.max(1, (aggregateCardinality - producerIndex + step - 1) / step);
        return producerIndex + step * random.nextInt(partitions);
    }

    private boolean isOptimisticConflict(Throwable throwable) {
        var current = throwable;
        while (current != null) {
            if (current instanceof OptimisticAppendToStreamException) return true;
            current = current.getCause();
        }
        return false;
    }

    private DeliveryCatchup waitForDeliveries(long expected, MetricsCollector collector, long timeoutMillis) throws InterruptedException {
        if (expected <= 0) return new DeliveryCatchup(true, 0);
        long startedAt = System.currentTimeMillis();
        long deadline = startedAt + timeoutMillis;
        while (collector.deliveredCount() < expected && System.currentTimeMillis() < deadline) {
            Thread.sleep(50L);
        }
        long elapsed = Math.max(0L, System.currentTimeMillis() - startedAt);
        boolean caughtUp = collector.deliveredCount() >= expected;
        return new DeliveryCatchup(caughtUp, elapsed);
    }

    private void writeMetricsIfConfigured(String metricsOutputFile, String json) throws IOException {
        if (!StringUtils.hasText(metricsOutputFile)) return;
        var target = Paths.get(metricsOutputFile).toAbsolutePath().normalize();
        if (target.getParent() != null) Files.createDirectories(target.getParent());
        Files.writeString(target, json + System.lineSeparator(), StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.WRITE);
        log.info("Wrote backpressure metrics to {}", target);
        System.out.println("############# [perf-lab] backpressure metrics file: " + target);
    }

    private String toJson(BackpressureMetrics metrics) {
        try {
            return objectMapper.writeValueAsString(metrics);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Failed to serialize backpressure metrics to JSON", e);
        }
    }

    /**
     * Samples pressure-signals (gauge values + inbox-backlog query) periodically and tracks peaks.
     * Each sample is O(1) for gauges and a single count query against the inbox table.
     */
    private static final class PressureSampler {
        private final Optional<MeterRegistry> meterRegistry;
        private final Jdbi jdbi;
        private final String inboxTable;

        private final AtomicLong peakBackfillLiveBufferSize = new AtomicLong(0);
        private final AtomicLong peakInboxReceivedCount     = new AtomicLong(0);
        private final AtomicLong finalInboxReceivedCount    = new AtomicLong(0);
        private final AtomicLong samples                    = new AtomicLong(0);

        private long dispatcherTickFailuresAtStart = 0;
        private long dispatcherConversionFailuresAtStart = 0;
        private long dispatcherPoisonRowsAtStart = 0;

        PressureSampler(Optional<MeterRegistry> meterRegistry, Jdbi jdbi, String inboxTable) {
            this.meterRegistry = meterRegistry;
            this.jdbi = jdbi;
            this.inboxTable = inboxTable;
        }

        void reset() {
            peakBackfillLiveBufferSize.set(0);
            peakInboxReceivedCount.set(0);
            finalInboxReceivedCount.set(0);
            samples.set(0);
            dispatcherTickFailuresAtStart = readCounter("essentials.cdc.dispatcher.tick.failures");
            dispatcherConversionFailuresAtStart = readCounter("essentials.cdc.dispatcher.conversion.failures");
            dispatcherPoisonRowsAtStart = readCounter("essentials.cdc.dispatcher.poison.rows");
        }

        void sample() {
            samples.incrementAndGet();
            long bufferSize = readGauge("essentials.cdc.backfill_live.buffer.size");
            peakBackfillLiveBufferSize.accumulateAndGet(bufferSize, Math::max);
            long inbox = inboxReceivedCount();
            peakInboxReceivedCount.accumulateAndGet(inbox, Math::max);
            // Overwrite each call so snapshot() reflects the most recent reading (i.e. the value
            // at end-of-run when called from the main flow's final sampler.sample()).
            finalInboxReceivedCount.set(inbox);
        }

        Pressure snapshot() {
            return new Pressure(
                    peakBackfillLiveBufferSize.get(),
                    peakInboxReceivedCount.get(),
                    finalInboxReceivedCount.get(),
                    samples.get(),
                    Math.max(0L, readCounter("essentials.cdc.dispatcher.tick.failures") - dispatcherTickFailuresAtStart),
                    Math.max(0L, readCounter("essentials.cdc.dispatcher.conversion.failures") - dispatcherConversionFailuresAtStart),
                    Math.max(0L, readCounter("essentials.cdc.dispatcher.poison.rows") - dispatcherPoisonRowsAtStart)
            );
        }

        private long readGauge(String name) {
            return meterRegistry.map(reg -> {
                Gauge g = reg.find(name).gauge();
                return g == null ? 0L : (long) g.value();
            }).orElse(0L);
        }

        private long readCounter(String name) {
            return meterRegistry.map(reg -> {
                var c = reg.find(name).counter();
                return c == null ? 0L : (long) c.count();
            }).orElse(0L);
        }

        private long inboxReceivedCount() {
            try {
                return jdbi.withHandle(h -> h.createQuery("select count(*) from " + inboxTable + " where status = 'RECEIVED'")
                                              .mapTo(Long.class)
                                              .findFirst()
                                              .orElse(0L));
            } catch (Exception e) {
                // INBOX table may not exist in DIRECT mode — that's fine, return 0.
                return 0L;
            }
        }
    }

    private static final class MetricsCollector {
        private final AtomicLong delivered = new AtomicLong();
        private final AtomicLong deserializationMisses = new AtomicLong();
        private final AtomicLong appendConflictErrors = new AtomicLong();
        private final AtomicLong appendInfrastructureErrors = new AtomicLong();
        private final AtomicLong appendRetriedConflicts = new AtomicLong();
        private final AtomicLong firstDeliveryAtNanos = new AtomicLong(Long.MAX_VALUE);
        private final List<Long> latenciesNanos = Collections.synchronizedList(new ArrayList<>());

        void recordDelivery(PersistedEvent event) {
            delivered.incrementAndGet();
            firstDeliveryAtNanos.accumulateAndGet(System.nanoTime(), Math::min);
            var payload = event.event().getJsonDeserialized().orElse(null);
            if (payload instanceof LabOrderPlaced placed) {
                latenciesNanos.add(Math.max(0, System.nanoTime() - placed.appendedAtNanos()));
            } else {
                deserializationMisses.incrementAndGet();
            }
        }

        void setAppendOutcomes(long conflictErrors, long infrastructureErrors, long retriedConflicts) {
            appendConflictErrors.set(conflictErrors);
            appendInfrastructureErrors.set(infrastructureErrors);
            appendRetriedConflicts.set(retriedConflicts);
        }

        long deliveredCount() {
            return delivered.get();
        }

        void reset() {
            delivered.set(0);
            deserializationMisses.set(0);
            appendConflictErrors.set(0);
            appendInfrastructureErrors.set(0);
            appendRetriedConflicts.set(0);
            firstDeliveryAtNanos.set(Long.MAX_VALUE);
            synchronized (latenciesNanos) {
                latenciesNanos.clear();
            }
        }

        BackpressureMetrics snapshot(String mode,
                                     long handlerDelayMs,
                                     int producerRateHz,
                                     long catchupBudgetMs,
                                     int backpressureBufferSize,
                                     long produced,
                                     long eventsInDbCount,
                                     long expectedDeliveries,
                                     EssentialsPerformanceLabProperties properties,
                                     long measurementStartedAtNanos,
                                     long producerStoppedAtNanos,
                                     DeliveryCatchup catchup,
                                     Pressure pressure,
                                     Optional<CdcAvailability.Snapshot> cdcSnapshot) {
            long[] sortedLatencies;
            synchronized (latenciesNanos) {
                sortedLatencies = latenciesNanos.stream().mapToLong(Long::longValue).toArray();
            }
            Arrays.sort(sortedLatencies);

            var runMillis = Math.max(1L, properties.getDuration().toMillis());
            var appendThroughput = produced * 1_000.0d / runMillis;
            var deliveryThroughput = delivered.get() * 1_000.0d / runMillis;
            var deliveredCount = delivered.get();
            var finalLagEvents = Math.max(0L, expectedDeliveries - deliveredCount);
            var completionPct = expectedDeliveries == 0 ? 100.0d : (deliveredCount * 100.0d) / expectedDeliveries;
            var producerWindowMs = Math.max(1L, TimeUnit.NANOSECONDS.toMillis(Math.max(0L, producerStoppedAtNanos - measurementStartedAtNanos)));
            var catchupMs = catchup.caughtUp() ? catchup.elapsedMs() : -1L;

            boolean bufferBoundHeld         = pressure.peakBackfillLiveBufferSize() <= backpressureBufferSize;
            boolean caughtUpWithinTimeout   = catchup.caughtUp() && finalLagEvents == 0;
            // Durability check: count of rows in the aggregate's table appended during measurement.
            // -1 sentinel = query failed → treat as unknown (fail closed on the invariant).
            boolean noEventsActuallyLost    = eventsInDbCount >= 0 && eventsInDbCount >= produced;
            boolean noTickFailures          = pressure.dispatcherTickFailuresDelta() == 0;

            return new BackpressureMetrics(
                    mode,
                    Instant.now().toString(),
                    handlerDelayMs,
                    producerRateHz,
                    catchupBudgetMs,
                    backpressureBufferSize,
                    produced,
                    eventsInDbCount,
                    expectedDeliveries,
                    deliveredCount,
                    appendConflictErrors.get() + appendInfrastructureErrors.get(),
                    deserializationMisses.get(),
                    appendThroughput,
                    deliveryThroughput,
                    percentileMillis(sortedLatencies, 0.50d),
                    percentileMillis(sortedLatencies, 0.95d),
                    percentileMillis(sortedLatencies, 0.99d),
                    producerWindowMs,
                    catchupMs,
                    catchup.caughtUp(),
                    finalLagEvents,
                    completionPct,
                    pressure,
                    bufferBoundHeld,
                    noEventsActuallyLost,
                    caughtUpWithinTimeout,
                    noTickFailures,
                    cdcSnapshot.orElse(null)
            );
        }

        private static double percentileMillis(long[] sortedLatenciesNanos, double percentile) {
            if (sortedLatenciesNanos.length == 0) return 0.0d;
            int index = (int) Math.ceil(percentile * sortedLatenciesNanos.length) - 1;
            index = Math.max(0, Math.min(index, sortedLatenciesNanos.length - 1));
            return sortedLatenciesNanos[index] / 1_000_000.0d;
        }
    }

    private record BackpressureMetrics(String mode,
                                       String capturedAt,
                                       long handlerDelayMs,
                                       int producerRateHz,
                                       long catchupBudgetMs,
                                       int backpressureBufferSize,
                                       long producedEvents,
                                       /** Count of rows in the aggregate's event-stream table appended during measurement. {@code -1} = query failed. */
                                       long eventsInDbCount,
                                       long expectedDeliveries,
                                       long deliveredEvents,
                                       long appendErrors,
                                       long deserializationMisses,
                                       double appendEventsPerSecond,
                                       double deliveredEventsPerSecond,
                                       double p50LatencyMs,
                                       double p95LatencyMs,
                                       double p99LatencyMs,
                                       long producerWindowMs,
                                       long timeToCatchUpMs,
                                       boolean caughtUpRaw,
                                       long deliveryLagEventsEnd,
                                       double deliveryCompletionPct,
                                       Pressure pressure,
                                       boolean invariantBoundedBufferHeld,
                                       /** True iff every produced event is durably persisted in the DB by end-of-run. Real correctness signal. */
                                       boolean invariantNoEventsActuallyLost,
                                       /** True iff subscribers received every expected event before catchup budget elapsed. Delivery-timeliness signal. */
                                       boolean invariantCaughtUpWithinTimeout,
                                       boolean invariantNoDispatcherTickFailures,
                                       CdcAvailability.Snapshot cdc) {
    }

    private record Pressure(long peakBackfillLiveBufferSize,
                            long peakInboxReceivedCount,
                            /** Final inbox RECEIVED count at end-of-run. Combined with eventsInDbCount tells you whether drain is just slow (high finalInboxBacklog) or whether something is stuck. */
                            long finalInboxReceivedCount,
                            long samples,
                            long dispatcherTickFailuresDelta,
                            long dispatcherConversionFailuresDelta,
                            long dispatcherPoisonRowsDelta) {
    }

    private record DeliveryCatchup(boolean caughtUp, long elapsedMs) {
    }

    private record LabOrderPlaced(String aggregateId,
                                  long sequence,
                                  long appendedAtNanos) {
    }
}
