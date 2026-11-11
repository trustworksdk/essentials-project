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

import dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.eventstream.*;
import dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.persistence.*;
import dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.persistence.table_per_aggregate_type.*;
import dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.spike.CurrentEngineBaselineSpikeIT.*;
import dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.spike.CurrentEngineBaselineSpikeIT.Stack;
import dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.spike.SingleStatementAppendSpikeIT.*;
import dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.test_data.*;
import dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.types.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

import java.lang.management.ManagementFactory;
import java.nio.file.*;
import java.sql.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;

/**
 * Follow-up to spike P0.4 ({@code docs/plans/event-store-phase0-append.md}, "Contention throughput"): why does the
 * single-statement append (B) reach fewer appends per second than today's engine (A) when 8 writers contend for 10
 * aggregates?
 * <p>
 * Same shape as {@link SingleStatementAppendSpikeIT#contended()} (8 writers, 10 pre-created aggregates, each unit of
 * work reads the last event order and appends one event after it, fresh table per arm, 2 s warm-up, arm order rotated
 * per run), plus what that test did not measure:
 * <ul>
 *     <li>client wall time per attempt split into begin, last-order lookup, append and commit/rollback, per outcome
 *     (success, zero-row conflict, unique-violation conflict);</li>
 *     <li>client CPU (the writer threads' own CPU time and the JVM's), the PostgreSQL container's CPU (cgroup
 *     {@code cpu.stat}) and the whole machine's busy fraction ({@code /proc/stat});</li>
 *     <li>where the writer backends are, sampled every ~2 ms from {@code pg_stat_activity} (state, wait event);</li>
 *     <li>{@code pg_stat_statements} with {@code track_planning} on: plans, plan and execution time, buffers per call
 *     (only for statements that completed - a statement failing with a unique violation is not recorded).</li>
 * </ul>
 * Arms: A (engine), C (today's statements through raw pgjdbc), B (§5.1 statement) and B with
 * {@code ON CONFLICT DO NOTHING}. Select arms with {@code -Dspike.contention.arms=A_ENGINE,B_SINGLE}; window length
 * with {@code -Dspike.contention.seconds}; {@code -Dspike.contention.jfr=true} records a JFR file per measured window.
 * Results go to {@code target/spike-results/p0-4-contention.txt}.
 * <p>
 * Throwaway: runs only with {@code -Dspike.run=true}.
 */
@Tag("spike")
@EnabledIfSystemProperty(named = "spike.run", matches = "true")
class ContendedAppendThroughputSpikeIT {
    private static final String  RESULTS    = "p0-4-contention.txt";
    private static final int     RUNS       = Integer.getInteger("spike.contention.runs", 2);
    private static final int     SECONDS    = Integer.getInteger("spike.contention.seconds", 20);
    private static final int     WRITERS    = Integer.getInteger("spike.contention.writers", 8);
    private static final int     AGGREGATES = Integer.getInteger("spike.contention.aggregates", 10);
    private static final boolean JFR        = Boolean.getBoolean("spike.contention.jfr");

    enum Arm {
        A_ENGINE("A engine"),
        C_RAW("C raw pgjdbc, today's statements"),
        B_SINGLE("B single statement"),
        B_ON_CONFLICT("B + ON CONFLICT DO NOTHING");

        final String label;

        Arm(String label) {
            this.label = label;
        }
    }

    private static final int OK = 0, ZERO = 1, UNIQUE = 2, OTHER = 3;
    private static final String[] OUTCOME = {"success", "zero-row", "unique-violation", "other"};

    /** Per-thread accumulators: count and summed phase times (ns) per outcome. */
    static final class Acc {
        final long[] count  = new long[4];
        final long[] begin  = new long[4];
        final long[] lookup = new long[4];
        final long[] append = new long[4];
        final long[] end    = new long[4];
        long cpuNanos;

        void add(Acc o) {
            for (int i = 0; i < 4; i++) {
                count[i] += o.count[i];
                begin[i] += o.begin[i];
                lookup[i] += o.lookup[i];
                append[i] += o.append[i];
                end[i] += o.end[i];
            }
            cpuNanos += o.cpuNanos;
        }

        long attempts() {
            return count[OK] + count[ZERO] + count[UNIQUE] + count[OTHER];
        }
    }

    @Test
    void contention() throws Exception {
        var arms = Arrays.stream(System.getProperty("spike.contention.arms", "A_ENGINE,C_RAW,B_SINGLE,B_ON_CONFLICT").split(",")).map(String::trim).map(Arm::valueOf).toList();
        record("");
        record("=== CONTENTION | " + Instant.now() + " | runs " + RUNS + " | CPUs " + Runtime.getRuntime().availableProcessors() + " | " + WRITERS + " writers, " + AGGREGATES
                       + " aggregates, " + SECONDS + " s per arm after a 2 s warm-up on a throwaway table, fresh table per arm, arm order rotates per run, arms " + arms
                       + ", pg_stat_statements.track_planning=on, pg_stat_activity sampled every ~2 ms" + (JFR ? ", JFR on" : ""));
        for (int run = 1; run <= RUNS; run++) {
            try (var pg = Pg.start()) {
                try (var s = pg.observer.createStatement()) {
                    s.execute("ALTER SYSTEM SET pg_stat_statements.track_planning = on");
                    s.execute("SELECT pg_reload_conf()");
                }
                var w = new Writer(Stack.writer(pg, "writer"));
                var order = new ArrayList<>(arms);
                Collections.rotate(order, run - 1);
                for (var arm : order) {
                    window(pg, w, arm, Duration.ofSeconds(2), run, true);
                    window(pg, w, arm, Duration.ofSeconds(SECONDS), run, false);
                }
                w.stack.close();
            }
        }
    }

    private void window(Pg pg, Writer w, Arm arm, Duration duration, int run, boolean discard) throws Exception {
        var type = w.freshType("ct" + run);
        var cfg  = w.config(type);
        var aggregates = new ArrayList<OrderId>();
        for (int i = 0; i < AGGREGATES; i++) {
            var id = OrderId.random();
            w.stack.unitOfWorkFactory.usingUnitOfWork(uow -> w.stack.eventStore.appendToStream(type, id, Optional.empty(), List.of(new OrderEvent.OrderAdded(id, CustomerId.random(), 1))));
            aggregates.add(id);
        }
        long seqBefore  = sequenceLastValue(pg, cfg);
        long rowsBefore = rowCount(pg, cfg);
        pg.resetStatistics();
        var osBean       = (com.sun.management.OperatingSystemMXBean) ManagementFactory.getOperatingSystemMXBean();
        long jvmCpu0     = osBean.getProcessCpuTime();
        long[] machine0  = machineCpu();
        long server0     = containerCpuMicros(pg);
        jdk.jfr.Recording jfr = null;
        if (JFR && !discard) {
            jfr = new jdk.jfr.Recording(jdk.jfr.Configuration.getConfiguration("profile"));
            jfr.start();
        }
        var stop    = new AtomicBoolean();
        var sampler = new Sampler(pg, stop);
        var samplerThread = Thread.ofPlatform().name("sampler").start(sampler);
        var total   = new Acc();
        var firstOther = new AtomicReference<Throwable>();
        long stopAt = System.nanoTime() + duration.toNanos();
        long wall0  = System.nanoTime();
        var pool    = Executors.newFixedThreadPool(WRITERS);
        var futures = new ArrayList<Future<Acc>>();
        for (int t = 0; t < WRITERS; t++) {
            futures.add(pool.submit(() -> {
                var threads = ManagementFactory.getThreadMXBean();
                long cpu0   = threads.getCurrentThreadCpuTime();
                var acc     = new Acc();
                var random  = ThreadLocalRandom.current();
                var stamps  = new long[3]; // after begin (lambda entered), after lookup, when the append returned or threw
                while (System.nanoTime() < stopAt) {
                    var id = aggregates.get(random.nextInt(aggregates.size()));
                    int outcome;
                    long t0 = System.nanoTime();
                    stamps[0] = stamps[1] = stamps[2] = 0;
                    try {
                        w.stack.unitOfWorkFactory.usingUnitOfWork(uow -> {
                            stamps[0] = System.nanoTime();
                            long last = w.stack.eventStore.loadLastPersistedEventRelatedTo(type, id).orElseThrow().eventOrder().longValue();
                            stamps[1] = System.nanoTime();
                            var events = List.<Object>of(new OrderEvent.ProductAddedToOrder(id, ProductId.random(), 1));
                            try {
                                switch (arm) {
                                    case A_ENGINE -> w.stack.eventStore.appendToStream(type, id, Optional.of(last), events);
                                    case C_RAW -> w.rawBatch(uow, cfg, new Append(id, Optional.of(last), events));
                                    case B_SINGLE, B_ON_CONFLICT -> {
                                        var result = w.single(uow, cfg, List.of(new Append(id, Optional.of(last), events)),
                                                              arm == Arm.B_SINGLE ? Lock.NONE : Lock.ON_CONFLICT_DO_NOTHING);
                                        if (result.counts()[0] == 0) throw new ZeroRows();
                                    }
                                }
                            } finally {
                                stamps[2] = System.nanoTime();
                            }
                        });
                        outcome = OK;
                    } catch (Throwable e) {
                        if (hasCause(e, ZeroRows.class)) outcome = ZERO;
                        else if (isUniqueViolation(e)) outcome = UNIQUE;
                        else {
                            outcome = OTHER;
                            firstOther.compareAndSet(null, e);
                        }
                    }
                    long t4 = System.nanoTime();
                    acc.count[outcome]++;
                    if (stamps[2] != 0) {
                        acc.begin[outcome] += stamps[0] - t0;
                        acc.lookup[outcome] += stamps[1] - stamps[0];
                        acc.append[outcome] += stamps[2] - stamps[1];
                        acc.end[outcome] += t4 - stamps[2];
                    }
                }
                acc.cpuNanos = threads.getCurrentThreadCpuTime() - cpu0;
                return acc;
            }));
        }
        pool.shutdown();
        for (var f : futures) total.add(f.get(duration.toSeconds() + 60, TimeUnit.SECONDS));
        long wallNanos = System.nanoTime() - wall0;
        stop.set(true);
        samplerThread.join();
        if (discard) return;
        long jvmCpu   = osBean.getProcessCpuTime() - jvmCpu0;
        long[] machine1 = machineCpu();
        long serverCpu = containerCpuMicros(pg) - server0;
        if (jfr != null) {
            jfr.stop();
            var file = Path.of("target", "spike-results", "p0-4-contention-run" + run + "-" + arm.name() + ".jfr");
            jfr.dump(file);
            jfr.close();
            record("  JFR: " + file.toAbsolutePath());
        }
        long successes = rowCount(pg, cfg) - rowsBefore;
        long burned    = sequenceLastValue(pg, cfg) - seqBefore - successes;
        double seconds = duration.toMillis() / 1000.0;
        long attempts  = total.attempts();
        double machineBusy = (double) (machine1[0] - machine0[0]) / Math.max(1, machine1[1] - machine0[1]);
        record(String.format("CONTENTION run %d | %-34s | attempts %d (%.0f/s), appended %d (%.0f/s), zero-row %d, unique-violation %d, other %d, conflict rate %.1f%% | "
                                     + "burned %d (%.1f per 1 000 appended)",
                             run, arm.label, attempts, attempts / seconds, successes, successes / seconds, total.count[ZERO], total.count[UNIQUE], total.count[OTHER],
                             100.0 * (attempts - total.count[OK]) / attempts, burned, burned * 1000.0 / Math.max(1, successes)));
        record(String.format("  wall per attempt %.1f µs (= %d writers x %.1f s / attempts) | client CPU: writer threads %.1f µs/attempt, whole JVM %.1f µs/attempt | "
                                     + "PostgreSQL container CPU %.1f µs/attempt (%.2f cores) | machine busy %.0f%% of %d CPUs",
                             WRITERS * wallNanos / 1000.0 / attempts, WRITERS, wallNanos / 1e9, total.cpuNanos / 1000.0 / attempts, jvmCpu / 1000.0 / attempts,
                             serverCpu / (double) attempts, serverCpu / 1e6 / (wallNanos / 1e9), 100 * machineBusy, Runtime.getRuntime().availableProcessors()));
        for (int o = 0; o < 4; o++) {
            if (total.count[o] == 0) continue;
            long n = total.count[o];
            record(String.format("  %-16s %8d | µs per attempt: begin %6.1f, lookup %6.1f, append %6.1f, commit/rollback+exception %6.1f, total %6.1f",
                                 OUTCOME[o], n, total.begin[o] / 1000.0 / n, total.lookup[o] / 1000.0 / n, total.append[o] / 1000.0 / n, total.end[o] / 1000.0 / n,
                                 (total.begin[o] + total.lookup[o] + total.append[o] + total.end[o]) / 1000.0 / n));
        }
        record("  " + sampler.summary());
        statements(pg);
        if (firstOther.get() != null) record("  first other error: " + firstOther.get());
    }

    /** Samples where the writer backends are, from pg_stat_activity. */
    static final class Sampler implements Runnable {
        private final Pg                 pg;
        private final AtomicBoolean      stop;
        private final Map<String, Long>  buckets = new TreeMap<>();
        private long                     rows;
        private long                     samples;

        Sampler(Pg pg, AtomicBoolean stop) {
            this.pg = pg;
            this.stop = stop;
        }

        @Override
        public void run() {
            try (var c = DriverManager.getConnection(pg.container.getJdbcUrl(), "observer", "observer");
                 var s = c.prepareStatement("SELECT state, coalesce(wait_event_type, 'CPU'), coalesce(wait_event, '-') FROM pg_stat_activity WHERE usename = 'writer'")) {
                while (!stop.get()) {
                    try (var rs = s.executeQuery()) {
                        while (rs.next()) {
                            var state = rs.getString(1);
                            var key   = ("active".equals(state) ? "active " + rs.getString(2) + ":" + rs.getString(3) : state + " (client)");
                            buckets.merge(key, 1L, Long::sum);
                            rows++;
                        }
                    }
                    samples++;
                    java.util.concurrent.locks.LockSupport.parkNanos(2_000_000);
                }
            } catch (SQLException e) {
                buckets.merge("sampler failed: " + e.getMessage(), 1L, Long::sum);
            }
        }

        String summary() {
            var sb = new StringJoiner(", ", "backend states over " + samples + " samples (" + String.format("%.1f", rows / (double) Math.max(1, samples)) + " writer backends per sample): ", "");
            buckets.entrySet().stream().sorted(Map.Entry.<String, Long>comparingByValue().reversed())
                   .forEach(e -> sb.add(String.format("%s %.1f%%", e.getKey(), 100.0 * e.getValue() / Math.max(1, rows))));
            return sb.toString();
        }
    }

    private static void statements(Pg pg) throws SQLException {
        try (var s = pg.observer.createStatement();
             var rs = s.executeQuery("SELECT s.query, s.calls, s.rows, s.plans, s.total_plan_time, s.total_exec_time, s.shared_blks_hit + s.shared_blks_read, s.shared_blks_dirtied "
                                             + "FROM pg_stat_statements s JOIN pg_roles r ON r.oid = s.userid WHERE r.rolname = 'writer' ORDER BY s.calls DESC LIMIT 6")) {
            while (rs.next()) {
                long calls = rs.getLong(2);
                var oneLine = rs.getString(1).replaceAll("\\s+", " ");
                record(String.format("     calls %8d rows %8d plans %8d | plan %6.1f µs/plan, exec %6.1f µs/call, total exec %7.0f ms | buffers %.1f/call, dirtied %.2f/call | %s",
                                     calls, rs.getLong(3), rs.getLong(4), rs.getLong(4) == 0 ? 0 : rs.getDouble(5) * 1000 / rs.getLong(4), rs.getDouble(6) * 1000 / Math.max(1, calls),
                                     rs.getDouble(6), rs.getLong(7) / (double) Math.max(1, calls), rs.getLong(8) / (double) Math.max(1, calls),
                                     oneLine.substring(0, Math.min(110, oneLine.length()))));
            }
        }
    }

    /** {busy jiffies, total jiffies} of the whole machine (the Docker VM, shared with the container). */
    private static long[] machineCpu() {
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

    /** CPU the PostgreSQL container has used, µs, from its cgroup (v2 {@code cpu.stat}, else v1 {@code cpuacct.usage}). */
    private static long containerCpuMicros(Pg pg) {
        try {
            var r = pg.container.execInContainer("sh", "-c", "cat /sys/fs/cgroup/cpu.stat 2>/dev/null || cat /sys/fs/cgroup/cpuacct/cpuacct.usage");
            for (var line : r.getStdout().split("\n")) {
                if (line.startsWith("usage_usec")) return Long.parseLong(line.split("\\s+")[1]);
                if (line.matches("\\d+")) return Long.parseLong(line) / 1000;
            }
        } catch (Exception e) {
            // fall through
        }
        return 0;
    }

    private static final class ZeroRows extends RuntimeException {
        ZeroRows() {
            super("zero rows: optimistic-concurrency conflict", null, false, false);
        }
    }

    private static boolean isUniqueViolation(Throwable t) {
        for (var c = t; c != null; c = c.getCause()) {
            if (c instanceof OptimisticAppendToStreamException) return true;
            if (c instanceof SQLException s && "23505".equals(s.getSQLState())) return true;
        }
        return false;
    }

    private static boolean hasCause(Throwable t, Class<? extends Throwable> type) {
        for (var c = t; c != null; c = c.getCause()) if (type.isInstance(c)) return true;
        return false;
    }

    private static long sequenceLastValue(Pg pg, SeparateTablePerAggregateEventStreamConfiguration cfg) throws SQLException {
        return SpikePostgres.queryLong(pg.observer, "SELECT last_value FROM " + SpikePostgres.queryString(pg.observer, "SELECT pg_get_serial_sequence('" + cfg.eventStreamTableName + "', '"
                + cfg.eventStreamTableColumnNames.globalOrderColumn + "')"));
    }

    private static long rowCount(Pg pg, SeparateTablePerAggregateEventStreamConfiguration cfg) throws SQLException {
        return SpikePostgres.queryLong(pg.observer, "SELECT count(*) FROM " + cfg.eventStreamTableName);
    }

    private static void record(String line) {
        SpikePostgres.record(RESULTS, line);
    }
}
