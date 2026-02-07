/*
 *  Copyright 2021-2025 the original author or authors.
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
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.images.builder.ImageFromDockerfile;
import org.testcontainers.junit.jupiter.*;

import javax.sql.DataSource;
import java.sql.SQLException;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

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

        // Fail-fast sanity checks (match tailer startup semantics)
        unitOfWorkFactory.usingUnitOfWork(uow -> {
            boolean logicalOk = PostgresqlUtil.isLogicalDecodingEnabled(uow.handle());
            if (!logicalOk) throw new IllegalStateException("Logical decoding not enabled");

            boolean usable = PostgresqlUtil.isOutputPluginUsable(uow.handle(), "wal2json");
            if (!usable) throw new IllegalStateException("wal2json output plugin not usable");
        });
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
