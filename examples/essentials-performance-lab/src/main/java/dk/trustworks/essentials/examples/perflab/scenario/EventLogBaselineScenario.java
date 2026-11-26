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

import com.zaxxer.hikari.HikariDataSource;
import dk.trustworks.essentials.components.eventlog.*;
import dk.trustworks.essentials.components.eventlog.processor.*;
import dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.eventstream.AggregateType;
import dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.persistence.*;
import dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.serializer.AggregateIdSerializer;
import dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.serializer.json.JSONEventSerializer;
import dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.transaction.EventStoreManagedUnitOfWorkFactory;
import dk.trustworks.essentials.examples.perflab.EssentialsPerformanceLabProperties;
import dk.trustworks.essentials.examples.perflab.harness.StatementCountingDataSource;
import org.jdbi.v3.core.Jdbi;
import org.slf4j.*;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.jdbc.autoconfigure.DataSourceProperties;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import tools.jackson.databind.ObjectMapper;

import java.nio.file.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import java.util.concurrent.locks.LockSupport;

/**
 * The new event store engine's arm of {@link EventLogVsPollingVsCdcComparisonScenario} (design phase 8, unit 8b-E): the
 * same fixed-seed append + subscribe workload as {@link BaselinePollingVsCdcScenario}, and the same metrics JSON, with
 * the appends going through the {@link EventLogEventStore} adapter and the subscribers being
 * {@link EventReactor}s ({@code essentials.lab.event-log.processor=reactor}, default) or {@link ViewProjection}s
 * ({@code =projection}).
 * <p>
 * The engine gets its own Hikari pool ({@code essentials.lab.event-log.pool-size}, default 20) on the application's
 * database, wrapped in a {@link StatementCountingDataSource}, and the adapter a JDBI unit of work over that pool, so every
 * statement of the arm - appends included - is counted. {@code eventStoreSelects*} use the baseline's rule (a
 * {@code SELECT} naming the event table); {@code engineStatements*} count every statement.
 * <p>
 * The handlers record the delivery in memory and write nothing, as the baseline's subscribers do, so the arm measures
 * the engine's delivery path rather than a view's writes. Registered only with {@code essentials.lab.event-log.enabled=true}.
 */
@Component
@ConditionalOnProperty(name = "essentials.lab.event-log.enabled", havingValue = "true")
public class EventLogBaselineScenario implements LabScenario {
    private static final Logger log = LoggerFactory.getLogger(EventLogBaselineScenario.class);

    public static final  String        PROCESSOR_PROPERTY = "essentials.lab.event-log.processor";
    public static final  String        POOL_SIZE_PROPERTY = "essentials.lab.event-log.pool-size";
    private static final AggregateType ORDERS             = AggregateType.of("LabOrders");
    private static final String        EVENT_TABLE        = AggregateTypeTables.of(ORDERS.toString()).eventTable();

    private final DataSourceProperties   dataSourceProperties;
    private final JSONEventSerializer    jsonSerializer;
    private final PersistableEventMapper eventMapper;
    private final ObjectMapper           objectMapper;
    private final Environment            environment;

    public EventLogBaselineScenario(DataSourceProperties dataSourceProperties,
                                    JSONEventSerializer jsonSerializer,
                                    PersistableEventMapper eventMapper,
                                    ObjectMapper objectMapper,
                                    Environment environment) {
        this.dataSourceProperties = dataSourceProperties;
        this.jsonSerializer = jsonSerializer;
        this.eventMapper = eventMapper;
        this.objectMapper = objectMapper;
        this.environment = environment;
    }

    @Override
    public String name() {
        return "event-log-baseline";
    }

    @Override
    public String description() {
        return "The baseline append + subscribe workload on the new event store engine (EventLogEventStore + EventReactor/ViewProjection)";
    }

    @Override
    public void run(EssentialsPerformanceLabProperties properties) throws Exception {
        var processor = environment.getProperty(PROCESSOR_PROPERTY, "reactor").trim().toLowerCase(Locale.ROOT);
        if (!processor.equals("reactor") && !processor.equals("projection")) {
            throw new IllegalArgumentException(PROCESSOR_PROPERTY + " must be 'reactor' or 'projection', not '" + processor + "'");
        }
        int poolSize = environment.getProperty(POOL_SIZE_PROPERTY, Integer.class, 20);
        var runId    = UUID.randomUUID().toString().replace("-", "").substring(0, 8);

        try (var pool = dataSourceProperties.initializeDataSourceBuilder().type(HikariDataSource.class).build()) {
            pool.setMaximumPoolSize(poolSize);
            pool.setPoolName("event-log-lab");
            var dataSource = new StatementCountingDataSource(pool, EVENT_TABLE);
            var eventLog = EventLog.builder().setDataSource(dataSource).setSettings(EventLogSettings.defaults()).build();
            eventLog.start();
            var eventStore = new EventLogEventStore(eventLog, new EventStoreManagedUnitOfWorkFactory(Jdbi.create(dataSource)), jsonSerializer, eventMapper);
            eventStore.addAggregateEventStreamConfiguration(ORDERS, AggregateIdSerializer.serializerFor(String.class));

            var collector  = new Collector();
            var processors = new ArrayList<Runnable>();
            var handles    = new ArrayList<java.util.function.Supplier<Optional<SubscriptionHandle>>>();
            try {
                for (int i = 0; i < properties.getSubscriberCount(); i++) {
                    var subscriber = "lab-" + processor + "-" + i + "-" + runId;
                    if (processor.equals("reactor")) {
                        var reactor = EventReactor.builder(subscriber, ORDERS.toString())
                                                  .on(LabLogOrderPlaced.class, event -> collector.record(event.payloadAs(LabLogOrderPlaced.class)))
                                                  .startFrom(StartFrom.head())
                                                  .build(eventLog, jsonSerializer)
                                                  .start();
                        processors.add(reactor::stop);
                        handles.add(reactor::handle);
                    } else {
                        var projection = ViewProjection.builder(subscriber, ORDERS.toString())
                                                       .on(LabLogOrderPlaced.class, (event, context) -> collector.record(event.payloadAs(LabLogOrderPlaced.class)))
                                                       .startFrom(StartFrom.head())
                                                       .build(eventLog, jsonSerializer)
                                                       .start();
                        processors.add(projection::stop);
                        handles.add(projection::handle);
                    }
                }
                awaitAttached(handles);

                var warmupProduced = runProducerPhase(properties.getWarmup(), properties, properties.getRandomSeed(), eventStore, 0);
                waitForDeliveries(warmupProduced.produced() * properties.getSubscriberCount(), collector, TimeUnit.SECONDS.toMillis(10));

                collector.reset();
                dataSource.startCounting();
                var measurementStartedAtNanos = System.nanoTime();
                var producer = runProducerPhase(properties.getDuration(), properties, properties.getRandomSeed() + 10_000, eventStore, 1);
                var producerStoppedAtNanos = System.nanoTime();
                long expected = producer.produced() * properties.getSubscriberCount();
                var catchupMs = waitForDeliveries(expected, collector, Math.max(TimeUnit.SECONDS.toMillis(10), properties.getDuration().toMillis()));
                dataSource.stopCounting();

                var metrics = collector.metrics("event-log-" + processor, producer, expected, properties, measurementStartedAtNanos, producerStoppedAtNanos, catchupMs,
                                                dataSource, poolSize);
                var json = objectMapper.writeValueAsString(metrics);
                log.info("Event log baseline metrics: {}", json);
                System.out.println("############# [perf-lab] EVENT LOG BASELINE DONE mode=" + metrics.get("mode") + " produced=" + metrics.get("producedEvents")
                                           + " delivered=" + metrics.get("deliveredEvents") + " p95_ms=" + metrics.get("p95LatencyMs"));
                if (StringUtils.hasText(properties.getMetricsOutputFile())) {
                    var target = Paths.get(properties.getMetricsOutputFile()).toAbsolutePath().normalize();
                    if (target.getParent() != null) {
                        Files.createDirectories(target.getParent());
                    }
                    Files.writeString(target, json + System.lineSeparator());
                }
            } finally {
                processors.forEach(Runnable::run);
                eventLog.stop();
            }
        }
    }

    /** Exclusive subscriptions take their lease first; produce only once every one is attached, as the CDC legs wait for ACTIVE */
    private static void awaitAttached(List<java.util.function.Supplier<Optional<SubscriptionHandle>>> handles) throws InterruptedException {
        var deadline = System.nanoTime() + Duration.ofSeconds(30).toNanos();
        while (System.nanoTime() < deadline) {
            if (handles.stream().allMatch(h -> h.get().map(handle -> "ATTACHED".equals(handle.status().phase())).orElse(false))) {
                return;
            }
            Thread.sleep(20);
        }
        log.warn("Not every subscription was ATTACHED within 30 s: {}", handles.stream().map(h -> h.get().map(handle -> handle.status().phase()).orElse("none")).toList());
    }

    private record ProducerOutcome(long produced, long conflictErrors, long infrastructureErrors, long retriedConflicts) {
    }

    /** {@link BaselinePollingVsCdcScenario}'s producer phase: same seeds, aggregate choice, pacing and retry rule */
    private ProducerOutcome runProducerPhase(Duration phaseDuration, EssentialsPerformanceLabProperties properties, long seed, EventLogEventStore eventStore,
                                             int phaseIndex) throws InterruptedException {
        if (phaseDuration.isZero() || phaseDuration.isNegative()) return new ProducerOutcome(0, 0, 0, 0);
        var nextEventNumber      = new AtomicLong();
        var produced             = new AtomicLong();
        var conflicts            = new AtomicLong();
        var infrastructureErrors = new AtomicLong();
        var retried              = new AtomicLong();
        long deadlineNanos = System.nanoTime() + phaseDuration.toNanos();
        double rateHz = Math.max(0.0d, properties.getProducerRateHz());
        long perThreadIntervalNanos = rateHz > 0 ? (long) (1_000_000_000.0d * properties.getProducerThreads() / rateHz) : 0L;

        var executor = Executors.newFixedThreadPool(properties.getProducerThreads(), runnable -> {
            var thread = new Thread(runnable, "lab-event-log-producer-" + phaseIndex);
            thread.setDaemon(true);
            return thread;
        });
        for (int i = 0; i < properties.getProducerThreads(); i++) {
            final int producerIndex = i;
            executor.submit(() -> {
                var random      = new Random(seed + (long) producerIndex * 31L + (long) phaseIndex * 997L);
                var cardinality = Math.max(1, properties.getAggregateCardinality());
                if (producerIndex >= cardinality) return;
                long nextAppendAtNanos = System.nanoTime() + (perThreadIntervalNanos > 0 ? perThreadIntervalNanos * producerIndex / properties.getProducerThreads() : 0L);
                while (System.nanoTime() < deadlineNanos) {
                    if (perThreadIntervalNanos > 0) {
                        long waitNanos = nextAppendAtNanos - System.nanoTime();
                        if (waitNanos > 0) LockSupport.parkNanos(waitNanos);
                        nextAppendAtNanos += perThreadIntervalNanos;
                    }
                    int step       = Math.max(1, properties.getProducerThreads());
                    int partitions = Math.max(1, (cardinality - producerIndex + step - 1) / step);
                    var aggregateId = "order-" + (producerIndex + step * random.nextInt(partitions));
                    var event = new LabLogOrderPlaced(aggregateId, nextEventNumber.incrementAndGet(), System.nanoTime());
                    int maxAttempts = Math.max(1, properties.getAppendMaxAttempts());
                    long backoffMillis = Math.max(0L, properties.getAppendRetryBackoff().toMillis());
                    for (int attempt = 1; attempt <= maxAttempts; attempt++) {
                        try {
                            eventStore.getUnitOfWorkFactory().usingUnitOfWork(unitOfWork -> eventStore.appendToStream(ORDERS, aggregateId, List.of(event)));
                            produced.incrementAndGet();
                            break;
                        } catch (Exception e) {
                            boolean conflict = isOptimisticConflict(e);
                            if (conflict && attempt < maxAttempts) {
                                retried.incrementAndGet();
                                if (backoffMillis > 0) {
                                    try {
                                        Thread.sleep(backoffMillis * attempt);
                                    } catch (InterruptedException interrupted) {
                                        Thread.currentThread().interrupt();
                                        return;
                                    }
                                }
                                continue;
                            }
                            (conflict ? conflicts : infrastructureErrors).incrementAndGet();
                            if (!conflict) log.debug("Append failed", e);
                            break;
                        }
                    }
                }
            });
        }
        executor.shutdown();
        executor.awaitTermination(phaseDuration.toMillis() + TimeUnit.SECONDS.toMillis(5), TimeUnit.MILLISECONDS);
        if (!executor.isTerminated()) executor.shutdownNow();
        if (conflicts.get() + infrastructureErrors.get() > 0) {
            log.warn("Phase {} had append errors (conflicts={}, infrastructure={}, conflictRetries={})", phaseIndex, conflicts.get(), infrastructureErrors.get(), retried.get());
        }
        return new ProducerOutcome(produced.get(), conflicts.get(), infrastructureErrors.get(), retried.get());
    }

    private static boolean isOptimisticConflict(Throwable throwable) {
        for (var current = throwable; current != null; current = current.getCause()) {
            if (current instanceof OptimisticAppendToStreamException) return true;
        }
        return false;
    }

    /** @return the catch-up time in ms, or -1 if the deliveries did not arrive within {@code timeoutMillis} */
    private static long waitForDeliveries(long expected, Collector collector, long timeoutMillis) throws InterruptedException {
        if (expected <= 0) return 0;
        long startedAt = System.currentTimeMillis();
        long deadline  = startedAt + timeoutMillis;
        while (collector.delivered.get() < expected && System.currentTimeMillis() < deadline) {
            Thread.sleep(25L);
        }
        return collector.delivered.get() >= expected ? Math.max(0L, System.currentTimeMillis() - startedAt) : -1L;
    }

    /** The payload; the appended-at timestamp gives the end-to-end latency on delivery */
    public record LabLogOrderPlaced(String aggregateId, long sequence, long appendedAtNanos) {
    }

    private static final class Collector {
        private final AtomicLong delivered            = new AtomicLong();
        private final AtomicLong firstDeliveryAtNanos = new AtomicLong(Long.MAX_VALUE);
        private final List<Long> latenciesNanos       = Collections.synchronizedList(new ArrayList<>());

        void record(LabLogOrderPlaced placed) {
            long now = System.nanoTime();
            delivered.incrementAndGet();
            firstDeliveryAtNanos.accumulateAndGet(now, Math::min);
            latenciesNanos.add(Math.max(0, now - placed.appendedAtNanos()));
        }

        void reset() {
            delivered.set(0);
            firstDeliveryAtNanos.set(Long.MAX_VALUE);
            latenciesNanos.clear();
        }

        /** The baseline's JSON fields, in its order, then the engine's own */
        Map<String, Object> metrics(String mode, ProducerOutcome producer, long expected, EssentialsPerformanceLabProperties properties, long startedAtNanos,
                                    long producerStoppedAtNanos, long catchupMs, StatementCountingDataSource statements, int poolSize) {
            long[] sorted;
            synchronized (latenciesNanos) {
                sorted = latenciesNanos.stream().mapToLong(Long::longValue).toArray();
            }
            Arrays.sort(sorted);
            var runMillis        = Math.max(1L, properties.getDuration().toMillis());
            var deliveredCount   = delivered.get();
            var firstDelivery    = firstDeliveryAtNanos.get();
            var producerWindowMs = Math.max(1L, TimeUnit.NANOSECONDS.toMillis(Math.max(0L, producerStoppedAtNanos - startedAtNanos)));
            var windowMs         = Math.max(1L, producerWindowMs + Math.max(0L, catchupMs));
            var selectsPerSecond = statements.tableSelects() * 1_000.0d / windowMs;
            var subscribers      = Math.max(1, properties.getSubscriberCount());

            var metrics = new LinkedHashMap<String, Object>();
            metrics.put("mode", mode);
            metrics.put("capturedAt", Instant.now().toString());
            metrics.put("producedEvents", producer.produced());
            metrics.put("expectedDeliveries", expected);
            metrics.put("deliveredEvents", deliveredCount);
            metrics.put("appendErrors", producer.conflictErrors() + producer.infrastructureErrors());
            metrics.put("appendConflictErrors", producer.conflictErrors());
            metrics.put("appendInfrastructureErrors", producer.infrastructureErrors());
            metrics.put("appendRetriedConflicts", producer.retriedConflicts());
            metrics.put("deserializationMisses", 0L);
            metrics.put("appendEventsPerSecond", producer.produced() * 1_000.0d / runMillis);
            metrics.put("deliveredEventsPerSecond", deliveredCount * 1_000.0d / runMillis);
            metrics.put("p50LatencyMs", percentileMillis(sorted, 0.50d));
            metrics.put("p95LatencyMs", percentileMillis(sorted, 0.95d));
            metrics.put("p99LatencyMs", percentileMillis(sorted, 0.99d));
            metrics.put("freshnessP50Ms", percentileMillis(sorted, 0.50d));
            metrics.put("freshnessP95Ms", percentileMillis(sorted, 0.95d));
            metrics.put("freshnessP99Ms", percentileMillis(sorted, 0.99d));
            metrics.put("slaUnder500msPct", ratioAtOrBelowMillis(sorted, 500.0d));
            metrics.put("slaUnder1000msPct", ratioAtOrBelowMillis(sorted, 1_000.0d));
            metrics.put("timeToFirstDeliveryMs", firstDelivery == Long.MAX_VALUE ? -1L : Math.max(0L, TimeUnit.NANOSECONDS.toMillis(firstDelivery - startedAtNanos)));
            metrics.put("producerWindowMs", producerWindowMs);
            metrics.put("timeToCatchUpMs", catchupMs);
            metrics.put("caughtUpWithinTimeout", catchupMs >= 0);
            metrics.put("deliveryLagEventsEnd", Math.max(0L, expected - deliveredCount));
            metrics.put("deliveryCompletionPct", expected == 0 ? 100.0d : deliveredCount * 100.0d / expected);
            metrics.put("producerTargetRateHz", properties.getProducerRateHz());
            metrics.put("eventStoreTable", EVENT_TABLE);
            metrics.put("eventStoreSelectsDuringMeasurement", statements.tableSelects());
            metrics.put("eventStoreSelectsPerSecond", selectsPerSecond);
            metrics.put("eventStoreSelectsPerSecondPerSubscriber", selectsPerSecond / subscribers);
            metrics.put("eventStoreSelectsWindowMs", windowMs);
            metrics.put("engineStatementsDuringMeasurement", statements.statements());
            metrics.put("engineStatementsPerSecond", statements.statements() * 1_000.0d / windowMs);
            metrics.put("engineStatementsPerDeliveredEvent", deliveredCount == 0 ? 0.0d : statements.statements() / (double) deliveredCount);
            metrics.put("enginePoolSize", poolSize);
            return metrics;
        }

        private static double percentileMillis(long[] sorted, double percentile) {
            if (sorted.length == 0) return 0.0d;
            int index = Math.max(0, Math.min((int) Math.ceil(percentile * sorted.length) - 1, sorted.length - 1));
            return sorted[index] / 1_000_000.0d;
        }

        private static double ratioAtOrBelowMillis(long[] sorted, double thresholdMs) {
            if (sorted.length == 0) return 0.0d;
            long threshold = (long) (thresholdMs * 1_000_000.0d);
            int accepted = 0;
            while (accepted < sorted.length && sorted[accepted] <= threshold) accepted++;
            return accepted * 100.0d / sorted.length;
        }
    }
}
