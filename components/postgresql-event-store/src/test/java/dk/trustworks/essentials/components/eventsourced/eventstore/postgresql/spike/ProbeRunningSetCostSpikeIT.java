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

import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.testcontainers.postgresql.PostgreSQLContainer;

import java.sql.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import java.util.concurrent.locks.LockSupport;

import static dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.spike.SpikePostgres.*;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * Spike P0.12 of {@code docs/plans/event-store-subscription-redesign.md} (§4.10, §6, §7.3): what the combined probe's
 * {@code pg_stat_activity} read costs as the number of connections to the cluster grows, and what probing at a high
 * rate does to concurrent appends.
 * <p>
 * Reading {@code pg_stat_activity} makes the backend copy the status entry of every live backend into a
 * transaction-local snapshot ({@code pgstat_read_current_status}), scanning every slot ({@code max_connections} plus
 * auxiliary processes) and allocating {@code track_activity_query_size} bytes per slot for the query texts, then looks
 * up each live backend's xid. The cost therefore scales with connections and {@code max_connections}, not with
 * writers. Many JVMs each with a Hikari pool means 100-500 connections in one cluster.
 * <p>
 * Per level of open client connections (10, 100, 400; idle, having run one {@code SELECT 1}), on a server with
 * {@code max_connections=500} (and, for the slot-scan part, 50 and 2 000 at fewer levels):
 * <ul>
 *     <li><b>Cost cells</b>: each statement {@code spike.probecost.iterations} times back to back on one connection:
 *     client p50/p99, server execution per call ({@code pg_stat_statements}), server CPU per call (container cgroup,
 *     idle CPU of the same duration subtracted). Statements: the running-set sub-select alone (as rows and in the
 *     probe's exact {@code array_agg} form), the combined probe (P0.11's {@code UNION ALL} form, 0 and 10 holes) and
 *     the same tail read without the sub-select, and alternatives: {@code pg_stat_get_activity(NULL)} without the
 *     view's joins, {@code pg_stat_get_backend_idset()} (no xid, the copy alone), {@code pg_snapshot_xip(pg_current_snapshot())}
 *     (rejected for correctness in §4.4; cost reference only), {@code pg_locks} transaction-id locks (reference), the
 *     sub-select under each {@code stats_fetch_consistency}, after {@code pg_stat_clear_snapshot()} inside a
 *     transaction, and a second read inside a transaction (the per-transaction cache, unsafe per P0.2).</li>
 *     <li><b>Active cost cells</b>: a subset of the same while 20% of the connections are writers appending one event
 *     per autocommit transaction, as fast as they can.</li>
 *     <li><b>Append windows</b>: appends per second of the same writers with no prober, one prober every 1 ms, and N
 *     probers each every 10 ms (N = connections / 10, as if every JVM had a pool of 10 and one reader); each prober
 *     runs the combined probe or, as the control, the same tail read without the sub-select. Palindrome order.</li>
 * </ul>
 * Results: {@code target/spike-results/p0-12-probe-cost.txt}. Throwaway: runs only with {@code -Dspike.run=true}.
 */
@Tag("spike")
@EnabledIfSystemProperty(named = "spike.run", matches = "true")
class ProbeRunningSetCostSpikeIT {
    private static final String RESULTS = "p0-12-probe-cost.txt";

    private static final String     IMAGE          = System.getProperty("spike.probecost.image", "postgres:17.5");
    private static final List<Integer> LEVELS      = ints("spike.probecost.levels", "10,100,400");
    private static final int        ITERATIONS     = Integer.getInteger("spike.probecost.iterations", 2000);
    private static final int        ACTIVE_ITER    = Integer.getInteger("spike.probecost.activeIterations", 1000);
    private static final int        WINDOW_SECONDS = Integer.getInteger("spike.probecost.windowSeconds", 6);
    private static final int        PRELOAD_ROWS   = Integer.getInteger("spike.probecost.rows", 100_000);
    private static final double     WRITER_SHARE   = Double.parseDouble(System.getProperty("spike.probecost.writerShare", "0.2"));
    private static final boolean    APPENDS        = Boolean.parseBoolean(System.getProperty("spike.probecost.appends", "true"));
    private static final int        LIMIT          = 1000;
    private static final String     TABLE_NAME     = "spike_probe_cost";

    /** Containers: max_connections and the connection levels each one runs (the main one runs everything). */
    private static final int              MAIN_MAX_CONNECTIONS = 500;
    private static final Map<Integer, List<Integer>> SLOT_SWEEP = Map.of(50, List.of(10), 2000, List.of(10, 400));

    private static final String RUNNING  = "(SELECT array_agg(backend_xid::text) FROM pg_stat_activity WHERE backend_xid IS NOT NULL)";
    private static final String COLUMNS  = "e.global_order, e.aggregate_id, e.event_order, e.event_id, e.caused_by_event_id, e.correlation_id, e.event_type, "
            + "e.event_revision, e.timestamp, e.event_payload, e.event_metadata, e.tenant";

    private static List<Integer> ints(String property, String defaults) {
        return Arrays.stream(System.getProperty(property, defaults).split(",")).map(String::trim).filter(s -> !s.isEmpty()).map(Integer::valueOf).toList();
    }

    // ------------------------------------------------------------------------------------------------------------------
    // Statements
    // ------------------------------------------------------------------------------------------------------------------

    enum Kind {PLAIN, PROBE, TXN_CLEAR, TXN_CACHED}

    /**
     * One measured statement. PROBE statements take (cursor, limit, holes) and return rows from the event table;
     * {@code setting} is a {@code SET} run on the measuring connection first, reset after.
     */
    record Stmt(String label, Kind kind, String sql, int holes, String setting, boolean active) {
        String tagged() {
            return "/*p012 " + label + "*/ " + sql;
        }
    }

    private static String probe(boolean running) {
        var from = " FROM " + TABLE_NAME + " e ";
        return "SELECT u.*" + (running ? ", " + RUNNING + " AS running" : "") + " FROM ((SELECT " + COLUMNS + from + "WHERE e.global_order > ? ORDER BY e.global_order LIMIT ?)"
                + " UNION ALL (SELECT " + COLUMNS + from + "WHERE e.global_order = ANY(?))) u ORDER BY u.global_order";
    }

    private static final String SUB_AGG = "SELECT array_agg(backend_xid::text) FROM pg_stat_activity WHERE backend_xid IS NOT NULL";

    static List<Stmt> statements() {
        return List.of(
                new Stmt("SELECT1", Kind.PLAIN, "SELECT 1", 0, null, true),
                new Stmt("SUB_ROWS", Kind.PLAIN, "SELECT backend_xid FROM pg_stat_activity WHERE backend_xid IS NOT NULL", 0, null, false),
                new Stmt("SUB_AGG", Kind.PLAIN, SUB_AGG, 0, null, true),
                new Stmt("SRF_AGG", Kind.PLAIN, "SELECT array_agg(backend_xid::text) FROM pg_stat_get_activity(NULL) WHERE backend_xid IS NOT NULL", 0, null, true),
                new Stmt("IDSET", Kind.PLAIN, "SELECT count(pg_stat_get_backend_pid(i)) FROM pg_stat_get_backend_idset() i", 0, null, false),
                new Stmt("XIP", Kind.PLAIN, "SELECT array_agg(x::text) FROM pg_snapshot_xip(pg_current_snapshot()) x", 0, null, true),
                new Stmt("LOCKS", Kind.PLAIN, "SELECT array_agg(transactionid::text) FROM pg_locks WHERE locktype = 'transactionid' AND mode = 'ExclusiveLock' AND granted", 0, null,
                         false),
                new Stmt("SUB_AGG_FETCH_NONE", Kind.PLAIN, SUB_AGG, 0, "stats_fetch_consistency = 'none'", false),
                new Stmt("SUB_AGG_FETCH_SNAPSHOT", Kind.PLAIN, SUB_AGG, 0, "stats_fetch_consistency = 'snapshot'", false),
                new Stmt("TXN_CLEAR_SUB_AGG", Kind.TXN_CLEAR, SUB_AGG, 0, null, false),
                new Stmt("TXN_CACHED_SUB_AGG", Kind.TXN_CACHED, SUB_AGG, 0, null, false),
                new Stmt("TAIL_D0", Kind.PROBE, probe(false), 0, null, false),
                new Stmt("PROBE_D0", Kind.PROBE, probe(true), 0, null, true),
                new Stmt("TAIL_D10", Kind.PROBE, probe(false), 10, null, true),
                new Stmt("PROBE_D10", Kind.PROBE, probe(true), 10, null, true));
    }

    /** A probe statement prepared on one connection: executes with a cursor and returns the highest global_order read. */
    static final class Prober implements AutoCloseable {
        final PreparedStatement ps;
        final java.sql.Array    holes;

        Prober(Connection c, Stmt stmt, List<Long> holeValues) throws SQLException {
            ps = c.prepareStatement(stmt.tagged());
            holes = c.createArrayOf("bigint", holeValues.subList(0, stmt.holes).toArray(Long[]::new));
        }

        /** Executes once; returns {highest global_order read or -1, rows}. */
        long[] run(long cursor) throws SQLException {
            ps.setLong(1, cursor);
            ps.setInt(2, LIMIT);
            ps.setArray(3, holes);
            long max = -1, rows = 0;
            try (var rs = ps.executeQuery()) {
                var columns = rs.getMetaData().getColumnCount();
                while (rs.next()) {
                    max = Math.max(max, rs.getLong(1));
                    for (var i = 2; i <= columns; i++) rs.getString(i);
                    rows++;
                }
            }
            return new long[]{max, rows};
        }

        @Override
        public void close() throws SQLException {
            ps.close();
        }
    }

    // ------------------------------------------------------------------------------------------------------------------

    @Test
    void running_set_read_cost_as_connections_grow() throws Exception {
        var started = System.nanoTime();
        record(RESULTS, "=== P0.12 cost of the probe's pg_stat_activity read vs connections | " + SpikeServer.target(" | container " + IMAGE
                + " fsync=off shared_buffers=256MB") + " | levels=" + LEVELS + " iterations=" + ITERATIONS + " activeIterations=" + ACTIVE_ITER + " windows=" + WINDOW_SECONDS
                + "s writerShare=" + WRITER_SHARE + " preload=" + PRELOAD_ROWS + " limit=" + LIMIT);
        record(RESULTS, "CSV-COST,maxConnections,connections,state,label,iterations,p50us,p99us,meanUs,serverExecUsPerCall,statementsPerCall,serverCpuUsPerCall,rowsPerCall,"
                + "backends,xidsInSet");
        record(RESULTS, "CSV-APPEND,connections,writers,probers,arm,pass,seconds,appendsPerSec,appendP50us,appendP99us,probes,probesPerSec,probeP50us,probeP99us,"
                + "probeExecUs,probeRowsPerCall,serverCpuCores");
        if (SpikeServer.external()) {
            try (var server = SpikeServer.external("probecost", "spike", true)) {
                int maxConnections;
                try (var c = connect(server); var s = c.createStatement()) {
                    s.execute("CREATE EXTENSION IF NOT EXISTS pg_stat_statements");
                    maxConnections = (int) queryLong(c, "SELECT setting::int FROM pg_settings WHERE name = 'max_connections'");
                    var inUse = queryLong(c, "SELECT count(*) FROM pg_stat_activity WHERE backend_type = 'client backend'");
                    var levels = LEVELS.stream().filter(l -> l + inUse + 10 <= maxConnections).toList();
                    if (levels.size() < LEVELS.size()) record(RESULTS, "external: max_connections=" + maxConnections + ", " + inUse + " in use: levels limited to " + levels);
                    runServer(server, maxConnections, levels, true);
                }
            }
        } else {
            try (var server = container(MAIN_MAX_CONNECTIONS)) {
                runServer(server, MAIN_MAX_CONNECTIONS, LEVELS, true);
            }
            for (var e : new TreeMap<>(SLOT_SWEEP).entrySet()) {
                try (var server = container(e.getKey())) {
                    runServer(server, e.getKey(), e.getValue(), false);
                }
            }
        }
        record(RESULTS, "=== done in %.1f s".formatted((System.nanoTime() - started) / 1e9));
    }

    private static SpikeServer container(int maxConnections) throws SQLException {
        var container = new PostgreSQLContainer(IMAGE).withDatabaseName("spike").withUsername("spike").withPassword("spike")
                                                      .withSharedMemorySize(1024L * 1024 * 1024)
                                                      .withCommand("postgres", "-c", "fsync=off", "-c", "shared_buffers=256MB", "-c", "max_connections=" + maxConnections,
                                                                   "-c", "shared_preload_libraries=pg_stat_statements");
        container.start();
        var server = SpikeServer.of(container);
        try (var c = connect(server); var s = c.createStatement()) {
            s.execute("CREATE EXTENSION IF NOT EXISTS pg_stat_statements");
        }
        return server;
    }

    /** All levels on one server; {@code full} adds the active cells and the append windows. */
    private static void runServer(SpikeServer server, int maxConnections, List<Integer> levels, boolean full) throws Exception {
        var thisDb = server.container != null ? "" : " AND s.dbid = (SELECT oid FROM pg_database WHERE datname = current_database())";
        var pool   = new ArrayList<Connection>();
        try (var observer = connect(server); var measurer = connect(server)) {
            record(RESULTS, "--- server max_connections=" + maxConnections + " | " + serverVersion(observer) + " | " + settings(observer) + " | cpu " + server.cpuSource());
            var holes = load(observer);
            try {
                for (var level : levels) {
                    // observer + measurer count as two of the level's client connections
                    while (pool.size() < level - 2) {
                        var c = connect(server);
                        try (var s = c.createStatement()) {
                            s.execute("SELECT 1");
                        }
                        pool.add(c);
                    }
                    var idleCpuPerSecond = idleCpu(server);
                    record(RESULTS, "--- level %d: %d client connections open, %d rows in pg_stat_activity (%d client backends), idle server CPU %.0f µs/s".formatted(
                            level, pool.size() + 2, queryLong(observer, "SELECT count(*) FROM pg_stat_activity"),
                            queryLong(observer, "SELECT count(*) FROM pg_stat_activity WHERE backend_type = 'client backend'"), idleCpuPerSecond));
                    for (var stmt : statements()) {
                        costCell(server, observer, measurer, maxConnections, level, "idle", stmt, ITERATIONS, holes, idleCpuPerSecond, thisDb);
                    }
                    if (!full) continue;
                    int writers = Math.max(1, (int) Math.round(level * WRITER_SHARE));
                    try (var load = new WriterLoad(pool.subList(0, writers))) {
                        load.start();
                        Thread.sleep(1000);
                        for (var stmt : statements()) {
                            if (stmt.active) costCell(server, observer, measurer, maxConnections, level, "active", stmt, ACTIVE_ITER, holes, Double.NaN, thisDb);
                        }
                        if (APPENDS) appendWindows(server, observer, pool, level, writers, load, holes, thisDb);
                    }
                }
            } finally {
                for (var c : pool) {
                    try {
                        c.close();
                    } catch (SQLException ignored) {
                        // closing
                    }
                }
                try (var s = observer.createStatement()) {
                    s.execute("DROP TABLE IF EXISTS " + TABLE_NAME);
                }
            }
        }
    }

    private static String settings(Connection c) throws SQLException {
        return queryString(c, "SELECT string_agg(name || '=' || setting, ', ' ORDER BY name) FROM pg_settings WHERE name IN ('max_connections', 'track_activity_query_size', "
                + "'superuser_reserved_connections', 'autovacuum_max_workers', 'max_worker_processes', 'shared_buffers', 'fsync', 'synchronous_commit', 'jit', "
                + "'stats_fetch_consistency', 'track_activities')");
    }

    /** The engine-shaped event table with {@code PRELOAD_ROWS} rows; returns ten absent values below the head (the holes). */
    private static List<Long> load(Connection c) throws SQLException {
        try (var s = c.createStatement()) {
            s.execute("DROP TABLE IF EXISTS " + TABLE_NAME);
            s.execute("CREATE TABLE " + TABLE_NAME + " (global_order bigint GENERATED BY DEFAULT AS IDENTITY PRIMARY KEY, aggregate_id text NOT NULL, event_order bigint NOT NULL, "
                              + "event_id text NOT NULL, caused_by_event_id text, correlation_id text, event_type text NOT NULL, event_revision text NOT NULL, "
                              + "timestamp timestamptz NOT NULL, event_payload jsonb NOT NULL, event_metadata jsonb NOT NULL, tenant text, UNIQUE (aggregate_id, event_order), "
                              + "UNIQUE (event_id))");
            s.execute("INSERT INTO " + TABLE_NAME + " SELECT g, 'Order-' || (g % 10000), g / 10000, gen_random_uuid()::text, NULL, gen_random_uuid()::text, 'OrderEvent', '1', now(), "
                              + "jsonb_build_object('orderId', 'Order-' || (g % 10000), 'quantity', g % 10, 'note', md5(g::text)), '{\"traceId\": \"t\"}'::jsonb, NULL "
                              + "FROM generate_series(1, " + PRELOAD_ROWS + ") g");
            s.execute("SELECT setval(pg_get_serial_sequence('" + TABLE_NAME + "', 'global_order'), " + PRELOAD_ROWS + ")");
        }
        var holes = new ArrayList<Long>();
        for (var i = 0; i < 10; i++) holes.add((long) PRELOAD_ROWS - 100 - 3L * i);
        try (var ps = c.prepareStatement("DELETE FROM " + TABLE_NAME + " WHERE global_order = ANY(?)")) {
            ps.setArray(1, c.createArrayOf("bigint", holes.toArray(Long[]::new)));
            assertThat(ps.executeUpdate()).isEqualTo(10);
        }
        try (var s = c.createStatement()) {
            s.execute("VACUUM (ANALYZE) " + TABLE_NAME);
        }
        return holes;
    }

    private static double idleCpu(SpikeServer server) throws InterruptedException {
        var before = server.cpuMicros();
        var t0     = System.nanoTime();
        Thread.sleep(2000);
        var after = server.cpuMicros();
        return SpikeServer.cpuDelta(before, after) / ((System.nanoTime() - t0) / 1e9);
    }

    // ------------------------------------------------------------------------------------------------------------------
    // Cost cells
    // ------------------------------------------------------------------------------------------------------------------

    private static void costCell(SpikeServer server, Connection observer, Connection c, int maxConnections, int level, String state, Stmt stmt, int iterations,
                                 List<Long> holes, double idleCpuPerSecond, String thisDb) throws SQLException {
        if (stmt.setting != null) {
            try (var s = c.createStatement()) {
                s.execute("SET " + stmt.setting);
            } catch (SQLException e) {
                record(RESULTS, "CSV-COST,%d,%d,%s,%s,0,,,,,,,,,,n/a: %s".formatted(maxConnections, level, state, stmt.label, e.getMessage().replace(',', ';')));
                return;
            }
        }
        var latencies = new long[iterations];
        long rows = 0;
        String lastSet = null;
        try (var unit = new Unit(c, stmt, holes)) {
            for (var i = 0; i < 50; i++) unit.run();
            try (var s = observer.createStatement()) {
                s.execute("SELECT pg_stat_statements_reset()");
            }
            var cpuBefore = server.cpuMicros();
            var t0        = System.nanoTime();
            for (var i = 0; i < iterations; i++) {
                var start = System.nanoTime();
                rows += unit.run();
                latencies[i] = System.nanoTime() - start;
            }
            var elapsed  = (System.nanoTime() - t0) / 1e9;
            var cpuAfter = server.cpuMicros();
            lastSet = unit.lastRunning;
            double calls, exec;
            try (var s = observer.createStatement();
                 var rs = s.executeQuery("SELECT coalesce(sum(s.calls), 0), coalesce(sum(s.total_exec_time), 0) FROM pg_stat_statements s WHERE s.query LIKE '%/*p012 "
                                                 + stmt.label + "*/%'" + thisDb)) {
                rs.next();
                calls = rs.getDouble(1);
                exec = rs.getDouble(2);
            }
            var cpu        = SpikeServer.cpuDelta(cpuBefore, cpuAfter);
            var cpuPerCall = Double.isNaN(idleCpuPerSecond) ? Double.NaN : (cpu - idleCpuPerSecond * elapsed) / iterations;
            var mean       = Arrays.stream(latencies).average().orElse(0) / 1e3;
            Arrays.sort(latencies);
            var backends = queryLong(observer, "SELECT count(*) FROM pg_stat_activity");
            record(RESULTS, "CSV-COST,%d,%d,%s,%s,%d,%.1f,%.1f,%.1f,%.1f,%.2f,%.1f,%.1f,%d,%d".formatted(maxConnections, level, state, stmt.label, iterations,
                                                                                                       percentile(latencies, 0.50) / 1e3, percentile(latencies, 0.99) / 1e3, mean,
                                                                                                       exec * 1000 / iterations, calls / iterations, cpuPerCall,
                                                                                                       (double) rows / iterations, backends,
                                                                                                       lastSet == null || stmt.kind == Kind.PROBE || !stmt.sql.contains("array_agg") ? 0 : lastSet.split(",").length));
        } finally {
            if (stmt.setting != null) {
                try (var s = c.createStatement()) {
                    s.execute("RESET " + stmt.setting.substring(0, stmt.setting.indexOf(' ')));
                }
            }
            if (!c.getAutoCommit()) {
                c.rollback();
                c.setAutoCommit(true);
            }
        }
    }

    /** One timed unit of a statement on the measuring connection. */
    private static final class Unit implements AutoCloseable {
        private final Connection        c;
        private final Stmt              stmt;
        private final PreparedStatement ps;
        private final PreparedStatement clear;
        private final Prober            prober;
        private       long              cursor;
        String lastRunning;

        Unit(Connection c, Stmt stmt, List<Long> holes) throws SQLException {
            this.c = c;
            this.stmt = stmt;
            switch (stmt.kind) {
                case PROBE -> {
                    prober = new Prober(c, stmt, holes);
                    ps = null;
                    clear = null;
                    cursor = queryLong(c, "SELECT max(global_order) FROM " + TABLE_NAME) - 1;
                }
                case TXN_CLEAR -> {
                    prober = null;
                    ps = c.prepareStatement(stmt.tagged());
                    clear = c.prepareStatement("/*p012 " + stmt.label + "*/ SELECT pg_stat_clear_snapshot()");
                    c.setAutoCommit(false);
                }
                case TXN_CACHED -> {
                    prober = null;
                    ps = c.prepareStatement(stmt.tagged());
                    clear = null;
                    c.setAutoCommit(false);
                }
                default -> {
                    prober = null;
                    ps = c.prepareStatement(stmt.tagged());
                    clear = null;
                }
            }
        }

        /** Runs once; returns the rows read. */
        long run() throws SQLException {
            switch (stmt.kind) {
                case PROBE -> {
                    var r = prober.run(cursor);
                    // keep re-reading the last row so every probe returns one and evaluates the sub-select (P0.2's caveat)
                    if (r[0] > 0) cursor = r[0] - 1;
                    return r[1];
                }
                case TXN_CLEAR -> {
                    clear.execute();
                    var n = read();
                    c.commit();
                    return n;
                }
                default -> {
                    return read();
                }
            }
        }

        private long read() throws SQLException {
            long n = 0;
            try (var rs = ps.executeQuery()) {
                while (rs.next()) {
                    lastRunning = rs.getString(1);
                    n++;
                }
            }
            return n;
        }

        @Override
        public void close() throws SQLException {
            if (prober != null) prober.close();
            if (ps != null) ps.close();
            if (clear != null) clear.close();
        }
    }

    private static long percentile(long[] sorted, double p) {
        return sorted.length == 0 ? 0 : sorted[Math.min(sorted.length - 1, Math.max(0, (int) Math.ceil(p * sorted.length) - 1))];
    }

    // ------------------------------------------------------------------------------------------------------------------
    // Writers and append windows
    // ------------------------------------------------------------------------------------------------------------------

    /** Log-scale latency histogram (2% buckets), lock-free for many recording threads. */
    static final class Histogram {
        private static final double BASE = Math.log(1.02);
        private final AtomicLongArray counts = new AtomicLongArray(1200);
        private final AtomicLong      total  = new AtomicLong();

        void record(long nanos) {
            var us = Math.max(1.0, nanos / 1e3);
            counts.incrementAndGet(Math.min(counts.length() - 1, (int) (Math.log(us) / BASE)));
            total.incrementAndGet();
        }

        long count() {
            return total.get();
        }

        double percentileUs(double p) {
            var n = total.get();
            if (n == 0) return Double.NaN;
            long target = (long) Math.ceil(p * n), seen = 0;
            for (var i = 0; i < counts.length(); i++) {
                seen += counts.get(i);
                if (seen >= target) return Math.exp((i + 0.5) * BASE);
            }
            return Double.NaN;
        }
    }

    /** Writers appending one event per autocommit transaction, as fast as they can, until closed. */
    static final class WriterLoad implements AutoCloseable {
        private final List<Connection>   connections;
        private final List<Thread>       threads = new ArrayList<>();
        private final AtomicLong         appends = new AtomicLong();
        private volatile boolean         running = true;
        volatile Histogram               histogram = new Histogram();
        private final AtomicReference<Throwable> failure = new AtomicReference<>();

        WriterLoad(List<Connection> connections) {
            this.connections = List.copyOf(connections);
        }

        void start() {
            var run = Long.toString(System.nanoTime(), 36);
            for (var i = 0; i < connections.size(); i++) {
                var c         = connections.get(i);
                var aggregate = "W" + i + "-" + run;
                var t = Thread.ofPlatform().name("p012-writer-" + i).start(() -> {
                    try (var ps = c.prepareStatement("INSERT INTO " + TABLE_NAME + " (aggregate_id, event_order, event_id, event_type, event_revision, timestamp, event_payload, "
                                                             + "event_metadata) VALUES (?, ?, ?, 'OrderEvent', '1', now(), '{\"orderId\": \"x\", \"quantity\": 1}'::jsonb, "
                                                             + "'{\"traceId\": \"t\"}'::jsonb)")) {
                        long order = 0;
                        while (running) {
                            ps.setString(1, aggregate);
                            ps.setLong(2, order++);
                            ps.setString(3, UUID.randomUUID().toString());
                            var start = System.nanoTime();
                            ps.executeUpdate();
                            histogram.record(System.nanoTime() - start);
                            appends.incrementAndGet();
                        }
                    } catch (Throwable e) {
                        if (running) failure.compareAndSet(null, e);
                    }
                });
                threads.add(t);
            }
        }

        long appends() {
            var f = failure.get();
            if (f != null) throw new IllegalStateException("writer failed", f);
            return appends.get();
        }

        @Override
        public void close() throws InterruptedException {
            running = false;
            for (var t : threads) t.join(30_000);
        }
    }

    enum Arm {
        NONE(0, 0, false), TAIL_1MS(1, 1, false), PROBE_1MS(1, 1, true), TAIL_N_10MS(-1, 10, false), PROBE_N_10MS(-1, 10, true);

        /** Probers (-1 = connections / 10), each probing every {@code periodMillis}. */
        final int     probers;
        final int     periodMillis;
        final boolean running;

        Arm(int probers, int periodMillis, boolean running) {
            this.probers = probers;
            this.periodMillis = periodMillis;
            this.running = running;
        }

        int probers(int level) {
            return probers < 0 ? Math.max(1, level / 10) : probers;
        }
    }

    private static void appendWindows(SpikeServer server, Connection observer, List<Connection> pool, int level, int writers, WriterLoad load, List<Long> holes,
                                      String thisDb) throws Exception {
        var arms  = List.of(Arm.values());
        var order = new ArrayList<>(arms);
        var back  = new ArrayList<>(arms);
        Collections.reverse(back);
        order.addAll(back);
        for (var pass = 0; pass < order.size(); pass++) {
            var arm     = order.get(pass);
            var nProbes = arm.probers(level);
            var probers = new ArrayList<Thread>();
            var probing = new AtomicBoolean(true);
            var probeHist = new AtomicReference<>(new Histogram());
            var probeRows = new AtomicLong();
            var failure   = new AtomicReference<Throwable>();
            var stmt      = new Stmt(arm.running ? "PROBE_D10" : "TAIL_D10", Kind.PROBE, probe(arm.running), 10, null, false);
            for (var p = 0; p < nProbes; p++) {
                var c      = pool.get(writers + p);
                var offset = (long) arm.periodMillis * 1_000_000L * p / nProbes;
                probers.add(Thread.ofPlatform().name("p012-prober-" + p).start(() -> {
                    try (var prober = new Prober(c, stmt, holes)) {
                        var cursor = queryLong(c, "SELECT max(global_order) FROM " + TABLE_NAME) - 1;
                        var period = arm.periodMillis * 1_000_000L;
                        var next   = System.nanoTime() + offset;
                        while (probing.get()) {
                            var wait = next - System.nanoTime();
                            if (wait > 0) LockSupport.parkNanos(wait);
                            next += period;
                            var start = System.nanoTime();
                            var r     = prober.run(cursor);
                            probeHist.get().record(System.nanoTime() - start);
                            probeRows.addAndGet(r[1]);
                            if (r[0] > 0) cursor = r[0] - 1;
                            if (next < System.nanoTime()) next = System.nanoTime();
                        }
                    } catch (Throwable e) {
                        failure.compareAndSet(null, e);
                    }
                }));
            }
            Thread.sleep(1000);
            try (var s = observer.createStatement()) {
                s.execute("SELECT pg_stat_statements_reset()");
            }
            load.histogram = new Histogram();
            probeHist.set(new Histogram());
            probeRows.set(0);
            var appendsBefore = load.appends();
            var cpuBefore     = server.cpuMicros();
            var t0            = System.nanoTime();
            Thread.sleep(WINDOW_SECONDS * 1000L);
            var appendsAfter = load.appends();
            var elapsed      = (System.nanoTime() - t0) / 1e9;
            var cpuAfter     = server.cpuMicros();
            var appendHist   = load.histogram;
            var ph           = probeHist.get();
            var probes       = ph.count();
            var rowsRead     = probeRows.get();
            double exec;
            try (var s = observer.createStatement();
                 var rs = s.executeQuery("SELECT coalesce(sum(s.total_exec_time), 0), coalesce(sum(s.calls), 0) FROM pg_stat_statements s WHERE s.query LIKE '%/*p012 "
                                                 + stmt.label + "*/%'" + thisDb)) {
                rs.next();
                exec = rs.getDouble(2) == 0 ? Double.NaN : rs.getDouble(1) * 1000 / rs.getDouble(2);
            }
            probing.set(false);
            for (var t : probers) t.join(30_000);
            if (failure.get() != null) throw new IllegalStateException("prober failed", failure.get());
            record(RESULTS, "CSV-APPEND,%d,%d,%d,%s,%d,%.1f,%.0f,%.1f,%.1f,%d,%.0f,%.1f,%.1f,%.1f,%.1f,%.2f".formatted(
                    level, writers, nProbes, arm, pass, elapsed, (appendsAfter - appendsBefore) / elapsed, appendHist.percentileUs(0.50), appendHist.percentileUs(0.99), probes,
                    probes / elapsed, ph.percentileUs(0.50), ph.percentileUs(0.99), exec, probes == 0 ? 0.0 : (double) rowsRead / probes,
                    SpikeServer.cpuDelta(cpuBefore, cpuAfter) / 1e6 / elapsed));
        }
    }
}
