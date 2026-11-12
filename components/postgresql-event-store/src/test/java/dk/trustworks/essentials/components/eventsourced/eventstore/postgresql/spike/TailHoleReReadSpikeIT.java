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

import dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.spike.SpikePostgres.Protocol;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.testcontainers.postgresql.PostgreSQLContainer;

import java.sql.*;
import java.util.*;
import java.util.stream.*;

import static dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.spike.SpikePostgres.*;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * Spike P0.11 of {@code docs/plans/event-store-subscription-redesign.md} (§19 risk 13): what it costs the tail statement
 * of §4.10 to also re-read the outstanding holes, as P0.5's finding F1 proposes for §4.2
 * ({@code WHERE global_order > :cursor OR global_order = ANY(:holes)}, with ranges after a sequence jump), and whether
 * the planner keeps both halves as index scans.
 * <p>
 * One event table per design (the engine's current layout, and the combined option of the append doc's §8) is
 * bulk-loaded with {@code spike.tail.rows} rows (default 2 million) with a 1-million-value sequence jump 150 000 rows
 * below the head, and 2 000 rows deleted to make holes: a pool just below the cursor ("near") and a pool 100 000 values
 * below it ("far", a long writer holding an old value while the cursor moved on). The cursor is placed 0, 1 or 100
 * rows below the head, so a probe reads that many new rows; nothing is written while measuring.
 * <p>
 * Variants of the combined probe (every one carries the running-set sub-select of §4.10):
 * <ul>
 *     <li>{@code A}: {@code global_order > ?} only (today's design, the baseline; it does not re-read holes)</li>
 *     <li>{@code B}: {@code OR global_order = ANY(?)}</li>
 *     <li>{@code C}: {@code OR global_order = ANY(?) OR global_order BETWEEN ? AND ?} (the jump as one range)</li>
 *     <li>{@code C_UNNEST}: {@code OR EXISTS (… unnest(?, ?) r(lo, hi) WHERE global_order BETWEEN lo AND hi)} (holes
 *     and the jump as an array of ranges)</li>
 *     <li>{@code D}: the tail read {@code UNION ALL} a hole read by {@code = ANY(?)}, one statement</li>
 *     <li>{@code D_RANGES}: {@code D} plus a {@code LATERAL} range branch over {@code unnest(?, ?)} for the jump</li>
 *     <li>{@code E}: two statements, the tail probe and then a hole read when holes are outstanding (round-trip
 *     comparison only: two snapshots, so it does not meet the model's constraint)</li>
 * </ul>
 * Plan shapes come from SQL-level {@code PREPARE} / {@code EXPLAIN (ANALYZE, BUFFERS) EXECUTE} under
 * {@code force_custom_plan}, {@code force_generic_plan} and {@code auto} (after five executions of the same parameters,
 * and after five executions with no holes); latency from pgjdbc on the extended protocol (unnamed statement = custom
 * plan; named statement under {@code auto}; named under {@code force_generic_plan}), server time and buffers from
 * {@code pg_stat_statements}. Results: {@code target/spike-results/p0-11-tail-holes.txt} and {@code p0-11-tail-plans.txt}.
 * <p>
 * Throwaway: runs only with {@code -Dspike.run=true}.
 */
@Tag("spike")
@EnabledIfSystemProperty(named = "spike.run", matches = "true")
class TailHoleReReadSpikeIT {
    private static final String RESULTS = "p0-11-tail-holes.txt";
    private static final String PLANS   = "p0-11-tail-plans.txt";

    private static final String       IMAGE       = System.getProperty("spike.tail.image", "postgres:17.5");
    private static final int          ROWS        = Integer.getInteger("spike.tail.rows", 2_000_000);
    private static final int          ITERATIONS  = Integer.getInteger("spike.tail.iterations", 200);
    private static final int          LIMIT       = Integer.getInteger("spike.tail.limit", 1000);
    private static final long         SLOW_MILLIS = Long.getLong("spike.tail.slowMillis", 50);
    private static final long         TIMEOUT_MS  = Long.getLong("spike.tail.timeoutMillis", 5000);
    private static final List<String> DESIGNS     = list("spike.tail.designs", "BASE,COMBO");
    private static final List<String> VARIANTS    = list("spike.tail.variants", "A,B,C,C_UNNEST,D,D_RANGES,E");
    private static final List<String> PROTOCOLS   = list("spike.tail.protocols", "EXTENDED_UNNAMED,PREPARED_AUTO,PREPARED_GENERIC");

    /** Rows above the jump; the jump is this far below the head. */
    private static final int  ABOVE_JUMP = 150_000;
    private static final long JUMP       = 1_000_000;
    private static final int  POOL       = 1000;
    private static final int  NEAR_FROM  = 200;
    private static final int  FAR_FROM   = 100_000;
    private static final int  SPACING    = 3;

    private static final int[]    HOLE_COUNTS = {0, 1, 10, 100, 1000};
    private static final int[]    NEW_ROWS    = {0, 1, 100};
    private static final String   RUNNING     = "(SELECT array_agg(backend_xid::text) FROM pg_stat_activity WHERE backend_xid IS NOT NULL)";
    private static final String   COLUMNS     = "e.global_order, e.aggregate_id, e.event_order, e.event_id, e.caused_by_event_id, e.correlation_id, e.event_type, "
            + "e.event_revision, e.timestamp, e.event_payload, e.event_metadata, e.tenant";
    private static final String   EVENT_TYPE  = "FQCN:dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.test_data.OrderEvent$ProductAddedToOrder";

    private static List<String> list(String property, String defaults) {
        return Arrays.stream(System.getProperty(property, defaults).split(",")).map(String::trim).filter(s -> !s.isEmpty()).toList();
    }

    // ------------------------------------------------------------------------------------------------------------------
    // Model: table, case, parameters, variants
    // ------------------------------------------------------------------------------------------------------------------

    /** A loaded event table: its head, the jump and the two hole pools (nearest to the cursor first). */
    record Table(String name, String design, long head, long jumpLo, long jumpHi, List<Long> near, List<Long> far) {
        long cursor(int newRows) {
            return head - newRows;
        }

        List<Long> holes(Case c) {
            if (c.holes == 0) return List.of();
            return (c.far ? far : near).subList(0, c.holes);
        }
    }

    record Case(int holes, boolean far, int newRows) {
        String position() {
            return holes == 0 ? "-" : far ? "far" : "near";
        }

        @Override
        public String toString() {
            return "holes=" + holes + " " + position() + " new=" + newRows;
        }
    }

    /** One typed parameter: {@code bigint}, {@code bigint[]} or {@code int}. */
    record Param(String type, Object value) {
        static Param bigint(long v) {
            return new Param("bigint", v);
        }

        static Param bigints(List<Long> v) {
            return new Param("bigint[]", v);
        }

        static Param integer(int v) {
            return new Param("int", v);
        }

        @SuppressWarnings("unchecked")
        void bind(PreparedStatement ps, int index) throws SQLException {
            switch (type) {
                case "bigint" -> ps.setLong(index, (Long) value);
                case "int" -> ps.setInt(index, (Integer) value);
                default -> ps.setArray(index, ps.getConnection().createArrayOf("bigint", ((List<Long>) value).toArray(Long[]::new)));
            }
        }

        @SuppressWarnings("unchecked")
        String literal() {
            if (type.equals("bigint[]")) {
                return "'{" + ((List<Long>) value).stream().map(String::valueOf).collect(Collectors.joining(",")) + "}'::bigint[]";
            }
            return String.valueOf(value);
        }
    }

    /** A statement of a variant: its SQL with {@code ?} placeholders and its parameters for a case. */
    record Stmt(String label, String sql, List<Param> params) {
        String prepareSql(String name) {
            var n = new int[]{0};
            var numbered = Pattern.QUESTION.matcher(sql).replaceAll(m -> "\\$" + (++n[0]));
            return "PREPARE " + name + "(" + params.stream().map(Param::type).collect(Collectors.joining(", ")) + ") AS " + numbered;
        }

        String executeSql(String name) {
            return "EXECUTE " + name + "(" + params.stream().map(Param::literal).collect(Collectors.joining(", ")) + ")";
        }
    }

    private static final class Pattern {
        static final java.util.regex.Pattern QUESTION = java.util.regex.Pattern.compile("\\?");
    }

    enum Variant {
        A, B, C, C_UNNEST, D, D_RANGES, E;

        /** The holes and the jump as [lo, hi] ranges, for the array-of-ranges forms. */
        private static List<List<Long>> ranges(List<Long> holes, Table t, boolean withHoles) {
            var lo = new ArrayList<Long>();
            var hi = new ArrayList<Long>();
            if (withHoles) {
                lo.addAll(holes);
                hi.addAll(holes);
            }
            lo.add(t.jumpLo);
            hi.add(t.jumpHi);
            return List.of(lo, hi);
        }

        /** The statements of one probe; all but E have one. {@code drift} = a history case: no holes, no jump. */
        List<Stmt> statements(Table t, long cursor, List<Long> holes, boolean drift) {
            var from    = " FROM " + t.name + " e ";
            var tail    = "SELECT " + COLUMNS + ", " + RUNNING + " AS running" + from;
            var order   = " ORDER BY e.global_order LIMIT ?";
            var c       = Param.bigint(cursor);
            var h       = Param.bigints(holes);
            var l       = Param.integer(LIMIT);
            var ranges  = drift ? List.<List<Long>>of(List.of(), List.of()) : ranges(holes, t, this == C_UNNEST);
            var lo      = Param.bigints(ranges.get(0));
            var hi      = Param.bigints(ranges.get(1));
            var jumpLo  = Param.bigint(drift ? 0 : t.jumpLo);
            var jumpHi  = Param.bigint(drift ? 0 : t.jumpHi);
            var holeSel = "(SELECT " + COLUMNS + from + "WHERE e.global_order = ANY(?))";
            var tailSel = "(SELECT " + COLUMNS + from + "WHERE e.global_order > ?" + order + ")";
            return switch (this) {
                case A -> List.of(new Stmt("A", tail + "WHERE e.global_order > ?" + order, List.of(c, l)));
                case B -> List.of(new Stmt("B", tail + "WHERE (e.global_order > ? OR e.global_order = ANY(?))" + order, List.of(c, h, l)));
                case C -> List.of(new Stmt("C", tail + "WHERE (e.global_order > ? OR e.global_order = ANY(?) OR e.global_order BETWEEN ? AND ?)" + order,
                                           List.of(c, h, jumpLo, jumpHi, l)));
                case C_UNNEST -> List.of(new Stmt("C_UNNEST", tail + "WHERE (e.global_order > ? OR EXISTS (SELECT 1 FROM unnest(?::bigint[], ?::bigint[]) AS r(lo, hi) "
                                                                + "WHERE e.global_order BETWEEN r.lo AND r.hi))" + order, List.of(c, lo, hi, l)));
                case D -> List.of(new Stmt("D", "SELECT u.*, " + RUNNING + " AS running FROM (" + tailSel + " UNION ALL " + holeSel + ") u ORDER BY u.global_order",
                                           List.of(c, l, h)));
                case D_RANGES -> List.of(new Stmt("D_RANGES", "SELECT u.*, " + RUNNING + " AS running FROM (" + tailSel + " UNION ALL " + holeSel
                        + " UNION ALL (SELECT x.* FROM unnest(?::bigint[], ?::bigint[]) AS r(lo, hi) CROSS JOIN LATERAL (SELECT " + COLUMNS + from
                        + "WHERE e.global_order BETWEEN r.lo AND r.hi) x)) u ORDER BY u.global_order", List.of(c, l, h, lo, hi)));
                case E -> {
                    var probe = new Stmt("E_TAIL", tail + "WHERE e.global_order > ?" + order, List.of(c, l));
                    yield holes.isEmpty() && !drift ? List.of(probe)
                                          : List.of(probe, new Stmt("E_HOLES", "SELECT " + COLUMNS + from + "WHERE e.global_order = ANY(?) ORDER BY e.global_order", List.of(h)));
                }
            };
        }

        /** E always prepares both statements, so a no-hole probe of E costs what A costs. */
        boolean readsHoles() {
            return this != A;
        }

        boolean readsJump() {
            return this == C || this == C_UNNEST || this == D_RANGES;
        }
    }

    // ------------------------------------------------------------------------------------------------------------------

    @Test
    void tail_statement_with_outstanding_holes() throws Exception {
        var started = System.nanoTime();
        try (var server = startServer();
             var observer = connect(server)) {
            var thisDb = server.container != null ? "" : " AND s.dbid = (SELECT oid FROM pg_database WHERE datname = current_database())";
            record(RESULTS, "=== P0.11 tail statement with outstanding holes | " + serverVersion(observer) + SpikeServer.target(" | container " + IMAGE
                    + " fsync=off shared_buffers=1GB pg_stat_statements.track_planning=on") + " | rows=" + ROWS + " limit=" + LIMIT + " iterations=" + ITERATIONS
                    + " slow>" + SLOW_MILLIS + "ms timeout=" + TIMEOUT_MS + "ms | designs=" + DESIGNS + " variants=" + VARIANTS + " protocols=" + PROTOCOLS);
            record(RESULTS, "settings: " + settings(observer));
            for (var design : DESIGNS) {
                var table = load(observer, design);
                try {
                    sanity(server, table);
                    plans(server, table);
                    timings(server, observer, table, thisDb);
                } finally {
                    try (var s = observer.createStatement()) {
                        s.execute("DROP TABLE IF EXISTS " + table.name);
                    }
                }
            }
            record(RESULTS, "=== done in %.1f s".formatted((System.nanoTime() - started) / 1e9));
        }
    }

    private static SpikeServer startServer() throws SQLException {
        if (SpikeServer.external()) {
            var server = SpikeServer.external("tail", "spike", true);
            try (var c = connect(server); var s = c.createStatement()) {
                s.execute("CREATE EXTENSION IF NOT EXISTS pg_stat_statements");
                server.enableTrackPlanning(c);
            }
            return server;
        }
        var container = new PostgreSQLContainer(IMAGE).withDatabaseName("spike").withUsername("spike").withPassword("spike")
                                                      .withSharedMemorySize(1024L * 1024 * 1024)
                                                      .withCommand("postgres", "-c", "fsync=off", "-c", "shared_buffers=1GB", "-c", "max_wal_size=4GB",
                                                                   "-c", "shared_preload_libraries=pg_stat_statements", "-c", "pg_stat_statements.track_planning=on");
        container.start();
        var server = SpikeServer.of(container);
        try (var c = connect(server); var s = c.createStatement()) {
            s.execute("CREATE EXTENSION IF NOT EXISTS pg_stat_statements");
        }
        return server;
    }

    private static String settings(Connection c) throws SQLException {
        try (var s = c.createStatement();
             var rs = s.executeQuery("SELECT string_agg(name || '=' || setting, ', ' ORDER BY name) FROM pg_settings WHERE name IN ('shared_buffers', 'work_mem', "
                                             + "'random_page_cost', 'effective_cache_size', 'jit', 'max_parallel_workers_per_gather', 'plan_cache_mode', 'fsync')")) {
            rs.next();
            return rs.getString(1);
        }
    }

    // ------------------------------------------------------------------------------------------------------------------
    // Load
    // ------------------------------------------------------------------------------------------------------------------

    private static Table load(Connection c, String design) throws SQLException {
        var name    = "spike_tail_" + design.toLowerCase(Locale.ROOT);
        var started = System.nanoTime();
        long j = ROWS - ABOVE_JUMP; // rows 1..j keep their number; the rest are shifted up by JUMP
        try (var s = c.createStatement()) {
            s.execute("SET statement_timeout = 0");
            s.execute("DROP TABLE IF EXISTS " + name);
            var go = "CASE WHEN g <= " + j + " THEN g ELSE g + " + JUMP + " END";
            var payload = "jsonb_build_object('orderId', 'Order-' || (g % 100000), 'productId', 'Product-' || (g % 977), 'quantity', g % 10, 'note', md5(g::text))";
            switch (design) {
                case "BASE" -> {
                    s.execute("CREATE TABLE " + name + " (global_order bigint GENERATED BY DEFAULT AS IDENTITY PRIMARY KEY, aggregate_id text NOT NULL, event_order bigint NOT NULL, "
                                      + "event_id text NOT NULL, caused_by_event_id text, correlation_id text, event_type text NOT NULL, event_revision text NOT NULL, "
                                      + "timestamp timestamptz NOT NULL, event_payload jsonb NOT NULL, event_metadata jsonb NOT NULL, tenant text)");
                    s.execute("INSERT INTO " + name + " SELECT " + go + ", 'Order-' || (g % 100000), g / 100000, gen_random_uuid()::text, NULL, gen_random_uuid()::text, '"
                                      + EVENT_TYPE + "', '1', now(), " + payload + ", '{\"traceId\": \"t\"}'::jsonb, NULL FROM generate_series(1, " + ROWS + ") g");
                    s.execute("ALTER TABLE " + name + " ADD UNIQUE (aggregate_id, event_order), ADD UNIQUE (event_id)");
                }
                case "COMBO" -> {
                    // append doc §8: fixed-width columns first, uuid ids, no UNIQUE(event_id), interned smallint type, json payload
                    s.execute("CREATE TABLE " + name + " (global_order bigint GENERATED BY DEFAULT AS IDENTITY PRIMARY KEY, event_order bigint NOT NULL, timestamp timestamptz NOT NULL, "
                                      + "event_id uuid NOT NULL, caused_by_event_id uuid, correlation_id uuid, event_type smallint NOT NULL, event_revision smallint NOT NULL, "
                                      + "aggregate_id text NOT NULL, event_payload json NOT NULL, event_metadata json NOT NULL, tenant text)");
                    s.execute("INSERT INTO " + name + " (global_order, event_order, timestamp, event_id, caused_by_event_id, correlation_id, event_type, event_revision, aggregate_id, "
                                      + "event_payload, event_metadata, tenant) SELECT " + go + ", g / 100000, now(), gen_random_uuid(), NULL, gen_random_uuid(), 1, 1, "
                                      + "'Order-' || (g % 100000), (" + payload + ")::json, '{\"traceId\": \"t\"}'::json, NULL FROM generate_series(1, " + ROWS + ") g");
                    s.execute("ALTER TABLE " + name + " ADD UNIQUE (aggregate_id, event_order)");
                }
                default -> throw new IllegalArgumentException("design " + design);
            }
        }
        var head   = queryLong(c, "SELECT max(global_order) FROM " + name);
        var jumpLo = j + 1;
        var jumpHi = j + JUMP;
        var near   = LongStream.range(0, POOL).map(i -> head - NEAR_FROM - SPACING * i).boxed().toList();
        var far    = LongStream.range(0, POOL).map(i -> head - FAR_FROM - SPACING * i).boxed().toList();
        assertThat(far.getLast()).isGreaterThan(jumpHi);
        try (var ps = c.prepareStatement("DELETE FROM " + name + " WHERE global_order = ANY(?)")) {
            var all = new ArrayList<>(near);
            all.addAll(far);
            ps.setArray(1, c.createArrayOf("bigint", all.toArray(Long[]::new)));
            assertThat(ps.executeUpdate()).isEqualTo(2 * POOL);
        }
        try (var s = c.createStatement()) {
            s.execute("SELECT setval(pg_get_serial_sequence('" + name + "', 'global_order'), " + head + ")");
            s.execute("VACUUM (ANALYZE) " + name);
            s.execute("CHECKPOINT");
            s.execute("SET statement_timeout = '60s'");
        }
        var table = new Table(name, design, head, jumpLo, jumpHi, near, far);
        record(RESULTS, "--- %s: %s loaded in %.1f s | rows=%d head=%d jump=[%d, %d] near holes %d..%d far holes %d..%d | table %s, total with indexes %s, pkey %s".formatted(
                design, name, (System.nanoTime() - started) / 1e9, queryLong(c, "SELECT count(*) FROM " + name), head, jumpLo, jumpHi, near.getFirst(), near.getLast(),
                far.getFirst(), far.getLast(), size(c, "pg_relation_size('" + name + "')"), size(c, "pg_total_relation_size('" + name + "')"),
                size(c, "pg_relation_size('" + name + "_pkey')")));
        return table;
    }

    private static String size(Connection c, String expression) throws SQLException {
        return queryString(c, "SELECT pg_size_pretty(" + expression + ")");
    }

    // ------------------------------------------------------------------------------------------------------------------
    // Sanity: every variant returns the late commits it is asked for, the new rows, and in global_order
    // ------------------------------------------------------------------------------------------------------------------

    private static void sanity(SpikeServer server, Table t) throws SQLException {
        var cursor = t.cursor(100);
        // two values that exist (late commits) and ten outstanding holes that do not
        var late  = List.of(t.head - 150, t.head - 160);
        var holes = new ArrayList<>(late);
        holes.addAll(t.near.subList(0, 10));
        var expectedTail = LongStream.rangeClosed(cursor + 1, t.head).boxed().toList();
        for (var variantName : VARIANTS) {
            var variant = Variant.valueOf(variantName);
            for (var protocol : Protocol.values()) {
                try (var c = connect(server, protocol)) {
                    setTimeout(c);
                    var stmts = variant.statements(t, cursor, holes, false);
                    List<Long> rows = List.of();
                    try {
                        for (var i = 0; i < (protocol.serverPrepared ? 6 : 1); i++) {
                            var start = System.nanoTime();
                            rows = runProbe(c, stmts, true);
                            if ((System.nanoTime() - start) / 1_000_000 > SLOW_MILLIS) break;
                        }
                    } catch (SQLException e) {
                        if (!"57014".equals(e.getSQLState())) throw e;
                        record(RESULTS, "sanity %s %s %s: timed out after %d ms, not checked".formatted(t.design, variant, protocol, TIMEOUT_MS));
                        continue;
                    }
                    var expected = new TreeSet<Long>(expectedTail);
                    if (variant.readsHoles()) expected.addAll(late);
                    var ordered = variant == Variant.E ? sortedCopy(rows) : rows;
                    record(RESULTS, "sanity %s %s %s: rows=%d first=%s ordered=%s".formatted(t.design, variant, protocol, rows.size(), rows.stream().limit(3).toList(),
                                                                                         rows.equals(sortedCopy(rows))));
                    assertThat(ordered).as(t.design + " " + variant + " " + protocol).containsExactlyElementsOf(expected);
                }
            }
        }
    }

    private static List<Long> sortedCopy(List<Long> rows) {
        return rows.stream().sorted().toList();
    }

    /** Runs a probe's statements, reading every column of every row; returns the global orders in arrival order. */
    private static List<Long> runProbe(Connection c, List<Stmt> stmts, boolean collect) throws SQLException {
        var rows = collect ? new ArrayList<Long>() : null;
        for (var stmt : stmts) {
            try (var ps = c.prepareStatement(stmt.sql)) {
                for (var i = 0; i < stmt.params.size(); i++) stmt.params.get(i).bind(ps, i + 1);
                try (var rs = ps.executeQuery()) {
                    var columns = rs.getMetaData().getColumnCount();
                    while (rs.next()) {
                        for (var col = 2; col <= columns; col++) rs.getString(col);
                        if (collect) rows.add(rs.getLong(1));
                    }
                }
            }
        }
        return rows;
    }

    private static void setTimeout(Connection c) throws SQLException {
        try (var s = c.createStatement()) {
            s.execute("SET statement_timeout = " + TIMEOUT_MS);
        }
    }

    // ------------------------------------------------------------------------------------------------------------------
    // Plan shapes
    // ------------------------------------------------------------------------------------------------------------------

    enum PlanMode {CUSTOM, GENERIC, AUTO, AUTO_DRIFT}

    private static List<Case> planCases() {
        var cases = new ArrayList<Case>();
        cases.add(new Case(0, false, 0));
        cases.add(new Case(0, false, 1));
        cases.add(new Case(0, false, 100));
        for (var far : new boolean[]{false, true}) {
            for (var h : HOLE_COUNTS) {
                if (h > 0) cases.add(new Case(h, far, 1));
            }
        }
        cases.add(new Case(1000, true, 0));
        cases.add(new Case(1000, true, 100));
        return cases;
    }

    private static void plans(SpikeServer server, Table t) throws SQLException {
        record(PLANS, "=== " + t.design + " (" + t.name + ")");
        var slow = new HashMap<String, Integer>();
        try (var c = connect(server, Protocol.SIMPLE)) {
            setTimeout(c);
            for (var cs : planCases()) {
                for (var variantName : VARIANTS) {
                    var variant = Variant.valueOf(variantName);
                    if (variant == Variant.A && cs.holes > 0) continue; // A ignores holes; its shape is the no-hole one
                    var stmts = variant.statements(t, t.cursor(cs.newRows), t.holes(cs), false);
                    var drift = variant.statements(t, t.cursor(1), List.of(), true);
                    for (var mode : PlanMode.values()) {
                        for (var si = 0; si < stmts.size(); si++) {
                            var stmt = stmts.get(si);
                            var key  = stmt.label + "/" + mode;
                            if (slow.getOrDefault(key, 0) >= 2) {
                                record(PLANS, "%s | %s | %s | %s | skipped: slow twice before".formatted(t.design, cs, stmt.label, mode));
                                record(RESULTS, "PLAN,%s,%s,%d,%s,%d,%s,skipped,,,,".formatted(t.design, stmt.label, cs.holes, cs.position(), cs.newRows, mode));
                                continue;
                            }
                            var plan = explain(c, stmt, mode, drift.get(Math.min(si, drift.size() - 1)));
                            slow.merge(key, plan.executionMs > SLOW_MILLIS || plan.timedOut ? 1 : -slow.getOrDefault(key, 0), Integer::sum);
                            record(PLANS, "%s | %s | %s | %s | %s | exec %.3f ms plan %.3f ms buffers %d | %s".formatted(t.design, cs, stmt.label, mode,
                                                                                                                       plan.generic ? "generic" : "custom", plan.executionMs,
                                                                                                                       plan.planningMs, plan.buffers, plan.shape));
                            record(RESULTS, "PLAN,%s,%s,%d,%s,%d,%s,%s,%.3f,%.3f,%d,%s".formatted(t.design, stmt.label, cs.holes, cs.position(), cs.newRows, mode,
                                                                                                 plan.timedOut ? "timeout" : plan.generic ? "generic" : "custom",
                                                                                                 plan.executionMs, plan.planningMs, plan.buffers, plan.shape));
                            var full = cs.newRows == 1 && (cs.holes == 0 || cs.holes == 10 || cs.holes == 1000) || cs.holes == 1000 && cs.newRows == 100;
                            if (full) {
                                for (var line : plan.text) record(PLANS, "    " + line);
                            }
                        }
                    }
                }
            }
        }
    }

    record Plan(boolean generic, boolean timedOut, double executionMs, double planningMs, long buffers, String shape, List<String> text) {
    }

    private static Plan explain(Connection c, Stmt stmt, PlanMode mode, Stmt drift) throws SQLException {
        try (var s = c.createStatement()) {
            s.execute("DEALLOCATE ALL");
            s.execute("SET plan_cache_mode = " + switch (mode) {
                case CUSTOM -> "force_custom_plan";
                case GENERIC -> "force_generic_plan";
                default -> "auto";
            });
            s.execute(stmt.prepareSql("p"));
            try {
                if (mode == PlanMode.AUTO || mode == PlanMode.AUTO_DRIFT) {
                    var history = mode == PlanMode.AUTO ? stmt : drift;
                    for (var i = 0; i < 5; i++) {
                        try (var rs = s.executeQuery(history.executeSql("p"))) {
                            while (rs.next()) ;
                        }
                    }
                }
                var genericBefore = queryLong(c, "SELECT generic_plans FROM pg_prepared_statements WHERE name = 'p'");
                var text          = new ArrayList<String>();
                try (var rs = s.executeQuery("EXPLAIN (ANALYZE, BUFFERS) " + stmt.executeSql("p"))) {
                    while (rs.next()) text.add(rs.getString(1));
                } catch (SQLException e) {
                    if ("57014".equals(e.getSQLState())) {
                        return new Plan(mode == PlanMode.GENERIC, true, TIMEOUT_MS, 0, 0, "TIMEOUT", List.of("timed out after " + TIMEOUT_MS + " ms"));
                    }
                    throw e;
                }
                var generic = queryLong(c, "SELECT generic_plans FROM pg_prepared_statements WHERE name = 'p'") > genericBefore;
                return new Plan(generic, false, timing(text, "Execution Time:"), timing(text, "Planning Time:"), buffers(text), shape(text), text);
            } finally {
                s.execute("DEALLOCATE ALL");
                s.execute("RESET plan_cache_mode");
            }
        }
    }

    private static double timing(List<String> text, String prefix) {
        return text.stream().map(String::trim).filter(l -> l.startsWith(prefix)).findFirst()
                   .map(l -> Double.parseDouble(l.substring(prefix.length()).replace("ms", "").trim())).orElse(-1.0);
    }

    /** Shared buffers (hit + read) of the top node: the first "Buffers:" line. */
    private static long buffers(List<String> text) {
        for (var line : text) {
            var l = line.trim();
            if (l.startsWith("Buffers:")) {
                long total = 0;
                var m = java.util.regex.Pattern.compile("shared (?:hit=(\\d+))?\\s*(?:read=(\\d+))?").matcher(l);
                if (m.find()) {
                    if (m.group(1) != null) total += Long.parseLong(m.group(1));
                    if (m.group(2) != null) total += Long.parseLong(m.group(2));
                }
                return total;
            }
        }
        return 0;
    }

    /**
     * The plan's nodes in pre-order as {@code depth:name}, without the running-set InitPlan's subtree (which is the same
     * in every variant), joined with " > ". Index names and filter conditions are left out; "Index Cond" is kept as a
     * marker of what the scan could use.
     */
    static String shape(List<String> text) {
        var nodes     = new ArrayList<String>();
        var skipBelow = -1;
        for (var line : text) {
            if (line.startsWith("Planning") || line.startsWith("Execution")) break;
            var indent = line.length() - line.stripLeading().length();
            var l      = line.trim();
            if (skipBelow >= 0) {
                if (indent > skipBelow) continue;
                skipBelow = -1;
            }
            if (l.startsWith("InitPlan") && l.contains("returns") || l.equals("InitPlan 1") || l.startsWith("InitPlan 1 ")) {
                skipBelow = indent;
                continue;
            }
            if (l.startsWith("SubPlan") || l.startsWith("InitPlan")) {
                nodes.add(l);
                continue;
            }
            var node = l.startsWith("-> ") ? l.substring(3).trim() : (nodes.isEmpty() ? l : null);
            if (node != null) {
                var cut = node.indexOf("  (");
                if (cut > 0) node = node.substring(0, cut);
                node = node.replaceAll(" on spike_tail_\\w+( e)?", "").replaceAll(" using spike_tail_\\w+", "");
                var actual = l.contains("never executed") ? " (never executed)" : "";
                nodes.add(node + actual);
                continue;
            }
            if (l.startsWith("Index Cond:") || l.startsWith("Recheck Cond:") || l.startsWith("Filter:") || l.startsWith("Rows Removed by Filter:")) {
                var tag = l.startsWith("Rows Removed") ? "removed=" + l.substring(l.indexOf(':') + 1).trim() : l.substring(0, l.indexOf(':'));
                nodes.set(nodes.size() - 1, nodes.getLast() + "[" + tag + "]");
            }
        }
        return String.join(" > ", nodes);
    }

    // ------------------------------------------------------------------------------------------------------------------
    // Timings
    // ------------------------------------------------------------------------------------------------------------------

    private static List<Case> timingCases() {
        var cases = new ArrayList<Case>();
        for (var newRows : NEW_ROWS) cases.add(new Case(0, false, newRows));
        for (var far : new boolean[]{false, true}) {
            for (var h : HOLE_COUNTS) {
                if (h == 0) continue;
                for (var newRows : NEW_ROWS) cases.add(new Case(h, far, newRows));
            }
        }
        return cases;
    }

    private static void timings(SpikeServer server, Connection observer, Table t, String thisDb) throws SQLException {
        record(RESULTS, "CSV,design,protocol,variant,holes,position,newRows,iterations,p50us,p99us,maxus,serverExecUsPerProbe,serverPlanUsPerProbe,statementsPerProbe,"
                + "buffersPerProbe,rowsPerProbe,clientRows,genericPlans,customPlans,status");
        var slow = new HashMap<String, Integer>();
        for (var cs : timingCases()) {
            for (var variantName : VARIANTS) {
                var variant = Variant.valueOf(variantName);
                for (var protocolName : PROTOCOLS) {
                    var protocol = Protocol.valueOf(protocolName);
                    var key      = variant + "/" + protocol;
                    var prefix   = "CSV,%s,%s,%s,%d,%s,%d".formatted(t.design, protocol, variant, cs.holes, cs.position(), cs.newRows);
                    if (slow.getOrDefault(key, 0) >= 2) {
                        record(RESULTS, prefix + ",0,,,,,,,,,,,,skipped-slow");
                        continue;
                    }
                    var stmts = variant.statements(t, t.cursor(cs.newRows), t.holes(cs), false);
                    try (var c = connect(server, protocol)) {
                        setTimeout(c);
                        var status = "ok";
                        // warm-up: six executions so a named statement is past its five custom plans; a slow one stops early
                        var first   = System.nanoTime();
                        var rows    = runProbe(c, stmts, true).size();
                        var firstMs = (System.nanoTime() - first) / 1e6;
                        var iterations = ITERATIONS;
                        if (firstMs > SLOW_MILLIS) {
                            iterations = 2;
                            status = "slow";
                        } else {
                            for (var i = 0; i < (protocol.serverPrepared ? 5 : 1); i++) runProbe(c, stmts, false);
                        }
                        try (var s = observer.createStatement()) {
                            s.execute("SELECT pg_stat_statements_reset()");
                        }
                        var latencies = new long[iterations];
                        for (var i = 0; i < iterations; i++) {
                            var start = System.nanoTime();
                            runProbe(c, stmts, false);
                            latencies[i] = System.nanoTime() - start;
                        }
                        Arrays.sort(latencies);
                        double exec, plan, calls, buffers, statRows;
                        try (var s = observer.createStatement();
                             var rs = s.executeQuery("SELECT coalesce(sum(s.calls), 0), coalesce(sum(s.total_exec_time), 0), coalesce(sum(s.total_plan_time), 0), "
                                                             + "coalesce(sum(s.shared_blks_hit + s.shared_blks_read), 0), coalesce(sum(s.rows), 0) FROM pg_stat_statements s "
                                                             + "WHERE s.query LIKE '%" + t.name + "%' AND s.query NOT LIKE '%pg_stat_statements%'" + thisDb)) {
                            rs.next();
                            calls = rs.getDouble(1);
                            exec = rs.getDouble(2);
                            plan = rs.getDouble(3);
                            buffers = rs.getDouble(4);
                            statRows = rs.getDouble(5);
                        }
                        var plans = protocol.serverPrepared ? planCounts(c, t) : new long[]{-1, -1};
                        if (status.equals("slow")) slow.merge(key, 1, Integer::sum);
                        else slow.put(key, 0);
                        record(RESULTS, prefix + ",%d,%.1f,%.1f,%.1f,%.1f,%.1f,%.2f,%.1f,%.1f,%d,%s,%s,%s".formatted(
                                iterations, percentile(latencies, 0.50) / 1e3, percentile(latencies, 0.99) / 1e3, latencies[iterations - 1] / 1e3,
                                exec * 1000 / iterations, plan * 1000 / iterations, calls / iterations, buffers / iterations, statRows / iterations, rows,
                                plans[0] < 0 ? "" : plans[0], plans[1] < 0 ? "" : plans[1], status));
                    } catch (SQLException e) {
                        if (!"57014".equals(e.getSQLState())) throw e;
                        slow.merge(key, 1, Integer::sum);
                        record(RESULTS, prefix + ",0,,,,,,,,,,,,timeout");
                    }
                }
            }
        }
    }

    private static long percentile(long[] sorted, double p) {
        return sorted[Math.min(sorted.length - 1, (int) Math.ceil(p * sorted.length) - 1)];
    }

    private static long[] planCounts(Connection c, Table t) throws SQLException {
        try (var s = c.createStatement();
             var rs = s.executeQuery("SELECT coalesce(sum(generic_plans), 0), coalesce(sum(custom_plans), 0) FROM pg_prepared_statements WHERE statement LIKE '%"
                                             + t.name + "%' AND statement NOT LIKE '%pg_prepared_statements%'")) {
            rs.next();
            return new long[]{rs.getLong(1), rs.getLong(2)};
        }
    }

    @Test
    void shape_drops_the_running_set_init_plan() {
        var text = List.of("Limit  (cost=1..2 rows=1 width=8) (actual time=0.1..0.2 rows=1 loops=1)",
                           "  Buffers: shared hit=7",
                           "  InitPlan 1",
                           "    ->  Aggregate  (cost=1..2)",
                           "          ->  Function Scan on pg_stat_get_activity s  (cost=1..2)",
                           "  ->  Index Scan using spike_tail_base_pkey on spike_tail_base e  (cost=1..2)",
                           "        Index Cond: (global_order > '5'::bigint)",
                           "Planning Time: 0.1 ms",
                           "Execution Time: 0.2 ms");
        assertThat(shape(text)).isEqualTo("Limit > Index Scan[Index Cond]");
        assertThat(buffers(text)).isEqualTo(7);
    }
}
