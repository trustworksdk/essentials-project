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

import dk.trustworks.essentials.components.foundation.test.EssentialsTestContainers;
import org.junit.jupiter.api.BeforeEach;
import org.postgresql.ds.PGSimpleDataSource;
import org.testcontainers.junit.jupiter.*;
import org.testcontainers.postgresql.PostgreSQLContainer;

import java.sql.*;
import java.time.Instant;
import java.util.*;

/**
 * One static PostgreSQL container per test class; every test starts from an empty {@code public} schema.
 */
@Testcontainers
abstract class AbstractEventLogIT {
    /**
     * Overrides the PostgreSQL image of every IT of this module, e.g. {@code -Deventlog.it.image=postgres:16.15}, to run
     * them against another supported major (16, 17 and 18). Unset: {@link EssentialsTestContainers#POSTGRES_IMAGE}
     */
    static final String IMAGE_PROPERTY = "eventlog.it.image";

    @Container
    static final PostgreSQLContainer postgres = postgresContainer();

    private static PostgreSQLContainer postgresContainer() {
        var image = System.getProperty(IMAGE_PROPERTY, "");
        if (image.isBlank()) {
            return EssentialsTestContainers.postgres();
        }
        var defaults = EssentialsTestContainers.postgres();
        return new PostgreSQLContainer(org.testcontainers.utility.DockerImageName.parse(image).asCompatibleSubstituteFor("postgres"))
                .withDatabaseName(defaults.getDatabaseName())
                .withUsername(defaults.getUsername())
                .withPassword(defaults.getPassword());
    }

    protected PGSimpleDataSource dataSource;

    @BeforeEach
    void resetDatabase() throws SQLException {
        dataSource = dataSource("");
        try (var connection = dataSource.getConnection(); var statement = connection.createStatement()) {
            statement.execute("DROP SCHEMA public CASCADE");
            statement.execute("CREATE SCHEMA public");
        }
    }

    /**
     * @param urlParameters extra pgjdbc URL parameters, e.g. {@code preferQueryMode=simple}
     */
    protected static PGSimpleDataSource dataSource(String urlParameters) {
        var ds  = new PGSimpleDataSource();
        var url = postgres.getJdbcUrl();
        if (!urlParameters.isEmpty()) {
            url = url + (url.contains("?") ? "&" : "?") + urlParameters;
        }
        ds.setUrl(url);
        ds.setUser(postgres.getUsername());
        ds.setPassword(postgres.getPassword());
        return ds;
    }

    protected EventLog startedEventLog(String... aggregateTypes) throws SQLException {
        var eventLog = EventLog.builder().setDataSource(dataSource).build();
        eventLog.start();
        for (var type : aggregateTypes) {
            eventLog.registerAggregateType(type);
        }
        return eventLog;
    }

    protected static EventToAppend event(String type, String payload) {
        return new EventToAppend(UUID.randomUUID(), null, null, type, 1, Instant.parse("2026-11-15T10:00:00.123456Z"), payload, "{}");
    }

    protected static List<EventToAppend> events(String type, int count) {
        var events = new ArrayList<EventToAppend>();
        for (var i = 0; i < count; i++) {
            events.add(event(type, "{\"n\":" + i + "}"));
        }
        return events;
    }

    protected static long queryLong(Connection connection, String sql) throws SQLException {
        try (var statement = connection.createStatement(); var resultSet = statement.executeQuery(sql)) {
            resultSet.next();
            return resultSet.getLong(1);
        }
    }

    protected long queryLong(String sql) throws SQLException {
        try (var connection = dataSource.getConnection()) {
            return queryLong(connection, sql);
        }
    }

    protected void execute(String sql) throws SQLException {
        try (var connection = dataSource.getConnection(); var statement = connection.createStatement()) {
            statement.execute(sql);
        }
    }

    protected long sequenceLastValue(AggregateTypeTables tables) throws SQLException {
        return queryLong("SELECT coalesce((SELECT last_value FROM pg_sequences WHERE schemaname || '.' || sequencename = pg_get_serial_sequence('" +
                                 tables.eventTable() + "', 'global_order') AND last_value IS NOT NULL), 0)");
    }

    /**
     * Every stream's head row equals its {@code max(event_order)}, and every stream with events has a head row
     */
    protected void assertHeadsConsistent(AggregateTypeTables tables) throws SQLException {
        var mismatches = queryLong("SELECT count(*) FROM (SELECT aggregate_id, max(event_order) AS m FROM " + tables.eventTable() + " GROUP BY aggregate_id) e " +
                                           "FULL JOIN " + tables.headTable() + " h USING (aggregate_id) WHERE h.version IS DISTINCT FROM e.m");
        org.assertj.core.api.Assertions.assertThat(mismatches).as("streams whose head differs from max(event_order)").isZero();
    }

    /** Appends in its own committed transaction */
    protected List<AppendResult> appendCommitted(EventLog eventLog, AppendRequest... requests) throws SQLException {
        try (var connection = dataSource.getConnection()) {
            connection.setAutoCommit(false);
            try {
                var results = eventLog.append(connection, List.of(requests));
                connection.commit();
                return results;
            } catch (SQLException | RuntimeException e) {
                connection.rollback();
                throw e;
            }
        }
    }
}
