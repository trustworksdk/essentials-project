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

package dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.cdc;

import dk.trustworks.essentials.components.distributed.fencedlock.postgresql.PostgresqlFencedLockManager;
import dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.PostgresqlEventStore;
import dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.eventstream.PersistedEvent;
import dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.gap.PostgresqlEventStreamGapHandler;
import dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.persistence.table_per_aggregate_type.*;
import dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.processor.EventProcessorIT;
import dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.serializer.json.EssentialsJSONEventSerializers;
import dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.subscription.*;
import dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.test_data.*;
import dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.types.*;
import dk.trustworks.essentials.components.foundation.transaction.UnitOfWork;
import dk.trustworks.essentials.components.foundation.types.SubscriberId;
import dk.trustworks.essentials.types.LongRange;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.stream.LongStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

/**
 * F-871 / F-872: a CDC subscription stalled in its handler must not back-pressure the shared per-aggregate-type
 * {@link CdcEventBus} sink. Before, the bus was paced by its slowest subscriber: the stalled subscription filled its
 * hand-over queue and then the sink's buffer, after which the dispatcher got {@code FAIL_OVERFLOW} for every subscriber
 * of the aggregate type - dropped for the healthy subscriptions too under {@code LOG_AND_DROP}, a
 * {@link CdcBusOverflowException} under {@code FAIL_FAST}.
 * <p>
 * Two subscriptions on one aggregate type, a small bus buffer and a polling page of 5: one subscription blocks in its
 * handler on #2 while the "dispatcher" publishes far more events than either buffer holds. The healthy subscription
 * must receive every one of them, in order, from the bus, and every publish must go through. Once the stall ends, the
 * stalled subscription - which overflowed its own buffer and left the bus - must receive every event exactly once, in
 * order, and be back on the bus: it catches up from where it got to and rejoins it, rather than polling until CDC
 * availability next changes. The bus is fed directly from a single-threaded executor standing in for the
 * {@code CdcDispatcher}, as in {@link CdcEventStoreSubscriptionErrorPolicyIsolationIT}.
 * <p>
 * Also: a batched subscription whose batch handler is busy must hold back the ordered backfill-to-live hand-over
 * rather than overflow it - which ended the subscription's flux, silently.
 */
class CdcEventStoreSubscriptionOverflowIT extends AbstractLogicalReplicationPostgresIT {
    private static final int    PAGE_SIZE      = 5;
    private static final int    BUS_BUFFER     = 16;
    private static final long   STALLING_EVENT = 2;
    /**
     * Well beyond the stalled subscription's own buffer plus the bus sink's: enough to overflow both before the fix
     */
    private static final long   EVENTS         = 4 * (BUS_BUFFER + PAGE_SIZE);
    private static final String OVERFLOWS      = "essentials.cdc.eventstore.live_source.overflow.count";

    private PostgresqlEventStore<SeparateTablePerAggregateEventStreamConfiguration> eventStore;
    private CdcEventBus                                                             cdcBus;
    private SimpleMeterRegistry                                                     meterRegistry;
    private EventStoreSubscriptionManager                                           eventStoreSubscriptionManager;
    private ExecutorService                                                         dispatcher;
    private CountDownLatch                                                          mayHandleStallingEvent;

    private void setup(CdcProperties.CdcOverflowPolicy overflowPolicy) {
        var persistenceStrategy = new SeparateTablePerAggregateTypePersistenceStrategy(
                jdbi,
                unitOfWorkFactory,
                new EventProcessorIT.TestPersistableEventMapper(),
                SeparateTablePerAggregateTypeEventStreamConfigurationFactory.defaultConfiguration(EssentialsJSONEventSerializers.create())
        );
        persistenceStrategy.addAggregateEventStreamConfiguration(ORDERS, OrderId.class);
        eventStore = new PostgresqlEventStore<>(unitOfWorkFactory, persistenceStrategy);

        var cdcProperties = new CdcProperties();
        cdcProperties.getEventBus().setBackpressureBufferSize(BUS_BUFFER);
        // No overflow retries: any FAIL_OVERFLOW on the bus surfaces at once, as a drop or as a failed publish
        cdcProperties.getEventBus().setOverflowMaxRetries(0);
        cdcProperties.getEventBus().setOverflowPolicy(overflowPolicy);
        cdcBus = new CdcEventBus(cdcProperties.getEventBus());
        meterRegistry = new SimpleMeterRegistry();
        var availability = new CdcAvailability();
        var cdcEventStore = new CdcEventStore<>(eventStore,
                                                unitOfWorkFactory,
                                                new PostgresqlEventStreamGapHandler<>(unitOfWorkFactory),
                                                cdcBus,
                                                cdcProperties,
                                                availability,
                                                Optional.of(meterRegistry));
        // Force ACTIVE so pollEvents serves the live tail from the CDC bus
        availability.active("it-subscription-overflow-slot");

        eventStoreSubscriptionManager = EventStoreSubscriptionManager.builder()
                                                                     .setEventStore(cdcEventStore)
                                                                     // The polling page - and with it each subscription's CDC hand-over buffer
                                                                     .setEventStorePollingBatchSize(PAGE_SIZE)
                                                                     .setEventStorePollingInterval(Duration.ofMillis(50))
                                                                     .setFencedLockManager(PostgresqlFencedLockManager.builder()
                                                                                                                      .setJdbi(jdbi)
                                                                                                                      .setUnitOfWorkFactory(unitOfWorkFactory)
                                                                                                                      .setLockManagerInstanceId("node-1")
                                                                                                                      .setLockTimeOut(Duration.ofSeconds(3))
                                                                                                                      .setLockConfirmationInterval(Duration.ofMillis(500))
                                                                                                                      .build())
                                                                     .setSnapshotResumePointsEvery(Duration.ofSeconds(1))
                                                                     .setDurableSubscriptionRepository(new PostgresqlDurableSubscriptionRepository(jdbi, cdcEventStore))
                                                                     .build();
        eventStoreSubscriptionManager.start();
        dispatcher = Executors.newSingleThreadExecutor(runnable -> new Thread(runnable, "test-cdc-dispatcher"));
    }

    @AfterEach
    void cleanup() {
        if (mayHandleStallingEvent != null) {
            mayHandleStallingEvent.countDown();
        }
        unitOfWorkFactory.getCurrentUnitOfWork().ifPresent(UnitOfWork::rollback);
        if (eventStoreSubscriptionManager != null) {
            eventStoreSubscriptionManager.stop();
        }
        if (dispatcher != null) {
            dispatcher.shutdownNow();
        }
    }

    @ParameterizedTest
    @EnumSource(CdcProperties.CdcOverflowPolicy.class)
    void a_stalled_subscription_neither_holds_up_nor_loses_events_for_the_other_subscriptions_and_rejoins_the_bus_once_caught_up(CdcProperties.CdcOverflowPolicy overflowPolicy) throws Exception {
        setup(overflowPolicy);
        mayHandleStallingEvent = new CountDownLatch(1);
        var stalledReceived = new CopyOnWriteArrayList<Long>();
        var stalledThreads  = new ConcurrentHashMap<Long, String>();
        var healthyReceived = new CopyOnWriteArrayList<Long>();
        eventStoreSubscriptionManager.subscribeToAggregateEventsAsynchronously(SubscriberId.of("orders-stalled"),
                                                                               ORDERS,
                                                                               GlobalEventOrder.FIRST_GLOBAL_EVENT_ORDER,
                                                                               Optional.empty(),
                                                                               (PersistedEventHandler) event -> {
                                                                                   var globalOrder = event.globalEventOrder().longValue();
                                                                                   if (globalOrder == STALLING_EVENT) {
                                                                                       // A handler stuck in a slow call, or a long SubscriptionErrorPolicy backoff
                                                                                       awaitQuietly(mayHandleStallingEvent);
                                                                                   }
                                                                                   stalledThreads.put(globalOrder, Thread.currentThread().getName());
                                                                                   stalledReceived.add(globalOrder);
                                                                               });
        eventStoreSubscriptionManager.subscribeToAggregateEventsAsynchronously(SubscriberId.of("orders-healthy"),
                                                                               ORDERS,
                                                                               GlobalEventOrder.FIRST_GLOBAL_EVENT_ORDER,
                                                                               Optional.empty(),
                                                                               (PersistedEventHandler) event -> healthyReceived.add(event.globalEventOrder().longValue()));

        // #1 proves both subscriptions are live (delivered by the bus, or by backfill if it beat the attach)
        publishOnDispatcher(appendOrder()).get(10, TimeUnit.SECONDS);
        await().atMost(Duration.ofSeconds(15)).untilAsserted(() -> {
            assertThat(stalledReceived).containsExactly(1L);
            assertThat(healthyReceived).containsExactly(1L);
        });

        // #2..: the stalled subscription blocks on #2. Every publish must go through (under FAIL_FAST a bus overflow
        // fails it), and the healthy subscription must receive every event from the bus (under LOG_AND_DROP a bus
        // overflow drops it). Published at the healthy subscription's pace, so it never falls behind itself
        for (long expected = STALLING_EVENT; expected <= EVENTS; expected++) {
            var globalOrder = appendOrder();
            assertThat(globalOrder).isEqualTo(expected);
            publishOnDispatcher(globalOrder).get(10, TimeUnit.SECONDS);
            await().pollDelay(Duration.ZERO)
                   .pollInterval(Duration.ofMillis(5))
                   .atMost(Duration.ofSeconds(10))
                   .until(() -> healthyReceived.contains(globalOrder));
        }
        assertThat(healthyReceived).containsExactlyElementsOf(globalOrders(1, EVENTS));
        assertThat(stalledReceived).containsExactly(1L);

        mayHandleStallingEvent.countDown();

        // The stalled subscription catches up: every event exactly once, in order
        await().atMost(Duration.ofSeconds(30)).untilAsserted(() -> assertThat(stalledReceived).containsExactlyElementsOf(globalOrders(1, EVENTS)));
        // Only the stalled subscription left the bus
        assertThat(meterRegistry.counter(OVERFLOWS).count()).isEqualTo(1);

        // Both keep going on the bus: the stalled one rejoined it once caught up. It used to poll until CDC availability
        // next changed, which handled the next event on its Publish-* polling thread rather than its Cdc-* bus thread
        var next = appendOrder();
        publishOnDispatcher(next).get(10, TimeUnit.SECONDS);
        await().atMost(Duration.ofSeconds(15)).untilAsserted(() -> {
            assertThat(healthyReceived).containsExactlyElementsOf(globalOrders(1, next));
            assertThat(stalledReceived).containsExactlyElementsOf(globalOrders(1, next));
        });
        assertThat(stalledThreads.get(next)).startsWith("Cdc-orders-stalled-");
        assertThat(meterRegistry.counter(OVERFLOWS).count()).isEqualTo(1);
    }

    /**
     * A batched subscription asks for more events only once its batch handler - on a thread of its own - is done with
     * a batch. While that handler was busy, the ordered backfill-to-live hand-over kept taking events from the live source
     * regardless, until its bounded queue overflowed: the {@link CdcBusOverflowException} ended the subscription's flux,
     * and the subscription - still reporting itself active - silently received nothing more. It must instead hold the
     * live source back, and once the handler is done receive every event exactly once and in order.
     */
    @Test
    void a_batched_subscription_with_a_busy_batch_handler_holds_back_the_live_events_instead_of_ending_on_an_overflow() throws Exception {
        setup(CdcProperties.CdcOverflowPolicy.FAIL_FAST);
        mayHandleStallingEvent = new CountDownLatch(1);
        var received = new CopyOnWriteArrayList<Long>();
        var subscription = eventStoreSubscriptionManager.batchSubscribeToAggregateEventsAsynchronously(
                SubscriberId.of("orders-batched"),
                ORDERS,
                GlobalEventOrder.FIRST_GLOBAL_EVENT_ORDER,
                Optional.empty(),
                PAGE_SIZE,
                Duration.ofMillis(50),
                new BatchedPersistedEventHandler() {
                    @Override
                    public int handleBatch(List<PersistedEvent> events) {
                        if (events.stream().anyMatch(event -> event.globalEventOrder().longValue() == STALLING_EVENT)) {
                            // A batch handler stuck in a slow call, or a long SubscriptionErrorPolicy backoff
                            awaitQuietly(mayHandleStallingEvent);
                        }
                        events.forEach(event -> received.add(event.globalEventOrder().longValue()));
                        return PAGE_SIZE;
                    }
                });

        publishOnDispatcher(appendOrder()).get(10, TimeUnit.SECONDS);
        await().atMost(Duration.ofSeconds(15)).untilAsserted(() -> assertThat(received).containsExactly(1L));

        // Far more than the hand-over holds (BUS_BUFFER) while the batch handler is stuck on the batch with #2
        for (long expected = STALLING_EVENT; expected <= EVENTS; expected++) {
            var globalOrder = appendOrder();
            assertThat(globalOrder).isEqualTo(expected);
            publishOnDispatcher(globalOrder).get(10, TimeUnit.SECONDS);
        }
        // Long enough for the live events to have overrun the hand-over before the fix
        await().pollDelay(Duration.ofSeconds(1)).until(() -> true);
        assertThat(subscription.isActive()).isTrue();

        mayHandleStallingEvent.countDown();

        await().atMost(Duration.ofSeconds(30)).untilAsserted(() -> assertThat(received).containsExactlyElementsOf(globalOrders(1, EVENTS)));
        // And it is still receiving
        var next = appendOrder();
        publishOnDispatcher(next).get(10, TimeUnit.SECONDS);
        await().atMost(Duration.ofSeconds(15)).untilAsserted(() -> assertThat(received).containsExactlyElementsOf(globalOrders(1, next)));
        assertThat(subscription.isActive()).isTrue();
    }

    private static List<Long> globalOrders(long fromInclusive, long toInclusive) {
        return LongStream.rangeClosed(fromInclusive, toInclusive).boxed().toList();
    }

    private static void awaitQuietly(CountDownLatch latch) {
        try {
            latch.await(60, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private Future<?> publishOnDispatcher(long globalOrder) {
        var events = unitOfWorkFactory.withUnitOfWork(() -> eventStore.loadEventsByGlobalOrder(ORDERS, LongRange.between(globalOrder, globalOrder), List.of()).toList());
        assertThat(events).hasSize(1);
        return dispatcher.submit(() -> cdcBus.publish(events));
    }

    /**
     * @return the global order of the appended event
     */
    private long appendOrder() {
        var orderId = OrderId.random();
        unitOfWorkFactory.usingUnitOfWork(() -> eventStore.appendToStream(ORDERS,
                                                                          orderId,
                                                                          EventOrder.NO_EVENTS_PREVIOUSLY_PERSISTED,
                                                                          List.of(new OrderEvent.OrderAdded(orderId, CustomerId.random(), 1))));
        return unitOfWorkFactory.withUnitOfWork(() -> eventStore.findHighestGlobalEventOrderPersisted(ORDERS))
                                .orElseThrow()
                                .longValue();
    }
}
