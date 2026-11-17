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

package dk.trustworks.essentials.components.eventlog.migration;

import dk.trustworks.essentials.components.eventlog.*;
import dk.trustworks.essentials.components.eventlog.migration.PreflightReport.RefusalKind;

import java.util.*;

import static dk.trustworks.essentials.components.eventlog.EventLogSchema.MIGRATION_TABLE;

/**
 * The migration's SQL (design phase 9 §3). Every table and column name in it was validated by {@link SourceTable},
 * {@link SourceColumns} or {@link AggregateTypeTables} before it reaches here; every value is a bind parameter. No DDL:
 * the event log's tables are created by the engine's contributor, and {@code setval} is not DDL (§3.6).
 */
final class MigrationStorage {
    static final String UUID_RE = "'^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$'";

    static final String MIGRATION_STATUS = "SELECT aggregate_type, state, source_table, copied_through, epochs, started_at, cut_over_at FROM " + MIGRATION_TABLE +
            " WHERE aggregate_type = ?";
    static final String MIGRATION_STATUS_FOR_UPDATE = MIGRATION_STATUS + " FOR UPDATE";
    static final String INSERT_MIGRATION = "INSERT INTO " + MIGRATION_TABLE + " (aggregate_type, state, source_table) VALUES (?, 'COPYING', ?) " +
            "ON CONFLICT (aggregate_type) DO NOTHING";
    static final String CHECKPOINT = "UPDATE " + MIGRATION_TABLE + " SET copied_through = ? WHERE aggregate_type = ? AND state = 'COPYING' AND copied_through = ?";
    static final String COUNT_EPOCH = "UPDATE " + MIGRATION_TABLE + " SET epochs = epochs + 1 WHERE aggregate_type = ? AND state = 'COPYING'";
    static final String CUT_OVER = "UPDATE " + MIGRATION_TABLE + " SET state = 'CUT_OVER', copied_through = ?, cut_over_at = now() " +
            "WHERE aggregate_type = ? AND state = 'COPYING'";
    static final String SET_SEQUENCE = "SELECT setval(pg_get_serial_sequence(?, 'global_order'), ?, true)";
    static final String CLOCK = "SELECT clock_timestamp()";
    /** §3.3: the transactions of this database, other than this backend's, that started at or before the epoch's start */
    static final String OLDER_TRANSACTIONS = "SELECT pid, application_name, xact_start, state FROM pg_stat_activity " +
            "WHERE datname = current_database() AND backend_type = 'client backend' AND pid <> pg_backend_pid() " +
            "AND xact_start IS NOT NULL AND xact_start <= ? ORDER BY xact_start LIMIT 20";

    private final SourceTable         source;
    private final SourceCatalog       catalog;
    private final AggregateTypeTables target;

    MigrationStorage(SourceTable source, SourceCatalog catalog, AggregateTypeTables target) {
        this.source = source;
        this.catalog = catalog;
        this.target = target;
    }

    private String col(String name) {
        return "o." + name;
    }

    private SourceColumns c() {
        return source.columns();
    }

    /** {@code caused_by_event_id} values that are copied as null (§2.4, decision 5) */
    String nonUuidCause() {
        return catalog.isUuid(c().causedByEventId()) ? "false" : col(c().causedByEventId()) + " IS NOT NULL AND " + col(c().causedByEventId()) + " !~ " + UUID_RE;
    }

    /** §2.4's refusals as predicates over a row {@code o} of the old table */
    Map<RefusalKind, String> refusalPredicates() {
        var p = new LinkedHashMap<RefusalKind, String>();
        if (!catalog.isUuid(c().eventId())) {
            p.put(RefusalKind.EVENT_ID_NOT_CANONICAL_UUID, col(c().eventId()) + " IS NOT NULL AND " + col(c().eventId()) + " !~ " + UUID_RE);
        }
        if (!catalog.isUuid(c().correlationId())) {
            p.put(RefusalKind.CORRELATION_ID_NOT_CANONICAL_UUID, col(c().correlationId()) + " IS NOT NULL AND " + col(c().correlationId()) + " !~ " + UUID_RE);
        }
        var rev = col(c().eventRevision()) + "::text";
        p.put(RefusalKind.EVENT_REVISION_NOT_SMALLINT, col(c().eventRevision()) + " IS NOT NULL AND (" + rev + " !~ '^-?[0-9]{1,5}$' OR (CASE WHEN " + rev +
                " ~ '^-?[0-9]{1,5}$' THEN " + rev + "::int NOT BETWEEN -32768 AND 32767 ELSE false END))");
        p.put(RefusalKind.REQUIRED_VALUE_NULL, String.join(" OR ", List.of(c().aggregateId(), c().eventOrder(), c().eventId(), c().eventType(), c().eventRevision(),
                                                                          c().timestamp(), c().eventPayload(), c().eventMetaData())
                                                                      .stream().map(n -> col(n) + " IS NULL").toList()));
        return p;
    }

    /** One scan: row count, max, the non-UUID causes, and per refusal its count and up to 10 sample global orders */
    String preflightScan() {
        var go  = col(c().globalOrder());
        var sql = new StringBuilder("SELECT count(*), coalesce(max(" + go + "), 0), count(*) FILTER (WHERE " + nonUuidCause() + ")");
        for (var predicate : refusalPredicates().values()) {
            sql.append(", count(*) FILTER (WHERE ").append(predicate).append("), (array_agg(").append(go).append(" ORDER BY ").append(go)
               .append(") FILTER (WHERE ").append(predicate).append("))[1:10]");
        }
        return sql.append(" FROM ").append(source.tableName()).append(" o").toString();
    }

    String maxGlobalOrder() {
        return "SELECT coalesce(max(" + c().globalOrder() + "), 0) FROM " + source.tableName();
    }

    String targetMaxGlobalOrder() {
        return "SELECT coalesce(max(global_order), 0) FROM " + target.eventTable();
    }

    String distinctEventTypes() {
        return "SELECT DISTINCT " + c().eventType() + " FROM " + source.tableName() + " WHERE " + c().globalOrder() + " > ? AND " + c().globalOrder() + " <= ?";
    }

    /** The range's row count and the rows in it that cannot be copied (written after pre-flight) */
    String countRange() {
        return "SELECT count(*), count(*) FILTER (WHERE " + String.join(" OR ", refusalPredicates().values().stream().map(p -> "(" + p + ")").toList()) + ") FROM " +
                source.tableName() + " o WHERE " + col(c().globalOrder()) + " > ? AND " + col(c().globalOrder()) + " <= ?";
    }

    /** §3.2: one server-side statement per batch; the payload is the old column's text output, never re-serialised (§2.3) */
    String insertRange(boolean payloadViaJsonb) {
        var cause = catalog.isUuid(c().causedByEventId()) ? col(c().causedByEventId())
                                                           : "CASE WHEN " + col(c().causedByEventId()) + " ~ " + UUID_RE + " THEN " + col(c().causedByEventId()) + "::uuid END";
        var json = payloadViaJsonb ? "::jsonb::text::json" : "::text::json";
        return "INSERT INTO " + target.eventTable() + " (global_order, event_order, timestamp, event_id, caused_by_event_id, correlation_id, event_type, event_revision, " +
                "aggregate_id, event_payload, event_metadata, tenant) " +
                "SELECT " + col(c().globalOrder()) + ", " + col(c().eventOrder()) + ", " + col(c().timestamp()) + ", " + col(c().eventId()) + "::uuid, " + cause + ", " +
                col(c().correlationId()) + "::uuid, t.id, " + col(c().eventRevision()) + "::text::smallint, " + col(c().aggregateId()) + "::text, " +
                col(c().eventPayload()) + json + ", " + col(c().eventMetaData()) + json + ", " + col(c().tenant()) + "::text " +
                "FROM " + source.tableName() + " o JOIN " + EventLogSchema.EVENT_TYPES_TABLE + " t ON t.name = " + col(c().eventType()) +
                " WHERE " + col(c().globalOrder()) + " > ? AND " + col(c().globalOrder()) + " <= ? ORDER BY " + col(c().globalOrder());
    }

    /** §3.6: the heads of the batch's aggregates; a stream spanning batches keeps its highest order */
    String upsertHeads() {
        return "INSERT INTO " + target.headTable() + " (aggregate_id, version) SELECT aggregate_id, max(event_order) FROM " + target.eventTable() +
                " WHERE global_order > ? AND global_order <= ? GROUP BY aggregate_id " +
                "ON CONFLICT (aggregate_id) DO UPDATE SET version = greatest(" + target.headTable() + ".version, EXCLUDED.version)";
    }

    String lockTarget() {
        return source.tableName();
    }

    // ------------------------------------------------------------------ verification (§6)

    private static final String NULL_MARK = "'∅'";

    /**
     * §6.1: an old row rendered as the text its event log copy must render to. Every nullable column is marked, so that
     * {@code concat_ws} cannot shift a value into a neighbour's place; the cause is what the copy keeps of it (§2.4), the
     * revision what {@code smallint} keeps of it
     */
    private String oldRowText(boolean withPayload) {
        var cause = catalog.isUuid(c().causedByEventId()) ? col(c().causedByEventId()) + "::text"
                                                           : "CASE WHEN " + col(c().causedByEventId()) + " ~ " + UUID_RE + " THEN " + col(c().causedByEventId()) + "::text END";
        return "concat_ws('|', " + col(c().globalOrder()) + ", " + col(c().aggregateId()) + "::text, " + col(c().eventOrder()) + ", " + col(c().eventId()) + "::text, " +
                "coalesce(" + cause + ", " + NULL_MARK + "), coalesce(" + col(c().correlationId()) + "::text, " + NULL_MARK + "), " + col(c().eventType()) + "::text, " +
                col(c().eventRevision()) + "::text::smallint::text, (extract(epoch FROM " + col(c().timestamp()) + ") * 1e6)::bigint, " +
                (withPayload ? col(c().eventPayload()) + "::text, " + col(c().eventMetaData()) + "::text, " : "") +
                "coalesce(" + col(c().tenant()) + "::text, " + NULL_MARK + "))";
    }

    /** §6.1: the same rendering of an event log row; the type name comes from {@code el_event_types} */
    private static String newRowText(boolean withPayload) {
        return "concat_ws('|', e.global_order, e.aggregate_id, e.event_order, e.event_id::text, coalesce(e.caused_by_event_id::text, " + NULL_MARK + "), " +
                "coalesce(e.correlation_id::text, " + NULL_MARK + "), coalesce(y.name, " + NULL_MARK + "), e.event_revision::text, " +
                "(extract(epoch FROM e.timestamp) * 1e6)::bigint, " + (withPayload ? "e.event_payload::text, e.event_metadata::text, " : "") +
                "coalesce(e.tenant, " + NULL_MARK + "))";
    }

    private String oldRange() {
        return " FROM " + source.tableName() + " o WHERE " + col(c().globalOrder()) + " > ? AND " + col(c().globalOrder()) + " <= ?";
    }

    private String newRange() {
        return " FROM " + target.eventTable() + " e LEFT JOIN " + EventLogSchema.EVENT_TYPES_TABLE + " y ON y.id = e.event_type WHERE e.global_order > ? AND e.global_order <= ?";
    }

    /** The old range's row count and its hash: the md5 of the rows' md5s in {@code global_order} order */
    String oldRangeHash(boolean withPayload) {
        return "SELECT count(*), coalesce(md5(string_agg(md5(" + oldRowText(withPayload) + "), '' ORDER BY " + col(c().globalOrder()) + ")), '')" + oldRange();
    }

    String newRangeHash(boolean withPayload) {
        return "SELECT count(*), coalesce(md5(string_agg(md5(" + newRowText(withPayload) + "), '' ORDER BY e.global_order)), '')" + newRange();
    }

    /** Per row of the old range: its {@code global_order} and hash (bisection's last step) */
    String oldRowHashes(boolean withPayload) {
        return "SELECT " + col(c().globalOrder()) + ", md5(" + oldRowText(withPayload) + ")" + oldRange() + " ORDER BY " + col(c().globalOrder());
    }

    String newRowHashes(boolean withPayload) {
        return "SELECT e.global_order, md5(" + newRowText(withPayload) + ")" + newRange() + " ORDER BY e.global_order";
    }

    /**
     * §6.1: the streams with an event in {@code (?, ?]} whose head is missing or differs from the stream's
     * {@code max(event_order)}: their count and up to 10 of their ids
     */
    String headMismatches() {
        var mismatch = "SELECT d.aggregate_id FROM (SELECT DISTINCT aggregate_id FROM " + target.eventTable() + " WHERE global_order > ? AND global_order <= ?) d " +
                "LEFT JOIN " + target.headTable() + " h USING (aggregate_id) " +
                "WHERE h.version IS DISTINCT FROM (SELECT max(x.event_order) FROM " + target.eventTable() + " x WHERE x.aggregate_id = d.aggregate_id)";
        return "SELECT count(*), (array_agg(aggregate_id ORDER BY aggregate_id))[1:10] FROM (" + mismatch + ") m";
    }

    /** §6.1: heads without a stream (there must be as many heads as streams) */
    String orphanHeads() {
        return "SELECT count(*) FROM " + target.headTable() + " h WHERE NOT EXISTS (SELECT 1 FROM " + target.eventTable() + " e WHERE e.aggregate_id = h.aggregate_id)";
    }

    String targetMaxGlobalOrderUpTo() {
        return "SELECT coalesce(max(global_order), 0) FROM " + target.eventTable() + " WHERE global_order <= ?";
    }

    /** The event table's identity sequence {@code last_value}, 0 if nothing was ever drawn or set */
    String targetSequenceLastValue() {
        return "SELECT coalesce((SELECT s.last_value FROM pg_sequences s WHERE quote_ident(s.schemaname) || '.' || quote_ident(s.sequencename) = " +
                "pg_get_serial_sequence('" + target.eventTable() + "', 'global_order')), 0)";
    }

    /** §6.3: the old rows above the copied maximum: count and max */
    String lateRows() {
        return "SELECT count(*), coalesce(max(" + c().globalOrder() + "), 0) FROM " + source.tableName() + " WHERE " + c().globalOrder() + " > ?";
    }

    /** §6.3: the first 10 of them, with their timestamps */
    String lateRowSamples() {
        return "SELECT " + c().globalOrder() + ", " + c().timestamp() + " FROM " + source.tableName() + " WHERE " + c().globalOrder() + " > ? ORDER BY " +
                c().globalOrder() + " LIMIT 10";
    }
}
