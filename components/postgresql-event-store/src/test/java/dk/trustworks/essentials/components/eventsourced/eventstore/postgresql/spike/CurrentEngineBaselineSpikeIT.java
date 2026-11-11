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

package dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.spike;

import com.zaxxer.hikari.*;
import dk.trustworks.essentials.components.distributed.fencedlock.postgresql.PostgresqlFencedLockManager;
import dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.*;
import dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.eventstream.*;
import dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.gap.PostgresqlEventStreamGapHandler;
import dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.persistence.*;
import dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.persistence.table_per_aggregate_type.*;
import dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.serializer.json.EssentialsJSONEventSerializers;
import dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.subscription.*;
import dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.test_data.*;
import dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.transaction.EventStoreManagedUnitOfWorkFactory;
import dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.types.*;
import dk.trustworks.essentials.components.foundation.fencedlock.*;
import dk.trustworks.essentials.components.foundation.schema.SchemaOwnership;
import dk.trustworks.essentials.components.foundation.types.*;
import org.jdbi.v3.core.Jdbi;
import org.jdbi.v3.postgres.PostgresPlugin;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.testcontainers.postgresql.PostgreSQLContainer;

import java.lang.management.ManagementFactory;
import java.sql.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import java.util.function.BooleanSupplier;

/**
 * Spike P0.0 of {@code docs/plans/event-store-subscription-redesign.md}: a baseline of what the CURRENT engine costs -
 * {@link PostgresqlEventStore} with {@link SeparateTablePerAggregateTypePersistenceStrategy}, durable subscriptions from
 * the {@link EventStoreSubscriptionManager}, {@link PostgresqlEventStreamGapHandler} and the jittered polling optimizer,
 * wired with the defaults of {@code spring-boot-starter-postgresql-event-store} (batch size 10, poll interval 100 ms,
 * backoff +50 ms per empty poll up to 2 000 ms, jitter 0.1, resume points saved every 1 s, notify-polling off,
 * {@code TEXT} ids, {@code JSONB} payloads, Hikari pool of 10).
 * <p>
 * Each run of each shape gets a fresh PostgreSQL container with {@code pg_stat_statements} preloaded, so every shape has
 * a fresh table and fresh statistics. The application connects as separate roles (writer, reader, reader2) so
 * {@code pg_stat_statements} separates the write path from the read path; the observer is the container's own user and
 * is excluded. Results go to {@code target/spike-results/p0-0-baseline.txt}.
 * <p>
 * Throwaway: runs only with {@code -Dspike.run=true}. Shapes can be selected with {@code -Dit.test=CurrentEngineBaselineSpikeIT#idle}.
 */
@Tag("spike")
@EnabledIfSystemProperty(named = "spike.run", matches = "true")
class CurrentEngineBaselineSpikeIT {
    private static final String        RESULTS        = "p0-0-baseline.txt";
    private static final String        IMAGE          = System.getProperty("spike.baseline.image", "postgres:17.5");
    private static final int           RUNS           = Integer.getInteger("spike.baseline.runs", 2);
    private static final double        TIME_SCALE     = Double.parseDouble(System.getProperty("spike.baseline.timeScale", "1.0"));
    private static final AggregateType ORDERS         = AggregateType.of("Orders");
    private static final String        EVENTS_TABLE   = "orders_events";
    private static final String        OBSERVER       = "observer";

    // ------------------------------------------------------------------ shapes

    @Test
    void idle() throws Exception {
        record Config(int subscriptions, int jvms, boolean exclusive) {
            String label() {
                return subscriptions + (exclusive ? " exclusive" : " non-exclusive") + " subscription(s) x " + jvms + " JVM(s)";
            }
        }
        var configs = List.of(new Config(1, 1, false), new Config(10, 1, false), new Config(50, 1, false),
                              new Config(10, 1, true), new Config(10, 2, true));
        var warmUp = scaled(Duration.ofSeconds(45));
        var window = scaled(Duration.ofSeconds(60));
        header("IDLE", "warm-up " + warmUp.toSeconds() + " s after catch-up, window " + window.toSeconds() + " s, 100 seeded events, no writes in the window");
        for (int run = 1; run <= RUNS; run++) {
            for (var config : configs) {
                try (var pg = Pg.start()) {
                    var writer   = Stack.writer(pg, "writer");
                    var received = new AtomicLong();
                    var readers  = new ArrayList<Stack>();
                    for (int jvm = 1; jvm <= config.jvms(); jvm++) {
                        var reader = Stack.reader(pg, jvm == 1 ? "reader" : "reader2", "node" + jvm);
                        readers.add(reader);
                        for (int i = 0; i < config.subscriptions(); i++) {
                            PersistedEventHandler handler = e -> received.incrementAndGet();
                            if (config.exclusive()) {
                                reader.manager.exclusivelySubscribeToAggregateEventsAsynchronously(SubscriberId.of("sub-" + i), ORDERS, GlobalEventOrder.FIRST_GLOBAL_EVENT_ORDER,
                                                                                                    Optional.empty(), new NoOpLockAware(), handler);
                            } else {
                                reader.manager.subscribeToAggregateEventsAsynchronously(SubscriberId.of("sub-" + i), ORDERS, GlobalEventOrder.FIRST_GLOBAL_EVENT_ORDER,
                                                                                         Optional.empty(), handler);
                            }
                        }
                    }
                    for (int i = 0; i < 100; i++) {
                        writer.appendNewOrder(1, Optional.empty());
                    }
                    // exclusive: one JVM holds each lock, so 100 deliveries per subscription in total
                    long expected = 100L * config.subscriptions() * (config.exclusive() ? 1 : config.jvms());
                    await(Duration.ofSeconds(120), () -> received.get() >= expected);
                    Thread.sleep(warmUp.toMillis());
                    pg.resetStatistics();
                    Thread.sleep(window.toMillis());
                    var summary = pg.summarise("idle run " + run + ": " + config.label(), window, 0, 0);
                    record(String.format("IDLE run %d | %-48s | statements/s %8.2f (non-utility %8.2f) | reader %8.2f/s | gap-table rows written %d | WAL %d B",
                                         run, config.label(), summary.perSecond(summary.total), summary.perSecond(summary.nonUtility),
                                         summary.perSecond(summary.byRole("reader") + summary.byRole("reader2")), summary.gapRowsWritten, summary.walBytes));
                    for (var reader : readers) reader.close();
                    writer.close();
                }
            }
        }
    }

    @Test
    void steady() throws Exception {
        var warmUp = scaled(Duration.ofSeconds(10));
        var window = scaled(Duration.ofSeconds(60));
        header("STEADY", "10 non-exclusive subscriptions, one writer appending one OrderAdded per unit of work to a new aggregate (no expected order), warm-up "
                + warmUp.toSeconds() + " s, window " + window.toSeconds() + " s");
        for (int run = 1; run <= RUNS; run++) {
            for (int rate : new int[]{10, 100}) {
                try (var pg = Pg.start()) {
                    var writer    = Stack.writer(pg, "writer");
                    var reader    = Stack.reader(pg, "reader", "node1");
                    var committed = new ConcurrentHashMap<String, Long>();
                    var measuring = new AtomicBoolean(false);
                    var latencies = new ConcurrentLinkedQueue<Long>();
                    var delivered = new AtomicLong();
                    for (int i = 0; i < 10; i++) {
                        reader.manager.subscribeToAggregateEventsAsynchronously(SubscriberId.of("sub-" + i), ORDERS, GlobalEventOrder.FIRST_GLOBAL_EVENT_ORDER, Optional.empty(), e -> {
                            long now = System.nanoTime();
                            var at = committed.get(e.aggregateId().toString());
                            if (at != null) {
                                latencies.add(now - at);
                                delivered.incrementAndGet();
                            }
                        });
                    }
                    var scheduler = Executors.newSingleThreadScheduledExecutor();
                    var appended  = new AtomicLong();
                    scheduler.scheduleAtFixedRate(() -> {
                        var id = writer.appendNewOrder(1, Optional.empty());
                        if (measuring.get()) {
                            committed.put(id.toString(), System.nanoTime());
                            appended.incrementAndGet();
                        }
                    }, 0, 1_000_000 / rate, TimeUnit.MICROSECONDS);
                    Thread.sleep(warmUp.toMillis());
                    pg.resetStatistics();
                    measuring.set(true);
                    Thread.sleep(window.toMillis());
                    measuring.set(false);
                    var summary = pg.summarise("steady run " + run + ": " + rate + " events/s", window, appended.get(), 0);
                    scheduler.shutdown();
                    scheduler.awaitTermination(10, TimeUnit.SECONDS);
                    await(Duration.ofSeconds(30), () -> delivered.get() >= appended.get() * 10);
                    var l = Latencies.of(latencies);
                    long events = appended.get();
                    record(String.format("STEADY run %d | %3d ev/s | appended %d, deliveries %d | latency p50 %.1f ms p99 %.1f ms max %.1f ms | statements per appended event: total %.1f, writer %.1f, reader %.1f | reader statements per delivery %.2f | WAL per event %.0f B",
                                         run, rate, events, delivered.get(), l.p50(), l.p99(), l.max(), (double) summary.total / events,
                                         (double) summary.byRole("writer") / events, (double) summary.byRole("reader") / events, (double) summary.byRole("reader") / (events * 10),
                                         (double) summary.walBytes / events));
                    reader.close();
                    writer.close();
                }
            }
        }
    }

    @Test
    void burst() throws Exception {
        int total = 100_000;
        header("BURST", total + " events (1 000 units of work x 1 aggregate x 100 events, 4 writer threads) appended while one non-exclusive subscription is stopped, then started");
        for (int run = 1; run <= RUNS; run++) {
            try (var pg = Pg.start()) {
                var writer   = Stack.writer(pg, "writer");
                var reader   = Stack.reader(pg, "reader", "node1");
                var seen     = ConcurrentHashMap.<Long>newKeySet();
                var received = new AtomicLong();
                var subscription = reader.manager.subscribeToAggregateEventsAsynchronously(SubscriberId.of("burst"), ORDERS, GlobalEventOrder.FIRST_GLOBAL_EVENT_ORDER, Optional.empty(), e -> {
                    if (seen.add(e.globalEventOrder().longValue())) received.incrementAndGet();
                });
                writer.appendNewOrder(1, Optional.empty());
                await(Duration.ofSeconds(30), () -> received.get() == 1);
                Thread.sleep(2_000); // resume point saved
                subscription.stop();
                var appendStart = System.nanoTime();
                var pool        = Executors.newFixedThreadPool(4);
                var futures     = new ArrayList<Future<?>>();
                for (int i = 0; i < total / 100; i++) futures.add(pool.submit(() -> writer.appendNewOrder(100, Optional.empty())));
                for (var f : futures) f.get();
                pool.shutdown();
                var appendMs = (System.nanoTime() - appendStart) / 1e6;
                System.gc();
                Thread.sleep(500);
                var memory   = ManagementFactory.getMemoryMXBean();
                long baseline = memory.getHeapMemoryUsage().getUsed();
                var peak     = new AtomicLong(baseline);
                var sampling = new AtomicBoolean(true);
                var sampler  = new Thread(() -> {
                    while (sampling.get()) {
                        peak.accumulateAndGet(memory.getHeapMemoryUsage().getUsed(), Math::max);
                        try { Thread.sleep(50); } catch (InterruptedException e) { return; }
                    }
                });
                sampler.start();
                pg.resetStatistics();
                var start = System.nanoTime();
                subscription.start();
                await(Duration.ofMinutes(10), () -> received.get() >= total + 1);
                var catchUp = Duration.ofNanos(System.nanoTime() - start);
                sampling.set(false);
                sampler.join();
                var summary = pg.summarise("burst run " + run, catchUp, 0, total);
                record(String.format("BURST run %d | append of %d events %.1f s | catch-up %.1f s (%.0f events/s) | reader statements %d (%.3f per event, %d BEGIN/COMMIT/ROLLBACK) | heap baseline %d MB, peak %d MB (+%d MB) | WAL during catch-up %d B",
                                     run, total, appendMs / 1000, catchUp.toMillis() / 1000.0, total / (catchUp.toMillis() / 1000.0), summary.byRole("reader"),
                                     (double) summary.byRole("reader") / total, summary.transactionControl, baseline >> 20, peak.get() >> 20, (peak.get() - baseline) >> 20, summary.walBytes));
                reader.close();
                writer.close();
            }
        }
    }

    @Test
    void concurrentWriters() throws Exception {
        var writeFor = scaled(Duration.ofSeconds(30));
        var settle   = scaled(Duration.ofSeconds(135));
        header("CONCURRENT WRITERS", "8 writer threads, each at most 50 units of work/s (one OrderAdded to a new aggregate), 10% rolled back after the insert, for "
                + writeFor.toSeconds() + " s; one non-exclusive subscription; then " + settle.toSeconds() + " s for transient gaps to be given up (120 s threshold)");
        for (int run = 1; run <= RUNS; run++) {
            try (var pg = Pg.start()) {
                var writer        = Stack.writer(pg, "writer");
                var reader        = Stack.reader(pg, "reader", "node1");
                var committed     = new ConcurrentHashMap<String, Long>();
                var delivered     = ConcurrentHashMap.<String>newKeySet();
                var lateLatencies = new ConcurrentLinkedQueue<Long>();
                var inOrder       = new ConcurrentLinkedQueue<Long>();
                var maxSeen       = new AtomicLong(-1);
                var duplicates    = new AtomicLong();
                reader.manager.subscribeToAggregateEventsAsynchronously(SubscriberId.of("concurrent"), ORDERS, GlobalEventOrder.FIRST_GLOBAL_EVENT_ORDER, Optional.empty(), e -> {
                    long now = System.nanoTime();
                    var  key = e.aggregateId().toString();
                    if (!delivered.add(key)) {
                        duplicates.incrementAndGet();
                        return;
                    }
                    long g    = e.globalEventOrder().longValue();
                    long prev = maxSeen.getAndAccumulate(g, Math::max);
                    // the commit timestamp is taken after commit returns, so a fast delivery can arrive first
                    var at = committed.get(key);
                    long latency = at == null ? 0 : now - at;
                    if (g < prev) lateLatencies.add(latency);
                    else inOrder.add(latency);
                });
                pg.resetStatistics();
                var stopAt     = System.nanoTime() + writeFor.toNanos();
                var rolledBack = new AtomicLong();
                var pool       = Executors.newFixedThreadPool(8);
                for (int w = 0; w < 8; w++) {
                    pool.submit(() -> {
                        var random = ThreadLocalRandom.current();
                        while (System.nanoTime() < stopAt) {
                            long t0 = System.nanoTime();
                            boolean rollback = random.nextDouble() < 0.10;
                            var id = OrderId.random();
                            try {
                                writer.unitOfWorkFactory.usingUnitOfWork(uow -> {
                                    writer.eventStore.appendToStream(ORDERS, id, Optional.empty(), List.of(new OrderEvent.OrderAdded(id, CustomerId.random(), 1)));
                                    if (rollback) uow.markAsRollbackOnly();
                                });
                                if (rollback) rolledBack.incrementAndGet();
                                else committed.put(id.toString(), System.nanoTime());
                            } catch (Exception e) {
                                if (rollback) rolledBack.incrementAndGet();
                                else e.printStackTrace();
                            }
                            long sleep = 20_000_000 - (System.nanoTime() - t0);
                            if (sleep > 0) LockSupportSleep.nanos(sleep);
                        }
                    });
                }
                pool.shutdown();
                pool.awaitTermination(writeFor.toSeconds() + 30, TimeUnit.SECONDS);
                var allDelivered = await(Duration.ofSeconds(60), () -> delivered.containsAll(committed.keySet()));
                Thread.sleep(settle.toMillis());
                var summary = pg.summarise("concurrent writers run " + run, writeFor.plus(settle), committed.size(), 0);
                var late    = Latencies.of(lateLatencies);
                var normal  = Latencies.of(inOrder);
                long holes  = pg.sequenceLastValue() - pg.rowCount();
                record(String.format("CONCURRENT run %d | committed %d, rolled back %d, holes in global_order %d | all committed delivered: %s (missing %d, duplicates %d) | late deliveries (below a higher one already delivered) %d: p50 %.1f ms p99 %.1f ms max %.1f ms | in-order deliveries p50 %.1f ms p99 %.1f ms | transient gap rows inserted %d, deleted %d; permanent gap rows inserted %d",
                                     run, committed.size(), rolledBack.get(), holes, allDelivered, committed.keySet().stream().filter(k -> !delivered.contains(k)).count(), duplicates.get(),
                                     lateLatencies.size(), late.p50(), late.p99(), late.max(), normal.p50(), normal.p99(),
                                     summary.transientInserted, summary.transientDeleted, summary.permanentInserted));
                reader.close();
                writer.close();
            }
        }
    }

    @Test
    void append() throws Exception {
        record Case(String label, int aggregates, int eventsPerAggregate, boolean expectedOrder, int iterations) {
        }
        var cases = List.of(new Case("1 event, 1 aggregate, no expected order", 1, 1, false, 1000),
                            new Case("1 event, 1 aggregate, expected order given", 1, 1, true, 1000),
                            new Case("10 events, 1 aggregate", 1, 10, false, 1000),
                            new Case("100 events, 1 aggregate", 1, 100, false, 300),
                            new Case("5 aggregates x 10 events per unit of work", 5, 10, false, 500));
        header("APPEND", "single thread, one unit of work per iteration, new aggregates, 50 warm-up iterations per case; round trips counted in a separate 100-iteration pass through a counting TCP proxy");
        for (int run = 1; run <= RUNS; run++) {
            try (var pg = Pg.start(); var proxy = new RoundTripCountingProxy(pg.server.host(), pg.server.port())) {
                var writer  = Stack.writer(pg, "writer");
                var proxied = Stack.writer(pg, "writer", pg.server.viaProxy(proxy.port()));
                record("APPEND run " + run + " | pgjdbc " + org.postgresql.Driver.getVersion() + ", reWriteBatchedInserts=" + pg.driverProperty("reWriteBatchedInserts")
                               + ", prepareThreshold=" + pg.driverProperty("prepareThreshold"));
                for (var c : cases) {
                    for (int i = 0; i < 50; i++) writer.appendInOneUnitOfWork(c.aggregates(), c.eventsPerAggregate(), c.expectedOrder());
                    pg.resetStatistics();
                    var latencies = new ArrayList<Long>();
                    for (int i = 0; i < c.iterations(); i++) {
                        long t0 = System.nanoTime();
                        writer.appendInOneUnitOfWork(c.aggregates(), c.eventsPerAggregate(), c.expectedOrder());
                        latencies.add(System.nanoTime() - t0);
                    }
                    long events  = (long) c.iterations() * c.aggregates() * c.eventsPerAggregate();
                    var summary  = pg.summarise("append run " + run + ": " + c.label(), Duration.ofSeconds(1), events, 0);
                    for (int i = 0; i < 20; i++) proxied.appendInOneUnitOfWork(c.aggregates(), c.eventsPerAggregate(), c.expectedOrder());
                    proxy.reset();
                    for (int i = 0; i < 100; i++) proxied.appendInOneUnitOfWork(c.aggregates(), c.eventsPerAggregate(), c.expectedOrder());
                    double roundTrips = proxy.flights() / 100.0;
                    var l = Latencies.of(latencies);
                    record(String.format("APPEND run %d | %-44s | p50 %.2f ms p99 %.2f ms | statements per unit of work %.2f (non-utility %.2f) | round trips per unit of work %.2f | WAL per event %.0f B",
                                         run, c.label(), l.p50(), l.p99(), (double) summary.total / c.iterations(), (double) summary.nonUtility / c.iterations(), roundTrips,
                                         (double) summary.walBytes / events));
                }
                occContended(pg, writer, run);
                proxied.close();
                writer.close();
            }
        }
    }

    /** 8 writers on 10 aggregates: read the last event order, append one event after it, in one unit of work. */
    private void occContended(Pg pg, Stack writer, int run) throws Exception {
        var duration   = scaled(Duration.ofSeconds(20));
        var aggregates = new ArrayList<OrderId>();
        for (int i = 0; i < 10; i++) aggregates.add(writer.appendNewOrder(1, Optional.empty()));
        long seqBefore = pg.sequenceLastValue();
        long rowsBefore = pg.rowCount();
        pg.resetStatistics();
        var attempts  = new AtomicLong();
        var conflicts = new AtomicLong();
        var otherErr  = new AtomicLong();
        var stopAt    = System.nanoTime() + duration.toNanos();
        var pool      = Executors.newFixedThreadPool(8);
        for (int w = 0; w < 8; w++) {
            pool.submit(() -> {
                var random = ThreadLocalRandom.current();
                while (System.nanoTime() < stopAt) {
                    var id = aggregates.get(random.nextInt(aggregates.size()));
                    attempts.incrementAndGet();
                    try {
                        writer.unitOfWorkFactory.usingUnitOfWork(uow -> {
                            long last = writer.eventStore.loadLastPersistedEventRelatedTo(ORDERS, id).orElseThrow().eventOrder().longValue();
                            writer.eventStore.appendToStream(ORDERS, id, Optional.of(last), List.of(new OrderEvent.ProductAddedToOrder(id, ProductId.random(), 1)));
                        });
                    } catch (Exception e) {
                        if (hasCause(e, OptimisticAppendToStreamException.class)) conflicts.incrementAndGet();
                        else otherErr.incrementAndGet();
                    }
                }
            });
        }
        pool.shutdown();
        pool.awaitTermination(duration.toSeconds() + 30, TimeUnit.SECONDS);
        long successes = pg.rowCount() - rowsBefore;
        long burned    = pg.sequenceLastValue() - seqBefore - successes;
        var  summary   = pg.summarise("append run " + run + ": OCC contended", duration, successes, 0);
        record(String.format("APPEND run %d | OCC contended: 8 writers, 10 aggregates, %d s | attempts %d, appended %d, OCC conflicts %d, other errors %d | identity values burned %d = %.0f per 1 000 attempts, %.0f per 1 000 successful appends | statements per attempt %.2f",
                             run, duration.toSeconds(), attempts.get(), successes, conflicts.get(), otherErr.get(), burned, burned * 1000.0 / attempts.get(),
                             burned * 1000.0 / successes, (double) summary.total / attempts.get()));
    }

    // ------------------------------------------------------------------ support

    private static boolean hasCause(Throwable t, Class<? extends Throwable> type) {
        for (var c = t; c != null; c = c.getCause()) if (type.isInstance(c)) return true;
        return false;
    }

    private static Duration scaled(Duration d) {
        return Duration.ofMillis((long) (d.toMillis() * TIME_SCALE));
    }

    private static boolean await(Duration timeout, BooleanSupplier condition) throws InterruptedException {
        var until = System.nanoTime() + timeout.toNanos();
        while (System.nanoTime() < until) {
            if (condition.getAsBoolean()) return true;
            Thread.sleep(20);
        }
        return condition.getAsBoolean();
    }

    private static void header(String shape, String setup) {
        record("");
        record("=== " + shape + " | " + Instant.now() + " | " + SpikeServer.target("image " + IMAGE) + " | runs " + RUNS + " | CPUs " + Runtime.getRuntime().availableProcessors()
                       + " | max heap " + (Runtime.getRuntime().maxMemory() >> 20) + " MB | " + setup);
    }

    private static void record(String line) {
        SpikePostgres.record(RESULTS, line);
    }

    private static final class LockSupportSleep {
        static void nanos(long nanos) {
            java.util.concurrent.locks.LockSupport.parkNanos(nanos);
        }
    }

    record Latencies(double p50, double p99, double max) {
        static Latencies of(Collection<Long> nanos) {
            if (nanos.isEmpty()) return new Latencies(Double.NaN, Double.NaN, Double.NaN);
            var sorted = nanos.stream().mapToLong(Long::longValue).sorted().toArray();
            return new Latencies(sorted[(int) Math.floor(0.50 * (sorted.length - 1))] / 1e6,
                                 sorted[(int) Math.floor(0.99 * (sorted.length - 1))] / 1e6,
                                 sorted[sorted.length - 1] / 1e6);
        }
    }

    private static final class NoOpLockAware implements FencedLockAwareSubscriber {
        @Override
        public void onLockAcquired(FencedLock fencedLock, SubscriptionResumePoint resumeFromAndIncluding) {
        }

        @Override
        public void onLockReleased(FencedLock fencedLock) {
        }
    }

    /** What one measurement window cost, from pg_stat_statements and the WAL position. */
    record Summary(Duration window, long total, long nonUtility, long transactionControl, Map<String, Long> callsByRole, long walBytes,
                   long transientInserted, long transientDeleted, long permanentInserted, long gapRowsWritten) {
        double perSecond(long n) {
            return n / (window.toMillis() / 1000.0);
        }

        long byRole(String role) {
            return callsByRole.getOrDefault(role, 0L);
        }
    }

    /** One container, the observer connection and the measurement helpers. */
    static final class Pg implements AutoCloseable {
        /** The container or the external server ({@code -Dspike.jdbcUrl}); see {@link SpikeServer}. */
        final SpikeServer server;
        final Connection  observer;
        String            lsnAtReset;

        private Pg(SpikeServer server) throws SQLException {
            this.server = server;
            this.observer = DriverManager.getConnection(server.getJdbcUrl(), server.getUsername(), server.getPassword());
        }

        String jdbcUrl() {
            return server.getJdbcUrl();
        }

        /** Restricts a pg_stat_statements query (alias {@code s}) to this run's database on an external server; nothing in a container. */
        String thisDatabase() {
            return server.container != null ? "" : " AND s.dbid = (SELECT oid FROM pg_database WHERE datname = current_database())";
        }

        static Pg start() throws SQLException {
            if (SpikeServer.external()) {
                var pg = new Pg(SpikeServer.external("baseline", OBSERVER, true));
                try (var s = pg.observer.createStatement()) {
                    s.execute("CREATE EXTENSION IF NOT EXISTS pg_stat_statements");
                }
                for (var role : List.of("writer", "reader", "reader2")) pg.server.createRole(pg.observer, role);
                return pg;
            }
            var container = new PostgreSQLContainer(IMAGE).withDatabaseName("baseline").withUsername(OBSERVER).withPassword(OBSERVER)
                                                          .withCommand("postgres", "-c", "fsync=off", "-c", "shared_preload_libraries=pg_stat_statements",
                                                                       "-c", "pg_stat_statements.max=10000", "-c", "pg_stat_statements.track_utility=on");
            container.start();
            var pg = new Pg(SpikeServer.of(container));
            try (var s = pg.observer.createStatement()) {
                s.execute("CREATE EXTENSION IF NOT EXISTS pg_stat_statements");
                for (var role : List.of("writer", "reader", "reader2")) s.execute("CREATE ROLE " + role + " LOGIN SUPERUSER PASSWORD '" + role + "'");
            }
            return pg;
        }

        String driverProperty(String name) throws SQLException {
            for (var p : new org.postgresql.Driver().getPropertyInfo(jdbcUrl(), new Properties())) {
                if (p.name.equals(name)) return p.value;
            }
            return "?";
        }

        void resetStatistics() throws SQLException {
            try (var s = observer.createStatement()) {
                s.execute("SELECT pg_stat_statements_reset()");
            }
            lsnAtReset = SpikePostgres.queryString(observer, "SELECT pg_current_wal_lsn()::text");
        }

        long sequenceLastValue() throws SQLException {
            return SpikePostgres.queryLong(observer, "SELECT last_value FROM " + SpikePostgres.queryString(observer, "SELECT pg_get_serial_sequence('" + EVENTS_TABLE + "', 'global_order')"));
        }

        long rowCount() throws SQLException {
            return SpikePostgres.queryLong(observer, "SELECT count(*) FROM " + EVENTS_TABLE);
        }

        /** Reads the statistics since {@link #resetStatistics()}, records the top statements, and returns the totals. */
        Summary summarise(String label, Duration window, long eventsAppended, long eventsRead) throws SQLException {
            long wal = SpikePostgres.queryLong(observer, "SELECT pg_wal_lsn_diff(pg_current_wal_lsn(), '" + lsnAtReset + "'::pg_lsn)::bigint");
            record Row(String role, String query, long calls, long rows, long walBytes, double totalMs) {
            }
            var rows = new ArrayList<Row>();
            try (var s = observer.createStatement();
                 var rs = s.executeQuery("SELECT r.rolname, s.query, s.calls, s.rows, s.wal_bytes::bigint, s.total_exec_time FROM pg_stat_statements s " +
                                                 "JOIN pg_roles r ON r.oid = s.userid WHERE r.rolname <> '" + server.role(OBSERVER) + "'" + thisDatabase() + " ORDER BY s.calls DESC")) {
                while (rs.next()) rows.add(new Row(server.logical(rs.getString(1)), rs.getString(2), rs.getLong(3), rs.getLong(4), rs.getLong(5), rs.getDouble(6)));
            }
            long total = 0, nonUtility = 0, transactionControl = 0, tIns = 0, tDel = 0, pIns = 0;
            var  byRole = new TreeMap<String, Long>();
            for (var r : rows) {
                total += r.calls();
                byRole.merge(r.role(), r.calls(), Long::sum);
                var q = r.query().trim().toLowerCase(Locale.ROOT);
                boolean txControl = q.startsWith("begin") || q.startsWith("commit") || q.startsWith("rollback") || q.startsWith("start transaction");
                boolean utility   = txControl || q.startsWith("set ") || q.startsWith("show ") || q.startsWith("savepoint") || q.startsWith("release") || q.startsWith("reset");
                if (txControl) transactionControl += r.calls();
                if (!utility) nonUtility += r.calls();
                if (q.contains("transient_subscriber_gaps")) {
                    if (q.startsWith("insert") || q.contains("insert into transient_subscriber_gaps")) tIns += r.rows();
                    if (q.startsWith("delete")) tDel += r.rows();
                }
                if (q.contains("insert into permanent_gaps")) pIns += r.rows();
            }
            record("  -- " + label + ": " + total + " statements in " + window.toMillis() + " ms (" + nonUtility + " non-utility, " + transactionControl
                           + " BEGIN/COMMIT/ROLLBACK), by role " + byRole + ", WAL " + wal + " B" + (eventsAppended > 0 ? ", events appended " + eventsAppended : "")
                           + (eventsRead > 0 ? ", events read " + eventsRead : ""));
            rows.stream().limit(12).forEach(r -> record(String.format("     %-8s calls %8d rows %8d wal %10d B exec %9.1f ms | %s", r.role(), r.calls(), r.rows(), r.walBytes(), r.totalMs(),
                                                                       r.query().replaceAll("\\s+", " ").substring(0, Math.min(170, r.query().replaceAll("\\s+", " ").length())))));
            return new Summary(window, total, nonUtility, transactionControl, byRole, wal, tIns, tDel, pIns, tIns + tDel + pIns);
        }

        @Override
        public void close() throws Exception {
            observer.close();
            server.close();
        }
    }

    /** One "application instance": pool, Jdbi, unit of work factory, event store and (for readers) the subscription manager. */
    static final class Stack implements AutoCloseable {
        final HikariDataSource                                                        dataSource;
        final EventStoreManagedUnitOfWorkFactory                                      unitOfWorkFactory;
        final PostgresqlEventStore<SeparateTablePerAggregateEventStreamConfiguration> eventStore;
        EventStoreSubscriptionManager                                                 manager;

        private Stack(String jdbcUrl, String role, String password) {
            this(jdbcUrl, role, password, JSONColumnType.JSONB);
        }

        /** {@code jsonColumnType} is used for the payload and the metadata column of every event table this stack creates. */
        private Stack(String jdbcUrl, String role, String password, JSONColumnType jsonColumnType) {
            var config = new HikariConfig();
            config.setJdbcUrl(jdbcUrl);
            config.setUsername(role);
            config.setPassword(password);
            config.setPoolName(role + "-" + UUID.randomUUID().toString().substring(0, 4));
            dataSource = new HikariDataSource(config);
            var jdbi = Jdbi.create(dataSource);
            jdbi.installPlugin(new PostgresPlugin());
            unitOfWorkFactory = new EventStoreManagedUnitOfWorkFactory(jdbi);
            var persistenceStrategy = SeparateTablePerAggregateTypePersistenceStrategy.builder()
                                                                                      .setJdbi(jdbi)
                                                                                      .setUnitOfWorkFactory(unitOfWorkFactory)
                                                                                      .setEventMapper(new BaselineEventMapper())
                                                                                      .setAggregateEventStreamConfigurationFactory(SeparateTablePerAggregateTypeEventStreamConfigurationFactory.standardSingleTenantConfiguration(
                                                                                              EssentialsJSONEventSerializers.create(), IdentifierColumnType.TEXT, jsonColumnType))
                                                                                      .setSchemaOwnership(SchemaOwnership.COMPONENT)
                                                                                      .build();
            var gapHandler = new PostgresqlEventStreamGapHandler<SeparateTablePerAggregateEventStreamConfiguration>(unitOfWorkFactory, SchemaOwnership.COMPONENT);
            eventStore = PostgresqlEventStore.<SeparateTablePerAggregateEventStreamConfiguration>builder()
                                             .setUnitOfWorkFactory(unitOfWorkFactory)
                                             .setPersistenceStrategy(persistenceStrategy)
                                             .setEventStreamGapHandlerFactory(es -> gapHandler)
                                             .build();
            eventStore.addAggregateEventStreamConfiguration(ORDERS, OrderId.class);
            this.jdbi = jdbi;
        }

        private final Jdbi jdbi;

        static Stack writer(Pg pg, String role) {
            return new Stack(pg.jdbcUrl(), pg.server.role(role), pg.server.password(role));
        }

        static Stack writer(Pg pg, String role, JSONColumnType jsonColumnType) {
            return new Stack(pg.jdbcUrl(), pg.server.role(role), pg.server.password(role), jsonColumnType);
        }

        static Stack writer(Pg pg, String role, String jdbcUrl, JSONColumnType jsonColumnType) {
            return new Stack(jdbcUrl, pg.server.role(role), pg.server.password(role), jsonColumnType);
        }

        static Stack writer(Pg pg, String role, String jdbcUrl) {
            return new Stack(jdbcUrl, pg.server.role(role), pg.server.password(role));
        }

        static Stack reader(Pg pg, String role, String instanceId) {
            var stack = new Stack(pg.jdbcUrl(), pg.server.role(role), pg.server.password(role));
            var lockManager = PostgresqlFencedLockManager.builder()
                                                         .setJdbi(stack.jdbi)
                                                         .setUnitOfWorkFactory(stack.unitOfWorkFactory)
                                                         .setLockManagerInstanceId(instanceId)
                                                         .setLockTimeOut(Duration.ofSeconds(15))
                                                         .setLockConfirmationInterval(Duration.ofSeconds(4))
                                                         .setSchemaOwnership(SchemaOwnership.COMPONENT)
                                                         .build();
            stack.manager = EventStoreSubscriptionManager.builder()
                                                         .setEventStore(stack.eventStore)
                                                         .setFencedLockManager(lockManager)
                                                         .setDurableSubscriptionRepository(new PostgresqlDurableSubscriptionRepository(stack.jdbi, stack.eventStore,
                                                                                                                                       PostgresqlDurableSubscriptionRepository.DEFAULT_DURABLE_SUBSCRIPTIONS_TABLE_NAME,
                                                                                                                                       SchemaOwnership.COMPONENT))
                                                         .setEventStorePollingBatchSize(10)
                                                         .setEventStorePollingInterval(Duration.ofMillis(100))
                                                         .setSnapshotResumePointsEvery(Duration.ofSeconds(1))
                                                         .setSnapshotResumePointsAfterEvents(0)
                                                         .setStartLifeCycles(true)
                                                         .setEventStorePollingOptimizerFactory(name -> new JitteredEventStorePollingOptimizer(name, 100, 50, 2000, 0.1d))
                                                         .build();
            stack.manager.start();
            return stack;
        }

        OrderId appendNewOrder(int events, Optional<Long> expectedOrder) {
            var id = OrderId.random();
            unitOfWorkFactory.usingUnitOfWork(uow -> eventStore.appendToStream(ORDERS, id, expectedOrder, eventsFor(id, events)));
            return id;
        }

        void appendInOneUnitOfWork(int aggregates, int eventsPerAggregate, boolean expectedOrder) {
            unitOfWorkFactory.usingUnitOfWork(uow -> {
                for (int a = 0; a < aggregates; a++) {
                    var id = OrderId.random();
                    eventStore.appendToStream(ORDERS, id, expectedOrder ? Optional.of(EventOrder.NO_EVENTS_PREVIOUSLY_PERSISTED.longValue()) : Optional.empty(),
                                              eventsFor(id, eventsPerAggregate));
                }
            });
        }

        private static List<Object> eventsFor(OrderId id, int count) {
            var events = new ArrayList<Object>(count);
            events.add(new OrderEvent.OrderAdded(id, CustomerId.random(), ThreadLocalRandom.current().nextLong(1_000_000)));
            for (int i = 1; i < count; i++) events.add(new OrderEvent.ProductAddedToOrder(id, ProductId.random(), 2));
            return events;
        }

        @Override
        public void close() {
            if (manager != null) manager.stop();
            dataSource.close();
        }
    }

    /** Same shape as the module's test mappers: two metadata keys, a correlation id and a cause. */
    private static final class BaselineEventMapper implements PersistableEventMapper {
        private final CorrelationId correlationId   = CorrelationId.random();
        private final EventId       causedByEventId = EventId.random();

        @Override
        public PersistableEvent map(Object aggregateId, AggregateEventStreamConfiguration configuration, Object event, EventOrder eventOrder) {
            return PersistableEvent.from(EventId.random(), configuration.aggregateType, aggregateId, EventTypeOrName.with(event.getClass()), event, eventOrder,
                                         EventRevision.of(1), EventMetaData.of("Key1", "Value1", "Key2", "Value2"), OffsetDateTime.now(), causedByEventId, correlationId, null);
        }
    }
}
