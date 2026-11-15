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
import dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.spike.MinimalWireClient.Type;
import dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.spike.ProjectionProgressSpikeIT.Samples;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.testcontainers.postgresql.PostgreSQLContainer;

import java.lang.management.*;
import java.sql.*;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import java.util.function.LongFunction;
import java.util.stream.*;

import static dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.spike.SpikePostgres.*;
import static dk.trustworks.essentials.shared.FailFast.requireTrue;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * Spike P0.16 of {@code docs/plans/event-store-subscription-redesign.md} §18 (background:
 * {@code docs/plans/event-store-phase0-beyond-jdbc-spikes.md}, "P0.16: owned connections for the engine's reads"): for
 * the engine's own long-lived connections, what does an owned connection with every statement prepared at connect and
 * fixed session settings save per call over pooled pgjdbc defaults; which {@code plan_cache_mode} keeps each statement
 * on its index; and do the tail reads of several woken aggregate types in one flush beat one round trip per type?
 * <p>
 * Tables: P0.11's event table ({@code -Dspike.owned.rows}, default 2 million rows, today's layout, a 1-million-value
 * sequence jump below the head and a pool of deleted rows just below the head as outstanding holes), the subscription
 * and lease tables of §8.2/§9 ({@code -Dspike.owned.subscribers}, default 1 000 rows each, so a lookup is an index
 * question), and up to the largest of {@code -Dspike.owned.types} (default {@code 1,10,50}) aggregate-type tables of
 * {@code -Dspike.owned.typeRows} rows (default 20 000) each.
 * <p>
 * Statements ({@code -Dspike.owned.statements}), each tagged with a trailing comment:
 * <ul>
 *     <li>{@code TAIL_0} / {@code TAIL_1} / {@code TAIL_100}: the tail read ({@code global_order > ? ORDER BY ... LIMIT ?})
 *     with 0, 1 and 100 new rows;</li>
 *     <li>{@code PROBE_0} / {@code PROBE_10}: P0.11's {@code UNION ALL} combined probe (tail read {@code UNION ALL} a hole
 *     read by {@code = ANY(?)}, with the running set read through {@code pg_stat_get_activity(NULL)} as P0.12 proposes),
 *     1 new row, 0 and 10 outstanding holes;</li>
 *     <li>{@code RESUME}: §8.2's resume-point update with §9's fence assertion (P0.13/P0.14's form), autocommit.</li>
 * </ul>
 * Arms ({@code -Dspike.owned.arms}):
 * <ul>
 *     <li>{@code A_POOL}: a Hikari pool with its and pgjdbc's defaults ({@code prepareThreshold=5}); every call borrows
 *     a connection and prepares the statement, as the engine's code does today;</li>
 *     <li>{@code B_AUTO} / {@code B_CUSTOM} / {@code B_GENERIC}: one owned pgjdbc connection, {@code prepareThreshold=1},
 *     {@code options=-c jit=off -c max_parallel_workers_per_gather=0 -c plan_cache_mode=<mode>}, every statement prepared
 *     and executed once at connect;</li>
 *     <li>{@code C_AUTO} / {@code C_CUSTOM} / {@code C_GENERIC}: {@link MinimalWireClient}: the same settings in the
 *     startup message, every statement parsed in one round trip at connect, binary parameters and results.</li>
 * </ul>
 * Several types ({@code -Dspike.owned.wakeArms}): N types woken at once, each tail read with
 * {@code -Dspike.owned.wakeNewRows} new rows (default 1), all owned connections under
 * {@code -Dspike.owned.wakeMode} (default {@code auto}):
 * <ul>
 *     <li>{@code PER_TYPE}: (i) one reader thread and owned connection per type, one round trip each, released together;</li>
 *     <li>{@code FLUSH_PGJDBC}: (ii) one owned connection, the N tail reads as one multi-statement
 *     {@code PreparedStatement}, {@code execute()} then {@code getMoreResults()}. pgjdbc sends one Sync at the end, not
 *     one per statement, and forces an intermediate Sync once it estimates 64 000 bytes of replies, which a result row
 *     of unknown width counts for alone; the round-trip column shows what that leaves of "one flush";</li>
 *     <li>{@code FLUSH_WIRE}: (ii) through {@link MinimalWireClient}: N Bind/Execute/Sync in one flush, then the N
 *     replies in order;</li>
 *     <li>{@code FLUSH_WIRE_1SYNC}: as {@code FLUSH_WIRE} with one Sync after the last read, as pgjdbc's multi-statement
 *     form sends it (one implicit transaction, one ReadyForQuery), to separate the Sync count from the client.</li>
 * </ul>
 * Before the windows: per statement, {@code EXPLAIN (GENERIC_PLAN)} (PostgreSQL 16+) and, per plan mode, the plan a
 * SQL-level prepared statement actually uses after six executions ({@code EXPLAIN EXECUTE} and
 * {@code pg_prepared_statements}' generic/custom counts), each checked for "no sequential scan". Round trips per call
 * and per wake come from a {@link RoundTripCountingProxy} pass of {@code -Dspike.owned.roundTripCalls} calls after a
 * 20-call warm-up.
 * <p>
 * Timed windows, one caller thread: per statement the arms in a palindrome, per type count the wake arms in a
 * palindrome, each after {@code -Dspike.owned.warmupMillis} (1 000) of warm-up, {@code -Dspike.owned.seconds} (10) per
 * statement window and {@code -Dspike.owned.wakeSeconds} (10) per wake window. Measured: server CPU per call or wake
 * (container cgroup, or {@code -Dspike.serverCpuCommand}) and its system part, recorded execution and planning time
 * and planner runs ({@code pg_stat_statements} of the reader role, {@code track_planning} on), client CPU (the calling
 * threads, {@link ThreadMXBean}), latency p50/p99, and the reader connection's own generic/custom plan counts. A
 * container runs {@code fsync=on} ({@code -Dspike.owned.fsync}, a list); an external server one pass with its own
 * settings. Results: {@code target/spike-results/p0-16-owned.txt}, CSV lines and GATE lines for the row's four
 * clauses.
 * <p>
 * Not built: the periodic resume-point writer and the notifier as separate connections (RESUME is the writer's
 * statement; the notifier was measured in P0.10), and arm C over TLS (the wire client is plain TCP).
 * <p>
 * Throwaway: runs only with {@code -Dspike.run=true}.
 */
@Tag("spike")
@EnabledIfSystemProperty(named = "spike.run", matches = "true")
class OwnedReadConnectionSpikeIT {
    private static final String        RESULTS        = "p0-16-owned.txt";
    private static final String        IMAGE          = System.getProperty("spike.owned.image", "postgres:17.5");
    private static final int           ROWS           = Integer.getInteger("spike.owned.rows", 2_000_000);
    private static final int           TYPE_ROWS      = Integer.getInteger("spike.owned.typeRows", 20_000);
    private static final int           SUBSCRIBERS    = Integer.getInteger("spike.owned.subscribers", 1000);
    private static final int           SECONDS        = Integer.getInteger("spike.owned.seconds", 10);
    private static final int           WAKE_SECONDS   = Integer.getInteger("spike.owned.wakeSeconds", 10);
    private static final int           WARMUP_MILLIS  = Integer.getInteger("spike.owned.warmupMillis", 1000);
    private static final int           RT_CALLS       = Integer.getInteger("spike.owned.roundTripCalls", 200);
    private static final int           LIMIT          = Integer.getInteger("spike.owned.limit", 1000);
    private static final int           WAKE_NEW_ROWS  = Integer.getInteger("spike.owned.wakeNewRows", 1);
    private static final String        WAKE_MODE      = System.getProperty("spike.owned.wakeMode", "auto");
    private static final List<Integer> TYPES          = ints("spike.owned.types", "1,10,50");
    private static final List<String>  FSYNC          = strings("spike.owned.fsync", "on");
    private static final List<String>  STATEMENTS     = strings("spike.owned.statements", "TAIL_0,TAIL_1,TAIL_100,PROBE_0,PROBE_10,RESUME");
    private static final List<Arm>     ARMS           = strings("spike.owned.arms", "A_POOL,B_AUTO,B_CUSTOM,B_GENERIC,C_AUTO").stream().map(Arm::valueOf).toList();
    private static final List<WakeArm> WAKE_ARMS      = strings("spike.owned.wakeArms", "PER_TYPE,FLUSH_PGJDBC,FLUSH_WIRE,FLUSH_WIRE_1SYNC").stream().map(WakeArm::valueOf).toList();

    private static final double GATE_OWNED_US   = 20;
    private static final double GATE_OWNED_P50  = 0.20;
    private static final double GATE_WAKE_CUT   = 0.30;
    private static final int    GATE_WAKE_TYPES = 10;
    private static final double GATE_C_CUT      = 0.20;

    private static final String READER     = "reader";
    private static final String EVENTS     = "p016_events";
    private static final String SUBS       = "p016_subscriptions";
    private static final String LEASES     = "p016_leases";
    private static final String AGG_TYPE   = "Orders";
    private static final String SUBSCRIBER = "projection-0";
    private static final String OWNER      = "node-1";
    private static final long   FENCE      = 7;
    private static final long   EPOCH      = 3;
    private static final long   JUMP       = 1_000_000;
    private static final int    HOLE_POOL  = 100;
    private static final int    NEAR_FROM  = 200;
    private static final int    SPACING    = 3;
    private static final String COLUMNS    = "e.global_order, e.aggregate_id, e.event_order, e.event_id, e.caused_by_event_id, e.correlation_id, e.event_type, "
            + "e.event_revision, e.timestamp, e.event_payload, e.event_metadata, e.tenant";
    private static final String RUNNING    = "(SELECT array_agg(backend_xid::text) FROM pg_stat_get_activity(NULL) WHERE backend_xid IS NOT NULL)";
    private static final String EVENT_TYPE = "FQCN:dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.test_data.OrderEvent$ProductAddedToOrder";
    private static final List<String> MODES = List.of("auto", "force_custom_plan", "force_generic_plan");

    enum Kind {POOL, OWNED, WIRE}

    enum Arm {
        A_POOL(Kind.POOL, null), B_AUTO(Kind.OWNED, "auto"), B_CUSTOM(Kind.OWNED, "force_custom_plan"), B_GENERIC(Kind.OWNED, "force_generic_plan"),
        C_AUTO(Kind.WIRE, "auto"), C_CUSTOM(Kind.WIRE, "force_custom_plan"), C_GENERIC(Kind.WIRE, "force_generic_plan");

        final Kind   kind;
        final String mode;

        Arm(Kind kind, String mode) {
            this.kind = kind;
            this.mode = mode;
        }

        static Arm of(Kind kind, String mode) {
            return Arrays.stream(values()).filter(a -> a.kind == kind && Objects.equals(a.mode, mode)).findFirst().orElseThrow();
        }
    }

    enum WakeArm {PER_TYPE, FLUSH_PGJDBC, FLUSH_WIRE, FLUSH_WIRE_1SYNC}

    private static List<Integer> ints(String property, String defaults) {
        return strings(property, defaults).stream().map(Integer::valueOf).toList();
    }

    private static List<String> strings(String property, String defaults) {
        return Arrays.stream(System.getProperty(property, defaults).split(",")).map(String::trim).filter(s -> !s.isEmpty()).toList();
    }

    /** The session settings of every owned connection, as {@code options}. */
    static String options(String mode) {
        return "-c jit=off -c max_parallel_workers_per_gather=0 -c plan_cache_mode=" + mode;
    }

    // ------------------------------------------------------------------------------------------------------------------
    // Statements
    // ------------------------------------------------------------------------------------------------------------------

    /**
     * One statement: SQL with {@code ?} placeholders, its parameter types, the values of call {@code i}, and the rows it
     * must return (or update).
     */
    record Stmt(String label, String sql, List<Type> types, LongFunction<Object[]> values, int expected, boolean tailRead) {
        /** Trailing tag: PostgreSQL 18's pg_stat_statements drops a leading comment from the stored text. */
        String tagged() {
            return sql + " /*p016 " + label + "*/";
        }

        /** {@link #tagged()} with {@code $n} placeholders, for SQL-level PREPARE, EXPLAIN (GENERIC_PLAN) and the wire client. */
        String numbered() {
            return SpikeSql.numbered(tagged());
        }

        String wireName() {
            return "p016_" + label.toLowerCase(Locale.ROOT);
        }
    }

    static final class SpikeSql {
        static String numbered(String sql) {
            var b = new StringBuilder();
            int n = 0;
            for (int i = 0; i < sql.length(); i++) {
                char ch = sql.charAt(i);
                if (ch == '?') b.append('$').append(++n);
                else b.append(ch);
            }
            return b.toString();
        }
    }

    static String tailSql(String table) {
        return "SELECT " + COLUMNS + " FROM " + table + " e WHERE e.global_order > ? ORDER BY e.global_order LIMIT ?";
    }

    static final String RESUME = "UPDATE " + SUBS + " SET resume_from_and_including_global_eventorder = ?, last_updated = now() "
            + "WHERE aggregate_type = ? AND subscriber_id = ? AND reposition_epoch = ? "
            + "AND EXISTS (SELECT 1 FROM " + LEASES + " l WHERE l.lease_name = ? AND l.owner = ? AND l.fence = ?)";

    private static Stmt statement(String label, long head, List<Long> holes) {
        int limit = LIMIT;
        return switch (label) {
            case "TAIL_0", "TAIL_1", "TAIL_100" -> {
                int n = Integer.parseInt(label.substring(5));
                yield new Stmt(label, tailSql(EVENTS), List.of(Type.INT8, Type.INT4), i -> new Object[]{head - n, limit}, n, true);
            }
            case "PROBE_0", "PROBE_10" -> {
                int h      = Integer.parseInt(label.substring(6));
                var values = holes.subList(0, h).stream().mapToLong(Long::longValue).toArray();
                var from   = " FROM " + EVENTS + " e ";
                var sql    = "SELECT u.*, " + RUNNING + " AS running FROM ((SELECT " + COLUMNS + from + "WHERE e.global_order > ? ORDER BY e.global_order LIMIT ?) "
                        + "UNION ALL (SELECT " + COLUMNS + from + "WHERE e.global_order = ANY(?))) u ORDER BY u.global_order";
                yield new Stmt(label, sql, List.of(Type.INT8, Type.INT4, Type.INT8_ARRAY), i -> new Object[]{head - 1, limit, values}, 1, true);
            }
            case "RESUME" -> new Stmt(label, RESUME, List.of(Type.INT8, Type.TEXT, Type.TEXT, Type.INT8, Type.TEXT, Type.TEXT, Type.INT8),
                                      i -> new Object[]{i, AGG_TYPE, SUBSCRIBER, EPOCH, SUBSCRIBER, OWNER, FENCE}, 1, false);
            default -> throw new IllegalArgumentException("spike.owned.statements: unknown " + label);
        };
    }

    private static String typeTable(int k) {
        return "p016_type_" + k;
    }

    private static Stmt wakeStatement(int k) {
        return new Stmt("WAKE_" + k, tailSql(typeTable(k)), List.of(Type.INT8, Type.INT4), i -> new Object[]{(long) TYPE_ROWS - WAKE_NEW_ROWS, LIMIT}, WAKE_NEW_ROWS, true);
    }

    // ------------------------------------------------------------------------------------------------------------------
    // pgjdbc binding and reading
    // ------------------------------------------------------------------------------------------------------------------

    static int bind(Connection c, PreparedStatement ps, int k, Stmt s, Object[] values) throws SQLException {
        for (int i = 0; i < values.length; i++) {
            switch (s.types().get(i)) {
                case INT8 -> ps.setLong(k++, (Long) values[i]);
                case INT4 -> ps.setInt(k++, (Integer) values[i]);
                case TEXT -> ps.setString(k++, (String) values[i]);
                case INT8_ARRAY -> ps.setArray(k++, c.createArrayOf("bigint", Arrays.stream((long[]) values[i]).boxed().toArray(Long[]::new)));
            }
        }
        return k;
    }

    /** Executes and reads every column of every row as text, as the engine would; returns rows read or rows updated. */
    static int run(PreparedStatement ps) throws SQLException {
        if (!ps.execute()) return ps.getUpdateCount();
        try (var rs = ps.getResultSet()) {
            return readAll(rs);
        }
    }

    static int readAll(ResultSet rs) throws SQLException {
        int columns = rs.getMetaData().getColumnCount(), rows = 0;
        while (rs.next()) {
            for (int i = 1; i <= columns; i++) rs.getString(i);
            rows++;
        }
        return rows;
    }

    static Connection owned(String url, String user, String password, String mode) throws SQLException {
        var props = new Properties();
        props.setProperty("user", user);
        props.setProperty("password", password);
        props.setProperty("prepareThreshold", "1");
        props.setProperty("options", options(mode));
        var c = DriverManager.getConnection(url, props);
        c.setAutoCommit(true);
        return c;
    }

    private static String planCounts(Connection c) throws SQLException {
        try (var s = c.createStatement();
             var rs = s.executeQuery("SELECT count(*), coalesce(sum(generic_plans), 0), coalesce(sum(custom_plans), 0) FROM pg_prepared_statements")) {
            rs.next();
            return "named " + rs.getLong(1) + " generic " + rs.getLong(2) + " custom " + rs.getLong(3);
        }
    }

    // ------------------------------------------------------------------------------------------------------------------
    // Callers: one per arm, each runs one statement per call
    // ------------------------------------------------------------------------------------------------------------------

    interface Caller extends AutoCloseable {
        int call(Stmt s, long i) throws Exception;

        /** The reader connection's own named statements and their generic/custom plan counts. */
        String planCounts() throws Exception;

        @Override
        void close() throws Exception;
    }

    /** A: borrow from Hikari, prepare, execute, return; pgjdbc and Hikari defaults. */
    static final class PoolCaller implements Caller {
        final HikariDataSource ds;

        PoolCaller(String url, String user, String password) {
            var config = new HikariConfig();
            config.setJdbcUrl(url);
            config.setUsername(user);
            config.setPassword(password);
            config.setPoolName("p016-pool");
            ds = new HikariDataSource(config);
        }

        @Override
        public int call(Stmt s, long i) throws SQLException {
            try (var c = ds.getConnection(); var ps = c.prepareStatement(s.tagged())) {
                bind(c, ps, 1, s, s.values().apply(i));
                return run(ps);
            }
        }

        @Override
        public String planCounts() throws SQLException {
            try (var c = ds.getConnection()) {
                return "pooled connection: " + OwnedReadConnectionSpikeIT.planCounts(c);
            }
        }

        @Override
        public void close() {
            ds.close();
        }
    }

    /** B: one owned pgjdbc connection, every statement prepared and executed once at connect. */
    static final class OwnedCaller implements Caller {
        final Connection                     c;
        final Map<String, PreparedStatement> prepared = new HashMap<>();

        OwnedCaller(String url, String user, String password, String mode, List<Stmt> all) throws SQLException {
            c = owned(url, user, password, mode);
            for (var s : all) {
                var ps = c.prepareStatement(s.tagged());
                prepared.put(s.label(), ps);
                bind(c, ps, 1, s, s.values().apply(0));
                run(ps);
            }
        }

        @Override
        public int call(Stmt s, long i) throws SQLException {
            var ps = prepared.get(s.label());
            bind(c, ps, 1, s, s.values().apply(i));
            return run(ps);
        }

        @Override
        public String planCounts() throws SQLException {
            return OwnedReadConnectionSpikeIT.planCounts(c);
        }

        @Override
        public void close() throws SQLException {
            c.close();
        }
    }

    /** C: the minimal wire client, every statement parsed in one round trip at connect, binary in and out. */
    static final class WireCaller implements Caller {
        final MinimalWireClient client;

        WireCaller(String host, int port, String database, String user, String password, String mode, List<Stmt> all) throws Exception {
            client = new MinimalWireClient(host, port, database, user, password, options(mode));
            var parse = new LinkedHashMap<String, Map.Entry<String, List<Type>>>();
            for (var s : all) parse.put(s.wireName(), Map.entry(s.numbered(), s.types()));
            client.prepareAll(parse);
        }

        @Override
        public int call(Stmt s, long i) throws Exception {
            client.queue(s.wireName(), s.types(), s.values().apply(i));
            client.flush();
            var r = client.receive();
            return (int) (r.affected() >= 0 ? r.affected() : r.rows());
        }

        @Override
        public String planCounts() throws Exception {
            var row = client.simpleQuery("SELECT count(*), coalesce(sum(generic_plans), 0), coalesce(sum(custom_plans), 0) FROM pg_prepared_statements").getFirst();
            return "named " + row.get(0) + " generic " + row.get(1) + " custom " + row.get(2);
        }

        @Override
        public void close() throws Exception {
            client.close();
        }
    }

    private Caller open(SpikeServer server, Arm arm, String url, String host, int port) throws Exception {
        var user = server.role(READER);
        var pw   = server.password(READER);
        return switch (arm.kind) {
            case POOL -> new PoolCaller(url, user, pw);
            case OWNED -> new OwnedCaller(url, user, pw, arm.mode, statements);
            case WIRE -> new WireCaller(host, port, server.database(), user, pw, arm.mode, statements);
        };
    }

    // ------------------------------------------------------------------------------------------------------------------
    // Wakers: one wake = the tail reads of N types
    // ------------------------------------------------------------------------------------------------------------------

    interface Waker extends AutoCloseable {
        /** Rows read over all types. */
        int wake() throws Exception;

        /** CPU of the waker's own threads (not the caller's), ns. */
        long ownThreadsCpuNanos();

        @Override
        void close() throws Exception;
    }

    /** (i) One reader thread and owned connection per type; a wake releases them together and waits for all. */
    static final class PerTypeWaker implements Waker {
        final List<Thread>     threads     = new ArrayList<>();
        final List<Connection> connections = new ArrayList<>();
        final Phaser           phaser;
        final AtomicInteger    rows        = new AtomicInteger();
        volatile boolean       stop;
        volatile String        failure;

        PerTypeWaker(String url, String user, String password, int types) throws SQLException {
            phaser = new Phaser(types + 1);
            for (int k = 0; k < types; k++) {
                var s  = wakeStatement(k);
                var c  = owned(url, user, password, WAKE_MODE);
                connections.add(c);
                var ps = c.prepareStatement(s.tagged());
                bind(c, ps, 1, s, s.values().apply(0));
                run(ps);
                var t = Thread.ofPlatform().name("p016-type-" + k).daemon().unstarted(() -> {
                    while (true) {
                        phaser.arriveAndAwaitAdvance();
                        if (stop) {
                            phaser.arriveAndDeregister();
                            return;
                        }
                        try {
                            rows.addAndGet(run(ps));
                        } catch (Exception e) {
                            if (failure == null) failure = e.toString();
                        }
                        phaser.arriveAndAwaitAdvance();
                    }
                });
                threads.add(t);
            }
            threads.forEach(Thread::start);
        }

        @Override
        public int wake() {
            rows.set(0);
            phaser.arriveAndAwaitAdvance();
            phaser.arriveAndAwaitAdvance();
            if (failure != null) throw new IllegalStateException(failure);
            return rows.get();
        }

        @Override
        public long ownThreadsCpuNanos() {
            var mx = ManagementFactory.getThreadMXBean();
            return threads.stream().mapToLong(t -> Math.max(0, mx.getThreadCpuTime(t.threadId()))).sum();
        }

        @Override
        public void close() throws Exception {
            stop = true;
            phaser.arrive();
            for (var t : threads) t.join(10_000);
            for (var c : connections) c.close();
        }
    }

    /** (ii) pgjdbc: one owned connection, the N tail reads as one multi-statement PreparedStatement. */
    static final class FlushPgjdbcWaker implements Waker {
        final Connection        c;
        final PreparedStatement ps;
        final List<Stmt>        stmts;

        FlushPgjdbcWaker(String url, String user, String password, int types) throws SQLException {
            stmts = IntStream.range(0, types).mapToObj(OwnedReadConnectionSpikeIT::wakeStatement).toList();
            c = owned(url, user, password, WAKE_MODE);
            ps = c.prepareStatement(stmts.stream().map(Stmt::tagged).collect(Collectors.joining(";\n")));
            wake();
        }

        @Override
        public int wake() throws SQLException {
            int k = 1;
            for (var s : stmts) k = bind(c, ps, k, s, s.values().apply(0));
            boolean isResult = ps.execute();
            int     rows     = 0;
            while (true) {
                if (isResult) {
                    try (var rs = ps.getResultSet()) {
                        rows += readAll(rs);
                    }
                } else if (ps.getUpdateCount() == -1) {
                    return rows;
                }
                isResult = ps.getMoreResults();
            }
        }

        @Override
        public long ownThreadsCpuNanos() {
            return 0;
        }

        @Override
        public void close() throws SQLException {
            c.close();
        }
    }

    /** (ii) wire client: N Bind/Execute in one flush, one Sync per tail read or ({@code oneSync}) one after the last. */
    static final class FlushWireWaker implements Waker {
        final MinimalWireClient client;
        final List<Stmt>        stmts;
        final boolean           oneSync;

        FlushWireWaker(String host, int port, String database, String user, String password, int types, boolean oneSync) throws Exception {
            this.oneSync = oneSync;
            stmts = IntStream.range(0, types).mapToObj(OwnedReadConnectionSpikeIT::wakeStatement).toList();
            client = new MinimalWireClient(host, port, database, user, password, options(WAKE_MODE));
            var parse = new LinkedHashMap<String, Map.Entry<String, List<Type>>>();
            for (var s : stmts) parse.put(s.wireName(), Map.entry(s.numbered(), s.types()));
            client.prepareAll(parse);
        }

        @Override
        public int wake() throws Exception {
            if (oneSync) {
                for (var s : stmts) client.queue(s.wireName(), s.types(), s.values().apply(0), false);
                client.sync();
                client.flush();
                return client.receive().rows();
            }
            for (var s : stmts) client.queue(s.wireName(), s.types(), s.values().apply(0));
            client.flush();
            int rows = 0;
            for (int i = 0; i < stmts.size(); i++) rows += client.receive().rows();
            return rows;
        }

        @Override
        public long ownThreadsCpuNanos() {
            return 0;
        }

        @Override
        public void close() throws Exception {
            client.close();
        }
    }

    private Waker openWaker(SpikeServer server, WakeArm arm, int types, String url, String host, int port) throws Exception {
        var user = server.role(READER);
        var pw   = server.password(READER);
        return switch (arm) {
            case PER_TYPE -> new PerTypeWaker(url, user, pw, types);
            case FLUSH_PGJDBC -> new FlushPgjdbcWaker(url, user, pw, types);
            case FLUSH_WIRE -> new FlushWireWaker(host, port, server.database(), user, pw, types, false);
            case FLUSH_WIRE_1SYNC -> new FlushWireWaker(host, port, server.database(), user, pw, types, true);
        };
    }

    // ------------------------------------------------------------------------------------------------------------------
    // The test
    // ------------------------------------------------------------------------------------------------------------------

    private List<Stmt>                         statements = List.of();
    /** Per "label/arm" or "types/wakeArm": round trips per call or wake. */
    private final Map<String, Double>         roundTrips = new HashMap<>();
    /** Per "label/arm" or "types/wakeArm": every window's {server cpu µs, p50 µs, client cpu µs}. */
    private final Map<String, List<double[]>> windows    = new HashMap<>();
    /** Per label: generic plan index-only, and per mode index-only. */
    private final Map<String, Boolean>              genericIndexOnly = new HashMap<>();
    private final Map<String, Map<String, Boolean>> modeIndexOnly    = new HashMap<>();
    private final List<String>                      failures         = new ArrayList<>();

    @Test
    void owned_read_connections() throws Exception {
        requireTrue(SECONDS > 0 && WAKE_SECONDS > 0 && RT_CALLS > 0, "spike.owned.seconds, wakeSeconds and roundTripCalls must be > 0");
        requireTrue(ROWS >= 2000 && TYPE_ROWS > WAKE_NEW_ROWS, "spike.owned.rows must be >= 2000 and typeRows > wakeNewRows");
        requireTrue(TYPES.stream().allMatch(t -> t > 0), "spike.owned.types must all be > 0");
        var started = System.nanoTime();
        if (SpikeServer.external()) {
            try (var server = SpikeServer.external("owned", "spike", true)) {
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
        assertThat(failures).as("row-count checks and window failures").isEmpty();
    }

    private static SpikeServer container(boolean fsync) {
        var container = new PostgreSQLContainer(IMAGE).withDatabaseName("spike").withUsername("spike").withPassword("spike")
                                                      .withSharedMemorySize(1024L * 1024 * 1024)
                                                      .withCommand("postgres", "-c", fsync ? "fsync=on" : "fsync=off", "-c", "shared_buffers=256MB", "-c", "max_wal_size=4GB",
                                                                   "-c", "max_connections=200", "-c", "shared_preload_libraries=pg_stat_statements",
                                                                   "-c", "pg_stat_statements.max=10000", "-c", "pg_stat_statements.track_utility=on");
        container.start();
        return SpikeServer.of(container);
    }

    private void runPass(SpikeServer server, String pass) throws Exception {
        try (var observer = DriverManager.getConnection(server.getJdbcUrl(), server.getUsername(), server.getPassword())) {
            exec(observer, "CREATE EXTENSION IF NOT EXISTS pg_stat_statements");
            server.enableTrackPlanning(observer);
            server.createRole(observer, READER);
            var settings = new StringJoiner(", ");
            for (var name : List.of("server_version", "fsync", "synchronous_commit", "shared_buffers", "max_connections", "jit", "plan_cache_mode", "max_parallel_workers_per_gather")) {
                settings.add(name + "=" + queryString(observer, "SELECT current_setting('" + name + "', true)"));
            }
            var loadStarted = System.nanoTime();
            var loaded      = load(observer);
            record("");
            record("=== P0.16 owned read connections | " + Instant.now() + " | " + SpikeServer.target(IMAGE + " container, fsync=" + pass + " shared_buffers=256MB")
                           + " | settings " + settings + " | server CPU " + server.cpuSource() + " | client CPUs " + Runtime.getRuntime().availableProcessors() + " | pgjdbc "
                           + org.postgresql.util.DriverInfo.DRIVER_VERSION + " | event table " + ROWS + " rows (head " + loaded.head + ", jump " + loaded.jumpLo + ".." + loaded.jumpHi
                           + ", " + HOLE_POOL + " holes from head-" + NEAR_FROM + "), " + TYPES.stream().mapToInt(Integer::intValue).max().orElse(0) + " type tables of " + TYPE_ROWS
                           + " rows, " + SUBSCRIBERS + " subscribers, loaded in " + String.format("%.1f", (System.nanoTime() - loadStarted) / 1e9) + " s | statements " + STATEMENTS
                           + " | arms " + ARMS + " palindrome | types " + TYPES + ", wake arms " + WAKE_ARMS + " palindrome, plan_cache_mode " + WAKE_MODE + ", " + WAKE_NEW_ROWS
                           + " new rows per type | " + SECONDS + " s statement / " + WAKE_SECONDS + " s wake windows after " + WARMUP_MILLIS + " ms warm-up, one caller thread | "
                           + "owned options '" + options("<mode>") + "' | round trips over " + RT_CALLS + " calls through RoundTripCountingProxy");
            statements = STATEMENTS.stream().map(l -> statement(l, loaded.head, loaded.holes)).toList();

            for (var s : statements) plans(server, s);
            record("CSV,pass,statement,arm,window,calls,seconds,callsPerSec,roundTripsPerCall,cpuUsPerCall,sysUsPerCall,execUsPerCall,planUsPerCall,plansPerCall,"
                           + "clientCpuUsPerCall,p50us,p99us,mismatches,readerPlans");
            for (var s : statements) {
                countRoundTrips(server, s);
                var order = new ArrayList<>(ARMS);
                var back  = new ArrayList<>(ARMS);
                Collections.reverse(back);
                order.addAll(back);
                int n = 0;
                for (var arm : order) window(server, observer, pass, s, arm, ++n);
            }
            record("CSV,pass,types,wakeArm,window,wakes,seconds,wakesPerSec,roundTripsPerWake,cpuUsPerWake,sysUsPerWake,execUsPerWake,planUsPerWake,callsPerWake,"
                           + "clientCpuUsPerWake,p50us,p99us,mismatches");
            for (int types : TYPES) {
                countWakeRoundTrips(server, types);
                var order = new ArrayList<>(WAKE_ARMS);
                var back  = new ArrayList<>(WAKE_ARMS);
                Collections.reverse(back);
                order.addAll(back);
                int n = 0;
                for (var arm : order) wakeWindow(server, observer, pass, types, arm, ++n);
            }
            gates();
            try (var s = observer.createStatement()) {
                s.execute("DROP TABLE IF EXISTS " + EVENTS + ", " + SUBS + ", " + LEASES);
                for (int k = 0; k < TYPES.stream().mapToInt(Integer::intValue).max().orElse(0); k++) s.execute("DROP TABLE IF EXISTS " + typeTable(k));
            }
        }
    }

    // ------------------------------------------------------------------------------------------------------------------
    // Load
    // ------------------------------------------------------------------------------------------------------------------

    record Loaded(long head, long jumpLo, long jumpHi, List<Long> holes) {
    }

    private static Loaded load(Connection c) throws SQLException {
        int  aboveJump = Math.min(150_000, ROWS / 2);
        long j         = ROWS - aboveJump;
        var  columns   = "(global_order bigint GENERATED BY DEFAULT AS IDENTITY PRIMARY KEY, aggregate_id text NOT NULL, event_order bigint NOT NULL, "
                + "event_id text NOT NULL, caused_by_event_id text, correlation_id text, event_type text NOT NULL, event_revision text NOT NULL, "
                + "timestamp timestamptz NOT NULL, event_payload jsonb NOT NULL, event_metadata jsonb NOT NULL, tenant text)";
        var payload = "jsonb_build_object('orderId', 'Order-' || (g % 100000), 'productId', 'Product-' || (g % 977), 'quantity', g % 10, 'note', md5(g::text))";
        int maxTypes = TYPES.stream().mapToInt(Integer::intValue).max().orElse(0);
        try (var s = c.createStatement()) {
            s.execute("SET statement_timeout = 0");
            s.execute("DROP TABLE IF EXISTS " + EVENTS + ", " + SUBS + ", " + LEASES);
            // P0.11's BASE layout, with its jump: rows above j are shifted up by JUMP
            s.execute("CREATE TABLE " + EVENTS + " " + columns);
            s.execute("INSERT INTO " + EVENTS + " SELECT CASE WHEN g <= " + j + " THEN g ELSE g + " + JUMP + " END, 'Order-' || (g % 100000), g / 100000, "
                              + "gen_random_uuid()::text, NULL, gen_random_uuid()::text, '" + EVENT_TYPE + "', '1', now(), " + payload + ", '{\"traceId\": \"t\"}'::jsonb, NULL "
                              + "FROM generate_series(1, " + ROWS + ") g");
            s.execute("ALTER TABLE " + EVENTS + " ADD UNIQUE (aggregate_id, event_order), ADD UNIQUE (event_id)");
            for (int k = 0; k < maxTypes; k++) {
                s.execute("DROP TABLE IF EXISTS " + typeTable(k));
                s.execute("CREATE TABLE " + typeTable(k) + " " + columns);
                s.execute("INSERT INTO " + typeTable(k) + " SELECT g, 'Order-' || (g % 10000), g / 10000, gen_random_uuid()::text, NULL, gen_random_uuid()::text, '"
                                  + EVENT_TYPE + "', '1', now(), " + payload + ", '{\"traceId\": \"t\"}'::jsonb, NULL FROM generate_series(1, " + TYPE_ROWS + ") g");
                s.execute("SELECT setval(pg_get_serial_sequence('" + typeTable(k) + "', 'global_order'), " + TYPE_ROWS + ")");
            }
            // §8.2's table (P0.13/P0.14 shape) and §9's leases, with enough rows that a lookup is an index question
            s.execute("CREATE TABLE " + SUBS + " (subscriber_id text NOT NULL, aggregate_type text NOT NULL, resume_from_and_including_global_eventorder bigint, "
                              + "last_updated timestamptz, reposition_epoch bigint NOT NULL DEFAULT 0, PRIMARY KEY (subscriber_id, aggregate_type)) WITH (fillfactor = 50)");
            s.execute("CREATE TABLE " + LEASES + " (lease_name text PRIMARY KEY, owner text NOT NULL, fence bigint NOT NULL)");
            s.execute("INSERT INTO " + SUBS + " SELECT 'projection-' || k, '" + AGG_TYPE + "', 1, now(), " + EPOCH + " FROM generate_series(0, " + (SUBSCRIBERS - 1) + ") k");
            s.execute("INSERT INTO " + LEASES + " SELECT 'projection-' || k, '" + OWNER + "', " + FENCE + " FROM generate_series(0, " + (SUBSCRIBERS - 1) + ") k");
        }
        long head  = queryLong(c, "SELECT max(global_order) FROM " + EVENTS);
        var  holes = LongStream.range(0, HOLE_POOL).map(i -> head - NEAR_FROM - SPACING * i).boxed().toList();
        try (var ps = c.prepareStatement("DELETE FROM " + EVENTS + " WHERE global_order = ANY(?)")) {
            ps.setArray(1, c.createArrayOf("bigint", holes.toArray(Long[]::new)));
            assertThat(ps.executeUpdate()).isEqualTo(HOLE_POOL);
        }
        try (var s = c.createStatement()) {
            s.execute("SELECT setval(pg_get_serial_sequence('" + EVENTS + "', 'global_order'), " + head + ")");
            s.execute("VACUUM (ANALYZE)");
            s.execute("CHECKPOINT");
            s.execute("SET statement_timeout = '60s'");
        }
        return new Loaded(head, j + 1, j + JUMP, holes);
    }

    // ------------------------------------------------------------------------------------------------------------------
    // Plans
    // ------------------------------------------------------------------------------------------------------------------

    private static String literal(Type type, Object value) {
        return switch (type) {
            case INT8, INT4 -> value.toString();
            case TEXT -> "'" + value + "'";
            case INT8_ARRAY -> "'{" + Arrays.stream((long[]) value).mapToObj(Long::toString).collect(Collectors.joining(",")) + "}'::bigint[]";
        };
    }

    private static String sqlType(Type type) {
        return switch (type) {
            case INT8 -> "bigint";
            case INT4 -> "int";
            case TEXT -> "text";
            case INT8_ARRAY -> "bigint[]";
        };
    }

    private static List<String> lines(Statement st, String sql) throws SQLException {
        var out = new ArrayList<String>();
        try (var rs = st.executeQuery(sql)) {
            while (rs.next()) out.add(rs.getString(1));
        }
        return out;
    }

    /** No sequential scan anywhere in the plan (the running set's function scan is not one). */
    private static boolean indexOnly(List<String> plan) {
        return plan.stream().noneMatch(l -> l.contains("Seq Scan"));
    }

    /**
     * {@code EXPLAIN (GENERIC_PLAN)}, then per plan mode the plan a SQL-level prepared statement uses after six
     * executions, with the session settings of the owned connections.
     */
    private void plans(SpikeServer server, Stmt s) throws SQLException {
        // simple protocol: the $n placeholders reach the server as text, not as parameters of pgjdbc's own Parse
        var url = server.getJdbcUrl() + (server.getJdbcUrl().contains("?") ? "&" : "?") + "preferQueryMode=simple";
        try (var c = DriverManager.getConnection(url, server.role(READER), server.password(READER)); var st = c.createStatement()) {
            st.execute("SET jit = off");
            st.execute("SET max_parallel_workers_per_gather = 0");
            try {
                var generic = lines(st, "EXPLAIN (GENERIC_PLAN) " + s.numbered());
                genericIndexOnly.put(s.label(), indexOnly(generic));
                record(String.format("PLAN %-8s EXPLAIN (GENERIC_PLAN)      | index scans only: %-3s | %s", s.label(), indexOnly(generic) ? "yes" : "NO",
                                     TailHoleReReadSpikeIT.shape(generic)));
            } catch (SQLException e) {
                record("PLAN " + s.label() + " EXPLAIN (GENERIC_PLAN) not available: " + e.getSQLState() + " " + e.getMessage().lines().findFirst().orElse(""));
            }
            var types  = s.types().stream().map(OwnedReadConnectionSpikeIT::sqlType).collect(Collectors.joining(", "));
            var values = s.values().apply(1);
            var args   = IntStream.range(0, values.length).mapToObj(i -> literal(s.types().get(i), values[i])).collect(Collectors.joining(", "));
            for (var mode : MODES) {
                st.execute("SET plan_cache_mode = " + mode);
                st.execute("PREPARE p016_plan (" + types + ") AS " + s.numbered());
                try {
                    for (int i = 0; i < 6; i++) st.execute("EXECUTE p016_plan(" + args + ")");
                    var plan = lines(st, "EXPLAIN EXECUTE p016_plan(" + args + ")");
                    var counts = lines(st, "SELECT generic_plans || ' generic, ' || custom_plans || ' custom' FROM pg_prepared_statements WHERE name = 'p016_plan'");
                    modeIndexOnly.computeIfAbsent(s.label(), k -> new HashMap<>()).put(mode, indexOnly(plan));
                    record(String.format("PLAN %-8s %-18s after 6 | index scans only: %-3s | %s | %s", s.label(), mode, indexOnly(plan) ? "yes" : "NO", counts.getFirst(),
                                         TailHoleReReadSpikeIT.shape(plan)));
                } finally {
                    st.execute("DEALLOCATE p016_plan");
                }
            }
        }
    }

    // ------------------------------------------------------------------------------------------------------------------
    // Round trips
    // ------------------------------------------------------------------------------------------------------------------

    private void countRoundTrips(SpikeServer server, Stmt s) throws Exception {
        var j = new StringJoiner(" | ");
        for (var arm : ARMS) {
            try (var proxy = new RoundTripCountingProxy(server.host(), server.port());
                 var caller = open(server, arm, server.viaProxy(proxy.port()), "127.0.0.1", proxy.port())) {
                for (int i = 0; i < 20; i++) caller.call(s, i);
                proxy.reset();
                for (int i = 0; i < RT_CALLS; i++) caller.call(s, 100 + i);
                double rt = proxy.flights() / (double) RT_CALLS;
                roundTrips.put(s.label() + "/" + arm, rt);
                j.add(String.format("%s %.2f", arm, rt));
            }
        }
        record("ROUND TRIPS per call, " + s.label() + ": " + j);
    }

    private void countWakeRoundTrips(SpikeServer server, int types) throws Exception {
        var j     = new StringJoiner(" | ");
        int wakes = Math.max(10, Math.min(RT_CALLS, 100));
        for (var arm : WAKE_ARMS) {
            try (var proxy = new RoundTripCountingProxy(server.host(), server.port());
                 var waker = openWaker(server, arm, types, server.viaProxy(proxy.port()), "127.0.0.1", proxy.port())) {
                for (int i = 0; i < 20; i++) waker.wake();
                proxy.reset();
                for (int i = 0; i < wakes; i++) waker.wake();
                double rt = proxy.flights() / (double) wakes;
                roundTrips.put(types + "/" + arm, rt);
                j.add(String.format("%s %.2f", arm, rt));
            }
        }
        record("ROUND TRIPS per wake, " + types + " types: " + j + (types > 1 && WAKE_ARMS.contains(WakeArm.FLUSH_PGJDBC)
                                                                     ? " | FLUSH_PGJDBC above 1 = pgjdbc synced mid-flush (64 000-byte reply estimate per result of unknown width)" : ""));
    }

    // ------------------------------------------------------------------------------------------------------------------
    // Windows
    // ------------------------------------------------------------------------------------------------------------------

    private void window(SpikeServer server, Connection observer, String pass, Stmt s, Arm arm, int n) throws Exception {
        var mx = ManagementFactory.getThreadMXBean();
        try (var caller = open(server, arm, server.getJdbcUrl(), server.host(), server.port())) {
            long call     = 1000;
            long warmEnd  = System.nanoTime() + WARMUP_MILLIS * 1_000_000L;
            while (System.nanoTime() < warmEnd) caller.call(s, call++);
            exec(observer, "SELECT pg_stat_statements_reset()");
            var    latency    = new Samples();
            long   mismatches = 0;
            String first      = null;
            var    cpu0       = server.cpuUserSystemMicros();
            long   client0    = mx.getCurrentThreadCpuTime();
            long   start      = System.nanoTime(), end = start + SECONDS * 1_000_000_000L, calls = 0, t1 = start;
            while (t1 < end) {
                long t0 = System.nanoTime();
                int  r  = caller.call(s, call++);
                t1 = System.nanoTime();
                latency.add(t1 - t0);
                calls++;
                if (r != s.expected()) {
                    mismatches++;
                    if (first == null) first = "returned " + r + ", expected " + s.expected();
                }
            }
            long   client1  = mx.getCurrentThreadCpuTime();
            var    cpu1     = server.cpuUserSystemMicros();
            double seconds  = (t1 - start) / 1e9;
            var    recorded = recorded(server, observer);
            var    lat      = Samples.summary(List.of(latency));
            double cpu      = cpu0 == null || cpu1 == null ? Double.NaN : (cpu1[0] - cpu0[0]) / (double) calls;
            double sys      = cpu0 == null || cpu1 == null ? Double.NaN : (cpu1[2] - cpu0[2]) / (double) calls;
            double clientUs = (client1 - client0) / 1e3 / calls;
            double rt       = roundTrips.getOrDefault(s.label() + "/" + arm, Double.NaN);
            var    plans    = caller.planCounts();
            if (mismatches > 0) failures.add(s.label() + " " + arm + " window " + n + ": " + mismatches + " mismatches, first " + first);
            windows.computeIfAbsent(s.label() + "/" + arm, k -> new ArrayList<>()).add(new double[]{cpu, lat[0], clientUs});
            record(String.format("%-8s %-9s w%-2d | %7d calls in %.1f s = %8.1f/s | %.2f round trips/call | server CPU %7.1f µs/call (system %6.1f) | exec %6.1f plan %5.1f µs/call, "
                                         + "%.2f plans/call | client CPU %6.1f µs/call | p50 %6.0f µs p99 %6.0f µs | mismatches %d | reader %s",
                                 s.label(), arm, n, calls, seconds, calls / seconds, rt, cpu, sys, recorded[0] * 1000 / calls, recorded[1] * 1000 / calls, recorded[3] / calls,
                                 clientUs, lat[0], lat[1], mismatches, plans));
            record(String.format(Locale.ROOT, "CSV,%s,%s,%s,%d,%d,%.2f,%.1f,%.2f,%.1f,%.1f,%.1f,%.1f,%.3f,%.1f,%.0f,%.0f,%d,%s", pass, s.label(), arm, n, calls, seconds,
                                 calls / seconds, rt, cpu, sys, recorded[0] * 1000 / calls, recorded[1] * 1000 / calls, recorded[3] / calls, clientUs, lat[0], lat[1], mismatches,
                                 plans.replace(',', ';')));
        }
    }

    private void wakeWindow(SpikeServer server, Connection observer, String pass, int types, WakeArm arm, int n) throws Exception {
        var mx       = ManagementFactory.getThreadMXBean();
        int expected = types * WAKE_NEW_ROWS;
        try (var waker = openWaker(server, arm, types, server.getJdbcUrl(), server.host(), server.port())) {
            long warmEnd = System.nanoTime() + WARMUP_MILLIS * 1_000_000L;
            while (System.nanoTime() < warmEnd) waker.wake();
            exec(observer, "SELECT pg_stat_statements_reset()");
            var    latency    = new Samples();
            long   mismatches = 0;
            String first      = null;
            var    cpu0       = server.cpuUserSystemMicros();
            long   client0    = mx.getCurrentThreadCpuTime() + waker.ownThreadsCpuNanos();
            long   start      = System.nanoTime(), end = start + WAKE_SECONDS * 1_000_000_000L, wakes = 0, t1 = start;
            while (t1 < end) {
                long t0 = System.nanoTime();
                int  r  = waker.wake();
                t1 = System.nanoTime();
                latency.add(t1 - t0);
                wakes++;
                if (r != expected) {
                    mismatches++;
                    if (first == null) first = "read " + r + " rows, expected " + expected;
                }
            }
            long   client1  = mx.getCurrentThreadCpuTime() + waker.ownThreadsCpuNanos();
            var    cpu1     = server.cpuUserSystemMicros();
            double seconds  = (t1 - start) / 1e9;
            var    recorded = recorded(server, observer);
            var    lat      = Samples.summary(List.of(latency));
            double cpu      = cpu0 == null || cpu1 == null ? Double.NaN : (cpu1[0] - cpu0[0]) / (double) wakes;
            double sys      = cpu0 == null || cpu1 == null ? Double.NaN : (cpu1[2] - cpu0[2]) / (double) wakes;
            double clientUs = (client1 - client0) / 1e3 / wakes;
            double rt       = roundTrips.getOrDefault(types + "/" + arm, Double.NaN);
            if (mismatches > 0) failures.add(types + " types " + arm + " window " + n + ": " + mismatches + " mismatches, first " + first);
            windows.computeIfAbsent(types + "/" + arm, k -> new ArrayList<>()).add(new double[]{cpu, lat[0], clientUs});
            record(String.format("WAKE %2d types %-16s w%-2d | %6d wakes in %.1f s = %7.1f/s | %.2f round trips/wake | server CPU %8.1f µs/wake (system %7.1f) | exec %6.1f plan %5.1f "
                                         + "µs/wake, %.1f statements/wake | client CPU %7.1f µs/wake | p50 %6.0f µs p99 %6.0f µs | mismatches %d",
                                 types, arm, n, wakes, seconds, wakes / seconds, rt, cpu, sys, recorded[0] * 1000 / wakes, recorded[1] * 1000 / wakes, recorded[2] / wakes,
                                 clientUs, lat[0], lat[1], mismatches));
            record(String.format(Locale.ROOT, "CSV,%s,%d,%s,%d,%d,%.2f,%.1f,%.2f,%.1f,%.1f,%.1f,%.1f,%.2f,%.1f,%.0f,%.0f,%d", pass, types, arm, n, wakes, seconds, wakes / seconds, rt,
                                 cpu, sys, recorded[0] * 1000 / wakes, recorded[1] * 1000 / wakes, recorded[2] / wakes, clientUs, lat[0], lat[1], mismatches));
        }
    }

    /** {exec ms, plan ms, calls, plans} of the reader role since the last reset. */
    private static double[] recorded(SpikeServer server, Connection observer) throws SQLException {
        var thisDatabase = server.container != null ? "" : " AND s.dbid = (SELECT oid FROM pg_database WHERE datname = current_database())";
        try (var s = observer.createStatement();
             var rs = s.executeQuery("SELECT coalesce(sum(s.total_exec_time), 0), coalesce(sum(s.total_plan_time), 0), coalesce(sum(s.calls), 0), coalesce(sum(s.plans), 0) "
                                             + "FROM pg_stat_statements s JOIN pg_roles r ON r.oid = s.userid WHERE r.rolname = '" + server.role(READER) + "'"
                                             + " AND s.query LIKE '%/*p016 %'" + thisDatabase)) {
            rs.next();
            return new double[]{rs.getDouble(1), rs.getDouble(2), rs.getDouble(3), rs.getDouble(4)};
        }
    }

    // ------------------------------------------------------------------------------------------------------------------
    // Gates
    // ------------------------------------------------------------------------------------------------------------------

    /** {server cpu µs, p50 µs, client cpu µs}, means over the palindrome's windows; null when not run. */
    private double[] mean(String key) {
        var w = windows.get(key);
        if (w == null || w.isEmpty()) return null;
        var m = new double[3];
        for (int i = 0; i < 3; i++) {
            int col = i;
            m[i] = w.stream().mapToDouble(x -> x[col]).average().orElse(Double.NaN);
        }
        return m;
    }

    private void gates() {
        // Plan mode per statement
        var chosen = new HashMap<String, String>();
        for (var s : statements) {
            var    ok       = modeIndexOnly.getOrDefault(s.label(), Map.of());
            var    generic  = genericIndexOnly.get(s.label());
            var    indexed  = MODES.stream().filter(m -> Boolean.TRUE.equals(ok.get(m))).toList();
            String mode;
            String why;
            if (Boolean.FALSE.equals(generic) || Boolean.FALSE.equals(ok.get("force_generic_plan"))) {
                mode = "force_custom_plan";
                why = "pinned: the generic plan is not on the index";
            } else {
                mode = indexed.stream().filter(m -> ARMS.contains(Arm.of(Kind.OWNED, m)) && mean(s.label() + "/" + Arm.of(Kind.OWNED, m)) != null)
                              .min(Comparator.comparingDouble(m -> {
                                  var v = mean(s.label() + "/" + Arm.of(Kind.OWNED, m));
                                  return Double.isNaN(v[0]) ? v[1] : v[0];
                              })).orElse("auto");
                why = "every mode's plan on the index; the measured B mode with the least server CPU (p50 when CPU is not available)";
            }
            chosen.put(s.label(), mode);
            record(String.format("GATE plan %-8s | generic plan index-only: %s | index-only under %s | chosen %s (%s)", s.label(),
                                 generic == null ? "n/a" : generic ? "yes" : "NO", indexed, mode, why));
        }

        // B against A per tail read
        int passed = 0, evaluated = 0;
        for (var s : statements) {
            if (!s.tailRead()) continue;
            var a = mean(s.label() + "/" + Arm.A_POOL);
            var armB = Arm.of(Kind.OWNED, chosen.get(s.label()));
            var b    = mean(s.label() + "/" + armB);
            if (a == null || b == null) {
                record("GATE owned " + s.label() + ": not evaluated (needs A_POOL and " + armB + ")");
                continue;
            }
            evaluated++;
            double saved   = a[0] - b[0];
            double p50Gain = (a[1] - b[1]) / a[1];
            boolean pass   = (!Double.isNaN(saved) && saved >= GATE_OWNED_US) || p50Gain >= GATE_OWNED_P50;
            if (pass) passed++;
            record(String.format("GATE owned %-8s | A_POOL %.1f µs CPU, p50 %.0f µs | %s %.1f µs CPU, p50 %.0f µs | saves %.1f µs server CPU (needs >= %.0f) or %.1f %% of p50 (needs >= %.0f %%) | %s",
                                 s.label(), a[0], a[1], armB, b[0], b[1], saved, GATE_OWNED_US, p50Gain * 100, GATE_OWNED_P50 * 100, pass ? "PASSES" : "fails"));
        }
        record("GATE owned summary: passes on " + passed + " of " + evaluated + " tail reads | Phase 2's tail reader owns its connection with statements prepared at connect if B "
                       + "saves >= " + (int) GATE_OWNED_US + " µs server CPU or >= " + (int) (GATE_OWNED_P50 * 100) + " % of p50 per tail read");

        // C against B, same mode
        for (var s : statements) {
            for (var arm : ARMS) {
                if (arm.kind != Kind.WIRE) continue;
                var c = mean(s.label() + "/" + arm);
                var b = mean(s.label() + "/" + Arm.of(Kind.OWNED, arm.mode));
                if (c == null || b == null) {
                    record("GATE wire " + s.label() + " " + arm + ": not evaluated (needs " + Arm.of(Kind.OWNED, arm.mode) + ")");
                    continue;
                }
                double cut = 1 - c[0] / b[0];
                record(String.format("GATE wire %-8s %s vs %s | %.1f vs %.1f µs server CPU/call = %.1f %% less (needs >= %.0f %%) | p50 %.0f vs %.0f µs | client CPU %.1f vs %.1f µs | %s",
                                     s.label(), arm, Arm.of(Kind.OWNED, arm.mode), c[0], b[0], cut * 100, GATE_C_CUT * 100, c[1], b[1], c[2], b[2],
                                     Double.isNaN(cut) ? "no server CPU" : cut >= GATE_C_CUT ? "C COUNTS" : "C does not count"));
            }
        }

        // Several types in one flush
        for (int types : TYPES) {
            var per = mean(types + "/" + WakeArm.PER_TYPE);
            if (per == null) continue;
            WakeArm best = null;
            for (var arm : List.of(WakeArm.FLUSH_PGJDBC, WakeArm.FLUSH_WIRE, WakeArm.FLUSH_WIRE_1SYNC)) {
                var m = mean(types + "/" + arm);
                if (m != null && (best == null || m[0] < mean(types + "/" + best)[0] || Double.isNaN(mean(types + "/" + best)[0]) && m[1] < mean(types + "/" + best)[1])) best = arm;
            }
            if (best == null) {
                record("GATE wake " + types + " types: no one-flush arm ran");
                continue;
            }
            var j = new StringJoiner(" | ");
            for (var arm : WAKE_ARMS) {
                var m = mean(types + "/" + arm);
                if (m != null) j.add(String.format("%s %.1f µs CPU/wake, p50 %.0f µs, client %.1f µs, %.2f RT", arm, m[0], m[1], m[2], roundTrips.getOrDefault(types + "/" + arm, Double.NaN)));
            }
            var    b   = mean(types + "/" + best);
            double cut = 1 - b[0] / per[0];
            var verdict = types != GATE_WAKE_TYPES ? "(gate is at " + GATE_WAKE_TYPES + " types)" : Double.isNaN(cut) ? "no server CPU" : cut >= GATE_WAKE_CUT ? "PASSES" : "fails";
            record(String.format("GATE wake %d types | %s | best one-flush %s cuts server CPU/wake by %.1f %% (needs >= %.0f %%) | %s", types, j, best, cut * 100, GATE_WAKE_CUT * 100,
                                 verdict));
        }
    }

    private static void exec(Connection c, String sql) throws SQLException {
        try (var s = c.createStatement()) {
            s.execute(sql);
        }
    }

    private static void record(String line) {
        SpikePostgres.record(RESULTS, line);
    }
}
