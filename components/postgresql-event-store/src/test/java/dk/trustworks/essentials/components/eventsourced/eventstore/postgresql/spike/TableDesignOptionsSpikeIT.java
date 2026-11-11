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
import dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.spike.CurrentEngineBaselineSpikeIT.Pg;
import dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.spike.CurrentEngineBaselineSpikeIT.Stack;
import dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.test_data.*;
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
 * §8 additions: combination arms ({@code COMBO_ALL*}: guarded-UPDATE head or §5.1's xid/head CTE, no {@code UNIQUE(event_id)},
 * {@code uuid} ids, interned {@code smallint} type, {@code json}/{@code bytea} payload, fixed-width columns first; the
 * {@code _SCALAR} arm binds scalar parameters for one-event appends), an uncontended shape that appends to existing
 * streams, and contended-only {@code BASE_*} variants that each add one difference to §6's B arm on the engine's table
 * (text arrays, the engine's row, the tenant index, a longer gap between lookup and append) to explain the burned-value
 * ratio.
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
        EXPLICIT_NEXTVAL("jsonb", "text", true, false, Head.NONE, false, true),
        // §8 combinations: guarded-UPDATE head (or §5.1's xid/head CTE), no UNIQUE(event_id), uuid ids, interned smallint type, json/bytea payload, fixed-width columns first
        COMBO_ALL(Combo.HEAD, "json", false),
        COMBO_ALL_BYTEA(Combo.HEAD, "bytea", false),
        COMBO_ALL_NO_HEAD(Combo.NO_HEAD, "json", false),
        COMBO_ALL_SCALAR(Combo.HEAD, "json", true),
        // §8.4 diagnostics: BASE's table and statement, one set-up difference to the engine's §6 B arm at a time (contended shape only)
        BASE_TEXT_ARRAYS(Variant.TEXT_ARRAYS),
        BASE_ENGINE_PAYLOAD(Variant.ENGINE_PAYLOAD),
        BASE_TENANT_INDEX(Variant.TENANT_INDEX),
        BASE_LOOKUP_DELAY(Variant.LOOKUP_DELAY),
        BASE_LOOKUP_SLEEP(Variant.LOOKUP_SLEEP),
        BASE_ENGINE_LIKE(Variant.TEXT_ARRAYS, Variant.ENGINE_PAYLOAD, Variant.TENANT_INDEX, Variant.LOOKUP_DELAY);

        final String       payloadType, eventIdType;
        final boolean      eventIdUnique, uuidV7, narrow, explicitNextval;
        final Head         head;
        final Combo        combo;
        /** COMBO_ALL_SCALAR: a one-event append binds scalar parameters (no {@code unnest}), as §6's {@code SCALAR_1}. */
        final boolean      scalar1;
        final Set<Variant> variants;

        Design(String payloadType, String eventIdType, boolean eventIdUnique, boolean uuidV7, Head head, boolean narrow, boolean explicitNextval) {
            this.payloadType = payloadType;
            this.eventIdType = eventIdType;
            this.eventIdUnique = eventIdUnique;
            this.uuidV7 = uuidV7;
            this.head = head;
            this.narrow = narrow;
            this.explicitNextval = explicitNextval;
            this.combo = Combo.NONE;
            this.scalar1 = false;
            this.variants = Set.of();
        }

        Design(Combo combo, String payloadType, boolean scalar1) {
            this.payloadType = payloadType;
            this.eventIdType = "uuid";
            this.eventIdUnique = false;
            this.uuidV7 = false;
            this.head = combo == Combo.HEAD ? Head.GUARDED_UPDATE : Head.NONE;
            this.narrow = false;
            this.explicitNextval = false;
            this.combo = combo;
            this.scalar1 = scalar1;
            this.variants = Set.of();
        }

        Design(Variant... variants) {
            this.payloadType = "jsonb";
            this.eventIdType = "text";
            this.eventIdUnique = true;
            this.uuidV7 = false;
            this.head = Head.NONE;
            this.narrow = false;
            this.explicitNextval = false;
            this.combo = Combo.NONE;
            this.scalar1 = false;
            this.variants = Set.of(variants);
        }

        boolean has(Variant v) {
            return variants.contains(v);
        }

        /** The diagnostic variants run in the contended shape only. */
        boolean contendedOnly() {
            return !variants.isEmpty();
        }
    }

    enum Combo {NONE, HEAD, NO_HEAD}

    /** A difference between this spike's BASE set-up and §6's B arm on the engine's table. */
    enum Variant {TEXT_ARRAYS, ENGINE_PAYLOAD, TENANT_INDEX, LOOKUP_DELAY, LOOKUP_SLEEP}

    /** Busy-wait (LOOKUP_DELAY) or park (LOOKUP_SLEEP) after the lookup, standing in for the engine lookup's extra client time. */
    private static final long LOOKUP_DELAY_NANOS = Long.getLong("spike.design.lookupDelayMicros", 50) * 1000;

    private static final List<Design> DESIGNS = Arrays.stream(System.getProperty("spike.design.arms", String.join(",", Arrays.stream(Design.values()).map(Enum::name).toList()))
                                                              .split(",")).map(String::trim).map(Design::valueOf).toList();

    /** A created table of one design, with its SQL. */
    static final class Table {
        final Design d;
        final String t, s, seq;
        final String create, update, lockHead, lookup, read;
        /** Combination arms only: the correctness control insert, and COMBO_ALL_SCALAR's one-event statements. */
        final String control, scalarCreate, scalarUpdate;

        Table(Pg pg, Design d) throws SQLException {
            this.d = d;
            int n = TABLES.incrementAndGet();
            this.t = "td_" + d.name().toLowerCase(Locale.ROOT) + "_" + n;
            this.s = t + "_streams";
            this.seq = t + "_seq";
            if (d.combo != Combo.NONE) {
                var pt = d.payloadType;
                try (var st = pg.observer.createStatement()) {
                    // alignment order: 8-byte columns, then the 16-byte uuids (1-byte aligned), the 2-byte smallints, then the varlena columns
                    st.execute("CREATE TABLE " + t + " (global_order bigint GENERATED BY DEFAULT AS IDENTITY PRIMARY KEY, event_order bigint NOT NULL, timestamp timestamptz NOT NULL, "
                                       + "event_id uuid NOT NULL, caused_by_event_id uuid, correlation_id uuid, event_type smallint NOT NULL, event_revision smallint NOT NULL, "
                                       + "aggregate_id text NOT NULL, event_payload " + pt + " NOT NULL, event_metadata " + pt + " NOT NULL, tenant text, "
                                       + "UNIQUE (aggregate_id, event_order))");
                    st.execute("CREATE TABLE IF NOT EXISTS td_event_types (id smallint PRIMARY KEY, name text NOT NULL UNIQUE)");
                    st.execute("INSERT INTO td_event_types VALUES (1, '" + EVENT_TYPE + "') ON CONFLICT DO NOTHING");
                    if (d.combo == Combo.HEAD) st.execute("CREATE TABLE " + s + " (aggregate_id text PRIMARY KEY, version bigint NOT NULL) WITH (fillfactor = 50)");
                }
                var cols   = "aggregate_id, event_order, event_id, caused_by_event_id, correlation_id, event_type, event_revision, timestamp, event_payload, event_metadata, tenant";
                var arrayT = pt.equals("bytea") ? "bytea[]" : "text[]";
                var unnest = "unnest(?::uuid[], ?::uuid[], ?::uuid[], ?::int2[], ?::timestamptz[], ?::" + arrayT + ", ?::" + arrayT + ") WITH ORDINALITY AS e(id, caused_by, correlation, type, ts, payload, metadata, n)";
                var payloadExpr = pt.equals("bytea") ? "e.payload, e.metadata" : "e.payload::" + pt + ", e.metadata::" + pt;
                var insert = "INSERT INTO " + t + " (" + cols + ")\nSELECT ?::text, %1$s.v + e.n, e.id, e.caused_by, e.correlation, e.type, 1, e.ts, " + payloadExpr + ", NULL\nFROM %1$s CROSS JOIN " + unnest
                        + "\nRETURNING global_order, event_order";
                var insert1 = "INSERT INTO " + t + " (" + cols + ")\nSELECT ?::text, %1$s.v + 1, ?::uuid, ?::uuid, ?::uuid, ?::int2, 1, ?::timestamptz, ?::" + pt + ", ?::" + pt + ", NULL\nFROM %1$s"
                        + "\nRETURNING global_order, event_order";
                if (d.combo == Combo.HEAD) {
                    var guarded = "WITH h AS (UPDATE " + s + " SET version = version + ?::bigint WHERE aggregate_id = ?::text AND version = ?::bigint RETURNING version - ?::bigint AS v)\n";
                    var created = "WITH h AS (INSERT INTO " + s + " (aggregate_id, version) VALUES (?::text, ?::bigint - 1) ON CONFLICT (aggregate_id) DO NOTHING RETURNING -1::bigint AS v)\n";
                    update = guarded + insert.formatted("h");
                    create = created + insert.formatted("h");
                    scalarUpdate = d.scalar1 ? guarded + insert1.formatted("h") : null;
                    scalarCreate = d.scalar1 ? created + insert1.formatted("h") : null;
                    lookup = "SELECT version FROM " + s + " WHERE aggregate_id = ?";
                } else {
                    update = "WITH xid AS MATERIALIZED (SELECT pg_current_xact_id() AS x),\n"
                            + "     head AS MATERIALIZED (SELECT coalesce(max(event_order), -1) AS v FROM " + t + " WHERE aggregate_id = ?::text),\n"
                            + "     ok AS (SELECT head.v FROM head, xid WHERE head.v = ?::bigint)\n" + insert.formatted("ok");
                    create = update;
                    scalarUpdate = null;
                    scalarCreate = null;
                    lookup = "SELECT event_order FROM " + t + " WHERE aggregate_id = ? ORDER BY event_order DESC LIMIT 1";
                }
                lockHead = null;
                read = "SELECT global_order, event_order, event_id, caused_by_event_id, correlation_id, event_type, event_revision, timestamp, event_payload, event_metadata, tenant FROM "
                        + t + " WHERE aggregate_id = ? ORDER BY event_order";
                control = "INSERT INTO " + t + " (aggregate_id, event_order, event_id, event_type, event_revision, timestamp, event_payload, event_metadata) VALUES ('ctl', 0, gen_random_uuid(), 1, 1, now(), '{}', '{}')";
                return;
            }
            control = null;
            scalarCreate = null;
            scalarUpdate = null;
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
                if (d.has(Variant.TENANT_INDEX)) st.execute("CREATE INDEX " + t + "_tenant ON " + t + " (tenant)"); // the engine's table has it
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
            if (d.scalar1 && n == 1) return appendScalar(c, aggregateId, expected);
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
                var engine = d.has(Variant.ENGINE_PAYLOAD);
                if (engine) { // the row the engine writes for the same event (§8.4): its payload, metadata, type and ids
                    var row = ENGINE_ROW;
                    for (int i = 0; i < n; i++) {
                        payloads[i] = row.payload().replace(row.orderId(), aggregateId).replace(row.productId(), UUID.randomUUID().toString());
                        meta[i] = row.meta();
                    }
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
                    Arrays.fill(cb, engine ? ENGINE_ROW.causedBy() : causedBy);
                    Arrays.fill(co, engine ? ENGINE_ROW.correlation() : corr);
                    Arrays.fill(ty, d.combo != Combo.NONE ? "1" : engine ? ENGINE_ROW.type() : EVENT_TYPE);
                    if (d.has(Variant.TEXT_ARRAYS)) { // as §6's B text arrays: createArrayOf("text", ...), sent as text array literals
                        ps.setArray(p - 1, c.createArrayOf("text", ids));
                        ps.setArray(p++, c.createArrayOf("text", cb));
                        ps.setArray(p++, c.createArrayOf("text", co));
                        ps.setArray(p++, c.createArrayOf("text", ty));
                        ps.setArray(p++, c.createArrayOf("text", ts));
                        ps.setArray(p++, c.createArrayOf("text", payloads));
                        ps.setArray(p, c.createArrayOf("text", meta));
                    } else {
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

        /** COMBO_ALL_SCALAR, one event: scalar parameters, no {@code unnest}. */
        private int appendScalar(Connection c, String aggregateId, long expected) throws SQLException {
            try (var ps = c.prepareStatement(expected < 0 ? scalarCreate : scalarUpdate)) {
                int p = 1;
                if (expected < 0) {
                    ps.setString(p++, aggregateId);
                    ps.setLong(p++, 1);
                } else {
                    ps.setLong(p++, 1);
                    ps.setString(p++, aggregateId);
                    ps.setLong(p++, expected);
                    ps.setLong(p++, 1);
                }
                ps.setString(p++, aggregateId);
                ps.setObject(p++, UUID.randomUUID());
                ps.setObject(p++, UUID.randomUUID());
                ps.setObject(p++, UUID.randomUUID());
                ps.setShort(p++, (short) 1);
                ps.setObject(p++, OffsetDateTime.now(ZoneOffset.UTC));
                ps.setString(p++, "{\"orderId\":\"" + aggregateId + "\",\"productId\":\"" + UUID.randomUUID() + "\",\"quantity\":1}");
                ps.setString(p, "{\"Key1\":\"Value1\",\"Key2\":\"Value2\"}");
                int rows = 0;
                try (var rs = ps.executeQuery()) {
                    while (rs.next()) {
                        if (rs.getLong(2) != expected + 1) throw new IllegalStateException("event order " + rs.getLong(2) + " after " + expected);
                        rows++;
                    }
                }
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
                        } else if (d.combo != Combo.NONE) {
                            chars += typeNames.get(rs.getInt(6)).length() + (d.payloadType.equals("bytea")
                                                                               ? new String(rs.getBytes(9), StandardCharsets.UTF_8).length() + new String(rs.getBytes(10), StandardCharsets.UTF_8).length()
                                                                               : rs.getString(9).length() + rs.getString(10).length());
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

    /** One row as the engine writes it for {@code ProductAddedToOrder}, captured per run for the ENGINE_PAYLOAD variants. */
    record EngineRow(String payload, String meta, String causedBy, String correlation, String type, String orderId, String productId) {}

    private static volatile EngineRow ENGINE_ROW;

    private static EngineRow captureEngineRow(Pg pg) throws SQLException {
        var orders    = AggregateType.of("Orders");
        var orderId   = OrderId.random();
        var productId = ProductId.random();
        try (var stack = Stack.writer(pg, "writer")) {
            stack.unitOfWorkFactory.usingUnitOfWork(uow -> stack.eventStore.appendToStream(orders, orderId, Optional.empty(), List.of(new OrderEvent.ProductAddedToOrder(orderId, productId, 1))));
        }
        var table = SpikePostgres.queryString(pg.observer, "SELECT relname FROM pg_class WHERE relkind = 'r' AND relname LIKE 'orders%events'");
        try (var st = pg.observer.createStatement();
             var rs = st.executeQuery("SELECT event_payload::text, event_metadata::text, caused_by_event_id, correlation_id, event_type FROM " + table)) {
            rs.next();
            var row = new EngineRow(rs.getString(1), rs.getString(2), rs.getString(3), rs.getString(4), rs.getString(5), orderId.toString(), productId.toString());
            st.execute("DROP TABLE " + table);
            return row;
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
        var c = DriverManager.getConnection(pg.server.getJdbcUrl(), pg.server.role("writer"), pg.server.password("writer"));
        c.setAutoCommit(false);
        return c;
    }

    // ------------------------------------------------------------------ the test

    @Test
    void table_design_options() throws Exception {
        record("");
        record("=== TABLE DESIGN | " + Instant.now() + " | runs " + RUNS + ", uncontended rounds " + ROUNDS + ", contended " + SECONDS + " s, CPUs " + Runtime.getRuntime().availableProcessors()
                       + " | arms " + DESIGNS + SpikeServer.target(" | postgres:17.5 fsync=off, client and database share one VM"));
        for (int run = 1; run <= RUNS; run++) {
            try (var pg = Pg.start()) {
                record("RUN " + run + " | server " + SpikePostgres.serverVersion(pg.observer) + " | pgjdbc " + org.postgresql.Driver.getVersion());
                ENGINE_ROW = captureEngineRow(pg);
                record("ENGINE ROW run " + run + " | " + ENGINE_ROW + " | lookup delay " + LOOKUP_DELAY_NANOS / 1000 + " µs");
                correctness(pg, run);
                var shaped = DESIGNS.stream().filter(d -> !d.contendedOnly()).toList();
                for (int round = 0; round <= ROUNDS; round++) {
                    var order = new ArrayList<>(shaped);
                    Collections.rotate(order, round + run);
                    for (var events : List.of(1, 10, 100)) {
                        for (var d : order) uncontended(pg, d, events, run, round, round == 0);
                    }
                    for (var events : List.of(1, 10)) { // §8 gap (a): appends to existing streams
                        for (var d : order) uncontendedExisting(pg, d, events, run, round, round == 0);
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
                // a one-event append in its own transaction (COMBO_ALL_SCALAR's scalar statement)
                int one = t.append(c, "agg", 4, 1);
                String xid1;
                try (var st = c.createStatement(); var rs = st.executeQuery("SELECT current_setting('td.xid_at_first_row', true)")) {
                    rs.next();
                    xid1 = rs.getString(1);
                }
                c.commit();
                // control: a plain INSERT ... VALUES with no xid CTE and no head row takes the global order before any xid
                String control;
                try (var st = c.createStatement()) {
                    if (t.control != null) st.execute(t.control);
                    else st.execute(d.narrow ? "INSERT INTO " + t.t + " (" + (d.explicitNextval ? "global_order, " : "") + "aggregate_id, event_order, event_id, event_type, event_revision, timestamp, event_payload, event_meta) VALUES ("
                                       + "'ctl', 0, 'ctl', 1, 1, now(), '{}', '{}')"
                                        : "INSERT INTO " + t.t + " (" + (d.explicitNextval ? "global_order, " : "") + "aggregate_id, event_order, event_id, event_type, event_revision, timestamp, event_payload, event_metadata) VALUES ("
                                       + (d.explicitNextval ? "nextval('" + t.seq + "'), " : "") + "'ctl', 0, '" + UUID.randomUUID() + "', 'x', 1, now(), '{}', '{}')");
                    try (var rs = st.executeQuery("SELECT current_setting('td.xid_at_first_row', true)")) {
                        rs.next();
                        control = rs.getString(1);
                    }
                }
                c.commit();
                record(String.format("CORRECTNESS run %d | %-20s | stale expected order: rows %d, global order values taken %d | append after it: rows %d | xid when first row's global order (%s) was taken: %s | "
                                     + "1-event append: rows %d, xid %s | control plain INSERT: %s",
                                     run, d, stale, seqAfterStale - seqBefore, ok, go, xid, one, xid1, control));
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

    /**
     * §8 gap (a): appends to existing streams, single writer. 1 000 streams are created first (one event each, untimed),
     * then each unit of work appends {@code events} to the next stream with its correct expected order (no lookup).
     */
    private void uncontendedExisting(Pg pg, Design d, int events, int run, int round, boolean discard) throws SQLException {
        var t = new Table(pg, d);
        int iterations = discard ? 100 : 1000;
        int streams = 1000;
        try (var c = writer(pg)) {
            var ids = new String[streams];
            var versions = new long[streams];
            for (int i = 0; i < streams; i++) {
                ids[i] = UUID.randomUUID().toString();
                t.append(c, ids[i], -1, 1);
                c.commit();
            }
            for (int i = 0; i < 50; i++) { // warm-up on the existing-stream statement
                var k = i % streams;
                if (t.append(c, ids[k], versions[k], events) != events) throw new IllegalStateException("conflict in warm-up");
                c.commit();
                versions[k] += events;
            }
            long rows0 = SpikePostgres.queryLong(pg.observer, "SELECT count(*) FROM " + t.t);
            pg.resetStatistics();
            var lat = new long[iterations];
            for (int i = 0; i < iterations; i++) {
                var k = (i + 50) % streams;
                long t0 = System.nanoTime();
                if (t.append(c, ids[k], versions[k], events) != events) throw new IllegalStateException("conflict on an existing stream");
                c.commit();
                lat[i] = System.nanoTime() - t0;
                versions[k] += events;
            }
            if (discard) return;
            long wal   = SpikePostgres.queryLong(pg.observer, "SELECT pg_wal_lsn_diff(pg_current_wal_lsn(), '" + pg.lsnAtReset + "'::pg_lsn)::bigint");
            double exec = execMillis(pg);
            long added = SpikePostgres.queryLong(pg.observer, "SELECT count(*) FROM " + t.t) - rows0;
            if (added != (long) iterations * events) throw new IllegalStateException("rows " + added);
            Arrays.sort(lat);
            record(String.format("UNCONTENDED-EXISTING run %d round %d | %-20s | %3d events | p50 %.3f ms p99 %.3f ms | server exec %.1f µs/UoW | WAL %.0f B/event",
                                 run, round, d, events, lat[iterations / 2] / 1e6, lat[iterations * 99 / 100] / 1e6, exec * 1000 / iterations, wal / (double) (iterations * events)));
        } finally {
            t.drop(pg);
        }
    }

    private static double execMillis(Pg pg) throws SQLException {
        return SpikePostgres.queryLong(pg.observer, "SELECT coalesce(round(sum(s.total_exec_time) * 1000), 0)::bigint FROM pg_stat_statements s JOIN pg_roles r ON r.oid = s.userid "
                + "WHERE r.rolname = '" + pg.server.role("writer") + "' AND s.query !~* '^\\s*(begin|commit|rollback|set |show )'") / 1000.0;
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
        var osBean = (com.sun.management.OperatingSystemMXBean) java.lang.management.ManagementFactory.getOperatingSystemMXBean();
        long jvm0 = osBean.getProcessCpuTime();
        long[] machine0 = machineCpu();
        var attempts = new AtomicLong();
        var zero = new AtomicLong();
        var unique = new AtomicLong();
        var other = new AtomicLong();
        var firstOther = new AtomicReference<Throwable>();
        var lookupNanos = new AtomicLongArray(3); // per outcome: 0 success, 1 zero-row, 2 unique-violation
        var appendNanos = new AtomicLongArray(3);
        var delay = t.d.has(Variant.LOOKUP_DELAY) ? LOOKUP_DELAY_NANOS : 0;
        var sleep = t.d.has(Variant.LOOKUP_SLEEP) ? LOOKUP_DELAY_NANOS : 0;
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
                        long t0 = System.nanoTime(), t1 = t0;
                        try {
                            long last = t.lookup(c, id);
                            if (delay > 0) {
                                long until = System.nanoTime() + delay;
                                while (System.nanoTime() < until) Thread.onSpinWait();
                            }
                            if (sleep > 0) java.util.concurrent.locks.LockSupport.parkNanos(sleep);
                            t1 = System.nanoTime();
                            if (t.append(c, id, last, 1) == 0) {
                                long t2 = System.nanoTime();
                                zero.incrementAndGet();
                                lookupNanos.addAndGet(1, t1 - t0);
                                appendNanos.addAndGet(1, t2 - t1);
                                c.rollback();
                            } else {
                                long t2 = System.nanoTime();
                                lookupNanos.addAndGet(0, t1 - t0);
                                appendNanos.addAndGet(0, t2 - t1);
                                c.commit();
                            }
                        } catch (SQLException e) {
                            long t2 = System.nanoTime();
                            c.rollback();
                            if ("23505".equals(e.getSQLState())) {
                                unique.incrementAndGet();
                                lookupNanos.addAndGet(2, t1 - t0);
                                appendNanos.addAndGet(2, t2 - t1);
                            } else if (other.incrementAndGet() == 1) firstOther.set(e);
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
        double cpu = SpikeServer.cpuDelta(cpu0, ContendedAppendThroughputSpikeIT.containerCpuMicros(pg));
        long jvm = osBean.getProcessCpuTime() - jvm0;
        long[] machine1 = machineCpu();
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
        long ok = a - zero.get() - unique.get() - other.get();
        record(String.format("  JVM CPU %.1f µs/attempt | machine busy %.0f%% of %d CPUs", jvm / 1e3 / a,
                             100.0 * (machine1[0] - machine0[0]) / Math.max(1, machine1[1] - machine0[1]), Runtime.getRuntime().availableProcessors()));
        record(String.format("  client µs per attempt, lookup (incl. any delay) / append statement: success %.1f / %.1f, zero-row %.1f / %.1f, unique-violation %.1f / %.1f",
                             lookupNanos.get(0) / 1e3 / Math.max(1, ok), appendNanos.get(0) / 1e3 / Math.max(1, ok),
                             lookupNanos.get(1) / 1e3 / Math.max(1, zero.get()), appendNanos.get(1) / 1e3 / Math.max(1, zero.get()),
                             lookupNanos.get(2) / 1e3 / Math.max(1, unique.get()), appendNanos.get(2) / 1e3 / Math.max(1, unique.get())));
        if (firstOther.get() != null) record("  first other error: " + firstOther.get());
        t.drop(pg);
    }

    private static long[] machineCpu() {
        try {
            var f = java.nio.file.Files.readAllLines(java.nio.file.Path.of("/proc/stat")).getFirst().trim().split("\\s+");
            long total = 0;
            for (int i = 1; i < f.length; i++) total += Long.parseLong(f[i]);
            long idle = Long.parseLong(f[4]) + Long.parseLong(f[5]);
            return new long[]{total - idle, total};
        } catch (Exception e) {
            return new long[]{0, 0};
        }
    }

    private static void record(String line) {
        SpikePostgres.record(RESULTS, line);
    }
}
