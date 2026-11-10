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

import org.testcontainers.postgresql.PostgreSQLContainer;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.sql.*;
import java.util.*;

/**
 * Throwaway support for the phase-0 spikes of {@code docs/plans/event-store-subscription-redesign.md}: a PostgreSQL
 * container per major version under test, connections in each query-protocol mode, the event table and the result
 * log. Not engine code, and not run by the normal build (see the {@code spike.run} gate on the spike ITs).
 */
final class SpikePostgres {
    /** The majors the spikes run against. Override with {@code -Dspike.pg.images=postgres:17.5,...}. */
    static final String DEFAULT_IMAGES = "postgres:16.15,postgres:17.5,postgres:18.4";

    static final String TABLE = "spike_events";

    private static final Map<String, PostgreSQLContainer> containers = new LinkedHashMap<>();

    private SpikePostgres() {
    }

    static List<String> images() {
        return Arrays.stream(System.getProperty("spike.pg.images", DEFAULT_IMAGES).split(","))
                     .map(String::trim)
                     .filter(s -> !s.isEmpty())
                     .toList();
    }

    static synchronized PostgreSQLContainer container(String image) {
        return containers.computeIfAbsent(image, i -> {
            var container = new PostgreSQLContainer(i)
                    .withDatabaseName("spike")
                    .withUsername("spike")
                    .withPassword("spike");
            container.start();
            return container;
        });
    }

    static synchronized void stopAll() {
        containers.values().forEach(PostgreSQLContainer::stop);
        containers.clear();
    }

    /**
     * How a connection talks to the server. The spikes care about two things: whether parameters are inlined as
     * literals (simple protocol) or bound, and whether the plan is a custom one or a generic one of a named
     * server-side prepared statement.
     */
    enum Protocol {
        /** pgjdbc inlines every parameter and sends one Query message: always a custom plan. */
        SIMPLE("preferQueryMode=simple", false, null),
        /** Extended protocol, unnamed statement every time (prepareThreshold=0): a custom plan per execution. */
        EXTENDED_UNNAMED("prepareThreshold=0", false, null),
        /**
         * Extended protocol, named server-side statement from the first execution, default plan_cache_mode: custom
         * plans for the first five executions, after which the server may switch to the generic plan.
         */
        PREPARED_AUTO("prepareThreshold=1", true, null),
        /** Named server-side statement and {@code plan_cache_mode=force_generic_plan}: the generic plan always. */
        PREPARED_GENERIC("prepareThreshold=1", true, "force_generic_plan");

        final String  urlParameter;
        final boolean serverPrepared;
        final String  planCacheMode;

        Protocol(String urlParameter, boolean serverPrepared, String planCacheMode) {
            this.urlParameter = urlParameter;
            this.serverPrepared = serverPrepared;
            this.planCacheMode = planCacheMode;
        }
    }

    /** A connection in the given protocol mode; autocommit on, a 60 s statement timeout as a backstop. */
    static Connection connect(PostgreSQLContainer postgres, Protocol protocol) throws SQLException {
        var url = postgres.getJdbcUrl();
        url = url + (url.contains("?") ? "&" : "?") + protocol.urlParameter;
        var connection = DriverManager.getConnection(url, postgres.getUsername(), postgres.getPassword());
        try (var statement = connection.createStatement()) {
            statement.execute("SET statement_timeout = '60s'");
            if (protocol.planCacheMode != null) {
                statement.execute("SET plan_cache_mode = '" + protocol.planCacheMode + "'");
            }
        }
        return connection;
    }

    /** A plain connection (default pgjdbc settings) for setup and observation. */
    static Connection connect(PostgreSQLContainer postgres) throws SQLException {
        return connect(postgres, Protocol.EXTENDED_UNNAMED);
    }

    static String serverVersion(Connection connection) throws SQLException {
        return queryString(connection, "SHOW server_version");
    }

    /**
     * Recreate the event table - shaped like {@code SeparateTablePerAggregateTypePersistenceStrategy}'s event stream
     * table - with a fresh identity sequence, then take one value from it and return that value.
     * <p>
     * The pre-take matters: {@code nextval} WAL-logs (and so assigns the calling transaction an xid) only once per
     * 32 values, and the first call on a fresh sequence is such a call. Taking it here makes the calls under test the
     * ordinary kind, which do not assign an xid.
     */
    static long recreateEventTable(Connection connection) throws SQLException {
        try (var statement = connection.createStatement()) {
            statement.execute("DROP TABLE IF EXISTS " + TABLE);
            statement.execute("""
                              CREATE TABLE %s (
                                  global_order       bigint GENERATED BY DEFAULT AS IDENTITY PRIMARY KEY,
                                  aggregate_id       text NOT NULL,
                                  event_order        bigint NOT NULL,
                                  event_id           text NOT NULL,
                                  caused_by_event_id text,
                                  correlation_id     text,
                                  event_type         text NOT NULL,
                                  event_revision     text NOT NULL,
                                  timestamp          timestamptz NOT NULL,
                                  event_payload      jsonb NOT NULL,
                                  event_metadata     jsonb NOT NULL,
                                  tenant             text,
                                  UNIQUE (aggregate_id, event_order),
                                  UNIQUE (event_id)
                              )""".formatted(TABLE));
        }
        return queryLong(connection, "SELECT nextval(pg_get_serial_sequence('" + TABLE + "', 'global_order'))");
    }

    /** {@code last_value} of the identity sequence - non-transactional, so it shows a value another backend took. */
    static long sequenceLastValue(Connection connection) throws SQLException {
        var sequence = queryString(connection, "SELECT pg_get_serial_sequence('" + TABLE + "', 'global_order')");
        return queryLong(connection, "SELECT last_value FROM " + sequence);
    }

    static long backendPid(Connection connection) throws SQLException {
        return queryLong(connection, "SELECT pg_backend_pid()");
    }

    /** {@code pg_stat_activity.backend_xid} of a backend, as text, or null. Run on an autocommit connection. */
    static String backendXidOf(Connection observer, long pid) throws SQLException {
        try (var statement = observer.prepareStatement("SELECT backend_xid::text FROM pg_stat_activity WHERE pid = ?")) {
            statement.setInt(1, (int) pid);
            try (var rs = statement.executeQuery()) {
                return rs.next() ? rs.getString(1) : null;
            }
        }
    }

    /** One committed event for a fresh aggregate, through an autocommit connection; returns its global_order. */
    static long insertOne(Connection connection, String aggregateId) throws SQLException {
        try (var statement = connection.prepareStatement(
                "INSERT INTO " + TABLE + " (aggregate_id, event_order, event_id, event_type, event_revision, timestamp, event_payload, event_metadata) " +
                        "VALUES (?, 1, ?, 'Plain', '1', now(), '{}'::jsonb, '{}'::jsonb) RETURNING global_order")) {
            statement.setString(1, aggregateId);
            statement.setString(2, UUID.randomUUID().toString());
            try (var rs = statement.executeQuery()) {
                rs.next();
                return rs.getLong(1);
            }
        }
    }

    static long queryLong(Connection connection, String sql) throws SQLException {
        try (var statement = connection.createStatement();
             var rs = statement.executeQuery(sql)) {
            rs.next();
            return rs.getLong(1);
        }
    }

    static String queryString(Connection connection, String sql) throws SQLException {
        try (var statement = connection.createStatement();
             var rs = statement.executeQuery(sql)) {
            rs.next();
            return rs.getString(1);
        }
    }

    /** Appends a line to {@code target/spike-results/<file>} and prints it, so a run leaves a readable record. */
    static synchronized void record(String file, String line) {
        System.out.println("SPIKE " + file + " | " + line);
        try {
            var directory = Path.of(System.getProperty("user.dir"), "target", "spike-results");
            Files.createDirectories(directory);
            Files.writeString(directory.resolve(file), line + System.lineSeparator(), StandardCharsets.UTF_8,
                              StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
