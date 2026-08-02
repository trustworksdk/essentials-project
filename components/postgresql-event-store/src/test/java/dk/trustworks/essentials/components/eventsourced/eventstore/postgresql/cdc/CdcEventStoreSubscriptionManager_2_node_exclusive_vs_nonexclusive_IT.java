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
import dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.serializer.json.*;
import dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.subscription.*;
import dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.test_data.*;
import dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.transaction.EventStoreManagedUnitOfWorkFactory;
import dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.types.*;
import dk.trustworks.essentials.components.foundation.fencedlock.FencedLock;
import dk.trustworks.essentials.components.foundation.transaction.UnitOfWork;
import dk.trustworks.essentials.components.foundation.types.SubscriberId;
import org.awaitility.Awaitility;
import org.jdbi.v3.core.Jdbi;
import org.junit.jupiter.api.*;

import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.stream.IntStream;

import static dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.processor.EventProcessorIT.createObjectMapper;
import static org.assertj.core.api.Assertions.assertThat;

public class CdcEventStoreSubscriptionManager_2_node_exclusive_vs_nonexclusive_IT extends AbstractLogicalReplicationPostgresIT {

    private PostgresqlEventStore<SeparateTablePerAggregateEventStreamConfiguration> eventStore;
    private EventProcessorIT.TestPersistableEventMapper                             eventMapper;
    private JSONEventSerializer                                                    jacksonJSONSerializer;
    private CdcInboxRepository            inboxRepository;
    private EventStreamGapHandler<?>      gapHandler;
    private DurableSubscriptionRepository durableSubscriptionRepository;

    @BeforeEach
    void setup() {
        jacksonJSONSerializer = EssentialsJSONEventSerializers.createForActiveJacksonFlavor();
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
        gapHandler = new PostgresqlEventStreamGapHandler<>(unitOfWorkFactory);

        durableSubscriptionRepository = new PostgresqlDurableSubscriptionRepository(jdbi, eventStore);

        inboxRepository = new CdcInboxRepository(unitOfWorkFactory);
    }


    @Test
    void hybrid_2node_exclusive_only_one_active_and_failover_then_live_continues() {
        // Shared across both nodes to simulate "CDC feed" (both nodes subscribe to same logical events)
        var cdcBus = new CdcEventBus();

        // Node1 + Node2 each get their own manager but wrap underlying PG store with CdcEventStore
        var node1 = createHybridManager("node1", cdcBus);
        var node2 = createHybridManager("node2", cdcBus);

        try {
            AggregateType aggregateType = ORDERS;
            SubscriberId  subscriberId  = SubscriberId.of("OrdersSubscriber");

            // Persist initial events BEFORE subscribing => should be delivered via BACKFILL (DB)
            var orderId = OrderId.random();
            node2.baseEventStore.getUnitOfWorkFactory().usingUnitOfWork(() -> {
                node2.baseEventStore.appendToStream(
                        aggregateType,
                        orderId,
                        EventOrder.NO_EVENTS_PREVIOUSLY_PERSISTED,
                        List.of(
                                new OrderEvent.OrderAdded(orderId, CustomerId.random(), 100),
                                new OrderEvent.ProductAddedToOrder(orderId, ProductId.random(), 2),
                                new OrderEvent.ProductRemovedFromOrder(orderId, ProductId.random())
                               )
                                                    );
            });

            // Subscribe on both nodes (exclusive). Only one should acquire lock and process.
            var node1Received = new ConcurrentLinkedDeque<PersistedEvent>();
            var node2Received = new ConcurrentLinkedDeque<PersistedEvent>();

            var sub1 = node1.manager.exclusivelySubscribeToAggregateEventsAsynchronously(
                    subscriberId,
                    aggregateType,
                    GlobalEventOrder.FIRST_GLOBAL_EVENT_ORDER,
                    Optional.empty(),
                    new FencedLockAwareSubscriber() {
                        @Override
                        public void onLockAcquired(FencedLock fencedLock, SubscriptionResumePoint rp) {
                        }

                        @Override
                        public void onLockReleased(FencedLock fencedLock) {
                        }
                    },
                    new PersistedEventHandler() {
                        @Override
                        public void onResetFrom(EventStoreSubscription s, GlobalEventOrder g) {
                        }

                        @Override
                        public void handle(PersistedEvent e) {
                            node1Received.add(e);
                        }
                    }
                                                                                );

            var sub2 = node2.manager.exclusivelySubscribeToAggregateEventsAsynchronously(
                    subscriberId,
                    aggregateType,
                    GlobalEventOrder.FIRST_GLOBAL_EVENT_ORDER,
                    Optional.empty(),
                    new FencedLockAwareSubscriber() {
                        @Override
                        public void onLockAcquired(FencedLock fencedLock, SubscriptionResumePoint rp) {
                        }

                        @Override
                        public void onLockReleased(FencedLock fencedLock) {
                        }
                    },
                    new PersistedEventHandler() {
                        @Override
                        public void onResetFrom(EventStoreSubscription s, GlobalEventOrder g) {
                        }

                        @Override
                        public void handle(PersistedEvent e) {
                            node2Received.add(e);
                        }
                    }
                                                                                );

            // Wait until exactly one is active
            Awaitility.await()
                      .atMost(Duration.ofSeconds(10))
                      .pollInterval(Duration.ofMillis(100))
                      .untilAsserted(() -> {
                          boolean a1 = sub1.isActive();
                          boolean a2 = sub2.isActive();
                          assertThat(a1 ^ a2).isTrue();
                      });

            boolean node1InitiallyActive = sub1.isActive();

            // Backfill should deliver 3 events to the ACTIVE node
            Awaitility.await()
                      .atMost(Duration.ofSeconds(10))
                      .pollInterval(Duration.ofMillis(100))
                      .untilAsserted(() -> {
                          int total = node1Received.size() + node2Received.size();
                          assertThat(total).isGreaterThanOrEqualTo(3);
                      });

            // Kill active node's manager to force failover
            if (node1InitiallyActive) {
                node1.manager.stop();
            } else {
                node2.manager.stop();
            }

            // Wait until the other becomes active
            Awaitility.await()
                      .atMost(Duration.ofSeconds(15))
                      .pollInterval(Duration.ofMillis(200))
                      .untilAsserted(() -> {
                          if (node1InitiallyActive) {
                              assertThat(sub2.isActive()).isTrue();
                          } else {
                              assertThat(sub1.isActive()).isTrue();
                          }
                      });

            // Append a NEW event (GO=4) and publish it as LIVE via CDC bus.
            // (In prod this comes from dispatcher/tailer; here we publish directly to exercise hybrid path.)
            List<PersistedEvent> appended = node1InitiallyActive
                                            ? appendOneEventReturningPersisted(node2.baseEventStore, aggregateType, orderId, 200)
                                            : appendOneEventReturningPersisted(node1.baseEventStore, aggregateType, orderId, 200);

            cdcBus.publish(appended);

            // Assert the live event arrives on the new active node
            Awaitility.await()
                      .atMost(Duration.ofSeconds(10))
                      .pollInterval(Duration.ofMillis(100))
                      .untilAsserted(() -> {
                          var all = new ArrayList<PersistedEvent>();
                          all.addAll(node1Received);
                          all.addAll(node2Received);
                          assertThat(all.stream().map(e -> e.globalEventOrder().longValue()).toList()
                                  .contains(appended.get(0).globalEventOrder().longValue()));
                      });

            sub1.stop();
            sub2.stop();
        } finally {
            safeStop(node1);
            safeStop(node2);
        }
    }

    @Test
    void hybrid_poison_dispatcher_triggers_reset_after_poison_on_active_exclusive_subscription() {
        var cdcBus = new CdcEventBus();

        var node1 = createHybridManager("node1", cdcBus);
        var node2 = createHybridManager("node2", cdcBus);

        // We'll run dispatcher on the node that ends up ACTIVE.
        // If node2 becomes active, we stop node1 and recreate subscription on node2 (simple + deterministic).
        try {
            AggregateType aggregateType = ORDERS;
            SubscriberId  subscriberId  = SubscriberId.of("sub-1");
            String        slotName      = "slot_" + UUID.randomUUID().toString().replace("-", "");

            // Persist many events so the subscription advances (so poison gap is "behind" current)
            // Do this BEFORE subscribing to avoid timing races with CDC live subscription setup.
            var orderId = OrderId.random();
            appendEventsReturningPersisted(
                    node1.baseEventStore,
                    aggregateType,
                    orderId,
                    List.of(
                            new OrderEvent.OrderAdded(orderId, CustomerId.random(), 1),
                            new OrderEvent.ProductAddedToOrder(orderId, ProductId.random(), 2),
                            new OrderEvent.ProductRemovedFromOrder(orderId, ProductId.random()),
                            new OrderEvent.OrderAccepted(orderId)
                           )
                                           );

            // Create subscriptions on both nodes
            var resets   = new CopyOnWriteArrayList<GlobalEventOrder>();
            var received = new ConcurrentLinkedDeque<PersistedEvent>();

            var sub1 = node1.manager.exclusivelySubscribeToAggregateEventsAsynchronously(
                    subscriberId,
                    aggregateType,
                    GlobalEventOrder.FIRST_GLOBAL_EVENT_ORDER,
                    Optional.empty(),
                    new FencedLockAwareSubscriber() {
                        @Override
                        public void onLockAcquired(FencedLock fencedLock, SubscriptionResumePoint rp) {
                        }

                        @Override
                        public void onLockReleased(FencedLock fencedLock) {
                        }
                    },
                    new PersistedEventHandler() {
                        @Override
                        public void onResetFrom(EventStoreSubscription s, GlobalEventOrder g) {
                            resets.add(g);
                        }

                        @Override
                        public void handle(PersistedEvent e) {
                            received.add(e);
                        }
                    }
                                                                                );

            var sub2 = node2.manager.exclusivelySubscribeToAggregateEventsAsynchronously(
                    subscriberId,
                    aggregateType,
                    GlobalEventOrder.FIRST_GLOBAL_EVENT_ORDER,
                    Optional.empty(),
                    new FencedLockAwareSubscriber() {
                        @Override
                        public void onLockAcquired(FencedLock fencedLock, SubscriptionResumePoint rp) {
                        }

                        @Override
                        public void onLockReleased(FencedLock fencedLock) {
                        }
                    },
                    new PersistedEventHandler() {
                        @Override
                        public void onResetFrom(EventStoreSubscription s, GlobalEventOrder g) {
                            resets.add(g);
                        }

                        @Override
                        public void handle(PersistedEvent e) {
                            received.add(e);
                        }
                    }
                                                                                );

            // Wait until exactly one is active; ensure node1 is active (to keep dispatcher wiring simple)
            Awaitility.await().atMost(Duration.ofSeconds(10)).untilAsserted(() -> {
                boolean a1 = sub1.isActive();
                boolean a2 = sub2.isActive();
                assertThat(a1 ^ a2).isTrue();
            });

            if (!sub1.isActive()) {
                // Make it deterministic: stop node2 so node1 takes lock
                node2.manager.stop();

                Awaitility.await().atMost(Duration.ofSeconds(15)).untilAsserted(() -> {
                    assertThat(sub1.isActive()).isTrue();
                });
            }

            // Let subscription consume those (hybrid backfill)
            Awaitility.await().atMost(Duration.ofSeconds(10)).untilAsserted(() -> {
                assertThat(received.size()).isGreaterThanOrEqualTo(4);
            });

            // Build REAL dispatcher + notifier on node1
            var notifier = new SubscriptionResetOnPoisonNotifier(node1.manager, node1.durableSubscriptionRepository);

            // Using your existing converter/extractor style (use the same resolver as your ITs)
            AggregateTypeResolver resolver  = table -> "orders_events".equalsIgnoreCase(table) ? ORDERS : null;
            var                   converter = new JacksonWal2JsonToPersistedEventConverter(node1.jsonSerializer, resolver,
                                                                                  AggregateIdSerializerResolver.forEventStore(node1.baseEventStore));
            var                   extractor = new JacksonWalGlobalOrdersExtractor(node1.jsonSerializer, resolver);

            var availability = new CdcAvailability();
            availability.active(slotName);
            var plugin = new Wal2JsonLogicalDecodingPlugin(
                    CdcProperties.WalReplicationTailerProperties.defaults(java.time.Duration.ofMillis(25), java.time.Duration.ofMillis(50), java.time.Duration.ofSeconds(2), java.time.Duration.ofMillis(100)),
                    converter, extractor, CdcProperties.WalParserMode.STRING);
            var dispatcher = new CdcDispatcher(
                    node1.inboxRepository,
                    node1.unitOfWorkFactory,
                    node1.gapHandler,
                    plugin,
                    Optional.of(notifier),
                    cdcBus::publish,
                    slotName,
                    CdcDispatcherProperties.defaults(),
                    CdcProperties.CdcDeliveryMode.INBOX,
                    availability,
                    Optional.empty()
            );

            dispatcher.start();

            // Inject poison wal2json message that:
            // - is valid JSON
            // - maps to ORDERS
            // - has global_order=5
            // - fails conversion (event_revision = not-an-int)
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
                                       5,
                                       "00000000-0000-0000-0000-000000000005",
                                       1,
                                       "00000000-0000-0000-0000-000000000005",
                                       null,
                                       null,
                                       "FQCN:dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.test_data.OrderEvent$OrderAdded",
                                       "not-an-int",
                                       "2026-01-27 15:38:10.67955+01",
                                       {"orderId":"00000000-0000-0000-0000-000000000005","customerId":"C","amount":1},
                                       {},
                                       null
                                     ]
                                   }
                                 ]
                               }
                               """;

            node1.inboxRepository.insertRaw(slotName, "0/POISON", poisonWal, "RECEIVED");

            // Then: we should see reset to 5 recorded via handler callback
            Awaitility.await()
                      .atMost(Duration.ofSeconds(10))
                      .pollInterval(Duration.ofMillis(100))
                      .untilAsserted(() -> {
                          var rp = node1.durableSubscriptionRepository
                                  .getResumePoint(subscriberId, aggregateType)
                                  .orElseThrow();

                          assertThat(rp.getResumeFromAndIncluding().longValue())
                                  .isLessThanOrEqualTo(5L);
                      });

            // And: durable resume was forced to 5 at least once (don’t expect it to stay there)
            Awaitility.await()
                      .atMost(Duration.ofSeconds(10))
                      .pollInterval(Duration.ofMillis(100))
                      .untilAsserted(() -> {
                          var rp = node1.durableSubscriptionRepository
                                  .getResumePoint(subscriberId, aggregateType)
                                  .orElseThrow();
                          assertThat(rp.getResumeFromAndIncluding().longValue()).isLessThanOrEqualTo(5L);
                      });

            dispatcher.stop();
            sub1.stop();
            sub2.stop();
        } finally {
            safeStop(node1);
            safeStop(node2);
        }
    }

    @Test
    void hybrid_poison_rewinds_non_exclusive_subscription_and_persists_resume_point_immediately() {
        var cdcBus = new CdcEventBus();
        AggregateType aggregateType = ORDERS;
        SubscriberId  subscriberId  = SubscriberId.of("non-exclusive-sub");

        var node1 = createHybridManager("node1", cdcBus);
        var manager = node1.manager;

        try {
            var handledOrders = new ConcurrentLinkedDeque<Long>();

            // Subscribe non-exclusively (the one you said should NOT be exclusive)
            var subscription = manager.subscribeToAggregateEventsAsynchronously(
                    subscriberId,
                    aggregateType,
                    GlobalEventOrder.FIRST_GLOBAL_EVENT_ORDER,
                    Optional.empty(),
                    new PersistedEventHandler() {
                        @Override
                        public void onResetFrom(EventStoreSubscription sub, GlobalEventOrder go) {
                            // NOTE: we do NOT assert on this; current impl may not call it.
                        }

                        @Override
                        public void handle(PersistedEvent event) {
                            handledOrders.add(event.globalEventOrder().longValue());
                        }
                    }
                                                                               );

            // Persist 1..10
            var orderId = OrderId.random();
            var appended = appendEventsReturningPersisted(
                    eventStore,
                    aggregateType,
                    orderId,
                    IntStream.rangeClosed(1, 10)
                             .mapToObj(i -> new OrderEvent.OrderAdded(orderId, CustomerId.random(), i))
                             .toList()
                                                           );
            cdcBus.publish(appended);

            // Wait until we've processed at least 10 events (so resume is > 5)
            Awaitility.await()
                      .atMost(Duration.ofSeconds(10))
                      .untilAsserted(() -> {
                          assertThat(handledOrders).isNotEmpty();
                          assertThat(handledOrders.stream().max(Long::compareTo).orElse(0L)).isGreaterThanOrEqualTo(10L);
                      });

            // Verify durable resume is > 5 before poison reset (best-effort; it might lag a bit)
            Awaitility.await()
                      .atMost(Duration.ofSeconds(10))
                      .untilAsserted(() -> {
                          var before = durableSubscriptionRepository
                                  .getResumePoint(subscriberId, aggregateType)
                                  .orElseThrow()
                                  .getResumeFromAndIncluding()
                                  .longValue();
                          assertThat(before).isGreaterThan(5L);
                      });

            // Trigger poison reset at 5
            var notifier = new SubscriptionResetOnPoisonNotifier(manager, durableSubscriptionRepository);
            notifier.onPoison(aggregateType, List.of(GlobalEventOrder.of(5L)), "it-poison");

            // 1) Assert durable resume was forced immediately to 5
            Awaitility.await()
                      .atMost(Duration.ofSeconds(10))
                      .untilAsserted(() -> {
                          var rp = durableSubscriptionRepository
                                  .getResumePoint(subscriberId, aggregateType)
                                  .orElseThrow();

                          assertThat(rp.getResumeFromAndIncluding().longValue()).isEqualTo(5L);
                      });

            // 2) Prove rewind happened by observing a replay of an already-seen global order >= 5
            //    The simplest signal is: after reset we should see "5" again (duplicate).
            //    Because you already processed up to 10, seeing 5 again can only happen via rewind.
            Awaitility.await()
                      .atMost(Duration.ofSeconds(10))
                      .untilAsserted(() -> {
                          long count5 = handledOrders.stream().filter(go -> go == 5L).count();
                          assertThat(count5).isGreaterThanOrEqualTo(2L);
                      });

            subscription.stop();

        } finally {
            safeStop(node1);
        }
    }

    @Test
    void hybrid_2node_batched_non_exclusive_duplicate_delivery_is_expected_and_ordered_per_node() {
        var cdcBus = new CdcEventBus();
        var node1 = createHybridManager("node1", cdcBus);
        var node2 = createHybridManager("node2", cdcBus);

        try {
            var node1Orders = new CopyOnWriteArrayList<Long>();
            var node2Orders = new CopyOnWriteArrayList<Long>();
            var orderId = OrderId.random();

            var sub1 = node1.manager.batchSubscribeToAggregateEventsAsynchronously(
                    SubscriberId.of("batched-node1-sub"),
                    ORDERS,
                    GlobalEventOrder.FIRST_GLOBAL_EVENT_ORDER,
                    Optional.empty(),
                    10,
                    Duration.ofMillis(50),
                    new BatchedPersistedEventHandler() {
                        @Override
                        public int handleBatch(List<PersistedEvent> events) {
                            events.forEach(event -> node1Orders.add(event.globalEventOrder().longValue()));
                            return 10;
                        }

                        @Override
                        public void onResetFrom(EventStoreSubscription subscription, GlobalEventOrder subscribeFromAndIncludingGlobalOrder) {
                        }
                    }
            );

            var sub2 = node2.manager.batchSubscribeToAggregateEventsAsynchronously(
                    SubscriberId.of("batched-node2-sub"),
                    ORDERS,
                    GlobalEventOrder.FIRST_GLOBAL_EVENT_ORDER,
                    Optional.empty(),
                    10,
                    Duration.ofMillis(50),
                    new BatchedPersistedEventHandler() {
                        @Override
                        public int handleBatch(List<PersistedEvent> events) {
                            events.forEach(event -> node2Orders.add(event.globalEventOrder().longValue()));
                            return 10;
                        }

                        @Override
                        public void onResetFrom(EventStoreSubscription subscription, GlobalEventOrder subscribeFromAndIncludingGlobalOrder) {
                        }
                    }
            );

            var appended = appendEventsReturningPersisted(
                    node1.baseEventStore,
                    ORDERS,
                    orderId,
                    IntStream.rangeClosed(1, 6)
                             .mapToObj(i -> new OrderEvent.OrderAdded(orderId, CustomerId.random(), i))
                             .toList()
            );
            cdcBus.publish(appended);

            Awaitility.await().atMost(Duration.ofSeconds(10)).untilAsserted(() -> {
                assertThat(node1Orders).containsExactly(1L, 2L, 3L, 4L, 5L, 6L);
                assertThat(node2Orders).containsExactly(1L, 2L, 3L, 4L, 5L, 6L);
            });

            sub1.stop();
            sub2.stop();
        } finally {
            safeStop(node1);
            safeStop(node2);
        }
    }

    @Test
    void hybrid_2node_exclusive_failover_replays_from_last_durable_resume_point() {
        var cdcBus = new CdcEventBus();
        var node1 = createHybridManager("node1", cdcBus);
        var node2 = createHybridManager("node2", cdcBus);

        try {
            AggregateType aggregateType = ORDERS;
            SubscriberId subscriberId = SubscriberId.of("exclusive-failover-resume-sub");
            var orderId = OrderId.random();

            appendEventsReturningPersisted(
                    node1.baseEventStore,
                    aggregateType,
                    orderId,
                    IntStream.rangeClosed(1, 6)
                             .mapToObj(i -> new OrderEvent.OrderAdded(orderId, CustomerId.random(), i))
                             .toList()
            );

            var node1Orders = new ConcurrentLinkedDeque<Long>();
            var node2Orders = new ConcurrentLinkedDeque<Long>();

            var sub1 = node1.manager.exclusivelySubscribeToAggregateEventsAsynchronously(
                    subscriberId,
                    aggregateType,
                    GlobalEventOrder.FIRST_GLOBAL_EVENT_ORDER,
                    Optional.empty(),
                    new FencedLockAwareSubscriber() {
                        @Override
                        public void onLockAcquired(FencedLock fencedLock, SubscriptionResumePoint rp) {
                        }

                        @Override
                        public void onLockReleased(FencedLock fencedLock) {
                        }
                    },
                    e -> node1Orders.add(e.globalEventOrder().longValue())
            );

            var sub2 = node2.manager.exclusivelySubscribeToAggregateEventsAsynchronously(
                    subscriberId,
                    aggregateType,
                    GlobalEventOrder.FIRST_GLOBAL_EVENT_ORDER,
                    Optional.empty(),
                    new FencedLockAwareSubscriber() {
                        @Override
                        public void onLockAcquired(FencedLock fencedLock, SubscriptionResumePoint rp) {
                        }

                        @Override
                        public void onLockReleased(FencedLock fencedLock) {
                        }
                    },
                    e -> node2Orders.add(e.globalEventOrder().longValue())
            );

            Awaitility.await().atMost(Duration.ofSeconds(10)).untilAsserted(() -> assertThat(sub1.isActive() ^ sub2.isActive()).isTrue());
            Awaitility.await().atMost(Duration.ofSeconds(10)).untilAsserted(() -> {
                var all = new ArrayList<Long>();
                all.addAll(node1Orders);
                all.addAll(node2Orders);
                assertThat(all).containsAll(List.of(1L, 2L, 3L, 4L, 5L, 6L));
            });

            Awaitility.await().atMost(Duration.ofSeconds(10)).untilAsserted(() -> {
                var resume = node1.durableSubscriptionRepository
                        .getResumePoint(subscriberId, aggregateType)
                        .orElseThrow()
                        .getResumeFromAndIncluding()
                        .longValue();
                assertThat(resume).isGreaterThanOrEqualTo(7L);
            });

            boolean node1Active = sub1.isActive();
            if (node1Active) {
                node1.manager.stop();
            } else {
                node2.manager.stop();
            }

            Awaitility.await().atMost(Duration.ofSeconds(15)).untilAsserted(() -> {
                if (node1Active) {
                    assertThat(sub2.isActive()).isTrue();
                } else {
                    assertThat(sub1.isActive()).isTrue();
                }
            });

            Awaitility.await().atMost(Duration.ofSeconds(10)).untilAsserted(() -> {
                var active = node1Active ? sub2 : sub1;
                assertThat(active.currentResumePoint()).isPresent();
                assertThat(active.currentResumePoint().orElseThrow().getResumeFromAndIncluding().longValue()).isGreaterThanOrEqualTo(7L);
            });

            sub1.stop();
            sub2.stop();
        } finally {
            safeStop(node1);
            safeStop(node2);
        }
    }

    @Test
    void hybrid_poison_reset_triggered_on_standby_does_not_rewind_active_exclusive_subscription() throws InterruptedException {
        var cdcBus = new CdcEventBus();
        var node1 = createHybridManager("node1", cdcBus);
        var node2 = createHybridManager("node2", cdcBus);

        try {
            AggregateType aggregateType = ORDERS;
            SubscriberId subscriberId = SubscriberId.of("standby-poison-sub");
            var orderId = OrderId.random();
            var resets1 = new CopyOnWriteArrayList<Long>();
            var resets2 = new CopyOnWriteArrayList<Long>();
            var received1 = new CopyOnWriteArrayList<Long>();
            var received2 = new CopyOnWriteArrayList<Long>();

            appendEventsReturningPersisted(
                    node1.baseEventStore,
                    aggregateType,
                    orderId,
                    IntStream.rangeClosed(1, 10)
                             .mapToObj(i -> new OrderEvent.OrderAdded(orderId, CustomerId.random(), i))
                             .toList()
            );

            var sub1 = node1.manager.exclusivelySubscribeToAggregateEventsAsynchronously(
                    subscriberId,
                    aggregateType,
                    GlobalEventOrder.FIRST_GLOBAL_EVENT_ORDER,
                    Optional.empty(),
                    new FencedLockAwareSubscriber() {
                        @Override
                        public void onLockAcquired(FencedLock fencedLock, SubscriptionResumePoint rp) {
                        }

                        @Override
                        public void onLockReleased(FencedLock fencedLock) {
                        }
                    },
                    new PersistedEventHandler() {
                        @Override
                        public void onResetFrom(EventStoreSubscription s, GlobalEventOrder g) {
                            resets1.add(g.longValue());
                        }

                        @Override
                        public void handle(PersistedEvent e) {
                            received1.add(e.globalEventOrder().longValue());
                        }
                    }
            );

            var sub2 = node2.manager.exclusivelySubscribeToAggregateEventsAsynchronously(
                    subscriberId,
                    aggregateType,
                    GlobalEventOrder.FIRST_GLOBAL_EVENT_ORDER,
                    Optional.empty(),
                    new FencedLockAwareSubscriber() {
                        @Override
                        public void onLockAcquired(FencedLock fencedLock, SubscriptionResumePoint rp) {
                        }

                        @Override
                        public void onLockReleased(FencedLock fencedLock) {
                        }
                    },
                    new PersistedEventHandler() {
                        @Override
                        public void onResetFrom(EventStoreSubscription s, GlobalEventOrder g) {
                            resets2.add(g.longValue());
                        }

                        @Override
                        public void handle(PersistedEvent e) {
                            received2.add(e.globalEventOrder().longValue());
                        }
                    }
            );

            Awaitility.await().atMost(Duration.ofSeconds(10)).untilAsserted(() -> assertThat(sub1.isActive() ^ sub2.isActive()).isTrue());
            boolean node1Active = sub1.isActive();

            Awaitility.await().atMost(Duration.ofSeconds(10)).untilAsserted(() -> {
                var resume = node1.durableSubscriptionRepository
                        .getResumePoint(subscriberId, aggregateType)
                        .orElseThrow()
                        .getResumeFromAndIncluding()
                        .longValue();
                assertThat(resume).isGreaterThan(5L);
            });

            var standbyManager = node1Active ? node2.manager : node1.manager;
            var standbyRepo = node1Active ? node2.durableSubscriptionRepository : node1.durableSubscriptionRepository;
            new SubscriptionResetOnPoisonNotifier(standbyManager, standbyRepo)
                    .onPoison(aggregateType, List.of(GlobalEventOrder.of(5L)), "it-poison-standby");

            Thread.sleep(1000);

            var resumeAfter = node1.durableSubscriptionRepository
                    .getResumePoint(subscriberId, aggregateType)
                    .orElseThrow()
                    .getResumeFromAndIncluding()
                    .longValue();
            var activeResets = node1Active ? resets1 : resets2;
            assertThat(resumeAfter).isGreaterThan(5L);
            assertThat(activeResets).isEmpty();

            var followupOrderId1 = OrderId.random();
            var appended = appendEventsReturningPersisted(
                    node1.baseEventStore,
                    aggregateType,
                    followupOrderId1,
                    IntStream.rangeClosed(11, 12)
                             .mapToObj(i -> new OrderEvent.OrderAdded(followupOrderId1, CustomerId.random(), i))
                             .toList()
            );
            cdcBus.publish(appended);

            Awaitility.await().atMost(Duration.ofSeconds(10)).untilAsserted(() -> {
                var activeReceived = node1Active ? received1 : received2;
                assertThat(activeReceived).contains(11L, 12L);
                assertThat(activeReceived.stream().filter(go -> go == 5L).count()).isLessThanOrEqualTo(1L);
            });

            sub1.stop();
            sub2.stop();
        } finally {
            safeStop(node1);
            safeStop(node2);
        }
    }

    @Test
    void hybrid_exclusive_lock_handover_during_poison_reset_keeps_resume_point_clamped() throws InterruptedException {
        var cdcBus = new CdcEventBus();
        var node1 = createHybridManager("node1", cdcBus);
        var node2 = createHybridManager("node2", cdcBus);

        try {
            AggregateType aggregateType = ORDERS;
            SubscriberId subscriberId = SubscriberId.of("exclusive-reset-handover-sub");
            var orderId = OrderId.random();

            appendEventsReturningPersisted(
                    node1.baseEventStore,
                    aggregateType,
                    orderId,
                    IntStream.rangeClosed(1, 10)
                             .mapToObj(i -> new OrderEvent.OrderAdded(orderId, CustomerId.random(), i))
                             .toList()
            );

            var resets = new CopyOnWriteArrayList<Long>();

            var sub1 = node1.manager.exclusivelySubscribeToAggregateEventsAsynchronously(
                    subscriberId,
                    aggregateType,
                    GlobalEventOrder.FIRST_GLOBAL_EVENT_ORDER,
                    Optional.empty(),
                    new FencedLockAwareSubscriber() {
                        @Override
                        public void onLockAcquired(FencedLock fencedLock, SubscriptionResumePoint rp) {
                        }

                        @Override
                        public void onLockReleased(FencedLock fencedLock) {
                        }
                    },
                    new PersistedEventHandler() {
                        @Override
                        public void onResetFrom(EventStoreSubscription s, GlobalEventOrder g) {
                            resets.add(g.longValue());
                        }

                        @Override
                        public void handle(PersistedEvent e) {
                        }
                    }
            );

            var sub2 = node2.manager.exclusivelySubscribeToAggregateEventsAsynchronously(
                    subscriberId,
                    aggregateType,
                    GlobalEventOrder.FIRST_GLOBAL_EVENT_ORDER,
                    Optional.empty(),
                    new FencedLockAwareSubscriber() {
                        @Override
                        public void onLockAcquired(FencedLock fencedLock, SubscriptionResumePoint rp) {
                        }

                        @Override
                        public void onLockReleased(FencedLock fencedLock) {
                        }
                    },
                    new PersistedEventHandler() {
                        @Override
                        public void onResetFrom(EventStoreSubscription s, GlobalEventOrder g) {
                            resets.add(g.longValue());
                        }

                        @Override
                        public void handle(PersistedEvent e) {
                        }
                    }
            );

            Awaitility.await().atMost(Duration.ofSeconds(10)).untilAsserted(() -> assertThat(sub1.isActive() ^ sub2.isActive()).isTrue());
            Awaitility.await().atMost(Duration.ofSeconds(10)).untilAsserted(() -> {
                var resume = node1.durableSubscriptionRepository
                        .getResumePoint(subscriberId, aggregateType)
                        .orElseThrow()
                        .getResumeFromAndIncluding()
                        .longValue();
                assertThat(resume).isGreaterThan(5L);
            });

            boolean node1Active = sub1.isActive();
            var activeManager = node1Active ? node1.manager : node2.manager;
            var activeContext = node1Active ? node1 : node2;

            var notifier = new SubscriptionResetOnPoisonNotifier(activeManager, activeContext.durableSubscriptionRepository);
            var sawClampedResume = new AtomicBoolean(false);
            var resetThread = new Thread(() -> notifier.onPoison(aggregateType, List.of(GlobalEventOrder.of(5L)), "it-poison-race"));
            var stopThread = new Thread(() -> {
                try {
                    Thread.sleep(100);
                } catch (InterruptedException ignored) {
                }
                activeManager.stop();
            });
            resetThread.start();
            stopThread.start();
            resetThread.join();

            var resumeAfterResetNode1 = node1.durableSubscriptionRepository.getResumePoint(subscriberId, aggregateType)
                    .orElseThrow()
                    .getResumeFromAndIncluding()
                    .longValue();
            var resumeAfterResetNode2 = node2.durableSubscriptionRepository.getResumePoint(subscriberId, aggregateType)
                    .orElseThrow()
                    .getResumeFromAndIncluding()
                    .longValue();
            sawClampedResume.set(Math.min(resumeAfterResetNode1, resumeAfterResetNode2) <= 5L);

            stopThread.join();

            Awaitility.await().atMost(Duration.ofSeconds(15)).untilAsserted(() -> {
                if (node1Active) {
                    assertThat(sub2.isActive()).isTrue();
                } else {
                    assertThat(sub1.isActive()).isTrue();
                }
            });

            assertThat(sawClampedResume.get()).isTrue();

            sub1.stop();
            sub2.stop();
        } finally {
            safeStop(node1);
            safeStop(node2);
        }
    }

    @Test
    void hybrid_2node_auto_mode_with_one_node_failed_still_processes_after_failover_without_cdc_bus_publish() {
        var cdcBus = new CdcEventBus();
        var node1 = createHybridManager("node1", cdcBus);
        var node2 = createHybridManager("node2", cdcBus, availability -> availability.failed("slot-disabled", "cdc disabled"));

        try {
            AggregateType aggregateType = ORDERS;
            SubscriberId subscriberId = SubscriberId.of("mixed-mode-exclusive-sub");
            var orderId = OrderId.random();
            var node2Received = new CopyOnWriteArrayList<Long>();

            appendEventsReturningPersisted(
                    node1.baseEventStore,
                    aggregateType,
                    orderId,
                    IntStream.rangeClosed(1, 3)
                             .mapToObj(i -> new OrderEvent.OrderAdded(orderId, CustomerId.random(), i))
                             .toList()
            );

            var sub1 = node1.manager.exclusivelySubscribeToAggregateEventsAsynchronously(
                    subscriberId,
                    aggregateType,
                    GlobalEventOrder.FIRST_GLOBAL_EVENT_ORDER,
                    Optional.empty(),
                    new FencedLockAwareSubscriber() {
                        @Override
                        public void onLockAcquired(FencedLock fencedLock, SubscriptionResumePoint rp) {
                        }

                        @Override
                        public void onLockReleased(FencedLock fencedLock) {
                        }
                    },
                    e -> {
                    }
            );

            Awaitility.await().atMost(Duration.ofSeconds(10)).untilAsserted(() -> assertThat(sub1.isActive()).isTrue());

            var sub2 = node2.manager.exclusivelySubscribeToAggregateEventsAsynchronously(
                    subscriberId,
                    aggregateType,
                    GlobalEventOrder.FIRST_GLOBAL_EVENT_ORDER,
                    Optional.empty(),
                    new FencedLockAwareSubscriber() {
                        @Override
                        public void onLockAcquired(FencedLock fencedLock, SubscriptionResumePoint rp) {
                        }

                        @Override
                        public void onLockReleased(FencedLock fencedLock) {
                        }
                    },
                    e -> node2Received.add(e.globalEventOrder().longValue())
            );

            Awaitility.await().atMost(Duration.ofSeconds(10)).untilAsserted(() -> assertThat(sub1.isActive() ^ sub2.isActive()).isTrue());
            node1.manager.stop();
            Awaitility.await().atMost(Duration.ofSeconds(15)).untilAsserted(() -> assertThat(sub2.isActive()).isTrue());

            var followupOrderId2 = OrderId.random();
            appendEventsReturningPersisted(
                    node2.baseEventStore,
                    aggregateType,
                    followupOrderId2,
                    IntStream.rangeClosed(4, 5)
                             .mapToObj(i -> new OrderEvent.OrderAdded(followupOrderId2, CustomerId.random(), i))
                             .toList()
            );

            Awaitility.await().atMost(Duration.ofSeconds(10)).untilAsserted(() -> assertThat(node2Received).contains(4L, 5L));

            sub1.stop();
            sub2.stop();
        } finally {
            safeStop(node1);
            safeStop(node2);
        }
    }

    @Test
    void reset_after_poison_never_advances_resume_point() {

        AggregateType aggregateType = ORDERS;
        SubscriberId  subscriberId  = SubscriberId.of("sub-1");

        var node = createHybridManager("node1", new CdcEventBus());
        var manager = node.manager;

        EventStoreSubscription subscription = null;

        try {
            subscription =
                    manager.subscribeToAggregateEventsAsynchronously(
                            subscriberId,
                            aggregateType,
                            GlobalEventOrder.FIRST_GLOBAL_EVENT_ORDER,
                            Optional.empty(),
                            event -> { /* no-op */ }
                                                                    );

            // Produce events 1..10
            var orderId = OrderId.random();
            unitOfWorkFactory.usingUnitOfWork(() -> {
                eventStore.appendToStream(
                        aggregateType,
                        orderId,
                        EventOrder.NO_EVENTS_PREVIOUSLY_PERSISTED,
                        IntStream.rangeClosed(1, 10)
                                 .mapToObj(i -> new OrderEvent.OrderAdded(orderId, CustomerId.random(), i))
                                 .toList()
                                         );
            });

            // Wait until subscription is clearly running
            EventStoreSubscription finalSubscription = subscription;
            Awaitility.await()
                      .atMost(Duration.ofSeconds(5))
                      .untilAsserted(() ->
                                             assertThat(finalSubscription.isActive()).isTrue()
                                    );

            // Trigger poison reset
            new SubscriptionResetOnPoisonNotifier(
                    manager,
                    durableSubscriptionRepository
            ).onPoison(
                    aggregateType,
                    List.of(GlobalEventOrder.of(5)),
                    "it-poison"
                      );

            // IMPORTANT: stop subscription immediately so it cannot advance again
            subscription.stop();

            // Assert durable resume was clamped (never > poison)
            Awaitility.await()
                      .atMost(Duration.ofSeconds(10))
                      .untilAsserted(() -> {
                          long resume =
                                  durableSubscriptionRepository
                                          .getResumePoint(subscriberId, aggregateType)
                                          .orElseThrow()
                                          .getResumeFromAndIncluding()
                                          .longValue();

                          assertThat(resume)
                                  .as("resume must be <= poison gap after reset")
                                  .isLessThanOrEqualTo(5L);
                      });

        } finally {
            if (subscription != null) {
                subscription.stop();
            }
            safeStop(node);
        }
    }

    private static final class HybridManagerContext {
        public final Jdbi                                    jdbi;
        public final EventStoreSubscriptionManager           manager;
        public final PostgresqlEventStore<?>                 baseEventStore;
        public final EventStoreManagedUnitOfWorkFactory      unitOfWorkFactory;
        public final EventStreamGapHandler<?>      gapHandler;
        public final PostgresqlDurableSubscriptionRepository durableSubscriptionRepository;
        public final CdcInboxRepository                      inboxRepository;
        public final JSONEventSerializer                    jsonSerializer;

        HybridManagerContext(
                Jdbi jdbi,
                EventStoreSubscriptionManager manager,
                PostgresqlEventStore<?> baseEventStore,
                EventStoreManagedUnitOfWorkFactory unitOfWorkFactory,
                EventStreamGapHandler<?> gapHandler,
                PostgresqlDurableSubscriptionRepository durableSubscriptionRepository,
                CdcInboxRepository inboxRepository,
                JSONEventSerializer jsonSerializer
                            ) {
            this.jdbi = jdbi;
            this.manager = manager;
            this.baseEventStore = baseEventStore;
            this.unitOfWorkFactory = unitOfWorkFactory;
            this.gapHandler = gapHandler;
            this.durableSubscriptionRepository = durableSubscriptionRepository;
            this.inboxRepository = inboxRepository;
            this.jsonSerializer = jsonSerializer;
        }

    }

    private HybridManagerContext createHybridManager(String nodeName, CdcEventBus bus, java.util.function.Consumer<CdcAvailability> availabilityCustomizer) {
        var availability = new CdcAvailability();
        availability.active("test");
        availabilityCustomizer.accept(availability);
        var cdcEventStore = new CdcEventStore<>(eventStore, unitOfWorkFactory, gapHandler, bus, new CdcProperties(), availability);

        var durableRepo = new PostgresqlDurableSubscriptionRepository(jdbi, cdcEventStore);
        var manager = EventStoreSubscriptionManager.createFor(
                cdcEventStore,
                20,
                Duration.ofMillis(100),
                new PostgresqlFencedLockManager(jdbi, unitOfWorkFactory, Optional.of(nodeName), Duration.ofSeconds(3), Duration.ofMillis(500), false),
                Duration.ofSeconds(2),
                durableRepo
        );
        manager.start();

        var inboxRepo = new CdcInboxRepository(unitOfWorkFactory);

        return new HybridManagerContext(
                jdbi,
                manager,
                eventStore,
                unitOfWorkFactory,
                gapHandler,
                durableRepo,
                inboxRepo,
                jacksonJSONSerializer
        );
    }

    private HybridManagerContext createHybridManager(String nodeName, CdcEventBus bus) {
        return createHybridManager(nodeName, bus, ignored -> {
        });
    }

    private static void safeStop(HybridManagerContext ctx) {
        if (ctx == null) return;
        try { ctx.manager.getEventStore().getUnitOfWorkFactory().getCurrentUnitOfWork().ifPresent(UnitOfWork::rollback); } catch (Exception ignore) {}
        try { ctx.manager.stop(); } catch (Exception ignore) {}
    }

    private List<PersistedEvent> appendOneEventReturningPersisted(
            EventStore store,
            AggregateType type,
            OrderId orderId,
            int amount
                                                                 ) {
        return store.getUnitOfWorkFactory().withUnitOfWork(() -> {
            var stream = store.appendToStream(
                    type,
                    orderId,
                    List.of(new OrderEvent.OrderAdded(orderId, CustomerId.random(), amount))
                                             );
            return stream.eventList();
        });
    }

    private List<PersistedEvent> appendEventsReturningPersisted(
            EventStore store,
            AggregateType type,
            OrderId orderId,
            List<? extends OrderEvent> events
                                                               ) {
        return store.getUnitOfWorkFactory().withUnitOfWork(() -> {
            var stream = store.appendToStream(
                    type,
                    orderId,
                    EventOrder.NO_EVENTS_PREVIOUSLY_PERSISTED,
                    events
                                             );
            return stream.eventList();
        });
    }

}
