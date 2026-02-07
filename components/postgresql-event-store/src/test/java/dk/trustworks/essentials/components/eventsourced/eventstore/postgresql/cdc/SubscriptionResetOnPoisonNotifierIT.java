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

import dk.trustworks.essentials.components.distributed.fencedlock.postgresql.PostgresqlFencedLockManager;
import dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.*;
import dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.cdc.CdcProperties.CdcDispatcherProperties;
import dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.cdc.converter.*;
import dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.eventstream.*;
import dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.gap.*;
import dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.persistence.table_per_aggregate_type.*;
import dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.processor.EventProcessorIT;
import dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.serializer.json.JacksonJSONEventSerializer;
import dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.subscription.*;
import dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.test_data.*;
import dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.types.GlobalEventOrder;
import dk.trustworks.essentials.components.foundation.fencedlock.*;
import dk.trustworks.essentials.components.foundation.transaction.UnitOfWork;
import dk.trustworks.essentials.components.foundation.types.SubscriberId;
import org.awaitility.Awaitility;
import org.junit.jupiter.api.*;
import reactor.core.Disposable;
import reactor.core.publisher.Flux;

import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

import static dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.processor.EventProcessorIT.createObjectMapper;
import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

public class SubscriptionResetOnPoisonNotifierIT extends AbstractWal2JsonPostgresIT {

    private PostgresqlEventStore<SeparateTablePerAggregateEventStreamConfiguration> eventStore;
    private CdcEventStore<SeparateTablePerAggregateEventStreamConfiguration>        cdcEventStore;
    private EventProcessorIT.TestPersistableEventMapper                             eventMapper;
    private JacksonJSONEventSerializer                                              jacksonJSONSerializer;
    private CdcInboxRepository                                                      inboxRepository;
    private EventStreamGapHandler<?>                                                gapHandler;
    private DurableSubscriptionRepository                                           durableSubscriptionRepository;
    private FencedLockManager                                                       fencedLockManager;
    private EventStoreSubscriptionManager                                           eventStoreSubscriptionManager;

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

        durableSubscriptionRepository = new PostgresqlDurableSubscriptionRepository(jdbi, cdcEventStore);

        inboxRepository = new CdcInboxRepository(unitOfWorkFactory);

        fencedLockManager = new PostgresqlFencedLockManager(jdbi, unitOfWorkFactory, Optional.empty(), Duration.ofSeconds(3), Duration.ofMillis(500), false);

        eventStoreSubscriptionManager = new DefaultEventStoreSubscriptionManager(cdcEventStore,
                                                                                 100,
                                                                                 Duration.ofMillis(25),
                                                                                 fencedLockManager,
                                                                                 Duration.ofMillis(75),
                                                                                 durableSubscriptionRepository,
                                                                                 true);
    }

    @AfterEach
    void cleanup() {
        unitOfWorkFactory.getCurrentUnitOfWork().ifPresent(UnitOfWork::rollback);
        assertThat(unitOfWorkFactory.getCurrentUnitOfWork()).isEmpty();
    }

    @Test
    void resets_resume_point_in_db_immediately_after_poison_for_non_transaction_subscription() throws InterruptedException {
        fencedLockManager.start();
        eventStoreSubscriptionManager.start();

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

        var poisonNotifier = new SubscriptionResetOnPoisonNotifier(
                eventStoreSubscriptionManager /* created below */,
                durableSubscriptionRepository
        );

        var dispatcher = new CdcDispatcher(
                inboxRepository,
                unitOfWorkFactory,
                gapHandler,
                converter,
                walGlobalOrdersExtractor,
                Optional.of(poisonNotifier),
                cdcEventStore.getCdcBus()::publish, // publish converted events
                slotName,
                CdcDispatcherProperties.defaults()
        );

        var received   = new CopyOnWriteArrayList<Long>();
        var resets     = new CopyOnWriteArrayList<Long>();
        var resetLatch = new CountDownLatch(1);

        var subscriberId = SubscriberId.of("orders-sub-it");

        eventStoreSubscriptionManager.exclusivelySubscribeToAggregateEventsAsynchronously(
                subscriberId,
                ORDERS,
                at -> GlobalEventOrder.FIRST_GLOBAL_EVENT_ORDER,
                Optional.empty(),
                new FencedLockAwareSubscriber() {
                    @Override
                    public void onLockAcquired(FencedLock lock, SubscriptionResumePoint rp) {
                    }

                    @Override
                    public void onLockReleased(FencedLock lock) {
                    }
                },
                new PersistedEventHandler() {
                    @Override
                    public void onResetFrom(EventStoreSubscription s, GlobalEventOrder g) {
                        resets.add(g.longValue());
                        resetLatch.countDown();
                    }

                    @Override
                    public void handle(PersistedEvent e) {
                        received.add(e.globalEventOrder().longValue());
                    }
                }
                                                                                         );

        // Start dispatcher (tailer can be omitted here; we inject poison row directly)
        dispatcher.start();

        // --- When: produce normal events 1..5 ---
        var orderId = OrderId.of("beed77fb-1115-1115-9c48-03ed5bfe8f89");

        unitOfWorkFactory.usingUnitOfWork(uow -> {
            eventStore.appendToStream(
                    ORDERS,
                    orderId,
                    List.of(
                            new OrderEvent.OrderAdded(orderId, CustomerId.of("C1"), 100),
                            new OrderEvent.ProductAddedToOrder(orderId, ProductId.of("P1"), 2),
                            new OrderEvent.ProductRemovedFromOrder(orderId, ProductId.of("P1"))
                           )
                                     );
        });

        unitOfWorkFactory.usingUnitOfWork(uow -> {
            eventStore.appendToStream(
                    ORDERS,
                    orderId,
                    List.of(
                            new OrderEvent.OrderAdded(orderId, CustomerId.of("C2"), 200),
                            new OrderEvent.OrderAdded(orderId, CustomerId.of("C3"), 300)
                           )
                                     );
        });

        // Wait until subscription has consumed at least 5 events
        await()
                  .atMost(Duration.ofSeconds(10))
                  .pollInterval(Duration.ofMillis(50))
                  .untilAsserted(() -> assertThat(received).hasSizeGreaterThanOrEqualTo(5));

        // Ensure durable resume point has been persisted (should be >= 6)
        await()
                  .atMost(Duration.ofSeconds(10))
                  .pollInterval(Duration.ofMillis(50))
                  .untilAsserted(() -> {
                      var rp = durableSubscriptionRepository.getResumePoint(subscriberId, ORDERS).orElseThrow();
                      assertThat(rp.getResumeFromAndIncluding().longValue()).isGreaterThanOrEqualTo(6L);
                  });

        // --- Inject poison WAL into inbox that extracts gap global_order=2 and fails conversion ---
        // Use a failure that is guaranteed: event_revision "not-an-int" (as you already observed)
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
                                   {"orderId":"beed77fb-1115-1115-9c48-03ed5bfe8f89","productId":"P1","quantity":2},
                                   {},
                                   null
                                 ]
                               }
                             ]
                           }
                           """;

        inboxRepository.insertRaw(slotName, "0/POISON", poisonWal, "RECEIVED");

        // --- Then: reset is invoked and durable resume point forced to 2 ---
        assertThat(resetLatch.await(10, TimeUnit.SECONDS)).isTrue();
        assertThat(resets).contains(2L);

        await()
                  .atMost(Duration.ofSeconds(5))
                  .pollInterval(Duration.ofMillis(50))
                  .untilAsserted(() -> {
                      var rp = durableSubscriptionRepository.getResumePoint(subscriberId, ORDERS).orElseThrow();
                      assertThat(rp.getResumeFromAndIncluding().longValue()).isEqualTo(2L);
                  });

        // And: subscription should not stall; append another event and verify we eventually see a higher GO
        unitOfWorkFactory.usingUnitOfWork(uow -> {
            eventStore.appendToStream(
                    ORDERS,
                    orderId,
                    List.of(new OrderEvent.OrderAdded(orderId, CustomerId.of("C4"), 400))
                                     );
        });

        await()
                  .atMost(Duration.ofSeconds(10))
                  .pollInterval(Duration.ofMillis(50))
                  .untilAsserted(() -> {
                      // We don’t assert exact ordering here (reset can cause replays)
                      // But we *must* see progress beyond 5 (e.g. 6)
                      assertThat(received.stream().max(Long::compareTo).orElse(0L)).isGreaterThanOrEqualTo(6L);
                  });


        dispatcher.stop();
        fencedLockManager.stop();
        eventStoreSubscriptionManager.stop();
    }

    @Test
    void hybrid_pollEvents_live_arrives_during_backfill_is_ordered_correctly() {

        AggregateType aggregateType = ORDERS;
        SubscriberId  subscriberId  = SubscriberId.of("ordering-it");

        // Persist 3 events → backfill range
        var orderId = OrderId.of("beed77fb-1115-1115-9c48-03ed5bfe8f89");

        unitOfWorkFactory.usingUnitOfWork(uow -> {
            eventStore.appendToStream(
                    aggregateType,
                    orderId,
                    List.of(
                            new OrderEvent.OrderAdded(orderId, CustomerId.of("C1"), 100),
                            new OrderEvent.ProductAddedToOrder(orderId, ProductId.of("P1"), 2),
                            new OrderEvent.ProductRemovedFromOrder(orderId, ProductId.of("P1"))
                           )
                                     );
        });

        // Capture emitted events
        List<Long> receivedOrders = new CopyOnWriteArrayList<>();

        Flux<PersistedEvent> flux =
                cdcEventStore.pollEvents(
                        aggregateType,
                        GlobalEventOrder.FIRST_GLOBAL_EVENT_ORDER,
                        Optional.of(1),
                        Optional.of(Duration.ofMillis(50)),
                        Optional.empty(),
                        Optional.of(subscriberId),
                        Optional.empty()
                                        );

        Disposable sub = flux.subscribe(e -> {
            receivedOrders.add(e.globalEventOrder().longValue());
        });

        // Wait until backfill emits first event
        await()
                  .atMost(Duration.ofSeconds(5))
                  .untilAsserted(() ->
                                         assertThat(receivedOrders).contains(1L)
                                );

        // 🔥 Inject live CDC event *while backfill is still running*
        unitOfWorkFactory.usingUnitOfWork(uow -> {
            eventStore.appendToStream(
                    aggregateType,
                    orderId,
                    List.of(new OrderEvent.OrderAdded(orderId, CustomerId.of("C2"), 200))
                                     );
        });

        // Then: must eventually emit 1,2,3,4 IN ORDER
        await()
                  .atMost(Duration.ofSeconds(10))
                  .untilAsserted(() ->
                                         assertThat(receivedOrders)
                                                 .containsExactly(1L, 2L, 3L, 4L)
                                );

        sub.dispose();
    }

    @Test
    void reset_after_poison_rewinds_subscription_and_persists_resume_point_immediately() {
        // --- Given ---------------------------------------------------------------
        fencedLockManager.start();

        AggregateType aggregateType = ORDERS;
        SubscriberId  subscriberId  = SubscriberId.of("sub-1");

        // IMPORTANT for this test:
        // Disable the subscription manager periodic saver so it doesn't race and overwrite
        // the "immediate" rewind we expect to observe in durable storage.
        //
        // If you want TRACE for durable resume writes, add in logback-test.xml:
        // <logger name="dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.subscription.PostgresqlDurableSubscriptionRepository" level="TRACE"/>
        //
        var eventStorePollingBatchSize = 50;
        var eventStorePollingInterval  = Duration.ofMillis(25);

        var snapshotResumePointsEvery = Duration.ofHours(1); // effectively "off" for this test

        var subscriptionManager =
                new DefaultEventStoreSubscriptionManager(
                        cdcEventStore,
                        eventStorePollingBatchSize,
                        eventStorePollingInterval,
                        fencedLockManager,
                        snapshotResumePointsEvery,
                        durableSubscriptionRepository,
                        true
                );

        subscriptionManager.start();

        // Collect received global orders to prove rewind causes replay
        var receivedGlobalOrders = new CopyOnWriteArrayList<Long>();
        var go5Count             = new AtomicInteger(0);

        var subscription =
                subscriptionManager.subscribeToAggregateEventsAsynchronously(
                        subscriberId,
                        aggregateType,
                        GlobalEventOrder.FIRST_GLOBAL_EVENT_ORDER,
                        Optional.empty(),
                        new PersistedEventHandler() {
                            @Override
                            public void onResetFrom(EventStoreSubscription eventStoreSubscription, GlobalEventOrder globalEventOrder) {
                                // real handler could clear caches, etc. We just observe.
                            }

                            @Override
                            public void handle(PersistedEvent event) {
                                long go = event.globalEventOrder().longValue();
                                receivedGlobalOrders.add(go);
                                if (go == 5L) go5Count.incrementAndGet();
                            }
                        }
                                                                            );

        // Persist enough events so the subscriber can advance beyond 5 (to prove rewind is meaningful)
        // Create 12 events => expected resumeFromAndIncluding ends up >= 13 eventually
        var orderId = OrderId.of("beed77fb-1115-1115-9c48-03ed5bfe8f89");
        unitOfWorkFactory.usingUnitOfWork(uow -> {
            var events = new ArrayList<Object>();
            for (int i = 0; i < 12; i++) {
                events.add(new OrderEvent.OrderAdded(orderId, CustomerId.of("C" + i), 100 + i));
            }
            eventStore.appendToStream(aggregateType, orderId, events);
        });

        // Wait until the subscription has definitely processed past the gap (so current resume > 5)
        Awaitility.await()
                  .atMost(Duration.ofSeconds(15))
                  .pollInterval(Duration.ofMillis(50))
                  .untilAsserted(() -> {
                      var current = subscription.currentResumePoint()
                                                .map(SubscriptionResumePoint::getResumeFromAndIncluding)
                                                .orElseThrow();

                      // past 11 like your log
                      assertThat(current.longValue()).isGreaterThanOrEqualTo(11L);

                      // and we have seen GO=5 at least once before reset
                      assertThat(go5Count.get()).isGreaterThanOrEqualTo(1);
                  });

        // Sanity: durable will likely be ahead too (depending on your impl), but not required.
        // ------------------------------------------------------------------------

        var poisonNotifier = new SubscriptionResetOnPoisonNotifier(
                subscriptionManager,
                durableSubscriptionRepository
        );

        // --- When ---------------------------------------------------------------
        // We "discover poison" affecting GO=5
        poisonNotifier.onPoison(
                aggregateType,
                List.of(GlobalEventOrder.of(5L)),
                "it-poison"
                               );

        // --- Then ---------------------------------------------------------------

        // 1) Durable resume point must be persisted immediately to 5
        Awaitility.await()
                  .atMost(Duration.ofSeconds(5))
                  .pollInterval(Duration.ofMillis(50))
                  .untilAsserted(() -> {
                      var durable = durableSubscriptionRepository
                              .getResumePoint(subscriberId, aggregateType)
                              .orElseThrow()
                              .getResumeFromAndIncluding()
                              .longValue();

                      assertThat(durable).isEqualTo(5L);
                  });

        // 2) And we should observe a replay from 5 (GO=5 seen at least twice)
        Awaitility.await()
                  .atMost(Duration.ofSeconds(15))
                  .pollInterval(Duration.ofMillis(50))
                  .untilAsserted(() -> assertThat(go5Count.get()).isGreaterThanOrEqualTo(2));

        // Cleanup
        subscription.unsubscribe();
        subscriptionManager.stop();
        fencedLockManager.stop();
    }

}
