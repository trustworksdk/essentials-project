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

import dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.*;
import dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.cdc.CdcInboxRepository;
import dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.eventstream.*;
import dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.gap.PostgresqlEventStreamGapHandler;
import dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.persistence.PersistableEvent;
import dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.persistence.table_per_aggregate_type.*;
import dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.serializer.AggregateIdSerializer;
import dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.serializer.json.EssentialsJSONEventSerializers;
import dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.subscription.PostgresqlDurableSubscriptionRepository;
import dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.transaction.*;
import dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.types.EventTypeOrName;
import dk.trustworks.essentials.components.foundation.postgresql.PostgresqlCreateSchemaApplier;
import dk.trustworks.essentials.components.foundation.schema.*;
import dk.trustworks.essentials.components.foundation.test.EssentialsTestContainers;
import dk.trustworks.essentials.components.foundation.test.schema.SchemaReapplyLockProbe;
import org.awaitility.Awaitility;
import org.jdbi.v3.core.Jdbi;
import org.jdbi.v3.postgres.PostgresPlugin;
import org.junit.jupiter.api.*;
import org.testcontainers.junit.jupiter.*;
import org.testcontainers.postgresql.PostgreSQLContainer;

import java.time.Duration;
import java.util.concurrent.*;

import static dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.persistence.table_per_aggregate_type.SeparateTablePerAggregateTypeEventStreamConfigurationFactory.standardSingleTenantConfiguration;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * Every instance re-applies the event store's schema when it starts, while the instances already running append
 * events. The event-stream tables of every configured aggregate type are one schema change set - one transaction - and
 * {@code CREATE INDEX IF NOT EXISTS} takes a {@code SHARE} lock on its table even when the index exists. A re-apply
 * therefore held {@code SHARE} on the first table while it waited for the next one, and a running unit of work that had
 * appended to the second aggregate type and then appended to the first closed a lock cycle: PostgreSQL aborted one
 * side with "deadlock detected" - the starting instance, or the running instance's append.
 */
@Testcontainers
class EventStoreSchemaReapplyConcurrencyIT {
    private static final AggregateType ORDERS    = AggregateType.of("Orders");
    private static final AggregateType SHIPMENTS = AggregateType.of("Shipments");

    @Container
    private static final PostgreSQLContainer postgreSQLContainer = EssentialsTestContainers.postgres("event-store-schema-reapply-db");

    private Jdbi                                                                    jdbi;
    private EventStoreManagedUnitOfWorkFactory                                      unitOfWorkFactory;
    private SeparateTablePerAggregateTypePersistenceStrategy                        persistenceStrategy;
    private PostgresqlEventStore<SeparateTablePerAggregateEventStreamConfiguration> eventStore;
    private ExecutorService                                                         executor;

    @BeforeEach
    void setUp() {
        jdbi = Jdbi.create(postgreSQLContainer.getJdbcUrl(), postgreSQLContainer.getUsername(), postgreSQLContainer.getPassword());
        jdbi.installPlugin(new PostgresPlugin());
        // The container is shared by the class, so start every test from an empty database
        jdbi.useHandle(handle -> handle.execute("DROP SCHEMA public CASCADE; CREATE SCHEMA public"));

        unitOfWorkFactory = new EventStoreManagedUnitOfWorkFactory(jdbi);
        persistenceStrategy = SeparateTablePerAggregateTypePersistenceStrategy.builder()
                                                                              .setJdbi(jdbi)
                                                                              .setUnitOfWorkFactory(unitOfWorkFactory)
                                                                              .setEventMapper((aggregateId, configuration, event, eventOrder) ->
                                                                                                      PersistableEvent.builder()
                                                                                                                      .setEvent(event)
                                                                                                                      .setAggregateType(configuration.aggregateType)
                                                                                                                      .setAggregateId(aggregateId)
                                                                                                                      .setEventTypeOrName(EventTypeOrName.with(event.getClass()))
                                                                                                                      .setEventOrder(eventOrder)
                                                                                                                      .build())
                                                                              .setAggregateEventStreamConfigurationFactory(standardSingleTenantConfiguration(
                                                                                      EssentialsJSONEventSerializers.create(),
                                                                                      IdentifierColumnType.TEXT,
                                                                                      JSONColumnType.JSONB))
                                                                              .build();
        eventStore = new PostgresqlEventStore<>(unitOfWorkFactory, persistenceStrategy);
        eventStore.addAggregateEventStreamConfiguration(ORDERS, AggregateIdSerializer.serializerFor(String.class));
        eventStore.addAggregateEventStreamConfiguration(SHIPMENTS, AggregateIdSerializer.serializerFor(String.class));
        executor = Executors.newSingleThreadExecutor();
    }

    @AfterEach
    void tearDown() {
        if (executor != null) {
            executor.shutdownNow();
        }
    }

    @Test
    void reapplying_the_event_stream_schema_does_not_deadlock_with_a_unit_of_work_appending_to_two_aggregate_types() throws Exception {
        var tablesInApplyOrder = persistenceStrategy.contribute(SchemaContext.empty())
                                                    .stream()
                                                    .map(SchemaChange::objectName)
                                                    .distinct()
                                                    .toList();
        assertThat(tablesInApplyOrder).hasSize(2);
        var firstTable  = tablesInApplyOrder.get(0);
        var secondTable = tablesInApplyOrder.get(1);

        try (var runningInstance = jdbi.open()) {
            runningInstance.begin();
            // ROW EXCLUSIVE is what an append's INSERT takes: this unit of work has appended to the second aggregate type
            runningInstance.execute("LOCK TABLE " + secondTable + " IN ROW EXCLUSIVE MODE");

            var reapply = executor.submit(() -> PostgresqlCreateSchemaApplier.applyOwnSchema(jdbi, persistenceStrategy));
            // Before the fix the re-apply stops here holding SHARE on the first table and waiting for SHARE on the second
            Awaitility.await()
                      .atMost(Duration.ofSeconds(30))
                      .until(() -> reapply.isDone() || someoneWaitsForALockOn(secondTable));

            // ... and now appends to the first aggregate type
            runningInstance.execute("LOCK TABLE " + firstTable + " IN ROW EXCLUSIVE MODE");
            runningInstance.commit();

            reapply.get(30, TimeUnit.SECONDS);
        }
    }

    @Test
    void reapplying_the_event_stream_schema_does_not_wait_for_appends_in_flight() {
        SchemaReapplyLockProbe.assertReapplyDoesNotWaitForWritersOnItsTables(jdbi, persistenceStrategy);
    }

    @Test
    void reapplying_the_durable_subscriptions_schema_does_not_wait_for_resume_points_being_saved() {
        SchemaReapplyLockProbe.assertReapplyDoesNotWaitForWritersOnItsTables(jdbi, new PostgresqlDurableSubscriptionRepository(jdbi, eventStore));
    }

    @Test
    void reapplying_the_gap_handler_schema_does_not_wait_for_gaps_being_recorded() {
        SchemaReapplyLockProbe.assertReapplyDoesNotWaitForWritersOnItsTables(jdbi, new PostgresqlEventStreamGapHandler<>(unitOfWorkFactory));
    }

    @Test
    void reapplying_the_cdc_inbox_schema_does_not_wait_for_rows_being_staged() {
        SchemaReapplyLockProbe.assertReapplyDoesNotWaitForWritersOnItsTables(jdbi, new CdcInboxRepository(unitOfWorkFactory));
    }

    private boolean someoneWaitsForALockOn(String table) {
        return jdbi.withHandle(handle -> handle.createQuery("SELECT EXISTS (SELECT 1 FROM pg_locks WHERE relation = to_regclass(:table) AND NOT granted)")
                                               .bind("table", table)
                                               .mapTo(Boolean.class)
                                               .one());
    }
}
