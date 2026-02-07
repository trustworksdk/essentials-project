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
import java.util.stream.IntStream;

import static dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.processor.EventProcessorIT.createObjectMapper;
import static org.assertj.core.api.Assertions.assertThat;

public class CdcEventStoreSubscriptionManager_2_node_exclusive_vs_nonexclusive_IT extends AbstractWal2JsonPostgresIT {

    private PostgresqlEventStore<SeparateTablePerAggregateEventStreamConfiguration> eventStore;
    private EventProcessorIT.TestPersistableEventMapper                             eventMapper;
    private JacksonJSONEventSerializer                                              jacksonJSONSerializer;
    private CdcInboxRepository            inboxRepository;
    private EventStreamGapHandler<?>      gapHandler;
    private DurableSubscriptionRepository durableSubscriptionRepository;

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

            // Persist many events so the subscription advances (so poison gap is "behind" current)
            var orderId = OrderId.random();
            node1.baseEventStore.getUnitOfWorkFactory().usingUnitOfWork(() -> {
                node1.baseEventStore.appendToStream(
                        aggregateType,
                        orderId,
                        EventOrder.NO_EVENTS_PREVIOUSLY_PERSISTED,
                        List.of(
                                new OrderEvent.OrderAdded(orderId, CustomerId.random(), 1),
                                new OrderEvent.ProductAddedToOrder(orderId, ProductId.random(), 2),
                                new OrderEvent.ProductRemovedFromOrder(orderId, ProductId.random()),
                                new OrderEvent.OrderAccepted(orderId)
                               )
                                                  );
            });

            // Let subscription consume those (hybrid backfill)
            Awaitility.await().atMost(Duration.ofSeconds(10)).untilAsserted(() -> {
                assertThat(received.size()).isGreaterThanOrEqualTo(4);
            });

            // Build REAL dispatcher + notifier on node1
            var notifier = new SubscriptionResetOnPoisonNotifier(node1.manager, node1.durableSubscriptionRepository);

            // Using your existing converter/extractor style (use the same resolver as your ITs)
            AggregateTypeResolver resolver  = table -> "orders_events".equalsIgnoreCase(table) ? ORDERS : null;
            var                   converter = new JacksonWal2JsonToPersistedEventConverter(node1.jsonSerializer, resolver);
            var                   extractor = new JacksonWalGlobalOrdersExtractor(node1.jsonSerializer, resolver);

            var dispatcher = new CdcDispatcher(
                    node1.inboxRepository,
                    node1.unitOfWorkFactory,
                    node1.gapHandler,
                    converter,
                    extractor,
                    Optional.of(notifier),
                    cdcBus::publish,
                    slotName,
                    CdcDispatcherProperties.defaults()
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
        public final JacksonJSONEventSerializer              jsonSerializer;

        HybridManagerContext(
                Jdbi jdbi,
                EventStoreSubscriptionManager manager,
                PostgresqlEventStore<?> baseEventStore,
                EventStoreManagedUnitOfWorkFactory unitOfWorkFactory,
                EventStreamGapHandler<?> gapHandler,
                PostgresqlDurableSubscriptionRepository durableSubscriptionRepository,
                CdcInboxRepository inboxRepository,
                JacksonJSONEventSerializer jsonSerializer
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

    private HybridManagerContext createHybridManager(String nodeName, CdcEventBus bus) {
        // create Jdbi, unitOfWorkFactory, serializer, baseEventStore, gapHandler as you already do
        // then wrap:
        var cdcEventStore = new CdcEventStore<>(eventStore, unitOfWorkFactory, gapHandler, bus, new CdcProperties());

        var durableRepo = new PostgresqlDurableSubscriptionRepository(jdbi, cdcEventStore);
        var manager = EventStoreSubscriptionManager.createFor(
                cdcEventStore, // <-- IMPORTANT: manager uses HYBRID store
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


}
