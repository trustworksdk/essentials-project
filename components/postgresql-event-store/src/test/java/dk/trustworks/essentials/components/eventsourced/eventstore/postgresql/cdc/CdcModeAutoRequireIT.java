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

import dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.cdc.CdcProperties.WalReplicationTailerProperties;
import dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.cdc.converter.LogicalReplicationToPersistedEventConverter;
import dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.cdc.converter.PgOutputToPersistedEventConverter;
import dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.cdc.converter.WalGlobalOrdersExtractor;
import dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.processor.EventProcessorIT;
import dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.serializer.json.*;
import dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.transaction.EventStoreManagedUnitOfWorkFactory;
import dk.trustworks.essentials.components.foundation.transaction.jdbi.HandleAwareUnitOfWork;
import dk.trustworks.essentials.components.foundation.transaction.jdbi.HandleAwareUnitOfWorkFactory;
import org.jdbi.v3.core.Jdbi;
import org.jdbi.v3.postgres.PostgresPlugin;
import org.junit.jupiter.api.*;
import org.postgresql.ds.PGSimpleDataSource;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.images.builder.ImageFromDockerfile;
import org.testcontainers.junit.jupiter.*;

import javax.sql.DataSource;
import java.sql.SQLException;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.*;

@Testcontainers
public class CdcModeAutoRequireIT {

    private static final String DB          = "event-store";
    private static final String ADMIN_USER  = "test-user";
    private static final String ADMIN_PASS  = "secret-password";
    private static final String LIMITED_USER = "limited-user";
    private static final String LIMITED_PASS = "limited-password";

    @Container
    protected final GenericContainer<?> postgres = new GenericContainer<>(
            new ImageFromDockerfile()
                    .withFileFromClasspath("Dockerfile", "docker/postgresql-wal2json/Dockerfile")
    )
            .withEnv("POSTGRES_DB", DB)
            .withEnv("POSTGRES_USER", ADMIN_USER)
            .withEnv("POSTGRES_PASSWORD", ADMIN_PASS)
            .withCommand("postgres",
                         "-c", "wal_level=logical",
                         "-c", "max_replication_slots=10",
                         "-c", "max_wal_senders=10"
                        )
            .withExposedPorts(5432)
            .waitingFor(Wait.forListeningPort().withStartupTimeout(Duration.ofSeconds(60)));

    private Jdbi                               adminJdbi;
    private Jdbi                               limitedJdbi;
    private EventStoreManagedUnitOfWorkFactory unitOfWorkFactory;
    private DataSource                         replicationDataSource;
    private DataSource                         adminReplicationDataSource;
    private CdcInboxRepository                 inboxRepository;

    @BeforeEach
    void setup() throws SQLException {
        String host = postgres.getHost();
        int    port = postgres.getMappedPort(5432);
        String jdbcUrl = "jdbc:postgresql://" + host + ":" + port + "/" + DB;

        adminJdbi = Jdbi.create(jdbcUrl, ADMIN_USER, ADMIN_PASS);
        adminJdbi.installPlugin(new PostgresPlugin());

        adminJdbi.useHandle(h -> {
            h.execute("drop role if exists \"" + LIMITED_USER + "\"");
            h.execute("create role \"" + LIMITED_USER + "\" login password '" + LIMITED_PASS + "'");
            h.execute("grant connect on database \"" + DB + "\" to \"" + LIMITED_USER + "\"");
            h.execute("grant usage, create on schema public to \"" + LIMITED_USER + "\"");
            h.execute("grant select, insert, update, delete on all tables in schema public to \"" + LIMITED_USER + "\"");
            h.execute("alter default privileges in schema public grant select, insert, update, delete on tables to \"" + LIMITED_USER + "\"");
        });

        limitedJdbi = Jdbi.create(jdbcUrl, LIMITED_USER, LIMITED_PASS);
        limitedJdbi.installPlugin(new PostgresPlugin());

        unitOfWorkFactory = new EventStoreManagedUnitOfWorkFactory(limitedJdbi);
        replicationDataSource = replicationDataSource(host, port, DB, LIMITED_USER, LIMITED_PASS);
        adminReplicationDataSource = replicationDataSource(host, port, DB, ADMIN_USER, ADMIN_PASS);

        adminJdbi.useHandle(h -> h.execute("create table if not exists pgoutput_mode_test_events (id bigserial primary key, payload text not null, created_at timestamptz not null default now())"));

        inboxRepository = new CdcInboxRepository(unitOfWorkFactory);
    }

    @AfterEach
    void cleanup() {
        unitOfWorkFactory.getCurrentUnitOfWork().ifPresent(uow -> uow.rollback(new RuntimeException("test-cleanup")));
        assertThat(unitOfWorkFactory.getCurrentUnitOfWork()).isEmpty();
    }

    @Test
    void auto_mode_does_not_start_when_wal2json_unusable() {
        String slotName = "slot_" + UUID.randomUUID().toString().replace("-", "");
        var availability = new CdcAvailability();
        var tailer = wal2JsonInboxTailer(slotName, availability, CdcMode.AUTO, limitedJdbi, unitOfWorkFactory, replicationDataSource);

        tailer.start();

        assertThat(tailer.isStarted()).isFalse();
        assertThat(availability.getState()).isEqualTo(CdcAvailability.State.FAILED);
    }

    @Test
    void require_mode_throws_when_wal2json_unusable() {
        String slotName = "slot_" + UUID.randomUUID().toString().replace("-", "");
        var availability = new CdcAvailability();
        var tailer = wal2JsonInboxTailer(slotName, availability, CdcMode.REQUIRE, limitedJdbi, unitOfWorkFactory, replicationDataSource);

        assertThatThrownBy(tailer::start)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("required");
    }

    @Test
    void auto_mode_starts_when_pgoutput_is_configured_and_publication_exists() {
        String slotName = "slot_" + UUID.randomUUID().toString().replace("-", "");
        String publicationName = publicationName();
        createPublication(publicationName);

        var availability = new CdcAvailability();
        var tailer = pgOutputDirectTailer(slotName, publicationName, availability, CdcMode.AUTO);

        tailer.startAndAwaitReady(Duration.ofSeconds(10));

        assertThat(tailer.isStarted()).isTrue();
        assertThat(availability.getState()).isEqualTo(CdcAvailability.State.ACTIVE);

        tailer.stop();
    }

    @Test
    void require_mode_starts_when_pgoutput_is_configured_and_publication_exists() {
        String slotName = "slot_" + UUID.randomUUID().toString().replace("-", "");
        String publicationName = publicationName();
        createPublication(publicationName);

        var availability = new CdcAvailability();
        var tailer = pgOutputDirectTailer(slotName, publicationName, availability, CdcMode.REQUIRE);

        tailer.startAndAwaitReady(Duration.ofSeconds(10));

        assertThat(tailer.isStarted()).isTrue();
        assertThat(availability.getState()).isEqualTo(CdcAvailability.State.ACTIVE);

        tailer.stop();
    }

    @Test
    void auto_mode_does_not_start_when_pgoutput_publication_is_missing() {
        String slotName = "slot_" + UUID.randomUUID().toString().replace("-", "");
        String publicationName = publicationName();

        var availability = new CdcAvailability();
        var tailer = pgOutputDirectTailer(slotName, publicationName, availability, CdcMode.AUTO);

        tailer.start();

        assertThat(tailer.isStarted()).isFalse();
        assertThat(availability.getState()).isEqualTo(CdcAvailability.State.FAILED);
        assertThat(availability.snapshot().reason()).contains("publication").contains("does not exist");
    }

    @Test
    void require_mode_throws_when_pgoutput_publication_is_missing() {
        String slotName = "slot_" + UUID.randomUUID().toString().replace("-", "");
        String publicationName = publicationName();

        var availability = new CdcAvailability();
        var tailer = pgOutputDirectTailer(slotName, publicationName, availability, CdcMode.REQUIRE);

        assertThatThrownBy(tailer::start)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("required");
        assertThat(availability.snapshot().reason()).contains("publication").contains("does not exist");
    }

    private void createPublication(String publicationName) {
        adminJdbi.useHandle(handle -> {
            handle.execute("drop publication if exists " + publicationName);
            handle.execute("create publication " + publicationName + " for table pgoutput_mode_test_events");
        });
    }

    private static String publicationName() {
        return "pub_" + UUID.randomUUID().toString().replace("-", "").substring(0, 16);
    }

    private static WalReplicationTailerProperties tailerProps() {
        return WalReplicationTailerProperties.defaults(
                Duration.ofMillis(50), Duration.ofMillis(100), Duration.ofSeconds(2), Duration.ofMillis(100));
    }

    private WalReplicationTailer wal2JsonInboxTailer(String slotName,
                                                     CdcAvailability availability,
                                                     CdcMode mode,
                                                     Jdbi jdbi,
                                                     HandleAwareUnitOfWorkFactory<? extends HandleAwareUnitOfWork> uow,
                                                     DataSource ds) {
        var props = tailerProps();
        LogicalReplicationToPersistedEventConverter noConverter = (String s) -> List.of();
        WalGlobalOrdersExtractor noExtractor = (String s) -> List.of();
        var plugin = new Wal2JsonLogicalDecodingPlugin(props, noConverter, noExtractor, CdcProperties.WalParserMode.STRING);
        return new WalReplicationTailer(
                ds, jdbi, uow, slotName, inboxRepository, props,
                PgSlotMode.CREATE_IF_MISSING, mode, CdcProperties.CdcDeliveryMode.INBOX, plugin,
                Optional.empty(), Optional.empty(), availability,
                Optional.empty(), Optional.empty());
    }

    private WalReplicationTailer pgOutputDirectTailer(String slotName,
                                                      String publicationName,
                                                      CdcAvailability availability,
                                                      CdcMode mode) {
        var props = tailerProps();
        var pgConverter = new PgOutputToPersistedEventConverter(
                EssentialsJSONEventSerializers.createForActiveJacksonFlavor(),
                table -> null);
        return new WalReplicationTailer(
                adminReplicationDataSource, adminJdbi, new EventStoreManagedUnitOfWorkFactory(adminJdbi),
                slotName, inboxRepository, props,
                PgSlotMode.CREATE_IF_MISSING, mode, CdcProperties.CdcDeliveryMode.DIRECT,
                pgOutputPlugin(publicationName, pgConverter),
                Optional.of(events -> { }), Optional.empty(), availability,
                Optional.empty(), Optional.empty());
    }

    private static PgOutputLogicalDecodingPlugin pgOutputPlugin(String publicationName,
                                                                PgOutputToPersistedEventConverter converter) {
        var properties = new CdcProperties.PgOutputProperties();
        properties.setPublicationName(publicationName);
        properties.setProtoVersion(1);
        properties.setBinary(false);
        properties.setMessages(false);
        return new PgOutputLogicalDecodingPlugin(properties, converter);
    }

    private static DataSource replicationDataSource(String host, int port, String db, String user, String pass) throws SQLException {
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
}
