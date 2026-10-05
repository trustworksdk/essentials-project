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

import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

/**
 * A {@link SubscriptionErrorPolicy.Mode#RETRY_N_THEN_SKIP} backoff sleeps on the subscription's delivery thread. Under
 * CDC the bus emits on the one {@code cdc-dispatcher-<slot>} thread shared by every CDC subscription, so
 * {@link CdcEventStore} must hand each subscription over to a thread of its own - otherwise one subscription in its
 * backoff stalls CDC delivery for all the others.
 * <p>
 * Two subscribers on the same aggregate type: one fails #2 under {@code retryThenSkip}, the other is healthy. While the
 * failing one is backing off, the "dispatcher" must be free to publish the next event and the healthy subscriber must
 * receive it. The bus is fed directly from a single-threaded executor standing in for the {@code CdcDispatcher}, and
 * availability is driven by hand, as in {@link CdcEventStoreLiveTailHoleIT}.
 * <p>
 * Also pins that a backoff interrupted by the adaptive live source switching a subscription from polling to the CDC bus
 * is waited out, not read as a stop (F-880).
 */
class CdcEventStoreSubscriptionErrorPolicyIsolationIT extends AbstractLogicalReplicationPostgresIT {
    private static final String   SLOT          = "it-error-policy-isolation-slot";
    private static final long     FAILING_EVENT = 2;
    private static final int      MAX_RETRIES   = 2;
    /**
     * Long enough that every "while it is still backing off" assertion below has seconds of slack. Those assertions
     * are pinned by the failing handler's attempt count, read afterwards, not by sub-second deadlines
     */
    private static final Duration BACKOFF       = Duration.ofSeconds(5);

    private PostgresqlEventStore<SeparateTablePerAggregateEventStreamConfiguration> eventStore;
    private CdcEventBus                                                             cdcBus;
    private CdcAvailability                                                         availability;
    private SimpleMeterRegistry                                                     meterRegistry;
    private EventStoreSubscriptionManager                                           eventStoreSubscriptionManager;
    private ExecutorService                                                         dispatcher;
    private CdcEventStore<SeparateTablePerAggregateEventStreamConfiguration>        cdcEventStore;

    @BeforeEach
    void setup() {
        var persistenceStrategy = new SeparateTablePerAggregateTypePersistenceStrategy(
                jdbi,
                unitOfWorkFactory,
                new EventProcessorIT.TestPersistableEventMapper(),
                SeparateTablePerAggregateTypeEventStreamConfigurationFactory.defaultConfiguration(EssentialsJSONEventSerializers.create())
        );
        persistenceStrategy.addAggregateEventStreamConfiguration(ORDERS, OrderId.class);
        eventStore = new PostgresqlEventStore<>(unitOfWorkFactory, persistenceStrategy);

        cdcBus = new CdcEventBus();
        // Starts INACTIVE: each test decides whether its subscriptions start on the CDC bus or on polling
        availability = new CdcAvailability();
        meterRegistry = new SimpleMeterRegistry();
        var cdcProperties = new CdcProperties();
        cdcProperties.getHealthCheck().setActiveCutbackDebounce(Duration.ofMillis(200));
        cdcEventStore = new CdcEventStore<>(eventStore,
                                                unitOfWorkFactory,
                                                new PostgresqlEventStreamGapHandler<>(unitOfWorkFactory),
                                                cdcBus,
                                                cdcProperties,
                                                availability,
                                                Optional.of(meterRegistry));

        eventStoreSubscriptionManager = EventStoreSubscriptionManager.builder()
                                                                     .setEventStore(cdcEventStore)
                                                                     .setEventStorePollingBatchSize(50)
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
                                                                     .setSubscriptionErrorPolicy(SubscriptionErrorPolicy.retryThenSkip(MAX_RETRIES, BACKOFF, BACKOFF))
                                                                     .build();
        eventStoreSubscriptionManager.start();
        dispatcher = Executors.newSingleThreadExecutor(runnable -> new Thread(runnable, "test-cdc-dispatcher"));
    }

    @AfterEach
    void cleanup() {
        unitOfWorkFactory.getCurrentUnitOfWork().ifPresent(UnitOfWork::rollback);
        if (eventStoreSubscriptionManager != null) {
            eventStoreSubscriptionManager.stop();
        }
        if (dispatcher != null) {
            dispatcher.shutdownNow();
        }
    }

    @Test
    void a_subscription_in_its_retry_backoff_does_not_hold_up_the_other_cdc_subscriptions() throws Exception {
        // ACTIVE before subscribing, so pollEvents serves the live tail from the CDC bus
        availability.active(SLOT);
        var failingReceived = new CopyOnWriteArrayList<Long>();
        var failingAttempts = new AtomicInteger();
        var healthyReceived = new CopyOnWriteArrayList<Long>();
        eventStoreSubscriptionManager.subscribeToAggregateEventsAsynchronously(SubscriberId.of("orders-failing"),
                                                                               ORDERS,
                                                                               GlobalEventOrder.FIRST_GLOBAL_EVENT_ORDER,
                                                                               Optional.empty(),
                                                                               (PersistedEventHandler) event -> {
                                                                                   var globalOrder = event.globalEventOrder().longValue();
                                                                                   if (globalOrder == FAILING_EVENT) {
                                                                                       failingAttempts.incrementAndGet();
                                                                                       throw new IllegalStateException("Intentional failure handling event #" + globalOrder);
                                                                                   }
                                                                                   failingReceived.add(globalOrder);
                                                                               });
        eventStoreSubscriptionManager.subscribeToAggregateEventsAsynchronously(SubscriberId.of("orders-healthy"),
                                                                               ORDERS,
                                                                               GlobalEventOrder.FIRST_GLOBAL_EVENT_ORDER,
                                                                               Optional.empty(),
                                                                               (PersistedEventHandler) event -> healthyReceived.add(event.globalEventOrder().longValue()));

        // #1 proves both subscriptions are live (delivered by the bus, or by backfill if it beat the attach)
        publishOnDispatcher(appendOrder()).get(10, TimeUnit.SECONDS);
        await().atMost(Duration.ofSeconds(15)).untilAsserted(() -> {
            assertThat(failingReceived).containsExactly(1L);
            assertThat(healthyReceived).containsExactly(1L);
        });

        // Not awaited: before the fix this publish returned only once the failing subscription had given up on #2
        var publishingTwo = publishOnDispatcher(appendOrder());
        await().atMost(Duration.ofSeconds(10)).until(() -> failingAttempts.get() >= 1);

        // The failing subscription now sleeps in its backoff. The dispatcher must be free to publish, and the healthy
        // subscription must receive, before the failing one even makes its first retry. The deadlines are generous; the
        // attempt count read afterwards is what proves the isolation - before the fix none of this could complete until
        // the failing subscription had made every retry
        var publishingThree = publishOnDispatcher(appendOrder());
        publishingTwo.get(BACKOFF.toMillis(), TimeUnit.MILLISECONDS);
        publishingThree.get(BACKOFF.toMillis(), TimeUnit.MILLISECONDS);
        await().atMost(BACKOFF).untilAsserted(() -> assertThat(healthyReceived).containsExactly(1L, 2L, 3L));
        assertThat(failingAttempts.get()).as("still in the backoff before its first retry of #2").isEqualTo(1);
        assertThat(failingReceived).as("still retrying #2").containsExactly(1L);

        // Per-subscription order is kept: the failing subscription gives up on #2 and only then handles #3
        await().atMost(BACKOFF.multipliedBy(MAX_RETRIES + 2)).untilAsserted(() -> assertThat(failingReceived).containsExactly(1L, 3L));
        assertThat(failingAttempts.get()).isEqualTo(1 + MAX_RETRIES);
    }

    /**
     * F-880: once CDC is active, the adaptive live source switches a subscription that started on polling over to the
     * CDC bus, and cancelling the polling source interrupts the polling thread - here while the handler sits in its
     * retry backoff. That interrupt is not a stop: the retry still runs, and the subscription keeps handling events
     */
    @Test
    void a_retry_backoff_interrupted_by_the_switch_from_polling_to_the_cdc_bus_is_waited_out_and_the_subscription_keeps_going() throws Exception {
        // CDC not active yet: the subscription starts on polling, as every subscription started before the tailer does
        var attemptsAtFailingEvent = new AtomicInteger();
        var received               = new CopyOnWriteArrayList<Long>();
        var subscription = eventStoreSubscriptionManager.subscribeToAggregateEventsAsynchronously(SubscriberId.of("orders-switched"),
                                                                                                  ORDERS,
                                                                                                  GlobalEventOrder.FIRST_GLOBAL_EVENT_ORDER,
                                                                                                  Optional.empty(),
                                                                                                  (PersistedEventHandler) event -> {
                                                                                                      var globalOrder = event.globalEventOrder().longValue();
                                                                                                      if (globalOrder == FAILING_EVENT && attemptsAtFailingEvent.incrementAndGet() == 1) {
                                                                                                          throw new IllegalStateException("Intentional failure handling event #" + globalOrder);
                                                                                                      }
                                                                                                      received.add(globalOrder);
                                                                                                  });
        appendOrder();
        await().atMost(Duration.ofSeconds(15)).untilAsserted(() -> assertThat(received).containsExactly(1L));
        appendOrder();
        await().atMost(Duration.ofSeconds(10)).until(() -> attemptsAtFailingEvent.get() == 1);

        // CDC becomes active while #2 backs off: once the cut-over debounce has passed, the switch to the bus cancels the
        // polling source, which interrupts the thread #2 is backing off on
        availability.active(SLOT);

        // The retry still runs after the backoff and handles #2, instead of abandoning it as if the subscriber had stopped
        await().atMost(BACKOFF.multipliedBy(2)).untilAsserted(() -> assertThat(received).containsExactly(1L, 2L));
        assertThat(attemptsAtFailingEvent.get()).isEqualTo(2);
        // The switch to the bus did happen - its source is subscribed only once #2's handling has returned
        await().atMost(Duration.ofSeconds(5)).until(() -> meterRegistry.counter("essentials.cdc.eventstore.live_source.switch.count").count() == 2);

        // Events published after the switch are handled. Republished until the bus leg is attached - repeats are filtered
        var three = appendOrder();
        var four  = appendOrder();
        await().atMost(Duration.ofSeconds(15)).untilAsserted(() -> {
            publishOnDispatcher(three).get(5, TimeUnit.SECONDS);
            publishOnDispatcher(four).get(5, TimeUnit.SECONDS);
            assertThat(received).containsExactly(1L, 2L, 3L, 4L);
        });
        assertThat(subscription.isStoppedByErrorPolicy()).isFalse();
        assertThat(subscription.isActive()).isTrue();
    }

    /**
     * A subscription served by the CDC bus that {@link SubscriptionErrorPolicy.Mode#RETRY_N_THEN_STOP} stopped is resumed
     * at the failed event: the resumed subscription catches up from its held resume point - the failed event and what
     * arrived on the bus while it was stopped - and then follows the bus again
     */
    @Test
    void a_cdc_subscription_stopped_by_its_error_policy_is_resumed_at_the_failed_event() throws Exception {
        var stoppingManager = EventStoreSubscriptionManager.builder()
                                                           .setEventStore(cdcEventStore)
                                                           .setEventStorePollingBatchSize(50)
                                                           .setEventStorePollingInterval(Duration.ofMillis(50))
                                                           .setFencedLockManager(PostgresqlFencedLockManager.builder()
                                                                                                            .setJdbi(jdbi)
                                                                                                            .setUnitOfWorkFactory(unitOfWorkFactory)
                                                                                                            .setLockManagerInstanceId("node-2")
                                                                                                            .setLockTimeOut(Duration.ofSeconds(3))
                                                                                                            .setLockConfirmationInterval(Duration.ofMillis(500))
                                                                                                            .build())
                                                           .setSnapshotResumePointsEvery(Duration.ofSeconds(1))
                                                           .setDurableSubscriptionRepository(new PostgresqlDurableSubscriptionRepository(jdbi, cdcEventStore))
                                                           .setSubscriptionErrorPolicy(SubscriptionErrorPolicy.retryThenStop(1, Duration.ofMillis(50), Duration.ofMillis(50)))
                                                           .build();
        stoppingManager.start();
        try {
            // ACTIVE before subscribing, so pollEvents serves the live tail from the CDC bus
            availability.active(SLOT);
            var failing                = new java.util.concurrent.atomic.AtomicBoolean(true);
            var attemptsAtFailingEvent = new AtomicInteger();
            var received               = new CopyOnWriteArrayList<Long>();
            var subscription = stoppingManager.subscribeToAggregateEventsAsynchronously(SubscriberId.of("orders-resumed"),
                                                                                        ORDERS,
                                                                                        GlobalEventOrder.FIRST_GLOBAL_EVENT_ORDER,
                                                                                        Optional.empty(),
                                                                                        (PersistedEventHandler) event -> {
                                                                                            var globalOrder = event.globalEventOrder().longValue();
                                                                                            if (globalOrder == FAILING_EVENT) {
                                                                                                attemptsAtFailingEvent.incrementAndGet();
                                                                                                if (failing.get()) {
                                                                                                    throw new IllegalStateException("Intentional failure handling event #" + globalOrder);
                                                                                                }
                                                                                            }
                                                                                            received.add(globalOrder);
                                                                                        });

            var one = appendOrder();
            await().atMost(Duration.ofSeconds(15)).untilAsserted(() -> {
                publishOnDispatcher(one).get(5, TimeUnit.SECONDS);
                assertThat(received).containsExactly(1L);
            });
            publishOnDispatcher(appendOrder()).get(10, TimeUnit.SECONDS);
            // 1 attempt + 1 retry, then stopped at #2
            await().atMost(Duration.ofSeconds(10)).until(subscription::isStoppedByErrorPolicy);
            assertThat(attemptsAtFailingEvent.get()).isEqualTo(2);
            assertThat(subscription.isActive()).isTrue();

            // Published while stopped - not handled
            var three = appendOrder();
            publishOnDispatcher(three).get(10, TimeUnit.SECONDS);
            Thread.sleep(500);
            assertThat(received).containsExactly(1L);

            failing.set(false);
            assertThat(subscription.resumeIfStoppedByErrorPolicy()).isTrue();
            await().atMost(Duration.ofSeconds(15)).untilAsserted(() -> assertThat(received).containsExactly(1L, 2L, 3L));
            assertThat(attemptsAtFailingEvent.get()).isEqualTo(3);
            assertThat(subscription.isStoppedByErrorPolicy()).isFalse();

            // ... and then follows the bus again. Republished until the bus leg is attached - repeats are filtered
            var four = appendOrder();
            await().atMost(Duration.ofSeconds(15)).untilAsserted(() -> {
                publishOnDispatcher(four).get(5, TimeUnit.SECONDS);
                assertThat(received).containsExactly(1L, 2L, 3L, 4L);
            });
        } finally {
            stoppingManager.stop();
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
