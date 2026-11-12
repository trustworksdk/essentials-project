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

import static dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.spike.SpikePostgres.*;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * Spike P0.13 of {@code docs/plans/event-store-subscription-redesign.md} (§8.1-§8.3, §17 "Projection throughput",
 * §19 risk 6): what saving the resume point in the handler's transaction costs a view projection, one event per
 * transaction against a page per transaction, and what the per-event fallback costs when an event in a page fails.
 * <p>
 * A pre-filled event table ({@code spike.progress.events}, default 200 000 events over 20 000 aggregates, interleaved)
 * is projected into a view table keyed by aggregate id: one upsert per event ({@code INSERT ... ON CONFLICT DO UPDATE},
 * every aggregate's row pre-created so every write is an update). Each projection has its own connection, view table,
 * resume-point row and lease row, reads the event table in pages of {@code spike.progress.readSize} in autocommit
 * (the same for every arm) and writes as the arm says. The resume-point write is §8.2's statement with §9's fence
 * assertion ({@code AND reposition_epoch = ? AND EXISTS (lease row with this owner and fence)}); zero rows fails the run.
 * <ul>
 *     <li>{@code PER_EVENT}: one transaction per event: the upsert and the resume-point update.</li>
 *     <li>{@code PAGE_N} (N = 10, 100, 500): one transaction per page: one upsert statement per event, as an existing
 *     handler issues them, then one resume-point update.</li>
 *     <li>{@code PAGE_N_BATCH}: the page's upserts as one JDBC batch; {@code PAGE_N_SET}: as one set-based statement
 *     over {@code unnest} arrays (pre-aggregated per aggregate id, as a projection written for pages would).</li>
 *     <li>{@code PERIODIC}: today's model: one transaction per event for the upsert, and the resume point saved every
 *     {@code spike.progress.periodicMs} (default 1 000 ms, today's {@code snapshotResumePointsEvery}) on its own
 *     connection, outside the handler's transaction.</li>
 *     <li>{@code FALLBACK_K}: {@code PAGE_100} where every event whose global order is a multiple of K fails
 *     permanently. The page rolls back and is replayed one event per transaction; the failing event fails again, rolls
 *     back, and is dead-lettered (dead-letter insert and resume-point update in one transaction); page mode resumes
 *     with the next page.</li>
 * </ul>
 * With 1 and 4 concurrent projections, each run until its projections have read every event or for
 * {@code spike.progress.windowSeconds}. Per run: events/s, client transactions per event, server transactions per
 * event ({@code pg_stat_database}), WAL bytes, records and full-page images per event ({@code pg_stat_wal}, a
 * {@code CHECKPOINT} before each run), server CPU per event (container cgroup), commit latency, HOT share of the
 * resume-point and view updates, and a check that the view and the resume rows hold what was committed.
 * <p>
 * A container pass with {@code fsync=off} (every arm) and one with {@code fsync=on} (a subset), see
 * {@code spike.progress.fsync}; on an external server ({@code -Dspike.jdbcUrl}) one pass with the server's settings.
 * Results: {@code target/spike-results/p0-13-progress.txt}. Throwaway: runs only with {@code -Dspike.run=true}.
 */
@Tag("spike")
@EnabledIfSystemProperty(named = "spike.run", matches = "true")
class ProjectionProgressSpikeIT {
    private static final String RESULTS = "p0-13-progress.txt";

    private static final String        IMAGE          = System.getProperty("spike.progress.image", "postgres:17.5");
    private static final int           EVENTS         = Integer.getInteger("spike.progress.events", 200_000);
    private static final int           AGGREGATES     = Integer.getInteger("spike.progress.aggregates", 20_000);
    private static final int           READ_SIZE      = Integer.getInteger("spike.progress.readSize", 500);
    private static final int           WINDOW_SECONDS = Integer.getInteger("spike.progress.windowSeconds", 10);
    private static final int           PERIODIC_MS    = Integer.getInteger("spike.progress.periodicMs", 1000);
    private static final List<Integer> PROJECTIONS    = ints("spike.progress.projections", "1,4");
    private static final List<String>  FSYNC          = strings("spike.progress.fsync", "off,on");
    private static final List<String>  ARMS           = strings("spike.progress.arms",
                                                                "PER_EVENT,PERIODIC,PAGE_10,PAGE_100,PAGE_500,PAGE_100_BATCH,PAGE_500_BATCH,PAGE_100_SET,PAGE_500_SET,"
                                                                        + "FALLBACK_1000,FALLBACK_100");
    private static final List<String>  FSYNC_ON_ARMS  = strings("spike.progress.fsyncArms", "PER_EVENT,PERIODIC,PAGE_10,PAGE_100,PAGE_500_SET,FALLBACK_1000");

    private static final String EVENT_TABLE = TABLE;
    private static final String SUBS        = "spike_progress_subscriptions";
    private static final String LEASES      = "spike_progress_leases";
    private static final String DEAD        = "spike_progress_dead_letters";
    private static final String AGG_TYPE    = "Orders";
    private static final String OWNER       = "node-1";
    private static final long   FENCE       = 7;
    private static final long   EPOCH       = 3;

    private static List<Integer> ints(String property, String defaults) {
        return strings(property, defaults).stream().map(Integer::valueOf).toList();
    }

    private static List<String> strings(String property, String defaults) {
        return Arrays.stream(System.getProperty(property, defaults).split(",")).map(String::trim).filter(s -> !s.isEmpty()).toList();
    }

    private static String view(int i) {
        return "spike_progress_view_" + i;
    }

    // ------------------------------------------------------------------------------------------------------------------
    // Arms
    // ------------------------------------------------------------------------------------------------------------------

    enum Mode {PER_EVENT, PERIODIC, PAGE}

    enum Write {ROW, BATCH, SET}

    record Arm(String label, Mode mode, int page, Write write, int failEvery) {
        static Arm parse(String label) {
            if (label.equals("PER_EVENT")) return new Arm(label, Mode.PER_EVENT, 1, Write.ROW, 0);
            if (label.equals("PERIODIC")) return new Arm(label, Mode.PERIODIC, 1, Write.ROW, 0);
            if (label.startsWith("FALLBACK_")) return new Arm(label, Mode.PAGE, 100, Write.ROW, Integer.parseInt(label.substring(9)));
            if (label.startsWith("PAGE_")) {
                var parts = label.split("_");
                var write = parts.length > 2 ? Write.valueOf(parts[2]) : Write.ROW;
                return new Arm(label, Mode.PAGE, Integer.parseInt(parts[1]), write, 0);
            }
            throw new IllegalArgumentException(label);
        }
    }

    record Event(long globalOrder, String aggregateId, String type, String payload) {
    }

    /** Simulated permanent handler failure. */
    static final class HandlerFailure extends RuntimeException {
        HandlerFailure(long globalOrder) {
            super("simulated failure at " + globalOrder, null, false, false);
        }
    }

    /** Growable long array for latency samples. */
    static final class Samples {
        long[] v = new long[1024];
        int    n;

        void add(long x) {
            if (n == v.length) v = Arrays.copyOf(v, n * 2);
            v[n++] = x;
        }

        static double[] summary(List<Samples> all) {
            var count = all.stream().mapToInt(s -> s.n).sum();
            if (count == 0) return new double[]{Double.NaN, Double.NaN, Double.NaN};
            var merged = new long[count];
            var k      = 0;
            for (var s : all) {
                System.arraycopy(s.v, 0, merged, k, s.n);
                k += s.n;
            }
            Arrays.sort(merged);
            var sum = 0d;
            for (var x : merged) sum += x;
            return new double[]{merged[count / 2] / 1e3, merged[Math.min(count - 1, (int) (count * 0.99))] / 1e3, sum / count / 1e3};
        }
    }

    // ------------------------------------------------------------------------------------------------------------------

    private final List<String> mismatches = new ArrayList<>();

    @Test
    void progress_in_the_handlers_transaction_page_per_transaction() throws Exception {
        var started = System.nanoTime();
        record(RESULTS, "=== P0.13 projection progress: per event vs page per transaction vs periodic | " + SpikeServer.target(" | container " + IMAGE + " shared_buffers=256MB")
                + " | events=" + EVENTS + " aggregates=" + AGGREGATES + " readSize=" + READ_SIZE + " window=" + WINDOW_SECONDS + "s periodicMs=" + PERIODIC_MS
                + " projections=" + PROJECTIONS);
        record(RESULTS, "CSV,fsync,projections,arm,events,seconds,eventsPerSec,txPerEvent,rollbacksPerEvent,roundTripsPerEvent,serverXactPerEvent,walBytesPerEvent,"
                + "walRecordsPerEvent,fpiPerEvent,cpuUsPerEvent,cpuCores,commitP50us,commitP99us,commitMeanUs,commits,resumeHotPct,viewHotPct,deadLettered,"
                + "rolledBackWrites,check");
        if (SpikeServer.external()) {
            try (var server = SpikeServer.external("progress", "spike", false)) {
                runPass(server, "server", ARMS);
            }
        } else {
            for (var pass : FSYNC) {
                try (var server = container("on".equals(pass))) {
                    runPass(server, pass, "on".equals(pass) ? FSYNC_ON_ARMS : ARMS);
                }
            }
        }
        record(RESULTS, "=== done in %.1f s".formatted((System.nanoTime() - started) / 1e9));
        assertThat(mismatches).as("view/resume-point checks").isEmpty();
    }

    private static SpikeServer container(boolean fsync) {
        var container = new PostgreSQLContainer(IMAGE).withDatabaseName("spike").withUsername("spike").withPassword("spike")
                                                      .withSharedMemorySize(1024L * 1024 * 1024)
                                                      .withCommand("postgres", "-c", fsync ? "fsync=on" : "fsync=off", "-c", "shared_buffers=256MB",
                                                                   "-c", "max_wal_size=4GB", "-c", "max_connections=100");
        container.start();
        return SpikeServer.of(container);
    }

    /** pgjdbc defaults (prepareThreshold=5), as the engine's pool would use. */
    private static Connection open(SpikeServer server) throws SQLException {
        return DriverManager.getConnection(server.getJdbcUrl(), server.getUsername(), server.getPassword());
    }

    private void runPass(SpikeServer server, String pass, List<String> arms) throws Exception {
        var maxProjections = PROJECTIONS.stream().mapToInt(Integer::intValue).max().orElse(1);
        try (var observer = open(server)) {
            var settings = new StringBuilder();
            for (var name : List.of("server_version", "fsync", "synchronous_commit", "wal_sync_method", "full_page_writes", "shared_buffers", "max_wal_size")) {
                settings.append(name).append('=').append(queryString(observer, "SELECT current_setting('" + name + "')")).append(' ');
            }
            record(RESULTS, "--- pass fsync=" + pass + " | " + settings.toString().trim() + " | cpu " + server.cpuSource());
            setUp(observer, maxProjections);
            try {
                // discarded warm-up (JIT, plans, buffers)
                run(server, observer, pass, Arm.parse("PAGE_100"), 1, 3, false);
                run(server, observer, pass, Arm.parse("PER_EVENT"), 1, 3, false);
                for (var projections : PROJECTIONS) {
                    for (var label : arms) run(server, observer, pass, Arm.parse(label), projections, WINDOW_SECONDS, true);
                }
                // drift check: the first arm again
                run(server, observer, pass + "-drift", Arm.parse(arms.getFirst()), PROJECTIONS.getFirst(), WINDOW_SECONDS, true);
            } finally {
                try (var s = observer.createStatement()) {
                    for (var i = 0; i < maxProjections; i++) s.execute("DROP TABLE IF EXISTS " + view(i));
                    s.execute("DROP TABLE IF EXISTS " + SUBS + ", " + LEASES + ", " + DEAD + ", " + EVENT_TABLE);
                }
            }
        }
    }

    private static void setUp(Connection c, int maxProjections) throws SQLException {
        var t0 = System.nanoTime();
        recreateEventTable(c);
        try (var s = c.createStatement()) {
            s.execute("INSERT INTO " + EVENT_TABLE + " (global_order, aggregate_id, event_order, event_id, event_type, event_revision, timestamp, event_payload, event_metadata) "
                              + "SELECT g, 'agg-' || (g % " + AGGREGATES + "), g / " + AGGREGATES + " + 1, md5(g::text), 'OrderAmended', '1', now(), "
                              + "jsonb_build_object('orderId', 'agg-' || (g % " + AGGREGATES + "), 'amount', g, 'note', repeat('x', 150)), '{}'::jsonb "
                              + "FROM generate_series(1, " + EVENTS + ") g");
            s.execute("VACUUM (ANALYZE) " + EVENT_TABLE);
            s.execute("DROP TABLE IF EXISTS " + SUBS + ", " + LEASES + ", " + DEAD);
            // today's durable_subscriptions shape (PostgresqlDurableSubscriptionRepository), fillfactor below 100 per §8.2
            s.execute("CREATE TABLE " + SUBS + " (subscriber_id text NOT NULL, aggregate_type text NOT NULL, resume_from_and_including_global_eventorder bigint, "
                              + "last_updated timestamptz, reposition_epoch bigint NOT NULL DEFAULT 0, PRIMARY KEY (subscriber_id, aggregate_type)) WITH (fillfactor = 50)");
            s.execute("CREATE TABLE " + LEASES + " (lease_name text PRIMARY KEY, owner text NOT NULL, fence bigint NOT NULL)");
            s.execute("CREATE TABLE " + DEAD + " (global_order bigint NOT NULL, subscriber_id text NOT NULL, error text, added timestamptz NOT NULL, PRIMARY KEY (subscriber_id, global_order))");
            for (var i = 0; i < maxProjections; i++) {
                s.execute("DROP TABLE IF EXISTS " + view(i));
                s.execute("CREATE TABLE " + view(i) + " (aggregate_id text PRIMARY KEY, event_count bigint NOT NULL, last_global_order bigint NOT NULL, "
                                  + "last_event_type text, payload jsonb NOT NULL, updated_at timestamptz NOT NULL)");
                s.execute("INSERT INTO " + SUBS + " VALUES ('projection-" + i + "', '" + AGG_TYPE + "', 1, now(), " + EPOCH + ")");
                s.execute("INSERT INTO " + LEASES + " VALUES ('projection-" + i + "', '" + OWNER + "', " + FENCE + ")");
            }
        }
        record(RESULTS, "setup: %d events, %s event table, in %.1f s".formatted(EVENTS, queryString(c, "SELECT pg_size_pretty(pg_total_relation_size('" + EVENT_TABLE + "'))"),
                                                                               (System.nanoTime() - t0) / 1e9));
    }

    /** Fresh view tables (every aggregate's row present), resume rows at 1, dead letters cleared, a checkpoint. */
    private static void reset(Connection c, int projections) throws SQLException {
        try (var s = c.createStatement()) {
            for (var i = 0; i < projections; i++) {
                s.execute("TRUNCATE " + view(i));
                s.execute("INSERT INTO " + view(i) + " SELECT 'agg-' || a, 0, 0, NULL, '{}'::jsonb, now() FROM generate_series(0, " + (AGGREGATES - 1) + ") a");
                s.execute("VACUUM (ANALYZE) " + view(i));
            }
            s.execute("UPDATE " + SUBS + " SET resume_from_and_including_global_eventorder = 1, last_updated = now()");
            s.execute("TRUNCATE " + DEAD);
            s.execute("VACUUM (ANALYZE) " + SUBS);
            s.execute("CHECKPOINT");
        }
    }

    private static long[] serverCounters(Connection c) throws SQLException {
        try (var s = c.createStatement();
             var rs = s.executeQuery("SELECT (SELECT xact_commit + xact_rollback FROM pg_stat_database WHERE datname = current_database()), "
                                             + "pg_wal_lsn_diff(pg_current_wal_lsn(), '0/0')::bigint, w.wal_records, w.wal_fpi FROM pg_stat_wal w")) {
            rs.next();
            return new long[]{rs.getLong(1), rs.getLong(2), rs.getLong(3), rs.getLong(4)};
        }
    }

    private static long[] tableUpdates(Connection c, String table) throws SQLException {
        try (var ps = c.prepareStatement("SELECT n_tup_upd, n_tup_hot_upd FROM pg_stat_user_tables WHERE relname = ?")) {
            ps.setString(1, table);
            try (var rs = ps.executeQuery()) {
                return rs.next() ? new long[]{rs.getLong(1), rs.getLong(2)} : new long[]{0, 0};
            }
        }
    }

    private void run(SpikeServer server, Connection observer, String pass, Arm arm, int projections, int seconds, boolean measured) throws Exception {
        reset(observer, projections);
        Thread.sleep(600); // let the reset's statistics flush before the baseline is read
        var subsBefore = tableUpdates(observer, SUBS);
        var viewBefore = new long[2];
        for (var i = 0; i < projections; i++) {
            var u = tableUpdates(observer, view(i));
            viewBefore[0] += u[0];
            viewBefore[1] += u[1];
        }
        var before    = serverCounters(observer);
        var cpuBefore = server.cpuMicros();
        var workers   = new ArrayList<Projection>();
        for (var i = 0; i < projections; i++) workers.add(new Projection(server, i, arm));
        var pool     = Executors.newFixedThreadPool(projections);
        var t0       = System.nanoTime();
        var deadline = t0 + seconds * 1_000_000_000L;
        var futures  = new ArrayList<Future<?>>();
        for (var w : workers) futures.add(pool.submit(() -> {
            w.run(deadline);
            return null;
        }));
        try {
            for (var f : futures) f.get(seconds + 120L, TimeUnit.SECONDS);
        } finally {
            pool.shutdownNow();
            for (var w : workers) w.close();
        }
        var elapsed  = (System.nanoTime() - t0) / 1e9;
        var cpuAfter = server.cpuMicros();
        var after    = serverCounters(observer);
        Thread.sleep(1200); // pg_stat_user_tables is flushed by the backends at most once a second
        var subsAfter = tableUpdates(observer, SUBS);
        var viewAfter = new long[2];
        for (var i = 0; i < projections; i++) {
            var u = tableUpdates(observer, view(i));
            viewAfter[0] += u[0];
            viewAfter[1] += u[1];
        }
        if (!measured) return;

        long events = 0, commits = 0, rollbacks = 0, roundTrips = 0, dead = 0, wasted = 0;
        var  commitSamples = new ArrayList<Samples>();
        var  check         = new StringBuilder();
        for (var w : workers) {
            events += w.events;
            commits += w.commits + w.periodicSaves.get();
            rollbacks += w.rollbacks;
            roundTrips += w.roundTrips + w.periodicSaves.get();
            dead += w.deadLettered;
            wasted += w.rolledBackWrites;
            commitSamples.add(w.commitNanos);
            check.append(verify(observer, w));
        }
        var checkText = check.isEmpty() ? "ok" : check.toString().trim();
        if (!check.isEmpty()) mismatches.add(pass + " " + projections + " " + arm.label + ": " + checkText);
        var cpu    = SpikeServer.cpuDelta(cpuBefore, cpuAfter);
        var commit = Samples.summary(commitSamples);
        var e      = (double) events;
        var resumeHot = pct(subsAfter[1] - subsBefore[1], subsAfter[0] - subsBefore[0]);
        var viewHot   = pct(viewAfter[1] - viewBefore[1], viewAfter[0] - viewBefore[0]);
        record(RESULTS, "CSV,%s,%d,%s,%d,%.2f,%.0f,%.4f,%.5f,%.3f,%.4f,%.1f,%.3f,%.4f,%.1f,%.2f,%.0f,%.0f,%.0f,%d,%.0f,%.0f,%d,%d,%s".formatted(
                pass, projections, arm.label, events, elapsed, e / elapsed, (commits + rollbacks) / e, rollbacks / e, roundTrips / e, (after[0] - before[0]) / e,
                (after[1] - before[1]) / e, (after[2] - before[2]) / e, (after[3] - before[3]) / e, cpu / e, cpu / 1e6 / elapsed, commit[0], commit[1], commit[2], commits,
                resumeHot, viewHot, dead, wasted, checkText));
        record(RESULTS, "fsync=%-8s P=%d %-15s %8d events in %5.1f s = %7.0f ev/s | tx/ev %.4f | rt/ev %.3f | WAL %6.1f B/ev (fpi/ev %.4f) | cpu %5.1f µs/ev | commit p50 %.0f p99 %.0f µs | "
                        .formatted(pass, projections, arm.label, events, elapsed, e / elapsed, (commits + rollbacks) / e, roundTrips / e, (after[1] - before[1]) / e,
                                   (after[3] - before[3]) / e, cpu / e, commit[0], commit[1])
                        + "resume HOT %.0f%% view HOT %.0f%% | dead %d | %s".formatted(resumeHot, viewHot, dead, checkText));
    }

    private static double pct(long part, long whole) {
        return whole <= 0 ? Double.NaN : 100.0 * part / whole;
    }

    /** The view holds one count per committed write and the resume row is just past the last committed event. */
    private static String verify(Connection c, Projection w) throws SQLException {
        var counted = queryLong(c, "SELECT coalesce(sum(event_count), 0) FROM " + view(w.index));
        var resume  = queryLong(c, "SELECT resume_from_and_including_global_eventorder FROM " + SUBS + " WHERE subscriber_id = 'projection-" + w.index + "'");
        var dead    = queryLong(c, "SELECT count(*) FROM " + DEAD + " WHERE subscriber_id = 'projection-" + w.index + "'");
        var out     = new StringBuilder();
        if (counted != w.events - w.deadLettered) out.append(" p").append(w.index).append(" view=").append(counted).append("/expected=").append(w.events - w.deadLettered);
        if (resume != w.lastCommitted + 1) out.append(" p").append(w.index).append(" resume=").append(resume).append("/expected=").append(w.lastCommitted + 1);
        if (dead != w.deadLettered) out.append(" p").append(w.index).append(" dead=").append(dead).append("/expected=").append(w.deadLettered);
        return out.toString();
    }

    // ------------------------------------------------------------------------------------------------------------------
    // One projection
    // ------------------------------------------------------------------------------------------------------------------

    static final class Projection implements AutoCloseable {
        final int               index;
        final Arm               arm;
        final Connection        c;
        final PreparedStatement read, upsert, setUpsert, resume, deadLetter;
        final Samples           commitNanos = new Samples();
        final String            subscriber;
        // PERIODIC
        final Connection        saverConnection;
        final PreparedStatement saverResume;
        final AtomicLong        latestProcessed = new AtomicLong(0);
        final AtomicLong        periodicSaves   = new AtomicLong(0);
        long                    savedUpTo;

        long events, commits, rollbacks, roundTrips, deadLettered, rolledBackWrites, lastCommitted;

        Projection(SpikeServer server, int index, Arm arm) throws SQLException {
            this.index = index;
            this.arm = arm;
            this.subscriber = "projection-" + index;
            c = open(server);
            read = c.prepareStatement("SELECT global_order, aggregate_id, event_type, event_payload::text FROM " + EVENT_TABLE
                                              + " WHERE global_order > ? ORDER BY global_order LIMIT ?");
            var cols = "(aggregate_id, event_count, last_global_order, last_event_type, payload, updated_at)";
            var onConflict = " ON CONFLICT (aggregate_id) DO UPDATE SET event_count = v.event_count + excluded.event_count, last_global_order = excluded.last_global_order, "
                    + "last_event_type = excluded.last_event_type, payload = excluded.payload, updated_at = excluded.updated_at";
            upsert = c.prepareStatement("INSERT INTO " + view(index) + " AS v " + cols + " VALUES (?, 1, ?, ?, ?::jsonb, now())" + onConflict);
            setUpsert = c.prepareStatement("INSERT INTO " + view(index) + " AS v " + cols
                                                   + " SELECT DISTINCT ON (a) a, count(*) OVER (PARTITION BY a), g, t, p::jsonb, now()"
                                                   + " FROM unnest(?::text[], ?::bigint[], ?::text[], ?::text[]) AS u(a, g, t, p) ORDER BY a, g DESC" + onConflict);
            resume = c.prepareStatement(resumeSql());
            deadLetter = c.prepareStatement("INSERT INTO " + DEAD + " (global_order, subscriber_id, error, added) VALUES (?, ?, ?, now())");
            if (arm.mode == Mode.PERIODIC) {
                saverConnection = open(server);
                saverResume = saverConnection.prepareStatement(resumeSql());
            } else {
                saverConnection = null;
                saverResume = null;
            }
        }

        /** §8.2's statement with §9's fence assertion. */
        private static String resumeSql() {
            return "UPDATE " + SUBS + " SET resume_from_and_including_global_eventorder = ?, last_updated = now() "
                    + "WHERE aggregate_type = ? AND subscriber_id = ? AND reposition_epoch = ? "
                    + "AND EXISTS (SELECT 1 FROM " + LEASES + " l WHERE l.lease_name = ? AND l.owner = ? AND l.fence = ?)";
        }

        private static void bindResume(PreparedStatement ps, String subscriber, long resumeFrom) throws SQLException {
            ps.setLong(1, resumeFrom);
            ps.setString(2, AGG_TYPE);
            ps.setString(3, subscriber);
            ps.setLong(4, EPOCH);
            ps.setString(5, subscriber);
            ps.setString(6, OWNER);
            ps.setLong(7, FENCE);
        }

        private void saveResume(long resumeFrom) throws SQLException {
            bindResume(resume, subscriber, resumeFrom);
            roundTrips++;
            if (resume.executeUpdate() != 1) throw new IllegalStateException("resume point update hit zero rows (fence or epoch)");
        }

        private List<Event> readPage(long after) throws SQLException {
            read.setLong(1, after);
            read.setInt(2, READ_SIZE);
            var page = new ArrayList<Event>(READ_SIZE);
            roundTrips++;
            try (var rs = read.executeQuery()) {
                while (rs.next()) page.add(new Event(rs.getLong(1), rs.getString(2), rs.getString(3), rs.getString(4)));
            }
            return page;
        }

        private void commit() throws SQLException {
            var t = System.nanoTime();
            c.commit();
            commitNanos.add(System.nanoTime() - t);
            roundTrips++;
            commits++;
        }

        private void rollback() throws SQLException {
            c.rollback();
            roundTrips++;
            rollbacks++;
        }

        /** The handler: fails for every K-th global order, else one upsert statement. */
        private void handle(Event e) throws SQLException {
            if (arm.failEvery > 0 && e.globalOrder % arm.failEvery == 0) throw new HandlerFailure(e.globalOrder);
            upsert.setString(1, e.aggregateId);
            upsert.setLong(2, e.globalOrder);
            upsert.setString(3, e.type);
            upsert.setString(4, e.payload);
            roundTrips++;
            upsert.executeUpdate();
        }

        void run(long deadline) throws Exception {
            ScheduledExecutorService saver = null;
            if (arm.mode == Mode.PERIODIC) {
                saver = Executors.newSingleThreadScheduledExecutor();
                saver.scheduleAtFixedRate(this::periodicSave, PERIODIC_MS, PERIODIC_MS, TimeUnit.MILLISECONDS);
            }
            try {
                c.setAutoCommit(true);
                long cursor = 0;
                outer:
                while (System.nanoTime() < deadline) {
                    var page = readPage(cursor);
                    if (page.isEmpty()) break;
                    c.setAutoCommit(false);
                    try {
                        if (arm.mode == Mode.PAGE) {
                            for (var from = 0; from < page.size(); from += arm.page) {
                                if (System.nanoTime() >= deadline) break outer;
                                var sub = page.subList(from, Math.min(page.size(), from + arm.page));
                                processPage(sub);
                                cursor = sub.getLast().globalOrder;
                            }
                        } else {
                            for (var e : page) {
                                if ((e.globalOrder & 63) == 0 && System.nanoTime() >= deadline) break outer;
                                handle(e);
                                if (arm.mode == Mode.PER_EVENT) saveResume(e.globalOrder + 1);
                                commit();
                                events++;
                                lastCommitted = e.globalOrder;
                                if (arm.mode == Mode.PERIODIC) latestProcessed.set(e.globalOrder + 1);
                                cursor = e.globalOrder;
                            }
                        }
                    } finally {
                        c.setAutoCommit(true);
                    }
                }
            } finally {
                if (saver != null) {
                    saver.shutdown();
                    saver.awaitTermination(30, TimeUnit.SECONDS);
                    periodicSave(); // the final save on stop
                }
            }
        }

        private void processPage(List<Event> page) throws SQLException {
            var written = 0;
            try {
                switch (arm.write) {
                    case ROW -> {
                        for (var e : page) {
                            handle(e);
                            written++;
                        }
                    }
                    case BATCH -> {
                        for (var e : page) {
                            upsert.setString(1, e.aggregateId);
                            upsert.setLong(2, e.globalOrder);
                            upsert.setString(3, e.type);
                            upsert.setString(4, e.payload);
                            upsert.addBatch();
                        }
                        roundTrips++;
                        upsert.executeBatch();
                    }
                    case SET -> {
                        setUpsert.setArray(1, c.createArrayOf("text", page.stream().map(Event::aggregateId).toArray(String[]::new)));
                        setUpsert.setArray(2, c.createArrayOf("bigint", page.stream().map(Event::globalOrder).toArray(Long[]::new)));
                        setUpsert.setArray(3, c.createArrayOf("text", page.stream().map(Event::type).toArray(String[]::new)));
                        setUpsert.setArray(4, c.createArrayOf("text", page.stream().map(Event::payload).toArray(String[]::new)));
                        roundTrips++;
                        setUpsert.executeUpdate();
                    }
                }
                saveResume(page.getLast().globalOrder + 1);
                commit();
                events += page.size();
                lastCommitted = page.getLast().globalOrder;
            } catch (HandlerFailure failure) {
                rollback();
                rolledBackWrites += written;
                replayPerEvent(page);
            }
        }

        /** §8.3: the failed page again, one event per transaction; a failing event is dead-lettered, then page mode resumes. */
        private void replayPerEvent(List<Event> page) throws SQLException {
            for (var e : page) {
                try {
                    handle(e);
                    saveResume(e.globalOrder + 1);
                    commit();
                } catch (HandlerFailure failure) {
                    rollback();
                    deadLetter.setLong(1, e.globalOrder);
                    deadLetter.setString(2, subscriber);
                    deadLetter.setString(3, failure.getMessage());
                    roundTrips++;
                    deadLetter.executeUpdate();
                    saveResume(e.globalOrder + 1);
                    commit();
                    deadLettered++;
                }
                events++;
                lastCommitted = e.globalOrder;
            }
        }

        private synchronized void periodicSave() {
            var latest = latestProcessed.get();
            if (latest == 0 || latest == savedUpTo) return;
            try {
                bindResume(saverResume, subscriber, latest);
                if (saverResume.executeUpdate() != 1) throw new IllegalStateException("periodic resume point update hit zero rows");
                savedUpTo = latest;
                periodicSaves.incrementAndGet(); // an autocommit transaction of its own, on the saver's connection
            } catch (SQLException ex) {
                throw new IllegalStateException(ex);
            }
        }

        @Override
        public void close() throws SQLException {
            c.close();
            if (saverConnection != null) saverConnection.close();
        }
    }
}
