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

import dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.PostgresqlEventStore;
import dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.cdc.CdcProperties.*;
import dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.cdc.converter.*;
import dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.eventstream.*;
import dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.gap.*;
import dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.persistence.table_per_aggregate_type.*;
import dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.processor.EventProcessorIT;
import dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.serializer.TenantSerializer;
import dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.serializer.json.JacksonJSONEventSerializer;
import dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.test_data.*;
import dk.trustworks.essentials.components.foundation.transaction.UnitOfWork;
import org.junit.jupiter.api.*;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.time.Duration;
import java.util.*;
import java.util.concurrent.CopyOnWriteArrayList;

import static dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.processor.EventProcessorIT.createObjectMapper;
import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

@Testcontainers
public class Wal2JsonWithEssentialsAggregateIT extends AbstractWal2JsonPostgresIT {

    private PostgresqlEventStore<SeparateTablePerAggregateEventStreamConfiguration> eventStore;
    private EventProcessorIT.TestPersistableEventMapper                             eventMapper;
    private JacksonJSONEventSerializer                                              jacksonJSONSerializer;
    private CdcInboxRepository                                                      inboxRepository;
    private EventStreamGapHandler<?>                                                gapHandler;

    @BeforeEach
    void setup() {
        jacksonJSONSerializer = new JacksonJSONEventSerializer(createObjectMapper());
        eventMapper = new EventProcessorIT.TestPersistableEventMapper();

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
    void wal2json_converter_emits_same_PersistedEvents_as_eventStore_for_real_aggregate_append() {
        String slotName = "slot_" + UUID.randomUUID().toString().replace("-", "");

        AggregateTypeResolver resolver = table -> {
            if ("orders_events".equalsIgnoreCase(table)) return ORDERS;
            return null;
        };
        var converter = new JacksonWal2JsonToPersistedEventConverter(jacksonJSONSerializer, resolver);
        var extractor = new JacksonWalGlobalOrdersExtractor(jacksonJSONSerializer, resolver);

        List<PersistedEvent> cdcPersistedEvents = new CopyOnWriteArrayList<>();

        var cfg = Wal2JsonTailerProperties.defaults(
                Duration.ofMillis(10),
                Duration.ofMillis(50),
                Duration.ofSeconds(2),
                Duration.ofMillis(250)
                                                                 );

        var tailer = new Wal2JsonTailer(
                replicationDataSource,
                jdbi,
                unitOfWorkFactory,
                slotName,
                inboxRepository,
                cfg,
                PgSlotMode.CREATE_IF_MISSING,
                Optional.empty(),
                Optional.empty()
        );

        var dispatcher = new CdcDispatcher(
                inboxRepository,
                unitOfWorkFactory,
                gapHandler,
                converter,
                extractor,
                Optional.empty(),
                cdcPersistedEvents::addAll,
                slotName,
                CdcDispatcherProperties.defaults()
        );

        tailer.startAndAwaitReady(Duration.ofSeconds(10));
        dispatcher.start();

        // Inject a poison row that will fail conversion (invalid json)
        // IMPORTANT: don't use "0/0" - make it unique and non-realistic but stable
        String poisonLsn = "0/DEADBEEF";
        inboxRepository.insertRaw(slotName, poisonLsn, "not-json", "RECEIVED");

        // Now append real events, which will generate real wal2json messages
        var orderId = OrderId.of("beed77fb-1115-1115-9c48-03ed5bfe8f89");
        var persistableEvents = List.of(
                new OrderEvent.OrderAdded(orderId, CustomerId.of("Test-Customer-Id-15"), 1234),
                new OrderEvent.ProductAddedToOrder(orderId, ProductId.of("ProductId-1"), 2),
                new OrderEvent.ProductRemovedFromOrder(orderId, ProductId.of("ProductId-1"))
                                       );

        var uow = unitOfWorkFactory.getOrCreateNewUnitOfWork();
        eventStore.appendToStream(ORDERS, orderId, persistableEvents);
        uow.commit();

        // Then: poison should be quarantined + valid events should still be processed
        await()
                .atMost(Duration.ofSeconds(20))
                .pollInterval(Duration.ofMillis(100))
                .untilAsserted(() -> {
                    assertThat(inboxRepository.countByStatus(slotName, "POISON")).isEqualTo(1);
                    assertThat(cdcPersistedEvents).hasSizeGreaterThanOrEqualTo(3);
                });

        dispatcher.stop();
        tailer.stop();    }

    @Test
    void dispatcher_quarantines_poison_inbox_row_and_continues_processing_later_valid_rows() {
        String slotName = "slot_" + UUID.randomUUID().toString().replace("-", "");

        AggregateTypeResolver resolver = table -> {
            if ("orders_events".equalsIgnoreCase(table)) return ORDERS;
            return null;
        };
        var converter = new JacksonWal2JsonToPersistedEventConverter(jacksonJSONSerializer, resolver);
        var extractor = new JacksonWalGlobalOrdersExtractor(jacksonJSONSerializer, resolver);

        List<PersistedEvent> cdcPersistedEvents = new CopyOnWriteArrayList<>();

        var cfg = Wal2JsonTailerProperties.defaults(
                Duration.ofMillis(10),
                Duration.ofMillis(50),
                Duration.ofSeconds(2),
                Duration.ofMillis(250)
                                        );

        var tailer = new Wal2JsonTailer(
                replicationDataSource,
                jdbi,
                unitOfWorkFactory,
                slotName,
                inboxRepository,
                cfg,
                PgSlotMode.CREATE_IF_MISSING,
                Optional.empty(),
                Optional.empty()
        );

        var dispatcher = new CdcDispatcher(
                inboxRepository,
                unitOfWorkFactory,
                gapHandler,
                converter,
                extractor,
                Optional.empty(),
                cdcPersistedEvents::addAll,
                slotName,
                CdcDispatcherProperties.defaults()
        );

        tailer.startAndAwaitReady(Duration.ofSeconds(10));
        dispatcher.start();

        // Inject a poison row that will fail conversion (invalid json)
        String poisonLsn = "0/DEADBEEF";
        inboxRepository.insertRaw(slotName, poisonLsn, "not-json", "RECEIVED");

        // Now append real events, which will generate real wal2json messages
        var orderId = OrderId.of("beed77fb-1115-1115-9c48-03ed5bfe8f89");
        var persistableEvents = List.of(
                new OrderEvent.OrderAdded(orderId, CustomerId.of("Test-Customer-Id-15"), 1234),
                new OrderEvent.ProductAddedToOrder(orderId, ProductId.of("ProductId-1"), 2),
                new OrderEvent.ProductRemovedFromOrder(orderId, ProductId.of("ProductId-1"))
                                       );

        var uow = unitOfWorkFactory.getOrCreateNewUnitOfWork();
        eventStore.appendToStream(ORDERS, orderId, persistableEvents);
        uow.commit();

        // Then: poison should be quarantined + valid events should still be processed
        await()
                .atMost(Duration.ofSeconds(20))
                .pollInterval(Duration.ofMillis(100))
                .untilAsserted(() -> {
                    assertThat(inboxRepository.countByStatus(slotName, "POISON")).isEqualTo(1);
                    assertThat(cdcPersistedEvents).hasSizeGreaterThanOrEqualTo(3);
                });

        dispatcher.stop();
        tailer.stop();
    }

}
