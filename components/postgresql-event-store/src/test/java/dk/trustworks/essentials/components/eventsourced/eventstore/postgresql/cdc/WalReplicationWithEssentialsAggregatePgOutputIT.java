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

import dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.PostgresqlEventStore;
import dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.cdc.CdcProperties.CdcDeliveryMode;
import dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.cdc.CdcProperties.CdcDispatcherProperties;
import dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.cdc.CdcProperties.PgOutputProperties;
import dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.cdc.CdcProperties.WalReplicationTailerProperties;
import dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.cdc.CdcProperties.WalParserMode;
import dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.cdc.converter.*;
import dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.eventstream.AggregateType;
import dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.eventstream.EventStreamTableColumnNames;
import dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.eventstream.IdentifierColumnType;
import dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.eventstream.JSONColumnType;
import dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.eventstream.PersistedEvent;
import dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.gap.EventStreamGapHandler;
import dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.gap.PostgresqlEventStreamGapHandler;
import dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.persistence.table_per_aggregate_type.SeparateTablePerAggregateEventStreamConfiguration;
import dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.persistence.table_per_aggregate_type.SeparateTablePerAggregateTypeEventStreamConfigurationFactory;
import dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.persistence.table_per_aggregate_type.SeparateTablePerAggregateTypePersistenceStrategy;
import dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.processor.EventProcessorIT;
import dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.serializer.TenantSerializer;
import dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.serializer.json.JacksonJSONEventSerializer;
import dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.test_data.CustomerId;
import dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.test_data.OrderEvent;
import dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.test_data.OrderId;
import dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.test_data.ProductId;
import dk.trustworks.essentials.components.foundation.transaction.UnitOfWork;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Supplier;

import static dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.processor.EventProcessorIT.createObjectMapper;
import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

@Testcontainers
class WalReplicationWithEssentialsAggregatePgOutputIT extends AbstractLogicalReplicationPostgresIT {

    private PostgresqlEventStore<SeparateTablePerAggregateEventStreamConfiguration> eventStore;
    private JacksonJSONEventSerializer                                              jacksonJSONSerializer;
    private CdcInboxRepository                                                      inboxRepository;
    private EventStreamGapHandler<?>                                                gapHandler;

    @BeforeEach
    void setup() {
        jacksonJSONSerializer = new JacksonJSONEventSerializer(createObjectMapper());
        var eventMapper = new EventProcessorIT.TestPersistableEventMapper();

        var persistenceStrategy =
                new SeparateTablePerAggregateTypePersistenceStrategy(
                        jdbi,
                        unitOfWorkFactory,
                        eventMapper,
                        SeparateTablePerAggregateTypeEventStreamConfigurationFactory.standardConfiguration(
                                aggregateType -> aggregateType + "_events",
                                EventStreamTableColumnNames.defaultColumnNames(),
                                jacksonJSONSerializer,
                                IdentifierColumnType.TEXT,
                                JSONColumnType.JSONB,
                                new TenantSerializer.TenantIdSerializer()
                        )
                );

        persistenceStrategy.addAggregateEventStreamConfiguration(ORDERS, OrderId.class);

        eventStore = new PostgresqlEventStore<>(unitOfWorkFactory, persistenceStrategy);
        inboxRepository = new CdcInboxRepository(unitOfWorkFactory);
        gapHandler = new PostgresqlEventStreamGapHandler<>(eventStore, unitOfWorkFactory);
    }

    @AfterEach
    void cleanup() {
        unitOfWorkFactory.getCurrentUnitOfWork().ifPresent(UnitOfWork::rollback);
        assertThat(unitOfWorkFactory.getCurrentUnitOfWork()).isEmpty();
    }

    @Test
    void pgoutput_direct_delivery_mode_publishes_without_inbox_writes() {
        String slotName = slotName();
        String publicationName = publicationName();
        createPublication(publicationName);

        AggregateTypeResolver resolver = table -> "orders_events".equalsIgnoreCase(table) ? ORDERS : null;
        var pgOutputConverter = new PgOutputToPersistedEventConverter(jacksonJSONSerializer, resolver);
        List<PersistedEvent> cdcPersistedEvents = new CopyOnWriteArrayList<>();

        var availability = new CdcAvailability();
        var tailer = new WalReplicationTailer(
                replicationDataSource,
                jdbi,
                unitOfWorkFactory,
                slotName,
                inboxRepository,
                tailerProperties(),
                PgSlotMode.CREATE_IF_MISSING,
                CdcMode.AUTO,
                CdcDeliveryMode.DIRECT,
                pgOutputPlugin(publicationName, pgOutputConverter),
                Optional.of(cdcPersistedEvents::addAll),
                Optional.empty(),
                availability,
                Optional.empty(),
                Optional.empty(),
                Optional.of(eventStreamTablesSupplier()),
                false
        );

        tailer.startAndAwaitReady(Duration.ofSeconds(10));
        appendOrderEvents();

        await()
                .atMost(Duration.ofSeconds(20))
                .pollInterval(Duration.ofMillis(100))
                .untilAsserted(() -> {
                    assertThat(cdcPersistedEvents).hasSizeGreaterThanOrEqualTo(3);
                    assertThat(inboxRepository.countByStatus(slotName, "RECEIVED")).isZero();
                    assertThat(inboxRepository.countByStatus(slotName, "POISON")).isZero();
                    assertThat(inboxRepository.countByStatus(slotName, "DISPATCHED")).isZero();
                });

        tailer.stop();
    }

    @Test
    void pgoutput_inbox_delivery_persists_and_dispatches_events() {
        String slotName = slotName();
        String publicationName = publicationName();
        createPublication(publicationName);

        AggregateTypeResolver resolver = table -> "orders_events".equalsIgnoreCase(table) ? ORDERS : null;
        var pgOutputConverter = new PgOutputToPersistedEventConverter(jacksonJSONSerializer, resolver);
        List<PersistedEvent> cdcPersistedEvents = new CopyOnWriteArrayList<>();

        var availability = new CdcAvailability();
        var logicalDecodingPlugin = pgOutputPlugin(publicationName, pgOutputConverter);
        var tailer = new WalReplicationTailer(
                replicationDataSource,
                jdbi,
                unitOfWorkFactory,
                slotName,
                inboxRepository,
                tailerProperties(),
                PgSlotMode.CREATE_IF_MISSING,
                CdcMode.AUTO,
                CdcDeliveryMode.INBOX,
                logicalDecodingPlugin,
                Optional.of(cdcPersistedEvents::addAll),
                Optional.empty(),
                availability,
                Optional.empty(),
                Optional.empty(),
                Optional.of(eventStreamTablesSupplier()),
                false
        );

        var dispatcher = new CdcDispatcher(
                inboxRepository,
                unitOfWorkFactory,
                gapHandler,
                logicalDecodingPlugin,
                Optional.empty(),
                cdcPersistedEvents::addAll,
                slotName,
                CdcDispatcherProperties.defaults(),
                CdcDeliveryMode.INBOX,
                availability,
                Optional.empty()
        );

        tailer.startAndAwaitReady(Duration.ofSeconds(10));
        dispatcher.start();
        appendOrderEvents();

        await()
                .atMost(Duration.ofSeconds(20))
                .pollInterval(Duration.ofMillis(100))
                .untilAsserted(() -> {
                    assertThat(cdcPersistedEvents).hasSizeGreaterThanOrEqualTo(3);
                    assertThat(inboxRepository.countByStatus(slotName, "RECEIVED")).isZero();
                    assertThat(inboxRepository.countByStatus(slotName, "POISON")).isZero();
                    assertThat(inboxRepository.countByStatus(slotName, "DISPATCHED")).isGreaterThan(0);
                });

        dispatcher.stop();
        tailer.stop();
    }

    @Test
    void pgoutput_two_tailers_only_one_holds_slot_lock_and_second_takes_over() {
        String slotName = slotName();
        String publicationName = publicationName();
        createPublication(publicationName);

        AggregateTypeResolver resolver = table -> "orders_events".equalsIgnoreCase(table) ? ORDERS : null;
        var pgOutputConverter = new PgOutputToPersistedEventConverter(jacksonJSONSerializer, resolver);
        List<PersistedEvent> node1Events = new CopyOnWriteArrayList<>();
        List<PersistedEvent> node2Events = new CopyOnWriteArrayList<>();

        var tailer1 = directPgOutputTailer(slotName, publicationName, pgOutputConverter, node1Events);
        var tailer2 = directPgOutputTailer(slotName, publicationName, pgOutputConverter, node2Events);

        tailer1.startAndAwaitReady(Duration.ofSeconds(10));
        tailer2.start();

        await()
                .atMost(Duration.ofSeconds(5))
                .pollInterval(Duration.ofMillis(100))
                .untilAsserted(() -> {
                    assertThat(tailer1.getStatus().slotLockAcquired()).isTrue();
                    assertThat(tailer2.getStatus().slotLockAcquired()).isFalse();
                });

        appendOrderEvents();

        await()
                .atMost(Duration.ofSeconds(20))
                .pollInterval(Duration.ofMillis(100))
                .untilAsserted(() -> {
                    assertThat(node1Events).hasSizeGreaterThanOrEqualTo(3);
                    assertThat(node2Events).isEmpty();
                });

        tailer1.stop();

        await()
                .atMost(Duration.ofSeconds(10))
                .pollInterval(Duration.ofMillis(100))
                .untilAsserted(() -> assertThat(tailer2.getStatus().slotLockAcquired()).isTrue());

        appendOneMoreOrderEvent();

        await()
                .atMost(Duration.ofSeconds(20))
                .pollInterval(Duration.ofMillis(100))
                .untilAsserted(() -> assertThat(node2Events).hasSizeGreaterThanOrEqualTo(1));

        tailer2.stop();
    }

    /**
     * Verifies the inbox dedup invariant documented on {@link CdcInboxRepository#insertIfAbsent}: under
     * pgoutput, every persisted WAL message carries a distinct LSN, so the {@code unique(slot_name, lsn)}
     * key never falsely dedups two distinct messages. Exercised across a multi-statement transaction
     * (4 INSERTs in one txn — the first append, so the RELATION-message boundary is crossed) plus a
     * second transaction (another BEGIN/COMMIT boundary). Runs the tailer in INBOX mode WITHOUT a
     * dispatcher so the rows stay {@code RECEIVED} and their LSNs can be inspected directly.
     */
    @Test
    void pgoutput_inbox_persists_a_distinct_lsn_per_message_across_a_multi_statement_transaction() {
        String slotName = slotName();
        String publicationName = publicationName();
        createPublication(publicationName);

        AggregateTypeResolver resolver = table -> "orders_events".equalsIgnoreCase(table) ? ORDERS : null;
        var pgOutputConverter = new PgOutputToPersistedEventConverter(jacksonJSONSerializer, resolver);

        var availability = new CdcAvailability();
        var tailer = new WalReplicationTailer(
                replicationDataSource,
                jdbi,
                unitOfWorkFactory,
                slotName,
                inboxRepository,
                tailerProperties(),
                PgSlotMode.CREATE_IF_MISSING,
                CdcMode.AUTO,
                CdcDeliveryMode.INBOX,
                pgOutputPlugin(publicationName, pgOutputConverter),
                Optional.empty(), // INBOX mode → no direct consumer
                Optional.empty(),
                availability,
                Optional.empty(),
                Optional.empty(),
                Optional.of(eventStreamTablesSupplier()),
                false
        );

        tailer.startAndAwaitReady(Duration.ofSeconds(10));
        appendOrderEvents();       // 4 INSERTs in ONE transaction (first append → RELATION boundary)
        appendOneMoreOrderEvent(); // a 2nd transaction → another BEGIN/COMMIT boundary

        // The pgoutput pre-filter persists 'R' (RELATION) and 'I' (INSERT) messages, dropping
        // BEGIN/COMMIT. So the inbox receives 1 RELATION (emitted once, before the first INSERT for
        // orders_events) + 5 INSERTs = 6 rows. No dispatcher runs, so they remain RECEIVED. Crucially,
        // a RELATION→INSERT LSN collision (the documented risk) would dedup the first INSERT away and
        // we'd see fewer than 6 distinct LSNs.
        await()
                .atMost(Duration.ofSeconds(20))
                .pollInterval(Duration.ofMillis(100))
                .untilAsserted(() -> assertThat(inboxRepository.countByStatus(slotName, "RECEIVED")).isGreaterThanOrEqualTo(6L));

        var lsns = jdbi.withHandle(handle -> handle.createQuery(
                                                   "select lsn from " + CdcSql.DEFAULT_CDC_TABLE_NAME + " where slot_name = :slot")
                                                   .bind("slot", slotName)
                                                   .mapTo(String.class)
                                                   .list());

        assertThat(lsns).as("1 RELATION + 5 INSERT messages persisted").hasSizeGreaterThanOrEqualTo(6);
        assertThat(Set.copyOf(lsns))
                .as("every persisted WAL message (RELATION and INSERTs alike) must carry a distinct LSN — the inbox unique(slot_name, lsn) dedup key depends on it")
                .hasSize(lsns.size());

        tailer.stop();
    }

    private WalReplicationTailer directPgOutputTailer(String slotName,
                                                       String publicationName,
                                                       PgOutputToPersistedEventConverter pgOutputConverter,
                                                       List<PersistedEvent> persistedEvents) {
        var availability = new CdcAvailability();
        return new WalReplicationTailer(
                replicationDataSource,
                jdbi,
                unitOfWorkFactory,
                slotName,
                inboxRepository,
                tailerProperties(),
                PgSlotMode.CREATE_IF_MISSING,
                CdcMode.AUTO,
                CdcDeliveryMode.DIRECT,
                pgOutputPlugin(publicationName, pgOutputConverter),
                Optional.of(persistedEvents::addAll),
                Optional.empty(),
                availability,
                Optional.empty(),
                Optional.empty(),
                Optional.of(eventStreamTablesSupplier()),
                false
        );
    }

    /**
     * Live supplier of registered event-stream table names for this test class. Mirrors the
     * production {@code WalMessageFilter} wiring (Spring autoconfig pulls
     * {@code persistenceStrategy.getSeparateTablePerEventStreamTableNameAggregates().keySet()})
     * so the pgoutput pre-filter can correctly decide which {@code I}/{@code R} messages to
     * keep.
     */
    private static Supplier<Set<String>> eventStreamTablesSupplier() {
        return () -> Set.of("orders_events");
    }

    private void appendOneMoreOrderEvent() {
        var orderId = OrderId.of("beed77fb-1115-1115-9c48-03ed5bfe8f89");
        var uow = unitOfWorkFactory.getOrCreateNewUnitOfWork();
        eventStore.appendToStream(ORDERS, orderId, List.of(new OrderEvent.OrderAccepted(orderId)));
        uow.commit();
    }

    private void appendOrderEvents() {
        var orderId = OrderId.of("beed77fb-1115-1115-9c48-03ed5bfe8f89");
        var persistableEvents = List.of(
                new OrderEvent.OrderAdded(orderId, CustomerId.of("Test-Customer-Id-15"), 1234),
                new OrderEvent.ProductAddedToOrder(orderId, ProductId.of("ProductId-1"), 2),
                new OrderEvent.ProductAddedToOrder(orderId, ProductId.of("ProductId-2"), 1),
                new OrderEvent.ProductRemovedFromOrder(orderId, ProductId.of("ProductId-1"))
        );

        var uow = unitOfWorkFactory.getOrCreateNewUnitOfWork();
        eventStore.appendToStream(ORDERS, orderId, persistableEvents);
        uow.commit();
    }

    private void createPublication(String publicationName) {
        jdbi.useHandle(handle -> {
            handle.execute("drop publication if exists " + publicationName);
            handle.execute("create publication " + publicationName + " for table orders_events");
        });
    }

    private static String slotName() {
        return "slot_" + UUID.randomUUID().toString().replace("-", "");
    }

    private static String publicationName() {
        return "pub_" + UUID.randomUUID().toString().replace("-", "").substring(0, 16);
    }

    private static WalReplicationTailerProperties tailerProperties() {
        return WalReplicationTailerProperties.defaults(
                Duration.ofMillis(10),
                Duration.ofMillis(50),
                Duration.ofSeconds(2),
                Duration.ofMillis(250)
        );
    }

    private static PgOutputLogicalDecodingPlugin pgOutputPlugin(String publicationName, PgOutputToPersistedEventConverter converter) {
        var properties = new PgOutputProperties();
        properties.setPublicationName(publicationName);
        properties.setProtoVersion(1);
        properties.setBinary(false);
        properties.setMessages(false);
        return new PgOutputLogicalDecodingPlugin(properties, converter);
    }
}
