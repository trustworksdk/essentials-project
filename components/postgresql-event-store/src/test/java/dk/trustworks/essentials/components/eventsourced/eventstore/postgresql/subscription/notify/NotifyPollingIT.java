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

package dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.subscription.notify;

import dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.eventstream.AggregateType;
import dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.eventstream.EventStreamTableColumnNames;
import dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.eventstream.IdentifierColumnType;
import dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.eventstream.JSONColumnType;
import dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.persistence.AggregateEventStreamConfiguration;
import dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.persistence.EventMetaData;
import dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.persistence.PersistableEvent;
import dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.persistence.PersistableEventMapper;
import dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.persistence.table_per_aggregate_type.SeparateTablePerAggregateTypePersistenceStrategy;
import dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.serializer.AggregateIdSerializer;
import dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.serializer.json.EssentialsJSONEventSerializers;
import dk.trustworks.essentials.components.foundation.json.EssentialsObjectMappers;
import dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.test_data.CustomerId;
import dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.test_data.OrderEvent;
import dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.test_data.OrderId;
import dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.transaction.EventStoreManagedUnitOfWorkFactory;
import dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.transaction.EventStoreUnitOfWork;
import dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.transaction.EventStoreUnitOfWorkFactory;
import dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.types.EventOrder;
import dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.types.EventTypeOrName;
import dk.trustworks.essentials.components.foundation.postgresql.ListenNotify;
import dk.trustworks.essentials.components.foundation.postgresql.ListenNotify.SqlOperation;
import dk.trustworks.essentials.components.foundation.postgresql.MultiTableChangeListener;
import dk.trustworks.essentials.components.foundation.postgresql.PostgresqlUtil;
import dk.trustworks.essentials.components.foundation.postgresql.TableChangeNotification;
import dk.trustworks.essentials.components.foundation.types.CorrelationId;
import dk.trustworks.essentials.components.foundation.types.EventId;
import dk.trustworks.essentials.reactive.LocalEventBus;
import org.jdbi.v3.core.Jdbi;
import org.jdbi.v3.postgres.PostgresPlugin;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

import static dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.persistence.table_per_aggregate_type.SeparateTablePerAggregateTypeEventStreamConfigurationFactory.standardSingleTenantConfiguration;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * End-to-end integration test for S1 (NOTIFY-driven polling wake-up). Exercises the
 * full chain on a real Postgres:
 * <ul>
 *   <li>{@code SeparateTablePerAggregateTypePersistenceStrategy.enableNotifyTriggerInstallation}
 *       — sweep over existing configs, CAS rejection of double-call, per-table
 *       installer dispatch on subsequent aggregate registration.</li>
 *   <li>{@link ListenNotify#addChangeNotificationTriggerToTable} actually installs the
 *       pg_notify trigger on the event-stream table.</li>
 *   <li>{@link MultiTableChangeListener} picks up NOTIFY messages from the trigger.</li>
 *   <li>{@link NotifyEpochSource} bridges those onto per-table epoch counters.</li>
 *   <li>{@link NotifyAwareEventStorePollingOptimizer} reads epoch advances and forwards
 *       to delay=0 on the next poll.</li>
 * </ul>
 * Uses the standard table-per-aggregate-type persistence strategy directly (no Spring
 * boot context) so the test is fast, focused, and independent of the autoconfig glue.
 */
@Testcontainers
class NotifyPollingIT {
    private static final AggregateType ORDERS    = AggregateType.of("Orders");
    private static final AggregateType PRODUCTS  = AggregateType.of("Products");
    private static final EventMetaData META_DATA = EventMetaData.of("k", "v");

    @Container
    private final PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:latest")
            .withDatabaseName("notify-polling-it")
            .withUsername("test-user")
            .withPassword("secret-password");

    private Jdbi                                             jdbi;
    private EventStoreUnitOfWorkFactory<EventStoreUnitOfWork> unitOfWorkFactory;
    private SeparateTablePerAggregateTypePersistenceStrategy persistenceStrategy;
    private LocalEventBus                                    notifyBus;
    private MultiTableChangeListener<TableChangeNotification> changeListener;
    private NotifyEpochSource                                epochSource;

    @BeforeEach
    void setUp() {
        jdbi = Jdbi.create(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
        jdbi.installPlugin(new PostgresPlugin());
        unitOfWorkFactory = new EventStoreManagedUnitOfWorkFactory(jdbi);

        persistenceStrategy = new SeparateTablePerAggregateTypePersistenceStrategy(
                jdbi,
                unitOfWorkFactory,
                new TestEventMapper(),
                standardSingleTenantConfiguration(
                        aggregateType -> aggregateType + "_events",
                        EventStreamTableColumnNames.defaultColumnNames(),
                        EssentialsJSONEventSerializers.createForActiveJacksonFlavor(),
                        IdentifierColumnType.UUID,
                        JSONColumnType.JSONB));

        notifyBus = LocalEventBus.builder()
                                 .busName("NotifyPollingIT")
                                 .parallelThreads(1)
                                 .build();

        changeListener = new MultiTableChangeListener<>(
                jdbi,
                Duration.ofMillis(50),               // tight poll for fast tests
                EssentialsObjectMappers.createJSONSerializer(),
                notifyBus,
                false);
        changeListener.start();

        epochSource = new NotifyEpochSource(notifyBus);
    }

    @AfterEach
    void tearDown() {
        if (epochSource != null) epochSource.close();
        if (changeListener != null && changeListener.isStarted()) changeListener.stop();
    }

    @Test
    void enableNotifyTriggerInstallation_rejectsNullInstaller() {
        assertThatThrownBy(() -> persistenceStrategy.enableNotifyTriggerInstallation(null))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void enableNotifyTriggerInstallation_isOneShot_secondCallThrows() {
        persistenceStrategy.enableNotifyTriggerInstallation(tableName -> {});

        assertThatThrownBy(() -> persistenceStrategy.enableNotifyTriggerInstallation(tableName -> {}))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("already configured");
    }

    @Test
    void enableNotifyTriggerInstallation_sweepsExistingConfigs() {
        // Register two aggregates BEFORE enabling — installer must not yet fire.
        var preEnableCaptures = new RecordingInstaller();
        registerAggregate(ORDERS, OrderId.class);
        registerAggregate(PRODUCTS, OrderId.class);
        assertThat(preEnableCaptures.tables).isEmpty();

        // Enable — sweep should invoke the installer once per already-registered table.
        persistenceStrategy.enableNotifyTriggerInstallation(preEnableCaptures);

        assertThat(preEnableCaptures.tables)
                .containsExactlyInAnyOrder("orders_events", "products_events");
    }

    @Test
    void installerFiresForEachNewlyRegisteredAggregateAfterEnable() {
        var captures = new RecordingInstaller();
        persistenceStrategy.enableNotifyTriggerInstallation(captures);
        assertThat(captures.tables).isEmpty();

        registerAggregate(ORDERS, OrderId.class);
        assertThat(captures.tables).containsExactly("orders_events");

        registerAggregate(PRODUCTS, OrderId.class);
        assertThat(captures.tables).containsExactlyInAnyOrder("orders_events", "products_events");
    }

    @Test
    void endToEnd_appendingEventFiresNotifyAndWakesOptimizer() throws Exception {
        // Wire the real installer (the same one the Spring autoconfig uses).
        persistenceStrategy.enableNotifyTriggerInstallation(tableName ->
                jdbi.useTransaction(handle -> {
                    PostgresqlUtil.acquireBootstrapLock(handle);
                    ListenNotify.addChangeNotificationTriggerToTable(handle, tableName, List.of(SqlOperation.INSERT));
                    changeListener.listenToNotificationsFor(tableName, EventStreamTableChangeNotification.class);
                }));

        registerAggregate(ORDERS, OrderId.class);
        var ordersTable = "orders_events";

        // Sanity check: the trigger is installed in Postgres. Trigger name shape is
        // 'notify_on_<table>_changes' (see ListenNotify#addChangeNotificationTriggerToTable).
        var triggerExists = jdbi.withHandle(h -> h.createQuery(
                        "SELECT 1 FROM pg_trigger WHERE tgname = :triggerName")
                                                  .bind("triggerName", "notify_on_" + ordersTable + "_changes")
                                                  .mapTo(Integer.class)
                                                  .findOne()
                                                  .isPresent());
        assertThat(triggerExists).as("pg_notify trigger should be installed on table=%s", ordersTable).isTrue();

        // Drive an optimizer into backoff before any events arrive.
        var settings = new NotifyPollingSettings(true,
                                                 Duration.ofMillis(50),
                                                 Duration.ofSeconds(2),
                                                 2.0);
        var optimizer = new NotifyAwareEventStorePollingOptimizer(epochSource, ordersTable, settings);
        optimizer.eventStorePollingReturnedNoEvents(); // 100
        optimizer.eventStorePollingReturnedNoEvents(); // 200
        optimizer.eventStorePollingReturnedNoEvents(); // 400
        assertThat(optimizer.currentDelayMs()).isEqualTo(400L);

        // Persist an event — this should fire the pg_notify, the MultiTableChangeListener
        // should pick it up on its next poll, the EventBus should publish it, the
        // NotifyEpochSource should bump the counter, and the optimizer should wake up.
        appendOrderEvent(OrderId.random());

        // Wait for the epoch to advance (worst case: changeListener poll interval + bus
        // dispatch — 50 ms poll + dispatch latency).
        awaitEpochAtLeast(ordersTable, 1L, Duration.ofSeconds(5));

        // Optimizer must now return 0 (wake-up) and reset the ramp.
        assertThat(optimizer.currentDelayMs()).isZero();
        // Subsequent no-events poll restarts from initialDelay, not from 400ms.
        optimizer.eventStorePollingReturnedNoEvents();
        assertThat(optimizer.currentDelayMs()).isEqualTo(100L);
    }

    @Test
    void endToEnd_notifyOnDifferentTableLeavesOptimizerInBackoff() throws Exception {
        persistenceStrategy.enableNotifyTriggerInstallation(tableName ->
                jdbi.useTransaction(handle -> {
                    PostgresqlUtil.acquireBootstrapLock(handle);
                    ListenNotify.addChangeNotificationTriggerToTable(handle, tableName, List.of(SqlOperation.INSERT));
                    changeListener.listenToNotificationsFor(tableName, EventStreamTableChangeNotification.class);
                }));
        registerAggregate(ORDERS, OrderId.class);
        registerAggregate(PRODUCTS, OrderId.class);

        var settings  = new NotifyPollingSettings(true,
                                                  Duration.ofMillis(50),
                                                  Duration.ofSeconds(2),
                                                  2.0);
        var optimizer = new NotifyAwareEventStorePollingOptimizer(epochSource, "orders_events", settings);
        optimizer.eventStorePollingReturnedNoEvents(); // 100
        optimizer.eventStorePollingReturnedNoEvents(); // 200

        // Write to PRODUCTS — Orders optimizer must NOT wake up.
        appendProductEvent();
        awaitEpochAtLeast("products_events", 1L, Duration.ofSeconds(5));

        assertThat(optimizer.currentDelayMs()).isEqualTo(200L);
    }

    // ── Helpers ────────────────────────────────────────────────────────────────────

    private void registerAggregate(AggregateType type, Class<?> idType) {
        persistenceStrategy.addAggregateEventStreamConfiguration(type,
                                                                 AggregateIdSerializer.serializerFor(idType));
    }

    private void appendOrderEvent(OrderId orderId) {
        var unitOfWork = unitOfWorkFactory.getOrCreateNewUnitOfWork();
        persistenceStrategy.persist(unitOfWork,
                                    ORDERS,
                                    orderId,
                                    Optional.empty(),
                                    List.of(new OrderEvent.OrderAdded(orderId, CustomerId.random(), 1)));
        unitOfWork.commit();
    }

    private void appendProductEvent() {
        var unitOfWork = unitOfWorkFactory.getOrCreateNewUnitOfWork();
        var orderId    = OrderId.random();
        persistenceStrategy.persist(unitOfWork,
                                    PRODUCTS,
                                    orderId,
                                    Optional.empty(),
                                    List.of(new OrderEvent.OrderAdded(orderId, CustomerId.random(), 1)));
        unitOfWork.commit();
    }

    private void awaitEpochAtLeast(String tableName, long expected, Duration timeout) throws InterruptedException {
        var deadline = System.nanoTime() + timeout.toNanos();
        while (epochSource.currentEpoch(tableName) < expected && System.nanoTime() < deadline) {
            Thread.sleep(20);
        }
        assertThat(epochSource.currentEpoch(tableName))
                .as("epoch for table='%s' should reach %d within %s", tableName, expected, timeout)
                .isGreaterThanOrEqualTo(expected);
    }


    /**
     * Captures the {@code tableName} arguments the installer is invoked with. Thread-safe
     * because {@code enableNotifyTriggerInstallation}'s sweep runs sequentially, but the
     * per-registration path may interleave with the sweep — a concurrent set/list keeps
     * the test deterministic.
     */
    private static final class RecordingInstaller implements NotifyTriggerInstaller {
        final Set<String>     tables = ConcurrentHashMap.newKeySet();
        final AtomicInteger   calls  = new AtomicInteger();
        final List<String>    order  = new ArrayList<>();

        @Override
        public synchronized void installFor(String eventStreamTableName) {
            tables.add(eventStreamTableName);
            order.add(eventStreamTableName);
            calls.incrementAndGet();
        }
    }

    private static class TestEventMapper implements PersistableEventMapper {
        private final CorrelationId correlationId   = CorrelationId.random();
        private final EventId       causedByEventId = EventId.random();

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
                                         null,
                                         META_DATA,
                                         OffsetDateTime.now(),
                                         causedByEventId,
                                         correlationId,
                                         null);
        }
    }
}
