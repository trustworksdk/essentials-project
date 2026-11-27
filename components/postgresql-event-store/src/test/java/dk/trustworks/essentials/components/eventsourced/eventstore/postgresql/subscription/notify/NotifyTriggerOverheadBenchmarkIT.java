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

package dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.subscription.notify;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.eventstream.AggregateType;
import dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.eventstream.EventStreamTableColumnNames;
import dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.eventstream.IdentifierColumnType;
import dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.eventstream.JSONColumnType;
import dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.persistence.AggregateEventStreamConfiguration;
import dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.persistence.EventMetaData;
import dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.persistence.PersistableEvent;
import dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.persistence.PersistableEventMapper;
import dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.persistence.table_per_aggregate_type.SeparateTablePerAggregateTypePersistenceStrategy;
import dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.serializer.AggregateIdSerializer;
import dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.serializer.json.EssentialsJSONEventSerializers;
import dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.test_data.CustomerId;
import dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.test_data.OrderEvent;
import dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.test_data.OrderId;
import dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.transaction.EventStoreManagedUnitOfWorkFactory;
import dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.transaction.EventStoreUnitOfWork;
import dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.transaction.EventStoreUnitOfWorkFactory;
import dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.types.EventOrder;
import dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.types.EventTypeOrName;
import dk.trustworks.essentials.components.foundation.json.EssentialsObjectMappers;
import dk.trustworks.essentials.components.foundation.postgresql.MultiTableChangeListener;
import dk.trustworks.essentials.components.foundation.postgresql.TableChangeNotification;
import dk.trustworks.essentials.components.foundation.test.EssentialsTestContainers;
import dk.trustworks.essentials.components.foundation.types.CorrelationId;
import dk.trustworks.essentials.components.foundation.types.EventId;
import dk.trustworks.essentials.reactive.EventHandler;
import dk.trustworks.essentials.reactive.LocalEventBus;
import dk.trustworks.essentials.types.LongRange;
import org.jdbi.v3.core.Jdbi;
import org.jdbi.v3.postgres.PostgresPlugin;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.LockSupport;
import java.util.stream.IntStream;

import static dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.persistence.table_per_aggregate_type.SeparateTablePerAggregateTypeEventStreamConfigurationFactory.standardSingleTenantConfiguration;

/**
 * Opt-in benchmark for the S1 open questions in {@code docs/plans/subscription-improvements.md}: what the per
 * aggregate type {@code pg_notify} trigger of notify-polling costs an append, and what a burst of NOTIFYs does to the
 * {@link MultiTableChangeListener} that drains them. Benchmark only: it asserts nothing and prints its numbers.
 * <p>
 * <b>Trigger overhead</b> - one event-stream table whose trigger is described with it
 * ({@link SeparateTablePerAggregateTypePersistenceStrategy#enableNotifyTriggers}), switched between the two arms with
 * {@code ALTER TABLE ... DISABLE/ENABLE TRIGGER}, so both arms append to the same table, through the same pool, with a
 * {@link MultiTableChangeListener} LISTENing throughout. Every shape (events per transaction x concurrent appenders) runs
 * the arms alternately, off first, for {@code benchmark.notify.rounds} rounds of {@code benchmark.notify.seconds}
 * each (after an unmeasured warm-up of {@code benchmark.notify.warmup-seconds}). Each append is one unit of work on a new
 * stream; every run starts on a truncated table. The epoch advances of the {@link NotifyEpochSource} are counted per run, after the listener has drained
 * (one per table per listener poll, since {@link EventStreamTableNotificationDuplicationFilter} collapses a poll's notifications).
 * <p>
 * <b>Empty poll cost</b> - what one empty subscription poll ({@code loadEventsByGlobalOrder} past the head, in its own
 * unit of work) costs, the unit the trigger saves. It leaves out the gap handler's queries, so it is a lower bound.
 * <p>
 * <b>NOTIFY burst</b> - {@code benchmark.notify.burst-appenders} appenders commit single-event transactions as fast as they
 * can for {@code benchmark.notify.burst-seconds}, trigger on, while the backlog (committed NOTIFYs not yet delivered to
 * the epoch source) and {@code pg_notification_queue_usage()} are sampled. Run twice: with only the epoch source on the
 * listener's {@link LocalEventBus}, and with an extra async subscriber that takes 1 ms per event, as an application
 * subscriber on the shared bus of the Spring starter ({@code EventStoreEventBus}) would.
 * <p>
 * {@code mvn verify -pl components/postgresql-event-store -am -DskipUTs -Dbenchmark.run=true
 * -Dit.test=NotifyTriggerOverheadBenchmarkIT -Dfailsafe.failIfNoSpecifiedTests=false}
 */
@Testcontainers
@EnabledIfSystemProperty(named = "benchmark.run", matches = "true")
class NotifyTriggerOverheadBenchmarkIT {
    private static final Logger        log             = LoggerFactory.getLogger(NotifyTriggerOverheadBenchmarkIT.class);
    private static final AggregateType ORDERS          = AggregateType.of("Orders");
    private static final AggregateType PROBES          = AggregateType.of("Probes");
    private static final String        TABLE           = "orders_events";
    private static final String        PROBE_TABLE     = "probes_events";
    private static final String        TRIGGER         = "notify_on_" + TABLE + "_changes";
    private static final EventMetaData META_DATA       = EventMetaData.of("k", "v");
    private static final int           ROUNDS          = Integer.getInteger("benchmark.notify.rounds", 3);
    private static final int           SECONDS         = Integer.getInteger("benchmark.notify.seconds", 5);
    private static final int           WARMUP_SECONDS  = Integer.getInteger("benchmark.notify.warmup-seconds", 3);
    private static final int           BURST_APPENDERS = Integer.getInteger("benchmark.notify.burst-appenders", 8);
    private static final int           BURST_SECONDS   = Integer.getInteger("benchmark.notify.burst-seconds", 5);
    private static final int           EMPTY_POLLS     = Integer.getInteger("benchmark.notify.empty-polls", 3_000);
    private static final boolean       BURST_ONLY      = Boolean.getBoolean("benchmark.notify.burst-only");

    @Container
    private static final PostgreSQLContainer postgres = EssentialsTestContainers.postgres("notify-trigger-benchmark");

    private static HikariDataSource                                 dataSource;
    private static Jdbi                                             jdbi;
    private static EventStoreUnitOfWorkFactory<EventStoreUnitOfWork> unitOfWorkFactory;
    private static SeparateTablePerAggregateTypePersistenceStrategy persistenceStrategy;
    private static LocalEventBus                                    notifyBus;
    private static MultiTableChangeListener<TableChangeNotification> changeListener;
    private static NotifyEpochSource                                epochSource;
    private static final StringBuilder                              report = new StringBuilder();

    @BeforeAll
    static void setUp() {
        var config = new HikariConfig();
        config.setJdbcUrl(postgres.getJdbcUrl());
        config.setUsername(postgres.getUsername());
        config.setPassword(postgres.getPassword());
        config.setMaximumPoolSize(BURST_APPENDERS + 4);
        dataSource = new HikariDataSource(config);
        jdbi = Jdbi.create(dataSource);
        jdbi.installPlugin(new PostgresPlugin());
        unitOfWorkFactory = new EventStoreManagedUnitOfWorkFactory(jdbi);

        persistenceStrategy = new SeparateTablePerAggregateTypePersistenceStrategy(
                jdbi,
                unitOfWorkFactory,
                new BenchmarkEventMapper(),
                standardSingleTenantConfiguration(aggregateType -> aggregateType + "_events",
                                                  EventStreamTableColumnNames.defaultColumnNames(),
                                                  EssentialsJSONEventSerializers.create(),
                                                  IdentifierColumnType.UUID,
                                                  JSONColumnType.JSONB));

        notifyBus = LocalEventBus.builder().busName("NotifyTriggerOverheadBenchmarkIT").build();
        // The Spring starter's default polling interval (essentials.multi-table-change-listener.polling-interval)
        changeListener = new MultiTableChangeListener<>(jdbi,
                                                        Duration.ofMillis(50),
                                                        EssentialsObjectMappers.createJSONSerializer(),
                                                        notifyBus,
                                                        true); // The Spring starter's default (essentials.multi-table-change-listener.filter-duplicate-notifications)
        changeListener.start();
        epochSource = new NotifyEpochSource(notifyBus);

        persistenceStrategy.enableNotifyTriggers(EventStreamTableNotificationDuplicationFilter.registerWith(changeListener)::listenTo);
        persistenceStrategy.addAggregateEventStreamConfiguration(ORDERS, AggregateIdSerializer.serializerFor(OrderId.class));
        persistenceStrategy.addAggregateEventStreamConfiguration(PROBES, AggregateIdSerializer.serializerFor(OrderId.class));

        var version = jdbi.withHandle(h -> h.createQuery("SELECT version()").mapTo(String.class).one());
        line("Environment: %d CPUs visible to the JVM, %s, JDK %s", Runtime.getRuntime().availableProcessors(), version, System.getProperty("java.version"));
        line("Settings: rounds=%d, seconds/run=%d, warm-up=%ds, listener polling interval=50ms", ROUNDS, SECONDS, WARMUP_SECONDS);
    }

    @AfterAll
    static void tearDown() throws Exception {
        if (epochSource != null) epochSource.close();
        if (changeListener != null && changeListener.isStarted()) changeListener.stop();
        if (dataSource != null) dataSource.close();
        var out = Path.of("target", "notify-trigger-benchmark.txt");
        Files.createDirectories(out.getParent());
        Files.writeString(out, report.toString());
        log.info("Benchmark report written to {}", out.toAbsolutePath());
    }

    @Test
    void trigger_overhead_empty_poll_cost_and_notify_burst() throws Exception {
        if (BURST_ONLY) {
            setTrigger(true);
            runAppenders(4, 1, WARMUP_SECONDS);
            awaitListenerDrained();
            burst(false);
            burst(true);
            return;
        }
        // Warm-up: JIT, pool, table and index pages, both arms
        truncate();
        setTrigger(false);
        runAppenders(4, 1, WARMUP_SECONDS);
        setTrigger(true);
        runAppenders(4, 1, WARMUP_SECONDS);
        awaitListenerDrained();

        line("");
        line("== Trigger overhead (alternating arms, %d rounds) ==", ROUNDS);
        line("%-6s %-9s %-7s %-5s %12s %12s %10s %10s %12s %12s",
             "events", "appenders", "trigger", "round", "tx/s", "events/s", "p50 ms", "p99 ms", "epoch +", "epoch+/tx");
        for (int batch : new int[]{1, 100}) {
            for (int appenders : new int[]{1, 4}) {
                var offRuns = new ArrayList<RunResult>();
                var onRuns  = new ArrayList<RunResult>();
                for (int round = 1; round <= ROUNDS; round++) {
                    for (boolean triggerOn : new boolean[]{false, true}) {
                        truncate();
                        setTrigger(triggerOn);
                        long epochBefore = epochSource.currentEpoch(TABLE);
                        var  result      = runAppenders(appenders, batch, SECONDS);
                        awaitListenerDrained();
                        long notifies = epochSource.currentEpoch(TABLE) - epochBefore;
                        (triggerOn ? onRuns : offRuns).add(result);
                        line("%-6d %-9d %-7s %-5d %12.0f %12.0f %10.2f %10.2f %12d %12.3f",
                             batch, appenders, triggerOn ? "on" : "off", round, result.txPerSecond(), result.txPerSecond() * batch,
                             result.p50Ms(), result.p99Ms(), notifies, (double) notifies / result.transactions);
                    }
                }
                summary(batch, appenders, offRuns, onRuns);
            }
        }

        truncate();
        runAppenders(1, 100, 2);
        measureEmptyPollCost();

        setTrigger(true);
        burst(false);
        burst(true);
    }

    // ---------------------------------------------------------------------------------------------------------------

    private void summary(int batch, int appenders, List<RunResult> offRuns, List<RunResult> onRuns) {
        double offTx  = median(offRuns.stream().mapToDouble(RunResult::txPerSecond).toArray());
        double onTx   = median(onRuns.stream().mapToDouble(RunResult::txPerSecond).toArray());
        double offP50 = median(offRuns.stream().mapToDouble(RunResult::p50Ms).toArray());
        double onP50  = median(onRuns.stream().mapToDouble(RunResult::p50Ms).toArray());
        double offP99 = median(offRuns.stream().mapToDouble(RunResult::p99Ms).toArray());
        double onP99  = median(onRuns.stream().mapToDouble(RunResult::p99Ms).toArray());
        double offMean = median(offRuns.stream().mapToDouble(RunResult::meanMs).toArray());
        double onMean  = median(onRuns.stream().mapToDouble(RunResult::meanMs).toArray());
        line("SUMMARY events/tx=%d appenders=%d (median of rounds): tx/s off %.0f on %.0f (%+.1f%%); p50 ms off %.3f on %.3f; p99 ms off %.3f on %.3f; mean ms off %.3f on %.3f (%+.3f ms/tx)",
             batch, appenders, offTx, onTx, (onTx - offTx) * 100 / offTx, offP50, onP50, offP99, onP99, offMean, onMean, onMean - offMean);
    }

    private void measureEmptyPollCost() {
        long head = jdbi.withHandle(h -> h.createQuery("SELECT coalesce(max(global_order), 0) FROM " + TABLE).mapTo(Long.class).one());
        var latencies = new long[EMPTY_POLLS];
        for (int i = -500; i < EMPTY_POLLS; i++) {
            long start = System.nanoTime();
            var  uow   = unitOfWorkFactory.getOrCreateNewUnitOfWork();
            long found;
            try (var events = persistenceStrategy.loadEventsByGlobalOrder(uow, ORDERS, LongRange.between(head + 1, head + 100), List.of(), Optional.empty())) {
                found = events.count();
            }
            uow.commit();
            if (i >= 0) latencies[i] = System.nanoTime() - start;
            if (found != 0) throw new IllegalStateException("Expected an empty poll, found " + found);
        }
        Arrays.sort(latencies);
        line("");
        line("== Empty poll cost (one subscription poll past the head, own unit of work, %d polls, table has %d rows) ==", EMPTY_POLLS, head);
        line("mean %.3f ms, p50 %.3f ms, p99 %.3f ms", Arrays.stream(latencies).average().orElse(0) / 1e6,
             latencies[latencies.length / 2] / 1e6, latencies[(int) (latencies.length * 0.99)] / 1e6);
    }

    private void burst(boolean withSlowSubscriber) throws Exception {
        truncate();
        EventHandler slow = event -> LockSupport.parkNanos(1_000_000);
        if (withSlowSubscriber) notifyBus.addAsyncSubscriber(slow);
        try {
            awaitListenerDrained();
            long epochBefore = epochSource.currentEpoch(TABLE);
            var  committed   = new AtomicLong();
            var  running     = new java.util.concurrent.atomic.AtomicBoolean(true);
            var  maxBacklog  = new AtomicLong();
            var  maxQueueUse = new double[1];
            var sampler = Thread.ofPlatform().name("burst-sampler").start(() -> {
                while (running.get()) {
                    long backlog = committed.get() - (epochSource.currentEpoch(TABLE) - epochBefore);
                    maxBacklog.accumulateAndGet(backlog, Math::max);
                    double usage = jdbi.withHandle(h -> h.createQuery("SELECT pg_notification_queue_usage()").mapTo(Double.class).one());
                    maxQueueUse[0] = Math.max(maxQueueUse[0], usage);
                    LockSupport.parkNanos(20_000_000);
                }
            });
            var  result   = runAppenders(BURST_APPENDERS, 1, BURST_SECONDS, committed);
            long burstEnd = System.nanoTime();
            long expected = committed.get();
            // The probe commits after the burst; NOTIFYs reach the listener in commit order, so the probe's wake-up
            // arrives once every notification of the burst has been read: the time a subscription waits for its wake-up
            long probeEpochBefore = epochSource.currentEpoch(PROBE_TABLE);
            var  probeUow         = unitOfWorkFactory.getOrCreateNewUnitOfWork();
            var  probeId          = OrderId.random();
            persistenceStrategy.persist(probeUow, PROBES, probeId, Optional.empty(), List.of(new OrderEvent.OrderAdded(probeId, CustomerId.random(), 0)));
            probeUow.commit();
            while (epochSource.currentEpoch(PROBE_TABLE) == probeEpochBefore && System.nanoTime() - burstEnd < Duration.ofSeconds(120).toNanos()) {
                LockSupport.parkNanos(1_000_000);
            }
            long drainMs   = (System.nanoTime() - burstEnd) / 1_000_000;
            long delivered = epochSource.currentEpoch(TABLE) - epochBefore;
            running.set(false);
            sampler.join();
            line("");
            line("== NOTIFY burst: %d appenders x 1 event/tx for %ds, trigger on, %s ==", BURST_APPENDERS, BURST_SECONDS,
                 withSlowSubscriber ? "plus a 1 ms/event async subscriber on the same bus" : "epoch source the only bus subscriber");
            line("committed tx %d (%.0f tx/s), epoch advances %d, max (committed - epoch advances) %d, probe committed after the burst woke up after %d ms, max pg_notification_queue_usage %.6f",
                 expected, result.txPerSecond(), delivered, maxBacklog.get(), drainMs, maxQueueUse[0]);
        } finally {
            if (withSlowSubscriber) notifyBus.removeAsyncSubscriber(slow);
        }
    }

    private RunResult runAppenders(int appenders, int eventsPerTx, int seconds) throws InterruptedException {
        return runAppenders(appenders, eventsPerTx, seconds, new AtomicLong());
    }

    private RunResult runAppenders(int appenders, int eventsPerTx, int seconds, AtomicLong committed) throws InterruptedException {
        var  start     = new CountDownLatch(1);
        var  latencies = new long[appenders][];
        var  counts    = new int[appenders];
        var  threads   = new ArrayList<Thread>();
        long[] window  = new long[2];
        for (int t = 0; t < appenders; t++) {
            int index = t;
            threads.add(Thread.ofPlatform().name("appender-" + t).start(() -> {
                var local = new long[1 << 16];
                int n     = 0;
                try {
                    start.await();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return;
                }
                long deadline = window[1];
                while (System.nanoTime() < deadline) {
                    var  orderId = OrderId.random();
                    var  events  = IntStream.range(0, eventsPerTx).mapToObj(i -> (Object) new OrderEvent.OrderAdded(orderId, CustomerId.random(), i)).toList();
                    long t0      = System.nanoTime();
                    var  uow     = unitOfWorkFactory.getOrCreateNewUnitOfWork();
                    persistenceStrategy.persist(uow, ORDERS, orderId, Optional.empty(), events);
                    uow.commit();
                    long elapsed = System.nanoTime() - t0;
                    committed.incrementAndGet();
                    if (n == local.length) local = Arrays.copyOf(local, n * 2);
                    local[n++] = elapsed;
                }
                latencies[index] = local;
                counts[index] = n;
            }));
        }
        window[0] = System.nanoTime();
        window[1] = window[0] + Duration.ofSeconds(seconds).toNanos();
        start.countDown();
        for (var thread : threads) thread.join();
        long elapsedNanos = System.nanoTime() - window[0];
        int  total        = Arrays.stream(counts).sum();
        var  all          = new long[total];
        int  offset       = 0;
        for (int t = 0; t < appenders; t++) {
            System.arraycopy(latencies[t], 0, all, offset, counts[t]);
            offset += counts[t];
        }
        Arrays.sort(all);
        return new RunResult(total, elapsedNanos, all);
    }

    /** Every run starts on an empty table (also keeps the container's disk use bounded). */
    private static void truncate() {
        jdbi.useHandle(h -> h.execute("TRUNCATE " + TABLE));
        jdbi.useHandle(h -> h.execute("VACUUM ANALYZE " + TABLE));
    }

    private static void setTrigger(boolean enabled) {
        jdbi.useHandle(h -> h.execute("ALTER TABLE " + TABLE + (enabled ? " ENABLE" : " DISABLE") + " TRIGGER " + TRIGGER));
    }

    /** Waits until the epoch has not moved for 300 ms (the listener polls every 50 ms). */
    private static void awaitListenerDrained() {
        long last        = epochSource.currentEpoch(TABLE);
        long stableSince = System.nanoTime();
        long deadline    = System.nanoTime() + Duration.ofSeconds(60).toNanos();
        while (System.nanoTime() - stableSince < Duration.ofMillis(300).toNanos() && System.nanoTime() < deadline) {
            LockSupport.parkNanos(10_000_000);
            long now = epochSource.currentEpoch(TABLE);
            if (now != last) {
                last = now;
                stableSince = System.nanoTime();
            }
        }
    }

    private static double median(double[] values) {
        var sorted = values.clone();
        Arrays.sort(sorted);
        return sorted[sorted.length / 2];
    }

    private static void line(String format, Object... args) {
        var text = String.format(format, args);
        log.info(text);
        report.append(text).append('\n');
    }

    private record RunResult(long transactions, long elapsedNanos, long[] sortedLatencies) {
        double txPerSecond() {
            return transactions * 1e9 / elapsedNanos;
        }

        double p50Ms() {
            return sortedLatencies.length == 0 ? 0 : sortedLatencies[sortedLatencies.length / 2] / 1e6;
        }

        double p99Ms() {
            return sortedLatencies.length == 0 ? 0 : sortedLatencies[(int) (sortedLatencies.length * 0.99)] / 1e6;
        }

        double meanMs() {
            return Arrays.stream(sortedLatencies).average().orElse(0) / 1e6;
        }
    }

    private static class BenchmarkEventMapper implements PersistableEventMapper {
        private final CorrelationId correlationId   = CorrelationId.random();
        private final EventId       causedByEventId = EventId.random();

        @Override
        public PersistableEvent map(Object aggregateId,
                                    AggregateEventStreamConfiguration aggregateEventStreamConfiguration,
                                    Object event,
                                    EventOrder eventOrder) {
            return PersistableEvent.from(EventId.random(),
                                         aggregateEventStreamConfiguration.aggregateType,
                                         aggregateId,
                                         EventTypeOrName.with(event.getClass()),
                                         event,
                                         eventOrder,
                                         null,
                                         META_DATA,
                                         OffsetDateTime.now(),
                                         causedByEventId,
                                         correlationId,
                                         null);
        }
    }
}
