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

import dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.spike.ProjectionProgressSpikeIT.Samples;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.postgresql.core.*;
import org.testcontainers.postgresql.PostgreSQLContainer;

import java.sql.*;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;

import static dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.spike.SpikePostgres.*;
import static dk.trustworks.essentials.shared.FailFast.requireTrue;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * Spike P0.14 of {@code docs/archive/event-store-subscription-redesign.md} §18 (background:
 * {@code docs/archive/event-store-redesign-phase0.md}, "P0.14 One flush for engine-owned transactions"): when the engine owns
 * the transaction, how many round trips and how much server CPU does it save to send the last statements and the
 * commit in one flush, and can pgjdbc do it?
 * <p>
 * Units of work ("shapes", {@code -Dspike.pipeline.shapes}):
 * <ul>
 *     <li>{@code APPEND_1} / {@code APPEND_10}: §5.1's single-statement append of 1 or 10 events to one aggregate,
 *     committed by the engine;</li>
 *     <li>{@code APPEND_5AGG}: §5.2, five aggregates of one event each, the five §5.1 statements together;</li>
 *     <li>{@code PAGE_100}: a subscription page of 100 events as P0.13's set-based view upsert
 *     ({@code INSERT ... SELECT DISTINCT ON ... FROM unnest(...) ON CONFLICT DO UPDATE}), then §8.2's resume-point
 *     update with §9's fence assertion, then the commit.</li>
 * </ul>
 * Arms ({@code -Dspike.pipeline.arms}), pgjdbc only:
 * <ul>
 *     <li>{@code A_TX}: today. Autocommit off, the statements one by one (§5.2's five appends as one pgjdbc batch),
 *     a zero-row fenced update rolls back, then {@code commit()}.</li>
 *     <li>{@code B_BATCH}: autocommit on, the statements as one {@code executeBatch}: the appends as a
 *     {@code PreparedStatement} batch with {@code RETURN_GENERATED_KEYS}. One Sync, so one implicit transaction. Not
 *     for the page: pgjdbc cannot batch a multi-statement {@code PreparedStatement} (see {@link Arm#supports(Shape)}).</li>
 *     <li>{@code B_MULTI}: autocommit on, the statements as one multi-statement {@code PreparedStatement}
 *     ({@code execute()}, then {@code getMoreResults()}): one Sync.</li>
 *     <li>{@code D_EXPLICIT}: the row's arm D on pgjdbc: {@code B_MULTI} wrapped in {@code BEGIN; ...; COMMIT} in the same
 *     flush. After an error the session is left in a failed transaction block and needs a {@code ROLLBACK} round trip.</li>
 * </ul>
 * In the one-flush arms the fenced update is the erroring form ({@code WITH u AS (UPDATE ... RETURNING 1)
 * SELECT 1 / (SELECT count(*) FROM u)}), since a zero-row result does not stop a pipeline: a stale epoch fails with
 * {@code 22012} and the server skips everything up to the Sync, the commit included.
 * <p>
 * Before the timed windows, per pass:
 * <ul>
 *     <li><b>Stale epoch</b>, per arm: a {@code PAGE_100} unit whose subscription row has moved to another epoch must fail,
 *     leave the view and the resume point untouched, and leave the connection usable. This is the "last statement
 *     fails, nothing committed" proof the row asks for, and the gate's "a stale epoch commits nothing in every arm".</li>
 *     <li><b>Implicit-transaction size</b>: {@code B_BATCH} and {@code B_MULTI} with 5 and
 *     {@code -Dspike.pipeline.largeBatch} (default 400) single-row inserts whose last one violates the primary key, and
 *     the rows left committed. pgjdbc forces an intermediate Sync once it estimates 64 000 bytes of pending replies
 *     (about 250 statements without results), which in autocommit commits what came before.</li>
 *     <li><b>Round trips</b>, per shape and arm: one writer through {@link RoundTripCountingProxy} for
 *     {@code -Dspike.pipeline.roundTripUnits} units after a 20-unit warm-up (beyond pgjdbc's prepareThreshold).</li>
 * </ul>
 * Timed windows: per shape and writer count ({@code -Dspike.pipeline.writers}, default {@code 1,8,20}) the arms in a
 * palindrome, each window on fresh tables after a {@code CHECKPOINT}, {@code -Dspike.pipeline.warmupMillis} (2 000)
 * of warm-up and {@code -Dspike.pipeline.seconds} (20) measured. Each writer has its own connection, aggregates, view
 * table and subscription row, so nothing contends but the WAL. Per window: units/s, events/s, server CPU per unit
 * (container cgroup, or {@code -Dspike.serverCpuCommand}) and its system part, recorded execution and planning per
 * unit ({@code pg_stat_statements} of the writer role, {@code track_planning} on, utility statements included where the
 * server tracks them), statements per unit, unit latency p50/p99, and a check that the committed rows match what the
 * writers saw committed. A container pass runs {@code fsync=on} ({@code -Dspike.pipeline.fsync}, a list); an external
 * server one pass with its own settings. Results: {@code target/spike-results/p0-14-oneflush.txt}, with a GATE line
 * per shape at 8 writers.
 * <p>
 * Not built: the row's arms C (a minimal wire client: statements parsed at connect, Bind/Execute and one Sync) and D on
 * that client; this spike measures pgjdbc only, so the gate's "B within 10 % of C" clause is not evaluated. §5.2 in the
 * one-flush arms keeps §5.1's zero-row conflict, which in autocommit no longer rolls the other aggregates back; no
 * conflicts occur here (each writer owns its aggregates), and an engine that adopts B for §5.2 needs an erroring
 * append as well.
 * <p>
 * Throwaway: runs only with {@code -Dspike.run=true}.
 */
@Tag("spike")
@EnabledIfSystemProperty(named = "spike.run", matches = "true")
class OneFlushTransactionSpikeIT {
    private static final String        RESULTS        = "p0-14-oneflush.txt";
    private static final String        IMAGE          = System.getProperty("spike.pipeline.image", "postgres:17.5");
    private static final int           SECONDS        = Integer.getInteger("spike.pipeline.seconds", 20);
    private static final int           WARMUP_MILLIS  = Integer.getInteger("spike.pipeline.warmupMillis", 2000);
    private static final int           RT_UNITS       = Integer.getInteger("spike.pipeline.roundTripUnits", 200);
    private static final int           LARGE_BATCH    = Integer.getInteger("spike.pipeline.largeBatch", 400);
    private static final List<Integer> WRITERS        = ints("spike.pipeline.writers", "1,8,20");
    private static final List<String>  FSYNC          = strings("spike.pipeline.fsync", "on");
    private static final List<Shape>   SHAPES         = strings("spike.pipeline.shapes", "APPEND_1,APPEND_10,APPEND_5AGG,PAGE_100").stream().map(Shape::valueOf).toList();
    private static final List<Arm>     ARMS           = strings("spike.pipeline.arms", "A_TX,B_BATCH,B_MULTI,D_EXPLICIT").stream().map(Arm::valueOf).toList();
    private static final int           GATE_WRITERS   = 8;
    private static final double        GATE_CPU_US    = 35;
    private static final double        GATE_GAIN      = 0.10;

    private static final String WRITER         = "writer";
    private static final String SUBS           = "p014_subscriptions";
    private static final String LEASES         = "p014_leases";
    private static final String PROBE          = "p014_probe";
    private static final int    PAGE_SIZE      = 100;
    private static final int    VIEW_ROWS      = 1000;
    private static final int    AGGS_PER_WRITER = 10;
    private static final String AGG_TYPE       = "Orders";
    private static final String OWNER          = "node-1";
    private static final long   FENCE          = 7;
    private static final long   EPOCH          = 3;

    enum Shape {
        APPEND_1(1, 1), APPEND_10(1, 10), APPEND_5AGG(5, 1), PAGE_100(0, PAGE_SIZE);

        final int aggregates;
        final int events;

        Shape(int aggregates, int events) {
            this.aggregates = aggregates;
            this.events = events;
        }

        boolean page() {
            return this == PAGE_100;
        }

        int eventsPerUnit() {
            return page() ? PAGE_SIZE : aggregates * events;
        }
    }

    enum Arm {
        A_TX, B_BATCH, B_MULTI, D_EXPLICIT;

        boolean oneFlush() {
            return this != A_TX;
        }

        /**
         * pgjdbc's executeBatch cannot carry a multi-statement PreparedStatement (it casts every batch entry to a
         * SimpleQuery: ClassCastException on CompositeQuery, 42.7.14), and a batch of one PreparedStatement repeats one
         * statement, so the page's upsert-then-fenced-update has no executeBatch form. B_MULTI is pgjdbc's one-flush
         * page.
         */
        boolean supports(Shape shape) {
            return !(this == B_BATCH && shape.page());
        }
    }

    private static List<Integer> ints(String property, String defaults) {
        return strings(property, defaults).stream().map(Integer::valueOf).toList();
    }

    private static List<String> strings(String property, String defaults) {
        return Arrays.stream(System.getProperty(property, defaults).split(",")).map(String::trim).filter(s -> !s.isEmpty()).toList();
    }

    private static String view(int writer) {
        return "p014_view_" + writer;
    }

    private static String subscriber(int writer) {
        return "projection-" + writer;
    }

    // ------------------------------------------------------------------------------------------------------------------
    // SQL
    // ------------------------------------------------------------------------------------------------------------------

    /** §5.1, with text arrays as P0.4 measured it. Parameters: agg, expected, expected, agg, tenant, then 8 arrays (13). */
    static final String APPEND = "WITH xid  AS MATERIALIZED (SELECT pg_current_xact_id() AS x),\n" +
            "     head AS MATERIALIZED (SELECT coalesce(max(event_order), -1) AS v FROM " + TABLE + " WHERE aggregate_id = ?::text),\n" +
            "     ok   AS (SELECT head.v FROM head, xid WHERE ?::bigint IS NULL OR head.v = ?::bigint)\n" +
            "INSERT INTO " + TABLE + " (aggregate_id, event_order, event_id, caused_by_event_id, correlation_id, event_type, event_revision, timestamp, event_payload, " +
            "event_metadata, tenant)\n" +
            "SELECT ?::text, ok.v + e.n, e.id, e.caused_by, e.correlation, e.type, e.revision, e.ts, e.payload::jsonb, e.metadata::jsonb, ?::text\n" +
            "FROM ok\n" +
            "CROSS JOIN unnest(?::text[], ?::text[], ?::text[], ?::text[], ?::text[], ?::timestamptz[], ?::text[], ?::text[])\n" +
            "           WITH ORDINALITY AS e(id, caused_by, correlation, type, revision, ts, payload, metadata, n)\n" +
            "RETURNING global_order, event_order";
    static final int    APPEND_PARAMS = 13;

    /** P0.13's set-based page upsert. Parameters: 4 arrays. */
    static String upsert(int writer) {
        return "INSERT INTO " + view(writer) + " AS v (aggregate_id, event_count, last_global_order, last_event_type, payload, updated_at)\n" +
                "SELECT DISTINCT ON (a) a, count(*) OVER (PARTITION BY a), g, t, p::jsonb, now()\n" +
                "FROM unnest(?::text[], ?::bigint[], ?::text[], ?::text[]) AS u(a, g, t, p) ORDER BY a, g DESC\n" +
                "ON CONFLICT (aggregate_id) DO UPDATE SET event_count = v.event_count + excluded.event_count, last_global_order = excluded.last_global_order, " +
                "last_event_type = excluded.last_event_type, payload = excluded.payload, updated_at = excluded.updated_at";
    }

    static final int UPSERT_PARAMS = 4;

    /** §8.2 with §9's fence assertion (P0.13). Parameters: resume, type, subscriber, epoch, lease, owner, fence (7). */
    static final String RESUME = "UPDATE " + SUBS + " SET resume_from_and_including_global_eventorder = ?, last_updated = now()\n" +
            "WHERE aggregate_type = ? AND subscriber_id = ? AND reposition_epoch = ?\n" +
            "  AND EXISTS (SELECT 1 FROM " + LEASES + " l WHERE l.lease_name = ? AND l.owner = ? AND l.fence = ?)";

    /** The same, erroring with 22012 on zero rows, so a stale fence or epoch stops the pipeline and its commit. */
    static final String RESUME_ERRORING = "WITH u AS (" + RESUME.replace("\n", " ") + " RETURNING 1)\nSELECT 1 / (SELECT count(*) FROM u) AS fenced";

    static String multi(String... statements) {
        return String.join(";\n", statements);
    }

    static String explicit(String body) {
        return "BEGIN;\n" + body + ";\nCOMMIT";
    }

    // ------------------------------------------------------------------------------------------------------------------
    // The test
    // ------------------------------------------------------------------------------------------------------------------

    /** Per (shape, arm): round trips per unit, from the counting pass. */
    private final Map<String, Double>       roundTrips = new HashMap<>();
    /** Per (shape, writers, arm): every window's {units/s, cpu µs/unit}. */
    private final Map<String, List<double[]>> windows  = new HashMap<>();
    private final Map<Arm, Boolean>         staleOk    = new EnumMap<>(Arm.class);
    private final List<String>              failures   = new ArrayList<>();

    @Test
    void one_flush_transactions() throws Exception {
        requireTrue(SECONDS > 0 && RT_UNITS > 0 && LARGE_BATCH > 5, "spike.pipeline.seconds and roundTripUnits must be > 0, largeBatch > 5");
        requireTrue(WRITERS.stream().allMatch(w -> w > 0), "spike.pipeline.writers must all be > 0");
        var started = System.nanoTime();
        if (SpikeServer.external()) {
            try (var server = SpikeServer.external("pipeline", "spike", true)) {
                runPass(server, "server");
            }
        } else {
            for (var pass : FSYNC) {
                try (var server = container("on".equals(pass))) {
                    runPass(server, pass);
                }
            }
        }
        record("=== done in %.1f s".formatted((System.nanoTime() - started) / 1e9));
        assertThat(failures).as("stale-epoch proofs, window failures and committed-row checks").isEmpty();
    }

    private static SpikeServer container(boolean fsync) {
        var container = new PostgreSQLContainer(IMAGE).withDatabaseName("spike").withUsername("spike").withPassword("spike")
                                                      .withSharedMemorySize(1024L * 1024 * 1024)
                                                      .withCommand("postgres", "-c", fsync ? "fsync=on" : "fsync=off", "-c", "shared_buffers=256MB", "-c", "max_wal_size=4GB",
                                                                   "-c", "max_connections=100", "-c", "shared_preload_libraries=pg_stat_statements",
                                                                   "-c", "pg_stat_statements.max=10000", "-c", "pg_stat_statements.track_utility=on");
        container.start();
        return SpikeServer.of(container);
    }

    private void runPass(SpikeServer server, String pass) throws Exception {
        try (var observer = DriverManager.getConnection(server.getJdbcUrl(), server.getUsername(), server.getPassword())) {
            try (var s = observer.createStatement()) {
                s.execute("CREATE EXTENSION IF NOT EXISTS pg_stat_statements");
            }
            server.enableTrackPlanning(observer);
            server.createRole(observer, WRITER);
            var settings = new StringJoiner(", ");
            for (var name : List.of("server_version", "fsync", "synchronous_commit", "wal_sync_method", "shared_buffers", "max_connections", "pg_stat_statements.track_utility")) {
                settings.add(name + "=" + queryString(observer, "SELECT current_setting('" + name + "', true)"));
            }
            record("");
            record("=== P0.14 one-flush transactions | " + Instant.now() + " | " + SpikeServer.target(IMAGE + " container, fsync=" + pass + " shared_buffers=256MB")
                           + " | settings " + settings + " | server CPU " + server.cpuSource() + " | client CPUs " + Runtime.getRuntime().availableProcessors() + " | pgjdbc "
                           + org.postgresql.util.DriverInfo.DRIVER_VERSION + " (defaults, prepareThreshold=5) | shapes " + SHAPES + " | arms " + ARMS + " palindrome | writers "
                           + WRITERS + " | " + SECONDS + " s windows after " + WARMUP_MILLIS + " ms warm-up, fresh tables and a CHECKPOINT per window | round trips over "
                           + RT_UNITS + " units, 1 writer, through RoundTripCountingProxy");
            record("CSV,pass,shape,writers,arm,window,units,seconds,unitsPerSec,eventsPerSec,roundTripsPerUnit,cpuUsPerUnit,sysUsPerUnit,recordedUsPerUnit,"
                           + "stmtCallsPerUnit,p50us,p99us,failures,check");

            proveStaleEpoch(server, observer);
            proveImplicitTransactionSize(server, observer);
            for (var shape : SHAPES) {
                countRoundTrips(server, observer, shape);
                for (int writers : WRITERS) {
                    var order = new ArrayList<>(ARMS);
                    var back  = new ArrayList<>(ARMS);
                    Collections.reverse(back);
                    order.addAll(back);
                    int n = 0;
                    for (var arm : order) if (arm.supports(shape)) window(server, observer, pass, shape, writers, arm, ++n);
                }
                gate(shape);
            }
            gateSummary();
            try (var s = observer.createStatement()) {
                dropTables(s, WRITERS.stream().mapToInt(Integer::intValue).max().orElse(1));
                s.execute("DROP TABLE IF EXISTS " + PROBE);
            }
        }
    }

    // ------------------------------------------------------------------------------------------------------------------
    // Proofs
    // ------------------------------------------------------------------------------------------------------------------

    /**
     * Per arm: a page whose subscription row has moved to another epoch fails, commits neither the view upsert nor the
     * resume point, and leaves the connection usable for the next page.
     */
    private void proveStaleEpoch(SpikeServer server, Connection observer) throws Exception {
        for (var arm : ARMS) {
            if (!arm.supports(Shape.PAGE_100)) {
                staleOk.put(arm, true);
                record("STALE " + arm + " | not applicable: pgjdbc's executeBatch cannot carry the page's two different statements (a multi-statement PreparedStatement "
                               + "in a batch fails with ClassCastException CompositeQuery -> SimpleQuery in pgjdbc " + org.postgresql.util.DriverInfo.DRIVER_VERSION + "); B_MULTI covers it");
                continue;
            }
            createTables(observer, 1);
            try (var w = new Writer(server, server.getJdbcUrl(), 0, Shape.PAGE_100, arm)) {
                for (int i = 0; i < 3; i++) w.unit();
                long viewBefore   = queryLong(observer, "SELECT sum(event_count) FROM " + view(0));
                long resumeBefore = resumeOf(observer, 0);
                exec(observer, "UPDATE " + SUBS + " SET reposition_epoch = " + (EPOCH + 1) + " WHERE subscriber_id = '" + subscriber(0) + "'");
                String error = null;
                try {
                    w.unit();
                } catch (Exception e) {
                    error = e instanceof SQLException se ? se.getClass().getSimpleName() + " " + se.getSQLState() + " " + firstLine(se.getMessage())
                                                         : e.getClass().getSimpleName() + " " + firstLine(e.getMessage());
                }
                long viewAfter   = queryLong(observer, "SELECT sum(event_count) FROM " + view(0));
                long resumeAfter = resumeOf(observer, 0);
                exec(observer, "UPDATE " + SUBS + " SET reposition_epoch = " + EPOCH + " WHERE subscriber_id = '" + subscriber(0) + "'");
                String usable;
                try {
                    w.unit();
                    usable = resumeOf(observer, 0) == w.resumeFrom && queryLong(observer, "SELECT sum(event_count) FROM " + view(0)) == viewAfter + PAGE_SIZE
                             ? "yes" : "NO (next page did not commit as expected)";
                } catch (Exception e) {
                    usable = "NO: " + e.getClass().getSimpleName() + " " + firstLine(e.getMessage());
                }
                boolean ok = error != null && viewAfter == viewBefore && resumeAfter == resumeBefore && usable.equals("yes");
                staleOk.put(arm, ok);
                if (!ok) failures.add("stale epoch " + arm);
                record(String.format("STALE %-10s | error: %s | view %s (%d -> %d) | resume point %s (%d -> %d) | connection usable after: %s | extra round trips on the error path: %s | %s",
                                     arm, error == null ? "NONE (the stale page committed)" : error, viewAfter == viewBefore ? "unchanged" : "CHANGED", viewBefore, viewAfter,
                                     resumeAfter == resumeBefore ? "unchanged" : "MOVED", resumeBefore, resumeAfter, usable,
                                     arm == Arm.A_TX ? "rollback()" : arm == Arm.D_EXPLICIT ? "ROLLBACK (failed transaction block)" : "none",
                                     ok ? "OK: nothing committed" : "FAIL"));
            }
        }
    }

    /**
     * Autocommit, N single-row inserts in one executeBatch / one multi-statement PreparedStatement, the last violating the
     * primary key: how many rows stay committed. 0 means one implicit transaction.
     */
    private void proveImplicitTransactionSize(SpikeServer server, Connection observer) throws Exception {
        exec(observer, "DROP TABLE IF EXISTS " + PROBE);
        exec(observer, "CREATE TABLE " + PROBE + " (id int PRIMARY KEY)");
        try (var c = DriverManager.getConnection(server.getJdbcUrl(), server.role(WRITER), server.password(WRITER))) {
            c.setAutoCommit(true);
            for (int n : List.of(5, LARGE_BATCH)) {
                for (var arm : List.of(Arm.B_BATCH, Arm.B_MULTI)) {
                    exec(observer, "TRUNCATE " + PROBE);
                    String error = "none";
                    try {
                        if (arm == Arm.B_BATCH) {
                            try (var ps = c.prepareStatement("INSERT INTO " + PROBE + " VALUES (?)")) {
                                for (int i = 0; i < n; i++) {
                                    ps.setInt(1, i == n - 1 ? 0 : i);
                                    ps.addBatch();
                                }
                                ps.executeBatch();
                            }
                        } else {
                            var sql = new String[n];
                            Arrays.fill(sql, "INSERT INTO " + PROBE + " VALUES (?)");
                            try (var ps = c.prepareStatement(multi(sql))) {
                                for (int i = 0; i < n; i++) ps.setInt(i + 1, i == n - 1 ? 0 : i);
                                ps.execute();
                            }
                        }
                    } catch (SQLException e) {
                        error = e.getSQLState();
                    }
                    long left = queryLong(observer, "SELECT count(*) FROM " + PROBE);
                    record(String.format("IMPLICIT TX %-8s %4d statements, last one a duplicate key | error %s | rows left committed %d of %d | %s", arm, n, error, left, n - 1,
                                         left == 0 ? "one implicit transaction" : "NOT one transaction: pgjdbc synced mid-flush (deadlock guard) and autocommit committed the part before"));
                }
            }
        }
    }

    // ------------------------------------------------------------------------------------------------------------------
    // Round trips
    // ------------------------------------------------------------------------------------------------------------------

    private void countRoundTrips(SpikeServer server, Connection observer, Shape shape) throws Exception {
        try (var proxy = new RoundTripCountingProxy(server.host(), server.port())) {
            var j = new StringJoiner(" | ");
            for (var arm : ARMS) {
                if (!arm.supports(shape)) continue;
                createTables(observer, 1);
                try (var w = new Writer(server, server.viaProxy(proxy.port()), 0, shape, arm)) {
                    for (int i = 0; i < 20; i++) w.unit();
                    proxy.reset();
                    for (int i = 0; i < RT_UNITS; i++) w.unit();
                    double rt = proxy.flights() / (double) RT_UNITS;
                    roundTrips.put(shape + "/" + arm, rt);
                    j.add(String.format("%s %.2f", arm, rt));
                }
            }
            record("ROUND TRIPS per unit, " + shape + ": " + j);
        }
    }

    // ------------------------------------------------------------------------------------------------------------------
    // One timed window
    // ------------------------------------------------------------------------------------------------------------------

    private void window(SpikeServer server, Connection observer, String pass, Shape shape, int writers, Arm arm, int n) throws Exception {
        createTables(observer, writers);
        exec(observer, "CHECKPOINT");
        var list = new ArrayList<Writer>();
        try {
            for (int i = 0; i < writers; i++) list.add(new Writer(server, server.getJdbcUrl(), i, shape, arm));
            var pool    = Executors.newFixedThreadPool(writers, Thread.ofPlatform().name("p014-writer-", 0).factory());
            var futures = new ArrayList<Future<?>>();
            try {
                for (var w : list) futures.add(pool.submit(w::loop));
                Thread.sleep(WARMUP_MILLIS);
                exec(observer, "SELECT pg_stat_statements_reset()");
                var  cpu0  = server.cpuUserSystemMicros();
                long start = System.nanoTime();
                for (var w : list) w.measureFrom = start;
                Thread.sleep(SECONDS * 1000L);
                long end = System.nanoTime();
                for (var w : list) w.measureTo = end;
                var cpu1 = server.cpuUserSystemMicros();
                for (var w : list) w.stop = true;
                for (var f : futures) f.get(60, TimeUnit.SECONDS);

                long   units   = list.stream().mapToLong(w -> w.units).sum();
                long   failed  = list.stream().mapToLong(w -> w.failures).sum();
                double seconds = (end - start) / 1e9;
                double perUnit = Math.max(1, units);
                double cpu     = cpu0 == null || cpu1 == null ? Double.NaN : (cpu1[0] - cpu0[0]) / perUnit;
                double sys     = cpu0 == null || cpu1 == null ? Double.NaN : (cpu1[2] - cpu0[2]) / perUnit;
                double[] recorded = recorded(server, observer);
                var    lat     = Samples.summary(list.stream().map(w -> w.latency).toList());
                double rt      = roundTrips.getOrDefault(shape + "/" + arm, Double.NaN);
                String check   = check(observer, shape, list);
                if (failed > 0) {
                    failures.add(shape + " " + writers + "w " + arm + " window " + n + ": " + failed + " failed units, first " + list.stream().map(w -> w.firstFailure)
                                                                                                                                         .filter(Objects::nonNull).findFirst().orElse(null));
                }
                if (!check.equals("ok")) failures.add(shape + " " + writers + "w " + arm + " window " + n + ": " + check);
                windows.computeIfAbsent(shape + "/" + writers + "/" + arm, k -> new ArrayList<>()).add(new double[]{units / seconds, cpu});
                record(String.format("%-11s %2d writers | %-10s w%-2d | %7d units in %.1f s = %8.1f units/s (%9.0f events/s) | %.2f round trips/unit | server CPU %7.1f µs/unit (system %6.1f) "
                                             + "| recorded exec+plan %6.1f µs/unit, %.2f statements/unit | latency p50 %7.0f µs p99 %7.0f µs | failures %d | check %s",
                                     shape, writers, arm, n, units, seconds, units / seconds, units * shape.eventsPerUnit() / seconds, rt, cpu, sys,
                                     recorded[0] * 1000 / perUnit, recorded[1] / perUnit, lat[0], lat[1], failed, check));
                record(String.format(Locale.ROOT, "CSV,%s,%s,%d,%s,%d,%d,%.2f,%.1f,%.0f,%.2f,%.1f,%.1f,%.1f,%.2f,%.0f,%.0f,%d,%s",
                                     pass, shape, writers, arm, n, units, seconds, units / seconds, units * shape.eventsPerUnit() / seconds, rt, cpu, sys,
                                     recorded[0] * 1000 / perUnit, recorded[1] / perUnit, lat[0], lat[1], failed, check));
                for (var w : list) {
                    if (w.firstFailure != null) {
                        record("  first failure, writer " + w.index + ": " + w.firstFailure);
                        break;
                    }
                }
            } finally {
                pool.shutdownNow();
            }
        } finally {
            for (var w : list) w.close();
        }
    }

    /** {exec + plan ms, calls} of the writer role since the last reset. */
    private static double[] recorded(SpikeServer server, Connection observer) throws SQLException {
        var thisDatabase = server.container != null ? "" : " AND s.dbid = (SELECT oid FROM pg_database WHERE datname = current_database())";
        try (var s = observer.createStatement();
             var rs = s.executeQuery("SELECT coalesce(sum(s.total_exec_time + s.total_plan_time), 0), coalesce(sum(s.calls), 0) FROM pg_stat_statements s "
                                             + "JOIN pg_roles r ON r.oid = s.userid WHERE r.rolname = '" + server.role(WRITER) + "'" + thisDatabase)) {
            rs.next();
            return new double[]{rs.getDouble(1), rs.getDouble(2)};
        }
    }

    /** The committed rows match what the writers saw committed. */
    private static String check(Connection observer, Shape shape, List<Writer> writers) throws SQLException {
        var problems = new StringJoiner("; ");
        if (shape.page()) {
            for (var w : writers) {
                long view   = queryLong(observer, "SELECT sum(event_count) FROM " + view(w.index));
                long resume = resumeOf(observer, w.index);
                if (view != w.committedUnits * PAGE_SIZE) problems.add("writer " + w.index + " view " + view + " != " + w.committedUnits * PAGE_SIZE);
                if (resume != w.resumeFrom) problems.add("writer " + w.index + " resume " + resume + " != " + w.resumeFrom);
            }
        } else {
            long rows     = queryLong(observer, "SELECT count(*) FROM " + TABLE);
            long expected = writers.stream().mapToLong(w -> w.committedUnits).sum() * shape.eventsPerUnit();
            if (rows != expected) problems.add("events " + rows + " != " + expected);
        }
        return problems.length() == 0 ? "ok" : problems.toString();
    }

    // ------------------------------------------------------------------------------------------------------------------
    // Gate
    // ------------------------------------------------------------------------------------------------------------------

    private final List<String> gateVerdicts = new ArrayList<>();

    private double[] mean(Shape shape, int writers, Arm arm) {
        var w = windows.getOrDefault(shape + "/" + writers + "/" + arm, List.of());
        return new double[]{w.stream().mapToDouble(x -> x[0]).average().orElse(Double.NaN), w.stream().mapToDouble(x -> x[1]).average().orElse(Double.NaN)};
    }

    /**
     * The row's gate at 8 writers for one shape: the best one-flush arm (most units/s) against A_TX removes at least one
     * round trip per unit, saves at least 35 µs of server CPU per removed round trip, and gives at least 10 % more units/s.
     */
    private void gate(Shape shape) {
        if (!WRITERS.contains(GATE_WRITERS) || !ARMS.contains(Arm.A_TX)) {
            record("GATE " + shape + ": not evaluated (needs " + GATE_WRITERS + " writers and A_TX in the run)");
            return;
        }
        var    a    = mean(shape, GATE_WRITERS, Arm.A_TX);
        double aRt  = roundTrips.getOrDefault(shape + "/" + Arm.A_TX, Double.NaN);
        Arm    best = null;
        var    j    = new StringJoiner(", ");
        j.add(String.format("A_TX %.1f units/s, %.1f µs/unit, %.2f RT", a[0], a[1], aRt));
        for (var arm : ARMS) {
            if (!arm.oneFlush() || !arm.supports(shape)) continue;
            var m = mean(shape, GATE_WRITERS, arm);
            j.add(String.format("%s %.1f units/s, %.1f µs/unit, %.2f RT", arm, m[0], m[1], roundTrips.getOrDefault(shape + "/" + arm, Double.NaN)));
            if (best == null || m[0] > mean(shape, GATE_WRITERS, best)[0]) best = arm;
        }
        if (best == null) {
            record("GATE " + shape + ": no one-flush arm ran");
            return;
        }
        var    b        = mean(shape, GATE_WRITERS, best);
        double removed  = aRt - roundTrips.getOrDefault(shape + "/" + best, Double.NaN);
        double saved    = (a[1] - b[1]) / removed;
        double gain     = b[0] / a[0] - 1;
        boolean rtOk    = removed >= 0.95;
        boolean cpuOk   = !Double.isNaN(saved) && saved >= GATE_CPU_US;
        boolean gainOk  = gain >= GATE_GAIN;
        boolean pass    = rtOk && cpuOk && gainOk;
        var verdict = String.format("GATE %s at %d writers (means of the palindrome windows): %s | best one-flush arm %s: %.2f round trips removed per unit (%s), %.1f µs server CPU saved per "
                                            + "removed round trip (%s, needs >= %.0f), %+.1f %% units/s (%s, needs >= %+.0f %%) | %s",
                                    shape, GATE_WRITERS, j, best, removed, rtOk ? "ok" : "FAIL", saved, Double.isNaN(saved) ? "no server CPU" : cpuOk ? "ok" : "FAIL", GATE_CPU_US,
                                    gain * 100, gainOk ? "ok" : "FAIL", GATE_GAIN * 100, pass ? "PASSES" : "fails");
        gateVerdicts.add(shape + " " + (pass ? "passes (" + best + ")" : "fails"));
        record(verdict);
    }

    private void gateSummary() {
        var stale = ARMS.stream().map(a -> a + " " + (Boolean.TRUE.equals(staleOk.get(a)) ? "ok" : "FAIL")).toList();
        boolean allStale = ARMS.stream().allMatch(a -> Boolean.TRUE.equals(staleOk.get(a)));
        record("GATE summary: " + gateVerdicts + " | a stale epoch commits nothing in every arm: " + (allStale ? "yes" : "NO") + " " + stale
                       + " | arm C (minimal wire client) not built, pgjdbc only: the 'B within 10 % of C' clause is not evaluated | Phase 2 builds engine-owned transactions "
                       + "as one flush only for shapes that pass, and only if every stale-epoch proof holds");
    }

    // ------------------------------------------------------------------------------------------------------------------
    // Tables
    // ------------------------------------------------------------------------------------------------------------------

    private static void dropTables(Statement s, int writers) throws SQLException {
        for (int i = 0; i < Math.max(writers, 20); i++) s.execute("DROP TABLE IF EXISTS " + view(i));
        s.execute("DROP TABLE IF EXISTS " + SUBS + ", " + LEASES + ", " + TABLE);
    }

    /** Fresh event table, subscription and lease rows, and one view table per writer with every row present. */
    private static void createTables(Connection c, int writers) throws SQLException {
        try (var s = c.createStatement()) {
            dropTables(s, writers);
            // today's durable_subscriptions shape, fillfactor below 100 per §8.2 (as P0.13)
            s.execute("CREATE TABLE " + SUBS + " (subscriber_id text NOT NULL, aggregate_type text NOT NULL, resume_from_and_including_global_eventorder bigint, "
                              + "last_updated timestamptz, reposition_epoch bigint NOT NULL DEFAULT 0, PRIMARY KEY (subscriber_id, aggregate_type)) WITH (fillfactor = 50)");
            s.execute("CREATE TABLE " + LEASES + " (lease_name text PRIMARY KEY, owner text NOT NULL, fence bigint NOT NULL)");
            for (int i = 0; i < writers; i++) {
                s.execute("CREATE TABLE " + view(i) + " (aggregate_id text PRIMARY KEY, event_count bigint NOT NULL, last_global_order bigint NOT NULL, "
                                  + "last_event_type text, payload jsonb NOT NULL, updated_at timestamptz NOT NULL)");
                s.execute("INSERT INTO " + view(i) + " SELECT 'agg-' || k, 0, 0, null, '{}'::jsonb, now() FROM generate_series(0, " + (VIEW_ROWS - 1) + ") k");
                s.execute("INSERT INTO " + SUBS + " VALUES ('" + subscriber(i) + "', '" + AGG_TYPE + "', 1, now(), " + EPOCH + ")");
                s.execute("INSERT INTO " + LEASES + " VALUES ('" + subscriber(i) + "', '" + OWNER + "', " + FENCE + ")");
            }
            s.execute("VACUUM ANALYZE " + SUBS + ", " + LEASES);
        }
        recreateEventTable(c);
    }

    private static long resumeOf(Connection c, int writer) throws SQLException {
        return queryLong(c, "SELECT resume_from_and_including_global_eventorder FROM " + SUBS + " WHERE subscriber_id = '" + subscriber(writer) + "'");
    }

    private static void exec(Connection c, String sql) throws SQLException {
        try (var s = c.createStatement()) {
            s.execute(sql);
        }
    }

    private static String firstLine(String message) {
        if (message == null) return "";
        int nl = message.indexOf('\n');
        return nl < 0 ? message : message.substring(0, nl);
    }

    // ------------------------------------------------------------------------------------------------------------------
    // A writer: one connection, one unit of work after another
    // ------------------------------------------------------------------------------------------------------------------

    static final class Writer implements AutoCloseable {
        final int        index;
        final Shape      shape;
        final Arm        arm;
        final Connection c;
        final Samples    latency = new Samples();
        private final PreparedStatement statement;
        private final PreparedStatement resume;
        private final long[]            heads = new long[AGGS_PER_WRITER];
        private int                     nextAggregate;
        private long                    nextGlobalOrder = 1;
        /** The resume point of the last committed page. */
        long                            resumeFrom      = 1;
        long                            committedUnits;

        volatile long    measureFrom = Long.MAX_VALUE;
        volatile long    measureTo   = Long.MAX_VALUE;
        volatile boolean stop;
        long             units;
        long             failures;
        volatile String  firstFailure;

        Writer(SpikeServer server, String url, int index, Shape shape, Arm arm) throws SQLException {
            this.index = index;
            this.shape = shape;
            this.arm = arm;
            Arrays.fill(heads, -1);
            c = DriverManager.getConnection(url, server.role(WRITER), server.password(WRITER));
            c.setAutoCommit(!arm.equals(Arm.A_TX));
            String sql;
            if (shape.page()) {
                requireTrue(arm.supports(shape), arm + " has no form for " + shape);
                sql = switch (arm) {
                    case A_TX -> upsert(index);
                    case B_MULTI, B_BATCH -> multi(upsert(index), RESUME_ERRORING);
                    case D_EXPLICIT -> explicit(multi(upsert(index), RESUME_ERRORING));
                };
                resume = arm == Arm.A_TX ? c.prepareStatement(RESUME) : null;
                statement = c.prepareStatement(sql);
            } else {
                var appends = new String[arm == Arm.B_MULTI || arm == Arm.D_EXPLICIT ? shape.aggregates : 1];
                Arrays.fill(appends, APPEND);
                sql = arm == Arm.D_EXPLICIT ? explicit(multi(appends)) : multi(appends);
                resume = null;
                // A_TX batches §5.2's appends as one pgjdbc batch, as the engine does
                boolean batch = arm == Arm.B_BATCH || (arm == Arm.A_TX && shape.aggregates > 1);
                statement = batch ? c.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS) : c.prepareStatement(sql);
            }
        }

        Void loop() {
            while (!stop) {
                long t0 = System.nanoTime();
                try {
                    unit();
                } catch (Exception e) {
                    long t = System.nanoTime();
                    if (t0 >= measureFrom && t <= measureTo) failures++;
                    if (firstFailure == null) firstFailure = e.getClass().getSimpleName() + ": " + firstLine(e.getMessage());
                    continue;
                }
                long t1 = System.nanoTime();
                if (t0 >= measureFrom && t1 <= measureTo) {
                    units++;
                    latency.add(t1 - t0);
                }
            }
            return null;
        }

        /** One unit of work, committed or rolled back; the connection is ready for the next one either way. */
        void unit() throws SQLException {
            try {
                if (shape.page()) page();
                else append();
                committedUnits++;
            } catch (SQLException | RuntimeException e) {
                recover();
                throw e;
            }
        }

        private void recover() {
            try {
                if (arm == Arm.A_TX) {
                    c.rollback();
                } else if (c.unwrap(BaseConnection.class).getTransactionState() != TransactionState.IDLE) {
                    // D_EXPLICIT: the error left a failed transaction block behind the Sync
                    try (var s = c.createStatement()) {
                        s.execute("ROLLBACK");
                    }
                }
            } catch (SQLException ignored) {
                // the next unit reports it
            }
        }

        // -------------------------------------------------------------------------------------------- page

        private void page() throws SQLException {
            var a = new String[PAGE_SIZE];
            var g = new Long[PAGE_SIZE];
            var t = new String[PAGE_SIZE];
            var p = new String[PAGE_SIZE];
            for (int i = 0; i < PAGE_SIZE; i++) {
                long order = nextGlobalOrder + i;
                a[i] = "agg-" + (order % VIEW_ROWS);
                g[i] = order;
                t[i] = "OrderAmended";
                p[i] = "{\"orderId\":\"" + a[i] + "\",\"amount\":" + order + ",\"note\":\"" + "x".repeat(150) + "\"}";
            }
            long nextResume = nextGlobalOrder + PAGE_SIZE;
            int  k          = 1;
            k = bindUpsert(statement, k, a, g, t, p);
            switch (arm) {
                case A_TX -> {
                    statement.executeUpdate();
                    bindResume(resume, 1, nextResume);
                    if (resume.executeUpdate() != 1) throw new IllegalStateException("resume point update hit zero rows (stale fence or epoch)");
                    c.commit();
                }
                case B_MULTI, D_EXPLICIT, B_BATCH -> {
                    bindResume(statement, k, nextResume);
                    statement.execute();
                    drain(statement, null);
                }
            }
            nextGlobalOrder = nextResume;
            resumeFrom = nextResume;
        }

        private int bindUpsert(PreparedStatement ps, int k, String[] a, Long[] g, String[] t, String[] p) throws SQLException {
            ps.setArray(k++, c.createArrayOf("text", a));
            ps.setArray(k++, c.createArrayOf("bigint", g));
            ps.setArray(k++, c.createArrayOf("text", t));
            ps.setArray(k++, c.createArrayOf("text", p));
            return k;
        }

        private void bindResume(PreparedStatement ps, int k, long resumeFromValue) throws SQLException {
            ps.setLong(k++, resumeFromValue);
            ps.setString(k++, AGG_TYPE);
            ps.setString(k++, subscriber(index));
            ps.setLong(k++, EPOCH);
            ps.setString(k++, subscriber(index));
            ps.setString(k++, OWNER);
            ps.setLong(k, FENCE);
        }

        // -------------------------------------------------------------------------------------------- append

        private void append() throws SQLException {
            int   aggregates = shape.aggregates;
            var   picked     = new int[aggregates];
            for (int i = 0; i < aggregates; i++) picked[i] = (nextAggregate++) % AGGS_PER_WRITER;
            var returned = new int[aggregates];
            switch (arm) {
                case A_TX, B_BATCH -> {
                    boolean batch = arm == Arm.B_BATCH || aggregates > 1;
                    for (int i = 0; i < aggregates; i++) {
                        bindAppend(statement, 1, picked[i]);
                        if (batch) statement.addBatch();
                    }
                    if (batch) {
                        var counts = statement.executeBatch();
                        for (int i = 0; i < aggregates; i++) returned[i] = counts[i];
                        try (var rs = statement.getGeneratedKeys()) {
                            while (rs.next()) {
                                // global_order, event_order: read as the engine would build PersistedEvents
                                rs.getLong(1);
                                rs.getLong(2);
                            }
                        }
                    } else {
                        try (var rs = statement.executeQuery()) {
                            while (rs.next()) {
                                rs.getLong(1);
                                returned[0]++;
                            }
                        }
                    }
                    if (arm == Arm.A_TX) {
                        checkReturned(returned);
                        c.commit();
                    }
                }
                case B_MULTI, D_EXPLICIT -> {
                    for (int i = 0; i < aggregates; i++) bindAppend(statement, 1 + i * APPEND_PARAMS, picked[i]);
                    statement.execute();
                    drain(statement, returned);
                }
            }
            if (arm != Arm.A_TX) checkReturned(returned);
            for (int i = 0; i < aggregates; i++) heads[picked[i]] += shape.events;
        }

        /** §5.1's zero-row conflict. Never expected here: every writer owns its aggregates. */
        private void checkReturned(int[] returned) {
            for (int r : returned) if (r != shape.events) throw new IllegalStateException("append returned " + r + " rows, expected " + shape.events + " (conflict)");
        }

        private void bindAppend(PreparedStatement ps, int k, int aggregate) throws SQLException {
            int n           = shape.events;
            var aggregateId = "w" + index + "-" + aggregate;
            var ids         = new String[n];
            var causes      = new String[n];
            var correlation = new String[n];
            var types       = new String[n];
            var revisions   = new String[n];
            var timestamps  = new String[n];
            var payloads    = new String[n];
            var metadata    = new String[n];
            var now         = Instant.now().toString();
            for (int i = 0; i < n; i++) {
                ids[i] = UUID.randomUUID().toString();
                correlation[i] = UUID.randomUUID().toString();
                types[i] = UnattributedCpuSpikeIT.TYPE;
                revisions[i] = "1";
                timestamps[i] = now;
                payloads[i] = UnattributedCpuSpikeIT.PAYLOAD.formatted(aggregateId, ids[i]);
                metadata[i] = UnattributedCpuSpikeIT.METADATA.formatted(correlation[i]);
            }
            ps.setString(k++, aggregateId);
            ps.setLong(k++, heads[aggregate]);
            ps.setLong(k++, heads[aggregate]);
            ps.setString(k++, aggregateId);
            ps.setNull(k++, Types.VARCHAR);
            ps.setArray(k++, c.createArrayOf("text", ids));
            ps.setArray(k++, c.createArrayOf("text", causes));
            ps.setArray(k++, c.createArrayOf("text", correlation));
            ps.setArray(k++, c.createArrayOf("text", types));
            ps.setArray(k++, c.createArrayOf("text", revisions));
            ps.setArray(k++, c.createArrayOf("text", timestamps));
            ps.setArray(k++, c.createArrayOf("text", payloads));
            ps.setArray(k, c.createArrayOf("text", metadata));
        }

        /**
         * Reads every result of a multi-statement execute. With {@code returned}, the rows of each result set in turn
         * (the appends' RETURNING); BEGIN, COMMIT and the upsert give update counts.
         */
        private static void drain(Statement s, int[] returned) throws SQLException {
            int set = 0;
            while (true) {
                var rs = s.getResultSet();
                if (rs != null) {
                    try (rs) {
                        int rows = 0;
                        while (rs.next()) {
                            rs.getLong(1);
                            rows++;
                        }
                        if (returned != null && set < returned.length) returned[set] = rows;
                        set++;
                    }
                } else if (s.getUpdateCount() == -1) {
                    return;
                }
                s.getMoreResults();
            }
        }

        @Override
        public void close() throws SQLException {
            stop = true;
            c.close();
        }
    }

    private static void record(String line) {
        SpikePostgres.record(RESULTS, line);
    }
}
