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

import javax.sql.DataSource;
import java.io.PrintWriter;
import java.sql.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import java.util.function.BooleanSupplier;
import java.util.logging.Logger;

/**
 * Phase 3, unit C of {@code docs/plans/event-store-engine-phase3.md}: the measurements behind the phase-3 gate (§5.4,
 * predictions §4.3). The reader is one shared loop per {@link EventLog} (one thread, one held connection); a
 * subscription holds no connection and borrows one only for catch-up, bridge and progress statements.
 * <ul>
 *     <li>{@link #idle()}: 100 seeded events, no writes; 1, 10 and 50 {@code FRESH} subscriptions on one aggregate
 *     type. Gate: reader statements/s at 10 and 50 within 10 % of the figure at 1, for each backstop.</li>
 *     <li>{@link #steady()}: P0.0's steady shape ({@code CurrentEngineBaselineSpikeIT} in
 *     {@code postgresql-event-store}): one writer appending one event to a new aggregate per unit of work without an
 *     expected order at 10 and 100 events/s, 10 s warm-up, 60 s window, 10 subscriptions. Gate: reader statements per
 *     delivered event at or below today's engine with 10 subscriptions (phase-2 §6.1: 6.79-6.91 at 10 ev/s, 1.04 at 100
 *     ev/s), plus commit-to-handle latency.</li>
 *     <li>Both run at the default backstop (1 s) and P0.0's polling interval (100 ms), and with two reader pools: phase
 *     2's sizing (subscriptions + 10) and 4. Every {@code getConnection()} on the reader pool is timed; the maximum wait
 *     in the window and over the whole run (start-up and catch-up included) is reported. Gate: no wait above 1 s.</li>
 *     <li>{@link #reattachChurn()}: decision 3's re-attach churn. One {@code FRESH} subscription with a 50 ms handler (20
 *     events/s at most) behind a writer at 100 ev/s for 60 s, with and without a long writer that holds a
 *     {@code global_order} below the head for the whole window, on a reader pool of 4. Records attaches, detaches by
 *     reason, catch-up and bridge rows read, and bridge rows dropped as already handled. Rows of the page being worked
 *     on when the window closes are not counted; with a 50 ms handler that is at most one catch-up page.</li>
 * </ul>
 * Statements come from {@code pg_stat_statements} by role ({@code writer}, {@code reader}), utility statements
 * included, as P0.0; a fresh container per arm.
 * <p>
 * Results: {@code target/spike-results/p3-c-gate.txt}, {@code p3-c-churn.txt}. Knobs: {@code -Dspike.gate.runs} (2),
 * {@code -Dspike.gate.timeScale} (1.0, scales warm-ups and windows, the churn window included),
 * {@code -Dspike.gate.subscriptions} (steady, "10"), {@code -Dspike.gate.idleSubscriptions} ("1,10,50"),
 * {@code -Dspike.gate.backstops} (ms, "1000,100"), {@code -Dspike.gate.readerPools} ("0,4"; 0 = subscriptions + 10),
 * {@code -Dspike.eventlog.image}; external server as {@link SpikeDb}.
 * <p>
 * Phase 2's {@code eventIdIndex} and {@code probeBackoff} measurements (suite {@code p2-gate}, results in phase-2 §6)
 * were retired with this change: the probe topology (one reader per subscription) no longer exists. They are at commit
 * {@code 13184d080}.
 * <p>
 * Throwaway: runs only with {@code -Dspike.run=true}.
 */
@Tag("spike")
@EnabledIfSystemProperty(named = "spike.run", matches = "true")
class EventLogGateSpikeIT {
    private static final String        GATE               = "p3-c-gate.txt";
    private static final String        CHURN              = "p3-c-churn.txt";
    private static final String        TYPE               = "Orders";
    private static final String        EVENT_TYPE         = "dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.test_data.OrderEvent$OrderAdded";
    private static final int           RUNS               = Integer.getInteger("spike.gate.runs", 2);
    private static final double        TIME_SCALE         = Double.parseDouble(System.getProperty("spike.gate.timeScale", "1.0"));
    private static final List<Integer> SUBSCRIPTIONS      = ints(System.getProperty("spike.gate.subscriptions", "10"));
    private static final List<Integer> IDLE_SUBSCRIPTIONS = ints(System.getProperty("spike.gate.idleSubscriptions", "1,10,50"));
    private static final List<Integer> BACKSTOPS          = ints(System.getProperty("spike.gate.backstops", "1000,100"));
    private static final List<Integer> READER_POOLS       = ints(System.getProperty("spike.gate.readerPools", "0,4"));
    private static final int           WRITER_POOL        = 10;
    private static final int           CHURN_POOL         = 4;
    private static final UUID          CAUSE              = UUID.randomUUID();
    private static final UUID          CORRELATION        = UUID.randomUUID();

    private static List<Integer> ints(String list) {
        return Arrays.stream(list.split(",")).map(String::trim).filter(s -> !s.isEmpty()).map(Integer::valueOf).toList();
    }

    private static int readerPool(int configured, int subscriptions) {
        return configured == 0 ? subscriptions + 10 : configured;
    }

    // ------------------------------------------------------------------ gate: steady

    @Test
    void steady() throws Exception {
        var warmUp = scaled(Duration.ofSeconds(10));
        var window = scaled(Duration.ofSeconds(60));
        header(GATE, "STEADY", "one writer, one event per unit of work to a new aggregate, no expected order; FRESH subscriptions " + SUBSCRIPTIONS + ", backstops "
                + BACKSTOPS + " ms, reader pools " + READER_POOLS + " (0 = subscriptions + 10); warm-up " + warmUp.toSeconds() + " s, window " + window.toSeconds() + " s");
        for (int run = 1; run <= RUNS; run++) {
            for (int configuredPool : READER_POOLS) {
                for (int backstop : BACKSTOPS) {
                    for (int subscriptions : SUBSCRIPTIONS) {
                        for (int rate : new int[]{10, 100}) {
                            int pool = readerPool(configuredPool, subscriptions);
                            try (var db = SpikeDb.start("gate", 100); var app = new App(db, settings(backstop), pool)) {
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
                                app.observer.reset();
                                app.readerWait.resetWindow();
                                measuring.set(true);
                                Thread.sleep(window.toMillis());
                                measuring.set(false);
                                var s = summarise(db, GATE, "steady run " + run + ": pool " + pool + ", backstop " + backstop + " ms, " + subscriptions + " subscription(s), " + rate + " ev/s");
                                var reads      = app.observer.snapshot();
                                var windowWait = app.readerWait.windowMaxMillis();
                                scheduler.shutdown();
                                scheduler.awaitTermination(10, TimeUnit.SECONDS);
                                long events = appended.get();
                                await(Duration.ofSeconds(30), () -> delivered.get() >= events * subscriptions);
                                var l = Latencies.of(latencies);
                                SpikeDb.record(GATE, String.format("STEADY run %d | pool %2d | backstop %4d ms | %2d sub | %3d ev/s | appended %d, deliveries %d | latency p50 %.1f p99 %.1f max %.1f ms | "
                                                                           + "statements per appended event: writer %.2f, reader %.2f | READER STATEMENTS PER DELIVERY %.3f (non-utility %.3f) | "
                                                                           + "tail reads %d (%d combined), progress saves %d | MAX POOL WAIT window %.1f ms, run %.1f ms | WAL %.0f B/event",
                                                                   run, pool, backstop, subscriptions, rate, events, delivered.get(), l.p50(), l.p99(), l.max(),
                                                                   s.calls("writer") / (double) events, s.calls("reader") / (double) events,
                                                                   s.calls("reader") / (double) (events * subscriptions), s.nonUtility("reader") / (double) (events * subscriptions),
                                                                   reads.reads(), reads.combined(), reads.saves(), windowWait, app.readerWait.runMaxMillis(), s.wal() / (double) events));
                            }
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
        header(GATE, "IDLE", "100 seeded events, no writes; FRESH subscriptions " + IDLE_SUBSCRIPTIONS + ", backstops " + BACKSTOPS + " ms, reader pools " + READER_POOLS
                + " (0 = subscriptions + 10); warm-up " + warmUp.toSeconds() + " s after catch-up, window " + window.toSeconds() + " s");
        for (int run = 1; run <= RUNS; run++) {
            for (int configuredPool : READER_POOLS) {
                for (int backstop : BACKSTOPS) {
                    for (int subscriptions : IDLE_SUBSCRIPTIONS) {
                        int pool = readerPool(configuredPool, subscriptions);
                        try (var db = SpikeDb.start("gate", 100); var app = new App(db, settings(backstop), pool)) {
                            var received = new AtomicLong();
                            for (int i = 0; i < subscriptions; i++) {
                                app.eventLog.subscribe("sub-" + i, TYPE, "node1", 1, e -> received.incrementAndGet());
                            }
                            for (int i = 0; i < 100; i++) app.appendNewStream(1, false);
                            await(Duration.ofSeconds(60), () -> received.get() >= 100L * subscriptions);
                            Thread.sleep(warmUp.toMillis());
                            db.resetStatistics();
                            app.observer.reset();
                            app.readerWait.resetWindow();
                            Thread.sleep(window.toMillis());
                            var s     = summarise(db, GATE, "idle run " + run + ": pool " + pool + ", backstop " + backstop + " ms, " + subscriptions + " subscription(s)");
                            var reads = app.observer.snapshot();
                            double seconds = window.toMillis() / 1000.0;
                            SpikeDb.record(GATE, String.format("IDLE run %d | pool %2d | backstop %4d ms | %2d sub | READER STATEMENTS/S %.2f (non-utility %.2f, per subscription %.3f) | "
                                                                       + "tail reads/s %.2f (%d combined) | statements per tail read %.2f | progress saves %d | MAX POOL WAIT window %.1f ms, "
                                                                       + "run %.1f ms | WAL %d B",
                                                               run, pool, backstop, subscriptions, s.calls("reader") / seconds, s.nonUtility("reader") / seconds,
                                                               s.calls("reader") / seconds / subscriptions, reads.reads() / seconds, reads.combined(),
                                                               s.calls("reader") / (double) Math.max(1, reads.reads()), reads.saves(), app.readerWait.windowMaxMillis(),
                                                               app.readerWait.runMaxMillis(), s.wal()));
                        }
                    }
                }
            }
        }
    }

    // ------------------------------------------------------------------ decision 3: re-attach churn

    @Test
    void reattachChurn() throws Exception {
        var window = scaled(Duration.ofSeconds(60));
        header(CHURN, "RE-ATTACH CHURN", "one FRESH subscription, 50 ms handler; writer 100 ev/s (one event per new aggregate); window " + window.toSeconds()
                + " s; arms: a long writer holding a global_order below the head for the whole window, and none; backstops " + BACKSTOPS + " ms; reader pool " + CHURN_POOL);
        for (int run = 1; run <= RUNS; run++) {
            for (int backstop : BACKSTOPS) {
                for (boolean longWriter : new boolean[]{true, false}) {
                    try (var db = SpikeDb.start("churn", 100); var app = new App(db, settings(backstop), CHURN_POOL)) {
                        var churn = app.observer;
                        app.eventLog.subscribe("slow", TYPE, "node1", 1, e -> {
                            churn.handled(e.globalOrder());
                            Thread.sleep(50);
                        });
                        app.appendNewStream(1, false);
                        await(Duration.ofSeconds(30), () -> churn.distinctHandled() >= 1 && churn.attaches.get() >= 1);
                        try (var held = db.connect("writer")) {
                            held.setAutoCommit(false);
                            if (longWriter) {
                                app.append(held, UUID.randomUUID().toString(), 1, true);
                            }
                            var appended  = new AtomicLong();
                            var scheduler = Executors.newSingleThreadScheduledExecutor();
                            db.resetStatistics();
                            churn.reset();
                            app.readerWait.resetWindow();
                            scheduler.scheduleAtFixedRate(() -> {
                                try {
                                    app.appendNewStream(1, false);
                                    appended.incrementAndGet();
                                } catch (SQLException e) {
                                    throw new IllegalStateException(e);
                                }
                            }, 0, 10, TimeUnit.MILLISECONDS);
                            Thread.sleep(window.toMillis());
                            var c = churn.churnSnapshot();
                            var s = summarise(db, CHURN, "churn run " + run + ": backstop " + backstop + " ms, " + (longWriter ? "long writer" : "no long writer"));
                            scheduler.shutdown();
                            scheduler.awaitTermination(10, TimeUnit.SECONDS);
                            held.commit();
                            SpikeDb.record(CHURN, String.format("CHURN run %d | backstop %4d ms | %-14s | appended %d | handled %d (distinct %d, duplicates %d) | ATTACHES %d | detaches %s | "
                                                                        + "catch-up pages %d, rows %d, dropped %d | BRIDGE pages %d, ROWS READ %d, DROPPED AS HANDLED %d | inbox batches %d, rows %d | "
                                                                        + "reader statements %d (%.1f/s), non-utility %d | MAX POOL WAIT window %.1f ms, run %.1f ms",
                                                                run, backstop, longWriter ? "long writer" : "no long writer", appended.get(), c.handled(), c.distinct(), c.duplicates(),
                                                                c.attaches(), c.detaches(), c.catchUpPages(), c.catchUpRows(), c.catchUpDropped(), c.bridgePages(), c.bridgeRows(),
                                                                c.bridgeDropped(), c.batches(), c.batchRows(), s.calls("reader"), s.calls("reader") / (window.toMillis() / 1000.0),
                                                                s.nonUtility("reader"), app.readerWait.windowMaxMillis(), app.readerWait.runMaxMillis()));
                        }
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
        final TimedDataSource     readerWait;
        final EventLog            eventLog;
        final AggregateTypeTables tables;
        final Counters            observer = new Counters();

        App(SpikeDb db, EventLogSettings settings, int readerPoolSize) throws SQLException {
            writerPool = db.pool("writer", WRITER_POOL);
            readerPool = db.pool("reader", readerPoolSize);
            readerWait = new TimedDataSource(readerPool);
            eventLog = EventLog.builder().setDataSource(readerWait).setSettings(settings).setObserver(observer).build();
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

    /** Times every {@code getConnection()}: the pool wait the engine sees */
    private static final class TimedDataSource implements DataSource {
        private final DataSource delegate;
        private final AtomicLong runMaxNanos    = new AtomicLong();
        private final AtomicLong windowMaxNanos = new AtomicLong();

        TimedDataSource(DataSource delegate) {
            this.delegate = delegate;
        }

        private Connection timed(long started, Connection connection) {
            long waited = System.nanoTime() - started;
            runMaxNanos.accumulateAndGet(waited, Math::max);
            windowMaxNanos.accumulateAndGet(waited, Math::max);
            return connection;
        }

        void resetWindow() {
            windowMaxNanos.set(0);
        }

        double windowMaxMillis() {
            return windowMaxNanos.get() / 1e6;
        }

        double runMaxMillis() {
            return runMaxNanos.get() / 1e6;
        }

        @Override
        public Connection getConnection() throws SQLException {
            long started = System.nanoTime();
            return timed(started, delegate.getConnection());
        }

        @Override
        public Connection getConnection(String username, String password) throws SQLException {
            long started = System.nanoTime();
            return timed(started, delegate.getConnection(username, password));
        }

        @Override
        public PrintWriter getLogWriter() throws SQLException {
            return delegate.getLogWriter();
        }

        @Override
        public void setLogWriter(PrintWriter out) throws SQLException {
            delegate.setLogWriter(out);
        }

        @Override
        public void setLoginTimeout(int seconds) throws SQLException {
            delegate.setLoginTimeout(seconds);
        }

        @Override
        public int getLoginTimeout() throws SQLException {
            return delegate.getLoginTimeout();
        }

        @Override
        public Logger getParentLogger() throws SQLFeatureNotSupportedException {
            return delegate.getParentLogger();
        }

        @Override
        public <T> T unwrap(Class<T> iface) throws SQLException {
            return delegate.unwrap(iface);
        }

        @Override
        public boolean isWrapperFor(Class<?> iface) throws SQLException {
            return delegate.isWrapperFor(iface);
        }
    }

    record Reads(long reads, long combined, long saves) {
    }

    record Churn(long handled, long distinct, long duplicates, long attaches, Map<String, Long> detaches, long catchUpPages, long catchUpRows, long catchUpDropped,
                 long bridgePages, long bridgeRows, long bridgeDropped, long batches, long batchRows) {
    }

    /**
     * Every engine callback the spike counts. Catch-up and bridge pages are attributed on the delivery thread: a page's
     * callback comes before its rows are worked through, so the rows the handler did not see by the next delivery-thread
     * callback were dropped as already handled. Single-subscription attribution only (the churn shape).
     */
    private static final class Counters implements EventLogObserver {
        private final AtomicLong reads    = new AtomicLong();
        private final AtomicLong combined = new AtomicLong();
        private final AtomicLong saves    = new AtomicLong();
        final         AtomicLong attaches = new AtomicLong();

        private final Map<String, Long> detaches = new ConcurrentHashMap<>();
        private final Set<Long>         seen     = ConcurrentHashMap.newKeySet();
        private final AtomicLong        handled  = new AtomicLong();
        private final AtomicLong        duplicates = new AtomicLong();
        private volatile boolean        counting   = true;

        // delivery thread only (one subscription)
        private          String pageKind;          // "catch-up", "bridge" or null (an inbox batch)
        private          long   pageRows;
        private          long   pageHandled;
        private volatile long   catchUpPages, catchUpRows, catchUpDropped, bridgePages, bridgeRows, bridgeDropped, batches, batchRows;

        @Override
        public void onRead(String aggregateType, boolean isCombined, long nanos, int rows, int outstandingHoles, int outstandingCandidates, Duration oldestCandidateAge) {
            reads.incrementAndGet();
            if (isCombined) combined.incrementAndGet();
        }

        @Override
        public void onProgressSaved(String subscriber, String aggregateType, long resumeFrom) {
            saves.incrementAndGet();
        }

        @Override
        public void onAttach(String subscriber, String aggregateType, long cursor, long watermark) {
            attaches.incrementAndGet();
        }

        @Override
        public void onDetach(String subscriber, String aggregateType, String reason, long resumeFrom) {
            finishPage();
            detaches.merge(reason, 1L, Long::sum);
        }

        @Override
        public void onCatchUpRead(String subscriber, String aggregateType, boolean bridge, long nanos, int rows) {
            finishPage();
            pageKind = bridge ? "bridge" : "catch-up";
            pageRows = rows;
            pageHandled = 0;
        }

        @Override
        public void onBatchTaken(String subscriber, String aggregateType, int rows, int inboxEvents) {
            finishPage();
            batches++;
            batchRows += rows;
        }

        /** The handler saw an event (delivery thread) */
        void handled(long globalOrder) {
            if (!counting) return;
            handled.incrementAndGet();
            if (!seen.add(globalOrder)) duplicates.incrementAndGet();
            if (pageKind != null) pageHandled++;
        }

        long distinctHandled() {
            return seen.size();
        }

        private void finishPage() {
            if (pageKind == null || !counting) {
                pageKind = null;
                return;
            }
            if (pageKind.equals("bridge")) {
                bridgePages++;
                bridgeRows += pageRows;
                bridgeDropped += pageRows - pageHandled;
            } else {
                catchUpPages++;
                catchUpRows += pageRows;
                catchUpDropped += pageRows - pageHandled;
            }
            pageKind = null;
        }

        void reset() {
            reads.set(0);
            combined.set(0);
            saves.set(0);
            attaches.set(0);
            detaches.clear();
            handled.set(0);
            duplicates.set(0);
            // the delivery thread may be inside a page: its rows count from the next callback on
            catchUpPages = catchUpRows = catchUpDropped = bridgePages = bridgeRows = bridgeDropped = batches = batchRows = 0;
            seenAtReset = new HashSet<>(seen);
        }

        private volatile Set<Long> seenAtReset = Set.of();

        Reads snapshot() {
            return new Reads(reads.get(), combined.get(), saves.get());
        }

        /** Stops counting: the page in progress is not finished, so it is not counted */
        Churn churnSnapshot() {
            counting = false;
            var distinct = seen.stream().filter(g -> !seenAtReset.contains(g)).count();
            return new Churn(handled.get(), distinct, duplicates.get(), attaches.get(), new TreeMap<>(detaches), catchUpPages, catchUpRows, catchUpDropped, bridgePages,
                             bridgeRows, bridgeDropped, batches, batchRows);
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
