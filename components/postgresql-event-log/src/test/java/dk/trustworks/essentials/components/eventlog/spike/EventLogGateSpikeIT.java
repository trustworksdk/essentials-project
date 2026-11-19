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
 * Phase 3, unit C of {@code docs/archive/event-store-engine-phase3.md}: the measurements behind the phase-3 gate (§5.4,
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
 * Phase 4, unit C of {@code docs/archive/event-store-engine-phase4.md} (§9), suite {@code p4-gate}:
 * <ul>
 *     <li>{@link #projectionThroughput()}: P0.13's projection shape on the new engine; baseline {@code FRESH} + periodic
 *     with a per-event autocommit upsert, arms {@code CONTIGUOUS} + in-transaction row by row (page 10, 100), JDBC batch
 *     and set-based (page 100), and a fallback with one event in about 1 000 always failing.</li>
 *     <li>{@link #contiguousLatency()}: {@code CONTIGUOUS} against {@code FRESH} commit-to-handle latency with no long
 *     writer, one and four writers.</li>
 *     <li>{@link #longWriterChurn()}: re-attach churn behind a 60 s and a 300 s writer, both modes.</li>
 *     <li>{@link #mixedStatements()}: statements per delivered event with 10 subscriptions, half {@code CONTIGUOUS}
 *     in-transaction.</li>
 * </ul>
 * Results {@code target/spike-results/p4-c-*.txt}. Extra knobs: {@code -Dspike.gate.projectionEvents} (200000),
 * {@code -Dspike.gate.projections} ("1,4"), {@code -Dspike.gate.projectionArms} (all), {@code -Dspike.gate.latencyWriters}
 * ("1,4"). Suite {@code p3-gate} runs only {@link #steady()}, {@link #idle()} and {@link #reattachChurn()}.
 * <p>
 * Phase 5, unit C of {@code docs/archive/event-store-engine-phase5.md} (§8), suite {@code p5-gate}: {@link #rareTypeCatchUp()}
 * (§8.1, with the density sweep, index size and append cost), {@link #restartStorm()} (§8.2, catch-up permits from
 * {@code -Dspike.gate.stormPermits}, "4,50,2,8") and {@link #freshAfterRestart()} (§8.3). Results
 * {@code target/spike-results/p5-c-*.txt}. Knobs: {@code -Dspike.gate.rareEvents} (1000000), {@code -Dspike.gate.densities}
 * ("1,5,25,50"), {@code -Dspike.gate.stormEvents} (100000).
 * <p>
 * Phase 7, unit C of {@code docs/archive/event-store-engine-phase7.md} (§10), suite {@code p7-gate}: {@link #takeoverTime()}
 * (§10.1, a forked {@code LeaseOwnerProcess} owner and an in-JVM standby), {@link #renewalCost()} (§10.2) and
 * {@link #leaseUnaffected()} (§10.3). Results {@code target/spike-results/p7-c-*.txt}. Knobs:
 * {@code -Dspike.gate.p7Repetitions} (20), {@code -Dspike.gate.p7Shapes} ("CRASH,PAUSE,GRACEFUL"),
 * {@code -Dspike.gate.p7PauseSeconds} (30), {@code -Dspike.gate.p7Leases} ("1,10,100,1000"),
 * {@code -Dspike.gate.p7IdleSeconds} (300).
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
                                    app.eventLog.subscribe(Definitions.definition("sub-" + i, TYPE, "node1", 1, null, null), e -> {
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
                                app.eventLog.subscribe(Definitions.definition("sub-" + i, TYPE, "node1", 1, null, null), e -> received.incrementAndGet());
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
                        app.eventLog.subscribe(Definitions.definition("slow", TYPE, "node1", 1, null, null), e -> {
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

    // ================================================================== phase 4, unit C (suite p4-gate)

    /** The arms of the projection-throughput row (phase 4 §9) */
    enum ProjectionArm {
        /** Baseline: FRESH + PERIODIC, a per-event handler upserting in its own autocommit transaction, 1 s save */
        PERIODIC(0),
        /** CONTIGUOUS + in-transaction, row by row ({@code PageHandler.perEvent}) */
        ROW_10(10),
        ROW_100(100),
        /** CONTIGUOUS + in-transaction, the page's upserts as one JDBC batch */
        BATCH_100(100),
        /** CONTIGUOUS + in-transaction, one statement over {@code unnest} arrays, pre-aggregated per aggregate id */
        SET_100(100),
        /** ROW_100 with every event whose global order is a multiple of 997 always failing; default retry backoff */
        FALLBACK_100(100),
        /** FALLBACK_100 with a 1 ms retry backoff: the shrink and park cost without the retry waits */
        FALLBACK_100_FAST(100);

        final int pageSize;

        ProjectionArm(int pageSize) {
            this.pageSize = pageSize;
        }

        boolean failing() {
            return this == FALLBACK_100 || this == FALLBACK_100_FAST;
        }
    }

    private static final String              P4_THROUGHPUT         = "p4-c-throughput.txt";
    private static final String              P4_LATENCY            = "p4-c-latency.txt";
    private static final String              P4_CHURN              = "p4-c-churn.txt";
    private static final String              P4_STATEMENTS         = "p4-c-statements.txt";
    private static final int                 PROJECTION_EVENTS     = Integer.getInteger("spike.gate.projectionEvents", 200_000);
    private static final int                 PROJECTION_AGGREGATES = Math.max(1, PROJECTION_EVENTS / 10);
    private static final List<Integer>       PROJECTIONS           = ints(System.getProperty("spike.gate.projections", "1,4"));
    private static final List<ProjectionArm> PROJECTION_ARMS       = Arrays.stream(System.getProperty("spike.gate.projectionArms", "PERIODIC,ROW_10,ROW_100,BATCH_100,SET_100,FALLBACK_100,FALLBACK_100_FAST")
                                                                                         .split(",")).map(String::trim).filter(a -> !a.isEmpty()).map(ProjectionArm::valueOf).toList();
    private static final List<Integer>       LATENCY_WRITERS       = ints(System.getProperty("spike.gate.latencyWriters", "1,4"));
    /** One event in about 1 000 fails; 997 is prime to the aggregate count, so every failing event heads its own aggregate */
    private static final int                 FAILING_EVERY         = 997;
    private static final String              VIEW_COLUMNS          = "(aggregate_id, event_count, last_global_order, last_event_type, payload, updated_at)";
    private static final String              VIEW_ON_CONFLICT      = " ON CONFLICT (aggregate_id) DO UPDATE SET event_count = v.event_count + excluded.event_count, "
            + "last_global_order = excluded.last_global_order, last_event_type = excluded.last_event_type, payload = excluded.payload, updated_at = excluded.updated_at";

    private static String view(int projection) {
        return "p4_view_" + projection;
    }

    private static String upsert(int projection) {
        return "INSERT INTO " + view(projection) + " AS v " + VIEW_COLUMNS + " VALUES (?, 1, ?, ?, ?::jsonb, now())" + VIEW_ON_CONFLICT;
    }

    private static String setUpsert(int projection) {
        return "INSERT INTO " + view(projection) + " AS v " + VIEW_COLUMNS
                + " SELECT DISTINCT ON (a) a, count(*) OVER (PARTITION BY a), g, t, p::jsonb, now()"
                + " FROM unnest(?::text[], ?::bigint[], ?::text[], ?::text[]) AS u(a, g, t, p) ORDER BY a, g DESC" + VIEW_ON_CONFLICT;
    }

    private static void bind(PreparedStatement statement, StoredEvent event) throws SQLException {
        statement.setString(1, event.aggregateId());
        statement.setLong(2, event.globalOrder());
        statement.setString(3, event.eventType());
        statement.setString(4, event.payload());
    }

    private static void failIfChosen(ProjectionArm arm, StoredEvent event) {
        if (arm.failing() && event.globalOrder() % FAILING_EVERY == 0) {
            throw new IllegalStateException("always fails: " + event.globalOrder());
        }
    }

    private static PageHandler pageHandler(ProjectionArm arm, int projection) {
        var row = upsert(projection);
        return switch (arm) {
            case ROW_10, ROW_100, FALLBACK_100, FALLBACK_100_FAST -> PageHandler.perEvent((event, tx) -> {
                var connection = tx.connection();
                failIfChosen(arm, event);
                try (var statement = connection.prepareStatement(row)) {
                    bind(statement, event);
                    statement.executeUpdate();
                }
            });
            case BATCH_100 -> (page, tx) -> {
                var connection = tx.connection();
                try (var statement = connection.prepareStatement(row)) {
                    for (var event : page) {
                        bind(statement, event);
                        statement.addBatch();
                    }
                    statement.executeBatch();
                }
            };
            case SET_100 -> {
                var set = setUpsert(projection);
                yield (page, tx) -> {
                    var connection = tx.connection();
                    try (var statement = connection.prepareStatement(set)) {
                        statement.setArray(1, connection.createArrayOf("text", page.stream().map(StoredEvent::aggregateId).toArray(String[]::new)));
                        statement.setArray(2, connection.createArrayOf("bigint", page.stream().map(StoredEvent::globalOrder).toArray(Long[]::new)));
                        statement.setArray(3, connection.createArrayOf("text", page.stream().map(StoredEvent::eventType).toArray(String[]::new)));
                        statement.setArray(4, connection.createArrayOf("text", page.stream().map(StoredEvent::payload).toArray(String[]::new)));
                        statement.executeUpdate();
                    }
                };
            }
            case PERIODIC -> throw new IllegalArgumentException("PERIODIC has no page handler");
        };
    }

    /**
     * Row 1 of phase 4 §9, P0.13's shape on the new engine: {@code PROJECTION_EVENTS} seeded events over a tenth as many
     * interleaved aggregates (payload about 200 B), one pre-populated view table per projection, every write an upsert
     * that updates. Each projection starts from 1 and is timed from {@code subscribe} until its last event is applied.
     * Reader pool projections + 2; the baseline's handler connections are dedicated connections from the writer pool.
     */
    @Test
    void projectionThroughput() throws Exception {
        header(P4_THROUGHPUT, "PROJECTION THROUGHPUT", PROJECTION_EVENTS + " seeded events over " + PROJECTION_AGGREGATES + " aggregates; projections " + PROJECTIONS + "; arms "
                + PROJECTION_ARMS + "; reader pool projections + 2; failing arms: every global order divisible by " + FAILING_EVERY + " always throws");
        for (int run = 1; run <= RUNS; run++) {
            for (int projections : PROJECTIONS) {
                Double baseline = null;
                for (var arm : PROJECTION_ARMS) {
                    var settings = settings(1000).withPageSize(Math.max(1, arm.pageSize));
                    if (arm == ProjectionArm.FALLBACK_100_FAST) {
                        settings = settings.withRetryBackoff(Duration.ofMillis(1), Duration.ofMillis(1));
                    }
                    try (var db = SpikeDb.start("throughput", 100); var app = new App(db, settings, projections + 2)) {
                        seedProjection(app, projections);
                        db.checkpoint();
                        db.resetStatistics();
                        app.observer.resetTotals();
                        app.readerWait.resetWindow();
                        var handled     = new AtomicLong[projections];
                        var dedicated   = new ArrayList<Connection>();
                        var subscribers = new ArrayList<String>();
                        long started    = System.nanoTime();
                        for (int i = 0; i < projections; i++) {
                            var subscriber = "projection-" + i;
                            subscribers.add(subscriber);
                            handled[i] = new AtomicLong();
                            if (arm == ProjectionArm.PERIODIC) {
                                var connection = app.writerPool.getConnection();
                                dedicated.add(connection);
                                var counter = handled[i];
                                var sql     = upsert(i);
                                app.eventLog.subscribe(Definitions.definition(subscriber, TYPE, "node1", 1, null, null), e -> {
                                    try (var statement = connection.prepareStatement(sql)) {
                                        bind(statement, e);
                                        statement.executeUpdate();
                                    }
                                    counter.incrementAndGet();
                                });
                            } else {
                                app.eventLog.subscribe(Definitions.definition(subscriber, TYPE, "node1", 1, ConsistencyMode.CONTIGUOUS, null), pageHandler(arm, i));
                            }
                        }
                        var finished = new long[projections];
                        await(Duration.ofMinutes(30), () -> {
                            boolean all = true;
                            for (int i = 0; i < projections; i++) {
                                if (finished[i] != 0) continue;
                                boolean done = arm == ProjectionArm.PERIODIC ? handled[i].get() >= PROJECTION_EVENTS
                                                                             : app.observer.resumeFrom(subscribers.get(i)) > PROJECTION_EVENTS;
                                if (done) finished[i] = System.nanoTime();
                                else all = false;
                            }
                            return all;
                        });
                        double seconds = (Arrays.stream(finished).max().orElseThrow() - started) / 1e9;
                        var    s       = summarise(db, P4_THROUGHPUT, "throughput run " + run + ": " + arm + ", " + projections + " projection(s)");
                        for (var c : dedicated) c.close();
                        // the fold: every event applied once or parked, nothing else
                        var check = new StringBuilder();
                        long parkedRows = 0;
                        try (var c = db.connect("writer")) {
                            for (int i = 0; i < projections; i++) {
                                long counted = SpikeDb.queryLong(c, "SELECT coalesce(sum(event_count), 0) FROM " + view(i));
                                long parked  = SpikeDb.queryLong(c, "SELECT count(*) FROM " + EventLogSchema.PARKED_EVENTS_TABLE + " WHERE subscriber = '" + subscribers.get(i) + "'");
                                long last    = SpikeDb.queryLong(c, "SELECT coalesce(max(last_global_order), 0) FROM " + view(i));
                                parkedRows += parked;
                                if (counted + parked != PROJECTION_EVENTS) {
                                    check.append(" p").append(i).append(": view ").append(counted).append(" + parked ").append(parked).append(" != ").append(PROJECTION_EVENTS);
                                }
                                if (last == 0) check.append(" p").append(i).append(": empty view");
                            }
                        }
                        double perProjection = PROJECTION_EVENTS / seconds;
                        if (arm == ProjectionArm.PERIODIC) baseline = perProjection;
                        var o = app.observer;
                        SpikeDb.record(P4_THROUGHPUT, String.format("THROUGHPUT run %d | %-17s | %d projection(s) | %.1f s | EVENTS/S PER PROJECTION %.0f (total %.0f) | vs PERIODIC %s | "
                                                                            + "pages committed %d (mean %.1f events), failed %d | parked heads %d, blocked %d (rows %d) | "
                                                                            + "reader statements %d (%.3f per event) | MAX POOL WAIT %.1f ms | fold %s",
                                                                    run, arm, projections, seconds, perProjection, perProjection * projections,
                                                                    baseline == null ? "-" : String.format("%.2fx", perProjection / baseline),
                                                                    o.pagesCommitted.get(), o.pageEvents.get() / (double) Math.max(1, o.pagesCommitted.get()), o.pagesFailed.get(),
                                                                    o.parkedHeads.get(), o.parkedBlocked.get(), parkedRows, s.calls("reader"),
                                                                    s.calls("reader") / (double) PROJECTION_EVENTS / projections, app.readerWait.runMaxMillis(),
                                                                    check.isEmpty() ? "OK" : "MISMATCH" + check));
                    }
                }
            }
        }
    }

    private static void seedProjection(App app, int projections) throws SQLException {
        var padding = "x".repeat(120);
        try (var c = app.writerPool.getConnection()) {
            c.setAutoCommit(false);
            try (var s = c.createStatement()) {
                for (int i = 0; i < projections; i++) {
                    s.execute("CREATE TABLE " + view(i) + " (aggregate_id text PRIMARY KEY, event_count bigint NOT NULL, last_global_order bigint NOT NULL, "
                                      + "last_event_type text, payload jsonb NOT NULL, updated_at timestamptz NOT NULL)");
                    s.execute("INSERT INTO " + view(i) + " SELECT 'agg-' || a, 0, 0, NULL, '{}'::jsonb, now() FROM generate_series(0, " + (PROJECTION_AGGREGATES - 1) + ") a");
                }
            }
            c.commit();
            var requests = new ArrayList<AppendRequest>(1000);
            for (int k = 0; k < PROJECTION_EVENTS; k++) {
                var id = "agg-" + (k % PROJECTION_AGGREGATES);
                requests.add(AppendRequest.withoutExpectedOrder(TYPE, id, List.of(new EventToAppend(UUID.randomUUID(), CAUSE, CORRELATION, EVENT_TYPE, 1, Instant.now(),
                                                                                                    "{\"orderId\":\"" + id + "\",\"orderNumber\":" + k + ",\"note\":\"" + padding + "\"}",
                                                                                                    "{}"))));
                if (requests.size() == 1000 || k == PROJECTION_EVENTS - 1) {
                    app.eventLog.append(c, requests);
                    c.commit();
                    requests.clear();
                }
            }
            try (var s = c.createStatement()) {
                s.execute("ANALYZE");
            }
            c.commit();
        }
    }

    /**
     * Row 2: one FRESH (periodic) and one CONTIGUOUS (in-transaction, empty page handler) subscription on the same type;
     * P0.0's steady shape at 100 events/s from one writer, or from four writers that each hold their transaction 0-30 ms
     * after the append, so commits come out of order and leave holes. Commit-to-handle latency, from just before the
     * commit to the handler.
     */
    @Test
    void contiguousLatency() throws Exception {
        var warmUp = scaled(Duration.ofSeconds(10));
        var window = scaled(Duration.ofSeconds(60));
        header(P4_LATENCY, "CONTIGUOUS LATENCY", "one FRESH (periodic) and one CONTIGUOUS (in-transaction) subscription; 100 ev/s in total from writers " + LATENCY_WRITERS
                + " (4: each holds its transaction 0-30 ms before commit); backstops " + BACKSTOPS + " ms; warm-up " + warmUp.toSeconds() + " s, window " + window.toSeconds() + " s");
        for (int run = 1; run <= RUNS; run++) {
            for (int backstop : BACKSTOPS) {
                for (int writers : LATENCY_WRITERS) {
                    try (var db = SpikeDb.start("latency", 100); var app = new App(db, settings(backstop), 12)) {
                        var committed  = new ConcurrentHashMap<String, Long>();
                        var fresh      = new ConcurrentLinkedQueue<Long>();
                        var contiguous = new ConcurrentLinkedQueue<Long>();
                        var freshCount = new AtomicLong();
                        var contCount  = new AtomicLong();
                        app.eventLog.subscribe(Definitions.definition("fresh", TYPE, "node1", 1, null, null), e -> {
                            var at = committed.get(e.aggregateId());
                            if (at != null) {
                                fresh.add(System.nanoTime() - at);
                                freshCount.incrementAndGet();
                            }
                        });
                        app.eventLog.subscribe(Definitions.definition("contiguous", TYPE, "node1", 1, ConsistencyMode.CONTIGUOUS, null), (page, tx) -> {
                            var connection = tx.connection();
                            long now = System.nanoTime();
                            for (var e : page) {
                                var at = committed.get(e.aggregateId());
                                if (at != null) {
                                    contiguous.add(now - at);
                                    contCount.incrementAndGet();
                                }
                            }
                        });
                        var measuring = new AtomicBoolean();
                        var appended  = new AtomicLong();
                        var scheduler = Executors.newScheduledThreadPool(writers);
                        long period   = 10_000L * writers;
                        for (int w = 0; w < writers; w++) {
                            boolean hold = writers > 1;
                            scheduler.scheduleAtFixedRate(() -> {
                                try (var c = app.writerPool.getConnection()) {
                                    var id      = UUID.randomUUID().toString();
                                    var measure = measuring.get();
                                    c.setAutoCommit(false);
                                    app.append(c, id, 1, false);
                                    if (hold) Thread.sleep(ThreadLocalRandom.current().nextInt(31));
                                    // a reader may deliver before the commit returns: known before, timed after
                                    if (measure) committed.put(id, System.nanoTime());
                                    c.commit();
                                    if (measure) {
                                        committed.put(id, System.nanoTime());
                                        appended.incrementAndGet();
                                    }
                                } catch (SQLException | InterruptedException e) {
                                    throw new IllegalStateException(e);
                                }
                            }, w * 10_000L, period, TimeUnit.MICROSECONDS);
                        }
                        Thread.sleep(warmUp.toMillis());
                        db.resetStatistics();
                        app.observer.reset();
                        app.observer.resetTotals();
                        app.readerWait.resetWindow();
                        measuring.set(true);
                        Thread.sleep(window.toMillis());
                        measuring.set(false);
                        var holes = app.observer.maxHoles.get();
                        scheduler.shutdown();
                        scheduler.awaitTermination(10, TimeUnit.SECONDS);
                        long events = appended.get();
                        await(Duration.ofSeconds(30), () -> freshCount.get() >= events && contCount.get() >= events);
                        var f    = Latencies.of(fresh);
                        var c    = Latencies.of(contiguous);
                        var pass = c.p50() <= f.p50() + backstop && c.p99() <= f.p99() + backstop;
                        SpikeDb.record(P4_LATENCY, String.format("LATENCY run %d | backstop %4d ms | %d writer(s) | appended %d | FRESH p50 %.1f p99 %.1f max %.1f ms | "
                                                                         + "CONTIGUOUS p50 %.1f p99 %.1f max %.1f ms | delta p50 %+.1f p99 %+.1f ms | %s | max outstanding holes %d | "
                                                                         + "pages %d, max held back %d | MAX POOL WAIT window %.1f ms",
                                                                 run, backstop, writers, events, f.p50(), f.p99(), f.max(), c.p50(), c.p99(), c.max(), c.p50() - f.p50(),
                                                                 c.p99() - f.p99(), pass ? "PASS" : "FAIL", holes, app.observer.pagesCommitted.get(), app.observer.maxHeldBack.get(),
                                                                 app.readerWait.windowMaxMillis()));
                    }
                }
            }
        }
    }

    /**
     * Row 3: phase-3 §7.4's churn shape (one subscription, 50 ms per event, 100 ev/s, reader pool 4, a long writer
     * holding a value below the head) for 60 s and 300 s, once CONTIGUOUS (in-transaction, row by row) and once FRESH
     * (periodic). After the window the long writer commits and the next 10 s show the deferred bridge.
     */
    @Test
    void longWriterChurn() throws Exception {
        var after = scaled(Duration.ofSeconds(10));
        header(P4_CHURN, "CHURN BEHIND A LONG WRITER", "one subscription, 50 ms per event; writer 100 ev/s; a long writer holds a global_order below the head for the window; "
                + "windows 60 s and 300 s (scaled " + TIME_SCALE + "); CONTIGUOUS (in-transaction) and FRESH (periodic); backstop 1 000 ms; reader pool " + CHURN_POOL
                + "; then " + after.toSeconds() + " s after the long writer commits");
        for (int run = 1; run <= RUNS; run++) {
            for (int windowSeconds : new int[]{60, 300}) {
                for (var mode : ConsistencyMode.values()) {
                    var window     = scaled(Duration.ofSeconds(windowSeconds));
                    var contiguous = mode == ConsistencyMode.CONTIGUOUS;
                    try (var db = SpikeDb.start("churn", 100); var app = new App(db, settings(1000), CHURN_POOL)) {
                        var churn = app.observer;
                        if (contiguous) {
                            app.eventLog.subscribe(Definitions.definition("slow", TYPE, "node1", 1, mode, null), PageHandler.perEvent((e, tx) -> {
                                var connection = tx.connection();
                                churn.handled(e.globalOrder());
                                Thread.sleep(50);
                            }));
                        } else {
                            app.eventLog.subscribe(Definitions.definition("slow", TYPE, "node1", 1, null, null), e -> {
                                churn.handled(e.globalOrder());
                                Thread.sleep(50);
                            });
                        }
                        app.appendNewStream(1, false);
                        await(Duration.ofSeconds(30), () -> churn.distinctHandled() >= 1 && churn.attaches.get() >= 1);
                        try (var held = db.connect("writer")) {
                            held.setAutoCommit(false);
                            app.append(held, UUID.randomUUID().toString(), 1, true);
                            var appended  = new AtomicLong();
                            var scheduler = Executors.newSingleThreadScheduledExecutor();
                            db.resetStatistics();
                            churn.reset();
                            churn.resetTotals();
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
                            long bridgeReads = churn.bridgeReads.get(), bridgeRows = churn.bridgeReadRows.get(), catchUpReads = churn.catchUpReads.get();
                            long attaches    = churn.totalAttaches.get(), maxInbox = churn.maxInbox.get(), maxHeldBack = churn.maxHeldBack.get();
                            long pages       = churn.pagesCommitted.get(), released = churn.releasedRows.get();
                            var  c           = churn.churnSnapshot();
                            var  s           = summarise(db, P4_CHURN, "churn run " + run + ": " + mode + ", " + windowSeconds + " s writer");
                            held.commit();
                            long committedAt = System.nanoTime();
                            Thread.sleep(after.toMillis());
                            scheduler.shutdown();
                            scheduler.awaitTermination(10, TimeUnit.SECONDS);
                            SpikeDb.record(P4_CHURN, String.format("CHURN run %d | %-10s | writer %3d s | appended %d | handled %d (distinct %d, duplicates %d) | ATTACHES %d | detaches %s | "
                                                                           + "catch-up reads %d (rows %d, dropped %d) | BRIDGE STATEMENTS %d, ROWS READ %d, DROPPED AS HANDLED %s | "
                                                                           + "released %d, pages %d, max inbox %d, max held back %d | reader statements %d (%.1f/s) | "
                                                                           + "AFTER COMMIT (%d s): bridge statements %d, rows %d, released %d | MAX POOL WAIT window %.1f ms",
                                                                   run, mode, windowSeconds, appended.get(), c.handled(), c.distinct(), c.duplicates(), attaches, c.detaches(),
                                                                   catchUpReads, c.catchUpRows(), c.catchUpDropped(), bridgeReads, bridgeRows,
                                                                   contiguous ? "n/a" : c.bridgeDropped() + String.format(" (%.0f %%)", 100.0 * c.bridgeDropped() / Math.max(1, c.bridgeRows())),
                                                                   released, pages, maxInbox, maxHeldBack, s.calls("reader"), s.calls("reader") / (window.toMillis() / 1000.0),
                                                                   after.toSeconds(), churn.bridgeReads.get() - bridgeReads, churn.bridgeReadRows.get() - bridgeRows,
                                                                   churn.releasedRows.get() - released, app.readerWait.windowMaxMillis()));
                        }
                    }
                }
            }
        }
    }

    /**
     * Row 4: phase-3 §7.2's steady shape with 10 subscriptions, half FRESH (periodic, empty handler), half CONTIGUOUS
     * (in-transaction, empty page handler). Reader statements per delivered event against phase-3 §7.2.
     */
    @Test
    void mixedStatements() throws Exception {
        var warmUp        = scaled(Duration.ofSeconds(10));
        var window        = scaled(Duration.ofSeconds(60));
        int subscriptions = 10;
        header(P4_STATEMENTS, "STATEMENTS PER DELIVERY, MIXED", "P0.0's steady shape; 5 FRESH (periodic) and 5 CONTIGUOUS (in-transaction) subscriptions, empty handlers; backstops "
                + BACKSTOPS + " ms, reader pools " + READER_POOLS + " (0 = subscriptions + 10); warm-up " + warmUp.toSeconds() + " s, window " + window.toSeconds() + " s");
        for (int run = 1; run <= RUNS; run++) {
            for (int configuredPool : READER_POOLS) {
                for (int backstop : BACKSTOPS) {
                    for (int rate : new int[]{10, 100}) {
                        int pool = readerPool(configuredPool, subscriptions);
                        try (var db = SpikeDb.start("gate", 100); var app = new App(db, settings(backstop), pool)) {
                            var committed  = new ConcurrentHashMap<String, Long>();
                            var fresh      = new ConcurrentLinkedQueue<Long>();
                            var contiguous = new ConcurrentLinkedQueue<Long>();
                            var delivered  = new AtomicLong();
                            for (int i = 0; i < subscriptions / 2; i++) {
                                app.eventLog.subscribe(Definitions.definition("fresh-" + i, TYPE, "node1", 1, null, null), e -> {
                                    var at = committed.get(e.aggregateId());
                                    if (at != null) {
                                        fresh.add(System.nanoTime() - at);
                                        delivered.incrementAndGet();
                                    }
                                });
                                app.eventLog.subscribe(Definitions.definition("contiguous-" + i, TYPE, "node1", 1, ConsistencyMode.CONTIGUOUS, null), (page, tx) -> {
                                    var connection = tx.connection();
                                    long now = System.nanoTime();
                                    for (var e : page) {
                                        var at = committed.get(e.aggregateId());
                                        if (at != null) {
                                            contiguous.add(now - at);
                                            delivered.incrementAndGet();
                                        }
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
                                    if (measure) committed.put(id, System.nanoTime());
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
                            app.observer.resetTotals();
                            app.readerWait.resetWindow();
                            measuring.set(true);
                            Thread.sleep(window.toMillis());
                            measuring.set(false);
                            var s     = summarise(db, P4_STATEMENTS, "mixed run " + run + ": pool " + pool + ", backstop " + backstop + " ms, " + rate + " ev/s");
                            var reads = app.observer.snapshot();
                            long pages = app.observer.pagesCommitted.get();
                            long progress = db.statements().stream().filter(st -> st.role().equals("reader") && st.query().contains(EventLogSchema.PROGRESS_TABLE))
                                              .mapToLong(SpikeDb.Statement::calls).sum();
                            scheduler.shutdown();
                            scheduler.awaitTermination(10, TimeUnit.SECONDS);
                            long events = appended.get();
                            await(Duration.ofSeconds(30), () -> delivered.get() >= events * subscriptions);
                            var f = Latencies.of(fresh);
                            var c = Latencies.of(contiguous);
                            SpikeDb.record(P4_STATEMENTS, String.format("MIXED run %d | pool %2d | backstop %4d ms | %3d ev/s | appended %d | READER STATEMENTS PER DELIVERY %.3f "
                                                                                + "(non-utility %.3f) | progress statements %d (periodic saves + page updates), pages %d, tail reads %d | "
                                                                                + "FRESH p50 %.1f p99 %.1f ms, CONTIGUOUS p50 %.1f p99 %.1f ms | MAX POOL WAIT window %.1f ms, run %.1f ms",
                                                                        run, pool, backstop, rate, events, s.calls("reader") / (double) (events * subscriptions),
                                                                        s.nonUtility("reader") / (double) (events * subscriptions), progress, pages, reads.reads(), f.p50(), f.p99(),
                                                                        c.p50(), c.p99(), app.readerWait.windowMaxMillis(), app.readerWait.runMaxMillis()));
                        }
                    }
                }
            }
        }
    }

    // ================================================================== phase 5, unit C (suite p5-gate)

    private static final String        P5_RARE       = "p5-c-rare.txt";
    private static final String        P5_STORM      = "p5-c-storm.txt";
    private static final String        P5_RESTART    = "p5-c-restart.txt";
    private static final int           RARE_EVENTS   = Integer.getInteger("spike.gate.rareEvents", 1_000_000);
    private static final int           RARE_TYPES    = 100;
    private static final List<Integer> DENSITIES     = ints(System.getProperty("spike.gate.densities", "1,5,25,50"));
    private static final int           STORM_EVENTS  = Integer.getInteger("spike.gate.stormEvents", 100_000);
    private static final List<Integer> STORM_PERMITS = ints(System.getProperty("spike.gate.stormPermits", "4,50,2,8"));
    private static final String        RARE_TYPE     = typeName(0);

    private static String typeName(int type) {
        return String.format("type-%02d", type);
    }

    /** Exactly {@code density} % of the orders match, spread uniformly */
    private static boolean matching(long k, int density) {
        return (k + 1) * density / 100 > k * density / 100;
    }

    /** The arms of phase 5 §8.1 (the filtered read without the window needs a main-code switch: not measured) */
    enum RareArm {
        /** Reference: phase 4's path, {@code EventTypeFilter.all()}, the handler ignores the other types */
        UNFILTERED,
        FILTERED,
        FILTERED_INDEX
    }

    /**
     * Phase 5 §8.1: {@code RARE_EVENTS} seeded events on one aggregate type over 100 event types and a tenth as many
     * aggregates, payload about 200 B; the subscribed type is {@code density} % of them. One FRESH subscription from 1 with
     * an empty handler, timed until it attaches: with no writes the watermark is the head, so attaching means its catch-up
     * passed the head. Then a density sweep with the index, and the append cost with and without the index.
     */
    @Test
    void rareTypeCatchUp() throws Exception {
        header(P5_RARE, "RARE-TYPE CATCH-UP", RARE_EVENTS + " seeded events, " + RARE_TYPES + " event types, " + RARE_EVENTS / 10 + " aggregates, payload about 200 B; arms "
                + Arrays.toString(RareArm.values()) + " at 1 %, then FILTERED_INDEX at " + DENSITIES + " %; reader pool 4; a fresh container per arm");
        for (int run = 1; run <= RUNS; run++) {
            for (var arm : RareArm.values()) {
                rareArm(run, arm, 1);
            }
            for (int density : DENSITIES) {
                if (density != 1) rareArm(run, RareArm.FILTERED_INDEX, density);
            }
        }
    }

    private void rareArm(int run, RareArm arm, int density) throws Exception {
        var indexed  = arm == RareArm.FILTERED_INDEX;
        var settings = settings(1000);
        try (var db = SpikeDb.start("rare_r" + run + "_" + arm.name().toLowerCase(Locale.ROOT) + "_d" + density, 100)) {
            long   seedMillis;
            String indexState = "no index";
            String table;
            try (var seed = new App(db, settings, 4)) {
                table = seed.tables.eventTable();
                long t0 = System.nanoTime();
                seed(seed, RARE_EVENTS, k -> matching(k, density) ? RARE_TYPE : typeName(1 + (int) (k % (RARE_TYPES - 1))));
                seedMillis = (System.nanoTime() - t0) / 1_000_000;
                try (var c = db.connect("writer"); var s = c.createStatement()) {
                    long build = 0;
                    if (indexed) {
                        long b0 = System.nanoTime();
                        s.execute(EventLogSchemaContributor.eventTypeIndexStatements(seed.tables).getFirst());
                        build = (System.nanoTime() - b0) / 1_000_000;
                    }
                    s.execute("VACUUM ANALYZE " + table);
                    if (indexed) {
                        var index = seed.tables.eventTypeIndex();
                        indexState = String.format("index valid %s, %d MB (table %d MB), built in %.1f s",
                                                   SpikeDb.queryString(c, "SELECT (i.indisvalid AND i.indisready)::text FROM pg_index i WHERE i.indexrelid = to_regclass('" + index + "')"),
                                                   SpikeDb.queryLong(c, "SELECT pg_relation_size('" + index + "')") >> 20, SpikeDb.queryLong(c, "SELECT pg_relation_size('" + table + "')") >> 20,
                                                   build / 1000.0);
                    }
                }
            }
            var armSettings = indexed ? settings.withEventTypeIndexed(Set.of(TYPE)) : settings;
            try (var app = new App(db, armSettings, 4)) {
                long head;
                long expected;
                try (var c = db.connect("writer")) {
                    head = SpikeDb.queryLong(c, "SELECT max(global_order) FROM " + table);
                    expected = SpikeDb.queryLong(c, "SELECT count(*) FROM " + table + " e JOIN el_event_types t ON t.id = e.event_type WHERE t.name = '" + RARE_TYPE + "'");
                }
                db.checkpoint();
                db.resetStatistics();
                app.observer.resetTotals();
                var  handled = new AtomicLong();
                long started = System.nanoTime();
                if (arm == RareArm.UNFILTERED) {
                    app.eventLog.subscribe(Definitions.definition("rare", TYPE, "node1", 1, null, null), e -> {
                        if (RARE_TYPE.equals(e.eventType())) handled.incrementAndGet();
                    });
                } else {
                    app.eventLog.subscribe(Definitions.definition("rare", TYPE, "node1", 1, null, EventTypeFilter.of(RARE_TYPE)), e -> handled.incrementAndGet());
                }
                await(Duration.ofMinutes(30), () -> app.observer.firstAttach("rare") != 0);
                double seconds = (app.observer.firstAttach("rare") - started) / 1e9;
                var    s       = summarise(db, P5_RARE, "rare run " + run + ": " + arm + ", " + density + " %");
                long   rows    = db.statements().stream().filter(st -> st.role().equals("reader")).mapToLong(SpikeDb.Statement::rows).sum();
                var    o       = app.observer;
                long   reads   = o.catchUpReads.get();
                SpikeDb.record(P5_RARE, String.format("RARE run %d | %-14s | density %2d %% | head %d, matching %d, handled %d | seed %.0f s | %s | CATCH-UP %.2f s | catch-up statements %d "
                                                              + "(filtered %d, index-served %d, skip-ahead %d), permits %d | ROWS TO CLIENT catch-up %d (%.3f x matching), all reader statements %d | "
                                                              + "READER STATEMENTS %d (non-utility %d) | indexed-arm bound 1.05 x matching + statements = %.0f",
                                                      run, arm, density, head, expected, handled.get(), seedMillis / 1000.0, indexState, seconds, reads, o.filteredReads.get(),
                                                      o.indexReads.get(), o.permits.get() - reads, o.permits.get(), o.catchUpRowsAll.get(),
                                                      o.catchUpRowsAll.get() / (double) Math.max(1, expected), rows, s.calls("reader"), s.nonUtility("reader"),
                                                      1.05 * expected + reads));
                if (density == 1 && arm != RareArm.UNFILTERED) {
                    appendCost(db, app, run, indexed);
                }
            }
        }
    }

    /** P0.4's append shape on the seeded table: single thread, new aggregates, a random one of the 100 types */
    private static void appendCost(SpikeDb db, App app, int run, boolean indexed) throws SQLException {
        for (int events : new int[]{1, 10}) {
            int iterations = events == 1 ? 1000 : 300;
            for (int i = 0; i < 50; i++) app.appendNewStream(UUID.randomUUID().toString(), events, false, typeName(ThreadLocalRandom.current().nextInt(RARE_TYPES)));
            var latencies = new ArrayList<Long>();
            for (int i = 0; i < iterations; i++) {
                long t0 = System.nanoTime();
                app.appendNewStream(UUID.randomUUID().toString(), events, false, typeName(ThreadLocalRandom.current().nextInt(RARE_TYPES)));
                latencies.add(System.nanoTime() - t0);
            }
            var l = Latencies.of(latencies);
            SpikeDb.record(P5_RARE, String.format("APPEND run %d | %-8s | %2d event(s) per unit of work | p50 %.2f ms p99 %.2f ms max %.2f ms", run, indexed ? "index" : "no index",
                                                  events, l.p50(), l.p99(), l.max()));
        }
    }

    /** Seeds {@code events} events over a tenth as many aggregates, 1 000 per transaction, then analyzes */
    private static void seed(App app, int events, java.util.function.LongFunction<String> eventType) throws SQLException {
        var padding    = "x".repeat(120);
        int aggregates = Math.max(1, events / 10);
        try (var c = app.writerPool.getConnection()) {
            c.setAutoCommit(false);
            var requests = new ArrayList<AppendRequest>(1000);
            for (int k = 0; k < events; k++) {
                var id = "agg-" + (k % aggregates);
                requests.add(AppendRequest.withoutExpectedOrder(TYPE, id, List.of(new EventToAppend(UUID.randomUUID(), CAUSE, CORRELATION, eventType.apply(k), 1, Instant.now(),
                                                                                                    "{\"orderId\":\"" + id + "\",\"orderNumber\":" + k + ",\"note\":\"" + padding + "\"}",
                                                                                                    "{}"))));
                if (requests.size() == 1000 || k == events - 1) {
                    app.eventLog.append(c, requests);
                    c.commit();
                    requests.clear();
                }
            }
            c.setAutoCommit(true);
            try (var s = c.createStatement()) {
                s.execute("ANALYZE");
            }
        }
    }

    /**
     * Phase 5 §8.2: {@code STORM_EVENTS} seeded events (20 types), 8 writers at 25 events/s each (one event per unit of
     * work), after 10 s 50 subscriptions start at once from 1: 25 FRESH periodic and 25 CONTIGUOUS in-transaction, half of
     * each filtered to 2 of the 20 types. Engine and writers share ONE pool of 10 (one role). Measured until every
     * subscription has attached, plus 10 s. A probe connection samples {@code pg_stat_activity} every 5 ms.
     */
    @Test
    void restartStorm() throws Exception {
        header(P5_STORM, "RESTART STORM", STORM_EVENTS + " seeded events (20 types); 8 writers x 25 ev/s; 50 subscriptions from 1 (25 FRESH periodic, 25 CONTIGUOUS in-transaction, "
                + "half of each filtered to 2 types); one pool of 10 for engine and writers; catchUpPermits " + STORM_PERMITS + "; 10 s before, until all attached + 10 s");
        for (int run = 1; run <= RUNS; run++) {
            for (int permits : STORM_PERMITS) {
                stormArm(run, permits);
            }
        }
    }

    private void stormArm(int run, int permits) throws Exception {
        var settings = settings(1000).withCatchUpPermits(permits);
        try (var db = SpikeDb.start("storm_r" + run + "_p" + permits, 100); var app = new App(db, settings, WRITER_POOL, true)) {
            seed(app, STORM_EVENTS, k -> typeName((int) (k % 20)));
            db.checkpoint();
            var during    = new AtomicBoolean();
            var before    = new ConcurrentLinkedQueue<Long>();
            var inStorm   = new ConcurrentLinkedQueue<Long>();
            var writers   = Executors.newScheduledThreadPool(8);
            for (int w = 0; w < 8; w++) {
                writers.scheduleAtFixedRate(() -> {
                    try {
                        boolean storm = during.get();
                        long    t0    = System.nanoTime();
                        app.appendNewStream(UUID.randomUUID().toString(), 1, false, typeName(ThreadLocalRandom.current().nextInt(20)));
                        (storm ? inStorm : before).add(System.nanoTime() - t0);
                    } catch (SQLException e) {
                        throw new IllegalStateException(e);
                    }
                }, w * 5L, 40, TimeUnit.MILLISECONDS);
            }
            Thread.sleep(scaled(Duration.ofSeconds(10)).toMillis());
            var sampler = new ActivitySampler(db, app.tables.eventTable());
            db.resetStatistics();
            app.observer.resetTotals();
            app.writerWait.resetWindow();
            app.readerWait.resetWindow();
            during.set(true);
            long started = System.nanoTime();
            var  names   = new ArrayList<String>();
            for (int i = 0; i < 50; i++) {
                boolean fresh    = i < 25;
                boolean filtered = i % 2 == 0;
                var     name     = (fresh ? "fresh-" : "contiguous-") + i;
                names.add(name);
                var filter = filtered ? EventTypeFilter.of(typeName(0), typeName(1)) : EventTypeFilter.all();
                if (fresh) {
                    app.eventLog.subscribe(Definitions.definition(name, TYPE, "node1", 1, null, filter), e -> {
                    });
                } else {
                    app.eventLog.subscribe(Definitions.definition(name, TYPE, "node1", 1, ConsistencyMode.CONTIGUOUS, filter), (page, tx) -> {
                        var connection = tx.connection();
                    });
                }
            }
            await(Duration.ofMinutes(20), () -> names.stream().allMatch(n -> app.observer.firstAttach(n) != 0));
            long allLive = names.stream().mapToLong(app.observer::firstAttach).max().orElseThrow();
            Thread.sleep(scaled(Duration.ofSeconds(10)).toMillis());
            during.set(false);
            sampler.close();
            writers.shutdown();
            writers.awaitTermination(10, TimeUnit.SECONDS);
            var s = summarise(db, P5_STORM, "storm run " + run + ": permits " + permits);
            var b = Latencies.of(before);
            var d = Latencies.of(inStorm);
            var o = app.observer;
            SpikeDb.record(P5_STORM, String.format("STORM run %d | permits %2d | APPEND before p50 %.2f p99 %.2f max %.1f ms | DURING p50 %.2f p99 %.2f max %.1f ms (%d appends) | "
                                                           + "ALL LIVE %.1f s | PEAK CONNECTIONS %d | PEAK ACTIVE EVENT-TABLE READS %d (sampled, tail read included) | PERMITS IN USE max %d "
                                                           + "(%s cap) | largest permit wait %.1f ms | largest writer getConnection wait %.1f ms, engine %.1f ms | catch-up statements %d, "
                                                           + "bridge statements %d, filtered reads %d | statements %d (non-utility %d) | samples %d",
                                                   run, permits, b.p50(), b.p99(), b.max(), d.p50(), d.p99(), d.max(), inStorm.size(), (allLive - started) / 1e9,
                                                   sampler.peakConnections(), sampler.peakReads(), o.maxInUse.get(), o.maxInUse.get() <= permits ? "within" : "ABOVE",
                                                   o.maxPermitWait.get() / 1e6, app.writerWait.windowMaxMillis(), app.readerWait.windowMaxMillis(), o.catchUpReads.get(),
                                                   o.bridgeReads.get(), o.filteredReads.get(), s.calls("writer") + s.calls("reader"),
                                                   s.nonUtility("writer") + s.nonUtility("reader"), sampler.samples()));
        }
    }

    /** Samples {@code pg_stat_activity} every 5 ms on its own connection: application backends, and active event-table reads */
    private static final class ActivitySampler implements AutoCloseable {
        private final AtomicLong    peakConnections = new AtomicLong();
        private final AtomicLong    peakReads       = new AtomicLong();
        private final AtomicLong    samples         = new AtomicLong();
        private final AtomicBoolean running         = new AtomicBoolean(true);
        private final Thread        thread;

        ActivitySampler(SpikeDb db, String eventTable) {
            var sql = "SELECT count(*), count(*) FILTER (WHERE state = 'active' AND query ILIKE '%" + eventTable + "%' AND query NOT ILIKE '%pg_current_xact_id%') "
                    + "FROM pg_stat_activity WHERE backend_type = 'client backend' AND usename IN ('" + db.role("writer") + "', '" + db.role("reader") + "')";
            thread = Thread.ofPlatform().name("activity-sampler").start(() -> {
                // the observing role: excluded from the statement counts and from the sample itself
                try (var st = db.observer.prepareStatement(sql)) {
                    while (running.get()) {
                        try (var rs = st.executeQuery()) {
                            rs.next();
                            peakConnections.accumulateAndGet(rs.getLong(1), Math::max);
                            peakReads.accumulateAndGet(rs.getLong(2), Math::max);
                            samples.incrementAndGet();
                        }
                        Thread.sleep(5);
                    }
                } catch (Exception e) {
                    e.printStackTrace();
                }
            });
        }

        long peakConnections() { return peakConnections.get(); }

        long peakReads() { return peakReads.get(); }

        long samples() { return samples.get(); }

        @Override
        public void close() throws InterruptedException {
            running.set(false);
            thread.join();
        }
    }

    /**
     * Phase 5 §8.3 (recorded, not gated): one FRESH subscription at 100 ev/s, live; a writer holds a value for 60 s; 10 s
     * into it the reader restarts (every backend of the reader role is terminated, the loop's held connection among them).
     * Records the time from the restart to the first delivery, the bridge statements and rows, and the gaps handed off.
     */
    @Test
    void freshAfterRestart() throws Exception {
        var hold = scaled(Duration.ofSeconds(60));
        header(P5_RESTART, "FRESH AFTER A READER RESTART", "one FRESH subscription, writer 100 ev/s; a writer holds a value for " + hold.toSeconds()
                + " s; the reader role's backends are terminated 10 s into it; backstop 1 000 ms; reader pool 4");
        for (int run = 1; run <= RUNS; run++) {
            try (var db = SpikeDb.start("restart_r" + run, 100); var app = new App(db, settings(1000), CHURN_POOL)) {
                var o            = app.observer;
                var restartedAt  = new AtomicLong(Long.MAX_VALUE);
                var firstAfter   = new AtomicLong();
                app.eventLog.subscribe(Definitions.definition("fresh", TYPE, "node1", 1, null, null), e -> {
                    o.handled(e.globalOrder());
                    long now = System.nanoTime();
                    if (now > restartedAt.get()) firstAfter.compareAndSet(0, now);
                });
                var appended  = new AtomicLong();
                var scheduler = Executors.newSingleThreadScheduledExecutor();
                scheduler.scheduleAtFixedRate(() -> {
                    try {
                        app.appendNewStream(1, false);
                        appended.incrementAndGet();
                    } catch (SQLException e) {
                        throw new IllegalStateException(e);
                    }
                }, 0, 10, TimeUnit.MILLISECONDS);
                Thread.sleep(5_000);
                try (var held = db.connect("writer")) {
                    held.setAutoCommit(false);
                    app.append(held, UUID.randomUUID().toString(), 1, false);
                    long heldAt = System.nanoTime();
                    Thread.sleep(scaled(Duration.ofSeconds(10)).toMillis());
                    db.resetStatistics();
                    o.resetTotals();
                    long terminated;
                    restartedAt.set(System.nanoTime());
                    try (var s = db.observer.createStatement();
                         var rs = s.executeQuery("SELECT count(pg_terminate_backend(pid)) FROM pg_stat_activity WHERE usename = '" + db.role("reader") + "'")) {
                        rs.next();
                        terminated = rs.getLong(1);
                    }
                    long remaining = hold.toNanos() - (System.nanoTime() - heldAt);
                    Thread.sleep(Math.max(0, remaining / 1_000_000));
                    long firstMs  = firstAfter.get() == 0 ? -1 : (firstAfter.get() - restartedAt.get()) / 1_000_000;
                    long attaches = o.totalAttaches.get(), unknown = o.unknownAttaches.get(), handOffs = o.handOffs.get(), gaps = o.gapsHandedOff.get();
                    long toReader = o.handOffsToReader.get(), bridges = o.bridgeReads.get(), bridgeRows = o.bridgeReadRows.get(), waits = o.watermarkWaits.get();
                    var  detaches = new TreeMap<>(o.detachReasons);
                    held.commit();
                    Thread.sleep(scaled(Duration.ofSeconds(10)).toMillis());
                    scheduler.shutdown();
                    scheduler.awaitTermination(10, TimeUnit.SECONDS);
                    long expected = appended.get() + 1;
                    await(Duration.ofSeconds(60), () -> o.distinctHandled() >= expected);
                    var c = o.churnSnapshot();
                    SpikeDb.record(P5_RESTART, String.format("RESTART run %d | terminated %d backend(s) | FIRST DELIVERY AFTER RESTART %d ms | attaches %d (under an unknown watermark %d), "
                                                                     + "detaches %s | watermark waits %d | HAND-OFFS %d (to the reader %d), GAPS HANDED OFF %d | bridge statements %d, "
                                                                     + "BRIDGE ROWS %d | appended %d + 1 held, distinct handled %d, duplicates %d",
                                                             run, terminated, firstMs, attaches, unknown, detaches, waits, handOffs, toReader, gaps, bridges, bridgeRows,
                                                             appended.get(), c.distinct(), c.duplicates()));
                    summarise(db, P5_RESTART, "restart run " + run);
                }
            }
        }
    }


    // ------------------------------------------------------------------ phase 6, unit C (design phase 6 §7)

    private static final String        P6_LATENCY  = "p6-c-latency.txt";
    private static final String        P6_NOTIFY   = "p6-c-notify.txt";
    private static final String        P6_FLUSH    = "p6-c-flush.txt";
    private static final List<Integer> P6_RATES    = ints(System.getProperty("spike.gate.p6Rates", "1,10,100"));
    private static final List<String>  P6_ARMS     = Arrays.stream(System.getProperty("spike.gate.p6Arms", "BACKSTOP_1000,BACKSTOP_100,TIER2,TIER1,TIER12,LOST,TIER1_FORKED")
                                                                 .split(",")).map(String::trim).filter(s -> !s.isEmpty()).toList();
    private static final List<Integer> P6_WRITERS  = ints(System.getProperty("spike.gate.p6Writers", "1,8,20"));
    private static final List<Integer> P6_TYPES    = ints(System.getProperty("spike.gate.p6Types", "1,10,16,50"));
    private static final int           P6_FLUSH_HZ = Integer.getInteger("spike.gate.p6FlushRate", 50);

    /** A latency sample sink per (placement, mode) */
    private static final class Sinks {
        final Map<String, ConcurrentLinkedQueue<Long>> byKey = new ConcurrentHashMap<>();
        final AtomicLong                              total = new AtomicLong();

        void add(String key, long nanos) {
            byKey.computeIfAbsent(key, k -> new ConcurrentLinkedQueue<>()).add(nanos);
            total.incrementAndGet();
        }
    }

    /** 5 FRESH (periodic) and 5 CONTIGUOUS (in-transaction) subscriptions on {@code log}, measured under {@code placement} */
    private static void subscribeTen(EventLog log, String placement, Map<String, Long> committed, Sinks sinks) throws SQLException {
        for (int i = 0; i < 5; i++) {
            log.subscribe(Definitions.definition(placement + "-fresh-" + i, TYPE, "node1", 1, null, null), e -> {
                var at = committed.get(e.aggregateId());
                if (at != null) sinks.add(placement + " FRESH", System.nanoTime() - at);
            });
            log.subscribe(Definitions.definition(placement + "-contiguous-" + i, TYPE, "node1", 1, ConsistencyMode.CONTIGUOUS, null), (page, tx) -> {
                var connection = tx.connection();
                long now = System.nanoTime();
                for (var e : page) {
                    var at = committed.get(e.aggregateId());
                    if (at != null) sinks.add(placement + " CONTIGUOUS", now - at);
                }
            });
        }
    }

    private static void appendTo(EventLog log, Connection connection, String aggregateType, String id) throws SQLException {
        var event = new EventToAppend(UUID.randomUUID(), CAUSE, CORRELATION, EVENT_TYPE, 1, Instant.now(),
                                      "{\"orderId\":\"" + id + "\",\"orderingCustomerId\":\"" + UUID.randomUUID() + "\",\"orderNumber\":" + ThreadLocalRandom.current().nextLong(1_000_000) + "}",
                                      "{\"Key1\":\"Value1\",\"Key2\":\"Value2\"}");
        log.append(connection, AppendRequest.withoutExpectedOrder(aggregateType, id, List.of(event)));
    }

    private static EventLog engine(DataSource dataSource, EventLogSettings settings, Counters observer) throws SQLException {
        var log = EventLog.builder().setDataSource(dataSource).setSettings(settings).setObserver(observer).build();
        log.start();
        log.registerAggregateType(TYPE);
        log.eventTypes().idOf(EVENT_TYPE);
        return log;
    }

    /**
     * §7.1 latency and §7.4 (arm {@code LOST}): one writer at 1, 10 and 100 ev/s, one event per unit of work to a new
     * aggregate; 10 subscriptions (5 FRESH periodic, 5 CONTIGUOUS in-transaction). Arms: {@code BACKSTOP_1000},
     * {@code BACKSTOP_100} (phase 5: no cross-JVM wake-up, no {@code afterCommit}); {@code TIER2} (same engine,
     * {@code afterCommit}, listener off); {@code TIER1} (writer engine W on the writer role, subscriptions in engine S on
     * the reader role, notifier and listener on); {@code TIER12} (10 subscriptions in each engine); {@code LOST} (as
     * TIER1, W's notifier off and no {@code afterCommit}: every notification lost, backstop 1 s); {@code TIER1_FORKED}
     * (as TIER1 with W in a forked JVM, 10 ev/s only).
     */
    @Test
    void wakeLatency() throws Exception {
        var warmUp = scaled(Duration.ofSeconds(10));
        var window = scaled(Duration.ofSeconds(60));
        header(P6_LATENCY, "WAKE LATENCY", "arms " + P6_ARMS + ", rates " + P6_RATES + " ev/s; 5 FRESH periodic + 5 CONTIGUOUS in-transaction per engine; warm-up "
                + warmUp.toSeconds() + " s, window " + window.toSeconds() + " s");
        for (int run = 1; run <= RUNS; run++) {
            for (var arm : P6_ARMS) {
                for (int rate : P6_RATES) {
                    if (arm.equals("TIER1_FORKED") && rate != 10) continue;
                    latencyArm(run, arm, rate, warmUp, window);
                }
            }
        }
    }

    private void latencyArm(int run, String arm, int rate, Duration warmUp, Duration window) throws Exception {
        int     backstop  = arm.equals("BACKSTOP_100") ? 100 : 1000;
        boolean twoEngine = arm.startsWith("TIER1") || arm.equals("LOST");
        boolean crossJvm  = twoEngine;
        boolean callAfter = !arm.startsWith("BACKSTOP") && !arm.equals("LOST");
        boolean forked    = arm.equals("TIER1_FORKED");
        var     settings  = settings(backstop).withCrossJvmWakeUp(crossJvm);
        try (var db = SpikeDb.start("lat_r" + run + "_" + arm.toLowerCase(Locale.ROOT) + "_" + rate + coalescingSuffix(), 100); var app = new App(db, settings, 12)) {
            var committed = new ConcurrentHashMap<String, Long>();
            var sinks     = new Sinks();
            var wObserver = new Counters();
            EventLog writerLog = null;
            if (twoEngine && !forked) {
                writerLog = engine(app.writerWait, settings(backstop).withCrossJvmWakeUp(!arm.equals("LOST")), wObserver);
            }
            var sends    = new ConcurrentHashMap<String, Long>();
            var wireWake = new ConcurrentLinkedQueue<Long>();
            if (writerLog != null) {
                GateSpikeAccess.wakeHooks(writerLog, batch -> batch.forEach(p -> sends.put(p.substring(p.indexOf(':') + 1), System.nanoTime())), p -> {
                });
                GateSpikeAccess.wakeHooks(app.eventLog, batch -> {
                }, p -> {
                    var at = sends.get(p.substring(p.indexOf(':') + 1));
                    if (at != null) wireWake.add(System.nanoTime() - at);
                });
            }
            subscribeTen(app.eventLog, twoEngine ? "other" : "same", committed, sinks);
            if (arm.equals("TIER12")) subscribeTen(writerLog, "same", committed, sinks);
            int subscriptions = arm.equals("TIER12") ? 20 : 10;
            var measuring = new AtomicBoolean();
            var appended  = new AtomicLong();
            ScheduledExecutorService scheduler = null;
            Process child = null;
            if (forked) {
                child = SpikeWriterProcess.start(db, rate, committed, measuring, appended);
            } else {
                var wl = writerLog;
                scheduler = Executors.newSingleThreadScheduledExecutor();
                scheduler.scheduleAtFixedRate(() -> {
                    try (var c = app.writerWait.getConnection()) {
                        var id      = UUID.randomUUID().toString();
                        var measure = measuring.get();
                        c.setAutoCommit(false);
                        appendTo(wl != null ? wl : app.eventLog, c, TYPE, id);
                        if (measure) committed.put(id, System.nanoTime());
                        c.commit();
                        if (measure) {
                            committed.put(id, System.nanoTime());
                            appended.incrementAndGet();
                        }
                        if (callAfter) (wl != null ? wl : app.eventLog).afterCommit(List.of(TYPE));
                    } catch (SQLException e) {
                        throw new IllegalStateException(e);
                    }
                }, 0, 1_000_000 / rate, TimeUnit.MICROSECONDS);
            }
            Thread.sleep(warmUp.toMillis());
            db.resetStatistics();
            app.observer.reset();
            app.observer.resetTotals();
            wireWake.clear();
            long notificationsBefore = GateSpikeAccess.listenerNotifications(app.eventLog);
            long sendsBefore         = writerLog == null ? 0 : GateSpikeAccess.notifierSends(writerLog);
            measuring.set(true);
            Thread.sleep(window.toMillis());
            measuring.set(false);
            var s     = summarise(db, P6_LATENCY, "latency run " + run + ": " + arm + ", " + rate + " ev/s");
            var reads = app.observer.snapshot();
            long notifications = GateSpikeAccess.listenerNotifications(app.eventLog) - notificationsBefore;
            long sent          = writerLog == null ? 0 : GateSpikeAccess.notifierSends(writerLog) - sendsBefore;
            if (scheduler != null) {
                scheduler.shutdown();
                scheduler.awaitTermination(10, TimeUnit.SECONDS);
            }
            if (child != null) SpikeWriterProcess.stop(child);
            long events = appended.get();
            try {
                await(Duration.ofSeconds(30), () -> sinks.total.get() >= events * subscriptions);
            } catch (IllegalStateException e) {
                // the forked arm can miss a sample whose "B" line arrived after the delivery; recorded as the count per key
                SpikeDb.record(P6_LATENCY, "  !! " + arm + " " + rate + " ev/s: " + sinks.total.get() + " of " + events * subscriptions + " samples after 30 s");
            }
            double seconds = window.toMillis() / 1000.0;
            var line = new StringBuilder(String.format("LATENCY run %d | %-12s | %3d ev/s | appended %d |", run, arm, rate, events));
            for (var key : new TreeSet<>(sinks.byKey.keySet())) {
                var l = Latencies.of(sinks.byKey.get(key));
                line.append(String.format(" %s p50 %.2f p99 %.2f max %.1f ms (%d) |", key, l.p50(), l.p99(), l.max(), sinks.byKey.get(key).size()));
            }
            // S's deliveries: the "other" placement in two-engine arms, else every delivery
            long sDeliveries = events * 10;
            var w = Latencies.of(wireWake);
            line.append(String.format(" READER STATEMENTS PER DELIVERY %.3f (non-utility %.3f) | writer statements per event %.2f | WAL %.0f B/event | tail reads/s %.1f, "
                                              + "running-set reads/s %.2f | notifies sent %d (%.2f per commit), received %d | wake send->listener p50 %.2f p99 %.2f ms",
                                      s.calls("reader") / (double) sDeliveries, s.nonUtility("reader") / (double) sDeliveries, s.calls("writer") / (double) Math.max(1, events),
                                      s.wal() / (double) Math.max(1, events), reads.reads() / seconds, reads.combined() / seconds, sent, sent / (double) Math.max(1, events),
                                      notifications, w.p50(), w.p99()));
            SpikeDb.record(P6_LATENCY, line.toString());
            if (writerLog != null) writerLog.stop();
        }
    }

    /**
     * §7.2: 1, 8 and 20 writers, one event per unit of work to a new aggregate, 2 s warm-up and an 8 s window, with
     * {@code fsync} on and off; arms {@code NONE} (no {@code afterCommit}), {@code NOTIFIER} ({@code afterCommit}, the
     * coalesced notifier on the engine's reader-role pool) and {@code DIRECT} (reference: the writer runs
     * {@code pg_notify} on its own connection after commit). A second engine with one subscription listens throughout.
     * A probe samples {@code pg_stat_activity} every 5 ms for backends waiting on {@code Lock:object} (the NOTIFY lock).
     */
    @Test
    void notifyAppendCost() throws Exception {
        var warmUp = scaled(Duration.ofSeconds(2));
        var window = scaled(Duration.ofSeconds(8));
        header(P6_NOTIFY, "NOTIFY APPEND COST", "writers " + P6_WRITERS + ", arms NONE/NOTIFIER/DIRECT, fsync on and off; warm-up " + warmUp.toSeconds() + " s, window " + window.toSeconds() + " s");
        for (boolean fsync : new boolean[]{true, false}) {
            if (!Arrays.asList(P6_FSYNC.split(",")).contains(fsync ? "on" : "off")) continue;
            for (int run = 1; run <= RUNS; run++) {
                for (var arm : Arrays.stream(System.getProperty("spike.gate.p6NotifyArms", "NONE,NOTIFIER,DIRECT").split(",")).map(String::trim).toList()) {
                    for (int writers : P6_WRITERS) {
                        notifyArm(run, fsync, arm, writers, warmUp, window);
                    }
                }
            }
        }
    }

    private void notifyArm(int run, boolean fsync, String arm, int writers, Duration warmUp, Duration window) throws Exception {
        try (var db = SpikeDb.start("ntf_r" + run + "_" + (fsync ? "on" : "off") + "_" + arm.toLowerCase(Locale.ROOT) + "_" + writers + coalescingSuffix(), 100, fsync);
             var app = new App(db, settings(1000), 4)) {
            var listenerPool = db.pool("reader", 4);
            var listening    = engine(listenerPool, settings(1000), new Counters());
            listening.subscribe(Definitions.definition("listening", TYPE, "node1", 1, null, null), e -> {
            });
            var measuring = new AtomicBoolean();
            var stop      = new AtomicBoolean();
            var commits   = new ConcurrentLinkedQueue<Long>();
            var pool      = Executors.newFixedThreadPool(writers);
            for (int w = 0; w < writers; w++) {
                pool.submit(() -> {
                    while (!stop.get()) {
                        try (var c = app.writerPool.getConnection()) {
                            c.setAutoCommit(false);
                            appendTo(app.eventLog, c, TYPE, UUID.randomUUID().toString());
                            long t0 = System.nanoTime();
                            c.commit();
                            long took = System.nanoTime() - t0;
                            if (arm.equals("NOTIFIER")) app.eventLog.afterCommit(List.of(TYPE));
                            if (arm.equals("DIRECT")) {
                                c.setAutoCommit(true);
                                try (var st = c.createStatement()) {
                                    st.execute("SELECT pg_notify('el_wake', 'direct:" + TYPE + "')");
                                }
                            }
                            if (measuring.get()) commits.add(took);
                        } catch (SQLException e) {
                            throw new IllegalStateException(e);
                        }
                    }
                    return null;
                });
            }
            Thread.sleep(warmUp.toMillis());
            db.resetStatistics();
            long sendsBefore   = GateSpikeAccess.notifierSends(app.eventLog);
            long borrowsBefore = app.readerWait.count();
            long cpuBefore     = db.cpuMicros();
            var  lockSamples   = new AtomicLong();
            var  samples       = new AtomicLong();
            var  sampling      = new AtomicBoolean(true);
            var  sampler       = Thread.ofPlatform().start(() -> {
                try (var c = db.connect("writer"); var st = c.prepareStatement("SELECT count(*) FROM pg_stat_activity WHERE wait_event_type = 'Lock' AND wait_event = 'object'")) {
                    while (sampling.get()) {
                        try (var rs = st.executeQuery()) {
                            rs.next();
                            if (rs.getLong(1) > 0) lockSamples.incrementAndGet();
                        }
                        samples.incrementAndGet();
                        Thread.sleep(5);
                    }
                } catch (Exception e) {
                    throw new IllegalStateException(e);
                }
            });
            measuring.set(true);
            Thread.sleep(window.toMillis());
            measuring.set(false);
            long cpu = db.cpuMicros() - cpuBefore;
            sampling.set(false);
            sampler.join();
            stop.set(true);
            pool.shutdown();
            pool.awaitTermination(30, TimeUnit.SECONDS);
            long sends   = GateSpikeAccess.notifierSends(app.eventLog) - sendsBefore;
            long borrows = app.readerWait.count() - borrowsBefore;
            var  c       = Latencies.of(commits);
            double seconds = window.toMillis() / 1000.0;
            double sendMean = Double.NaN, sendMax = Double.NaN;
            try (var st = db.observer.createStatement();
                 var rs = st.executeQuery("SELECT sum(calls), sum(total_exec_time) / nullif(sum(calls), 0), max(max_exec_time) FROM pg_stat_statements WHERE query LIKE '%pg_notify%'")) {
                if (rs.next()) {
                    sendMean = rs.getDouble(2);
                    sendMax = rs.getDouble(3);
                }
            }
            SpikeDb.record(P6_NOTIFY, String.format("NOTIFY run %d | fsync %-3s | %-8s | %2d writer(s) | APPENDS/S %.0f | commit p50 %.3f p99 %.3f max %.1f ms | notifier sends %d "
                                                            + "(%.3f per commit), borrows %.1f/s | pg_notify exec mean %.3f max %.2f ms | Lock:object in %d of %d samples | server CPU %.1f %% of a core",
                                                    run, fsync ? "on" : "off", arm, writers, commits.size() / seconds, c.p50(), c.p99(), c.max(), sends,
                                                    sends / (double) Math.max(1, commits.size()), borrows / seconds, sendMean, sendMax, lockSamples.get(), samples.get(),
                                                    cpu / 10_000.0 / seconds));
            listening.stop();
            listenerPool.close();
        }
    }

    /**
     * §7.3 and §9 decision 2: 1, 10, 16 and 50 aggregate types, one FRESH subscription each; one writer per type commits
     * in lockstep ({@code P6_FLUSH_HZ} times a second), then one {@code afterCommit} names every type, so they are woken
     * together. A long writer per type holds one value below its head throughout, so every type keeps a candidate and
     * wants a running set on every read the probe clock allows. Arms: {@code FLUSH} (the engine as built: one flush for
     * 2-16 due types, one statement each above 16, the combined probe for one), {@code ALONE} (every due type read alone),
     * {@code SINGLE_FLUSH} (1 type only: the two-statement flush instead of the combined probe) and {@code WRITERS}
     * (no subscriptions: the server CPU the writers alone cost, subtracted from the others).
     */
    @Test
    void flushAcrossTypes() throws Exception {
        var warmUp = scaled(Duration.ofSeconds(5));
        var window = scaled(Duration.ofSeconds(30));
        header(P6_FLUSH, "ONE FLUSH ACROSS WOKEN TYPES", "types " + P6_TYPES + ", lockstep " + P6_FLUSH_HZ + "/s, a long writer per type; arms WRITERS/FLUSH/ALONE (+SINGLE_FLUSH at 1 type); warm-up "
                + warmUp.toSeconds() + " s, window " + window.toSeconds() + " s");
        for (int run = 1; run <= RUNS; run++) {
            for (int types : P6_TYPES) {
                var arms = types == 1 ? List.of("WRITERS", "FLUSH", "SINGLE_FLUSH") : List.of("WRITERS", "FLUSH", "ALONE");
                double writersCpu = Double.NaN;
                for (var arm : arms) {
                    var cpu = flushArm(run, types, arm, warmUp, window);
                    if (arm.equals("WRITERS")) writersCpu = cpu;
                    else SpikeDb.record(P6_FLUSH, String.format("   -> run %d, %d type(s), %s: READER SERVER CPU PER WAKE %.1f µs (arm minus WRITERS)", run, types, arm, cpu - writersCpu));
                }
            }
        }
    }

    /** @return server CPU per wake, µs */
    private double flushArm(int run, int types, String arm, Duration warmUp, Duration window) throws Exception {
        try (var db = SpikeDb.start("flush_r" + run + "_" + types + "_" + arm.toLowerCase(Locale.ROOT), 200);
             var app = new App(db, settings(1000).withCrossJvmWakeUp(false), 4)) {
            var names = new ArrayList<String>();
            for (int t = 0; t < types; t++) {
                names.add("Agg" + t);
                app.eventLog.registerAggregateType("Agg" + t);
            }
            GateSpikeAccess.flush(app.eventLog, !arm.equals("ALONE"));
            GateSpikeAccess.singleTypeFlush(app.eventLog, arm.equals("SINGLE_FLUSH"));
            var woken     = new ConcurrentHashMap<String, Long>();
            var latencies = new ConcurrentLinkedQueue<Long>();
            var delivered = new AtomicLong();
            var measuring = new AtomicBoolean();
            if (!arm.equals("WRITERS")) {
                for (var name : names) {
                    app.eventLog.subscribe(Definitions.definition("sub-" + name, name, "node1", 1, null, null), e -> {
                        var at = woken.get(e.aggregateId());
                        if (at != null && measuring.get()) {
                            latencies.add(System.nanoTime() - at);
                            delivered.incrementAndGet();
                        }
                    });
                }
                Thread.sleep(500);
            }
            var holders = new ArrayList<Connection>();
            for (var name : names) {
                var h = db.connect("writer");
                h.setAutoCommit(false);
                appendTo(app.eventLog, h, name, UUID.randomUUID().toString());
                holders.add(h);
            }
            var writers   = Executors.newFixedThreadPool(types);
            var ticks     = new AtomicLong();
            var scheduler = Executors.newSingleThreadScheduledExecutor();
            scheduler.scheduleAtFixedRate(() -> {
                try {
                    var ids   = new ArrayList<String>();
                    var tasks = new ArrayList<Callable<Void>>();
                    for (var name : names) {
                        var id = UUID.randomUUID().toString();
                        ids.add(id);
                        tasks.add(() -> {
                            try (var c = app.writerPool.getConnection()) {
                                c.setAutoCommit(false);
                                appendTo(app.eventLog, c, name, id);
                                c.commit();
                            }
                            return null;
                        });
                    }
                    for (var f : writers.invokeAll(tasks)) f.get();
                    long now = System.nanoTime();
                    ids.forEach(id -> woken.put(id, now));
                    app.eventLog.afterCommit(names);
                    if (measuring.get()) ticks.incrementAndGet();
                } catch (Exception e) {
                    throw new IllegalStateException(e);
                }
            }, 0, 1_000_000 / P6_FLUSH_HZ, TimeUnit.MICROSECONDS);
            Thread.sleep(warmUp.toMillis());
            db.resetStatistics();
            app.observer.reset();
            app.observer.resetTotals();
            long cpuBefore    = db.cpuMicros();
            var  readerBefore = db.backendCpuNanos("reader");
            measuring.set(true);
            Thread.sleep(window.toMillis());
            measuring.set(false);
            long cpu       = db.cpuMicros() - cpuBefore;
            long readerCpu = SpikeDb.cpuBetween(readerBefore, db.backendCpuNanos("reader"));
            var  s   = summarise(db, P6_FLUSH, "flush run " + run + ": " + types + " type(s), " + arm);
            var  o   = app.observer;
            scheduler.shutdown();
            scheduler.awaitTermination(10, TimeUnit.SECONDS);
            writers.shutdown();
            for (var h : holders) {
                h.rollback();
                h.close();
            }
            long wakes = ticks.get();
            var  l     = Latencies.of(latencies);
            double cpuPerWake = cpu / (double) Math.max(1, wakes);
            SpikeDb.record(P6_FLUSH, String.format("FLUSH run %d | %2d type(s) | %-12s | wakes %d | READER BACKENDS' CPU PER WAKE %.1f µs | server CPU per wake %.1f µs (%.1f %% of a core) | reader statements per wake %.2f | "
                                                           + "wake->handler p50 %.2f p99 %.2f max %.1f ms (%d deliveries) | reads %d, flushes %d (multi-type %d, statements %d), "
                                                           + "RUNNING-SET READS PER WAKE %.3f (in flushes %d, combined alone %d) | distinct flush type-lists %d",
                                                   run, types, arm, wakes, readerCpu / 1000.0 / Math.max(1, wakes), cpuPerWake, cpu / 10_000.0 / (window.toMillis() / 1000.0), s.calls("reader") / (double) Math.max(1, wakes),
                                                   l.p50(), l.p99(), l.max(), delivered.get(), o.snapshot().reads(), o.flushes.get(), o.multiFlushes.get(), o.flushStatements.get(),
                                                   (o.runningSetsInFlush.get() + o.combinedAlone.get()) / (double) Math.max(1, wakes), o.runningSetsInFlush.get(), o.combinedAlone.get(),
                                                   o.flushTexts.size()));
            return cpuPerWake;
        }
    }

    // ------------------------------------------------------------------ phase 7, unit C (design phase 7 §10)

    private static final String        P7_TAKEOVER   = "p7-c-takeover.txt";
    private static final String        P7_RENEWAL    = "p7-c-renewal.txt";
    private static final String        P7_UNAFFECTED = "p7-c-unaffected.txt";
    private static final int           P7_REPS       = Integer.getInteger("spike.gate.p7Repetitions", 20);
    private static final List<String>  P7_SHAPES     = Arrays.stream(System.getProperty("spike.gate.p7Shapes", "CRASH,PAUSE,GRACEFUL").split(",")).map(String::trim)
                                                             .filter(s -> !s.isEmpty()).toList();
    private static final int           P7_PAUSE_S    = Integer.getInteger("spike.gate.p7PauseSeconds", 30);
    private static final List<Integer> P7_LEASES     = ints(System.getProperty("spike.gate.p7Leases", "1,10,100,1000"));
    private static final int           P7_IDLE_S     = Integer.getInteger("spike.gate.p7IdleSeconds", 300);
    private static final String        P7_VIEW       = "el_test_view";
    private static final String        P7_LOG        = "el_test_progress_log";

    /** One §10.1 repetition: ms from the kill or stop to the standby's claim and first handled event per model, replays, landed writes */
    record Takeover(double claimMs, double periodicMs, double pagesMs, long periodicReplayed, long pagesRehandled, long pagesDuplicated, long landed, boolean lossSeen) {
    }

    /**
     * §10.1: a forked owner ({@code LeaseOwnerProcess} on the default lease settings: one FRESH periodic and one CONTIGUOUS
     * in-transaction exclusive subscription) and an in-JVM standby with the same two; a writer here at 100 ev/s. Shapes:
     * {@code CRASH} (SIGKILL), {@code PAUSE} (SIGSTOP for 30 s, then SIGCONT) and {@code GRACEFUL} (standard input
     * closed: the child stops its subscriptions, which releases). Each repetition uses subscriber names of its own and
     * starts at the head, waits for the owner to handle 20 events of each, then waits a random 0-4 s so the action falls at
     * a random point of the renewal cycle. Takeover time: from the action to the standby's first handled event, per
     * model ({@code nanoTime}, shared by every process on Linux). Replays: PERIODIC, standby deliveries at or below the
     * owner's last handled event; in-transaction, standby handler calls at or below the owner's last handler call
     * (re-handled, rolled back by the owner) and at or below its last committed view row (duplicated effects). Landed
     * writes: progress-log versions under the owner's fence that committed after the standby's claim (I5; the pause
     * shape's pass line). A fresh container per shape and run.
     */
    @Test
    void takeoverTime() throws Exception {
        header(P7_TAKEOVER, "TAKEOVER TIME", "shapes " + P7_SHAPES + ", " + P7_REPS + " repetitions each, pause " + P7_PAUSE_S
                + " s, default lease settings (15 s / 4 s / 7 s), forked owner, in-JVM standby, writer 100 ev/s");
        for (int run = 1; run <= RUNS; run++) {
            for (var shape : P7_SHAPES) {
                takeoverArm(run, shape);
            }
        }
    }

    private void takeoverArm(int run, String shape) throws Exception {
        try (var db = SpikeDb.start("tko_r" + run + "_" + shape.toLowerCase(Locale.ROOT), 100); var app = new App(db, EventLogSettings.defaults(), 20)) {
            try (var c = db.connect("writer"); var st = c.createStatement()) {
                st.execute("CREATE TABLE " + P7_VIEW + " (global_order bigint NOT NULL, writer text NOT NULL)");
                st.execute("CREATE TABLE " + P7_LOG + " (id bigserial PRIMARY KEY, subscriber text, resume_from bigint, lease_fence bigint, lease_owner text, at timestamptz)");
                st.execute("CREATE OR REPLACE FUNCTION el_test_progress_log_row() RETURNS trigger LANGUAGE plpgsql AS $$ BEGIN INSERT INTO " + P7_LOG
                                   + " (subscriber, resume_from, lease_fence, lease_owner, at) VALUES (NEW.subscriber, NEW.resume_from, NEW.lease_fence, NEW.lease_owner, clock_timestamp()); "
                                   + "RETURN NEW; END $$");
                st.execute("CREATE TRIGGER el_test_progress_log AFTER INSERT OR UPDATE ON el_progress FOR EACH ROW EXECUTE FUNCTION el_test_progress_log_row()");
            }
            var writing   = new AtomicBoolean(true);
            var scheduler = Executors.newSingleThreadScheduledExecutor();
            scheduler.scheduleAtFixedRate(() -> {
                if (!writing.get()) return;
                try {
                    app.appendNewStream(1, false);
                    app.eventLog.afterCommit(List.of(TYPE));
                } catch (Exception e) {
                    // keep the schedule alive
                }
            }, 0, 10_000, TimeUnit.MICROSECONDS);
            var reps = new ArrayList<Takeover>();
            for (int rep = 1; rep <= P7_REPS; rep++) {
                var t = takeoverRep(db, app, shape, rep);
                reps.add(t);
                SpikeDb.record(P7_TAKEOVER, String.format("  rep run %d | %-8s | %2d | claim %.0f ms | first handled PERIODIC %.0f ms, IN_TX %.0f ms | replayed PERIODIC %d | IN_TX re-handled %d, "
                                                                  + "duplicated %d | landed writes by the old owner %d | loss seen %s",
                                                          run, shape, rep, t.claimMs(), t.periodicMs(), t.pagesMs(), t.periodicReplayed(), t.pagesRehandled(), t.pagesDuplicated(),
                                                          t.landed(), t.lossSeen()));
            }
            writing.set(false);
            scheduler.shutdown();
            scheduler.awaitTermination(10, TimeUnit.SECONDS);
            var periodic = reps.stream().mapToDouble(Takeover::periodicMs).sorted().toArray();
            var pages    = reps.stream().mapToDouble(Takeover::pagesMs).sorted().toArray();
            var claims   = reps.stream().mapToDouble(Takeover::claimMs).sorted().toArray();
            SpikeDb.record(P7_TAKEOVER, String.format("TAKEOVER run %d | %-8s | %d reps | to first handled PERIODIC p50 %.0f p100 %.0f ms | IN_TX p50 %.0f p100 %.0f ms | to claim p50 %.0f p100 %.0f ms | "
                                                              + "replayed PERIODIC mean %.1f max %d | IN_TX re-handled mean %.1f max %d, duplicated %d | landed writes %d | loss seen %d of %d",
                                                      run, shape, reps.size(), periodic[(periodic.length - 1) / 2], periodic[periodic.length - 1], pages[(pages.length - 1) / 2],
                                                      pages[pages.length - 1], claims[(claims.length - 1) / 2], claims[claims.length - 1],
                                                      reps.stream().mapToLong(Takeover::periodicReplayed).average().orElse(0), reps.stream().mapToLong(Takeover::periodicReplayed).max().orElse(0),
                                                      reps.stream().mapToLong(Takeover::pagesRehandled).average().orElse(0), reps.stream().mapToLong(Takeover::pagesRehandled).max().orElse(0),
                                                      reps.stream().mapToLong(Takeover::pagesDuplicated).sum(), reps.stream().mapToLong(Takeover::landed).sum(),
                                                      reps.stream().filter(Takeover::lossSeen).count(), reps.size()));
        }
    }

    private Takeover takeoverRep(SpikeDb db, App app, String shape, int rep) throws Exception {
        var node         = "owner-" + rep;
        var periodicName = "tk-p-" + rep;
        var pagesName    = "tk-c-" + rep;
        long head = SpikeDb.queryLong(db.observer, "SELECT coalesce(max(global_order), 0) FROM " + app.tables.eventTable());
        var lines = new ConcurrentLinkedQueue<String[]>();
        var child = GateSpikeAccess.startLeaseOwner(db.jdbcUrl(), db.role("writer"), db.password("writer"), node, periodicName, pagesName, head + 1, lines::add);
        SubscriptionHandle sp = null, sc = null;
        try {
            await(Duration.ofSeconds(60), () -> childFence(lines, periodicName) != null && childFence(lines, pagesName) != null);
            long oldFenceP = childFence(lines, periodicName), oldFenceC = childFence(lines, pagesName);
            var standbyP = new ConcurrentLinkedQueue<long[]>();
            var standbyC = new ConcurrentLinkedQueue<long[]>();
            sp = app.eventLog.subscribe(Definitions.definition(periodicName, TYPE, null, head + 1, null, null), e -> standbyP.add(new long[]{e.globalOrder(), System.nanoTime()}));
            sc = app.eventLog.subscribe(Definitions.definition(pagesName, TYPE, null, head + 1, ConsistencyMode.CONTIGUOUS, null), (page, tx) -> {
                var connection = tx.connection();
                long now = System.nanoTime();
                for (var e : page) standbyC.add(new long[]{e.globalOrder(), now});
            });
            await(Duration.ofSeconds(60), () -> count(lines, "H") >= 20 && count(lines, "P") >= 20);
            Thread.sleep(ThreadLocalRandom.current().nextLong(EventLogSettings.defaults().leaseRenewalInterval().toMillis()));
            long t0 = System.nanoTime();
            switch (shape) {
                case "CRASH" -> {
                    child.destroyForcibly();
                    child.waitFor(30, TimeUnit.SECONDS);
                }
                case "GRACEFUL" -> {
                    child.getOutputStream().close();
                    child.waitFor(60, TimeUnit.SECONDS);
                }
                case "PAUSE" -> signal(child, "STOP");
                default -> throw new IllegalArgumentException(shape);
            }
            await(Duration.ofSeconds(90), () -> !standbyP.isEmpty() && !standbyC.isEmpty());
            var lossSeen = false;
            if (shape.equals("PAUSE")) {
                Thread.sleep(Math.max(0, Duration.ofSeconds(P7_PAUSE_S).toMillis() - Duration.ofNanos(System.nanoTime() - t0).toMillis()));
                signal(child, "CONT");
                try {
                    await(Duration.ofSeconds(30), () -> lost(lines, periodicName) && lost(lines, pagesName));
                    lossSeen = true;
                } catch (IllegalStateException e) {
                    // recorded as not seen
                }
                Thread.sleep(2_000); // anything the resumed owner still had in flight lands, or not, now
                child.getOutputStream().close();
                child.waitFor(60, TimeUnit.SECONDS);
            }
            Long claimedAt = app.observer.claimedAt.get(periodicName);
            long maxChildH = lines.stream().filter(l -> l[0].equals("H")).mapToLong(l -> Long.parseLong(l[1])).max().orElse(0);
            long maxChildP = lines.stream().filter(l -> l[0].equals("P")).mapToLong(l -> Long.parseLong(l[1])).max().orElse(0);
            long committed = SpikeDb.queryLong(db.observer, "SELECT coalesce(max(global_order), 0) FROM " + P7_VIEW + " WHERE writer = '" + node + "'");
            long landed    = landedWrites(db, periodicName, oldFenceP) + landedWrites(db, pagesName, oldFenceC);
            return new Takeover(claimedAt == null ? Double.NaN : (claimedAt - t0) / 1e6,
                                (standbyP.stream().mapToLong(a -> a[1]).min().orElseThrow() - t0) / 1e6,
                                (standbyC.stream().mapToLong(a -> a[1]).min().orElseThrow() - t0) / 1e6,
                                standbyP.stream().filter(a -> a[0] <= maxChildH).count(),
                                standbyC.stream().filter(a -> a[0] <= maxChildP).count(),
                                standbyC.stream().filter(a -> a[0] <= committed).count(),
                                landed, lossSeen);
        } finally {
            if (sp != null) sp.stop();
            if (sc != null) sc.stop();
            if (child.isAlive()) {
                child.destroyForcibly();
                child.waitFor(30, TimeUnit.SECONDS);
            }
        }
    }

    private static Long childFence(Collection<String[]> lines, String subscriber) {
        return lines.stream().filter(l -> l[0].equals("C") && l[1].equals(subscriber)).map(l -> Long.parseLong(l[2])).findFirst().orElse(null);
    }

    private static long count(Collection<String[]> lines, String kind) {
        return lines.stream().filter(l -> l[0].equals(kind)).count();
    }

    private static boolean lost(Collection<String[]> lines, String subscriber) {
        return lines.stream().anyMatch(l -> l[0].equals("L") && l[1].equals(subscriber));
    }

    /** Progress-log versions under {@code oldFence} that committed after the first version under any later fence */
    private static long landedWrites(SpikeDb db, String subscriber, long oldFence) throws SQLException {
        return SpikeDb.queryLong(db.observer, "SELECT count(*) FROM " + P7_LOG + " l WHERE l.subscriber = '" + subscriber + "' AND l.lease_fence = " + oldFence
                + " AND l.id > (SELECT coalesce(min(id), 9223372036854775807) FROM " + P7_LOG + " WHERE subscriber = '" + subscriber + "' AND lease_fence > " + oldFence + ")");
    }

    private static void signal(Process process, String signal) throws Exception {
        if (new ProcessBuilder("kill", "-" + signal, Long.toString(process.pid())).inheritIO().start().waitFor() != 0) {
            throw new IllegalStateException("kill -" + signal + " failed");
        }
    }

    /**
     * §10.2: N exclusive FRESH periodic subscriptions owned by one {@link EventLog}, all idle (no events), for 5 min after
     * every lease was claimed and a 10 s settle. Recorded: {@code LEASE_RENEW} calls, rows, server mean and max
     * ({@code pg_stat_statements}), the keeper's renewal statement time and connection-borrow time on the client (the
     * borrows of the {@code eventlog-lease} thread), WAL per pass ({@code pg_stat_wal}, the LSN, and the statement's own
     * {@code wal_bytes}/{@code wal_fpi}), the HOT ratio on {@code el_progress} over the window, and the table's size.
     */
    @Test
    void renewalCost() throws Exception {
        var window = scaled(Duration.ofSeconds(P7_IDLE_S));
        header(P7_RENEWAL, "RENEWAL COST PER N LEASES", "N " + P7_LEASES + ", idle exclusive FRESH periodic subscriptions, default lease settings, window " + window.toSeconds() + " s");
        for (int run = 1; run <= RUNS; run++) {
            for (int n : P7_LEASES) {
                renewalArm(run, n, window);
            }
        }
    }

    private void renewalArm(int run, int n, Duration window) throws Exception {
        try (var db = SpikeDb.start("ren_r" + run + "_" + n, 100); var app = new App(db, EventLogSettings.defaults(), 20)) {
            long sizeEmpty = SpikeDb.queryLong(db.observer, "SELECT pg_total_relation_size('el_progress')");
            var subscriptions = new ArrayList<SubscriptionHandle>(n);
            for (int i = 0; i < n; i++) {
                subscriptions.add(app.eventLog.subscribe(Definitions.definition("lease-" + i, TYPE, null, 1, null, null), e -> {
                }));
            }
            await(Duration.ofMinutes(5), () -> app.observer.claimedAt.size() >= n);
            Thread.sleep(10_000);
            db.resetStatistics();
            app.observer.resetLease();
            app.readerWait.leaseBorrows.clear();
            var tableBefore = progressTableStats(db);
            long walBefore  = SpikeDb.queryLong(db.observer, "SELECT wal_bytes::bigint FROM pg_stat_wal");
            Thread.sleep(window.toMillis());
            var renewals = new ArrayList<>(app.observer.renewals);
            var borrows  = new ArrayList<>(app.readerWait.leaseBorrows);
            long failed  = app.observer.renewalFailed.get();
            long wal     = db.walSinceReset();
            long walStat = SpikeDb.queryLong(db.observer, "SELECT wal_bytes::bigint FROM pg_stat_wal") - walBefore;
            long renewCalls = 0, renewRows = 0, renewWal = 0, renewFpi = 0, claimCalls = 0, readerCalls = 0;
            double renewMean = Double.NaN, renewMax = Double.NaN;
            try (var st = db.observer.createStatement();
                 var rs = st.executeQuery("SELECT s.query, s.calls, s.rows, s.mean_exec_time, s.max_exec_time, s.wal_bytes::bigint, s.wal_fpi FROM pg_stat_statements s "
                                                  + "JOIN pg_roles r ON r.oid = s.userid WHERE r.rolname = '" + db.role("reader") + "' AND s.dbid = (SELECT oid FROM pg_database WHERE datname = current_database())")) {
                while (rs.next()) {
                    var q = rs.getString(1);
                    readerCalls += rs.getLong(2);
                    if (q.contains("SET lease_until = now()") && !q.contains("nextval")) {
                        renewCalls += rs.getLong(2);
                        renewRows += rs.getLong(3);
                        renewMean = rs.getDouble(4);
                        renewMax = rs.getDouble(5);
                        renewWal += rs.getLong(6);
                        renewFpi += rs.getLong(7);
                    } else if (q.contains("lease_fence = nextval")) {
                        claimCalls += rs.getLong(2);
                    }
                }
            }
            summarise(db, P7_RENEWAL, "renewal run " + run + ", N " + n);
            Thread.sleep(11_000); // backends flush table statistics at most every 10 s when idle; the same lag applies to both snapshots
            var tableAfter = progressTableStats(db);

            long passes = renewals.size();
            var stmt = Latencies.of(renewals.stream().map(r -> r[2]).toList());
            var borrow = Latencies.of(borrows);
            var pass = new ArrayList<Long>();
            for (int i = 0; i < Math.min(renewals.size(), borrows.size()); i++) pass.add(renewals.get(i)[2] + borrows.get(i));
            var p = Latencies.of(pass);
            long minOwned = renewals.stream().mapToLong(r -> r[0]).min().orElse(0), minRenewed = renewals.stream().mapToLong(r -> r[1]).min().orElse(0);
            long upd = tableAfter[0] - tableBefore[0], hot = tableAfter[1] - tableBefore[1];
            SpikeDb.record(P7_RENEWAL, String.format("RENEWAL run %d | N %4d | passes %d (owned min %d, renewed min %d, failed %d) | LEASE_RENEW calls %d (%.2f per pass), rows %d, claims %d | "
                                                             + "server mean %.3f max %.2f ms | client statement p50 %.2f p99 %.2f max %.2f ms | borrow p50 %.3f p99 %.3f max %.2f ms | "
                                                             + "pass p50 %.2f p99 %.2f ms | WAL per pass: pg_stat_wal %.0f B, LSN %.0f B, statement %.0f B (FPI %d) | "
                                                             + "el_progress n_tup_upd %d, HOT %d, HOT ratio %.3f | size %d B empty, %d B before, %d B after, %d B growth | reader statements %d (%.2f/s)",
                                                     run, n, passes, minOwned, minRenewed, failed, renewCalls, renewCalls / (double) Math.max(1, passes), renewRows, claimCalls,
                                                     renewMean, renewMax, stmt.p50(), stmt.p99(), stmt.max(), borrow.p50(), borrow.p99(), borrow.max(), p.p50(), p.p99(),
                                                     walStat / (double) Math.max(1, passes), wal / (double) Math.max(1, passes), renewWal / (double) Math.max(1, renewCalls), renewFpi,
                                                     upd, hot, hot / (double) Math.max(1, upd), sizeEmpty, tableBefore[2], tableAfter[2], tableAfter[2] - tableBefore[2], readerCalls,
                                                     readerCalls / (window.toMillis() / 1000.0)));
            var slow = new StringBuilder();
            for (int i = 0; i < renewals.size(); i++) {
                if (renewals.get(i)[2] > 100_000_000L) slow.append(slow.isEmpty() ? "" : ",").append(i + 1).append('=').append(renewals.get(i)[2] / 1_000_000);
            }
            SpikeDb.record(P7_RENEWAL, String.format("  passes over 100 ms (client statement): %d of %d [pass=ms: %s]", slow.isEmpty() ? 0 : slow.toString().split(",").length,
                                                     renewals.size(), slow));
            var first = new StringBuilder();
            for (int i = 0; i < Math.min(15, renewals.size()); i++) {
                first.append(i == 0 ? "" : ", ").append(String.format("%.1f", renewals.get(i)[2] / 1_000_000.0));
            }
            SpikeDb.record(P7_RENEWAL, String.format("  cold start (unit D): first %d passes in the window, client statement ms [%s] | el_progress analysed %d times by the window's end",
                                                     Math.min(15, renewals.size()), first,
                                                     SpikeDb.queryLong(db.observer, "SELECT analyze_count + autoanalyze_count FROM pg_stat_user_tables WHERE relname = 'el_progress'")));
            subscriptions.forEach(SubscriptionHandle::stop);
        }
    }

    /** {@code el_progress}: n_tup_upd, n_tup_hot_upd, pg_total_relation_size */
    private static long[] progressTableStats(SpikeDb db) throws SQLException {
        try (var st = db.observer.createStatement();
             var rs = st.executeQuery("SELECT n_tup_upd, n_tup_hot_upd, pg_total_relation_size('el_progress') FROM pg_stat_user_tables WHERE relname = 'el_progress'")) {
            rs.next();
            return new long[]{rs.getLong(1), rs.getLong(2), rs.getLong(3)};
        }
    }

    /**
     * §10.3: phase-6 §7.1's steady shape at 100 ev/s (one engine, {@code afterCommit}, tier 1 off: phase 6's
     * {@code TIER2} arm; 5 FRESH periodic and 5 CONTIGUOUS in-transaction subscriptions, 10 s warm-up, 60 s window),
     * arms {@code NONEXCLUSIVE} (phase 6 baseline) and {@code EXCLUSIVE}; and phase-6 §7.2's append shape at 8 writers
     * with {@code fsync=on} (2 s warm-up, 8 s window, {@code afterCommit} with the default notifier, a second engine with
     * one listening FRESH subscription, non-exclusive or exclusive). {@code pg_stat_activity} is sampled every 5 ms for
     * backends waiting on a heavyweight lock, and those whose statement touches {@code el_progress} are counted apart.
     */
    @Test
    void leaseUnaffected() throws Exception {
        var warmUp = scaled(Duration.ofSeconds(10));
        var window = scaled(Duration.ofSeconds(60));
        header(P7_UNAFFECTED, "APPEND AND DELIVERY UNAFFECTED", "steady 100 ev/s, 5 FRESH periodic + 5 CONTIGUOUS in-transaction, warm-up " + warmUp.toSeconds() + " s, window "
                + window.toSeconds() + " s; append 8 writers fsync=on 2 s / 8 s; arms NONEXCLUSIVE/EXCLUSIVE");
        for (int run = 1; run <= RUNS; run++) {
            for (var exclusive : run % 2 == 1 ? new boolean[]{false, true} : new boolean[]{true, false}) {
                unaffectedSteady(run, exclusive, warmUp, window);
            }
        }
        for (int run = 1; run <= RUNS; run++) {
            for (var exclusive : run % 2 == 1 ? new boolean[]{false, true} : new boolean[]{true, false}) {
                unaffectedAppend(run, exclusive, scaled(Duration.ofSeconds(2)), scaled(Duration.ofSeconds(8)));
            }
        }
    }

    private static SubscriptionHandle subscribe(EventLog log, String subscriber, boolean exclusive, PeriodicHandler handler) throws SQLException {
        return exclusive ? log.subscribe(Definitions.definition(subscriber, TYPE, null, 1, null, null), handler) : log.subscribe(Definitions.definition(subscriber, TYPE, "node1", 1, null, null), handler);
    }

    private static SubscriptionHandle subscribe(EventLog log, String subscriber, boolean exclusive, PageHandler handler) throws SQLException {
        return exclusive ? log.subscribe(Definitions.definition(subscriber, TYPE, null, 1, ConsistencyMode.CONTIGUOUS, null), handler)
                         : log.subscribe(Definitions.definition(subscriber, TYPE, "node1", 1, ConsistencyMode.CONTIGUOUS, null), handler);
    }

    /** Samples {@code pg_stat_activity} every 5 ms: [samples, samples with a Lock wait, samples with a Lock wait on an el_progress statement] */
    private static final class LockSampler implements AutoCloseable {
        final AtomicLong samples = new AtomicLong(), anyLock = new AtomicLong(), progressLock = new AtomicLong();
        private final AtomicBoolean running = new AtomicBoolean(true);
        private final Thread        thread;

        LockSampler(SpikeDb db) {
            var sql = "SELECT count(*), count(*) FILTER (WHERE query ILIKE '%el_progress%') FROM pg_stat_activity WHERE wait_event_type = 'Lock' AND usename IN ('"
                    + db.role("writer") + "', '" + db.role("reader") + "')";
            thread = Thread.ofPlatform().name("lock-sampler").start(() -> {
                try (var st = db.observer.prepareStatement(sql)) {
                    while (running.get()) {
                        try (var rs = st.executeQuery()) {
                            rs.next();
                            if (rs.getLong(1) > 0) anyLock.incrementAndGet();
                            if (rs.getLong(2) > 0) progressLock.incrementAndGet();
                        }
                        samples.incrementAndGet();
                        Thread.sleep(5);
                    }
                } catch (Exception e) {
                    e.printStackTrace();
                }
            });
        }

        @Override
        public void close() throws InterruptedException {
            running.set(false);
            thread.join();
        }
    }

    private void unaffectedSteady(int run, boolean exclusive, Duration warmUp, Duration window) throws Exception {
        var arm      = exclusive ? "EXCLUSIVE" : "NONEXCLUSIVE";
        var settings = settings(1000).withCrossJvmWakeUp(false);
        try (var db = SpikeDb.start("una_r" + run + "_" + arm.toLowerCase(Locale.ROOT), 100); var app = new App(db, settings, 12)) {
            var committed = new ConcurrentHashMap<String, Long>();
            var sinks     = new Sinks();
            for (int i = 0; i < 5; i++) {
                subscribe(app.eventLog, "fresh-" + i, exclusive, e -> {
                    var at = committed.get(e.aggregateId());
                    if (at != null) sinks.add("FRESH", System.nanoTime() - at);
                });
                subscribe(app.eventLog, "contiguous-" + i, exclusive, (page, tx) -> {
                    var connection = tx.connection();
                    long now = System.nanoTime();
                    for (var e : page) {
                        var at = committed.get(e.aggregateId());
                        if (at != null) sinks.add("CONTIGUOUS", now - at);
                    }
                });
            }
            var measuring = new AtomicBoolean();
            var appended  = new AtomicLong();
            var scheduler = Executors.newSingleThreadScheduledExecutor();
            scheduler.scheduleAtFixedRate(() -> {
                try (var c = app.writerWait.getConnection()) {
                    var id      = UUID.randomUUID().toString();
                    var measure = measuring.get();
                    c.setAutoCommit(false);
                    appendTo(app.eventLog, c, TYPE, id);
                    if (measure) committed.put(id, System.nanoTime());
                    c.commit();
                    if (measure) {
                        committed.put(id, System.nanoTime());
                        appended.incrementAndGet();
                    }
                    app.eventLog.afterCommit(List.of(TYPE));
                } catch (SQLException e) {
                    throw new IllegalStateException(e);
                }
            }, 0, 10_000, TimeUnit.MICROSECONDS);
            Thread.sleep(warmUp.toMillis());
            db.resetStatistics();
            app.observer.resetLease();
            Summary s;
            LockSampler locks;
            try (var sampler = new LockSampler(db)) {
                locks = sampler;
                measuring.set(true);
                Thread.sleep(window.toMillis());
                measuring.set(false);
                s = summarise(db, P7_UNAFFECTED, "steady run " + run + ": " + arm);
            }
            long renewals = app.observer.renewals.size();
            scheduler.shutdown();
            scheduler.awaitTermination(10, TimeUnit.SECONDS);
            long events = appended.get();
            try {
                await(Duration.ofSeconds(30), () -> sinks.total.get() >= events * 10);
            } catch (IllegalStateException e) {
                SpikeDb.record(P7_UNAFFECTED, "  !! " + arm + ": " + sinks.total.get() + " of " + events * 10 + " samples after 30 s");
            }
            long leaseStatements = 0, idleTimeout = 0;
            for (var st : db.statements()) {
                if (!st.role().equals("reader")) continue;
                if (st.query().contains("lease_until = now()")) leaseStatements += st.calls();
                if (st.query().contains("idle_in_transaction_session_timeout")) idleTimeout += st.calls();
            }
            double seconds = window.toMillis() / 1000.0;
            var line = new StringBuilder(String.format("STEADY run %d | %-12s | 100 ev/s | appended %d |", run, arm, events));
            for (var key : new TreeSet<>(sinks.byKey.keySet())) {
                var l = Latencies.of(sinks.byKey.get(key));
                line.append(String.format(" %s p50 %.2f p99 %.2f max %.1f ms (%d) |", key, l.p50(), l.p99(), l.max(), sinks.byKey.get(key).size()));
            }
            long deliveries = events * 10;
            line.append(String.format(" READER STATEMENTS PER DELIVERY %.3f (non-utility %.3f) | lease statements %d (%.3f/s), renewal passes %d | page idle-timeout statements %d (%.3f per delivery) | "
                                              + "writer statements per event %.2f | WAL %.0f B/event | Lock waits in %d of %d samples, on el_progress statements %d",
                                      s.calls("reader") / (double) deliveries, s.nonUtility("reader") / (double) deliveries, leaseStatements, leaseStatements / seconds, renewals,
                                      idleTimeout, idleTimeout / (double) Math.max(1, deliveries), s.calls("writer") / (double) Math.max(1, events), s.wal() / (double) Math.max(1, events),
                                      locks.anyLock.get(), locks.samples.get(), locks.progressLock.get()));
            SpikeDb.record(P7_UNAFFECTED, line.toString());
        }
    }

    private void unaffectedAppend(int run, boolean exclusive, Duration warmUp, Duration window) throws Exception {
        var arm = exclusive ? "EXCLUSIVE" : "NONEXCLUSIVE";
        int writers = 8;
        try (var db = SpikeDb.start("unaa_r" + run + "_" + arm.toLowerCase(Locale.ROOT), 100, true); var app = new App(db, settings(1000), 4)) {
            var listenerPool = db.pool("reader", 4);
            var listening    = engine(listenerPool, settings(1000), new Counters());
            subscribe(listening, "listening", exclusive, e -> {
            });
            var measuring = new AtomicBoolean();
            var stop      = new AtomicBoolean();
            var commits   = new ConcurrentLinkedQueue<Long>();
            var pool      = Executors.newFixedThreadPool(writers);
            for (int w = 0; w < writers; w++) {
                pool.submit(() -> {
                    while (!stop.get()) {
                        try (var c = app.writerPool.getConnection()) {
                            c.setAutoCommit(false);
                            appendTo(app.eventLog, c, TYPE, UUID.randomUUID().toString());
                            long t0 = System.nanoTime();
                            c.commit();
                            long took = System.nanoTime() - t0;
                            app.eventLog.afterCommit(List.of(TYPE));
                            if (measuring.get()) commits.add(took);
                        } catch (SQLException e) {
                            throw new IllegalStateException(e);
                        }
                    }
                    return null;
                });
            }
            Thread.sleep(warmUp.toMillis());
            db.resetStatistics();
            LockSampler locks;
            try (var sampler = new LockSampler(db)) {
                locks = sampler;
                measuring.set(true);
                Thread.sleep(window.toMillis());
                measuring.set(false);
            }
            stop.set(true);
            pool.shutdown();
            pool.awaitTermination(30, TimeUnit.SECONDS);
            var c = Latencies.of(commits);
            double seconds = window.toMillis() / 1000.0;
            SpikeDb.record(P7_UNAFFECTED, String.format("APPEND run %d | fsync on | %-12s | %d writers | APPENDS/S %.0f | commit p50 %.3f p99 %.3f max %.1f ms | Lock waits in %d of %d samples, on el_progress statements %d",
                                                        run, arm, writers, commits.size() / seconds, c.p50(), c.p99(), c.max(), locks.anyLock.get(), locks.samples.get(),
                                                        locks.progressLock.get()));
            listening.stop();
            listenerPool.close();
        }
    }

    // ------------------------------------------------------------------ harness

    private static EventLogSettings settings(int backstopMillis) {
        var backstop = Duration.ofMillis(backstopMillis);
        // the back-off floor follows the backstop, as withBackstopInterval does when it is raised
        var settings = EventLogSettings.defaults().withProbeBackoff(backstop, Duration.ofSeconds(10)).withBackstopInterval(backstop);
        if (WOKEN_READ_MIN != null) settings = settings.withWokenReadMin(Duration.ofMillis(Long.parseLong(WOKEN_READ_MIN)));
        if (NOTIFY_SEND_MIN != null) settings = settings.withNotifySendMin(Duration.ofMillis(Long.parseLong(NOTIFY_SEND_MIN)));
        return settings;
    }

    // ---- phase 6 unit D (§10.8): the coalescing intervals, in ms; absent = the defaults ----
    private static final String  WOKEN_READ_MIN  = System.getProperty("spike.gate.p6WokenReadMin");
    private static final String  NOTIFY_SEND_MIN = System.getProperty("spike.gate.p6NotifySendMin");
    /** {@code on}, {@code off} or {@code on,off} (the default): the fsync settings §7.2 runs */
    private static final String  P6_FSYNC        = System.getProperty("spike.gate.p6Fsync", "on,off");

    /** A database-name suffix per coalescing setting, so arms with different intervals never share a permit key */
    private static String coalescingSuffix() {
        return (WOKEN_READ_MIN == null ? "" : "_r" + WOKEN_READ_MIN) + (NOTIFY_SEND_MIN == null ? "" : "_n" + NOTIFY_SEND_MIN);
    }

    /**
     * One application instance: a writer pool and an event log on a reader pool, as separate roles; or ({@code shared})
     * one pool of the writer role for both, as an application with one {@code DataSource} runs (phase 5 §8.2)
     */
    private static final class App implements AutoCloseable {
        final HikariDataSource    writerPool;
        final HikariDataSource    readerPool;
        final TimedDataSource     readerWait;
        final TimedDataSource     writerWait;
        final EventLog            eventLog;
        final AggregateTypeTables tables;
        final Counters            observer = new Counters();
        private final boolean     shared;

        App(SpikeDb db, EventLogSettings settings, int readerPoolSize) throws SQLException {
            this(db, settings, readerPoolSize, false);
        }

        App(SpikeDb db, EventLogSettings settings, int readerPoolSize, boolean shared) throws SQLException {
            this.shared = shared;
            writerPool = db.pool("writer", shared ? readerPoolSize : WRITER_POOL);
            readerPool = shared ? writerPool : db.pool("reader", readerPoolSize);
            readerWait = new TimedDataSource(readerPool);
            writerWait = new TimedDataSource(writerPool);
            eventLog = EventLog.builder().setDataSource(readerWait).setSettings(settings).setObserver(observer).build();
            eventLog.start();
            tables = eventLog.registerAggregateType(TYPE);
            eventLog.eventTypes().idOf(EVENT_TYPE);
        }

        String appendNewStream(int events, boolean expectedOrder) throws SQLException {
            return appendNewStream(UUID.randomUUID().toString(), events, expectedOrder);
        }

        String appendNewStream(String id, int events, boolean expectedOrder) throws SQLException {
            return appendNewStream(id, events, expectedOrder, EVENT_TYPE);
        }

        String appendNewStream(String id, int events, boolean expectedOrder, String eventType) throws SQLException {
            try (var c = writerWait.getConnection()) {
                c.setAutoCommit(false);
                append(c, id, events, expectedOrder, eventType);
                c.commit();
            }
            return id;
        }

        void append(Connection connection, String id, int count, boolean expectedOrder) throws SQLException {
            append(connection, id, count, expectedOrder, EVENT_TYPE);
        }

        void append(Connection connection, String id, int count, boolean expectedOrder, String eventType) throws SQLException {
            var events = new ArrayList<EventToAppend>(count);
            for (int i = 0; i < count; i++) {
                events.add(new EventToAppend(UUID.randomUUID(), CAUSE, CORRELATION, eventType, 1, Instant.now(),
                                             "{\"orderId\":\"" + id + "\",\"orderingCustomerId\":\"" + UUID.randomUUID() + "\",\"orderNumber\":" + ThreadLocalRandom.current().nextLong(1_000_000) + "}",
                                             "{\"Key1\":\"Value1\",\"Key2\":\"Value2\"}"));
            }
            eventLog.append(connection, expectedOrder ? AppendRequest.afterEventOrder(TYPE, id, AppendRequest.NEW_STREAM, events)
                                                      : AppendRequest.withoutExpectedOrder(TYPE, id, events));
        }

        @Override
        public void close() {
            eventLog.stop();
            if (!shared) readerPool.close();
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

        private final AtomicLong count          = new AtomicLong();

        long count() {
            return count.get();
        }

        /** Phase 7 §10.2: the borrows of the lease keeper's thread, ns */
        final ConcurrentLinkedQueue<Long> leaseBorrows = new ConcurrentLinkedQueue<>();

        private Connection timed(long started, Connection connection) {
            count.incrementAndGet();
            long waited = System.nanoTime() - started;
            if ("eventlog-lease".equals(Thread.currentThread().getName())) leaseBorrows.add(waited);
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
        // phase 7: leases
        final Map<String, Long>                 claimedAt     = new ConcurrentHashMap<>();
        final ConcurrentLinkedQueue<long[]>     renewals      = new ConcurrentLinkedQueue<>(); // owned, renewed, nanos
        final AtomicLong                        renewalFailed = new AtomicLong();

        void resetLease() {
            renewals.clear();
            renewalFailed.set(0);
        }

        @Override
        public void onLeaseClaimed(String subscriber, String aggregateType, long fence) {
            claimedAt.put(subscriber, System.nanoTime());
        }

        @Override
        public void onRenewal(int owned, int renewed, long nanos) {
            renewals.add(new long[]{owned, renewed, nanos});
        }

        @Override
        public void onRenewalFailed(Throwable cause) {
            renewalFailed.incrementAndGet();
        }

        // delivery thread only (one subscription)
        private          String pageKind;          // "catch-up", "bridge" or null (an inbox batch)
        private          long   pageRows;
        private          long   pageHandled;
        private volatile long   catchUpPages, catchUpRows, catchUpDropped, bridgePages, bridgeRows, bridgeDropped, batches, batchRows;

        // phase 4: totals that only resetTotals() clears, and the last resume point per subscriber
        final         AtomicLong        pagesCommitted = new AtomicLong();
        final         AtomicLong        pageEvents     = new AtomicLong();
        final         AtomicLong        pagesFailed    = new AtomicLong();
        final         AtomicLong        parkedHeads    = new AtomicLong();
        final         AtomicLong        parkedBlocked  = new AtomicLong();
        final         AtomicLong        catchUpReads   = new AtomicLong();
        final         AtomicLong        bridgeReads    = new AtomicLong();
        final         AtomicLong        bridgeReadRows = new AtomicLong();
        final         AtomicLong        releasedRows   = new AtomicLong();
        final         AtomicLong        maxInbox       = new AtomicLong();
        final         AtomicLong        maxHeldBack    = new AtomicLong();
        final         AtomicLong        maxHoles       = new AtomicLong();
        final         AtomicLong        totalAttaches  = new AtomicLong();
        private final Map<String, Long> resumeFrom     = new ConcurrentHashMap<>();
        // phase 5: every subscription, cleared by resetTotals()
        final         AtomicLong        permits          = new AtomicLong();
        final         AtomicLong        maxPermitWait    = new AtomicLong();
        final         AtomicLong        maxInUse         = new AtomicLong();
        final         AtomicLong        filteredReads    = new AtomicLong();
        final         AtomicLong        indexReads       = new AtomicLong();
        final         AtomicLong        catchUpRowsAll   = new AtomicLong();
        final         AtomicLong        unknownAttaches  = new AtomicLong();
        final         AtomicLong        handOffs         = new AtomicLong();
        final         AtomicLong        handOffsToReader = new AtomicLong();
        final         AtomicLong        gapsHandedOff    = new AtomicLong();
        final         AtomicLong        watermarkWaits   = new AtomicLong();
        final         Map<String, Long> detachReasons    = new ConcurrentHashMap<>();
        private final Map<String, Long> firstAttach      = new ConcurrentHashMap<>();
        // phase 6: every flush, cleared by resetTotals()
        final         AtomicLong        flushes            = new AtomicLong();
        final         AtomicLong        multiFlushes       = new AtomicLong();
        final         AtomicLong        flushStatements    = new AtomicLong();
        final         AtomicLong        runningSetsInFlush = new AtomicLong();
        final         AtomicLong        combinedAlone      = new AtomicLong();
        final         Set<List<String>> flushTexts         = ConcurrentHashMap.newKeySet();
        private volatile boolean        lastFlushAlone;

        void resetTotals() {
            for (var c : List.of(pagesCommitted, pageEvents, pagesFailed, parkedHeads, parkedBlocked, catchUpReads, bridgeReads, bridgeReadRows, releasedRows, maxInbox,
                                 maxHeldBack, maxHoles, totalAttaches, permits, maxPermitWait, maxInUse, filteredReads, indexReads, catchUpRowsAll, unknownAttaches,
                                 handOffs, handOffsToReader, gapsHandedOff, watermarkWaits, flushes, multiFlushes, flushStatements, runningSetsInFlush, combinedAlone)) {
                c.set(0);
            }
            detachReasons.clear();
            firstAttach.clear();
            flushTexts.clear();
        }

        @Override
        public void onFlush(List<String> aggregateTypes, int statements, long nanos) {
            flushes.incrementAndGet();
            flushStatements.addAndGet(statements);
            // a single type read alone reports one statement (the combined probe or a tail); a flush counts its running set as a statement
            lastFlushAlone = aggregateTypes.size() == 1 && statements == 1;
            if (!lastFlushAlone) {
                if (aggregateTypes.size() > 1) multiFlushes.incrementAndGet();
                runningSetsInFlush.addAndGet(Math.max(0, statements - aggregateTypes.size()));
                flushTexts.add(List.copyOf(aggregateTypes));
            }
        }

        /** @return when the subscriber first attached since the last {@link #resetTotals()} ({@code System.nanoTime()}), 0 if not yet */
        long firstAttach(String subscriber) {
            return firstAttach.getOrDefault(subscriber, 0L);
        }

        @Override
        public void onCatchUpPermit(String subscriber, String aggregateType, long waitNanos, int inUse) {
            permits.incrementAndGet();
            maxPermitWait.accumulateAndGet(waitNanos, Math::max);
            maxInUse.accumulateAndGet(inUse, Math::max);
        }

        @Override
        public void onFilteredRead(String subscriber, String aggregateType, long scannedOrders, int rows, long window, boolean usedIndex) {
            filteredReads.incrementAndGet();
            if (usedIndex) indexReads.incrementAndGet();
        }

        @Override
        public void onGapsHandedOff(String subscriber, String aggregateType, int gaps, boolean toReader) {
            handOffs.incrementAndGet();
            if (toReader) handOffsToReader.incrementAndGet();
            gapsHandedOff.addAndGet(gaps);
        }

        @Override
        public void onWaitingForWatermark(String subscriber, String aggregateType, boolean waiting, Set<String> heldBy) {
            if (waiting) watermarkWaits.incrementAndGet();
        }

        long resumeFrom(String subscriber) {
            return resumeFrom.getOrDefault(subscriber, 0L);
        }

        @Override
        public void onRead(String aggregateType, boolean isCombined, long nanos, int rows, int outstandingHoles, int outstandingCandidates, Duration oldestCandidateAge) {
            reads.incrementAndGet();
            if (isCombined) combined.incrementAndGet();
            if (isCombined && lastFlushAlone) combinedAlone.incrementAndGet();
            maxHoles.accumulateAndGet(outstandingHoles, Math::max);
        }

        @Override
        public void onProgressSaved(String subscriber, String aggregateType, long resumeFrom) {
            saves.incrementAndGet();
            this.resumeFrom.merge(subscriber, resumeFrom, Math::max);
        }

        @Override
        public void onAttach(String subscriber, String aggregateType, long cursor, long watermark) {
            attaches.incrementAndGet();
            totalAttaches.incrementAndGet();
            firstAttach.putIfAbsent(subscriber, System.nanoTime());
            if (watermark == Long.MIN_VALUE) unknownAttaches.incrementAndGet();
        }

        @Override
        public void onDetach(String subscriber, String aggregateType, String reason, long resumeFrom) {
            finishPage();
            detaches.merge(reason, 1L, Long::sum);
            detachReasons.merge(reason, 1L, Long::sum);
        }

        @Override
        public void onCatchUpRead(String subscriber, String aggregateType, boolean bridge, long nanos, int rows) {
            finishPage();
            pageKind = bridge ? "bridge" : "catch-up";
            pageRows = rows;
            pageHandled = 0;
            catchUpRowsAll.addAndGet(rows);
            if (bridge) {
                bridgeReads.incrementAndGet();
                bridgeReadRows.addAndGet(rows);
            } else {
                catchUpReads.incrementAndGet();
            }
        }

        @Override
        public void onBatchTaken(String subscriber, String aggregateType, int rows, int inboxEvents) {
            finishPage();
            batches++;
            batchRows += rows;
            maxInbox.accumulateAndGet(inboxEvents, Math::max);
        }

        @Override
        public void onPageCommitted(String subscriber, String aggregateType, int size) {
            pagesCommitted.incrementAndGet();
            pageEvents.addAndGet(size);
        }

        @Override
        public void onPageFailed(String subscriber, String aggregateType, int size, Throwable cause) {
            pagesFailed.incrementAndGet();
        }

        @Override
        public void onReleased(String subscriber, String aggregateType, int rows, long receivedWatermark, int heldBack) {
            releasedRows.addAndGet(rows);
            maxHeldBack.accumulateAndGet(heldBack, Math::max);
        }

        @Override
        public void onParked(String subscriber, String aggregateType, long globalOrder, Long blockedBy, int attempts) {
            (blockedBy == null ? parkedHeads : parkedBlocked).incrementAndGet();
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
