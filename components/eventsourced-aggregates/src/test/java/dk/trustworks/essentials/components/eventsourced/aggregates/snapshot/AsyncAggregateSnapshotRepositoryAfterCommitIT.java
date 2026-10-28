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

package dk.trustworks.essentials.components.eventsourced.aggregates.snapshot;

import dk.trustworks.essentials.components.eventsourced.aggregates.*;
import dk.trustworks.essentials.components.eventsourced.aggregates.modern.*;
import dk.trustworks.essentials.components.eventsourced.aggregates.modern.Order;
import dk.trustworks.essentials.components.eventsourced.aggregates.stateful.StatefulAggregateRepository;
import dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.PostgresqlEventStore;
import dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.eventstream.*;
import dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.persistence.table_per_aggregate_type.*;
import dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.serializer.json.EssentialsJSONEventSerializers;
import dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.transaction.EventStoreManagedUnitOfWorkFactory;
import dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.types.EventOrder;
import dk.trustworks.essentials.components.foundation.postgresql.SqlExecutionTimeLogger;
import dk.trustworks.essentials.components.foundation.transaction.UnitOfWork;
import org.jdbi.v3.core.Jdbi;
import org.jdbi.v3.postgres.PostgresPlugin;
import org.junit.jupiter.api.*;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.*;

import java.time.Duration;
import java.util.Optional;

import static dk.trustworks.essentials.components.eventsourced.aggregates.stateful.StatefulAggregateInstanceFactory.reflectionBasedAggregateRootFactory;
import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

/**
 * End-to-end check, on JDBI and Postgresql, that {@link AsyncAggregateSnapshotRepository} in
 * {@link SnapshotExecutionMode#ASYNC_IN_MEMORY} writes its snapshot only once the {@link UnitOfWork} that appended the events
 * has committed - through the {@code afterCommit} callback that
 * {@code GenericHandleAwareUnitOfWorkFactory} runs - and never when that unit of work rolls back.
 * <p>
 * The asynchronous snapshot write is registered as a lifecycle callback resource on the current unit of work and only handed
 * to the executor from {@code afterCommit}. Before that callback ran with this factory, no asynchronous snapshot was ever
 * written, so the commit cases below would time out.
 */
@Testcontainers
class AsyncAggregateSnapshotRepositoryAfterCommitIT {
    private static final AggregateType                  ORDERS            = AggregateType.of("Orders");
    private static final Duration                       SNAPSHOT_WAIT     = Duration.ofSeconds(5);
    private static final AsyncAggregateSnapshotSettings SNAPSHOT_SETTINGS = AsyncAggregateSnapshotSettings.asynchronous();

    /**
     * Shared by every test method. Each test works on its own random {@link OrderId}, so the event and snapshot rows left
     * behind by one test are invisible to the next and nothing needs resetting between them.
     */
    @Container
    private static final PostgreSQLContainer postgreSQLContainer = new PostgreSQLContainer("postgres:18.4").withDatabaseName("event-store")
                                                                                                                  .withUsername("test-user")
                                                                                                                  .withPassword("secret-password");

    private EventStoreManagedUnitOfWorkFactory                                      unitOfWorkFactory;
    private PostgresqlEventStore<SeparateTablePerAggregateEventStreamConfiguration> eventStore;
    private PostgresqlAggregateSnapshotStore                                        snapshotStore;
    private AsyncAggregateSnapshotRepository                                        snapshotRepository;

    @BeforeEach
    void setup() {
        var jdbi = Jdbi.create(postgreSQLContainer.getJdbcUrl(),
                               postgreSQLContainer.getUsername(),
                               postgreSQLContainer.getPassword());
        jdbi.installPlugin(new PostgresPlugin());
        jdbi.setSqlLogger(new SqlExecutionTimeLogger());

        unitOfWorkFactory = new EventStoreManagedUnitOfWorkFactory(jdbi);
        var aggregateEventStreamConfigurationFactory = SeparateTablePerAggregateTypeEventStreamConfigurationFactory.standardSingleTenantConfiguration(EssentialsJSONEventSerializers.create(),
                                                                                                                                                      IdentifierColumnType.UUID,
                                                                                                                                                      JSONColumnType.JSONB);
        eventStore = new PostgresqlEventStore<>(unitOfWorkFactory,
                                                new SeparateTablePerAggregateTypePersistenceStrategy(jdbi,
                                                                                                     unitOfWorkFactory,
                                                                                                     new TestPersistableEventMapper(),
                                                                                                     aggregateEventStreamConfigurationFactory));
        eventStore.addAggregateEventStreamConfiguration(ORDERS, OrderId.class);

        snapshotStore = new PostgresqlAggregateSnapshotStore(eventStore,
                                                             unitOfWorkFactory,
                                                             Optional.empty(),
                                                             aggregateEventStreamConfigurationFactory.jsonSerializer);
        snapshotRepository = AsyncAggregateSnapshotRepository.builder()
                                                             .setSnapshotStore(snapshotStore)
                                                             .setJsonSerializer(aggregateEventStreamConfigurationFactory.jsonSerializer)
                                                             .setAddNewSnapshotStrategy(AddNewAggregateSnapshotStrategy.updateOnEachAggregateUpdate())
                                                             .setSnapshotDeletionStrategy(AggregateSnapshotDeletionStrategy.keepAllHistoricSnapshots())
                                                             .setSettings(SNAPSHOT_SETTINGS)
                                                             .setUnitOfWorkFactory(unitOfWorkFactory)
                                                             .build();
        snapshotRepository.start();
    }

    @AfterEach
    void cleanup() {
        if (snapshotRepository != null) {
            snapshotRepository.stop();
        }
        unitOfWorkFactory.getCurrentUnitOfWork().ifPresent(UnitOfWork::rollback);
        assertThat(unitOfWorkFactory.getCurrentUnitOfWork()).isEmpty();
    }

    @Test
    void a_snapshot_is_written_after_the_unit_of_work_that_appended_the_events_commits() {
        var orderId = OrderId.random();
        var order   = new Order(orderId, CustomerId.random(), 1234);
        order.addProduct(ProductId.random(), 2);
        order.addProduct(ProductId.random(), 3);
        var eventsToPersist = order.getUncommittedChanges();
        order.markChangesAsCommitted();

        unitOfWorkFactory.usingUnitOfWork(() -> {
            var persistedEvents = eventStore.appendToStream(ORDERS,
                                                            eventsToPersist.aggregateId,
                                                            eventsToPersist.eventOrderOfLastRehydratedEvent,
                                                            eventsToPersist.events);
            snapshotRepository.aggregateUpdated(order, persistedEvents);
        });

        await().atMost(SNAPSHOT_WAIT)
               .untilAsserted(() -> {
                   var snapshot = snapshotStore.loadSnapshot(ORDERS, orderId, EventOrder.MAX_EVENT_ORDER, Order.class);
                   assertThat(snapshot).isPresent();
                   assertThat(snapshot.get().eventOrderOfLastIncludedEvent).isEqualTo(EventOrder.of(2));
               });
    }

    @Test
    void a_snapshot_is_written_after_a_stateful_aggregate_repository_commits_the_unit_of_work() {
        // The repository appends the events and calls aggregateUpdated(...) from its own beforeCommit callback, so the
        // snapshot's after-commit dispatch is registered while the commit's beforeCommit pass is running
        StatefulAggregateRepository<OrderId, OrderEvent, Order> ordersRepository = StatefulAggregateRepository.from(eventStore,
                                                                                                                    ORDERS,
                                                                                                                    reflectionBasedAggregateRootFactory(),
                                                                                                                    Order.class,
                                                                                                                    snapshotRepository);
        var orderId = OrderId.random();

        unitOfWorkFactory.usingUnitOfWork(() -> {
            var order = new Order(orderId, CustomerId.random(), 1234);
            order.addProduct(ProductId.random(), 2);
            ordersRepository.save(order);
        });

        await().atMost(SNAPSHOT_WAIT)
               .untilAsserted(() -> {
                   var snapshot = snapshotStore.loadSnapshot(ORDERS, orderId, EventOrder.MAX_EVENT_ORDER, Order.class);
                   assertThat(snapshot).isPresent();
                   assertThat(snapshot.get().eventOrderOfLastIncludedEvent).isEqualTo(EventOrder.of(1));
               });
    }

    @Test
    void no_snapshot_is_written_when_the_unit_of_work_that_appended_the_events_rolls_back() {
        var orderId = OrderId.random();
        var order   = new Order(orderId, CustomerId.random(), 1234);
        order.addProduct(ProductId.random(), 2);
        var eventsToPersist = order.getUncommittedChanges();
        order.markChangesAsCommitted();

        var unitOfWork = unitOfWorkFactory.getOrCreateNewUnitOfWork();
        var persistedEvents = eventStore.appendToStream(ORDERS,
                                                        eventsToPersist.aggregateId,
                                                        eventsToPersist.eventOrderOfLastRehydratedEvent,
                                                        eventsToPersist.events);
        snapshotRepository.aggregateUpdated(order, persistedEvents);
        unitOfWork.rollback(new RuntimeException("Simulated failure after the events were appended"));
        assertThat(unitOfWorkFactory.getCurrentUnitOfWork()).isEmpty();

        // Drain barrier instead of a fixed quiet window: AsyncAggregateSnapshotSettings.asynchronous() runs ONE worker thread,
        // which takes tasks in submission order. So a snapshot write dispatched by the rolled-back unit of work - the
        // regression this test guards against - would run before the write of a unit of work committed after it. Once that
        // later snapshot is visible, the rolled-back aggregate's snapshot would be visible too.
        assertThat(SNAPSHOT_SETTINGS.workerThreads()).as("the drain barrier below relies on a single worker thread").isEqualTo(1);
        var barrierOrderId = OrderId.random();
        var barrierOrder   = new Order(barrierOrderId, CustomerId.random(), 5678);
        barrierOrder.addProduct(ProductId.random(), 1);
        var barrierEventsToPersist = barrierOrder.getUncommittedChanges();
        barrierOrder.markChangesAsCommitted();

        unitOfWorkFactory.usingUnitOfWork(() -> {
            var barrierPersistedEvents = eventStore.appendToStream(ORDERS,
                                                                   barrierEventsToPersist.aggregateId,
                                                                   barrierEventsToPersist.eventOrderOfLastRehydratedEvent,
                                                                   barrierEventsToPersist.events);
            snapshotRepository.aggregateUpdated(barrierOrder, barrierPersistedEvents);
        });

        await().atMost(SNAPSHOT_WAIT)
               .untilAsserted(() -> assertThat(snapshotStore.loadSnapshot(ORDERS, barrierOrderId, EventOrder.MAX_EVENT_ORDER, Order.class)).isPresent());

        assertThat(snapshotStore.loadSnapshot(ORDERS, orderId, EventOrder.MAX_EVENT_ORDER, Order.class)).isEmpty();
        assertThat(snapshotStore.loadAllSnapshots(ORDERS, orderId, Order.class, false)).isEmpty();
    }
}
