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
}
