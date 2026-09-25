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
package dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.schema;

import dk.trustworks.essentials.components.distributed.fencedlock.postgresql.PostgresqlFencedLockStorage;
import dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.EventStore;
import dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.cdc.CdcInboxRepository;
import dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.eventstream.*;
import dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.gap.*;
import dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.persistence.table_per_aggregate_type.*;
import dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.serializer.AggregateIdSerializer;
import dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.serializer.json.EssentialsJSONEventSerializers;
import dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.subscription.PostgresqlDurableSubscriptionRepository;
import dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.test_data.OrderId;
import dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.transaction.*;
import dk.trustworks.essentials.components.foundation.postgresql.*;
import dk.trustworks.essentials.components.foundation.postgresql.ttl.PostgresqlTTLManager;
import dk.trustworks.essentials.components.foundation.scheduler.EssentialsScheduler;
import dk.trustworks.essentials.components.foundation.scheduler.executor.ExecutorScheduledJobRepository;
import dk.trustworks.essentials.components.foundation.schema.*;
import dk.trustworks.essentials.components.queue.postgresql.PostgresqlDurableQueues;
import org.jdbi.v3.core.Jdbi;
import org.jdbi.v3.postgres.PostgresPlugin;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.*;

import java.nio.file.*;
import java.time.Duration;
import java.util.*;

import static dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.persistence.table_per_aggregate_type.SeparateTablePerAggregateTypeEventStreamConfigurationFactory.standardSingleTenantConfiguration;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.mock;

/**
 * Every PostgreSQL contributor the event store's classpath reaches, through the emit path: the script they render is
 * run as a DBA would run it - emit itself needs no database - after which the validate mode passes and the create mode finds nothing to record that
 * the script did not. Guards that every real statement - PL/pgSQL bodies, DO blocks, triggers - survives being
 * written out as a script.
 */
@Testcontainers
class EssentialsSchemaScriptRoundTripIT {
    @Container
    private final PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:18.4")
            .withDatabaseName("schema-round-trip")
            .withUsername("test-user")
            .withPassword("secret-password");

    @TempDir
    Path tempDir;

    private Jdbi                                              jdbi;
    private EventStoreUnitOfWorkFactory<EventStoreUnitOfWork> unitOfWorkFactory;
    private List<EssentialsSchemaContributor>                 contributors;

    @BeforeEach
    void setUp() {
        jdbi = Jdbi.create(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
        jdbi.installPlugin(new PostgresPlugin());
        unitOfWorkFactory = new EventStoreManagedUnitOfWorkFactory(jdbi);

        var strategy = SeparateTablePerAggregateTypePersistenceStrategy.builder()
                                                                       .setJdbi(jdbi)
                                                                       .setUnitOfWorkFactory(unitOfWorkFactory)
                                                                       .setEventMapper((aggregateId, configuration, event, eventOrder) -> {
                                                                           throw new UnsupportedOperationException("no events are persisted");
                                                                       })
                                                                       .setAggregateEventStreamConfigurationFactory(standardSingleTenantConfiguration(aggregateType -> aggregateType + "_events",
                                                                                                                                                      EventStreamTableColumnNames.defaultColumnNames(),
                                                                                                                                                      EssentialsJSONEventSerializers.create(),
                                                                                                                                                      IdentifierColumnType.UUID,
                                                                                                                                                      JSONColumnType.JSONB))
                                                                       .setSchemaOwnership(SchemaOwnership.HARNESS)
                                                                       .build();
        strategy.addAggregateEventStreamConfiguration(AggregateType.of("Orders"), AggregateIdSerializer.serializerFor(OrderId.class));
        strategy.enableNotifyTriggers(tableName -> {});

        contributors = List.of(new ExecutorScheduledJobRepository(unitOfWorkFactory, ExecutorScheduledJobRepository.DEFAULT_SCHEDULED_JOBS_TABLE_NAME, SchemaOwnership.HARNESS),
                               new PostgresqlTTLManager(mock(EssentialsScheduler.class), unitOfWorkFactory, SchemaOwnership.HARNESS),
                               new PostgresqlFencedLockStorage(jdbi, PostgresqlFencedLockStorage.DEFAULT_FENCED_LOCKS_TABLE_NAME, SchemaOwnership.HARNESS),
                               PostgresqlDurableQueues.builder().setUnitOfWorkFactory(unitOfWorkFactory).setSchemaOwnership(SchemaOwnership.HARNESS).build(),
                               CdcInboxRepository.builder().setUnitOfWorkFactory(unitOfWorkFactory).setSchemaOwnership(SchemaOwnership.HARNESS).build(),
                               new PostgresqlDurableSubscriptionRepository(jdbi, mock(EventStore.class),
                                                                           PostgresqlDurableSubscriptionRepository.DEFAULT_DURABLE_SUBSCRIPTIONS_TABLE_NAME,
                                                                           SchemaOwnership.HARNESS),
                               new PostgresqlEventStreamGapHandler<>(unitOfWorkFactory,
                                                                     Duration.ofSeconds(60),
                                                                     mock(PostgresqlEventStreamGapHandler.ResolveTransientGapsToIncludeInQueryStrategy.class),
                                                                     mock(PostgresqlEventStreamGapHandler.ResolveTransientGapsToPermanentGapsPromotionStrategy.class),
                                                                     SchemaOwnership.HARNESS),
                               strategy);
    }

    @Test
    void the_emitted_script_creates_what_validate_then_accepts() throws Exception {
        var scriptFile = tempDir.resolve("essentials.sql");
        harness(new PostgresqlEmitSchemaApplier(scriptFile)).apply();
        assertThat(exists("orders_events")).as("emit executes nothing").isFalse();
        assertThatThrownBy(() -> harness(new PostgresqlValidateSchemaApplier(jdbi)).apply()).isInstanceOf(SchemaValidationException.class);

        run(Files.readString(scriptFile));

        harness(new PostgresqlValidateSchemaApplier(jdbi)).apply();
        assertThat(exists("orders_events")).isTrue();
        assertThat(exists("durable_queues")).isTrue();
        assertThat(triggerExists("notify_on_orders_events_changes")).isTrue();
    }

    @Test
    void the_script_records_exactly_the_ledger_the_create_mode_records() throws Exception {
        var scriptFile = tempDir.resolve("essentials.sql");
        harness(new PostgresqlEmitSchemaApplier(scriptFile)).apply();
        var script = Files.readString(scriptFile);
        run(script);
        run(script);
        var fromScript = ledger();

        harness(new PostgresqlCreateSchemaApplier(jdbi)).apply();

        assertThat(ledger()).as("the create mode, run after the script, changes no checksum and adds no row").isEqualTo(fromScript);
    }

    private EssentialsSchemaHarness harness(SchemaApplier applier) {
        return new EssentialsSchemaHarness(applier, SchemaContext.empty(), contributors);
    }

    private void run(String script) {
        jdbi.useHandle(handle -> {
            try (var statement = handle.getConnection().createStatement()) {
                statement.execute(script);
            } catch (java.sql.SQLException e) {
                throw new IllegalStateException(e);
            }
        });
    }

    private boolean exists(String relation) {
        return jdbi.withHandle(handle -> handle.createQuery("SELECT to_regclass(:name) IS NOT NULL").bind("name", relation).mapTo(Boolean.class).one());
    }

    private boolean triggerExists(String trigger) {
        return jdbi.withHandle(handle -> handle.createQuery("SELECT count(*) > 0 FROM pg_trigger WHERE tgname = :name").bind("name", trigger).mapTo(Boolean.class).one());
    }

    private Map<String, String> ledger() {
        var result = new TreeMap<String, String>();
        jdbi.useHandle(handle -> handle.createQuery("SELECT module_id || '/' || change_id || '/' || object_name AS key, checksum FROM essentials_schema_history")
                                       .map((rs, ctx) -> Map.entry(rs.getString("key"), rs.getString("checksum")))
                                       .forEach(entry -> result.put(entry.getKey(), entry.getValue())));
        return result;
    }
}
