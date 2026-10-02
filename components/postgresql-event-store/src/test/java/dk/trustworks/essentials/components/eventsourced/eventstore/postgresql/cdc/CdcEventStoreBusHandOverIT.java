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
import org.slf4j.*;
import reactor.core.Disposable;

import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

/**
 * The move of a running subscription onto the CDC bus must be gap-free. The bus replays nothing to a late subscriber,
 * so a subscription that attached to it without catching up lost every event the bus had published before the attach
 * that polling had not delivered yet - committed after polling's last fetch, during the {@code activeCutbackDebounce}
 * window, or while the switch waited for an event still in the handler - and the next bus event moved it past them for
 * good. That hit every subscription started before CDC was ACTIVE (warm-up), and every subscription after a
 * replication outage.
 * <p>
 * Events are committed continuously, one at a time, across the availability changes. A single-threaded "dispatcher"
 * publishes every committed event to the bus, in order, while availability is ACTIVE - and the backlog once it is ACTIVE
 * again - as {@code CdcDispatcher} does from the inbox. A bystander subscription stays on the bus throughout, as the
 * other subscriptions of an aggregate type do (without one the bus sink retains what it is handed before anyone
 * subscribes and replays it to its first subscriber, which would hide the loss). Polling runs every 500 ms, so its last
 * fetch is up to that long before the move onto the bus.
 */
class CdcEventStoreBusHandOverIT extends AbstractLogicalReplicationPostgresIT {
    private static final Logger   log              = LoggerFactory.getLogger(CdcEventStoreBusHandOverIT.class);
    private static final int      PAGE_SIZE        = 10;
    private static final Duration POLLING_INTERVAL = Duration.ofMillis(500);
    private static final Duration DEBOUNCE         = Duration.ofMillis(500);
    private static final Duration COMMIT_INTERVAL  = Duration.ofMillis(10);

    private PostgresqlEventStore<SeparateTablePerAggregateEventStreamConfiguration> eventStore;
    private CdcEventBus                                                             cdcBus;
    private CdcAvailability                                                         availability;
    private EventStoreSubscriptionManager                                           eventStoreSubscriptionManager;
    private ScheduledExecutorService                                                writer;
    private ScheduledExecutorService                                                dispatcher;
    private Disposable                                                              bystander;
    private final List<Long>                                                        committed     = new CopyOnWriteArrayList<>();
    private final AtomicLong                                                        publishedUpTo = new AtomicLong();

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

        var cdcProperties = new CdcProperties();
        cdcProperties.getHealthCheck().setActiveCutbackDebounce(DEBOUNCE);
        cdcBus = new CdcEventBus(cdcProperties.getEventBus());
        availability = new CdcAvailability();
        var cdcEventStore = new CdcEventStore<>(eventStore,
                                                unitOfWorkFactory,
                                                new PostgresqlEventStreamGapHandler<>(unitOfWorkFactory),
                                                cdcBus,
                                                cdcProperties,
                                                availability,
                                                Optional.of(new SimpleMeterRegistry()));

        eventStoreSubscriptionManager = EventStoreSubscriptionManager.builder()
                                                                     .setEventStore(cdcEventStore)
                                                                     .setEventStorePollingBatchSize(PAGE_SIZE)
                                                                     .setEventStorePollingInterval(POLLING_INTERVAL)
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
        bystander = cdcBus.fluxForAggregate(ORDERS).subscribe();
        writer = Executors.newSingleThreadScheduledExecutor(runnable -> new Thread(runnable, "test-writer"));
        dispatcher = Executors.newSingleThreadScheduledExecutor(runnable -> new Thread(runnable, "test-cdc-dispatcher"));
        dispatcher.scheduleWithFixedDelay(this::publishCommittedEvents, 0, 5, TimeUnit.MILLISECONDS);
    }

    @AfterEach
    void cleanup() {
        if (writer != null) {
            writer.shutdownNow();
        }
        if (dispatcher != null) {
            dispatcher.shutdownNow();
        }
        if (bystander != null) {
            bystander.dispose();
        }
        unitOfWorkFactory.getCurrentUnitOfWork().ifPresent(UnitOfWork::rollback);
        if (eventStoreSubscriptionManager != null) {
            eventStoreSubscriptionManager.stop();
        }
    }

    /**
     * (a) Warm-up: the subscription starts on polling while CDC is INACTIVE, and CDC comes up - with a fresh slot,
     * publishing from then on - while events keep being committed through the debounce and after it. A rolled-back
     * append leaves a hole in the global order along the way.
     */
    @Test
    void a_subscription_started_before_cdc_is_active_receives_every_event_exactly_once_and_in_order_across_the_move_onto_the_bus() throws Exception {
        var received = subscribe("orders-warm-up");
        startCommitting();
        await().atMost(Duration.ofSeconds(10)).until(() -> committed.size() >= 50);
        rollBackAnAppend();

        // A fresh slot: publishes what is committed from now on
        publishedUpTo.set(highestPersisted());
        availability.active("it-hand-over-slot");
        await().pollDelay(DEBOUNCE.multipliedBy(4)).atMost(Duration.ofSeconds(10)).until(() -> true);
        rollBackAnAppend();
        await().pollDelay(Duration.ofMillis(500)).atMost(Duration.ofSeconds(10)).until(() -> true);

        assertEveryCommittedEventIsReceivedExactlyOnceInOrder(received);
    }

    /**
     * (b) Recovery: the subscription starts while CDC is ACTIVE ({@code BackfillThenLiveOrdered} with the adaptive live
     * source as its live tail), CDC fails and recovers, and events keep being committed throughout. The dispatcher
     * publishes nothing during the outage and the backlog once CDC is back - while the subscription is still polling,
     * during the debounce. Before, the live source lost what polling had not fetched of that backlog, and the ordered
     * drain stalled on the first such event until its stall threshold (three minutes by default) re-subscribed it.
     */
    @Test
    void a_subscription_receives_every_event_exactly_once_and_in_order_across_an_outage_and_the_move_back_onto_the_bus() throws Exception {
        availability.active("it-hand-over-slot");
        var received = subscribe("orders-recovery");
        startCommitting();
        await().atMost(Duration.ofSeconds(10)).until(() -> committed.size() >= 50);

        availability.failed("it-hand-over-slot", "simulated replication outage");
        await().pollDelay(Duration.ofSeconds(1)).atMost(Duration.ofSeconds(10)).until(() -> true);
        availability.active("it-hand-over-slot");
        await().pollDelay(DEBOUNCE.multipliedBy(4)).atMost(Duration.ofSeconds(10)).until(() -> true);

        assertEveryCommittedEventIsReceivedExactlyOnceInOrder(received);
        assertThat(availability.getFallbackCount()).isEqualTo(1);
    }

    private void assertEveryCommittedEventIsReceivedExactlyOnceInOrder(List<Long> received) throws Exception {
        writer.shutdown();
        assertThat(writer.awaitTermination(10, TimeUnit.SECONDS)).isTrue();
        var lastCommitted = committed.getLast();
        log.info("Committed {} events, up to global order {}", committed.size(), lastCommitted);
        // Well below the live-drain stall threshold that eventually re-delivered what the bus leg lost before
        await().atMost(Duration.ofSeconds(30)).until(() -> !received.isEmpty() && received.getLast() >= lastCommitted || received.size() > committed.size());
        await().pollDelay(Duration.ofMillis(500)).atMost(Duration.ofSeconds(5)).until(() -> true);
        assertThat(received).containsExactlyElementsOf(committed);
    }

    private List<Long> subscribe(String subscriberId) {
        var received = new CopyOnWriteArrayList<Long>();
        eventStoreSubscriptionManager.subscribeToAggregateEventsAsynchronously(SubscriberId.of(subscriberId),
                                                                               ORDERS,
                                                                               GlobalEventOrder.FIRST_GLOBAL_EVENT_ORDER,
                                                                               Optional.empty(),
                                                                               (PersistedEventHandler) event -> received.add(event.globalEventOrder().longValue()));
        return received;
    }

    private void startCommitting() {
        writer.scheduleWithFixedDelay(() -> {
            try {
                committed.add(appendOrder());
            } catch (RuntimeException e) {
                log.error("Append failed", e);
            }
        }, 0, COMMIT_INTERVAL.toMillis(), TimeUnit.MILLISECONDS);
    }

    /**
     * Consumes a global order without committing it - a hole the bus never sees. Runs on the writer, so commits stay
     * one at a time and in global order
     */
    private void rollBackAnAppend() throws Exception {
        writer.submit(() -> {
            try {
                unitOfWorkFactory.usingUnitOfWork(unitOfWork -> {
                    appendTo(unitOfWork);
                    unitOfWork.markAsRollbackOnly();
                });
            } catch (RuntimeException expected) {
                // Rolled back
            }
        }).get(10, TimeUnit.SECONDS);
    }

    /**
     * The stand-in for the CdcDispatcher: while CDC is ACTIVE, publishes every event committed after the last one it
     * published, in global order (commits are one at a time, so that is commit order too)
     */
    private void publishCommittedEvents() {
        try {
            if (!availability.isActive()) {
                return;
            }
            long from = publishedUpTo.get() + 1;
            var events = unitOfWorkFactory.withUnitOfWork(() -> eventStore.loadEventsByGlobalOrder(ORDERS, LongRange.between(from, from + 999), List.of()).toList());
            if (!events.isEmpty()) {
                cdcBus.publish(events);
                publishedUpTo.set(events.getLast().globalEventOrder().longValue());
            }
        } catch (RuntimeException e) {
            log.error("Publishing failed", e);
        }
    }

    private long highestPersisted() {
        return unitOfWorkFactory.withUnitOfWork(() -> eventStore.findHighestGlobalEventOrderPersisted(ORDERS))
                                .map(GlobalEventOrder::longValue)
                                .orElse(0L);
    }

    /**
     * @return the global order of the appended event
     */
    private long appendOrder() {
        return unitOfWorkFactory.withUnitOfWork(this::appendTo);
    }

    private long appendTo(UnitOfWork unitOfWork) {
        var orderId = OrderId.random();
        return eventStore.appendToStream(ORDERS,
                                         orderId,
                                         EventOrder.NO_EVENTS_PREVIOUSLY_PERSISTED,
                                         List.of(new OrderEvent.OrderAdded(orderId, CustomerId.random(), 1)))
                         .eventList()
                         .getFirst()
                         .globalEventOrder()
                         .longValue();
    }
}
