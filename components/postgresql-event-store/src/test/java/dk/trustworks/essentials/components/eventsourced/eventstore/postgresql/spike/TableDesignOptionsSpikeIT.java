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

import dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.spike.CurrentEngineBaselineSpikeIT.Pg;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

import java.nio.charset.StandardCharsets;
import java.sql.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Phase 0 of {@code docs/plans/event-store-subscription-redesign.md}, table design options for a new engine
 * ({@code docs/plans/event-store-phase0-append.md} §7). Every arm is its own event table created by this spike's DDL
 * (not the current strategy's), written through raw pgjdbc, so options the current layout cannot express can be
 * measured: payload type, event id type and uniqueness, a stream head table, a narrower row, and how the global order is
 * drawn. All arms send the per-event values as binary {@code varchar[]}/{@code bytea[]} arrays expanded with
 * {@code unnest}, and always with an expected order.
 * <p>
 * Shapes, per run on a fresh container: uncontended appends (new aggregate per unit of work, 1/10/100 events, rotated
 * arm order per round), a read shape (one aggregate's 100 events), and 8 writers on 10 aggregates for 20 s in palindrome
 * arm order. Correctness per arm: contiguous per-aggregate event orders after the contended window, a stale expected
 * order, and whether the transaction has an xid when the first row's global order is taken (a {@code BEFORE INSERT}
 * trigger reads {@code pg_current_xact_id_if_assigned()}; a control insert with no xid CTE must show none).
 * Results go to {@code target/spike-results/p0-7-table-design.txt}.
 * <p>
 * Throwaway: runs only with {@code -Dspike.run=true}.
 */
@Tag("spike")
@EnabledIfSystemProperty(named = "spike.run", matches = "true")
class TableDesignOptionsSpikeIT {
    private static final String RESULTS = "p0-7-table-design.txt";
    private static final int    RUNS    = Integer.getInteger("spike.design.runs", 2);
    private static final int    ROUNDS  = Integer.getInteger("spike.design.rounds", 2);
    private static final int    SECONDS = Integer.getInteger("spike.design.seconds", 20);
    private static final String EVENT_TYPE = "FQCN:dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.test_data.OrderEvent$ProductAddedToOrder";
    private static final AtomicInteger TABLES = new AtomicInteger();

    enum Head {NONE, FOR_UPDATE, GUARDED_UPDATE}

    /** One table design. {@code BASE} is the current layout's columns and indexes with {@code jsonb}. */
    enum Design {
        BASE("jsonb", "text", true, false, Head.NONE, false, false),
        JSON("json", "text", true, false, Head.NONE, false, false),
        BYTEA("bytea", "text", true, false, Head.NONE, false, false),
        NO_EVENT_ID_UNIQUE("jsonb", "text", false, false, Head.NONE, false, false),
        UUID_V4("jsonb", "uuid", true, false, Head.NONE, false, false),
        UUID_V7("jsonb", "uuid", true, true, Head.NONE, false, false),
        HEAD_FOR_UPDATE("jsonb", "text", true, false, Head.FOR_UPDATE, false, false),
        HEAD_GUARDED_UPDATE("jsonb", "text", true, false, Head.GUARDED_UPDATE, false, false),
        NARROW("jsonb", "text", true, false, Head.NONE, true, false),
        EXPLICIT_NEXTVAL("jsonb", "text", true, false, Head.NONE, false, true);

        final String  payloadType, eventIdType;
        final boolean eventIdUnique, uuidV7, narrow, explicitNextval;
        final Head    head;

        Design(String payloadType, String eventIdType, boolean eventIdUnique, boolean uuidV7, Head head, boolean narrow, boolean explicitNextval) {
            this.payloadType = payloadType;
            this.eventIdType = eventIdType;
            this.eventIdUnique = eventIdUnique;
            this.uuidV7 = uuidV7;
            this.head = head;
            this.narrow = narrow;
            this.explicitNextval = explicitNextval;
        }
    }

    private static final List<Design> DESIGNS = Arrays.stream(System.getProperty("spike.design.arms", String.join(",", Arrays.stream(Design.values()).map(Enum::name).toList()))
                                                              .split(",")).map(String::trim).map(Design::valueOf).toList();

    /** A created table of one design, with its SQL. */
    static final class Table {
        final Design d;
        final String t, s, seq;
        final String create, update, lockHead, lookup, read;

        Table(Pg pg, Design d) throws SQLException {
            this.d = d;
            int n = TABLES.incrementAndGet();
            this.t = "td_" + d.name().toLowerCase(Locale.ROOT) + "_" + n;
            this.s = t + "_streams";
            this.seq = t + "_seq";
            try (var st = pg.observer.createStatement()) {
                if (d.explicitNextval) st.execute("CREATE SEQUENCE " + seq);
                var go = d.explicitNextval ? "global_order bigint PRIMARY KEY" : "global_order bigint GENERATED BY DEFAULT AS IDENTITY PRIMARY KEY";
                if (d.narrow) {
                    // fixed-width columns first (8-byte, then 2-byte), then the varlena ones; metadata, causation, correlation and tenant in one jsonb
                    st.execute("CREATE TABLE " + t + " (" + go + ", event_order bigint NOT NULL, timestamp timestamptz NOT NULL, event_type smallint NOT NULL, event_revision smallint NOT NULL, "
                                       + "aggregate_id text NOT NULL, event_id text NOT NULL, event_payload jsonb NOT NULL, event_meta jsonb NOT NULL, "
                                       + "UNIQUE (aggregate_id, event_order), UNIQUE (event_id))");
                    st.execute("CREATE TABLE IF NOT EXISTS td_event_types (id smallint PRIMARY KEY, name text NOT NULL UNIQUE)");
                    st.execute("INSERT INTO td_event_types VALUES (1, '" + EVENT_TYPE + "') ON CONFLICT DO NOTHING");
                } else {
                    st.execute("CREATE TABLE " + t + " (" + go + ", aggregate_id text NOT NULL, event_order bigint NOT NULL, event_id " + d.eventIdType + " NOT NULL, "
                                       + "caused_by_event_id text, correlation_id text, event_type text NOT NULL, event_revision integer NOT NULL, timestamp timestamptz NOT NULL, "
                                       + "event_payload " + d.payloadType + " NOT NULL, event_metadata " + d.payloadType + " NOT NULL, tenant text, "
                                       + "UNIQUE (aggregate_id, event_order)" + (d.eventIdUnique ? ", UNIQUE (event_id)" : "") + ")");
                }
                if (d.head != Head.NONE) st.execute("CREATE TABLE " + s + " (aggregate_id text PRIMARY KEY, version bigint NOT NULL) WITH (fillfactor = 50)");
            }
            var pt   = d.payloadType;
            var cols = d.narrow ? "aggregate_id, event_order, event_id, event_type, event_revision, timestamp, event_payload, event_meta"
                                : "aggregate_id, event_order, event_id, caused_by_event_id, correlation_id, event_type, event_revision, timestamp, event_payload, event_metadata, tenant";
            if (d.explicitNextval) cols = "global_order, " + cols;
            var arrayT = d.payloadType.equals("bytea") ? "bytea[]" : "text[]";
            var unnest = d.narrow ? "unnest(?::text[], ?::int2[], ?::timestamptz[], ?::text[], ?::text[]) WITH ORDINALITY AS e(id, type, ts, payload, meta, n)"
                                  : "unnest(?::" + d.eventIdType + "[], ?::text[], ?::text[], ?::text[], ?::timestamptz[], ?::" + arrayT + ", ?::" + arrayT + ") WITH ORDINALITY AS e(id, caused_by, correlation, type, ts, payload, metadata, n)";
            var payloadExpr = pt.equals("bytea") ? "e.payload, e.metadata" : "e.payload::" + pt + ", e.metadata::" + pt;
            var values = (d.explicitNextval ? "nextval('" + seq + "'), " : "")
                    + (d.narrow ? "?::text, %1$s.v + e.n, e.id, e.type, 1, e.ts, e.payload::jsonb, e.meta::jsonb"
                                : "?::text, %1$s.v + e.n, e.id, e.caused_by, e.correlation, e.type, 1, e.ts, " + payloadExpr + ", NULL");
            var insert    = "INSERT INTO " + t + " (" + cols + ")\nSELECT " + values + "\nFROM %1$s CROSS JOIN " + unnest + "\nRETURNING global_order, event_order";
            var returning = insert;
            switch (d.head) {
                case NONE -> {
                    // §5.1's shape: xid first, head read from the events table, zero rows on a stale expected order
                    update = "WITH xid AS MATERIALIZED (SELECT pg_current_xact_id() AS x),\n"
                            + "     head AS MATERIALIZED (SELECT coalesce(max(event_order), -1) AS v FROM " + t + " WHERE aggregate_id = ?::text),\n"
                            + "     ok AS (SELECT head.v FROM head, xid WHERE head.v = ?::bigint)\n" + returning.formatted("ok");
                    create = update;
                    lockHead = null;
                    lookup = "SELECT event_order FROM " + t + " WHERE aggregate_id = ? ORDER BY event_order DESC LIMIT 1";
                }
                case GUARDED_UPDATE -> {
                    // one statement: the head row's guarded UPDATE is the check; a lost race re-checks the WHERE against the
                    // winner's version (EvalPlanQual) and returns no row, so nothing is inserted and no global order is taken
                    update = "WITH h AS (UPDATE " + s + " SET version = version + ?::bigint WHERE aggregate_id = ?::text AND version = ?::bigint RETURNING version - ?::bigint AS v)\n"
                            + returning.formatted("h");
                    create = "WITH h AS (INSERT INTO " + s + " (aggregate_id, version) VALUES (?::text, ?::bigint - 1) ON CONFLICT (aggregate_id) DO NOTHING RETURNING -1::bigint AS v)\n"
                            + returning.formatted("h");
                    lockHead = null;
                    lookup = "SELECT version FROM " + s + " WHERE aggregate_id = ?";
                }
                default -> { // FOR_UPDATE: lock and check the head row first, then insert and move the head in one statement
                    update = "WITH h AS (SELECT ?::bigint AS v), u AS (UPDATE " + s + " SET version = version + ?::bigint WHERE aggregate_id = ?::text)\n" + returning.formatted("h");
                    create = "WITH h AS (INSERT INTO " + s + " (aggregate_id, version) VALUES (?::text, ?::bigint - 1) ON CONFLICT (aggregate_id) DO NOTHING RETURNING -1::bigint AS v)\n"
                            + returning.formatted("h");
                    lockHead = "SELECT version FROM " + s + " WHERE aggregate_id = ? FOR UPDATE";
                    lookup = "SELECT version FROM " + s + " WHERE aggregate_id = ?";
                }
            }
            read = d.narrow ? "SELECT global_order, event_order, event_id, event_type, event_revision, timestamp, event_payload, event_meta FROM " + t + " WHERE aggregate_id = ? ORDER BY event_order"
                            : "SELECT global_order, event_order, event_id, caused_by_event_id, correlation_id, event_type, event_revision, timestamp, event_payload, event_metadata, tenant FROM "
                    + t + " WHERE aggregate_id = ? ORDER BY event_order";
        }

        /** Dropped after use so a long run does not fill the Docker VM's disk. */
        void drop(Pg pg) throws SQLException {
            try (var st = pg.observer.createStatement()) {
                st.execute("DROP TABLE IF EXISTS " + t + ", " + s + (d.explicitNextval ? "; DROP SEQUENCE " + seq : ""));
                st.execute("CHECKPOINT");
            }
        }

        long sequenceLastValue(Pg pg) throws SQLException {
            var name = d.explicitNextval ? seq : SpikePostgres.queryString(pg.observer, "SELECT pg_get_serial_sequence('" + t + "', 'global_order')");
            return SpikePostgres.queryLong(pg.observer, "SELECT last_value FROM " + name);
        }

        /**
         * Appends {@code n} events after {@code expected} in the connection's open transaction. Returns the rows written:
         * 0 = optimistic-concurrency conflict detected without an error. A unique violation is thrown.
         */
        int append(Connection c, String aggregateId, long expected, int n) throws SQLException {
            if (d.head == Head.FOR_UPDATE && expected >= 0) {
                try (var l = c.prepareStatement(lockHead)) {
                    l.setString(1, aggregateId);
                    try (var rs = l.executeQuery()) {
                        if (!rs.next() || rs.getLong(1) != expected) return 0;
                    }
                }
            }
            var sql = expected < 0 ? create : update;
            try (var ps = c.prepareStatement(sql)) {
                int p = 1;
                if (d.head == Head.NONE) {
                    ps.setString(p++, aggregateId);
                    ps.setLong(p++, expected);
                } else if (expected < 0) {
                    ps.setString(p++, aggregateId);
                    ps.setLong(p++, n);
                } else if (d.head == Head.GUARDED_UPDATE) {
                    ps.setLong(p++, n);
                    ps.setString(p++, aggregateId);
                    ps.setLong(p++, expected);
                    ps.setLong(p++, n);
                } else {
                    ps.setLong(p++, expected);
                    ps.setLong(p++, n);
                    ps.setString(p++, aggregateId);
                }
                ps.setString(p++, aggregateId);
                var ids = new String[n];
                var ts  = new String[n];
                var now = OffsetDateTime.now(ZoneOffset.UTC).toString();
                for (int i = 0; i < n; i++) {
                    ids[i] = (d.uuidV7 ? uuidV7() : UUID.randomUUID()).toString();
                    ts[i] = now;
                }
                var causedBy = UUID.randomUUID().toString();
                var corr     = UUID.randomUUID().toString();
                var payloads = new String[n];
                var meta     = new String[n];
                for (int i = 0; i < n; i++) {
                    payloads[i] = "{\"orderId\":\"" + aggregateId + "\",\"productId\":\"" + UUID.randomUUID() + "\",\"quantity\":" + (i + 1) + "}";
                    meta[i] = d.narrow ? "{\"Key1\":\"Value1\",\"Key2\":\"Value2\",\"causedBy\":\"" + causedBy + "\",\"correlation\":\"" + corr + "\"}"
                                       : "{\"Key1\":\"Value1\",\"Key2\":\"Value2\"}";
                }
                ps.setObject(p++, ids);
                if (d.narrow) {
                    var types = new String[n];
                    Arrays.fill(types, "1");
                    ps.setObject(p++, types);
                    ps.setObject(p++, ts);
                    ps.setObject(p++, payloads);
                    ps.setObject(p, meta);
                } else {
                    var cb = new String[n];
                    var co = new String[n];
                    var ty = new String[n];
                    Arrays.fill(cb, causedBy);
                    Arrays.fill(co, corr);
                    Arrays.fill(ty, EVENT_TYPE);
                    ps.setObject(p++, cb);
                    ps.setObject(p++, co);
                    ps.setObject(p++, ty);
                    ps.setObject(p++, ts);
                    if (d.payloadType.equals("bytea")) {
                        ps.setObject(p++, utf8(payloads));
                        ps.setObject(p, utf8(meta));
                    } else {
                        ps.setObject(p++, payloads);
                        ps.setObject(p, meta);
                    }
                }
                int rows = 0;
                try (var rs = ps.executeQuery()) {
                    while (rs.next()) {
                        if (rs.getLong(2) != expected + 1 + rows) throw new IllegalStateException("event order " + rs.getLong(2) + " after " + expected);
                        rows++;
                    }
                }
                if (rows != 0 && rows != n) throw new IllegalStateException(rows + " of " + n + " rows");
                return rows;
            }
        }

        long lookup(Connection c, String aggregateId) throws SQLException {
            try (var ps = c.prepareStatement(lookup)) {
                ps.setString(1, aggregateId);
                try (var rs = ps.executeQuery()) {
                    return rs.next() ? rs.getLong(1) : -1;
                }
            }
        }

        int readAggregate(Connection c, String aggregateId, Map<Integer, String> typeNames) throws SQLException {
            try (var ps = c.prepareStatement(read)) {
                ps.setString(1, aggregateId);
                int n = 0;
                long chars = 0;
                try (var rs = ps.executeQuery()) {
                    while (rs.next()) {
                        if (d.narrow) {
                            chars += typeNames.get(rs.getInt(4)).length() + rs.getString(7).length() + rs.getString(8).length();
                        } else if (d.payloadType.equals("bytea")) {
                            chars += rs.getString(6).length() + new String(rs.getBytes(9), StandardCharsets.UTF_8).length() + new String(rs.getBytes(10), StandardCharsets.UTF_8).length();
                        } else {
                            chars += rs.getString(6).length() + rs.getString(9).length() + rs.getString(10).length();
                        }
                        rs.getString(3);
                        rs.getObject(8 - (d.narrow ? 2 : 0), OffsetDateTime.class);
                        n++;
                    }
                }
                if (chars == 0 && n > 0) throw new IllegalStateException("nothing read");
                return n;
            }
        }
    }

    static UUID uuidV7() {
        var r  = ThreadLocalRandom.current();
        long ms = System.currentTimeMillis();
        return new UUID((ms << 16) | 0x7000L | (r.nextLong() & 0x0FFFL), (r.nextLong() & 0x3FFFFFFFFFFFFFFFL) | 0x8000000000000000L);
    }

    private static byte[][] utf8(String[] values) {
        var out = new byte[values.length][];
        for (int i = 0; i < values.length; i++) out[i] = values[i].getBytes(StandardCharsets.UTF_8);
        return out;
    }

    private static Connection writer(Pg pg) throws SQLException {
        var c = DriverManager.getConnection(pg.container.getJdbcUrl(), "writer", "writer");
        c.setAutoCommit(false);
        return c;
    }

    // ------------------------------------------------------------------ the test

    @Test
    void table_design_options() throws Exception {
        record("");
        record("=== TABLE DESIGN | " + Instant.now() + " | runs " + RUNS + ", uncontended rounds " + ROUNDS + ", contended " + SECONDS + " s, CPUs " + Runtime.getRuntime().availableProcessors()
                       + " | arms " + DESIGNS + " | postgres:17.5 fsync=off, client and database share one VM");
        for (int run = 1; run <= RUNS; run++) {
            try (var pg = Pg.start()) {
                record("RUN " + run + " | server " + SpikePostgres.serverVersion(pg.observer) + " | pgjdbc " + org.postgresql.Driver.getVersion());
                correctness(pg, run);
                for (int round = 0; round <= ROUNDS; round++) {
                    var order = new ArrayList<>(DESIGNS);
                    Collections.rotate(order, round + run);
                    for (var events : List.of(1, 10, 100)) {
                        for (var d : order) uncontended(pg, d, events, run, round, round == 0);
                    }
                }
                var palindrome = new ArrayList<>(DESIGNS);
                if (run % 2 == 0) Collections.reverse(palindrome);
                var reversed = new ArrayList<>(palindrome);
                Collections.reverse(reversed);
                palindrome.addAll(reversed);
                var warm = new Table(pg, Design.BASE);
                contended(pg, warm, Duration.ofSeconds(2), run, true);
                for (var d : palindrome) contended(pg, new Table(pg, d), Duration.ofSeconds(SECONDS), run, false);
            }
        }
    }

    /** Stale expected order, and whether an xid exists when the first row's global order is taken. */
    private void correctness(Pg pg, int run) throws SQLException {
        try (var st = pg.observer.createStatement()) {
            st.execute("""
                       CREATE OR REPLACE FUNCTION td_xid_probe() RETURNS trigger LANGUAGE plpgsql AS $$
                       BEGIN
                           IF current_setting('td.first', true) IS DISTINCT FROM 'done' THEN
                               PERFORM set_config('td.first', 'done', true);
                               PERFORM set_config('td.xid_at_first_row', coalesce(pg_current_xact_id_if_assigned()::text, 'none'), true);
                               PERFORM set_config('td.global_order_at_first_row', coalesce(NEW.global_order::text, 'null'), true);
                           END IF;
                           RETURN NEW;
                       END $$""");
        }
        for (var d : DESIGNS) {
            var t = new Table(pg, d);
            try (var c = writer(pg)) {
                assertThat(t.append(c, "agg", -1, 2)).isEqualTo(2);
                c.commit();
                try (var st = c.createStatement()) {
                    st.execute("CREATE TRIGGER td_probe BEFORE INSERT ON " + t.t + " FOR EACH ROW EXECUTE FUNCTION td_xid_probe()");
                }
                c.commit();
                long seqBefore = t.sequenceLastValue(pg);
                int stale = t.append(c, "agg", 0, 1);
                c.rollback();
                long seqAfterStale = t.sequenceLastValue(pg);
                int ok = t.append(c, "agg", 1, 3);
                String xid, go;
                try (var st = c.createStatement(); var rs = st.executeQuery("SELECT current_setting('td.xid_at_first_row', true), current_setting('td.global_order_at_first_row', true)")) {
                    rs.next();
                    xid = rs.getString(1);
                    go = rs.getString(2);
                }
                c.commit();
                // control: a plain INSERT ... VALUES with no xid CTE and no head row takes the global order before any xid
                String control;
                try (var st = c.createStatement()) {
                    st.execute(d.narrow ? "INSERT INTO " + t.t + " (" + (d.explicitNextval ? "global_order, " : "") + "aggregate_id, event_order, event_id, event_type, event_revision, timestamp, event_payload, event_meta) VALUES ("
                                       + "'ctl', 0, 'ctl', 1, 1, now(), '{}', '{}')"
                                        : "INSERT INTO " + t.t + " (" + (d.explicitNextval ? "global_order, " : "") + "aggregate_id, event_order, event_id, event_type, event_revision, timestamp, event_payload, event_metadata) VALUES ("
                                       + (d.explicitNextval ? "nextval('" + t.seq + "'), " : "") + "'ctl', 0, '" + UUID.randomUUID() + "', 'x', 1, now(), '{}', '{}')");
                    try (var rs = st.executeQuery("SELECT current_setting('td.xid_at_first_row', true)")) {
                        rs.next();
                        control = rs.getString(1);
                    }
                }
                c.commit();
                record(String.format("CORRECTNESS run %d | %-20s | stale expected order: rows %d, global order values taken %d | append after it: rows %d | xid when first row's global order (%s) was taken: %s | control plain INSERT: %s",
                                     run, d, stale, seqAfterStale - seqBefore, ok, go, xid, control));
            }
        }
    }

    private void uncontended(Pg pg, Design d, int events, int run, int round, boolean discard) throws SQLException {
        var t = new Table(pg, d);
        int iterations = discard ? 100 : events == 100 ? 300 : 1000;
        var ids = new ArrayList<String>();
        try (var c = writer(pg)) {
            for (int i = 0; i < 50; i++) {
                t.append(c, UUID.randomUUID().toString(), -1, events);
                c.commit();
            }
            pg.resetStatistics();
            var lat = new long[iterations];
            for (int i = 0; i < iterations; i++) {
                var id = UUID.randomUUID().toString();
                long t0 = System.nanoTime();
                if (t.append(c, id, -1, events) != events) throw new IllegalStateException("conflict on a new aggregate");
                c.commit();
                lat[i] = System.nanoTime() - t0;
                ids.add(id);
            }
            if (discard) return;
            long wal   = SpikePostgres.queryLong(pg.observer, "SELECT pg_wal_lsn_diff(pg_current_wal_lsn(), '" + pg.lsnAtReset + "'::pg_lsn)::bigint");
            double exec = execMillis(pg);
            long rows  = SpikePostgres.queryLong(pg.observer, "SELECT count(*) FROM " + t.t);
            long bytes = SpikePostgres.queryLong(pg.observer, "SELECT pg_total_relation_size('" + t.t + "')" + (d.head != Head.NONE ? " + pg_total_relation_size('" + t.s + "')" : ""));
            long heap  = SpikePostgres.queryLong(pg.observer, "SELECT pg_relation_size('" + t.t + "')");
            Arrays.sort(lat);
            var line = String.format("UNCONTENDED run %d round %d | %-20s | %3d events | p50 %.3f ms p99 %.3f ms | server exec %.1f µs/UoW | WAL %.0f B/event | table+indexes %.0f B/row (heap %.0f B/row)",
                                     run, round, d, events, lat[iterations / 2] / 1e6, lat[iterations * 99 / 100] / 1e6, exec * 1000 / iterations,
                                     wal / (double) (iterations * events), bytes / (double) rows, heap / (double) rows);
            if (events == 100) {
                var typeNames = Map.of(1, EVENT_TYPE);
                var r = ThreadLocalRandom.current();
                for (int i = 0; i < 200; i++) t.readAggregate(c, ids.get(r.nextInt(ids.size())), typeNames);
                c.commit();
                pg.resetStatistics();
                var rl = new long[2000];
                for (int i = 0; i < rl.length; i++) {
                    long t0 = System.nanoTime();
                    if (t.readAggregate(c, ids.get(r.nextInt(ids.size())), typeNames) != 100) throw new IllegalStateException("short read");
                    rl[i] = System.nanoTime() - t0;
                }
                c.commit();
                double readExec = execMillis(pg);
                Arrays.sort(rl);
                line += String.format(" | read 100 events: p50 %.3f ms p99 %.3f ms, server exec %.1f µs/read", rl[1000] / 1e6, rl[1980] / 1e6, readExec * 1000 / rl.length);
            }
            record(line);
        } finally {
            t.drop(pg);
        }
    }

    private static double execMillis(Pg pg) throws SQLException {
        return SpikePostgres.queryLong(pg.observer, "SELECT coalesce(round(sum(s.total_exec_time) * 1000), 0)::bigint FROM pg_stat_statements s JOIN pg_roles r ON r.oid = s.userid "
                + "WHERE r.rolname = 'writer' AND s.query !~* '^\\s*(begin|commit|rollback|set |show )'") / 1000.0;
    }

    private void contended(Pg pg, Table t, Duration duration, int run, boolean discard) throws Exception {
        var aggregates = new ArrayList<String>();
        try (var c = writer(pg)) {
            for (int i = 0; i < 10; i++) {
                var id = UUID.randomUUID().toString();
                t.append(c, id, -1, 1);
                c.commit();
                aggregates.add(id);
            }
        }
        long seq0 = t.sequenceLastValue(pg);
        long rows0 = SpikePostgres.queryLong(pg.observer, "SELECT count(*) FROM " + t.t);
        pg.resetStatistics();
        try (var st = pg.observer.createStatement()) {
            st.execute("SELECT pg_stat_reset()");
        }
        long cpu0 = ContendedAppendThroughputSpikeIT.containerCpuMicros(pg);
        var attempts = new AtomicLong();
        var zero = new AtomicLong();
        var unique = new AtomicLong();
        var other = new AtomicLong();
        var firstOther = new AtomicReference<Throwable>();
        long stopAt = System.nanoTime() + duration.toNanos();
        var pool = Executors.newFixedThreadPool(8);
        var futures = new ArrayList<Future<?>>();
        for (int w = 0; w < 8; w++) {
            futures.add(pool.submit(() -> {
                try (var c = writer(pg)) {
                    var r = ThreadLocalRandom.current();
                    while (System.nanoTime() < stopAt) {
                        var id = aggregates.get(r.nextInt(aggregates.size()));
                        attempts.incrementAndGet();
                        try {
                            long last = t.lookup(c, id);
                            if (t.append(c, id, last, 1) == 0) {
                                zero.incrementAndGet();
                                c.rollback();
                            } else {
                                c.commit();
                            }
                        } catch (SQLException e) {
                            c.rollback();
                            if ("23505".equals(e.getSQLState())) unique.incrementAndGet();
                            else if (other.incrementAndGet() == 1) firstOther.set(e);
                        }
                    }
                }
                return null;
            }));
        }
        pool.shutdown();
        for (var f : futures) f.get(duration.toSeconds() + 60, TimeUnit.SECONDS);
        if (discard) {
            t.drop(pg);
            return;
        }
        long cpu = ContendedAppendThroughputSpikeIT.containerCpuMicros(pg) - cpu0;
        long appended = SpikePostgres.queryLong(pg.observer, "SELECT count(*) FROM " + t.t) - rows0;
        long burned = t.sequenceLastValue(pg) - seq0 - appended;
        long wal = SpikePostgres.queryLong(pg.observer, "SELECT pg_wal_lsn_diff(pg_current_wal_lsn(), '" + pg.lsnAtReset + "'::pg_lsn)::bigint");
        double exec = execMillis(pg);
        long broken = SpikePostgres.queryLong(pg.observer, "SELECT count(*) FROM (SELECT aggregate_id FROM " + t.t + " GROUP BY aggregate_id HAVING min(event_order) <> 0 OR max(event_order) <> count(*) - 1) x");
        var headInfo = "";
        if (t.d.head != Head.NONE) {
            Thread.sleep(1500); // let the writer backends' table statistics reach the cumulative stats
            long headMismatch = SpikePostgres.queryLong(pg.observer, "SELECT count(*) FROM " + t.s + " s WHERE s.version <> (SELECT max(event_order) FROM " + t.t + " e WHERE e.aggregate_id = s.aggregate_id)");
            String hot = SpikePostgres.queryString(pg.observer, "SELECT n_tup_upd || ' updates, ' || n_tup_hot_upd || ' HOT, ' || pg_relation_size('" + t.s + "') || ' B heap' FROM pg_stat_user_tables WHERE relname = '" + t.s + "'");
            headInfo = " | head rows != max(event_order): " + headMismatch + " | head table " + hot;
        }
        double secs = duration.toMillis() / 1000.0;
        long a = attempts.get();
        record(String.format("CONTENDED run %d | %-20s | attempts %d (%.0f/s), appended %d (%.0f/s) | conflicts: zero-row %d, unique-violation %d, other %d | burned %d = %.1f per 1 000 appended | "
                                     + "PostgreSQL CPU %.1f µs/attempt, recorded exec %.1f µs/attempt | WAL %.0f B/appended | aggregates with non-contiguous orders %d%s",
                             run, t.d, a, a / secs, appended, appended / secs, zero.get(), unique.get(), other.get(), burned, burned * 1000.0 / Math.max(1, appended),
                             cpu / (double) a, exec * 1000 / a, wal / (double) Math.max(1, appended), broken, headInfo));
        if (firstOther.get() != null) record("  first other error: " + firstOther.get());
        t.drop(pg);
    }

    private static void record(String line) {
        SpikePostgres.record(RESULTS, line);
    }
}
