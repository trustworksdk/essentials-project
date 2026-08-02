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
import dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.eventstream.AggregateType;
import dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.eventstream.PersistedEvent;
import dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.persistence.OptimisticAppendToStreamException;
import dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.subscription.EventStoreSubscriptionManager;
import dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.transaction.EventStoreUnitOfWork;
import dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.transaction.EventStoreUnitOfWorkFactory;
import dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.types.GlobalEventOrder;
import dk.trustworks.essentials.components.foundation.types.SubscriberId;
import dk.trustworks.essentials.examples.perflab.EssentialsPerformanceLabProperties;
import jakarta.annotation.PostConstruct;
import org.jdbi.v3.core.Jdbi;
import org.jdbi.v3.core.statement.SqlLogger;
import org.jdbi.v3.core.statement.SqlStatements;
import org.jdbi.v3.core.statement.StatementContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.io.IOException;
import java.nio.file.*;
import java.sql.SQLException;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import java.util.concurrent.locks.LockSupport;

@Component
public class BaselinePollingVsCdcScenario implements LabScenario {
    private static final Logger log = LoggerFactory.getLogger(BaselinePollingVsCdcScenario.class);

    private static final AggregateType ORDERS = AggregateType.of("LabOrders");
    /**
     * The event-stream table the scenario reads/writes. The standard naming convention
     * lowercases the aggregate-type name and appends {@code _events}, so this stays in
     * sync with {@link #ORDERS}.
     */
    private static final String ORDERS_TABLE = ORDERS.toString().toLowerCase() + "_events";

    private final EventStore eventStore;
    private final ConfigurableEventStore<?> configurableEventStore;
    private final EventStoreSubscriptionManager subscriptionManager;
    private final EventStoreUnitOfWorkFactory<? extends EventStoreUnitOfWork> unitOfWorkFactory;
    private final Optional<CdcAvailability> cdcAvailability;
    private final ObjectMapper objectMapper;
    private final Environment environment;
    private final Jdbi jdbi;

    public BaselinePollingVsCdcScenario(EventStore eventStore,
                                        ConfigurableEventStore<?> configurableEventStore,
                                        EventStoreSubscriptionManager subscriptionManager,
                                        EventStoreUnitOfWorkFactory<? extends EventStoreUnitOfWork> unitOfWorkFactory,
                                        Optional<CdcAvailability> cdcAvailability,
                                        ObjectMapper objectMapper,
                                        Environment environment,
                                        Jdbi jdbi) {
        this.eventStore = eventStore;
        this.configurableEventStore = configurableEventStore;
        this.subscriptionManager = subscriptionManager;
        this.unitOfWorkFactory = unitOfWorkFactory;
        this.cdcAvailability = cdcAvailability;
        this.objectMapper = objectMapper;
        this.environment = environment;
        this.jdbi = jdbi;
    }

    @Override
    public String name() {
        return "baseline-polling-vs-cdc";
    }

    @Override
    public String description() {
        return "Runs a fixed-seed append + subscribe workload and outputs JSON metrics for polling/CDC mode comparison";
    }

    /**
     * Register the scenario's aggregate type at Spring startup, matching how real applications
     * typically declare their aggregates. The CDC {@code AggregateTypeResolver} uses a live
     * supplier so runtime registration also works, but startup is the idiomatic path.
     */
    @PostConstruct
    void registerAggregateAtStartup() {
        if (configurableEventStore.findAggregateEventStreamConfiguration(ORDERS).isEmpty()) {
            configurableEventStore.addAggregateEventStreamConfiguration(ORDERS, String.class);
        }
    }

    @Override
    public void run(EssentialsPerformanceLabProperties properties) throws Exception {
        var subscriptions = new ArrayList<EventStoreSubscription>();
        var collector = new MetricsCollector();

        var startFrom = currentHighWatermark().map(GlobalEventOrder::increment)
                                              .orElse(GlobalEventOrder.FIRST_GLOBAL_EVENT_ORDER);

        // For CDC legs, wait for the WAL tailer to bring availability to ACTIVE before subscribing,
        // so the subscription is established directly on the CDC bus (push) path. Subscribing while
        // still INACTIVE would spend the warm-up + activeCutbackDebounce window on the polling
        // fallback before cutting over (see cdc-improvements.md P9), contaminating the latency
        // measurement with polling latency. Bounded wait; if CDC never activates we subscribe
        // anyway and the run self-labels cdc-fallback.
        boolean subscribedWhileCdcActive = awaitCdcActiveBeforeSubscribe();

        for (int i = 0; i < properties.getSubscriberCount(); i++) {
            var subscriberId = SubscriberId.of("lab-baseline-" + i + "-" + UUID.randomUUID());
            var subscription = subscriptionManager.subscribeToAggregateEventsAsynchronously(
                    subscriberId,
                    ORDERS,
                    startFrom,
                    collector::recordDelivery
            );
            subscriptions.add(subscription);
        }

        try {
            var warmupProduced = runProducerPhase(properties.getWarmup(), properties, properties.getRandomSeed(), new MetricsCollector(), 0);
            waitForDeliveries(warmupProduced * properties.getSubscriberCount(), collector, TimeUnit.SECONDS.toMillis(10));

            collector.reset();
            // Attach the event-store SELECT counter for the measurement window ONLY.
            // We don't care about warmup or catchup queries — only steady-state poll rate.
            // The counter wraps any existing SqlLogger so trace logging keeps working.
            var queryCounter = EventStoreSelectCounter.installOn(jdbi, ORDERS_TABLE);
            var measurementStartedAtNanos = System.nanoTime();
            var measurementProduced = runProducerPhase(properties.getDuration(), properties, properties.getRandomSeed() + 10_000, collector, 1);
            var producerStoppedAtNanos = System.nanoTime();

            long expectedDeliveries = measurementProduced * properties.getSubscriberCount();
            var catchup = waitForDeliveries(expectedDeliveries, collector, Math.max(TimeUnit.SECONDS.toMillis(10), properties.getDuration().toMillis()));
            // Snapshot the counter and restore the previous logger before building the
            // snapshot so the JSON includes only measurement-window queries.
            var dbLoad = queryCounter.snapshotAndUninstall();

            var snapshot = collector.snapshot(detectMode(subscribedWhileCdcActive),
                                              measurementProduced,
                                              expectedDeliveries,
                                              properties,
                                              measurementStartedAtNanos,
                                              producerStoppedAtNanos,
                                              catchup,
                                              cdcAvailability.map(CdcAvailability::snapshot),
                                              dbLoad);
            var json = toJson(snapshot);

            log.info("Baseline scenario metrics: {}", json);
            System.out.println("############# [perf-lab] BASELINE DONE #############");
            System.out.println("############# [perf-lab] mode=" + snapshot.mode() +
                               " produced=" + snapshot.producedEvents() +
                               " delivered=" + snapshot.deliveredEvents() +
                               " append_eps=" + String.format(java.util.Locale.ROOT, "%.2f", snapshot.appendEventsPerSecond()) +
                               " delivery_eps=" + String.format(java.util.Locale.ROOT, "%.2f", snapshot.deliveredEventsPerSecond()) +
                               " p95_ms=" + String.format(java.util.Locale.ROOT, "%.2f", snapshot.p95LatencyMs()) +
                               " catchup_ms=" + snapshot.timeToCatchUpMs() +
                               " sla_1000ms_pct=" + String.format(java.util.Locale.ROOT, "%.2f", snapshot.slaUnder1000msPct()));
            System.out.println("############# [perf-lab] ################################");
            writeMetricsIfConfigured(properties.getMetricsOutputFile(), json);
        } finally {
            subscriptions.forEach(EventStoreSubscription::unsubscribe);
        }
    }

    private String detectMode(boolean subscribedWhileCdcActive) {
        boolean cdcWrapper = eventStore.getClass().getSimpleName().contains("CdcEventStore");
        if (cdcWrapper) {
            // Report the path the subscription actually used, NOT the instantaneous availability.
            // A subscription established while CDC was ACTIVE delivers via the CDC bus (push); one
            // established while INACTIVE runs on the polling fallback (until a later cut-over). The
            // old check read isActive() at snapshot time — which is ACTIVE by end-of-run even when
            // the subscription spent the whole measurement window polling (the P9 mislabel).
            return subscribedWhileCdcActive ? "cdc-active" : "cdc-fallback";
        }
        // Pure polling subscriber path. Differentiate S1 notify-driven polling so child
        // runs of the comparison scenario self-label correctly without the parent having
        // to track which flag it set.
        boolean notifyPolling = Boolean.parseBoolean(
                environment.getProperty("essentials.eventstore.subscription-manager.notify-polling.enabled",
                                        "false"));
        return notifyPolling ? "polling-notify" : "polling";
    }

    /**
     * For CDC legs, block until {@link CdcAvailability} reports ACTIVE (the WAL tailer has
     * connected and the CDC bus is being fed) so subscriptions are established on the push path
     * rather than the polling fallback. Returns {@code true} if ACTIVE was observed; {@code false}
     * for non-CDC legs (nothing to wait for) or if CDC did not activate within the bound (the
     * caller then subscribes on the polling fallback and the run self-labels {@code cdc-fallback}).
     */
    private boolean awaitCdcActiveBeforeSubscribe() {
        if (cdcAvailability.isEmpty()) {
            return false; // non-CDC leg — nothing to wait for
        }
        var availability  = cdcAvailability.get();
        var deadlineNanos = System.nanoTime() + Duration.ofSeconds(60).toNanos();
        while (System.nanoTime() < deadlineNanos) {
            if (availability.isActive()) {
                log.info("CDC availability is ACTIVE — establishing subscriptions on the CDC bus (push) path");
                return true;
            }
            try {
                Thread.sleep(100L);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
        }
        log.warn("CDC availability did not reach ACTIVE within 60s — establishing subscriptions on the polling "
                 + "fallback path; latency for this run reflects polling, not CDC push");
        return false;
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

        // Producer-rate throttle. producerRateHz is the GLOBAL target across all threads;
        // dividing by thread count gives each thread its own pacing interval. 0 = unthrottled.
        // Necessary for "quiet workload" measurements where we need the subscriber to be idle
        // most of the time so the wake-up mechanism (jitter vs NOTIFY vs CDC push) becomes
        // the dominant latency contributor instead of subscriber backpressure. Accepts
        // fractional Hz (e.g. 0.1 = 1 event/10s) — see EssentialsPerformanceLabProperties.
        double producerRateHz        = Math.max(0.0d, properties.getProducerRateHz());
        long   perThreadIntervalNanos = producerRateHz > 0
                ? (long) (1_000_000_000.0d * properties.getProducerThreads() / producerRateHz)
                : 0L;

        var executor = Executors.newFixedThreadPool(properties.getProducerThreads(), runnable -> {
            var thread = new Thread(runnable, "lab-producer-" + phaseIndex);
            thread.setDaemon(true);
            return thread;
        });

        for (int i = 0; i < properties.getProducerThreads(); i++) {
            final int producerIndex = i;
            executor.submit(() -> {
                var random = new Random(seed + (long) producerIndex * 31L + (long) phaseIndex * 997L);
                var aggregateCardinality = Math.max(1, properties.getAggregateCardinality());
                if (producerIndex >= aggregateCardinality) {
                    return;
                }
                // Stagger thread start across the per-thread interval so all N threads
                // don't fire on the same tick — keeps the inter-arrival distribution
                // closer to the configured rate at low Hz.
                long nextAppendAtNanos = System.nanoTime()
                                         + (perThreadIntervalNanos > 0
                                                    ? perThreadIntervalNanos * producerIndex / properties.getProducerThreads()
                                                    : 0L);
                while (System.nanoTime() < deadlineNanos) {
                    if (perThreadIntervalNanos > 0) {
                        long waitNanos = nextAppendAtNanos - System.nanoTime();
                        if (waitNanos > 0) {
                            LockSupport.parkNanos(waitNanos);
                        }
                        nextAppendAtNanos += perThreadIntervalNanos;
                    }
                    var aggregateId = "order-" + nextAggregateIndex(random,
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
        if (!executor.isTerminated()) {
            executor.shutdownNow();
        }

        var totalAppendErrors = appendConflictErrors.get() + appendInfrastructureErrors.get();
        if (totalAppendErrors > 0) {
            log.warn("Phase {} had {} append errors (conflicts={}, infrastructure={}, conflictRetries={})",
                     phaseIndex,
                     totalAppendErrors,
                     appendConflictErrors.get(),
                     appendInfrastructureErrors.get(),
                     appendRetriedConflicts.get());
        }

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
            if (current instanceof OptimisticAppendToStreamException) {
                return true;
            }
            current = current.getCause();
        }
        return false;
    }

    private DeliveryCatchup waitForDeliveries(long expected, MetricsCollector collector, long timeoutMillis) throws InterruptedException {
        if (expected <= 0) return new DeliveryCatchup(true, 0);
        long startedAt = System.currentTimeMillis();
        long deadline = startedAt + timeoutMillis;
        while (collector.deliveredCount() < expected && System.currentTimeMillis() < deadline) {
            Thread.sleep(25L);
        }
        long elapsed = Math.max(0L, System.currentTimeMillis() - startedAt);
        boolean caughtUp = collector.deliveredCount() >= expected;
        return new DeliveryCatchup(caughtUp, elapsed);
    }

    private void writeMetricsIfConfigured(String metricsOutputFile, String json) throws IOException {
        if (!StringUtils.hasText(metricsOutputFile)) return;
        var target = Paths.get(metricsOutputFile).toAbsolutePath().normalize();
        if (target.getParent() != null) {
            Files.createDirectories(target.getParent());
        }
        Files.writeString(target, json + System.lineSeparator(), StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.WRITE);
        log.info("Wrote baseline metrics to {}", target);
        System.out.println("############# [perf-lab] baseline metrics file: " + target);
    }

    private String toJson(BaselineMetrics metrics) {
        try {
            return objectMapper.writeValueAsString(metrics);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Failed to serialize baseline metrics to JSON", e);
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

        BaselineMetrics snapshot(String mode,
                                 long produced,
                                 long expectedDeliveries,
                                 EssentialsPerformanceLabProperties properties,
                                 long measurementStartedAtNanos,
                                 long producerStoppedAtNanos,
                                 DeliveryCatchup catchup,
                                 Optional<CdcAvailability.Snapshot> cdcSnapshot,
                                 EventStoreSelectCounter.Snapshot dbLoad) {
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
            var firstDelivery = firstDeliveryAtNanos.get();
            var timeToFirstDeliveryMs = firstDelivery == Long.MAX_VALUE ? -1L : Math.max(0L, TimeUnit.NANOSECONDS.toMillis(firstDelivery - measurementStartedAtNanos));
            var producerWindowMs = Math.max(1L, TimeUnit.NANOSECONDS.toMillis(Math.max(0L, producerStoppedAtNanos - measurementStartedAtNanos)));
            var catchupMs = catchup.caughtUp() ? catchup.elapsedMs() : -1L;
            // Use the actual measurement window (producer start → catchup end) for the
            // DB-load per-second metric so the rate isn't skewed by a long quiet tail
            // or by warmup leaking in.
            var dbLoadWindowMillis = Math.max(1L, producerWindowMs + Math.max(0L, catchupMs));
            var selectsPerSecond = dbLoad.selectCount() * 1_000.0d / dbLoadWindowMillis;
            var selectsPerSecondPerSubscriber = properties.getSubscriberCount() == 0
                    ? selectsPerSecond
                    : selectsPerSecond / properties.getSubscriberCount();

            return new BaselineMetrics(
                    mode,
                    Instant.now().toString(),
                    produced,
                    expectedDeliveries,
                    deliveredCount,
                    appendConflictErrors.get() + appendInfrastructureErrors.get(),
                    appendConflictErrors.get(),
                    appendInfrastructureErrors.get(),
                    appendRetriedConflicts.get(),
                    deserializationMisses.get(),
                    appendThroughput,
                    deliveryThroughput,
                    percentileMillis(sortedLatencies, 0.50d),
                    percentileMillis(sortedLatencies, 0.95d),
                    percentileMillis(sortedLatencies, 0.99d),
                    percentileMillis(sortedLatencies, 0.50d),
                    percentileMillis(sortedLatencies, 0.95d),
                    percentileMillis(sortedLatencies, 0.99d),
                    ratioAtOrBelowMillis(sortedLatencies, 500.0d),
                    ratioAtOrBelowMillis(sortedLatencies, 1_000.0d),
                    timeToFirstDeliveryMs,
                    producerWindowMs,
                    catchupMs,
                    catchup.caughtUp(),
                    finalLagEvents,
                    completionPct,
                    properties.getProducerRateHz(),
                    ORDERS_TABLE,
                    dbLoad.selectCount(),
                    selectsPerSecond,
                    selectsPerSecondPerSubscriber,
                    dbLoadWindowMillis,
                    cdcSnapshot.orElse(null)
            );
        }

        private static double percentileMillis(long[] sortedLatenciesNanos, double percentile) {
            if (sortedLatenciesNanos.length == 0) return 0.0d;
            int index = (int) Math.ceil(percentile * sortedLatenciesNanos.length) - 1;
            index = Math.max(0, Math.min(index, sortedLatenciesNanos.length - 1));
            return sortedLatenciesNanos[index] / 1_000_000.0d;
        }

        private static double ratioAtOrBelowMillis(long[] sortedLatenciesNanos, double thresholdMs) {
            if (sortedLatenciesNanos.length == 0) return 0.0d;
            long thresholdNanos = (long) (thresholdMs * 1_000_000.0d);
            int accepted = 0;
            for (long latencyNanos : sortedLatenciesNanos) {
                if (latencyNanos <= thresholdNanos) {
                    accepted++;
                } else {
                    break;
                }
            }
            return accepted * 100.0d / sortedLatenciesNanos.length;
        }
    }

    private record BaselineMetrics(String mode,
                                   String capturedAt,
                                   long producedEvents,
                                   long expectedDeliveries,
                                   long deliveredEvents,
                                   long appendErrors,
                                   long appendConflictErrors,
                                   long appendInfrastructureErrors,
                                   long appendRetriedConflicts,
                                   long deserializationMisses,
                                   double appendEventsPerSecond,
                                   double deliveredEventsPerSecond,
                                   double p50LatencyMs,
                                   double p95LatencyMs,
                                   double p99LatencyMs,
                                   double freshnessP50Ms,
                                   double freshnessP95Ms,
                                   double freshnessP99Ms,
                                   double slaUnder500msPct,
                                   double slaUnder1000msPct,
                                   long timeToFirstDeliveryMs,
                                   long producerWindowMs,
                                   long timeToCatchUpMs,
                                   boolean caughtUpWithinTimeout,
                                   long deliveryLagEventsEnd,
                                   double deliveryCompletionPct,
                                   /** Configured target producer rate, events/sec (0 = unthrottled, fractional allowed). Pinned in JSON so a regression that drops the throttle is visible. */
                                   double producerTargetRateHz,
                                   /** Table name the {@link #eventStoreSelectsDuringMeasurement} counter was filtered to. */
                                   String eventStoreTable,
                                   /** Total SELECTs against {@link #eventStoreTable} during the measurement window — the proxy for subscription polling load. */
                                   long eventStoreSelectsDuringMeasurement,
                                   /** {@link #eventStoreSelectsDuringMeasurement} / {@link #eventStoreSelectsWindowMs}. The headline DB-load metric S1 reduces. */
                                   double eventStoreSelectsPerSecond,
                                   /** {@link #eventStoreSelectsPerSecond} divided by configured subscriber count — comparable across subscriberCount values. */
                                   double eventStoreSelectsPerSecondPerSubscriber,
                                   /** Window the SELECT counter was attached for (producer phase + catchup). */
                                   long eventStoreSelectsWindowMs,
                                   CdcAvailability.Snapshot cdc) {
    }

    private record DeliveryCatchup(boolean caughtUp, long elapsedMs) {
    }

    private record LabOrderPlaced(String aggregateId,
                                  long sequence,
                                  long appendedAtNanos) {
    }

    /**
     * Jdbi {@link SqlLogger} that counts {@code SELECT} statements referencing a given
     * event-stream table — the proxy for "how often are subscribers polling the event
     * store" and the headline metric the S1 NOTIFY-driven wake-up feature is designed
     * to reduce on quiet systems.
     * <p>
     * Wraps (rather than replaces) the previously-installed {@link SqlLogger} so the
     * framework's {@code SqlExecutionTimeLogger} trace logging keeps working while the
     * counter is attached. {@link #snapshotAndUninstall()} restores the previous logger
     * to keep the side-effect contained to the measurement window.
     * <p>
     * Filter rationale: we want subscription-poll selects only, not appends. So we
     * gate on (a) the rendered SQL contains the table name (case-insensitive) and
     * (b) the statement is a {@code SELECT}. INSERTs and UPDATEs into the same table
     * (writes by producers, framework metadata updates) are excluded.
     */
    static final class EventStoreSelectCounter implements SqlLogger {
        private final Jdbi      jdbi;
        private final SqlLogger previous;
        private final String    tableNameLower;
        private final AtomicLong selectCount = new AtomicLong();

        private EventStoreSelectCounter(Jdbi jdbi, SqlLogger previous, String tableName) {
            this.jdbi = jdbi;
            this.previous = previous;
            this.tableNameLower = tableName.toLowerCase(Locale.ROOT);
        }

        /**
         * Install a fresh counter on {@code jdbi}, wrapping whatever {@link SqlLogger} is
         * currently configured. The returned counter MUST be uninstalled via
         * {@link #snapshotAndUninstall()} to restore the prior logger — leaking the
         * counter would slowly grow a chain of wrappers across runs.
         */
        static EventStoreSelectCounter installOn(Jdbi jdbi, String tableName) {
            var existing = jdbi.getConfig(SqlStatements.class).getSqlLogger();
            var counter  = new EventStoreSelectCounter(jdbi, existing, tableName);
            jdbi.setSqlLogger(counter);
            return counter;
        }

        @Override
        public void logBeforeExecution(StatementContext context) {
            if (previous != null) previous.logBeforeExecution(context);
        }

        @Override
        public void logAfterExecution(StatementContext context) {
            try {
                var sql = context.getRenderedSql();
                if (sql != null) {
                    var lower = sql.toLowerCase(Locale.ROOT);
                    if (lower.contains(tableNameLower)) {
                        // Use indexOf+regionMatches-style check rather than startsWith
                        // because Jdbi sometimes prepends whitespace/comments.
                        var trimmed = lower.stripLeading();
                        if (trimmed.startsWith("select")) {
                            selectCount.incrementAndGet();
                        }
                    }
                }
            } finally {
                if (previous != null) previous.logAfterExecution(context);
            }
        }

        @Override
        public void logException(StatementContext context, SQLException ex) {
            if (previous != null) previous.logException(context, ex);
        }

        /**
         * Snapshot the count and restore the previous {@link SqlLogger} on the Jdbi
         * instance. Safe to call multiple times — subsequent calls return the same
         * count and the restore is a no-op (the previous-logger reference is captured
         * at install time).
         */
        Snapshot snapshotAndUninstall() {
            jdbi.setSqlLogger(previous);
            return new Snapshot(selectCount.get(), tableNameLower);
        }

        record Snapshot(long selectCount, String tableName) {
            static final Snapshot ZERO = new Snapshot(0L, "n/a");
        }
    }
}
