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
