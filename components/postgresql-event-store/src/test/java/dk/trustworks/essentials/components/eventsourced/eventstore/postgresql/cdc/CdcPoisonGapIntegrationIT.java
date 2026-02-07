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

import dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.*;
import dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.cdc.CdcProperties.CdcDispatcherProperties;
import dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.cdc.converter.*;
import dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.eventstream.*;
import dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.gap.*;
import dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.persistence.table_per_aggregate_type.*;
import dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.processor.EventProcessorIT;
import dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.serializer.json.JacksonJSONEventSerializer;
import dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.test_data.*;
import dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.types.GlobalEventOrder;
import dk.trustworks.essentials.components.foundation.transaction.UnitOfWork;
import dk.trustworks.essentials.components.foundation.types.SubscriberId;
import org.awaitility.Awaitility;
import org.junit.jupiter.api.*;
import org.testcontainers.junit.jupiter.Testcontainers;
import reactor.core.publisher.Flux;
import reactor.test.StepVerifier;

import java.time.Duration;
import java.util.*;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Function;

import static dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.processor.EventProcessorIT.createObjectMapper;
import static org.assertj.core.api.Assertions.assertThat;

@Testcontainers
public class CdcPoisonGapIntegrationIT extends AbstractWal2JsonPostgresIT {

    private PostgresqlEventStore<SeparateTablePerAggregateEventStreamConfiguration> eventStore;
    private CdcEventStore<SeparateTablePerAggregateEventStreamConfiguration>        cdcEventStore;
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
                        SeparateTablePerAggregateTypeEventStreamConfigurationFactory.defaultConfiguration(
                                jacksonJSONSerializer)
                );

        persistenceStrategy.addAggregateEventStreamConfiguration(ORDERS, OrderId.class);

        eventStore = new PostgresqlEventStore<>(unitOfWorkFactory, persistenceStrategy);
        gapHandler = new PostgresqlEventStreamGapHandler<>(eventStore, unitOfWorkFactory);

        cdcEventStore = new CdcEventStore<>(eventStore, unitOfWorkFactory, gapHandler, new CdcEventBus(), new CdcProperties());

        inboxRepository = new CdcInboxRepository(unitOfWorkFactory);
    }

    @AfterEach
    void cleanup() {
        unitOfWorkFactory.getCurrentUnitOfWork().ifPresent(UnitOfWork::rollback);
        assertThat(unitOfWorkFactory.getCurrentUnitOfWork()).isEmpty();
    }

    @Test
    void dispatcher_quarantines_poison_registers_permanent_gap_and_notifies_and_pollEvents_does_not_stall() {

        // Given
        String slotName = "slot_" + UUID.randomUUID().toString().replace("-", "");

        var gapHandler = new PostgresqlEventStreamGapHandler<>(eventStore,
                                                               unitOfWorkFactory);

        AggregateTypeResolver resolver = table -> {
            if ("orders_events".equalsIgnoreCase(table)) return ORDERS;
            return null;
        };

        var converter = new JacksonWal2JsonToPersistedEventConverter(jacksonJSONSerializer, resolver);

        var walGlobalOrdersExtractor = new JacksonWalGlobalOrdersExtractor(
                jacksonJSONSerializer,
                resolver
        );

        var poisonNotifier = new RecordingPoisonNotifier();

        List<PersistedEvent> cdcBus = new CopyOnWriteArrayList<>();

        var dispatcher = new CdcDispatcher(
                inboxRepository,
                unitOfWorkFactory,
                gapHandler,
                converter,
                walGlobalOrdersExtractor,
                Optional.of(poisonNotifier),
                cdcBus::addAll,
                slotName,
                CdcDispatcherProperties.defaults()
        );

        var orderId = OrderId.of("beed77fb-1115-1115-9c48-03ed5bfe8f89");
        var persistableEvents = List.of(
                new OrderEvent.OrderAdded(orderId, CustomerId.of("Test-Customer-Id-15"), 1234),
                new OrderEvent.ProductAddedToOrder(orderId, ProductId.of("ProductId-1"), 2),
                new OrderEvent.ProductRemovedFromOrder(orderId, ProductId.of("ProductId-1"))
                                       );

        var persistedStream = unitOfWorkFactory.withUnitOfWork(uow -> eventStore.appendToStream(ORDERS, orderId, persistableEvents));

        var dbPersisted = persistedStream.eventList();
        assertThat(dbPersisted).hasSize(3);
        assertThat(dbPersisted.get(0).globalEventOrder().longValue()).isEqualTo(1L);
        assertThat(dbPersisted.get(1).globalEventOrder().longValue()).isEqualTo(2L);
        assertThat(dbPersisted.get(2).globalEventOrder().longValue()).isEqualTo(3L);

        // Create an ACTUAL DB gap by deleting global_order=2
        // (So "subscription continues" is meaningful)
        unitOfWorkFactory.usingUnitOfWork(tx -> {
            tx.handle().execute("delete from orders_events where global_order = 2");
        });

        // Sanity: DB now has only global orders 1 and 3
        var highest = unitOfWorkFactory.withUnitOfWork(() -> eventStore.findHighestGlobalEventOrderPersisted(ORDERS).orElseThrow());
        assertThat(highest.longValue()).isEqualTo(3L);

        // Inject a poison wal2json message that:
        // - is VALID JSON
        // - contains an insert with global_order=2
        // - will FAIL conversion because timestamp uses "YYYY-MM-DD HH:MM:SS..." which OffsetDateTime.parse can't parse
        String poisonWal = """
                           {
                             "xid": 999,
                             "nextlsn": "0/0",
                             "timestamp": "2026-01-27 15:38:10.735471+01",
                             "change": [
                               {
                                 "kind": "insert",
                                 "schema": "public",
                                 "table": "orders_events",
                                 "columnnames": ["global_order","aggregate_id","event_order","event_id","caused_by_event_id","correlation_id","event_type","event_revision","timestamp","event_payload","event_metadata","tenant"],
                                 "columntypes":  ["bigint","text","bigint","text","text","text","text","text","timestamp with time zone","jsonb","jsonb","text"],
                                 "columnvalues": [
                                   2,
                                   "beed77fb-1115-1115-9c48-03ed5bfe8f89",
                                   1,
                                   "00000000-0000-0000-0000-000000000002",
                                   null,
                                   null,
                                   "FQCN:dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.test_data.OrderEvent$ProductAddedToOrder",
                                   "not-an-int",
                                   "2026-01-27 15:38:10.67955+01",
                                   {"orderId":"beed77fb-1115-1115-9c48-03ed5bfe8f89","productId":"ProductId-1","quantity":2},
                                   {},
                                   null
                                 ]
                               }
                             ]
                           }
                           """;

        // Put it into inbox as RECEIVED (bypassing tailer)
        inboxRepository.insertRaw(slotName, "0/POISON", poisonWal, "RECEIVED");

        // When: start dispatcher
        dispatcher.start();

        // Then: dispatcher should quarantine it and register permanent gap=2 for ORDERS
        Awaitility.await()
                  .atMost(Duration.ofSeconds(10))
                  .pollInterval(Duration.ofMillis(100))
                  .untilAsserted(() -> {
                      assertThat(inboxRepository.countByStatus(slotName, "POISON")).isEqualTo(1);

                      // verify permanent gap exists
                      var permanentGaps = unitOfWorkFactory.withUnitOfWork(() -> gapHandler.getPermanentGapsFor(ORDERS)).toList();
                      assertThat(permanentGaps).contains(GlobalEventOrder.of(2L));

                      assertThat(poisonNotifier.calls).hasSize(1);
                      var call = poisonNotifier.calls.get(0);
                      assertThat(call.aggregateType().getValue()).isEqualTo(ORDERS.getValue());
                      assertThat(call.gaps()).containsExactly(GlobalEventOrder.of(2L));
                      assertThat(call.reason()).contains("cdc-poison:0/POISON");
                  });

        // And: a subscription polling from 1 should not stall on missing 2.
        // It should deliver 1 and 3 (order preserved), and then keep moving.
        StepVerifier.create(
                            cdcEventStore.pollEvents(
                                                 ORDERS,
                                                 GlobalEventOrder.of(1L),
                                                 Optional.of(10),
                                                 Optional.of(Duration.ofMillis(50)),
                                                 Optional.empty(),
                                                 Optional.of(SubscriberId.of("it-subscriber")),
                                                 Optional.<Function<String, EventStorePollingOptimizer>>empty()
                                                    )
                                         .take(2) // expect 2 events: GO=1 and GO=3
                           )
                    .assertNext(e -> assertThat(e.globalEventOrder().longValue()).isEqualTo(1L))
                    .assertNext(e -> assertThat(e.globalEventOrder().longValue()).isEqualTo(3L))
                    .expectComplete()
                    .verify(Duration.ofSeconds(5));

        dispatcher.stop();
    }

    @Test
    void hybrid_pollEvents_backfill_then_live_continues_after_poison_gap() {

        // Given
        AggregateType aggregateType = ORDERS;
        SubscriberId  subscriberId  = SubscriberId.of("hybrid-it");

        // Persist initial events: global orders 1,2,3
        var orderId = OrderId.of("beed77fb-1115-1115-9c48-03ed5bfe8f89");
        var events = List.of(
                new OrderEvent.OrderAdded(orderId, CustomerId.of("C1"), 100),
                new OrderEvent.ProductAddedToOrder(orderId, ProductId.of("P1"), 2),
                new OrderEvent.ProductRemovedFromOrder(orderId, ProductId.of("P1"))
                            );

        // Delete GO=2 to create a REAL gap
        unitOfWorkFactory.usingUnitOfWork(uow -> {
            eventStore.appendToStream(aggregateType, orderId, events);
        });

        unitOfWorkFactory.usingUnitOfWork(uow -> {
            uow.handle().execute("delete from orders_events where global_order = 2");
        });

        unitOfWorkFactory.usingUnitOfWork(uow -> {
            gapHandler.registerPermanentGaps(
                    aggregateType,
                    List.of(GlobalEventOrder.of(2)),
                    "poison wal2json"
                                            );
        });

        Flux<PersistedEvent> flux =
                cdcEventStore.pollEvents(
                        aggregateType,
                        1L,
                        Optional.of(10),
                        Optional.of(Duration.ofMillis(50)),
                        Optional.empty(),
                        Optional.of(subscriberId),
                        Optional.empty()
                                        );

        // When / Then
        StepVerifier.create(flux)
                    .assertNext(e -> assertThat(e.globalEventOrder().longValue()).isEqualTo(1))
                    .assertNext(e -> assertThat(e.globalEventOrder().longValue()).isEqualTo(3))
                    .thenCancel()
                    .verify();

        // Now append a NEW event (GO=4)
        unitOfWorkFactory.usingUnitOfWork(uow -> {
            eventStore.appendToStream(
                    aggregateType,
                    orderId,
                    List.of(new OrderEvent.OrderAdded(orderId, CustomerId.of("C2"), 200))
                                     );
        });

        Flux<PersistedEvent> flux2 =
                cdcEventStore.pollEvents(
                        aggregateType,
                        1L,
                        Optional.of(10),
                        Optional.of(Duration.ofMillis(50)),
                        Optional.empty(),
                        Optional.of(subscriberId),
                        Optional.empty()
                                        );

        // And it should arrive LIVE
        StepVerifier.create(flux2.skip(2).take(1))
                    .assertNext(e -> assertThat(e.globalEventOrder().longValue()).isEqualTo(4))
                    .verifyComplete();
    }

}
