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
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.postgresql.PostgreSQLContainer;

import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.sql.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;

/**
 * Phase 0, §9 of {@code docs/plans/event-store-phase0-append.md}: where does the server CPU per contended append go
 * that {@code pg_stat_statements} does not record, and is it the cost of CPUs going idle and being woken on this
 * Docker VM rather than anything the statements do?
 * <p>
 * 8 writers, 10 aggregates, raw pgjdbc, every arm with the same lookup, the same parameters (types, casts, values)
 * and the same client code; only the append statement and the client "mode" differ. Statement arms:
 * <ul>
 *     <li>{@code A_VALUES} - {@code INSERT ... VALUES ... RETURNING global_order} (today's insert shape);</li>
 *     <li>{@code A_SELECT} - the same as {@code INSERT ... SELECT} with no {@code FROM} and no guard;</li>
 *     <li>{@code B_NO_CTE} - {@code A_SELECT} plus the head check as a scalar sub-select in {@code WHERE};</li>
 *     <li>{@code B_NO_XID} - the head check as §6's {@code head}/{@code ok} CTEs, without the {@code xid} CTE;</li>
 *     <li>{@code B} - §6's {@code SCALAR_1} statement: {@code xid}, {@code head} and {@code ok} CTEs, two
 *     {@code RETURNING} columns;</li>
 *     <li>{@code B_RET1} - {@code B} returning only {@code global_order};</li>
 *     <li>{@code B_COMMIT0} - {@code B}, but a zero-row result is committed instead of rolled back.</li>
 * </ul>
 * Modes (an arm is written {@code ARM@MODE}, default {@code NORMAL}): {@code NORMAL}; {@code SPIN} (a 50 µs
 * busy-wait between lookup and append, as §8.3); {@code BURNER} (client unchanged, but a privileged sidecar keeps
 * all 8 CPUs busy with spinning shells at {@code SCHED_IDLE} policy in a cgroup marked {@code cpu.idle=1}, so a CPU never enters WFI and any
 * normal task that wakes preempts the spinner at once).
 * <p>
 * Per window: appends and attempts per second, outcome mix, the PostgreSQL container's cgroup CPU (user and system),
 * the writer backends' own scheduler time ({@code /proc/<pid>/schedstat}: on-CPU and run-queue wait), their context
 * switches, {@code pg_stat_statements}' recorded execution, machine busy fraction, context switches, interrupts and
 * IPIs ({@code /proc/stat}, {@code /proc/interrupts}). With {@code -Dspike.cpu.perf=true} a privileged
 * {@code perf} sidecar ({@code --pid=host}) samples the whole VM ({@code cpu-clock}, the VM has no hardware PMU) for
 * every measured window and writes a per-window profile of the {@code postgres} processes to
 * {@code target/spike-results/p0-9-perf/}. {@code -Dspike.cpu.pgCpus=4-7} pins the database container to a CPU set
 * (pin the JVM with {@code taskset} on the Maven command). Results go to {@code target/spike-results/p0-9-cpu.txt}.
 * <p>
 * Throwaway: runs only with {@code -Dspike.run=true}.
 */
@Tag("spike")
@EnabledIfSystemProperty(named = "spike.run", matches = "true")
class UnattributedCpuSpikeIT {
    private static final String  RESULTS    = "p0-9-cpu.txt";
    private static final String  IMAGE      = "postgres:17.5";
    private static final int     RUNS       = Integer.getInteger("spike.cpu.runs", 2);
    private static final int     SECONDS    = Integer.getInteger("spike.cpu.seconds", 20);
    private static final int     WRITERS    = 8;
    private static final int     AGGREGATES = 10;
    private static final long    SPIN_NANOS = Long.getLong("spike.cpu.spinMicros", 50) * 1000;
    private static final boolean PERF       = Boolean.getBoolean("spike.cpu.perf");
    private static final String  PG_CPUS    = System.getProperty("spike.cpu.pgCpus", "");
    static final String  PAYLOAD    = "{\"orderId\":\"%s\",\"productId\":\"%s\",\"quantity\":1,\"note\":\"spike payload of roughly the engine's size\"}";
    static final String  METADATA   = "{\"trace\":\"%s\"}";
    static final String  TYPE       = "FQCN:dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.test_data.OrderEvent$ProductAddedToOrder";
    private static final String  COLS       = "aggregate_id, event_order, event_id, caused_by_event_id, correlation_id, event_type, event_revision, timestamp, event_payload, event_metadata, tenant";
    private static final String  VALS       = "?::text, %s, ?::text, ?::text, ?::text, ?::text, ?::text, ?::timestamptz, ?::jsonb, ?::jsonb, ?::text";

    enum Arm {A_VALUES, A_SELECT, B_NO_CTE, B_NO_XID, B, B_RET1, B_COMMIT0}

    enum Mode {NORMAL, SPIN, BURNER}

    /** Where the expected order goes in the statement's parameters: {@code GUARD_FIRST} = agg, exp, exp, then the row; {@code GUARD_LAST} = the row (order = exp + 1), then agg, exp. */
    static String sql(Arm arm, String t) {
        var head = "WITH xid  AS MATERIALIZED (SELECT pg_current_xact_id() AS x),\n" +
                "     head AS MATERIALIZED (SELECT coalesce(max(event_order), -1) AS v FROM " + t + " WHERE aggregate_id = ?::text),\n" +
                "     ok   AS (SELECT head.v FROM head, xid WHERE ?::bigint IS NULL OR head.v = ?::bigint)\n";
        var headNoXid = "WITH head AS MATERIALIZED (SELECT coalesce(max(event_order), -1) AS v FROM " + t + " WHERE aggregate_id = ?::text),\n" +
                "     ok   AS (SELECT head.v FROM head WHERE ?::bigint IS NULL OR head.v = ?::bigint)\n";
        var insert = "INSERT INTO " + t + " (" + COLS + ")\n";
        var fromOk = "SELECT " + VALS.formatted("ok.v + 1") + " FROM ok\n";
        return switch (arm) {
            case A_VALUES -> insert + "VALUES (" + VALS.formatted("?::bigint") + ") RETURNING global_order";
            case A_SELECT -> insert + "SELECT " + VALS.formatted("?::bigint") + " RETURNING global_order";
            case B_NO_CTE -> insert + "SELECT " + VALS.formatted("?::bigint") + "\nWHERE (SELECT coalesce(max(event_order), -1) FROM " + t + " WHERE aggregate_id = ?::text) = ?::bigint\n"
                    + "RETURNING global_order, event_order";
            case B_NO_XID -> headNoXid + insert + fromOk + "RETURNING global_order, event_order";
            case B, B_COMMIT0 -> head + insert + fromOk + "RETURNING global_order, event_order";
            case B_RET1 -> head + insert + fromOk + "RETURNING global_order";
        };
    }

    static boolean guardFirst(Arm arm) {
        return arm == Arm.B_NO_XID || arm == Arm.B || arm == Arm.B_RET1 || arm == Arm.B_COMMIT0;
    }

    // ------------------------------------------------------------------ the test

    @Test
    void unattributed_cpu() throws Exception {
        var specs = Arrays.stream(System.getProperty("spike.cpu.arms", "A_VALUES,B,A_VALUES@BURNER,B@BURNER,A_VALUES@SPIN,B@SPIN").split(",")).map(String::trim).toList();
        var palindrome = new ArrayList<>(specs);
        var reversed   = new ArrayList<>(specs);
        Collections.reverse(reversed);
        palindrome.addAll(reversed);
        record("");
        record("=== UNATTRIBUTED CPU | " + Instant.now() + " | runs " + RUNS + " | " + WRITERS + " writers, " + AGGREGATES + " aggregates, " + SECONDS
                       + " s per window after a 2 s warm-up, fresh table per window (dropped after), palindrome order " + palindrome + " (run 2 rotated by one) | " + IMAGE
                       + " fsync=off | pg cpuset '" + PG_CPUS + "' | JVM affinity " + affinity() + " | perf " + PERF
                       + (SpikeServer.external() ? " | " + SpikeServer.target("") + " | external: fsync, cpuset, BURNER and perf do not apply; backend /proc counters read NaN; "
                               + "machine busy, ctxt, intr and IPIs are the CLIENT machine's" : ""));
        for (int run = 1; run <= RUNS; run++) {
            var order = new ArrayList<>(palindrome);
            Collections.rotate(order, run - 1);
            try (var pg = Pg.start(); var perf = PERF && !SpikeServer.external() ? Perf.start() : null) {
                int n = 0;
                for (var spec : order) {
                    var parts = spec.split("@");
                    var arm   = Arm.valueOf(parts[0]);
                    var mode  = parts.length > 1 ? Mode.valueOf(parts[1]) : Mode.NORMAL;
                    if (mode == Mode.BURNER && pg.server.container == null) {
                        record("run " + run + " | " + arm + " @BURNER skipped: the burner is a sidecar container on the database's Docker host, not available on an external server");
                        n++;
                        continue;
                    }
                    GenericContainer<?> burner = mode == Mode.BURNER ? burner() : null;
                    try {
                        window(pg, null, arm, mode, "r" + run + "w" + n + "warm", Duration.ofSeconds(2), run, true);
                        window(pg, perf, arm, mode, "r" + run + "w" + n, Duration.ofSeconds(SECONDS), run, false);
                    } finally {
                        if (burner != null) burner.stop();
                    }
                    n++;
                }
            }
        }
    }

    private void window(Pg pg, Perf perf, Arm arm, Mode mode, String table, Duration duration, int run, boolean discard) throws Exception {
        var t = "cpu_" + table;
        try (var s = pg.observer.createStatement()) {
            s.execute("DROP TABLE IF EXISTS " + t);
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
                      )""".formatted(t));
            s.execute("GRANT ALL ON " + t + " TO " + pg.server.role("writer"));
        }
        var aggregates = new ArrayList<String>();
        try (var c = writer(pg); var ps = c.prepareStatement(sql(Arm.A_VALUES, t))) {
            for (int i = 0; i < AGGREGATES; i++) {
                var id = UUID.randomUUID().toString();
                aggregates.add(id);
                bind(ps, Arm.A_VALUES, id, -1);
                ps.executeQuery().close();
            }
            c.commit();
        }
        var lookup = "SELECT coalesce(max(event_order), -1) FROM " + t + " WHERE aggregate_id = ?";
        var append = sql(arm, t);
        // connections and their backend pids before the clock starts
        var connections = new ArrayList<Connection>();
        var pids = new ArrayList<Long>();
        for (int w = 0; w < WRITERS; w++) {
            var c = writer(pg);
            connections.add(c);
            try (var s = c.createStatement(); var rs = s.executeQuery("SELECT pg_backend_pid()")) {
                rs.next();
                pids.add(rs.getLong(1));
            }
            c.commit();
        }
        long rows0 = rowCount(pg, t);
        if (!discard) resetStatements(pg);
        var backends0 = backends(pg, pids);
        var cg0       = pg.server.cpuUserSystemMicros();
        var stat0     = procStat();
        var ipi0      = ipis();
        long jvm0     = jvmCpuNanos();
        var perfRun   = !discard && perf != null ? perf.record(table, duration) : null;

        var attempts = new AtomicLong();
        var zero = new AtomicLong();
        var unique = new AtomicLong();
        var other = new AtomicLong();
        var firstOther = new AtomicReference<Throwable>();
        var lookupNanos = new AtomicLong();
        var appendNanos = new AtomicLong();
        var endNanos = new AtomicLong();
        long stopAt = System.nanoTime() + duration.toNanos();
        var threads = new ArrayList<Thread>();
        for (int w = 0; w < WRITERS; w++) {
            var c = connections.get(w);
            var thread = Thread.ofPlatform().name("cpu-writer-" + w).unstarted(() -> {
                try (var lk = c.prepareStatement(lookup); var ap = c.prepareStatement(append)) {
                    var r = ThreadLocalRandom.current();
                    while (System.nanoTime() < stopAt) {
                        var id = aggregates.get(r.nextInt(aggregates.size()));
                        attempts.incrementAndGet();
                        long t0 = System.nanoTime(), t1 = t0, t2 = t0;
                        try {
                            lk.setString(1, id);
                            long last;
                            try (var rs = lk.executeQuery()) {
                                rs.next();
                                last = rs.getLong(1);
                            }
                            if (mode == Mode.SPIN) {
                                long until = System.nanoTime() + SPIN_NANOS;
                                while (System.nanoTime() < until) Thread.onSpinWait();
                            }
                            t1 = System.nanoTime();
                            bind(ap, arm, id, last);
                            boolean written;
                            try (var rs = ap.executeQuery()) {
                                written = rs.next();
                            }
                            t2 = System.nanoTime();
                            if (written) c.commit();
                            else {
                                zero.incrementAndGet();
                                if (arm == Arm.B_COMMIT0) c.commit();
                                else c.rollback();
                            }
                        } catch (SQLException e) {
                            t2 = System.nanoTime();
                            c.rollback();
                            if ("23505".equals(e.getSQLState())) unique.incrementAndGet();
                            else if (other.incrementAndGet() == 1) firstOther.set(e);
                        }
                        long t3 = System.nanoTime();
                        lookupNanos.addAndGet(t1 - t0);
                        appendNanos.addAndGet(t2 - t1);
                        endNanos.addAndGet(t3 - t2);
                    }
                } catch (SQLException e) {
                    firstOther.compareAndSet(null, e);
                }
            });
            threads.add(thread);
        }
        long wall0 = System.nanoTime();
        threads.forEach(Thread::start);
        for (var thread : threads) thread.join();
        double seconds = (System.nanoTime() - wall0) / 1e9;

        long jvm1     = jvmCpuNanos();
        var ipi1      = ipis();
        var stat1     = procStat();
        var cg1       = pg.server.cpuUserSystemMicros();
        var backends1 = backends(pg, pids);
        for (var c : connections) c.close();
        String perfSummary = perfRun != null ? perfRun.finish() : null;
        long rows1 = rowCount(pg, t);
        double exec = discard ? 0 : recordedExecMicros(pg);
        try (var s = pg.observer.createStatement()) {
            s.execute("DROP TABLE " + t);
        }
        if (discard) return;

        double a = attempts.get();
        long appended = rows1 - rows0;
        long[] d = new long[stat0.length];
        for (int i = 0; i < d.length; i++) d[i] = stat1[i] - stat0[i];
        long jiffies = 0;
        for (int i = 0; i < 8; i++) jiffies += d[i];
        double busy = jiffies == 0 ? 0 : 1 - (d[3] + d[4]) / (double) jiffies;
        double sysShare = jiffies == 0 ? 0 : (d[2] + d[5] + d[6]) / (double) jiffies;
        double[] b = delta(backends0, backends1, 5);
        double[] cg = delta(cg0, cg1, 3);
        record(String.format("run %d | %-9s @%-6s | appended/s %7.0f | attempts/s %7.0f | zero %4.1f%% unique %4.1f%% other %d | wall µs/att %5.1f (lookup %5.1f, append %5.1f, end %5.1f) | "
                                     + "PG cgroup µs/att %6.1f (user %5.1f, sys %5.1f) | backends on-CPU µs/att %5.1f, runq wait %5.1f, slices %.2f, vcsw %.2f, ivcsw %.3f | recorded exec µs/att %5.1f | "
                                     + "JVM CPU µs/att %5.1f | machine busy %4.1f%% (sys+irq %4.1f%%) | ctxt/att %.2f, intr/att %.2f, resched IPI/att %.3f, call IPI/att %.3f%s",
                             run, arm, mode, appended / seconds, a / seconds, 100 * zero.get() / a, 100 * unique.get() / a, other.get(),
                             WRITERS * seconds * 1e6 / a, lookupNanos.get() / a / 1000, appendNanos.get() / a / 1000, endNanos.get() / a / 1000,
                             cg[0] / a, cg[1] / a, cg[2] / a,
                             b[0] / a / 1000, b[1] / a / 1000, b[2] / a, b[3] / a, b[4] / a, exec / a,
                             (jvm1 - jvm0) / a / 1000, 100 * busy, 100 * sysShare, d[8] / a, d[9] / a, (ipi1[0] - ipi0[0]) / a, (ipi1[1] - ipi0[1]) / a,
                             firstOther.get() == null ? "" : " | first other: " + firstOther.get()));
        if (perfSummary != null) record("     perf " + table + " " + arm + "@" + mode + " | attempts " + attempts.get() + " | " + perfSummary);
    }

    private static void bind(PreparedStatement ps, Arm arm, String id, long last) throws SQLException {
        int p = 1;
        if (guardFirst(arm)) {
            ps.setString(p++, id);
            ps.setLong(p++, last);
            ps.setLong(p++, last);
        }
        ps.setString(p++, id);
        if (!guardFirst(arm)) ps.setLong(p++, last + 1);
        var eventId = UUID.randomUUID().toString();
        ps.setString(p++, eventId);
        ps.setString(p++, null);
        ps.setString(p++, eventId);
        ps.setString(p++, TYPE);
        ps.setString(p++, "1");
        ps.setString(p++, OffsetDateTime.now(ZoneOffset.UTC).toString());
        ps.setString(p++, PAYLOAD.formatted(id, eventId));
        ps.setString(p++, METADATA.formatted(eventId));
        ps.setString(p++, null);
        if (arm == Arm.B_NO_CTE) {
            ps.setString(p++, id);
            ps.setLong(p, last);
        }
    }

    // ------------------------------------------------------------------ measurement helpers

    /** {@code after - before} per element; all NaN when either is null (not available on an external server). */
    private static double[] delta(long[] before, long[] after, int length) {
        var d = new double[length];
        for (int i = 0; i < length; i++) d[i] = before == null || after == null ? Double.NaN : after[i] - before[i];
        return d;
    }

    /** Per backend pid summed: {on-CPU ns, run-queue wait ns, timeslices, voluntary switches, involuntary switches}; null on an external server. */
    private static long[] backends(Pg pg, List<Long> pids) throws Exception {
        if (pg.server.container == null) return null;
        var script = new StringBuilder();
        for (var pid : pids) script.append("cat /proc/").append(pid).append("/schedstat; grep ctxt_switches /proc/").append(pid).append("/status;");
        var out = pg.server.container.execInContainer("sh", "-c", script.toString()).getStdout();
        long[] r = new long[5];
        for (var line : out.split("\n")) {
            var f = line.trim().split("\\s+");
            if (f.length == 3 && !line.contains(":")) {
                r[0] += Long.parseLong(f[0]);
                r[1] += Long.parseLong(f[1]);
                r[2] += Long.parseLong(f[2]);
            } else if (line.startsWith("voluntary_ctxt_switches")) r[3] += Long.parseLong(f[1]);
            else if (line.startsWith("nonvoluntary_ctxt_switches")) r[4] += Long.parseLong(f[1]);
        }
        return r;
    }

    /** {user, nice, system, idle, iowait, irq, softirq, steal, ctxt, intr} of the whole VM. */
    private static long[] procStat() throws Exception {
        long[] r = new long[10];
        for (var line : Files.readAllLines(Path.of("/proc/stat"))) {
            var f = line.trim().split("\\s+");
            if (f[0].equals("cpu")) for (int i = 0; i < 8; i++) r[i] = Long.parseLong(f[i + 1]);
            else if (f[0].equals("ctxt")) r[8] = Long.parseLong(f[1]);
            else if (f[0].equals("intr")) r[9] = Long.parseLong(f[1]);
        }
        return r;
    }

    /** {rescheduling IPIs, function-call IPIs} summed over CPUs (arm64 {@code IPI0}/{@code IPI1}). */
    private static long[] ipis() throws Exception {
        long[] r = new long[2];
        for (var line : Files.readAllLines(Path.of("/proc/interrupts"))) {
            var f = line.trim().split("\\s+");
            int idx = f[0].equals("IPI0:") ? 0 : f[0].equals("IPI1:") ? 1 : -1;
            if (idx < 0) continue;
            for (int i = 1; i < f.length && f[i].matches("\\d+"); i++) r[idx] += Long.parseLong(f[i]);
        }
        return r;
    }

    private static long jvmCpuNanos() {
        return ((com.sun.management.OperatingSystemMXBean) java.lang.management.ManagementFactory.getOperatingSystemMXBean()).getProcessCpuTime();
    }

    private static String affinity() {
        try {
            for (var line : Files.readAllLines(Path.of("/proc/self/status"))) if (line.startsWith("Cpus_allowed_list")) return line.split(":\\s*")[1].trim();
        } catch (Exception e) {
            // ignore
        }
        return "?";
    }

    private static long rowCount(Pg pg, String t) throws SQLException {
        return SpikePostgres.queryLong(pg.observer, "SELECT count(*) FROM " + t);
    }

    private static void resetStatements(Pg pg) throws SQLException {
        try (var s = pg.observer.createStatement()) {
            s.execute("SELECT pg_stat_statements_reset()");
        }
    }

    /** {@code pg_stat_statements}' total execution time of the writer role's statements, µs. */
    private static double recordedExecMicros(Pg pg) throws SQLException {
        return SpikePostgres.queryLong(pg.observer, "SELECT coalesce(round(sum(s.total_exec_time) * 1000), 0)::bigint FROM pg_stat_statements s JOIN pg_roles r ON r.oid = s.userid WHERE r.rolname = '" + pg.server.role("writer") + "'");
    }

    private static Connection writer(Pg pg) throws SQLException {
        var c = DriverManager.getConnection(pg.server.getJdbcUrl(), pg.server.role("writer"), pg.server.password("writer"));
        c.setAutoCommit(false);
        return c;
    }

    private static void record(String line) {
        SpikePostgres.record(RESULTS, line);
    }

    /** 8 spinning shells in a privileged container whose cgroup is marked {@code cpu.idle=1}. */
    private static GenericContainer<?> burner() {
        var burner = new GenericContainer<>(IMAGE) // Debian: util-linux chrt
                .withPrivilegedMode(true)
                .withCommand("sh", "-c", "echo 1 > /sys/fs/cgroup/cpu.idle && cat /sys/fs/cgroup/cpu.idle && for i in 1 2 3 4 5 6 7 8; do chrt -i 0 sh -c \"while :; do :; done\" & done; wait");
        burner.start();
        var idle = "";
        for (int i = 0; i < 50 && idle.isEmpty(); i++) {
            idle = burner.getLogs().trim();
            if (idle.isEmpty()) LockSupportSleep.sleep();
        }
        if (!idle.equals("1")) throw new IllegalStateException("burner cgroup not idle: '" + idle + "'");
        return burner;
    }

    private static final class LockSupportSleep {
        static void sleep() {
            java.util.concurrent.locks.LockSupport.parkNanos(100_000_000L);
        }
    }

    // ------------------------------------------------------------------ containers

    static final class Pg implements AutoCloseable {
        final SpikeServer server;
        final Connection  observer;

        private Pg(SpikeServer server) throws SQLException {
            this.server = server;
            this.observer = DriverManager.getConnection(server.getJdbcUrl(), server.getUsername(), server.getPassword());
        }

        static Pg start() throws SQLException {
            if (SpikeServer.external()) {
                // fsync is whatever the server runs with (recorded in the header); the container path insists on off
                var pg = new Pg(SpikeServer.external("cpu", "observer", true));
                try (var s = pg.observer.createStatement()) {
                    s.execute("CREATE EXTENSION IF NOT EXISTS pg_stat_statements");
                }
                pg.server.createRole(pg.observer, "writer");
                return pg;
            }
            var container = new PostgreSQLContainer(IMAGE).withDatabaseName("cpu").withUsername("observer").withPassword("observer")
                                                          .withCommand("postgres", "-c", "fsync=off", "-c", "shared_preload_libraries=pg_stat_statements",
                                                                       "-c", "pg_stat_statements.max=10000");
            if (!PG_CPUS.isBlank()) container.withCreateContainerCmdModifier(cmd -> cmd.getHostConfig().withCpusetCpus(PG_CPUS));
            container.start();
            var pg = new Pg(SpikeServer.of(container));
            try (var s = pg.observer.createStatement()) {
                s.execute("CREATE EXTENSION IF NOT EXISTS pg_stat_statements");
                s.execute("CREATE ROLE writer LOGIN SUPERUSER PASSWORD 'writer'");
                try (var rs = s.executeQuery("SHOW fsync")) {
                    rs.next();
                    if (!rs.getString(1).equals("off")) throw new IllegalStateException("fsync is not off");
                }
            }
            return pg;
        }

        @Override
        public void close() throws Exception {
            observer.close();
            server.close();
        }
    }

    /** A privileged {@code perf} sidecar in the VM's pid namespace. */
    static final class Perf implements AutoCloseable {
        private final GenericContainer<?> container;

        private Perf(GenericContainer<?> container) {
            this.container = container;
        }

        static Perf start() throws Exception {
            var container = new GenericContainer<>("alpine:latest")
                    .withPrivilegedMode(true)
                    .withCreateContainerCmdModifier(cmd -> cmd.getHostConfig().withPidMode("host"))
                    .withCommand("sleep", "infinity");
            container.start();
            var r = container.execInContainer("apk", "add", "--no-cache", "perf");
            if (r.getExitCode() != 0) throw new IllegalStateException("apk add perf failed: " + r.getStderr());
            return new Perf(container);
        }

        PerfRun record(String name, Duration duration) {
            var future = CompletableFuture.supplyAsync(() -> {
                try {
                    return container.execInContainer("perf", "record", "-a", "-F", "997", "-o", "/tmp/" + name + ".data", "--", "sleep", String.valueOf(duration.toSeconds()));
                } catch (Exception e) {
                    throw new CompletionException(e);
                }
            });
            return new PerfRun(this, name, future);
        }

        @Override
        public void close() {
            container.stop();
        }
    }

    record PerfRun(Perf perf, String name, CompletableFuture<org.testcontainers.containers.Container.ExecResult> future) {
        /** Waits for the recording, writes the full reports to {@code p0-9-perf/<name>.txt} and returns a one-line summary of samples per comm. */
        String finish() throws Exception {
            var rec = future.get(SECONDS + 60L, TimeUnit.SECONDS);
            if (rec.getExitCode() != 0) return "perf record failed: " + rec.getStderr().replace('\n', ' ');
            var c = perf.container;
            var file = "/tmp/" + name + ".data";
            var byComm = c.execInContainer("perf", "report", "-i", file, "--sort", "comm", "--stdio", "-q", "--percent-limit", "0.5").getStdout();
            var pgDso = c.execInContainer("perf", "report", "-i", file, "--comms", "postgres", "--sort", "dso", "--stdio", "-q", "--percent-limit", "0.2").getStdout();
            var pgSym = c.execInContainer("perf", "report", "-i", file, "--comms", "postgres", "--sort", "dso,sym", "--stdio", "-q", "--percent-limit", "0.15").getStdout();
            var allSym = c.execInContainer("perf", "report", "-i", file, "--sort", "comm,dso,sym", "--stdio", "-q", "--percent-limit", "0.3").getStdout();
            var samples = c.execInContainer("sh", "-c", "perf report -i " + file + " --stdio --sort comm 2>/dev/null | grep -m1 'Samples'").getStdout().trim();
            c.execInContainer("rm", "-f", file);
            var dir = Path.of(System.getProperty("user.dir"), "target", "spike-results", "p0-9-perf");
            Files.createDirectories(dir);
            Files.writeString(dir.resolve(name + ".txt"), "== " + samples + "\n== by comm\n" + byComm + "\n== postgres by dso\n" + pgDso + "\n== postgres by dso,sym\n" + pgSym
                    + "\n== all by comm,dso,sym\n" + allSym, StandardCharsets.UTF_8);
            return samples + " | " + byComm.lines().limit(6).map(String::trim).reduce((x, y) -> x + "; " + y).orElse("");
        }
    }
}
