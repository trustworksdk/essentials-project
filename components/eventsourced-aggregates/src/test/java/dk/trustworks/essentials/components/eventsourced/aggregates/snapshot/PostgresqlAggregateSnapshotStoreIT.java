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
import dk.trustworks.essentials.components.eventsourced.aggregates.modern.Order;
import dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.PostgresqlEventStore;
import dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.eventstream.*;
import dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.persistence.AggregateEventStreamConfiguration;
import dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.persistence.PersistableEvent;
import dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.persistence.PersistableEventMapper;
import dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.persistence.table_per_aggregate_type.*;
import dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.serializer.json.EssentialsJSONEventSerializers;
import dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.transaction.EventStoreManagedUnitOfWorkFactory;
import dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.types.*;
import dk.trustworks.essentials.components.foundation.postgresql.SqlExecutionTimeLogger;
import dk.trustworks.essentials.components.foundation.transaction.UnitOfWork;
import dk.trustworks.essentials.components.foundation.types.CorrelationId;
import dk.trustworks.essentials.components.foundation.types.EventId;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.jdbi.v3.core.Jdbi;
import org.jdbi.v3.postgres.PostgresPlugin;
import org.junit.jupiter.api.*;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.*;

import java.time.OffsetDateTime;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

@Testcontainers
class PostgresqlAggregateSnapshotStoreIT {
    private static final AggregateType ORDERS         = AggregateType.of("Orders");
    /** A second aggregate type sharing the Order implementation class, to pin snapshot isolation between them. */
    private static final AggregateType ARCHIVED_ORDERS = AggregateType.of("ArchivedOrders");

    @Container
    private final PostgreSQLContainer<?> postgreSQLContainer = new PostgreSQLContainer<>("postgres:latest").withDatabaseName("event-store")
                                                                                                           .withUsername("test-user")
                                                                                                           .withPassword("secret-password");

    private EventStoreManagedUnitOfWorkFactory                                   unitOfWorkFactory;
    private PostgresqlEventStore<SeparateTablePerAggregateEventStreamConfiguration> eventStore;
    private PostgresqlAggregateSnapshotStore                                     snapshotStore;

    @BeforeEach
    void setup() {
        var jdbi = Jdbi.create(postgreSQLContainer.getJdbcUrl(),
                               postgreSQLContainer.getUsername(),
                               postgreSQLContainer.getPassword());
        jdbi.installPlugin(new PostgresPlugin());
        jdbi.setSqlLogger(new SqlExecutionTimeLogger());

        unitOfWorkFactory = new EventStoreManagedUnitOfWorkFactory(jdbi);
        var aggregateEventStreamConfigurationFactory = SeparateTablePerAggregateTypeEventStreamConfigurationFactory.standardSingleTenantConfiguration(EssentialsJSONEventSerializers.createForActiveJacksonFlavor(),
                                                                                                                                                      IdentifierColumnType.UUID,
                                                                                                                                                      JSONColumnType.JSONB);
        eventStore = new PostgresqlEventStore<>(unitOfWorkFactory,
                                                new SeparateTablePerAggregateTypePersistenceStrategy(jdbi,
                                                                                                     unitOfWorkFactory,
                                                                                                     new TestPersistableEventMapper(),
                                                                                                     aggregateEventStreamConfigurationFactory));
        eventStore.addAggregateEventStreamConfiguration(ORDERS, OrderId.class);
        eventStore.addAggregateEventStreamConfiguration(ARCHIVED_ORDERS, OrderId.class);
        snapshotStore = new PostgresqlAggregateSnapshotStore(eventStore,
                                                             unitOfWorkFactory,
                                                             Optional.empty(),
                                                             aggregateEventStreamConfigurationFactory.jsonSerializer);
    }

    @AfterEach
    void cleanup() {
        unitOfWorkFactory.getCurrentUnitOfWork().ifPresent(UnitOfWork::rollback);
        assertThat(unitOfWorkFactory.getCurrentUnitOfWork()).isEmpty();
    }

    @Test
    void save_load_find_and_delete_snapshots() {
        var orderId = OrderId.random();
        var firstSnapshot = new Order(orderId, CustomerId.random(), 1234);
        var latestSnapshot = new Order(orderId, CustomerId.random(), 5678);
        latestSnapshot.addProduct(ProductId.random(), 5);

        snapshotStore.saveSnapshot(ORDERS,
                                   orderId,
                                   Order.class,
                                   EventOrder.of(1),
                                   eventStore.getAggregateEventStreamConfiguration(ORDERS).jsonSerializer.serialize(firstSnapshot));
        snapshotStore.saveSnapshot(ORDERS,
                                   orderId,
                                   Order.class,
                                   EventOrder.of(3),
                                   eventStore.getAggregateEventStreamConfiguration(ORDERS).jsonSerializer.serialize(latestSnapshot));

        var latestSnapshotEventOrder = snapshotStore.findMostRecentLastIncludedEventOrder(ORDERS,
                                                                                           orderId,
                                                                                           Order.class);
        assertThat(latestSnapshotEventOrder).contains(EventOrder.of(3));

        var loadedLatestSnapshot = snapshotStore.loadSnapshot(ORDERS,
                                                              orderId,
                                                              EventOrder.MAX_EVENT_ORDER,
                                                              Order.class);
        assertThat(loadedLatestSnapshot).isPresent();
        assertThat(loadedLatestSnapshot.get().eventOrderOfLastIncludedEvent).isEqualTo(EventOrder.of(3));
        assertThat(loadedLatestSnapshot.get().aggregateSnapshot).usingRecursiveComparison()
                                                                .ignoringFieldsMatchingRegexes(AggregateSnapshotComparison.FRAMEWORK_RUNTIME_FIELDS)
                                                                .isEqualTo(latestSnapshot);

        var loadedEarlierSnapshot = snapshotStore.loadSnapshot(ORDERS,
                                                               orderId,
                                                               EventOrder.of(1),
                                                               Order.class);
        assertThat(loadedEarlierSnapshot).isPresent();
        assertThat(loadedEarlierSnapshot.get().eventOrderOfLastIncludedEvent).isEqualTo(EventOrder.of(1));
        assertThat(loadedEarlierSnapshot.get().aggregateSnapshot).usingRecursiveComparison()
                                                                 .ignoringFieldsMatchingRegexes(AggregateSnapshotComparison.FRAMEWORK_RUNTIME_FIELDS)
                                                                 .isEqualTo(firstSnapshot);

        var allSnapshots = snapshotStore.loadAllSnapshots(ORDERS,
                                                          orderId,
                                                          Order.class,
                                                          true);
        assertThat(allSnapshots).hasSize(2);

        snapshotStore.deleteSnapshots(ORDERS,
                                      orderId,
                                      Order.class,
                                      java.util.List.of(EventOrder.of(1)));

        assertThat(snapshotStore.loadAllSnapshots(ORDERS,
                                                  orderId,
                                                  Order.class,
                                                  true))
                .singleElement()
                .extracting(snapshot -> snapshot.eventOrderOfLastIncludedEvent)
                .isEqualTo(EventOrder.of(3));

        snapshotStore.deleteSnapshots(ORDERS, orderId, Order.class);
        assertThat(snapshotStore.loadAllSnapshots(ORDERS,
                                                  orderId,
                                                  Order.class,
                                                  true)).isEmpty();
        assertThat(snapshotStore.findMostRecentLastIncludedEventOrder(ORDERS,
                                                                      orderId,
                                                                      Order.class)).contains(EventOrder.NO_EVENTS_PREVIOUSLY_PERSISTED);
    }

    @Test
    void save_does_not_overwrite_when_a_newer_snapshot_already_exists() {
        var orderId = OrderId.random();
        var jsonSerializer = eventStore.getAggregateEventStreamConfiguration(ORDERS).jsonSerializer;

        snapshotStore.saveSnapshot(ORDERS, orderId, Order.class, EventOrder.of(10),
                                   jsonSerializer.serialize(new Order(orderId, CustomerId.random(), 1000)));
        snapshotStore.saveSnapshot(ORDERS, orderId, Order.class, EventOrder.of(5),
                                   jsonSerializer.serialize(new Order(orderId, CustomerId.random(), 500)));

        assertThat(snapshotStore.findMostRecentLastIncludedEventOrder(ORDERS, orderId, Order.class))
                .contains(EventOrder.of(10));
        assertThat(snapshotStore.loadAllSnapshots(ORDERS, orderId, Order.class, false))
                .singleElement()
                .extracting(snapshot -> snapshot.eventOrderOfLastIncludedEvent)
                .isEqualTo(EventOrder.of(10));
    }

    @Test
    void delete_snapshots_older_than_only_removes_strictly_older_rows() {
        var orderId = OrderId.random();
        var jsonSerializer = eventStore.getAggregateEventStreamConfiguration(ORDERS).jsonSerializer;

        snapshotStore.saveSnapshot(ORDERS, orderId, Order.class, EventOrder.of(1),
                                   jsonSerializer.serialize(new Order(orderId, CustomerId.random(), 1)));
        snapshotStore.saveSnapshot(ORDERS, orderId, Order.class, EventOrder.of(5),
                                   jsonSerializer.serialize(new Order(orderId, CustomerId.random(), 5)));
        snapshotStore.saveSnapshot(ORDERS, orderId, Order.class, EventOrder.of(9),
                                   jsonSerializer.serialize(new Order(orderId, CustomerId.random(), 9)));

        snapshotStore.deleteSnapshotsOlderThan(ORDERS, orderId, Order.class, EventOrder.of(5));

        assertThat(snapshotStore.loadAllSnapshots(ORDERS, orderId, Order.class, false))
                .extracting(snapshot -> snapshot.eventOrderOfLastIncludedEvent)
                .containsExactlyInAnyOrder(EventOrder.of(5), EventOrder.of(9));
    }

    @Test
    void delete_all_snapshots_for_aggregate_type() {
        var firstOrderId = OrderId.random();
        var secondOrderId = OrderId.random();

        snapshotStore.saveSnapshot(ORDERS,
                                   firstOrderId,
                                   Order.class,
                                   EventOrder.of(0),
                                   eventStore.getAggregateEventStreamConfiguration(ORDERS).jsonSerializer.serialize(new Order(firstOrderId, CustomerId.random(), 1234)));
        snapshotStore.saveSnapshot(ORDERS,
                                   secondOrderId,
                                   Order.class,
                                   EventOrder.of(0),
                                   eventStore.getAggregateEventStreamConfiguration(ORDERS).jsonSerializer.serialize(new Order(secondOrderId, CustomerId.random(), 5678)));

        snapshotStore.deleteAllSnapshots(Order.class);

        assertThat(snapshotStore.loadAllSnapshots(ORDERS,
                                                  firstOrderId,
                                                  Order.class,
                                                  true)).isEmpty();
        assertThat(snapshotStore.loadAllSnapshots(ORDERS,
                                                  secondOrderId,
                                                  Order.class,
                                                  true)).isEmpty();
    }

    @Test
    void records_metrics_for_snapshot_store_operations() {
        var meterRegistry = new SimpleMeterRegistry();
        snapshotStore = new PostgresqlAggregateSnapshotStore(eventStore,
                                                             unitOfWorkFactory,
                                                             Optional.empty(),
                                                             eventStore.getAggregateEventStreamConfiguration(ORDERS).jsonSerializer,
                                                             Optional.of(meterRegistry));
        var orderId = OrderId.random();
        var snapshot = new Order(orderId, CustomerId.random(), 1234);
        var serializedSnapshot = eventStore.getAggregateEventStreamConfiguration(ORDERS).jsonSerializer.serialize(snapshot);

        snapshotStore.saveSnapshot(ORDERS, orderId, Order.class, EventOrder.of(1), serializedSnapshot);
        snapshotStore.findMostRecentLastIncludedEventOrder(ORDERS, orderId, Order.class);
        snapshotStore.loadSnapshot(ORDERS, orderId, EventOrder.of(1), Order.class);
        snapshotStore.loadAllSnapshots(ORDERS, orderId, Order.class, true);
        snapshotStore.deleteSnapshots(ORDERS, orderId, Order.class, java.util.List.of(EventOrder.of(1)));
        snapshotStore.deleteAllSnapshots(Order.class);

        assertThat(meterRegistry.find(AggregateSnapshotMeasurementSupport.METRIC_PREFIX + ".save_snapshot")
                                .tag("aggregate_type", "Orders")
                                .tag("aggregate_impl_type", Order.class.getName())
                                .timer())
                .isNotNull()
                .extracting(timer -> timer.count())
                .isEqualTo(1L);
        assertThat(meterRegistry.find(AggregateSnapshotMeasurementSupport.METRIC_PREFIX + ".find_most_recent_last_included_event_order")
                                .tag("aggregate_type", "Orders")
                                .tag("aggregate_impl_type", Order.class.getName())
                                .timer())
                .isNotNull()
                .extracting(timer -> timer.count())
                .isEqualTo(1L);
        assertThat(meterRegistry.find(AggregateSnapshotMeasurementSupport.METRIC_PREFIX + ".load_snapshot")
                                .tag("aggregate_type", "Orders")
                                .tag("aggregate_impl_type", Order.class.getName())
                                .timer())
                .isNotNull()
                .extracting(timer -> timer.count())
                .isEqualTo(1L);
        assertThat(meterRegistry.find(AggregateSnapshotMeasurementSupport.METRIC_PREFIX + ".load_all_snapshots")
                                .tag("aggregate_type", "Orders")
                                .tag("aggregate_impl_type", Order.class.getName())
                                .tag("include_snapshot_payload", "true")
                                .timer())
                .isNotNull()
                .extracting(timer -> timer.count())
                .isEqualTo(1L);
        assertThat(meterRegistry.find(AggregateSnapshotMeasurementSupport.METRIC_PREFIX + ".delete_snapshots")
                                .tag("aggregate_type", "Orders")
                                .tag("aggregate_impl_type", Order.class.getName())
                                .tag("delete_mode", "selected")
                                .timer())
                .isNotNull()
                .extracting(timer -> timer.count())
                .isEqualTo(1L);
        assertThat(meterRegistry.find(AggregateSnapshotMeasurementSupport.METRIC_PREFIX + ".delete_all_snapshots")
                                .tag("aggregate_impl_type", Order.class.getName())
                                .timer())
                .isNotNull()
                .extracting(timer -> timer.count())
                .isEqualTo(1L);
        assertThat(meterRegistry.find(AggregateSnapshotMeasurementSupport.METRIC_PREFIX + ".deserialize_snapshot")
                                .tag("aggregate_type", "Orders")
                                .tag("aggregate_impl_type", Order.class.getName())
                                .tag("outcome", "success")
                                .timer())
                .isNotNull()
                .extracting(timer -> timer.count())
                .isEqualTo(2L);
    }

    /**
     * Every snapshot is identified by its {@link AggregateType} as well as its implementation type and id. The same
     * aggregate class registered under two aggregate types therefore keeps two independent snapshot histories, instead
     * of the second save being swallowed by the first one's row and the deletes reaching across.
     */
    @Test
    void snapshots_of_the_same_aggregate_id_and_impl_type_are_isolated_per_aggregate_type() {
        var orderId          = OrderId.random();
        var ordersProductId   = ProductId.random();
        var archivedProductId = ProductId.random();
        var ordersOrder       = new Order(orderId, CustomerId.random(), 1111);
        var archivedOrder     = new Order(orderId, CustomerId.random(), 2222);
        ordersOrder.addProduct(ordersProductId, 1);
        archivedOrder.addProduct(archivedProductId, 2);

        snapshotStore.saveSnapshot(ORDERS,
                                   orderId,
                                   Order.class,
                                   EventOrder.of(1),
                                   eventStore.getAggregateEventStreamConfiguration(ORDERS).jsonSerializer.serialize(ordersOrder));
        snapshotStore.saveSnapshot(ARCHIVED_ORDERS,
                                   orderId,
                                   Order.class,
                                   EventOrder.of(1),
                                   eventStore.getAggregateEventStreamConfiguration(ARCHIVED_ORDERS).jsonSerializer.serialize(archivedOrder));

        assertThat(snapshotStore.loadAllSnapshots(ORDERS, orderId, Order.class, false)).hasSize(1);
        assertThat(snapshotStore.loadAllSnapshots(ARCHIVED_ORDERS, orderId, Order.class, false)).hasSize(1);
        assertThat(snapshotStore.<OrderId, Order>loadSnapshot(ORDERS, orderId, EventOrder.MAX_EVENT_ORDER, Order.class))
                .hasValueSatisfying(snapshot -> {
                    assertThat((CharSequence) snapshot.aggregateType).isEqualTo(ORDERS);
                    assertThat(snapshot.aggregateSnapshot.productAndQuantity).containsExactly(Map.entry(ordersProductId, 1));
                });
        assertThat(snapshotStore.<OrderId, Order>loadSnapshot(ARCHIVED_ORDERS, orderId, EventOrder.MAX_EVENT_ORDER, Order.class))
                .hasValueSatisfying(snapshot -> {
                    assertThat((CharSequence) snapshot.aggregateType).isEqualTo(ARCHIVED_ORDERS);
                    assertThat(snapshot.aggregateSnapshot.productAndQuantity).containsExactly(Map.entry(archivedProductId, 2));
                });

        // A newer snapshot under one aggregate type must not suppress a save under the other.
        snapshotStore.saveSnapshot(ORDERS,
                                   orderId,
                                   Order.class,
                                   EventOrder.of(5),
                                   eventStore.getAggregateEventStreamConfiguration(ORDERS).jsonSerializer.serialize(ordersOrder));
        snapshotStore.saveSnapshot(ARCHIVED_ORDERS,
                                   orderId,
                                   Order.class,
                                   EventOrder.of(2),
                                   eventStore.getAggregateEventStreamConfiguration(ARCHIVED_ORDERS).jsonSerializer.serialize(archivedOrder));

        assertThat(snapshotStore.findMostRecentLastIncludedEventOrder(ORDERS, orderId, Order.class)).contains(EventOrder.of(5));
        assertThat(snapshotStore.findMostRecentLastIncludedEventOrder(ARCHIVED_ORDERS, orderId, Order.class)).contains(EventOrder.of(2));

        // Deleting one aggregate type's snapshots leaves the other's alone.
        snapshotStore.deleteSnapshots(ORDERS, orderId, Order.class);

        assertThat(snapshotStore.loadAllSnapshots(ORDERS, orderId, Order.class, false)).isEmpty();
        assertThat(snapshotStore.loadAllSnapshots(ARCHIVED_ORDERS, orderId, Order.class, false)).hasSize(2);
    }

    @Test
    void delete_all_snapshots_for_an_implementation_type_spans_aggregate_types() {
        var orderId = OrderId.random();
        var order   = new Order(orderId, CustomerId.random(), 1234);
        snapshotStore.saveSnapshot(ORDERS, orderId, Order.class, EventOrder.of(1),
                                   eventStore.getAggregateEventStreamConfiguration(ORDERS).jsonSerializer.serialize(order));
        snapshotStore.saveSnapshot(ARCHIVED_ORDERS, orderId, Order.class, EventOrder.of(1),
                                   eventStore.getAggregateEventStreamConfiguration(ARCHIVED_ORDERS).jsonSerializer.serialize(order));

        snapshotStore.deleteAllSnapshots(Order.class);

        assertThat(snapshotStore.loadAllSnapshots(ORDERS, orderId, Order.class, false)).isEmpty();
        assertThat(snapshotStore.loadAllSnapshots(ARCHIVED_ORDERS, orderId, Order.class, false)).isEmpty();
    }

    private static class TestPersistableEventMapper implements PersistableEventMapper {
        private final CorrelationId correlationId = CorrelationId.random();
        private final EventId causedByEventId = EventId.random();

        @Override
        public PersistableEvent map(Object aggregateId,
                                    AggregateEventStreamConfiguration aggregateEventStreamConfiguration,
                                    Object event,
                                    EventOrder eventOrder) {
            return PersistableEvent.from(EventId.random(),
                                         aggregateEventStreamConfiguration.aggregateType,
                                         aggregateId,
                                         EventTypeOrName.with(event.getClass()),
                                         event,
                                         eventOrder,
                                         EventRevision.of(1),
                                         new dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.persistence.EventMetaData(),
                                         OffsetDateTime.now(),
                                         causedByEventId,
                                         correlationId,
                                         null);
        }
    }
}
