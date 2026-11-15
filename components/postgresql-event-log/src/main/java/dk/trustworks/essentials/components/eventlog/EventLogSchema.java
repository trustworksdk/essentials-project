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

import dk.trustworks.essentials.components.foundation.postgresql.PostgresqlUtil;

import javax.sql.DataSource;
import java.sql.*;
import java.util.*;

import static dk.trustworks.essentials.shared.FailFast.requireNonNull;

/**
 * The event log's fixed table names, its schema entry points when no harness owns the schema, and the start-up checks
 * the watermark depends on (design {@code docs/plans/event-store-engine-phase2.md} §1.2, §2; plan §4.3).
 * <p>
 * The DDL text itself lives in {@link EventLogSchemaContributor}: the schema harness rule allows DDL only in schema
 * contributors. This class runs it, under the framework's bootstrap advisory lock, when the component owns its schema.
 */
public final class EventLogSchema {
    public static final String EVENT_TYPES_TABLE     = "el_event_types";
    public static final String AGGREGATE_TYPES_TABLE = "el_aggregate_types";
    public static final String PROGRESS_TABLE        = "el_progress";
    public static final String LEASE_FENCE_SEQUENCE  = "el_lease_fence_seq";
    public static final String PARKED_EVENTS_TABLE   = "el_parked_events";

    /**
     * Data sources already proven to show one connection another's {@code backend_xid}; identity, not equality
     */
    private static final Set<DataSource> WATERMARK_VERIFIED = Collections.newSetFromMap(new IdentityHashMap<>());

    private EventLogSchema() {
    }

    /**
     * Where an aggregate type's statements go: run directly under the bootstrap lock
     * ({@link #lockedAggregateTypeDdl(DataSource)}) or handed to the schema harness
     * ({@link EventLogSchemaContributor#aggregateTypeDdl()}).
     */
    @FunctionalInterface
    public interface AggregateTypeDdlExecutor {
        /**
         * @param tables     the aggregate type's names; may be {@code null} when only the statements matter
         * @param statements {@link EventLogSchemaContributor#aggregateTypeStatements(AggregateTypeTables)} for it
         */
        void execute(AggregateTypeTables tables, List<String> statements) throws SQLException;
    }

    /**
     * Create the fixed schema if it is not there. Non-destructive and idempotent; runs under the bootstrap lock.
     */
    public static void initialize(DataSource dataSource) throws SQLException {
        requireNonNull(dataSource, "No dataSource provided");
        withBootstrapLock(dataSource, schemaStatements());
    }

    /**
     * @return {@link EventLogSchemaContributor#schemaStatements()}
     */
    public static List<String> schemaStatements() {
        return EventLogSchemaContributor.schemaStatements();
    }

    /**
     * @return an executor that runs an aggregate type's statements under the bootstrap lock, in one transaction
     */
    public static AggregateTypeDdlExecutor lockedAggregateTypeDdl(DataSource dataSource) {
        requireNonNull(dataSource, "No dataSource provided");
        return (tables, statements) -> withBootstrapLock(dataSource, statements);
    }

    /**
     * {@code IF NOT EXISTS} is not atomic against concurrent sessions, so DDL serialises behind the framework's bootstrap
     * advisory lock - a transaction-scoped one, hence the explicit transaction.
     */
    private static void withBootstrapLock(DataSource dataSource, List<String> statements) throws SQLException {
        try (var connection = dataSource.getConnection()) {
            var autoCommit = connection.getAutoCommit();
            connection.setAutoCommit(false);
            try {
                try (var lock = connection.prepareStatement("SELECT pg_advisory_xact_lock(?)")) {
                    lock.setLong(1, PostgresqlUtil.ESSENTIALS_BOOTSTRAP_LOCK_KEY);
                    lock.execute();
                }
                try (var statement = connection.createStatement()) {
                    for (var sql : statements) {
                        statement.execute(sql);
                    }
                }
                connection.commit();
            } catch (SQLException | RuntimeException e) {
                connection.rollback();
                throw e;
            } finally {
                connection.setAutoCommit(autoCommit);
            }
        }
    }

    /**
     * Refuse to run where the watermark cannot work (plan §4.3 prerequisite 2). The tail reader's probe reads
     * {@code backend_xid} through {@code pg_stat_get_activity(NULL)}; where it is redacted for other roles' backends the
     * probe returns a subset of the running writers, and the watermark would pass a live writer's value. So this
     * constructs the condition: one connection takes a real transaction id, and a second must see it. Verified once per
     * {@code DataSource}; costs two connections and one rolled-back transaction.
     *
     * @throws IllegalStateException if the transaction id is not visible
     * @throws SQLException          if the probe itself cannot run
     */
    public static void verifyWatermarkPrerequisites(DataSource dataSource) throws SQLException {
        requireNonNull(dataSource, "No dataSource provided");
        synchronized (WATERMARK_VERIFIED) {
            if (WATERMARK_VERIFIED.contains(dataSource)) {
                return;
            }
        }
        try (var writer = dataSource.getConnection()) {
            var autoCommit = writer.getAutoCommit();
            writer.setAutoCommit(false);
            try {
                int writerPid;
                // pg_current_xact_id() assigns a real xid; a read-only transaction would prove nothing
                try (var statement = writer.prepareStatement("SELECT pg_backend_pid(), pg_current_xact_id()");
                     var resultSet = statement.executeQuery()) {
                    resultSet.next();
                    writerPid = resultSet.getInt(1);
                }
                try (var observer = dataSource.getConnection();
                     var statement = observer.prepareStatement("SELECT backend_xid IS NOT NULL FROM pg_stat_get_activity(NULL) WHERE pid = ?")) {
                    statement.setInt(1, writerPid);
                    try (var resultSet = statement.executeQuery()) {
                        if (!(resultSet.next() && resultSet.getBoolean(1))) {
                            throw new IllegalStateException("The event log cannot start: this database does not show one connection the transaction id of another " +
                                                                    "(pg_stat_get_activity(NULL).backend_xid was not visible for a backend that certainly held one). " +
                                                                    "The subscription watermark depends on it to know when a global_order can no longer commit; " +
                                                                    "without it events would be skipped. Grant the role pg_read_all_stats, or stay on the current event store.");
                        }
                    }
                }
            } finally {
                writer.rollback();
                writer.setAutoCommit(autoCommit);
            }
        }
        synchronized (WATERMARK_VERIFIED) {
            WATERMARK_VERIFIED.add(dataSource);
        }
    }

    /**
     * Refuse an event table whose {@code global_order} identity hands out values with a cache above 1 (plan §4.3
     * prerequisite 3): cached values are not handed out in wall-clock order across sessions.
     *
     * @throws IllegalStateException if the cache is not 1, or the table has no identity on {@code global_order}
     */
    public static void verifyIdentityCache(Connection connection, AggregateTypeTables tables) throws SQLException {
        requireNonNull(connection, "No connection provided");
        requireNonNull(tables, "No tables provided");
        try (var statement = connection.prepareStatement("SELECT s.seqcache FROM pg_sequence s WHERE s.seqrelid = pg_get_serial_sequence(?, 'global_order')::regclass")) {
            statement.setString(1, tables.eventTable());
            try (var resultSet = statement.executeQuery()) {
                if (!resultSet.next()) {
                    throw new IllegalStateException("Event table " + tables.eventTable() + " has no identity sequence on global_order");
                }
                var cache = resultSet.getLong(1);
                if (cache != 1) {
                    throw new IllegalStateException("The global_order identity of " + tables.eventTable() + " has CACHE " + cache + ", but the event log requires CACHE 1: " +
                                                            "cached values are not handed out in commit-observable order and the subscription watermark would skip events. " +
                                                            "Run ALTER TABLE " + tables.eventTable() + " ALTER COLUMN global_order SET CACHE 1");
                }
            }
        }
    }

    /**
     * @throws IllegalStateException if {@code el_aggregate_types} maps the aggregate type's event table to another
     *                               aggregate type (two types that differ only in case), or has no row for it
     */
    static void verifyRegistration(Connection connection, AggregateTypeTables tables) throws SQLException {
        try (var statement = connection.prepareStatement("SELECT aggregate_type FROM " + AGGREGATE_TYPES_TABLE + " WHERE event_table = ?")) {
            statement.setString(1, tables.eventTable());
            try (var resultSet = statement.executeQuery()) {
                if (!resultSet.next()) {
                    throw new IllegalStateException("Aggregate type '" + tables.aggregateType() + "' is not registered in " + AGGREGATE_TYPES_TABLE);
                }
                var registered = resultSet.getString(1);
                if (!registered.equals(tables.aggregateType())) {
                    throw new IllegalStateException("Aggregate type '" + tables.aggregateType() + "' maps to table " + tables.eventTable() +
                                                            ", which already belongs to aggregate type '" + registered + "'");
                }
            }
        }
    }
}
