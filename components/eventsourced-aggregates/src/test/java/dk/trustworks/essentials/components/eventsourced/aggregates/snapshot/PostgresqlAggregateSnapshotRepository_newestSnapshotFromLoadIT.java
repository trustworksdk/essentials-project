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
import dk.trustworks.essentials.components.foundation.transaction.UnitOfWork;
import dk.trustworks.essentials.components.foundation.types.*;
import org.jdbi.v3.core.Jdbi;
import org.jdbi.v3.core.statement.*;
import org.jdbi.v3.postgres.PostgresPlugin;
import org.junit.jupiter.api.*;
import org.testcontainers.junit.jupiter.*;
import org.testcontainers.postgresql.PostgreSQLContainer;

import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * An aggregate update decides whether to add a snapshot from the newest snapshot's event order. The snapshot
 * repository used to query that on every update, inside the command's transaction; it now takes it from what
 * {@code loadSnapshot} found when the aggregate was loaded in the same unit of work, and queries only without that.
 * These tests count the query, and check the snapshots still land where the policy says.
 */
@Testcontainers
class PostgresqlAggregateSnapshotRepository_newestSnapshotFromLoadIT {
    private static final AggregateType ORDERS = AggregateType.of("Orders");

    @Container
    static PostgreSQLContainer postgreSQLContainer = new PostgreSQLContainer("postgres:18.4").withDatabaseName("event-store")
                                                                                              .withUsername("test-user")
                                                                                              .withPassword("secret-password");

    private final AtomicInteger newestSnapshotQueries = new AtomicInteger();

    private EventStoreManagedUnitOfWorkFactory                    unitOfWorkFactory;
    private PostgresqlAggregateSnapshotRepository                 snapshotRepository;
    private StatefulAggregateRepository<OrderId, OrderEvent, Order> ordersRepository;

    @BeforeEach
    void setup() {
        var jdbi = Jdbi.create(postgreSQLContainer.getJdbcUrl(), postgreSQLContainer.getUsername(), postgreSQLContainer.getPassword());
        jdbi.installPlugin(new PostgresPlugin());
        jdbi.setSqlLogger(new SqlLogger() {
            @Override
            public void logAfterExecution(StatementContext context) {
                if (context.getRenderedSql() != null && context.getRenderedSql().contains("MAX(last_included_event_order)")) {
                    newestSnapshotQueries.incrementAndGet();
                }
            }
        });
        jdbi.useHandle(handle -> handle.execute("DROP TABLE IF EXISTS orders_events, aggregate_snapshots"));

        unitOfWorkFactory = new EventStoreManagedUnitOfWorkFactory(jdbi);
        var configurationFactory = SeparateTablePerAggregateTypeEventStreamConfigurationFactory.standardSingleTenantConfiguration(EssentialsJSONEventSerializers.create(),
                                                                                                                                   IdentifierColumnType.UUID,
                                                                                                                                   JSONColumnType.JSONB);
        var eventStore = new PostgresqlEventStore<>(unitOfWorkFactory,
                                                    new SeparateTablePerAggregateTypePersistenceStrategy(jdbi,
                                                                                                         unitOfWorkFactory,
                                                                                                         new TestPersistableEventMapper(),
                                                                                                         configurationFactory));
        eventStore.addAggregateEventStreamConfiguration(ORDERS, OrderId.class);
        snapshotRepository = new PostgresqlAggregateSnapshotRepository(eventStore,
                                                                       unitOfWorkFactory,
                                                                       configurationFactory.jsonSerializer,
                                                                       AddNewAggregateSnapshotStrategy.updateWhenBehindByNumberOfEvents(3),
                                                                       AggregateSnapshotDeletionStrategy.keepALimitedNumberOfHistoricSnapshots(10));
        ordersRepository = StatefulAggregateRepository.builder(eventStore)
                                                      .setAggregateType(ORDERS)
                                                      .setAggregateImplementationType(Order.class)
                                                      .setAggregateSnapshotRepository(snapshotRepository)
                                                      .build();
    }

    @AfterEach
    void cleanup() {
        unitOfWorkFactory.getCurrentUnitOfWork().ifPresent(UnitOfWork::rollback);
    }

    @Test
    void an_update_after_a_load_decides_from_what_the_load_found_and_snapshots_land_where_the_policy_says() {
        var orderId = OrderId.random();
        // A new aggregate was never loaded, so its first update has nothing recorded and asks the database
        unitOfWorkFactory.usingUnitOfWork(() -> ordersRepository.save(new Order(orderId, CustomerId.random(), 1234)));
        assertThat(newestSnapshotQueries).hasValue(1);

        // Event orders 1-5: a load before each, first without a snapshot, then from the snapshot at 2
        for (var round = 1; round <= 5; round++) {
            var quantity = round;
            unitOfWorkFactory.usingUnitOfWork(() -> ordersRepository.load(orderId).addProduct(ProductId.random(), quantity));
        }

        assertThat(newestSnapshotQueries).as("queries after the first update").hasValue(1);
        assertThat(snapshotEventOrders(orderId)).containsExactly(EventOrder.of(2), EventOrder.of(5));
    }

    @Test
    void a_snapshot_deleted_after_the_load_is_not_taken_from_the_load() {
        var orderId = OrderId.random();
        unitOfWorkFactory.usingUnitOfWork(() -> ordersRepository.save(new Order(orderId, CustomerId.random(), 1234)));
        for (var round = 1; round <= 2; round++) {
            var quantity = round;
            unitOfWorkFactory.usingUnitOfWork(() -> ordersRepository.load(orderId).addProduct(ProductId.random(), quantity));
        }
        assertThat(snapshotEventOrders(orderId)).containsExactly(EventOrder.of(2));
        newestSnapshotQueries.set(0);

        unitOfWorkFactory.usingUnitOfWork(() -> {
            var order = ordersRepository.load(orderId);
            snapshotRepository.deleteSnapshots(ORDERS, orderId, Order.class);
            order.addProduct(ProductId.random(), 3);
        });

        // Asked the database, found none, and so snapshotted at 3 rather than waiting for 5
        assertThat(newestSnapshotQueries).hasValue(1);
        assertThat(snapshotEventOrders(orderId)).containsExactly(EventOrder.of(3));
    }

    private List<EventOrder> snapshotEventOrders(OrderId orderId) {
        return unitOfWorkFactory.withUnitOfWork(() -> snapshotRepository.loadAllSnapshots(ORDERS, orderId, Order.class, false)
                                                                         .stream()
                                                                         .map(snapshot -> snapshot.eventOrderOfLastIncludedEvent)
                                                                         .toList());
    }
}
