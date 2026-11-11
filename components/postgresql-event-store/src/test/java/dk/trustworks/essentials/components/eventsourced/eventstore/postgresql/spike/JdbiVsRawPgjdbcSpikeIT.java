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

import dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.spike.CurrentEngineBaselineSpikeIT.*;
import dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.spike.CurrentEngineBaselineSpikeIT.Stack;
import dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.spike.SingleStatementAppendSpikeIT.*;
import dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.test_data.*;
import dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.types.EventOrder;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

import com.zaxxer.hikari.*;
import org.jdbi.v3.core.Jdbi;
import org.jdbi.v3.core.Handle;
import org.jdbi.v3.postgres.PostgresPlugin;

import java.lang.management.ManagementFactory;
import java.nio.file.*;
import java.sql.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import java.util.logging.*;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Spike P0.6 ({@code docs/plans/event-store-phase0-jdbc.md}): JDBI against raw pgjdbc for the same statements.
 * <p>
 * {@link #protocol_trace()} records the pgjdbc wire messages (JUL {@code org.postgresql} at FINEST) of one warmed-up unit
 * of work of {@link ContendedAppendThroughputSpikeIT}'s arm A (engine, JDBI) and arm C (raw pgjdbc), on the same pool,
 * so the two can be diffed message by message. {@link #round_trip_reconciliation()} counts round trips through P0.0's
 * {@link RoundTripCountingProxy} and in the wire trace on the same units of work, for P0.0's and P0.6's shapes.
 * <p>
 * {@link #statements()} runs the same statements through JDBI and through raw pgjdbc, on one Hikari pool with default
 * settings (as the engine's spike stacks), the same SQL text, the same parameter types and the same row mapping:
 * A's one-event insert batch, the engine's last-event lookup ({@code setFetchSize(1)} inside a transaction, as the engine
 * does), B's single statement ({@link UnattributedCpuSpikeIT}'s {@code B}), and the tail read in autocommit. Uncontended:
 * one thread, per-call latency p50/p99 and the thread's CPU time per call. Contended: 8 threads, units of work
 * (lookup + insert, lookup + B) and tail reads, in NORMAL mode and in SPIN mode (50 µs busy-wait between lookup and
 * append, or between reads), throughput and CPU per unit. Knobs: {@code -Dspike.jdbc.runs} (2),
 * {@code .calls} (20000), {@code .seconds} (20), {@code .contended} (the contended arm list).
 * <p>
 * Throwaway: runs only with {@code -Dspike.run=true}.
 */
@Tag("spike")
@EnabledIfSystemProperty(named = "spike.run", matches = "true")
class JdbiVsRawPgjdbcSpikeIT {
    private static final String RESULTS = "p0-6-jdbc.txt";
    private static final int    RUNS    = Integer.getInteger("spike.jdbc.runs", 2);
    private static final int    CALLS   = Integer.getInteger("spike.jdbc.calls", 20_000);
    private static final int    SECONDS = Integer.getInteger("spike.jdbc.seconds", 20);
    private static final int    THREADS = 8;
    private static final int    AGGREGATES_CONTENDED = 10;
    private static final int    PRELOAD_AGGREGATES = 100, PRELOAD_EVENTS = 1000;
    private static final int    TAIL_LIMIT = 100;
    private static final long   SPIN_NANOS = 50_000;

    /** {@code JDBI_FIRST}: JDBI, but the lookup uses {@code findFirst()} instead of the engine's {@code findOne()}, which reads on for a second row; {@code JDBI_NO_FETCH_SIZE}: JDBI, lookup with {@code findOne()} but no {@code setFetchSize(1)}. */
    enum Lib {JDBI, JDBI_FIRST, JDBI_NO_FETCH_SIZE, RAW}

    enum Stmt {INSERT, LOOKUP, LOOKUP_NO_FETCH_SIZE, B, TAIL}

    /** One row of the event table, mapped the same way by both libraries. */
    record Row(long globalOrder, String aggregateId, long eventOrder, String eventId, String causedBy, String correlation, String type, String revision,
               OffsetDateTime timestamp, String payload, String metadata, String tenant) {
        static Row of(ResultSet rs) throws SQLException {
            return new Row(rs.getLong(1), rs.getString(2), rs.getLong(3), rs.getString(4), rs.getString(5), rs.getString(6), rs.getString(7), rs.getString(8),
                           rs.getObject(9, OffsetDateTime.class), rs.getString(10), rs.getString(11), rs.getString(12));
        }
    }

    /** The parameters of an insert (A) or of B, in statement order: Long or String (null = a null String). */
    static Object[] params(UnattributedCpuSpikeIT.Arm arm, String id, long last) {
        var p = new ArrayList<>();
        if (UnattributedCpuSpikeIT.guardFirst(arm)) {
            p.add(id);
            p.add(last);
            p.add(last);
        }
        p.add(id);
        if (!UnattributedCpuSpikeIT.guardFirst(arm)) p.add(last + 1);
        var eventId = UUID.randomUUID().toString();
        p.add(eventId);
        p.add(null);
        p.add(eventId);
        p.add(UnattributedCpuSpikeIT.TYPE);
        p.add("1");
        p.add(OffsetDateTime.now(ZoneOffset.UTC).toString());
        p.add(UnattributedCpuSpikeIT.PAYLOAD.formatted(id, eventId));
        p.add(UnattributedCpuSpikeIT.METADATA.formatted(eventId));
        p.add(null);
        return p.toArray();
    }

    static void bind(PreparedStatement ps, Object[] p) throws SQLException {
        for (int i = 0; i < p.length; i++) {
            if (p[i] instanceof Long l) ps.setLong(i + 1, l);
            else ps.setString(i + 1, (String) p[i]);
        }
    }

    static <T extends org.jdbi.v3.core.statement.SqlStatement<T>> T bind(T st, Object[] p) {
        for (int i = 0; i < p.length; i++) {
            if (p[i] instanceof Long l) st.bind(i, l.longValue());
            else st.bind(i, (String) p[i]);
        }
        return st;
    }

    /** One thread's connection, in both forms: a JDBI handle and its raw connection. The handle is kept open, as a unit of work keeps it. */
    static final class Client implements AutoCloseable {
        final Handle     handle;
        final Connection raw;
        final String     table;

        Client(Jdbi jdbi, String table) {
            this.handle = jdbi.open();
            this.raw = handle.getConnection();
            this.table = table;
        }

        String insertSql() {
            return UnattributedCpuSpikeIT.sql(UnattributedCpuSpikeIT.Arm.A_VALUES, table);
        }

        String lookupSql() {
            return "SELECT * FROM " + table + " WHERE aggregate_id = ? ORDER BY event_order DESC LIMIT 1";
        }

        String tailSql() {
            return "SELECT * FROM " + table + " WHERE global_order > ? ORDER BY global_order LIMIT ?";
        }

        void begin(Lib lib) throws SQLException {
            if (lib != Lib.RAW) handle.begin();
            else raw.setAutoCommit(false);
        }

        void commit(Lib lib) throws SQLException {
            if (lib != Lib.RAW) handle.commit();
            else {
                raw.commit();
                raw.setAutoCommit(true);
            }
        }

        void rollback(Lib lib) throws SQLException {
            if (lib != Lib.RAW) handle.rollback();
            else {
                raw.rollback();
                raw.setAutoCommit(true);
            }
        }

        long insert(Lib lib, Object[] p) throws SQLException {
            if (lib != Lib.RAW) {
                var batch = handle.prepareBatch(insertSql());
                bind(batch, p).add();
                return batch.executePreparedBatch("global_order").mapTo(Long.class).one();
            }
            try (var ps = raw.prepareStatement(insertSql(), new String[]{"global_order"})) {
                bind(ps, p);
                ps.addBatch();
                ps.executeBatch();
                try (var keys = ps.getGeneratedKeys()) {
                    keys.next();
                    return keys.getLong(1);
                }
            }
        }

        Optional<Row> lookup(Lib lib, String id, boolean fetchSize) throws SQLException {
            if (lib == Lib.JDBI) return handle.createQuery(lookupSql()).bind(0, id).setFetchSize(1).map((rs, ctx) -> Row.of(rs)).findOne();
            if (lib == Lib.JDBI_NO_FETCH_SIZE) return handle.createQuery(lookupSql()).bind(0, id).map((rs, ctx) -> Row.of(rs)).findOne();
            if (lib == Lib.JDBI_FIRST) return handle.createQuery(lookupSql()).bind(0, id).setFetchSize(1).map((rs, ctx) -> Row.of(rs)).findFirst();
            try (var ps = raw.prepareStatement(lookupSql())) {
                if (fetchSize) ps.setFetchSize(1);
                ps.setString(1, id);
                try (var rs = ps.executeQuery()) {
                    return rs.next() ? Optional.of(Row.of(rs)) : Optional.empty();
                }
            }
        }

        /** B: returns the number of rows written (0 = expected order did not match). */
        int single(Lib lib, Object[] p) throws SQLException {
            var sql = UnattributedCpuSpikeIT.sql(UnattributedCpuSpikeIT.Arm.B, table);
            if (lib != Lib.RAW) return bind(handle.createQuery(sql), p).map((rs, ctx) -> rs.getLong(1)).list().size();
            try (var ps = raw.prepareStatement(sql)) {
                bind(ps, p);
                int n = 0;
                try (var rs = ps.executeQuery()) {
                    while (rs.next()) {
                        rs.getLong(1);
                        n++;
                    }
                }
                return n;
            }
        }

        List<Row> tail(Lib lib, long after) throws SQLException {
            if (lib != Lib.RAW) return handle.createQuery(tailSql()).bind(0, after).bind(1, TAIL_LIMIT).map((rs, ctx) -> Row.of(rs)).list();
            try (var ps = raw.prepareStatement(tailSql())) {
                ps.setLong(1, after);
                ps.setInt(2, TAIL_LIMIT);
                var rows = new ArrayList<Row>(TAIL_LIMIT);
                try (var rs = ps.executeQuery()) {
                    while (rs.next()) rows.add(Row.of(rs));
                }
                return rows;
            }
        }

        @Override
        public void close() {
            handle.close();
        }
    }

    // ------------------------------------------------------------------ uncontended, per statement

    @Test
    void statements() throws Exception {
        record("");
        record("=== P0.6 STATEMENTS | " + Instant.now() + " | runs " + RUNS + " | " + CALLS + " measured calls per (statement, library, pass) after " + CALLS / 10
                       + " warm-up calls | passes = libraries " + System.getProperty("spike.jdbc.libs", "JDBI,RAW") + " as a palindrome, rotated by one per run, statements " + System.getProperty("spike.jdbc.stmts", "all") + " | one thread, one pooled connection (Hikari defaults) | pgjdbc "
                       + org.postgresql.Driver.getVersion() + SpikeServer.target(" | postgres:17.5 fsync=off | client and database on one 8-CPU VM"));
        for (int run = 1; run <= RUNS; run++) {
            try (var pg = Pg.start(); var ds = pool(pg)) {
                var jdbi  = Jdbi.create(ds).installPlugin(new PostgresPlugin());
                var table = "p06_events_r" + run;
                var ids   = preload(pg, table);
                var max   = SpikePostgres.queryLong(pg.observer, "SELECT max(global_order) FROM " + table);
                var libs  = new ArrayList<>(Arrays.stream(System.getProperty("spike.jdbc.libs", "JDBI,RAW").split(",")).map(Lib::valueOf).toList());
                var back  = new ArrayList<>(libs);
                Collections.reverse(back);
                libs.addAll(back);
                Collections.rotate(libs, run - 1);
                var order = libs;
                var stmts = Arrays.stream(System.getProperty("spike.jdbc.stmts", "INSERT,LOOKUP,LOOKUP_NO_FETCH_SIZE,B,TAIL").split(",")).map(Stmt::valueOf).toList();
                try (var c = new Client(jdbi, table)) {
                    for (var stmt : stmts) {
                        for (var lib : order) {
                            if (stmt == Stmt.LOOKUP_NO_FETCH_SIZE && lib != Lib.RAW) continue;
                            uncontended(pg, c, run, stmt, lib, ids, max, true);
                            uncontended(pg, c, run, stmt, lib, ids, max, false);
                        }
                    }
                }
            }
        }
    }

    private void uncontended(Pg pg, Client c, int run, Stmt stmt, Lib lib, List<String> ids, long max, boolean warmup) throws Exception {
        int n = warmup ? CALLS / 10 : CALLS;
        var lat = new long[n];
        var random = ThreadLocalRandom.current();
        var threads = ManagementFactory.getThreadMXBean();
        if (!warmup) pg.resetStatistics();
        long cpu = 0;
        for (int i = 0; i < n; i++) {
            boolean tx = stmt != Stmt.TAIL;
            if (tx) c.begin(lib);
            Object[] p = switch (stmt) {
                case INSERT -> params(UnattributedCpuSpikeIT.Arm.A_VALUES, UUID.randomUUID().toString(), -1);
                case B -> params(UnattributedCpuSpikeIT.Arm.B, UUID.randomUUID().toString(), -1);
                default -> null;
            };
            var id = ids.get(random.nextInt(ids.size()));
            long after = random.nextLong(Math.max(1, max - TAIL_LIMIT));
            long c0 = threads.getCurrentThreadCpuTime();
            long t0 = System.nanoTime();
            switch (stmt) {
                case INSERT -> c.insert(lib, p);
                case LOOKUP -> c.lookup(lib, id, true).orElseThrow();
                case LOOKUP_NO_FETCH_SIZE -> c.lookup(lib, id, false).orElseThrow();
                case B -> {
                    if (c.single(lib, p) != 1) throw new IllegalStateException("B wrote no row");
                }
                case TAIL -> {
                    if (c.tail(lib, after).size() != TAIL_LIMIT) throw new IllegalStateException("short tail read");
                }
            }
            lat[i] = System.nanoTime() - t0;
            cpu += threads.getCurrentThreadCpuTime() - c0;
            if (tx) c.commit(lib);
        }
        if (warmup) return;
        Arrays.sort(lat);
        long sum = 0;
        for (var l : lat) sum += l;
        String pgss = "";
        try (var s = pg.observer.createStatement();
             var rs = s.executeQuery("SELECT s.calls, s.plans, s.total_exec_time, s.total_plan_time FROM pg_stat_statements s JOIN pg_roles r ON r.oid = s.userid "
                                             + "WHERE r.rolname = '" + pg.server.role("writer") + "' AND s.query NOT ILIKE 'BEGIN%' AND s.query NOT ILIKE 'COMMIT%' ORDER BY s.calls DESC LIMIT 1")) {
            if (rs.next()) pgss = String.format("server: calls %d, plans %d, exec %.1f µs/call", rs.getLong(1), rs.getLong(2), rs.getDouble(3) * 1000 / Math.max(1, rs.getLong(1)));
        }
        record(String.format("STMT run %d | %-20s | %-4s | p50 %6.1f µs, p90 %6.1f, p99 %6.1f, mean %6.1f | client thread CPU %6.1f µs/call | %s",
                             run, stmt, lib, lat[n / 2] / 1000.0, lat[(int) (n * 0.9)] / 1000.0, lat[(int) (n * 0.99)] / 1000.0, sum / 1000.0 / n, cpu / 1000.0 / n, pgss));
    }

    // ------------------------------------------------------------------ contended

    enum Unit {UOW_A, UOW_B, TAIL}

    @Test
    void contended() throws Exception {
        var specs = Arrays.stream(System.getProperty("spike.jdbc.contended", "UOW_A:JDBI,UOW_A:RAW,UOW_B:JDBI,UOW_B:RAW,TAIL:JDBI,TAIL:RAW,UOW_A:JDBI@SPIN,UOW_A:RAW@SPIN,UOW_B:JDBI@SPIN,UOW_B:RAW@SPIN,TAIL:JDBI@SPIN,TAIL:RAW@SPIN")
                                .split(",")).map(String::trim).toList();
        var palindrome = new ArrayList<>(specs);
        var reversed = new ArrayList<>(specs);
        Collections.reverse(reversed);
        palindrome.addAll(reversed);
        record("");
        record("=== P0.6 CONTENDED | " + Instant.now() + " | runs " + RUNS + " | " + THREADS + " threads, " + AGGREGATES_CONTENDED + " aggregates, " + SECONDS
                       + " s per window after a 2 s warm-up, fresh table per window (dropped after), palindrome order " + palindrome + " (run 2 rotated by one) | Hikari defaults, "
                       + "one connection per thread held for the window" + SpikeServer.target(" | postgres:17.5 fsync=off | client and database on one 8-CPU VM"));
        for (int run = 1; run <= RUNS; run++) {
            var order = new ArrayList<>(palindrome);
            Collections.rotate(order, run - 1);
            try (var pg = Pg.start(); var ds = pool(pg)) {
                var jdbi = Jdbi.create(ds).installPlugin(new PostgresPlugin());
                int n = 0;
                for (var spec : order) {
                    var spin = spec.endsWith("@SPIN");
                    var parts = spec.replace("@SPIN", "").split(":");
                    var unit = Unit.valueOf(parts[0]);
                    var lib = Lib.valueOf(parts[1]);
                    window(pg, jdbi, run, unit, lib, spin, "p06_c_r" + run + "w" + n + "warm", Duration.ofSeconds(2), true);
                    window(pg, jdbi, run, unit, lib, spin, "p06_c_r" + run + "w" + n, Duration.ofSeconds(SECONDS), false);
                    n++;
                }
            }
        }
    }

    private void window(Pg pg, Jdbi jdbi, int run, Unit unit, Lib lib, boolean spin, String table, Duration duration, boolean discard) throws Exception {
        List<String> ids;
        if (unit == Unit.TAIL) {
            ids = preload(pg, table);
        } else {
            createTable(pg, table);
            ids = new ArrayList<>();
            try (var c = new Client(jdbi, table)) {
                for (int i = 0; i < AGGREGATES_CONTENDED; i++) {
                    var id = UUID.randomUUID().toString();
                    ids.add(id);
                    c.insert(Lib.RAW, params(UnattributedCpuSpikeIT.Arm.A_VALUES, id, -1));
                }
            }
        }
        long max = SpikePostgres.queryLong(pg.observer, "SELECT max(global_order) FROM " + table);
        long rows0 = SpikePostgres.queryLong(pg.observer, "SELECT count(*) FROM " + table);
        var clients = new ArrayList<Client>();
        for (int t = 0; t < THREADS; t++) clients.add(new Client(jdbi, table));
        if (!discard) pg.resetStatistics();
        long server0 = ContendedAppendThroughputSpikeIT.containerCpuMicros(pg);
        long[] m0 = machine();
        long jvm0 = jvmCpu();
        var attempts = new LongAdder();
        var zero = new LongAdder();
        var unique = new LongAdder();
        var cpu = new LongAdder();
        var firstOther = new AtomicReference<Throwable>();
        long stopAt = System.nanoTime() + duration.toNanos();
        var threads = new ArrayList<Thread>();
        for (var c : clients) {
            threads.add(Thread.ofPlatform().unstarted(() -> {
                var tm = ManagementFactory.getThreadMXBean();
                long c0 = tm.getCurrentThreadCpuTime();
                var r = ThreadLocalRandom.current();
                while (System.nanoTime() < stopAt) {
                    attempts.increment();
                    try {
                        if (unit == Unit.TAIL) {
                            c.tail(lib, r.nextLong(Math.max(1, max - TAIL_LIMIT)));
                            if (spin) spin();
                            continue;
                        }
                        var id = ids.get(r.nextInt(ids.size()));
                        c.begin(lib);
                        try {
                            long last = c.lookup(lib, id, true).orElseThrow().eventOrder();
                            if (spin) spin();
                            if (unit == Unit.UOW_A) {
                                c.insert(lib, params(UnattributedCpuSpikeIT.Arm.A_VALUES, id, last));
                                c.commit(lib);
                            } else if (c.single(lib, params(UnattributedCpuSpikeIT.Arm.B, id, last)) == 1) {
                                c.commit(lib);
                            } else {
                                zero.increment();
                                c.rollback(lib);
                            }
                        } catch (Exception e) {
                            c.rollback(lib);
                            throw e;
                        }
                    } catch (Exception e) {
                        if (isUnique(e)) unique.increment();
                        else firstOther.compareAndSet(null, e);
                    }
                }
                cpu.add(tm.getCurrentThreadCpuTime() - c0);
            }));
        }
        long wall0 = System.nanoTime();
        threads.forEach(Thread::start);
        for (var t : threads) t.join();
        double seconds = (System.nanoTime() - wall0) / 1e9;
        double serverCpu = SpikeServer.cpuDelta(server0, ContendedAppendThroughputSpikeIT.containerCpuMicros(pg));
        long[] m1 = machine();
        long jvm = jvmCpu() - jvm0;
        for (var c : clients) c.close();
        long rows1 = SpikePostgres.queryLong(pg.observer, "SELECT count(*) FROM " + table);
        double exec = discard ? 0 : SpikePostgres.queryLong(pg.observer, "SELECT coalesce(round(sum(s.total_exec_time) * 1000), 0)::bigint FROM pg_stat_statements s "
                + "JOIN pg_roles r ON r.oid = s.userid WHERE r.rolname = '" + pg.server.role("writer") + "'");
        try (var s = pg.observer.createStatement()) {
            s.execute("DROP TABLE " + table);
            s.execute("CHECKPOINT");
        }
        if (discard) return;
        double a = attempts.sum();
        long done = unit == Unit.TAIL ? (long) a : rows1 - rows0;
        double busy = (double) (m1[0] - m0[0]) / Math.max(1, m1[1] - m0[1]);
        record(String.format("CONT run %d | %-5s | %-4s | %-6s | done/s %7.0f (%s) | attempts/s %7.0f | zero %4.1f%% unique %4.1f%% | wall µs/att %6.1f | client thread CPU µs/att %5.1f, JVM %5.1f | "
                                     + "PG CPU µs/att %6.1f | recorded exec µs/att %5.1f | machine busy %4.1f%%%s",
                             run, unit, lib, spin ? "SPIN" : "NORMAL", done / seconds, unit == Unit.TAIL ? "reads" : "appends", a / seconds, 100 * zero.sum() / a, 100 * unique.sum() / a,
                             THREADS * seconds * 1e6 / a, cpu.sum() / 1000.0 / a, jvm / 1000.0 / a, serverCpu / a, exec / a, 100 * busy,
                             firstOther.get() == null ? "" : " | first other: " + firstOther.get()));
    }

    private static void spin() {
        long until = System.nanoTime() + SPIN_NANOS;
        while (System.nanoTime() < until) Thread.onSpinWait();
    }

    private static boolean isUnique(Throwable t) {
        for (var c = t; c != null; c = c.getCause()) if (c instanceof SQLException s && "23505".equals(s.getSQLState())) return true;
        return false;
    }

    // ------------------------------------------------------------------ helpers

    private static HikariDataSource pool(Pg pg) {
        var config = new HikariConfig();
        config.setJdbcUrl(pg.server.getJdbcUrl());
        config.setUsername(pg.server.role("writer"));
        config.setPassword(pg.server.password("writer"));
        config.setPoolName("p06-" + UUID.randomUUID().toString().substring(0, 4));
        return new HikariDataSource(config);
    }

    private static void createTable(Pg pg, String table) throws SQLException {
        try (var s = pg.observer.createStatement()) {
            s.execute("DROP TABLE IF EXISTS " + table);
            s.execute("""
                      CREATE TABLE %s (
                          global_order       bigint GENERATED BY DEFAULT AS IDENTITY PRIMARY KEY,
                          aggregate_id       text NOT NULL,
                          event_order        bigint NOT NULL,
                          event_id           text NOT NULL,
                          caused_by_event_id text,
                          correlation_id     text,
                          event_type         text NOT NULL,
                          event_revision     text NOT NULL,
                          timestamp          timestamptz NOT NULL,
                          event_payload      jsonb NOT NULL,
                          event_metadata     jsonb NOT NULL,
                          tenant             text,
                          UNIQUE (aggregate_id, event_order),
                          UNIQUE (event_id)
                      )""".formatted(table));
        }
    }

    /** A fresh table with {@value PRELOAD_AGGREGATES} aggregates of {@value PRELOAD_EVENTS} events each; returns the aggregate ids. */
    private static List<String> preload(Pg pg, String table) throws SQLException {
        createTable(pg, table);
        var ids = new ArrayList<String>();
        for (int i = 0; i < PRELOAD_AGGREGATES; i++) ids.add(UUID.randomUUID().toString());
        try (var s = pg.observer.createStatement()) {
            s.execute(("INSERT INTO %s (aggregate_id, event_order, event_id, correlation_id, event_type, event_revision, timestamp, event_payload, event_metadata) "
                    + "SELECT a.id, e.n, gen_random_uuid()::text, gen_random_uuid()::text, '%s', '1', now(), "
                    + "jsonb_build_object('orderId', a.id, 'productId', gen_random_uuid()::text, 'quantity', 1, 'note', 'spike payload of roughly the engine''s size'), "
                    + "jsonb_build_object('trace', gen_random_uuid()::text) "
                    + "FROM unnest('{%s}'::text[]) AS a(id), generate_series(0, %d) AS e(n) ORDER BY e.n, a.id")
                              .formatted(table, UnattributedCpuSpikeIT.TYPE, String.join(",", ids), PRELOAD_EVENTS - 1));
            s.execute("VACUUM ANALYZE " + table);
        }
        return ids;
    }

    /** {busy jiffies, total jiffies} of the whole VM. */
    private static long[] machine() {
        try {
            var f = Files.readAllLines(Path.of("/proc/stat")).getFirst().trim().split("\\s+");
            long total = 0;
            for (int i = 1; i < f.length; i++) total += Long.parseLong(f[i]);
            long idle = Long.parseLong(f[4]) + Long.parseLong(f[5]);
            return new long[]{total - idle, total};
        } catch (Exception e) {
            return new long[]{0, 0};
        }
    }

    private static long jvmCpu() {
        return ((com.sun.management.OperatingSystemMXBean) ManagementFactory.getOperatingSystemMXBean()).getProcessCpuTime();
    }

    @Test
    void protocol_trace() throws Exception {
        var logger  = Logger.getLogger("org.postgresql");
        var lines   = Collections.synchronizedList(new ArrayList<String>());
        var handler = new Handler() {
            @Override
            public void publish(LogRecord r) {
                var m = r.getMessage();
                if (r.getParameters() != null) m = java.text.MessageFormat.format(m, r.getParameters());
                lines.add(m);
            }

            @Override
            public void flush() {
            }

            @Override
            public void close() {
            }
        };
        try (var pg = Pg.start()) {
            var w    = new Writer(Stack.writer(pg, "writer"));
            var type = w.freshType("trace");
            var cfg  = w.config(type);
            var id   = OrderId.random();
            w.stack.unitOfWorkFactory.usingUnitOfWork(uow -> w.stack.eventStore.appendToStream(type, id, Optional.empty(), List.of(new OrderEvent.OrderAdded(id, CustomerId.random(), 1))));
            for (var arm : List.of("A", "C", "A", "C")) {
                for (int i = 0; i < 12; i++) {
                    boolean traced = i == 11;
                    if (traced) {
                        lines.clear();
                        logger.setLevel(Level.FINEST);
                        logger.addHandler(handler);
                    }
                    w.stack.unitOfWorkFactory.usingUnitOfWork(uow -> {
                        long last = w.stack.eventStore.loadLastPersistedEventRelatedTo(type, id).orElseThrow().eventOrder().longValue();
                        var events = List.<Object>of(new OrderEvent.ProductAddedToOrder(id, ProductId.random(), 1));
                        if (arm.equals("A")) w.stack.eventStore.appendToStream(type, id, Optional.of(last), events);
                        else w.rawBatch(uow, cfg, new Append(id, Optional.of(last), events));
                    });
                    if (traced) {
                        logger.removeHandler(handler);
                        logger.setLevel(Level.OFF);
                        record("--- TRACE arm " + arm + " (unit of work #12 of this arm on a warm pool)");
                        for (var l : lines) record("  " + l.replaceAll("\\s+", " ").substring(0, Math.min(300, l.replaceAll("\\s+", " ").length())));
                    }
                }
            }
            try (var s = pg.observer.createStatement(); var rs = s.executeQuery("SELECT calls, plans, query FROM pg_stat_statements ORDER BY calls DESC LIMIT 12")) {
                while (rs.next()) record(String.format("  pgss calls %d plans %d | %s", rs.getLong(1), rs.getLong(2), rs.getString(3).replaceAll("\\s+", " ")));
            }
            w.stack.close();
        }
    }

    /**
     * Reconciles two round-trip counts that disagreed: P0.0's {@link RoundTripCountingProxy} reported 3 round trips for
     * "append 1 event, no expected order", while {@link #protocol_trace()} shows 4 for a unit of work with the last-event
     * lookup. Every unit of work here runs through the proxy AND with the pgjdbc wire trace (JUL {@code org.postgresql} at
     * FINEST, this thread only) at the same time, so both counts belong to the same bytes. The trace's count uses the
     * proxy's definition: a client flight is a run of frontend messages after the client last received something.
     * <p>
     * Shapes: P0.0's two one-event cases exactly as {@code CurrentEngineBaselineSpikeIT.Stack#appendInOneUnitOfWork}
     * runs them (a NEW aggregate, so the engine's own lookup finds no row; or the expected order given, so no lookup),
     * P0.6's traced shape (explicit {@code loadLastPersistedEventRelatedTo} on an EXISTING aggregate, then the append with
     * that order), and the engine's own lookup on an existing aggregate (no expected order given).
     */
    @Test
    void round_trip_reconciliation() throws Exception {
        int  iterations = Integer.getInteger("spike.jdbc.reconcileIterations", 50);
        var  logger     = Logger.getLogger("org.postgresql");
        var  testThread = Thread.currentThread().threadId();
        var  messages   = Collections.synchronizedList(new ArrayList<String>());
        var handler = new Handler() {
            @Override
            public void publish(LogRecord r) {
                if (r.getLongThreadID() != testThread) return;
                var m = r.getMessage();
                if (m == null) return;
                if (r.getParameters() != null) m = java.text.MessageFormat.format(m, r.getParameters());
                var t = m.trim();
                if (t.startsWith("FE=>") || t.startsWith("<=BE")) messages.add(t);
            }

            @Override
            public void flush() {
            }

            @Override
            public void close() {
            }
        };
        record("");
        record("=== ROUND-TRIP RECONCILIATION | " + Instant.now() + " | " + SpikeServer.target("Testcontainers") + " | pgjdbc " + org.postgresql.Driver.getVersion()
                       + " | " + iterations + " traced units of work per shape, after 20 warm-up units; proxy flights and trace flights counted on the same unit of work");
        try (var pg = Pg.start(); var proxy = new RoundTripCountingProxy(pg.server.host(), pg.server.port())) {
            var stack = Stack.writer(pg, "writer", pg.server.viaProxy(proxy.port()));
            var w     = new Writer(stack);
            var type  = w.freshType("rtrec");
            var existing = OrderId.random();
            stack.unitOfWorkFactory.usingUnitOfWork(uow -> stack.eventStore.appendToStream(type, existing, Optional.empty(),
                                                                                             List.of(new OrderEvent.OrderAdded(existing, CustomerId.random(), 1))));
            // let Hikari fill the pool, so no connection set-up lands in a measured window
            var until = System.nanoTime() + Duration.ofSeconds(30).toNanos();
            while (stack.dataSource.getHikariPoolMXBean().getTotalConnections() < stack.dataSource.getMaximumPoolSize() && System.nanoTime() < until) Thread.sleep(50);
            Thread.sleep(200);

            record Shape(String label, Runnable unitOfWork) {
            }
            var shapes = List.of(
                    new Shape("P0.0 '1 event, no expected order': NEW aggregate, engine lookup finds no row",
                              () -> stack.unitOfWorkFactory.usingUnitOfWork(uow -> {
                                  var id = OrderId.random();
                                  stack.eventStore.appendToStream(type, id, Optional.empty(), List.of(new OrderEvent.OrderAdded(id, CustomerId.random(), 1)));
                              })),
                    new Shape("P0.0 '1 event, expected order given': NEW aggregate, no lookup",
                              () -> stack.unitOfWorkFactory.usingUnitOfWork(uow -> {
                                  var id = OrderId.random();
                                  stack.eventStore.appendToStream(type, id, Optional.of(EventOrder.NO_EVENTS_PREVIOUSLY_PERSISTED.longValue()),
                                                                  List.of(new OrderEvent.OrderAdded(id, CustomerId.random(), 1)));
                              })),
                    new Shape("P0.6 protocol_trace arm A: EXISTING aggregate, explicit lookup finds a row, append with that order",
                              () -> stack.unitOfWorkFactory.usingUnitOfWork(uow -> {
                                  long last = stack.eventStore.loadLastPersistedEventRelatedTo(type, existing).orElseThrow().eventOrder().longValue();
                                  stack.eventStore.appendToStream(type, existing, Optional.of(last), List.of(new OrderEvent.ProductAddedToOrder(existing, ProductId.random(), 1)));
                              })),
                    new Shape("EXISTING aggregate, no expected order: engine lookup finds a row",
                              () -> stack.unitOfWorkFactory.usingUnitOfWork(uow -> stack.eventStore.appendToStream(type, existing, Optional.empty(),
                                                                                                                  List.of(new OrderEvent.ProductAddedToOrder(existing, ProductId.random(), 1))))));
            for (var shape : shapes) {
                for (int i = 0; i < 20; i++) shape.unitOfWork().run();
                long          proxyTotal = 0, traceTotal = 0, disagreements = 0;
                List<String>  example    = List.of();
                logger.setLevel(Level.FINEST);
                logger.addHandler(handler);
                try {
                    for (int i = 0; i < iterations; i++) {
                        messages.clear();
                        proxy.reset();
                        shape.unitOfWork().run();
                        long proxyFlights = proxy.flights();
                        List<String> trace;
                        synchronized (messages) {
                            trace = new ArrayList<>(messages);
                        }
                        long traceFlights = traceFlights(trace);
                        proxyTotal += proxyFlights;
                        traceTotal += traceFlights;
                        if (proxyFlights != traceFlights) disagreements++;
                        if (i == 0) example = trace;
                    }
                } finally {
                    logger.removeHandler(handler);
                    logger.setLevel(Level.OFF);
                }
                record(String.format("RT-RECONCILE | %-100s | proxy flights per UoW %.2f | trace flights per UoW %.2f | UoWs where they differ %d of %d",
                                     shape.label(), (double) proxyTotal / iterations, (double) traceTotal / iterations, disagreements, iterations));
                record("  flights of the first traced UoW: " + flightsSummary(example));
            }
            stack.close();
        }
    }

    /** Client flights in a pgjdbc trace: each run of {@code FE=>} messages that follows a {@code <=BE} message (or starts the trace). */
    static long traceFlights(List<String> trace) {
        long    flights  = 0;
        boolean lastFrom = false; // true = last message was from the client
        for (var m : trace) {
            if (m.startsWith("FE=>")) {
                if (!lastFrom) flights++;
                lastFrom = true;
            } else {
                lastFrom = false;
            }
        }
        return flights;
    }

    /** The trace as flights: "[1] FE Parse Bind Execute Sync | BE ParseComplete ... ; [2] ...", message names only. */
    static String flightsSummary(List<String> trace) {
        var     sb       = new StringBuilder();
        boolean lastFrom = false;
        int     flight   = 0;
        String  side     = "";
        for (var m : trace) {
            boolean fe   = m.startsWith("FE=>");
            var     name = m.substring(4).trim();
            int     cut  = name.indexOf('(');
            if (cut > 0) name = name.substring(0, cut);
            cut = name.indexOf(' ');
            if (cut > 0) name = name.substring(0, cut);
            if (fe && !lastFrom) {
                sb.append(flight == 0 ? "" : " ; ").append('[').append(++flight).append("] FE");
                side = "FE";
            } else if (!fe && !side.equals("BE")) {
                sb.append(" | BE");
                side = "BE";
            }
            sb.append(' ').append(name);
            lastFrom = fe;
        }
        return sb.toString();
    }

    private static void record(String line) {
        SpikePostgres.record(RESULTS, line);
    }
}
