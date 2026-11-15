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

package dk.trustworks.essentials.components.eventlog.spike;

import com.zaxxer.hikari.HikariDataSource;
import dk.trustworks.essentials.components.eventlog.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

import java.sql.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import java.util.function.BooleanSupplier;

/**
 * Phase 2, step 3 ("unit C") of {@code docs/plans/event-store-engine-phase2.md}: the measurements behind the phase-2
 * gate (§4 last paragraph, §5 decisions 1 and 3).
 * <ul>
 *     <li>{@link #steady()} and {@link #idle()}: P0.0's steady and idle shapes ({@code CurrentEngineBaselineSpikeIT} in
 *     {@code postgresql-event-store}) on the new engine: one writer appending one event to a new aggregate per unit of
 *     work without an expected order at 10 and 100 events/s, 10 s warm-up, 60 s window; idle: 100 seeded events, no
 *     writes. 1 and 10 {@code FRESH} subscriptions on one aggregate type (phase 2 has one tail reader per
 *     subscription), at the engine's default backstop (1 s) and at P0.0's polling interval (100 ms). Statements from
 *     {@code pg_stat_statements} by role ({@code writer}, {@code reader}), utility statements included, as P0.0. Phase 2's
 *     tail reader holds one pooled connection per subscription for its lifetime, so the reader pool is the subscription
 *     count plus 10 (a pool of 10 with 10 subscriptions starves the progress saves).</li>
 *     <li>{@link #eventIdIndex()}: decision 1, the plain {@code event_id} btree's cost on append - WAL bytes per event
 *     ({@code pg_current_wal_lsn} difference, as P0.7) and recorded execution per append, for 1, 10 and 100 events per
 *     new stream, with and without the index, otherwise the engine's full layout and statement.</li>
 *     <li>{@link #probeBackoff()}: the combined probe's cost and rate at 10, 100 and 400 client connections (P0.12's
 *     levels and {@code max_connections} 50/500/2 000), with one tail reader per 10 connections (P0.12's topology:
 *     a JVM with a pool of 10 and one reader) kept probing by a held writer below a committed event, under the
 *     engine's default settings and with the backstop floor removed (the back-off rule alone, as later wake-up tiers
 *     will see it). Probe CPU is the probe statement's recorded execution per second (one core = 1 000 ms/s).</li>
 * </ul>
 * Results: {@code target/spike-results/p2-c-gate.txt}, {@code p2-c-event-id-index.txt}, {@code p2-c-probe.txt}.
 * Knobs: {@code -Dspike.gate.runs} (2), {@code -Dspike.gate.timeScale} (1.0), {@code -Dspike.gate.subscriptions}
 * ("1,10"), {@code -Dspike.gate.backstops} (ms, "1000,100"), {@code -Dspike.index.runs} (2),
 * {@code -Dspike.probe.levels} ("10,100,400"), {@code -Dspike.probe.windowSeconds} (30), {@code -Dspike.eventlog.image};
 * external server as {@link SpikeDb}.
 * <p>
 * Throwaway: runs only with {@code -Dspike.run=true}.
 */
@Tag("spike")
@EnabledIfSystemProperty(named = "spike.run", matches = "true")
class EventLogGateSpikeIT {
    private static final String        GATE          = "p2-c-gate.txt";
    private static final String        INDEX         = "p2-c-event-id-index.txt";
    private static final String        PROBE         = "p2-c-probe.txt";
    private static final String        TYPE          = "Orders";
    private static final String        EVENT_TYPE    = "dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.test_data.OrderEvent$OrderAdded";
    private static final int           RUNS          = Integer.getInteger("spike.gate.runs", 2);
    private static final double        TIME_SCALE    = Double.parseDouble(System.getProperty("spike.gate.timeScale", "1.0"));
    private static final List<Integer> SUBSCRIPTIONS = ints(System.getProperty("spike.gate.subscriptions", "1,10"));
    private static final List<Integer> BACKSTOPS     = ints(System.getProperty("spike.gate.backstops", "1000,100"));
    private static final int           INDEX_RUNS    = Integer.getInteger("spike.index.runs", 2);
    private static final List<Integer> PROBE_LEVELS  = ints(System.getProperty("spike.probe.levels", "10,100,400"));
    private static final int           PROBE_WINDOW  = Integer.getInteger("spike.probe.windowSeconds", 30);
    private static final UUID          CAUSE         = UUID.randomUUID();
    private static final UUID          CORRELATION   = UUID.randomUUID();

    private static List<Integer> ints(String list) {
        return Arrays.stream(list.split(",")).map(String::trim).filter(s -> !s.isEmpty()).map(Integer::valueOf).toList();
    }

    // ------------------------------------------------------------------ gate: steady

    @Test
    void steady() throws Exception {
        var warmUp = scaled(Duration.ofSeconds(10));
        var window = scaled(Duration.ofSeconds(60));
        header(GATE, "STEADY", "one writer, one event per unit of work to a new aggregate, no expected order; FRESH subscriptions " + SUBSCRIPTIONS + ", backstops "
                + BACKSTOPS + " ms; warm-up " + warmUp.toSeconds() + " s, window " + window.toSeconds() + " s");
        for (int run = 1; run <= RUNS; run++) {
            for (int backstop : BACKSTOPS) {
                for (int subscriptions : SUBSCRIPTIONS) {
                    for (int rate : new int[]{10, 100}) {
                        try (var db = SpikeDb.start("gate", 100); var app = new App(db, settings(backstop), subscriptions + 10)) {
                            var committed = new ConcurrentHashMap<String, Long>();
                            var latencies = new ConcurrentLinkedQueue<Long>();
                            var delivered = new AtomicLong();
                            for (int i = 0; i < subscriptions; i++) {
                                app.eventLog.subscribe("sub-" + i, TYPE, "node1", 1, e -> {
                                    var at = committed.get(e.aggregateId());
                                    if (at != null) {
                                        latencies.add(System.nanoTime() - at);
                                        delivered.incrementAndGet();
                                    }
                                });
                            }
                            var measuring = new AtomicBoolean();
                            var appended  = new AtomicLong();
                            var scheduler = Executors.newSingleThreadScheduledExecutor();
                            scheduler.scheduleAtFixedRate(() -> {
                                try {
                                    var id      = UUID.randomUUID().toString();
                                    var measure = measuring.get();
                                    if (measure) {
                                        // a reader may deliver before the commit returns: known before, timed after
                                        committed.put(id, System.nanoTime());
                                    }
                                    app.appendNewStream(id, 1, false);
                                    if (measure) {
                                        committed.put(id, System.nanoTime());
                                        appended.incrementAndGet();
                                    }
                                } catch (SQLException e) {
                                    throw new IllegalStateException(e);
                                }
                            }, 0, 1_000_000 / rate, TimeUnit.MICROSECONDS);
                            Thread.sleep(warmUp.toMillis());
                            db.resetStatistics();
                            app.reads.reset();
                            measuring.set(true);
                            Thread.sleep(window.toMillis());
                            measuring.set(false);
                            var s = summarise(db, GATE, "steady run " + run + ": backstop " + backstop + " ms, " + subscriptions + " subscription(s), " + rate + " ev/s");
                            var reads = app.reads.snapshot();
                            scheduler.shutdown();
                            scheduler.awaitTermination(10, TimeUnit.SECONDS);
                            long events = appended.get();
                            await(Duration.ofSeconds(30), () -> delivered.get() >= events * subscriptions);
                            var l = Latencies.of(latencies);
                            SpikeDb.record(GATE, String.format("STEADY run %d | backstop %4d ms | %2d sub | %3d ev/s | appended %d, deliveries %d | latency p50 %.1f p99 %.1f max %.1f ms | "
                                                                       + "statements per appended event: writer %.2f, reader %.2f | READER STATEMENTS PER DELIVERY %.3f (non-utility %.3f) | "
                                                                       + "reads %d (%d combined), reader statements per read %.2f | WAL %.0f B/event",
                                                               run, backstop, subscriptions, rate, events, delivered.get(), l.p50(), l.p99(), l.max(),
                                                               s.calls("writer") / (double) events, s.calls("reader") / (double) events,
                                                               s.calls("reader") / (double) (events * subscriptions), s.nonUtility("reader") / (double) (events * subscriptions),
                                                               reads.total(), reads.combined(), s.calls("reader") / (double) Math.max(1, reads.total()), s.wal() / (double) events));
                        }
                    }
                }
            }
        }
    }

    // ------------------------------------------------------------------ gate: idle

    @Test
    void idle() throws Exception {
        var warmUp = scaled(Duration.ofSeconds(10));
        var window = scaled(Duration.ofSeconds(60));
        header(GATE, "IDLE", "100 seeded events, no writes; FRESH subscriptions " + SUBSCRIPTIONS + ", backstops " + BACKSTOPS + " ms; warm-up " + warmUp.toSeconds()
                + " s after catch-up, window " + window.toSeconds() + " s");
        for (int run = 1; run <= RUNS; run++) {
            for (int backstop : BACKSTOPS) {
                for (int subscriptions : SUBSCRIPTIONS) {
                    try (var db = SpikeDb.start("gate", 100); var app = new App(db, settings(backstop), subscriptions + 10)) {
                        var received = new AtomicLong();
                        for (int i = 0; i < subscriptions; i++) {
                            app.eventLog.subscribe("sub-" + i, TYPE, "node1", 1, e -> received.incrementAndGet());
                        }
                        for (int i = 0; i < 100; i++) app.appendNewStream(1, false);
                        await(Duration.ofSeconds(60), () -> received.get() >= 100L * subscriptions);
                        Thread.sleep(warmUp.toMillis());
                        db.resetStatistics();
                        app.reads.reset();
                        Thread.sleep(window.toMillis());
                        var s     = summarise(db, GATE, "idle run " + run + ": backstop " + backstop + " ms, " + subscriptions + " subscription(s)");
                        var reads = app.reads.snapshot();
                        double seconds = window.toMillis() / 1000.0;
                        SpikeDb.record(GATE, String.format("IDLE run %d | backstop %4d ms | %2d sub | READER STATEMENTS/S %.2f (non-utility %.2f, per subscription %.2f) | polls/s %.2f "
                                                                   + "(%d combined) | STATEMENTS PER IDLE POLL %.2f | WAL %d B",
                                                           run, backstop, subscriptions, s.calls("reader") / seconds, s.nonUtility("reader") / seconds,
                                                           s.calls("reader") / seconds / subscriptions, reads.total() / seconds, reads.combined(),
                                                           s.calls("reader") / (double) Math.max(1, reads.total()), s.wal()));
                    }
                }
            }
        }
    }

    // ------------------------------------------------------------------ decision 1: event_id index

    @Test
    void eventIdIndex() throws Exception {
        header(INDEX, "EVENT_ID INDEX", "new stream per unit of work (expected order -1), one append statement + commit per unit of work, single thread; 50 warm-up "
                + "appends, then 1 000 (300 for 100 events); arms in palindrome order WITH, WITHOUT, WITHOUT, WITH; fresh tables and a CHECKPOINT per window");
        try (var db = SpikeDb.start("eventid", 100)) {
            for (int run = 1; run <= INDEX_RUNS; run++) {
                for (int events : new int[]{1, 10, 100}) {
                    int round = 0;
                    for (boolean withIndex : new boolean[]{true, false, false, true}) {
                        round++;
                        indexWindow(db, run, round, events, withIndex);
                    }
                }
            }
        }
    }

    private void indexWindow(SpikeDb db, int run, int round, int events, boolean withIndex) throws Exception {
        try (var s = db.observer.createStatement()) {
            s.execute("DROP SCHEMA public CASCADE");
            s.execute("CREATE SCHEMA public");
            s.execute("GRANT ALL ON SCHEMA public TO public");
            s.execute("CREATE EXTENSION IF NOT EXISTS pg_stat_statements");
        }
        try (var app = new App(db, EventLogSettings.defaults())) {
            if (!withIndex) {
                try (var s = db.observer.createStatement()) {
                    s.execute("DROP INDEX " + app.tables.eventIdIndex());
                }
            }
            long indexes = SpikeDb.queryLong(db.observer, "SELECT count(*) FROM pg_indexes WHERE tablename = '" + app.tables.eventTable() + "'");
            int iterations = events == 100 ? 300 : 1000;
            try (var c = db.connect("writer")) {
                c.setAutoCommit(false);
                for (int i = 0; i < 50; i++) {
                    app.append(c, UUID.randomUUID().toString(), events, true);
                    c.commit();
                }
                db.checkpoint();
                db.resetStatistics();
                var latencies = new long[iterations];
                for (int i = 0; i < iterations; i++) {
                    long t0 = System.nanoTime();
                    app.append(c, UUID.randomUUID().toString(), events, true);
                    c.commit();
                    latencies[i] = System.nanoTime() - t0;
                }
                long   wal       = db.walSinceReset();
                var    appends   = db.statements().stream().filter(st -> st.role().equals("writer") && st.query().contains("INSERT INTO " + app.tables.eventTable())).toList();
                long   calls     = appends.stream().mapToLong(SpikeDb.Statement::calls).sum();
                double execMs    = appends.stream().mapToDouble(SpikeDb.Statement::totalExecMs).sum();
                long   stmtWal   = appends.stream().mapToLong(SpikeDb.Statement::walBytes).sum();
                long   rows      = SpikeDb.queryLong(db.observer, "SELECT count(*) FROM " + app.tables.eventTable());
                long   total     = SpikeDb.queryLong(db.observer, "SELECT pg_total_relation_size('" + app.tables.eventTable() + "')");
                Arrays.sort(latencies);
                SpikeDb.record(INDEX, String.format("EVENT_ID run %d round %d | %-13s | %3d events | WAL %.1f B/event (append statement %.1f B/event) | recorded execution %.1f µs/append "
                                                            + "(%d calls) | client p50 %.3f ms p99 %.3f ms | event table + indexes %.0f B/row | indexes on table %d",
                                                    run, round, withIndex ? "WITH index" : "WITHOUT index", events, wal / (double) (iterations * events),
                                                    stmtWal / (double) (iterations * events), execMs * 1000 / Math.max(1, calls), calls, latencies[iterations / 2] / 1e6,
                                                    latencies[iterations * 99 / 100] / 1e6, total / (double) rows, indexes));
            }
        }
    }

    // ------------------------------------------------------------------ probe back-off

    @Test
    void probeBackoff() throws Exception {
        header(PROBE, "PROBE BACK-OFF", "client connections " + PROBE_LEVELS + " (filler connections idle), one tail reader per 10 connections, all kept probing by a writer "
                + "held below a committed event; window " + PROBE_WINDOW + " s per arm; arms: defaults (backstop 1 s, back-off 1-10 s) and rule-only (backstop and "
                + "probeBackoffMin 1 ms, so wait = the back-off rule, capped at 10 s)");
        for (int level : PROBE_LEVELS) {
            int maxConnections = level <= 10 ? 50 : level <= 100 ? 500 : 2000;
            int readers        = Math.max(1, level / 10);
            for (var arm : List.of("defaults", "rule-only")) {
                var settings = arm.equals("defaults") ? EventLogSettings.defaults()
                                                      : EventLogSettings.defaults().withBackstopInterval(Duration.ofMillis(1)).withProbeBackoff(Duration.ofMillis(1), Duration.ofSeconds(10));
                try (var db = SpikeDb.start("probe", maxConnections); var app = new App(db, settings, readers + 2)) {
                    var fillers = new ArrayList<Connection>();
                    try (var held = db.connect("writer")) {
                        app.appendNewStream(1, false);
                        // the held writer takes a global_order and stays open; a later committed event leaves a hole below it
                        held.setAutoCommit(false);
                        app.append(held, UUID.randomUUID().toString(), 1, true);
                        app.appendNewStream(1, false);
                        for (int i = 0; i < readers; i++) {
                            app.eventLog.subscribe("reader-" + i, TYPE, "node1", 1, e -> { });
                        }
                        while (db.clientBackends() < level) {
                            fillers.add(db.connect("reader"));
                        }
                        long backends = db.clientBackends();
                        Thread.sleep(5_000);
                        db.resetStatistics();
                        app.reads.reset();
                        long cpu0 = db.cpuMicros();
                        long t0   = System.nanoTime();
                        Thread.sleep(PROBE_WINDOW * 1000L);
                        double seconds = (System.nanoTime() - t0) / 1e9;
                        long   cpu1    = db.cpuMicros();
                        var    reads   = app.reads.snapshot();
                        summarise(db, PROBE, "probe " + level + " connections, " + arm);
                        var probes = db.statements().stream().filter(st -> st.role().equals("reader") && st.query().contains("pg_stat_get_activity")).toList();
                        long   calls   = probes.stream().mapToLong(SpikeDb.Statement::calls).sum();
                        double execMs  = probes.stream().mapToDouble(SpikeDb.Statement::totalExecMs).sum();
                        double perProbeUs = execMs * 1000 / Math.max(1, calls);
                        double clientMs   = reads.combinedNanos() / 1e6 / Math.max(1, reads.combined());
                        double cpuMsPerS  = cpu0 < 0 || cpu1 < 0 ? Double.NaN : (cpu1 - cpu0) / 1000.0 / seconds;
                        SpikeDb.record(PROBE, String.format("PROBE %3d connections (%d client backends, max_connections %d) | %-9s | %2d readers | probe recorded execution %.1f µs, client %.3f ms "
                                                                    + "| probes/s per reader %.3f (wait %.0f ms) | PROBE CPU %.2f ms/s = %.3f %% of one core (cluster), %.4f %% per reader | "
                                                                    + "server CPU in window %.1f ms/s | combined reads %d, plain %d",
                                                            level, backends, maxConnections, arm, readers, perProbeUs, clientMs, calls / seconds / readers,
                                                            calls == 0 ? Double.NaN : seconds * readers * 1000 / calls, execMs / seconds, execMs / seconds / 10, execMs / seconds / 10 / readers,
                                                            cpuMsPerS, reads.combined(), reads.total() - reads.combined()));
                        held.rollback();
                    } finally {
                        for (var c : fillers) c.close();
                    }
                }
            }
        }
    }

    // ------------------------------------------------------------------ harness

    private static EventLogSettings settings(int backstopMillis) {
        var backstop = Duration.ofMillis(backstopMillis);
        // the back-off floor follows the backstop, as withBackstopInterval does when it is raised
        return EventLogSettings.defaults().withProbeBackoff(backstop, Duration.ofSeconds(10)).withBackstopInterval(backstop);
    }

    /** One application instance: a writer pool and an event log on a reader pool, as separate roles */
    private static final class App implements AutoCloseable {
        final HikariDataSource    writerPool;
        final HikariDataSource    readerPool;
        final EventLog            eventLog;
        final AggregateTypeTables tables;
        final ReadCounter         reads = new ReadCounter();

        App(SpikeDb db, EventLogSettings settings) throws SQLException {
            this(db, settings, 10);
        }

        App(SpikeDb db, EventLogSettings settings, int poolSize) throws SQLException {
            writerPool = db.pool("writer", poolSize);
            readerPool = db.pool("reader", poolSize);
            eventLog = EventLog.builder().setDataSource(readerPool).setSettings(settings).setObserver(reads).build();
            eventLog.start();
            tables = eventLog.registerAggregateType(TYPE);
            eventLog.eventTypes().idOf(EVENT_TYPE);
        }

        String appendNewStream(int events, boolean expectedOrder) throws SQLException {
            return appendNewStream(UUID.randomUUID().toString(), events, expectedOrder);
        }

        String appendNewStream(String id, int events, boolean expectedOrder) throws SQLException {
            try (var c = writerPool.getConnection()) {
                c.setAutoCommit(false);
                append(c, id, events, expectedOrder);
                c.commit();
            }
            return id;
        }

        void append(Connection connection, String id, int count, boolean expectedOrder) throws SQLException {
            var events = new ArrayList<EventToAppend>(count);
            for (int i = 0; i < count; i++) {
                events.add(new EventToAppend(UUID.randomUUID(), CAUSE, CORRELATION, EVENT_TYPE, 1, Instant.now(),
                                             "{\"orderId\":\"" + id + "\",\"orderingCustomerId\":\"" + UUID.randomUUID() + "\",\"orderNumber\":" + ThreadLocalRandom.current().nextLong(1_000_000) + "}",
                                             "{\"Key1\":\"Value1\",\"Key2\":\"Value2\"}"));
            }
            eventLog.append(connection, expectedOrder ? AppendRequest.afterEventOrder(TYPE, id, AppendRequest.NEW_STREAM, events)
                                                      : AppendRequest.withoutExpectedOrder(TYPE, id, events));
        }

        @Override
        public void close() {
            eventLog.stop();
            readerPool.close();
            writerPool.close();
        }
    }

    record Reads(long total, long combined, long combinedNanos) {
    }

    private static final class ReadCounter implements EventLogObserver {
        private final AtomicLong total         = new AtomicLong();
        private final AtomicLong combined      = new AtomicLong();
        private final AtomicLong combinedNanos = new AtomicLong();

        @Override
        public void onRead(String aggregateType, boolean isCombined, long nanos, int rows, int outstandingHoles, int outstandingCandidates, Duration oldestCandidateAge) {
            total.incrementAndGet();
            if (isCombined) {
                combined.incrementAndGet();
                combinedNanos.addAndGet(nanos);
            }
        }

        void reset() {
            total.set(0);
            combined.set(0);
            combinedNanos.set(0);
        }

        Reads snapshot() {
            return new Reads(total.get(), combined.get(), combinedNanos.get());
        }
    }

    record Summary(Map<String, Long> calls, Map<String, Long> nonUtility, long wal) {
        long calls(String role) {
            return calls.getOrDefault(role, 0L);
        }

        long nonUtility(String role) {
            return nonUtility.getOrDefault(role, 0L);
        }
    }

    private static Summary summarise(SpikeDb db, String file, String label) throws SQLException {
        long wal        = db.walSinceReset();
        var  statements = db.statements();
        var  calls      = new TreeMap<String, Long>();
        var  nonUtility = new TreeMap<String, Long>();
        for (var s : statements) {
            calls.merge(s.role(), s.calls(), Long::sum);
            if (!s.utility()) nonUtility.merge(s.role(), s.calls(), Long::sum);
        }
        SpikeDb.record(file, "  -- " + label + ": statements by role " + calls + " (non-utility " + nonUtility + "), WAL " + wal + " B");
        statements.stream().limit(8).forEach(s -> {
            var q = s.query().replaceAll("\\s+", " ");
            SpikeDb.record(file, String.format("     %-6s calls %7d rows %8d exec %9.1f ms | %s", s.role(), s.calls(), s.rows(), s.totalExecMs(), q.substring(0, Math.min(150, q.length()))));
        });
        return new Summary(calls, nonUtility, wal);
    }

    record Latencies(double p50, double p99, double max) {
        static Latencies of(Collection<Long> nanos) {
            if (nanos.isEmpty()) return new Latencies(Double.NaN, Double.NaN, Double.NaN);
            var sorted = nanos.stream().mapToLong(Long::longValue).sorted().toArray();
            return new Latencies(sorted[(int) Math.floor(0.50 * (sorted.length - 1))] / 1e6, sorted[(int) Math.floor(0.99 * (sorted.length - 1))] / 1e6,
                                 sorted[sorted.length - 1] / 1e6);
        }
    }

    private static void header(String file, String shape, String setup) throws SQLException {
        SpikeDb.record(file, "");
        SpikeDb.record(file, "=== " + shape + " | " + Instant.now() + " | " + (SpikeDb.external() ? "external server" : "container " + SpikeDb.IMAGE + " (fsync=off)")
                + " | CPUs " + Runtime.getRuntime().availableProcessors() + " | " + setup);
    }

    private static Duration scaled(Duration duration) {
        return Duration.ofMillis(Math.round(duration.toMillis() * TIME_SCALE));
    }

    private static void await(Duration timeout, BooleanSupplier condition) throws InterruptedException {
        long deadline = System.nanoTime() + timeout.toNanos();
        while (!condition.getAsBoolean()) {
            if (System.nanoTime() > deadline) throw new IllegalStateException("condition not met within " + timeout);
            Thread.sleep(20);
        }
    }
}
