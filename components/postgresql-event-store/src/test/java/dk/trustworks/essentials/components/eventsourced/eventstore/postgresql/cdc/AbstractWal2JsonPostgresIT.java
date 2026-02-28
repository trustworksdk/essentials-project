/*
 *  Copyright 2021-2026 the original author or authors.
 *
 *  Licensed under the Apache License, Version 2.0 (the "License");
 *  you may not use this file except in compliance with the License.
 *  You may obtain a copy of the License at
 *
 *       https://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 */

package dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.cdc;

import dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.eventstream.AggregateType;
import dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.transaction.EventStoreManagedUnitOfWorkFactory;
import dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.types.GlobalEventOrder;
import dk.trustworks.essentials.components.foundation.postgresql.*;
import org.jdbi.v3.core.Jdbi;
import org.jdbi.v3.postgres.PostgresPlugin;
import org.junit.jupiter.api.BeforeEach;
import org.postgresql.ds.PGSimpleDataSource;
import org.postgresql.util.PSQLException;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.images.builder.ImageFromDockerfile;
import org.testcontainers.junit.jupiter.*;

import javax.sql.DataSource;
import java.sql.*;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Abstract test class providing an integration testing framework for PostgreSQL databases
 * configured with logical replication and the wal2json output plugin.
 * <p>
 * This class utilizes Testcontainers to manage a PostgreSQL Docker container instance
 * with appropriate configurations to enable logical decoding and wal2json functionality for testing.
 * It is designed to be extended by specific integration test implementations that validate
 * behavior related to Change Data Capture (CDC) or event sourcing systems.
 * <p>
 * Responsibilities:
 * - Sets up and manages a PostgreSQL container with configurations needed for logical replication.
 * - Provides utilities to create and configure JDBI and PostgreSQL data source instances.
 * - Includes mechanisms to verify the readiness of the database and logical decoding capabilities.
 * - Offers helper methods and nested classes for testing CDC behavior, including poison notification handling.
 * <p>
 * Key Components:
 * - {@code @Testcontainers}: Annotation marking this class to leverage the Testcontainers framework.
 * - {@code GenericContainer<?> postgres}: A Testcontainers-managed PostgreSQL container pre-configured for testing.
 * - {@code Jdbi jdbi}: JDBI instance configured for interaction with the PostgreSQL database.
 * - {@code DataSource replicationDataSource}: Data source configured for replication purposes.
 * - {@code EventStoreManagedUnitOfWorkFactory unitOfWorkFactory}: Factory for creating managed unit-of-work instances.
 * <p>
 * Utility Methods:
 * - {@code baseSetup()}: Prepares database connection properties, initializes JDBI, and verifies logical decoding.
 * - {@code waitForPrimaryConnectionsReady()}: Waits for the PostgreSQL instance to be ready to accept connections.
 * - {@code replicationDataSource(String host, int port, String db, String user, String pass)}: Creates a data source instance with replication capabilities.
 * - {@code isDatabaseStartingUp(SQLException e)}: Determines if a database is in the process of starting up based on exception details.
 * <p>
 * Extension Guidelines:
 * - Extend this class and implement specific test cases for validating CDC scenarios or event-driven operations.
 * - Use {@code @BeforeEach} and {@code @AfterEach} annotations to set up test-specific configurations or cleanups.
 * - Leverage helper methods and utilities provided by this class to focus tests on application logic rather than infrastructure setup.
 */
@Testcontainers
public class AbstractWal2JsonPostgresIT {

    public static final AggregateType ORDERS = AggregateType.of("Orders");

    @Container
    protected final GenericContainer<?> postgres = new GenericContainer<>(
            new ImageFromDockerfile()
                    .withFileFromClasspath("Dockerfile", "docker/postgresql-wal2json/Dockerfile")
    )
            .withEnv("POSTGRES_DB", "event-store")
            .withEnv("POSTGRES_USER", "test-user")
            .withEnv("POSTGRES_PASSWORD", "secret-password")
            .withCommand("postgres",
                         "-c", "wal_level=logical",
                         "-c", "max_replication_slots=10",
                         "-c", "max_wal_senders=10"
                        )
            .withExposedPorts(5432)
            .waitingFor(Wait.forListeningPort().withStartupTimeout(Duration.ofSeconds(60)));

    protected Jdbi                               jdbi;
    protected DataSource                         replicationDataSource;
    protected EventStoreManagedUnitOfWorkFactory unitOfWorkFactory;

    protected String jdbcUrl;
    protected String host;
    protected int port;
    protected String db;
    protected String user;
    protected String pass;

    @BeforeEach
    void baseSetup() throws SQLException {
        host = postgres.getHost();
        port = postgres.getMappedPort(5432);
        db   = "event-store";
        user = "test-user";
        pass = "secret-password";

        jdbcUrl = "jdbc:postgresql://" + host + ":" + port + "/" + db;

        jdbi = Jdbi.create(jdbcUrl, user, pass);
        jdbi.installPlugin(new PostgresPlugin());
        jdbi.setSqlLogger(new SqlExecutionTimeLogger());

        unitOfWorkFactory = new EventStoreManagedUnitOfWorkFactory(jdbi);

        replicationDataSource = replicationDataSource(host, port, db, user, pass);
        waitForPrimaryConnectionsReady();

        // Fail-fast sanity checks (match tailer startup semantics)
        unitOfWorkFactory.usingUnitOfWork(uow -> {
            boolean logicalOk = PostgresqlUtil.isLogicalDecodingEnabled(uow.handle());
            if (!logicalOk) throw new IllegalStateException("Logical decoding not enabled");

            boolean usable = PostgresqlUtil.isOutputPluginUsable(uow.handle(), "wal2json");
            if (!usable) throw new IllegalStateException("wal2json output plugin not usable");
        });
    }

    private void waitForPrimaryConnectionsReady() throws SQLException {
        SQLException last = null;
        long deadlineMs = System.currentTimeMillis() + 30_000;
        while (System.currentTimeMillis() < deadlineMs) {
            try (var connection = DriverManager.getConnection(jdbcUrl, user, pass);
                 var statement = connection.createStatement()) {
                statement.execute("select 1");
                return;
            } catch (SQLException e) {
                last = e;
                if (!isDatabaseStartingUp(e)) {
                    throw e;
                }
                try {
                    Thread.sleep(250);
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    throw new SQLException("Interrupted while waiting for PostgreSQL to become ready", interrupted);
                }
            }
        }
        throw new SQLException("Timed out waiting for PostgreSQL to accept primary connections", last);
    }

    private static boolean isDatabaseStartingUp(SQLException e) {
        if (e instanceof PSQLException psqle && "57P03".equals(psqle.getSQLState())) {
            return true;
        }
        Throwable cause = e.getCause();
        while (cause != null) {
            if (cause instanceof PSQLException psqle && "57P03".equals(psqle.getSQLState())) {
                return true;
            }
            cause = cause.getCause();
        }
        return false;
    }

    protected static DataSource replicationDataSource(String host, int port, String db, String user, String pass) throws SQLException {
        var ds = new PGSimpleDataSource();
        ds.setServerNames(new String[]{host});
        ds.setPortNumbers(new int[]{port});
        ds.setDatabaseName(db);
        ds.setUser(user);
        ds.setPassword(pass);

        ds.setProperty("replication", "database");
        ds.setProperty("preferQueryMode", "simple");
        ds.setProperty("assumeMinServerVersion", "17");
        return ds;
    }

    final class RecordingPoisonNotifier implements CdcPoisonNotifier {
        record Call(AggregateType aggregateType, List<GlobalEventOrder> gaps, String reason) {}
        final List<Call> calls = new CopyOnWriteArrayList<>();

        @Override
        public void onPoison(AggregateType aggregateType, List<GlobalEventOrder> gaps, String reason) {
            calls.add(new Call(aggregateType, List.copyOf(gaps), reason));
        }
    }

}
