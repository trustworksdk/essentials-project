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

    // ---- migration (phase 9 §3.8) ----
    /** The aggregate type; its migration's state, source table and start, if a migration row exists */
    static final String MIGRATION_STATE =
            "SELECT state, source_table, started_at FROM " + MIGRATION_TABLE + " WHERE aggregate_type = ?";

    // ---- progress (design §2.5, §3.4) ----
    static final String PROGRESS_START = progressStart(null);

    /**
     * Create a progress row or take the stored one, storing the mode, model and filter digest (phase 8 §2.6). Parameters:
     * the key twice, exclusive, mode, model, then the initial resume point unless {@code headTable} is given, then the
     * filter digest. With {@code headTable} (a validated event table name) a new row starts at its
     * {@code max(global_order) + 1}, resolved in this statement (phase 8 decision 13)
     */
    static String progressStart(String headTable) {
        var resume = headTable == null ? "?" : "(SELECT coalesce(max(global_order), 0) + 1 FROM " + headTable + ")";
        return "WITH old AS (SELECT consistency_mode, progress_model, filter_digest FROM " + PROGRESS_TABLE + " WHERE subscriber = ? AND aggregate_type = ? AND instance_name = ?)\n" +
                "INSERT INTO " + PROGRESS_TABLE + " AS p (subscriber, aggregate_type, instance_name, exclusive, consistency_mode, progress_model, resume_from, filter_digest)\n" +
                "VALUES (?, ?, ?, ?, ?, ?, " + resume + ", ?)\n" +
                "ON CONFLICT (subscriber, aggregate_type, instance_name) DO UPDATE\n" +
                "    SET consistency_mode = EXCLUDED.consistency_mode, progress_model = EXCLUDED.progress_model, filter_digest = EXCLUDED.filter_digest, updated_at = now()\n" +
                "RETURNING p.resume_from, p.reset_epoch, (SELECT count(*) FROM old) > 0, (SELECT consistency_mode FROM old), (SELECT progress_model FROM old), p.exclusive,\n" +
                "          (SELECT filter_digest FROM old)";
    }

    /**
     * The guard every fenced write of a subscription carries (phase 7 §3.1): the epoch, then fence, fence, owner. A
     * non-exclusive subscription binds a null fence. A null epoch or owner disables that part: only the test mutants
     * {@code SAVE_WITHOUT_EPOCH} and {@code FENCE_WITHOUT_OWNER} bind one
     */
    static final String FENCE_GUARD =
            "reset_epoch = coalesce(?::bigint, reset_epoch) AND (?::bigint IS NULL OR (lease_fence = ?::bigint AND lease_owner = coalesce(?::text, lease_owner)))";
    /** resume_from, the key, then {@link #FENCE_GUARD}'s parameters */
    static final String PROGRESS_SAVE =
            "UPDATE " + PROGRESS_TABLE + " SET resume_from = ?, updated_at = now()\n" +
                    " WHERE subscriber = ? AND aggregate_type = ? AND instance_name = ? AND " + FENCE_GUARD;
    /**
     * Phase 4 §3.3: the in-transaction progress update, last statement of a page transaction - {@link #PROGRESS_SAVE}
     * plus a compare-and-set on the value this subscription last committed. Parameters as {@link #PROGRESS_SAVE}, then
     * the expected {@code resume_from}
     */
    static final String PROGRESS_SAVE_EXPECTED =
            "UPDATE " + PROGRESS_TABLE + " SET resume_from = ?, updated_at = now()\n" +
                    " WHERE subscriber = ? AND aggregate_type = ? AND instance_name = ? AND " + FENCE_GUARD + "\n" +
                    "   AND resume_from = ?";
    /**
     * Phase 9 §4.3: insert an imported progress row, or leave the existing one: the key, exclusive, mode, model,
     * resume_from, filter digest. Reset epoch 0 and no lease, so the first claim takes it. Returns resume_from of the
     * inserted row; no row when one existed
     */
    static final String PROGRESS_IMPORT =
            "INSERT INTO " + PROGRESS_TABLE + " (subscriber, aggregate_type, instance_name, exclusive, consistency_mode, progress_model, resume_from, filter_digest)\n" +
                    "VALUES (?, ?, ?, ?, ?, ?, ?, ?)\n" +
                    "ON CONFLICT (subscriber, aggregate_type, instance_name) DO NOTHING\n" +
                    "RETURNING resume_from";
    /** Mutant {@code IMPORT_PROGRESS_OVERWRITES}: {@link #PROGRESS_IMPORT} replacing an existing row's resume point */
    static final String PROGRESS_IMPORT_OVERWRITING = PROGRESS_IMPORT.replace("DO NOTHING",
                                                                              "DO UPDATE SET resume_from = EXCLUDED.resume_from, updated_at = now()");
    /** The key; the stored resume_from */
    static final String PROGRESS_RESUME_FROM =
            "SELECT resume_from FROM " + PROGRESS_TABLE + " WHERE subscriber = ? AND aggregate_type = ? AND instance_name = ?";
    static final String PROGRESS_READ =
            "SELECT resume_from, reset_epoch, consistency_mode, progress_model, exclusive, lease_owner, lease_fence FROM " + PROGRESS_TABLE +
                    " WHERE subscriber = ? AND aggregate_type = ? AND instance_name = ?";

    // ---- leases (phase 7 §2); all lease time is the server's now() ----
    /**
     * Claim every standby key whose lease is free or expired, in one statement (§2.2): {@code text[]} subscribers and
     * aggregate types, then owner, lease interval. Returns subscriber, aggregate type, fence, resume_from, reset_epoch,
     * consistency_mode, progress_model of each row claimed.
     * <p>
     * Shape (§15.5, unit D): the key list is a {@code MATERIALIZED} CTE that also carries every value the row is checked
     * against ({@code instance_name}, {@code exclusive}, the claim time), and every condition compares a column of
     * {@code el_progress} with a column of {@code k}. {@code el_progress} therefore has no restriction of its own, so the
     * planner's estimate of its side is the table's size whatever the statistics hold, and it never picks a nested loop
     * that rescans the key list for every row of a filter it guessed returns one: the plan is a hash join or a nested loop
     * that probes the primary key once per key, both linear in the keys
     */
    static final String LEASE_CLAIM =
            "WITH k AS MATERIALIZED (\n" +
                    "    SELECT k.subscriber, k.aggregate_type, ''::text AS instance_name, true AS exclusive, now() AS claim_time\n" +
                    "      FROM unnest(?::text[], ?::text[]) AS k(subscriber, aggregate_type))\n" +
                    "UPDATE " + PROGRESS_TABLE + " p\n" +
                    "   SET lease_owner = ?, lease_fence = nextval('" + LEASE_FENCE_SEQUENCE + "'), lease_until = now() + ?::interval\n" +
                    "  FROM k\n" +
                    " WHERE p.subscriber = k.subscriber AND p.aggregate_type = k.aggregate_type AND p.instance_name = k.instance_name\n" +
                    "   AND p.exclusive = k.exclusive\n" +
                    "   AND (p.lease_owner IS NULL OR p.lease_until < k.claim_time)\n" +
                    "RETURNING p.subscriber, p.aggregate_type, p.lease_fence, p.resume_from, p.reset_epoch, p.consistency_mode, p.progress_model, p.filter_digest";
    /** Mutant {@code CLAIM_IGNORES_LIVE_LEASE}: {@link #LEASE_CLAIM} without the lease condition */
    static final String LEASE_CLAIM_IGNORING_LEASE = LEASE_CLAIM.replace("   AND (p.lease_owner IS NULL OR p.lease_until < k.claim_time)\n", "");
    /**
     * The first claim of an exclusive row that does not exist yet (§2.2): subscriber, aggregate type, consistency_mode,
     * progress_model, resume_from, owner, lease interval. No row returned: another node created it
     */
static final String LEASE_CLAIM_NEW = leaseClaimNew(null);

    /**
     * Insert the row of a never-seen exclusive subscription with its first claim (§2.2): subscriber, aggregate type, mode,
     * model, the initial resume point unless {@code headTable} is given (phase 8 decision 13: then
     * {@code max(global_order) + 1} of that validated event table), owner, lease interval, filter digest (phase 8 §2.6)
     */
    static String leaseClaimNew(String headTable) {
        var resume = headTable == null ? "?" : "(SELECT coalesce(max(global_order), 0) + 1 FROM " + headTable + ")";
        return "INSERT INTO " + PROGRESS_TABLE + " (subscriber, aggregate_type, instance_name, exclusive, consistency_mode, progress_model, resume_from, lease_owner, lease_fence, lease_until, filter_digest)\n" +
                "VALUES (?, ?, '', true, ?, ?, " + resume + ", ?, nextval('" + LEASE_FENCE_SEQUENCE + "'), now() + ?::interval, ?)\n" +
                "ON CONFLICT (subscriber, aggregate_type, instance_name) DO NOTHING\n" +
                "RETURNING lease_fence, resume_from, reset_epoch";
    }
    /**
     * Renew every owned lease in one statement (§2.3): owner, then {@code text[]} subscribers, aggregate types and
     * {@code bigint[]} fences, then the lease interval. Returns subscriber, aggregate type of each lease renewed. Shaped
     * as {@link #LEASE_CLAIM}: the owner and the fence are checked against columns of the key list, never as a
     * restriction of {@code el_progress} alone, so no statistics (or their absence) can make the planner rescan the key
     * list per row (§15.5)
     */
    static final String LEASE_RENEW =
            "WITH k AS MATERIALIZED (\n" +
                    "    SELECT k.subscriber, k.aggregate_type, ''::text AS instance_name, k.fence, ?::text AS owner\n" +
                    "      FROM unnest(?::text[], ?::text[], ?::bigint[]) AS k(subscriber, aggregate_type, fence))\n" +
                    "UPDATE " + PROGRESS_TABLE + " p\n" +
                    "   SET lease_until = now() + ?::interval\n" +
                    "  FROM k\n" +
                    " WHERE p.subscriber = k.subscriber AND p.aggregate_type = k.aggregate_type AND p.instance_name = k.instance_name\n" +
                    "   AND p.lease_fence = k.fence\n" +
                    "   AND p.lease_owner = k.owner\n" +
                    "RETURNING p.subscriber, p.aggregate_type";
    /** Mutant {@code RENEW_WITHOUT_FENCE}: {@link #LEASE_RENEW} keyed by the subscription only (the owner still binds, unused) */
    static final String LEASE_RENEW_WITHOUT_FENCE = LEASE_RENEW.replace("   AND p.lease_fence = k.fence\n", "   AND k.fence IS NOT NULL\n")
                                                               .replace("   AND p.lease_owner = k.owner\n", "   AND k.owner IS NOT NULL\n");
    /** Release an in-transaction subscription's lease (§2.5): the key, then fence, owner */
    static final String LEASE_RELEASE =
            "UPDATE " + PROGRESS_TABLE + " SET lease_owner = NULL, lease_until = NULL, updated_at = now()\n" +
                    " WHERE subscriber = ? AND aggregate_type = ? AND instance_name = ? AND lease_fence = ? AND lease_owner = ?";
    /**
     * A periodic subscription's final save and release in one statement (§2.5): resume_from, the key, the epoch the
     * resume point was computed in (null under mutant {@code RELEASE_WRITES_ANY_EPOCH}), fence, owner
     */
    static final String PROGRESS_SAVE_RELEASE =
            "UPDATE " + PROGRESS_TABLE + "\n" +
                    "   SET resume_from = CASE WHEN reset_epoch = coalesce(?::bigint, reset_epoch) THEN ? ELSE resume_from END,\n" +
                    "       updated_at = now(), lease_owner = NULL, lease_until = NULL\n" +
                    " WHERE subscriber = ? AND aggregate_type = ? AND instance_name = ? AND lease_fence = ? AND lease_owner = ?";
    /**
     * A new owner whose claimed row names another mode or model writes its own (§2.2): consistency_mode, progress_model,
     * the key, then {@link #FENCE_GUARD}'s parameters
     */
    static final String PROGRESS_SET_MODE =
            "UPDATE " + PROGRESS_TABLE + " SET consistency_mode = ?, progress_model = ?, filter_digest = ?, updated_at = now()\n" +
                    " WHERE subscriber = ? AND aggregate_type = ? AND instance_name = ? AND " + FENCE_GUARD;
    /**
     * Mutant {@code FENCE_READ_BEFORE_WRITE}: the fence checked by a separate, unlocked read before an unfenced update - the
     * shape plan §9's revision rejected. The key, then fence, owner
     */
    static final String PROGRESS_FENCE_CHECK =
            "SELECT 1 FROM " + PROGRESS_TABLE + " WHERE subscriber = ? AND aggregate_type = ? AND instance_name = ? AND lease_fence = ? AND lease_owner = ?";
    /** A page transaction of an exclusive subscription ends within one lease if its owner dies (§7, decision 6): milliseconds */
    static final String PAGE_IDLE_TIMEOUT = "SELECT set_config('idle_in_transaction_session_timeout', ?, true)";

    // ---- parked events (phase 4 §4.4, §4.5); the subscription key's three parameters first unless said otherwise ----
    /** Then global_order, aggregate_id, blocked_by, attempts, error_type, error_message */
    static final String PARK_EVENT =
            "INSERT INTO " + PARKED_EVENTS_TABLE + " (subscriber, aggregate_type, instance_name, global_order, aggregate_id, blocked_by, attempts, error_type, error_message)\n" +
                    "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?) ON CONFLICT DO NOTHING";
    /**
     * The periodic park insert, guarded on the progress row (phase 7 §3.3): the key, then {@link #FENCE_GUARD}'s
     * parameters, then global_order, aggregate_id, blocked_by, attempts, error_type, error_message. The {@code FOR SHARE}
     * guard waits for a reset or claim that got there first and re-checks against its row version. Returns the guard's
     * and the insert's row counts
     */
    static final String PARK_EVENT_GUARDED =
            "WITH guard AS (SELECT 1 FROM " + PROGRESS_TABLE + " WHERE subscriber = ? AND aggregate_type = ? AND instance_name = ? AND " + FENCE_GUARD + " FOR SHARE),\n" +
                    "ins AS (INSERT INTO " + PARKED_EVENTS_TABLE + " (subscriber, aggregate_type, instance_name, global_order, aggregate_id, blocked_by, attempts, error_type, error_message)\n" +
                    "        SELECT ?, ?, ?, ?, ?, ?, ?, ?, ? FROM guard\n" +
                    "        ON CONFLICT DO NOTHING RETURNING 1)\n" +
                    "SELECT (SELECT count(*) FROM guard), (SELECT count(*) FROM ins)";
    /**
     * {@link #PARKED_FAILED_AGAIN} guarded as {@link #PARK_EVENT_GUARDED}: error_type, error_message, the key, the head,
     * then the key and {@link #FENCE_GUARD}'s parameters
     */
    static final String PARKED_FAILED_AGAIN_GUARDED =
            "UPDATE " + PARKED_EVENTS_TABLE + " SET attempts = attempts + 1, error_type = ?, error_message = ?, requested = NULL, requested_at = NULL\n" +
                    " WHERE subscriber = ? AND aggregate_type = ? AND instance_name = ? AND global_order = ?\n" +
                    "   AND EXISTS (SELECT 1 FROM " + PROGRESS_TABLE + " WHERE subscriber = ? AND aggregate_type = ? AND instance_name = ? AND " + FENCE_GUARD + " FOR SHARE)";
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
            "UPDATE " + PARKED_EVENTS_TABLE + " SET attempts = attempts + 1, error_type = ?, error_message = ?, requested = NULL, requested_at = NULL\n" + " WHERE subscriber = ? AND aggregate_type = ? AND instance_name = ? AND global_order = ?";
    /** A reset: every parked row of the subscription */
    static final String PARKED_DELETE_ALL = "DELETE FROM " + PARKED_EVENTS_TABLE + " WHERE subscriber = ? AND aggregate_type = ? AND instance_name = ?";
    // ---- phase 8 §4: parked requests and the admin facade ----
    /**
     * The serve transaction's first statement (§4.3, §11.1 I5, I6): lock the progress row under {@link #FENCE_GUARD}, so a
     * reset or a takeover that got there first leaves nothing to serve, and one that comes later waits for the serve to
     * commit. The key, then {@link #FENCE_GUARD}'s parameters. Taken before the parked rows, in the order a reset takes
     * them, so the two never deadlock
     */
    static final String PARKED_SERVE_GUARD =
            "SELECT 1 FROM " + PROGRESS_TABLE + " WHERE subscriber = ? AND aggregate_type = ? AND instance_name = ? AND " + FENCE_GUARD + " FOR NO KEY UPDATE";
    /** The parked check (§4.3): the requests stored on the subscription's heads. The key */
    static final String PARKED_REQUESTS =
            "SELECT global_order, requested FROM " + PARKED_EVENTS_TABLE +
                    " WHERE subscriber = ? AND aggregate_type = ? AND instance_name = ? AND requested IS NOT NULL AND blocked_by IS NULL ORDER BY global_order";
    /**
     * Store a request on a parked head (§4.3): the key, then the key, requested, the head. Returns whether the progress
     * row exists and whether a head row took the request
     */
    static final String PARKED_REQUEST =
            "WITH p AS (SELECT 1 FROM " + PROGRESS_TABLE + " WHERE subscriber = ? AND aggregate_type = ? AND instance_name = ?),\n" +
                    "u AS (UPDATE " + PARKED_EVENTS_TABLE + " SET requested = ?, requested_at = now()\n" +
                    "       WHERE subscriber = ? AND aggregate_type = ? AND instance_name = ? AND global_order = ? AND blocked_by IS NULL AND EXISTS (SELECT 1 FROM p)\n" +
                    "       RETURNING 1)\n" +
                    "SELECT (SELECT count(*) FROM p), (SELECT count(*) FROM u)";
    /**
     * The admin listing (§4.1), one statement: every progress row with its parked head count and total and its lag, the
     * head of each listed aggregate type read once from the type's event table through {@code el_aggregate_types} (so a
     * type no node of this JVM registered still shows). Optional aggregate type twice, then the limit. Columns:
     * subscriber, aggregate_type, instance_name, exclusive, consistency_mode, progress_model, resume_from, reset_epoch,
     * lease_owner, lease_until, lease_fence, updated_at, filter_digest, parked heads, parked total, lag (null if the type is
     * not registered)
     */
    static final String ADMIN_SUBSCRIPTIONS =
            "WITH p AS MATERIALIZED (SELECT * FROM " + PROGRESS_TABLE + " WHERE (?::text IS NULL OR aggregate_type = ?)\n" +
                    "                         ORDER BY aggregate_type, subscriber, instance_name LIMIT ?),\n" +
                    "h AS MATERIALIZED (SELECT t.aggregate_type,\n" +
                    "                          coalesce((xpath('/row/m/text()', query_to_xml(format('SELECT max(global_order) AS m FROM %I', t.event_table), false, true, '')))[1]::text::bigint, 0) AS head\n" +
                    "                     FROM " + AGGREGATE_TYPES_TABLE + " t WHERE t.aggregate_type IN (SELECT DISTINCT aggregate_type FROM p)),\n" +
                    "c AS (SELECT e.subscriber, e.aggregate_type, e.instance_name, count(*) FILTER (WHERE e.blocked_by IS NULL) AS heads, count(*) AS total\n" +
                    "        FROM " + PARKED_EVENTS_TABLE + " e JOIN p USING (subscriber, aggregate_type, instance_name)\n" +
                    "       GROUP BY e.subscriber, e.aggregate_type, e.instance_name)\n" +
                    "SELECT p.subscriber, p.aggregate_type, p.instance_name, p.exclusive, p.consistency_mode, p.progress_model, p.resume_from, p.reset_epoch,\n" +
                    "       p.lease_owner, p.lease_until, p.lease_fence, p.updated_at, p.filter_digest, coalesce(c.heads, 0), coalesce(c.total, 0),\n" +
                    "       greatest(h.head - (p.resume_from - 1), 0)\n" +
                    "  FROM p LEFT JOIN c USING (subscriber, aggregate_type, instance_name) LEFT JOIN h USING (aggregate_type)\n" +
                    " ORDER BY p.aggregate_type, p.subscriber, p.instance_name";
    /** {@link #ADMIN_SUBSCRIPTIONS} for one key: the key */
    static final String ADMIN_SUBSCRIPTION =
            ADMIN_SUBSCRIPTIONS.replace("WHERE (?::text IS NULL OR aggregate_type = ?)\n", "WHERE subscriber = ? AND aggregate_type = ? AND instance_name = ?\n")
                               .replace(" LIMIT ?),\n", "),\n");
    /** One subscription's parked rows above a global order (§4.1): the key, the lower bound (exclusive), the limit */
    static final String ADMIN_PARKED =
            "SELECT global_order, aggregate_id, blocked_by, attempts, error_type, error_message, parked_at, requested, requested_at FROM " + PARKED_EVENTS_TABLE +
                    " WHERE subscriber = ? AND aggregate_type = ? AND instance_name = ? AND global_order > ? ORDER BY global_order LIMIT ?";
    /**
     * Delete a retired instance's row and its parked rows (§19 item 17): only a non-exclusive row nobody holds a lease on.
     * The key, then the key. Returns the progress rows deleted
     */
    static final String ADMIN_DELETE_RETIRED =
            "WITH d AS (DELETE FROM " + PROGRESS_TABLE + " WHERE subscriber = ? AND aggregate_type = ? AND instance_name = ? AND NOT exclusive AND instance_name <> ''\n" +
                    "           AND (lease_until IS NULL OR lease_until < now()) RETURNING 1),\n" +
                    "e AS (DELETE FROM " + PARKED_EVENTS_TABLE + " WHERE subscriber = ? AND aggregate_type = ? AND instance_name = ? AND EXISTS (SELECT 1 FROM d))\n" +
                    "SELECT count(*) FROM d";

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
     * Phase 5's filtered statements (§2.2, §2.3, §3.2) and phase 8's tenant filter (§15, question 7) are the three
     * {@link FilterStatements} sets {@code typeFilter}, {@code tenantFilter} and {@code typeAndTenantFilter}; each binds
     * its filter parameters where {@link FilterStatements} says.
     * <p>
     * Phase 6's tail with re-read (§4.2), the shape a woken read and every statement of a multi-type flush use when holes
     * are outstanding: {@code rereadTail} cursor, limit, {@code bigint[]} holes; {@code rereadTailWithRanges} plus range
     * lows and highs. As {@code probe*} without the running-set branch: the flush reads one running set, last.
     */
    record TailStatements(String head, String plainTail, String probe, String probeWithRanges, String holeRead, String runningSet,
                          String probeWithoutHoleReread, String rangeRead, String bridgePage, String bridgePageWithRanges, String byOrders,
                          FilterStatements typeFilter, FilterStatements tenantFilter, FilterStatements typeAndTenantFilter,
                          String rereadTail, String rereadTailWithRanges) {
        static TailStatements of(String relation) {
            var tail   = "(SELECT " + EVENT_COLUMNS + ", NULL::text[] AS running FROM " + relation + " WHERE global_order > ? ORDER BY global_order LIMIT ?)";
            var holes  = "(SELECT " + EVENT_COLUMNS + ", NULL::text[] FROM " + relation + " WHERE global_order = ANY(?::bigint[]))";
            var ranges = "(SELECT " + EVENT_COLUMNS + ", NULL::text[] FROM " + relation + " e JOIN unnest(?::bigint[], ?::bigint[]) AS r(lo, hi) ON e.global_order BETWEEN r.lo AND r.hi)";
            var running = "(SELECT " + NULL_EVENT_COLUMNS + ", " + RUNNING_SET + ")";
            var order  = ") u ORDER BY u.global_order NULLS LAST";
            var range        = "SELECT " + EVENT_COLUMNS + " FROM " + relation + " WHERE global_order > ? AND global_order <= ? ORDER BY global_order LIMIT ?";
            var bridgeHoles  = "(SELECT " + EVENT_COLUMNS + " FROM " + relation + " WHERE global_order = ANY(?::bigint[]))";
            var bridgeRanges = "(SELECT " + EVENT_COLUMNS + " FROM " + relation + " e JOIN unnest(?::bigint[], ?::bigint[]) AS r(lo, hi) ON e.global_order BETWEEN r.lo AND r.hi)";
            var rTail        = "(SELECT " + EVENT_COLUMNS + " FROM " + relation + " WHERE global_order > ? ORDER BY global_order LIMIT ?)";
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
                    FilterStatements.of(relation, true, false),
                    FilterStatements.of(relation, false, true),
                    FilterStatements.of(relation, true, true),
                    "SELECT * FROM (\n" + rTail + "\nUNION ALL\n" + bridgeHoles + "\n) u ORDER BY u.global_order",
                    "SELECT * FROM (\n" + rTail + "\nUNION ALL\n" + bridgeHoles + "\nUNION ALL\n" + bridgeRanges + "\n) u ORDER BY u.global_order");
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
            typeFilter.putAll("", all);
            tenantFilter.putAll("tenant.", all);
            typeAndTenantFilter.putAll("typeAndTenant.", all);
            all.put("rereadTail", rereadTail);
            all.put("rereadTailWithRanges", rereadTailWithRanges);
            return all;
        }

        /**
         * @param types  the subscription filters on event type
         * @param tenant the subscription filters on tenant
         * @return the filtered statements for that filter; {@code null} without either
         */
        FilterStatements filter(boolean types, boolean tenant) {
            return types ? tenant ? typeAndTenantFilter : typeFilter : tenant ? tenantFilter : null;
        }
    }

    /**
     * The filtered catch-up and bridge statements of one filter shape (phase 5 §2.2, §2.3, §3.2; phase 8 §15 question 7).
     * The filter predicate {@code F} is {@code event_type = ANY(?::smallint[])} (the event type ids), {@code tenant = ?},
     * or both, ids first; every place below that says {@code F} binds those parameters in that order.
     * <ul>
     *     <li>{@code rangeRead}: lower (exclusive), upper (inclusive), {@code F}, limit</li>
     *     <li>{@code rangeReadPk}: the same with {@code event_type + 0} and {@code tenant || ''}, which keep the type and
     *     tenant indexes out of the plan (the density switch)</li>
     *     <li>{@code skipAheadProbe}: {@code F}, lower, upper - the first matching order; issued only while an index
     *     that serves {@code F} is valid</li>
     *     <li>{@code bridgePage}: lower, upper, {@code F}, limit, {@code bigint[]} holes, {@code F}</li>
     *     <li>{@code bridgePageWithRanges}: as {@code bridgePage}, then range lows, highs, {@code F}. Every bridge branch
     *     carries {@code F}</li>
     * </ul>
     */
    record FilterStatements(boolean types, boolean tenant, String rangeRead, String rangeReadPk, String skipAheadProbe, String bridgePage, String bridgePageWithRanges) {
        static FilterStatements of(String relation, boolean types, boolean tenant) {
            if (!types && !tenant) {
                throw new IllegalArgumentException("A filter statement set filters on event type, tenant or both");
            }
            var predicate   = predicate("", types, tenant, false);
            var predicatePk = predicate("", types, tenant, true);
            var range       = "SELECT " + EVENT_COLUMNS + " FROM " + relation + " WHERE global_order > ? AND global_order <= ? AND " + predicate + " ORDER BY global_order LIMIT ?";
            var rangePk     = "SELECT " + EVENT_COLUMNS + " FROM " + relation + " WHERE global_order > ? AND global_order <= ? AND " + predicatePk + " ORDER BY global_order LIMIT ?";
            var holes       = "(SELECT " + EVENT_COLUMNS + " FROM " + relation + " WHERE global_order = ANY(?::bigint[]) AND " + predicate + ")";
            // The tenant predicate filters the joined rows behind an OFFSET 0 fence: inside the join, a tenant the
            // statistics think rare (e.g. a column of nulls) makes a generic plan walk the table instead of the ranges
            var join        = "SELECT " + EVENT_COLUMNS + " FROM " + relation + " e JOIN unnest(?::bigint[], ?::bigint[]) AS r(lo, hi) ON e.global_order BETWEEN r.lo AND r.hi" +
                    (types ? " WHERE " + predicate("e.", true, false, false) : "");
            var ranges      = tenant ? "(SELECT * FROM (" + join + " OFFSET 0) f WHERE " + predicate("f.", false, true, false) + ")" : "(" + join + ")";
            return new FilterStatements(types, tenant, range, rangePk,
                                        "SELECT global_order FROM " + relation + " WHERE " + predicate + " AND global_order > ? AND global_order <= ? ORDER BY global_order LIMIT 1",
                                        "SELECT * FROM (\n(" + range + ")\nUNION ALL\n" + holes + "\n) u ORDER BY u.global_order",
                                        "SELECT * FROM (\n(" + range + ")\nUNION ALL\n" + holes + "\nUNION ALL\n" + ranges + "\n) u ORDER BY u.global_order");
        }

        private static String predicate(String alias, boolean types, boolean tenant, boolean primaryKey) {
            var typed    = alias + (primaryKey ? "event_type + 0" : "event_type") + " = ANY(?::smallint[])";
            var tenanted = alias + (primaryKey ? "tenant || ''" : "tenant") + " = ?";
            return types ? tenant ? typed + " AND " + tenanted : typed : tenanted;
        }

        private void putAll(String prefix, java.util.Map<String, String> all) {
            all.put(prefix + "filteredRangeRead", rangeRead);
            all.put(prefix + "filteredRangeReadPk", rangeReadPk);
            all.put(prefix + "skipAheadProbe", skipAheadProbe);
            all.put(prefix + "filteredBridgePage", bridgePage);
            all.put(prefix + "filteredBridgePageWithRanges", bridgePageWithRanges);
        }
    }

    private EventLogStorage() {
    }

    /**
     * The read statements the {@code EventStore} adapter needs (phase 8 §5.2, unit 8b-A), generated once per aggregate
     * type. Each reaches the event table through an index condition under a generic plan ({@code EventLogGenericPlanIT}).
     * <p>
     * The global-order range reads bind {@code fromInclusive, toInclusive}, then the filter parameters
     * {@link #FILTER_PARAMETERS}: {@code int2[] types} twice (null = every type) and {@code text tenant} twice (null =
     * every tenant; otherwise that tenant's events and the events without a tenant, as {@code PostgresqlEventStore}
     * reads them). The filters are plain {@code Filter} conditions on the primary-key range, never an {@code OR} with an
     * index condition. The requested orders outside the range are a statement of their own ({@code additional}:
     * {@code bigint[] orders, fromInclusive, toInclusive}, then the filters), merged with the range in the JVM: never an
     * {@code OR} with the range, and never a {@code UNION ALL} under one {@code ORDER BY}, whose generic plan sorts the
     * whole range before its first row.
     *
     * @param lastEvent           {@code aggregate_id}: the stream's highest event order, backwards on the stream-order constraint
     * @param findEvents          {@code uuid[] ids}: on the event-id index, in global order
     * @param range               the range read, in global order, walking the primary key (no sort)
     * @param additional          the additional orders outside the range, in global order
     * @param highest             {@code max(global_order)}, null on an empty table
     * @param lowest              {@code min(global_order)}, null on an empty table
     * @param causedBy            {@code uuid caused_by_event_id}: on the partial caused-by index, in global order
     */
    record ReadStatements(String lastEvent, String findEvents, String range, String additional, String highest, String lowest, String causedBy) {
        static final int FILTER_PARAMETERS = 4;

        static ReadStatements of(String events) {
            var filters = " AND (?::int2[] IS NULL OR event_type = ANY(?::int2[])) AND (?::text IS NULL OR tenant IS NULL OR tenant = ?::text)";
            var range = "SELECT " + EVENT_COLUMNS + " FROM " + events + " WHERE global_order >= ? AND global_order <= ?" + filters;
            var additional = "SELECT " + EVENT_COLUMNS + " FROM " + events + " WHERE global_order = ANY(?::bigint[]) AND (global_order < ? OR global_order > ?)" + filters;
            return new ReadStatements("SELECT " + EVENT_COLUMNS + " FROM " + events + " WHERE aggregate_id = ? ORDER BY event_order DESC LIMIT 1",
                                      "SELECT " + EVENT_COLUMNS + " FROM " + events + " WHERE event_id = ANY(?::uuid[]) ORDER BY global_order",
                                      range + " ORDER BY global_order",
                                      additional + " ORDER BY global_order",
                                      "SELECT max(global_order) FROM " + events,
                                      "SELECT min(global_order) FROM " + events,
                                      "SELECT " + EVENT_COLUMNS + " FROM " + events + " WHERE caused_by_event_id = ? ORDER BY global_order");
        }

        /**
         * @return every statement by its name, for the plan check
         */
        java.util.Map<String, String> all() {
            var all = new java.util.LinkedHashMap<String, String>();
            all.put("lastEvent", lastEvent);
            all.put("findEvents", findEvents);
            all.put("range", range);
            all.put("additional", additional);
            all.put("highest", highest);
            all.put("lowest", lowest);
            all.put("causedBy", causedBy);
            return all;
        }
    }

    /**
     * The statements generated once per aggregate type. Every append binds, after its head-path parameters,
     * {@code aggregate_id, tenant} and the eight arrays {@code uuid[] id, uuid[] caused_by, uuid[] correlation,
     * int2[] type, int2[] revision, text[] timestamp, text[] payload, text[] metadata}.
     */
    record TableStatements(String appendAfter, String appendNewStream, String appendWithoutExpectation, String loadStream, String findEvent) {
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
                                       "SELECT " + EVENT_COLUMNS + " FROM " + events + " WHERE aggregate_id = ? AND event_order >= ? AND event_order <= ? ORDER BY event_order",
                                       "SELECT " + EVENT_COLUMNS + " FROM " + events + " WHERE event_id = ?");
        }
    }
}
