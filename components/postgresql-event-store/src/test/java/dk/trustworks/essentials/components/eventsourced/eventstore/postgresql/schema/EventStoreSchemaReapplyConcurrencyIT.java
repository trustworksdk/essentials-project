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
import java.util.*;
import java.util.concurrent.*;

import static dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.persistence.table_per_aggregate_type.SeparateTablePerAggregateTypeEventStreamConfigurationFactory.standardSingleTenantConfiguration;
import static org.assertj.core.api.Assertions.*;

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

    /**
     * The statements a re-apply runs against an existing schema - with the optional causation index and notify triggers
     * enabled too - each executed in one transaction, as the applier does, and the relation locks that transaction holds
     * on the event-stream tables read from {@code pg_locks} after each one. None may be held: a lock on one table held
     * while the next table's statements run is what a unit of work appending in the opposite order could deadlock with.
     * The raw {@code CREATE INDEX IF NOT EXISTS} at the end shows the observation does see a lock where one is taken.
     */
    @Test
    void a_reapply_takes_no_lock_on_any_event_stream_table_even_with_causation_index_and_notify_triggers() {
        persistenceStrategy.enableCausationIndex();
        persistenceStrategy.enableNotifyTriggers(tableName -> { });
        PostgresqlCreateSchemaApplier.applyOwnSchema(jdbi, persistenceStrategy);
        var changes = persistenceStrategy.contribute(SchemaContext.empty());
        var tables  = changes.stream().map(SchemaChange::objectName).distinct().toList();
        assertThat(tables).hasSize(2);
        assertThat(changes).extracting(SchemaChange::changeId)
                           .contains("event-stream-table", "event-stream-tenant-index", "event-stream-caused-by-index", "change-notification-trigger");

        var locksAfterStatement = new LinkedHashMap<String, List<String>>();
        jdbi.useTransaction(handle -> {
            for (var change : changes) {
                for (var statement : change.statements()) {
                    handle.execute(statement);
                    locksAfterStatement.put(change.changeId() + " on " + change.objectName() + " #" + locksAfterStatement.size(),
                                            relationLocksHeldOn(handle, tables));
                }
            }
            assertThat(locksAfterStatement).allSatisfy((statement, locks) -> assertThat(locks).as(statement).isEmpty());

            handle.execute("CREATE INDEX IF NOT EXISTS " + tables.get(0) + "_lock_probe_idx ON " + tables.get(0) + " (event_order)");
            assertThat(relationLocksHeldOn(handle, tables)).containsExactly(tables.get(0) + " ShareLock");
            handle.rollback();
        });
    }

    /**
     * Through the event store's own appends, in both orders, with every optional change enabled: a unit of work appends
     * to one aggregate type, an instance starts and re-applies the schema, then the unit of work appends to the other
     * aggregate type and commits. The re-apply must finish while the unit of work still holds its first append - it
     * takes no lock on an event-stream table, so there is nothing to close a cycle with. Only asserting that both sides
     * succeed is not enough: when the re-apply did lock the tables, PostgreSQL aborted it as the deadlock victim and
     * the applier's retry on deadlock hid that.
     */
    @Test
    void reapplying_the_event_stream_schema_does_not_wait_for_a_unit_of_work_appending_to_two_aggregate_types_in_either_order() throws Exception {
        persistenceStrategy.enableCausationIndex();
        persistenceStrategy.enableNotifyTriggers(tableName -> { });
        PostgresqlCreateSchemaApplier.applyOwnSchema(jdbi, persistenceStrategy);

        appendToBothWhileTheSchemaIsReapplied(ORDERS, SHIPMENTS, "1");
        appendToBothWhileTheSchemaIsReapplied(SHIPMENTS, ORDERS, "2");

        unitOfWorkFactory.usingUnitOfWork(() -> {
            assertThat(eventStore.fetchStream(ORDERS, "1")).isPresent();
            assertThat(eventStore.fetchStream(SHIPMENTS, "1")).isPresent();
            assertThat(eventStore.fetchStream(ORDERS, "2")).isPresent();
            assertThat(eventStore.fetchStream(SHIPMENTS, "2")).isPresent();
        });
    }

    private void appendToBothWhileTheSchemaIsReapplied(AggregateType appendedFirst, AggregateType appendedSecond, String aggregateId) throws Exception {
        var appender           = Executors.newSingleThreadExecutor();
        var firstAppendDone    = new CountDownLatch(1);
        var mayAppendTheSecond = new CountDownLatch(1);
        try {
            var unitOfWork = appender.submit(() -> unitOfWorkFactory.usingUnitOfWork(() -> {
                eventStore.appendToStream(appendedFirst, aggregateId, new SomethingHappened(appendedFirst + "-" + aggregateId));
                firstAppendDone.countDown();
                assertThat(mayAppendTheSecond.await(30, TimeUnit.SECONDS)).isTrue();
                eventStore.appendToStream(appendedSecond, aggregateId, new SomethingHappened(appendedSecond + "-" + aggregateId));
            }));
            assertThat(firstAppendDone.await(30, TimeUnit.SECONDS)).isTrue();

            var reapply = executor.submit(() -> PostgresqlCreateSchemaApplier.applyOwnSchema(jdbi, persistenceStrategy));
            try {
                reapply.get(10, TimeUnit.SECONDS);
            } catch (TimeoutException e) {
                fail("The re-apply waited for the unit of work that appended to " + appendedFirst + " - waiting for: " + locksWaitedFor());
            }
            mayAppendTheSecond.countDown();

            unitOfWork.get(30, TimeUnit.SECONDS);
        } finally {
            mayAppendTheSecond.countDown();
            appender.shutdownNow();
        }
    }

    public record SomethingHappened(String what) {
    }

    private static List<String> relationLocksHeldOn(org.jdbi.v3.core.Handle handle, List<String> tables) {
        return handle.createQuery("SELECT relation::regclass::text || ' ' || mode FROM pg_locks " +
                                          "WHERE pid = pg_backend_pid() AND locktype = 'relation' AND relation::regclass::text = ANY(:tables) ORDER BY 1")
                     .bindArray("tables", String.class, tables)
                     .mapTo(String.class)
                     .list();
    }

    private List<String> locksWaitedFor() {
        return jdbi.withHandle(handle -> handle.createQuery("SELECT relation::regclass::text || ' (' || mode || ')' FROM pg_locks WHERE NOT granted AND relation IS NOT NULL")
                                               .mapTo(String.class)
                                               .list());
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
