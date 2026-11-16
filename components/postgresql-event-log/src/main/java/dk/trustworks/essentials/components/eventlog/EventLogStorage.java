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
package dk.trustworks.essentials.components.eventlog;

import static dk.trustworks.essentials.components.eventlog.EventLogSchema.*;

/**
 * Every DML statement of the engine, as a constant or a per-table template; nothing else holds SQL (DDL lives in
 * {@link EventLogSchemaContributor}). Table names reaching the templates come from a validated
 * {@link AggregateTypeTables}.
 */
final class EventLogStorage {
    static final String REGISTER_EVENT_TYPE  = "INSERT INTO " + EVENT_TYPES_TABLE + " (name) VALUES (?) ON CONFLICT (name) DO NOTHING RETURNING id";
    static final String SELECT_EVENT_TYPE_ID = "SELECT id FROM " + EVENT_TYPES_TABLE + " WHERE name = ?";
    static final String SELECT_EVENT_TYPES   = "SELECT id, name FROM " + EVENT_TYPES_TABLE;

    /**
     * Taken first in every append, so the transaction has an xid before the identity default draws a
     * {@code global_order} (plan §4.3 prerequisite 1, decision 4)
     */
    static final String XID_CTE = "WITH xid AS MATERIALIZED (SELECT pg_current_xact_id() AS x),\n";

    private static final String EVENT_COLUMNS = "global_order, event_order, event_id, caused_by_event_id, correlation_id, event_type, event_revision, " +
            "timestamp, event_payload::text, event_metadata::text, tenant, aggregate_id";

    /** {@link #EVENT_COLUMNS}' types as nulls, for the running-set row of the combined probe */
    private static final String NULL_EVENT_COLUMNS = "NULL::bigint, NULL::bigint, NULL::uuid, NULL::uuid, NULL::uuid, NULL::smallint, NULL::smallint, " +
            "NULL::timestamptz, NULL::text, NULL::text, NULL::text, NULL::text";

    /**
     * The running-writer set: every non-null {@code backend_xid}, read from the function behind {@code pg_stat_activity}
     * (P0.12). In the combined probe it is evaluated during execution, after the statement's snapshot (P0.2, I11).
     * <p>
     * Element 1 of the array is not an xid but the number of backends the same scan saw, which the probe back-off
     * scales with ({@link EventLogSettings#probeWait(long, int)}); the xids follow it. One scan, so the count costs
     * nothing extra.
     */
    static final String RUNNING_SET = "(SELECT ARRAY[count(*)::text] || coalesce(array_agg(backend_xid::text) FILTER (WHERE backend_xid IS NOT NULL), '{}'::text[]) " +
            "FROM pg_stat_get_activity(NULL))";

    /** Column index of the running set in the combined probe; columns 1-12 are {@link #EVENT_COLUMNS} */
    static final int RUNNING_COLUMN = 13;

    // ---- phase 5 (§4.2, §5.3) ----
    /** The database identity the catch-up permits are keyed by: name, server address (null on a socket), port */
    static final String DATABASE_IDENTITY = "SELECT current_database(), host(inet_server_addr()), inet_server_port()";
    /** Then the index name: one row {@code indisvalid AND indisready} if the index exists, none if absent */
    static final String INDEX_VALIDITY    = "SELECT i.indisvalid AND i.indisready FROM pg_index i WHERE i.indexrelid = to_regclass(?)";

    // ---- progress (design §2.5, §3.4) ----
    static final String PROGRESS_START =
            "WITH old AS (SELECT consistency_mode, progress_model FROM " + PROGRESS_TABLE + " WHERE subscriber = ? AND aggregate_type = ? AND instance_name = ?)\n" +
                    "INSERT INTO " + PROGRESS_TABLE + " AS p (subscriber, aggregate_type, instance_name, exclusive, consistency_mode, progress_model, resume_from)\n" +
                    "VALUES (?, ?, ?, ?, ?, ?, ?)\n" +
                    "ON CONFLICT (subscriber, aggregate_type, instance_name) DO UPDATE\n" +
                    "    SET consistency_mode = EXCLUDED.consistency_mode, progress_model = EXCLUDED.progress_model, exclusive = EXCLUDED.exclusive, updated_at = now()\n" +
                    "RETURNING p.resume_from, p.reset_epoch, (SELECT count(*) FROM old) > 0, (SELECT consistency_mode FROM old), (SELECT progress_model FROM old)";
    static final String PROGRESS_SAVE =
            "UPDATE " + PROGRESS_TABLE + " SET resume_from = ?, updated_at = now()\n" +
                    " WHERE subscriber = ? AND aggregate_type = ? AND instance_name = ? AND reset_epoch = ? AND (?::bigint IS NULL OR lease_fence = ?::bigint)";
    /**
     * Phase 4 §3.3: the in-transaction progress update, last statement of a page transaction - {@link #PROGRESS_SAVE}
     * plus a compare-and-set on the value this subscription last committed. Parameters as {@link #PROGRESS_SAVE}, then
     * the expected {@code resume_from}
     */
    static final String PROGRESS_SAVE_EXPECTED =
            "UPDATE " + PROGRESS_TABLE + " SET resume_from = ?, updated_at = now()\n" +
                    " WHERE subscriber = ? AND aggregate_type = ? AND instance_name = ? AND reset_epoch = ? AND (?::bigint IS NULL OR lease_fence = ?::bigint)\n" +
                    "   AND resume_from = ?";
    static final String PROGRESS_READ =
            "SELECT resume_from, reset_epoch, consistency_mode, progress_model FROM " + PROGRESS_TABLE + " WHERE subscriber = ? AND aggregate_type = ? AND instance_name = ?";

    // ---- parked events (phase 4 §4.4, §4.5); the subscription key's three parameters first unless said otherwise ----
    /** Then global_order, aggregate_id, blocked_by, attempts, error_type, error_message */
    static final String PARK_EVENT =
            "INSERT INTO " + PARKED_EVENTS_TABLE + " (subscriber, aggregate_type, instance_name, global_order, aggregate_id, blocked_by, attempts, error_type, error_message)\n" +
                    "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?) ON CONFLICT DO NOTHING";
    /** The held aggregates: every parked head */
    static final String PARKED_HEADS =
            "SELECT aggregate_id, global_order FROM " + PARKED_EVENTS_TABLE + " WHERE subscriber = ? AND aggregate_type = ? AND instance_name = ? AND blocked_by IS NULL ORDER BY global_order";
    /** Then the head twice: the head and every row held behind it, locked, in order */
    static final String PARKED_LOCK =
            "SELECT global_order, blocked_by FROM " + PARKED_EVENTS_TABLE + " WHERE subscriber = ? AND aggregate_type = ? AND instance_name = ? AND (global_order = ? OR blocked_by = ?)\n" +
                    "ORDER BY global_order FOR UPDATE";
    /** Then {@code bigint[]} global orders */
    static final String PARKED_DELETE =
            "DELETE FROM " + PARKED_EVENTS_TABLE + " WHERE subscriber = ? AND aggregate_type = ? AND instance_name = ? AND global_order = ANY(?::bigint[])";
    /** error_type, error_message, then the key, then the head */
    static final String PARKED_FAILED_AGAIN =
            "UPDATE " + PARKED_EVENTS_TABLE + " SET attempts = attempts + 1, error_type = ?, error_message = ?\n" + " WHERE subscriber = ? AND aggregate_type = ? AND instance_name = ? AND global_order = ?";
    /** A reset: every parked row of the subscription */
    static final String PARKED_DELETE_ALL = "DELETE FROM " + PARKED_EVENTS_TABLE + " WHERE subscriber = ? AND aggregate_type = ? AND instance_name = ?";
    /** A reset (tests until phase 8): resume_from, then the key */
    static final String PROGRESS_RESET =
            "UPDATE " + PROGRESS_TABLE + " SET reset_epoch = reset_epoch + 1, resume_from = ?, updated_at = now()\n" + " WHERE subscriber = ? AND aggregate_type = ? AND instance_name = ?";

    /**
     * The tail reader's statements over one relation - the aggregate type's event table, or a test's stand-in.
     * Parameters: {@code head} none; {@code plainTail} cursor, limit; {@code probe} cursor, limit, {@code bigint[]} holes;
     * {@code probeWithRanges} as {@code probe} plus {@code bigint[]} range lows and highs; {@code holeRead} holes, lows,
     * highs; {@code runningSet} none. {@code probe*} return {@link #RUNNING_COLUMN} columns, the running set on one row
     * whose {@code global_order} is null, sorted last - present even when no event row is (§4.10).
     * <p>
     * Phase 3's subscription statements, run on pooled connections: {@code rangeRead} lower (exclusive), upper
     * (inclusive), limit - the catch-up page; {@code bridgePage} as {@code rangeRead} plus {@code bigint[]} attach holes
     * at or below the lower bound; {@code bridgePageWithRanges} plus {@code bigint[]} range lows and highs. The bridge
     * re-reads holes by {@code UNION ALL}, never {@code OR} (P0.11), and reads no running set. {@code byOrders}:
     * {@code bigint[]} global orders - a parked aggregate's events (phase 4 §4.5).
     * <p>
     * Phase 5's filtered statements (§2.2, §2.3, §3.2) take a {@code smallint[]} of event type ids after the range bounds:
     * {@code filteredRangeRead} lower, upper, ids, limit; {@code filteredRangeReadPk} the same with
     * {@code event_type + 0}, which keeps the type index out of the plan (the density switch); {@code skipAheadProbe}
     * ids, lower, upper - the first matching order, issued only while the type index is valid; {@code filteredBridgePage}
     * lower, upper, ids, limit, holes, ids; {@code filteredBridgePageWithRanges} plus range lows, highs, ids. Both bridge
     * branches carry the type predicate.
     */
    record TailStatements(String head, String plainTail, String probe, String probeWithRanges, String holeRead, String runningSet,
                          String probeWithoutHoleReread, String rangeRead, String bridgePage, String bridgePageWithRanges, String byOrders,
                          String filteredRangeRead, String filteredRangeReadPk, String skipAheadProbe, String filteredBridgePage, String filteredBridgePageWithRanges) {
        static TailStatements of(String relation) {
            var tail   = "(SELECT " + EVENT_COLUMNS + ", NULL::text[] AS running FROM " + relation + " WHERE global_order > ? ORDER BY global_order LIMIT ?)";
            var holes  = "(SELECT " + EVENT_COLUMNS + ", NULL::text[] FROM " + relation + " WHERE global_order = ANY(?::bigint[]))";
            var ranges = "(SELECT " + EVENT_COLUMNS + ", NULL::text[] FROM " + relation + " e JOIN unnest(?::bigint[], ?::bigint[]) AS r(lo, hi) ON e.global_order BETWEEN r.lo AND r.hi)";
            var running = "(SELECT " + NULL_EVENT_COLUMNS + ", " + RUNNING_SET + ")";
            var order  = ") u ORDER BY u.global_order NULLS LAST";
            var range        = "SELECT " + EVENT_COLUMNS + " FROM " + relation + " WHERE global_order > ? AND global_order <= ? ORDER BY global_order LIMIT ?";
            var bridgeHoles  = "(SELECT " + EVENT_COLUMNS + " FROM " + relation + " WHERE global_order = ANY(?::bigint[]))";
            var bridgeRanges = "(SELECT " + EVENT_COLUMNS + " FROM " + relation + " e JOIN unnest(?::bigint[], ?::bigint[]) AS r(lo, hi) ON e.global_order BETWEEN r.lo AND r.hi)";
            var typed        = " AND event_type = ANY(?::smallint[])";
            var filtered     = "SELECT " + EVENT_COLUMNS + " FROM " + relation + " WHERE global_order > ? AND global_order <= ?" + typed + " ORDER BY global_order LIMIT ?";
            var filteredPk   = "SELECT " + EVENT_COLUMNS + " FROM " + relation + " WHERE global_order > ? AND global_order <= ? AND event_type + 0 = ANY(?::smallint[]) ORDER BY global_order LIMIT ?";
            var fHoles       = "(SELECT " + EVENT_COLUMNS + " FROM " + relation + " WHERE global_order = ANY(?::bigint[])" + typed + ")";
            var fRanges      = "(SELECT " + EVENT_COLUMNS + " FROM " + relation + " e JOIN unnest(?::bigint[], ?::bigint[]) AS r(lo, hi) ON e.global_order BETWEEN r.lo AND r.hi WHERE e.event_type = ANY(?::smallint[]))";
            return new TailStatements(
                    "SELECT (SELECT coalesce(max(global_order), 0) FROM " + relation + "), " + RUNNING_SET,
                    "SELECT " + EVENT_COLUMNS + " FROM " + relation + " WHERE global_order > ? ORDER BY global_order LIMIT ?",
                    "SELECT * FROM (\n" + tail + "\nUNION ALL\n" + holes + "\nUNION ALL\n" + running + "\n" + order,
                    "SELECT * FROM (\n" + tail + "\nUNION ALL\n" + holes + "\nUNION ALL\n" + ranges + "\nUNION ALL\n" + running + "\n" + order,
                    "SELECT * FROM (\n" + holes + "\nUNION ALL\n" + ranges + "\n) u ORDER BY u.global_order",
                    "SELECT " + RUNNING_SET,
                    "SELECT * FROM (\n" + tail + "\nUNION ALL\n" + running + "\n" + order,
                    range,
                    "SELECT * FROM (\n(" + range + ")\nUNION ALL\n" + bridgeHoles + "\n) u ORDER BY u.global_order",
                    "SELECT * FROM (\n(" + range + ")\nUNION ALL\n" + bridgeHoles + "\nUNION ALL\n" + bridgeRanges + "\n) u ORDER BY u.global_order",
                    "SELECT " + EVENT_COLUMNS + " FROM " + relation + " WHERE global_order = ANY(?::bigint[]) ORDER BY global_order",
                    filtered,
                    filteredPk,
                    "SELECT global_order FROM " + relation + " WHERE event_type = ANY(?::smallint[]) AND global_order > ? AND global_order <= ? ORDER BY global_order LIMIT 1",
                    "SELECT * FROM (\n(" + filtered + ")\nUNION ALL\n" + fHoles + "\n) u ORDER BY u.global_order",
                    "SELECT * FROM (\n(" + filtered + ")\nUNION ALL\n" + fHoles + "\nUNION ALL\n" + fRanges + "\n) u ORDER BY u.global_order");
        }

        /** Every statement with its name, for the generic-plan check */
        java.util.Map<String, String> all() {
            var all = new java.util.LinkedHashMap<String, String>();
            all.put("head", head);
            all.put("plainTail", plainTail);
            all.put("probe", probe);
            all.put("probeWithRanges", probeWithRanges);
            all.put("holeRead", holeRead);
            all.put("runningSet", runningSet);
            all.put("rangeRead", rangeRead);
            all.put("bridgePage", bridgePage);
            all.put("bridgePageWithRanges", bridgePageWithRanges);
            all.put("filteredRangeRead", filteredRangeRead);
            all.put("filteredRangeReadPk", filteredRangeReadPk);
            all.put("skipAheadProbe", skipAheadProbe);
            all.put("filteredBridgePage", filteredBridgePage);
            all.put("filteredBridgePageWithRanges", filteredBridgePageWithRanges);
            return all;
        }
    }

    private EventLogStorage() {
    }

    /**
     * The statements generated once per aggregate type. Every append binds, after its head-path parameters,
     * {@code aggregate_id, tenant} and the eight arrays {@code uuid[] id, uuid[] caused_by, uuid[] correlation,
     * int2[] type, int2[] revision, text[] timestamp, text[] payload, text[] metadata}.
     */
    record TableStatements(String appendAfter, String appendNewStream, String appendWithoutExpectation, String headVersion, String loadStream, String findEvent) {
        static TableStatements of(AggregateTypeTables tables) {
            var events = tables.eventTable();
            var heads  = tables.headTable();
            var insert = "INSERT INTO " + events + " (aggregate_id, event_order, event_id, caused_by_event_id, correlation_id,\n" +
                    "    event_type, event_revision, timestamp, event_payload, event_metadata, tenant)\n" +
                    "SELECT ?::text, h.v + e.n, e.id, e.caused_by, e.correlation, e.type, e.revision, e.ts::timestamptz, e.payload::json, e.metadata::json, ?::text\n" +
                    "FROM h CROSS JOIN unnest(?::uuid[], ?::uuid[], ?::uuid[], ?::int2[], ?::int2[], ?::text[], ?::text[], ?::text[])\n" +
                    "    WITH ORDINALITY AS e(id, caused_by, correlation, type, revision, ts, payload, metadata, n)\n" +
                    "RETURNING global_order, event_order";
            // params: n, aggregate_id, expected, n
            var after = XID_CTE +
                    "     h AS (UPDATE " + heads + " SET version = version + ?::bigint\n" +
                    "            WHERE aggregate_id = ?::text AND version = ?::bigint AND (SELECT true FROM xid)\n" +
                    "            RETURNING version - ?::bigint AS v)\n" + insert;
            // params: aggregate_id, n
            var newStream = XID_CTE +
                    "     h AS (INSERT INTO " + heads + " (aggregate_id, version) SELECT ?::text, ?::bigint - 1 FROM xid\n" +
                    "            ON CONFLICT (aggregate_id) DO NOTHING RETURNING -1::bigint AS v)\n" + insert;
            // params: aggregate_id, n, n, n
            var without = XID_CTE +
                    "     h AS (INSERT INTO " + heads + " AS hd (aggregate_id, version) SELECT ?::text, ?::bigint - 1 FROM xid\n" +
                    "            ON CONFLICT (aggregate_id) DO UPDATE SET version = hd.version + ?::bigint\n" +
                    "            RETURNING hd.version - ?::bigint AS v)\n" + insert;
            return new TableStatements(after, newStream, without,
                                       "SELECT version FROM " + heads + " WHERE aggregate_id = ?",
                                       "SELECT " + EVENT_COLUMNS + " FROM " + events + " WHERE aggregate_id = ? AND event_order >= ? AND event_order <= ? ORDER BY event_order",
                                       "SELECT " + EVENT_COLUMNS + " FROM " + events + " WHERE event_id = ?");
        }
    }
}
