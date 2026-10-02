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
import reactor.core.Disposable;

import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

/**
 * The CDC bus delivers events in <b>commit</b> order, and that is not global order: two transactions appending to the
 * same aggregate type each take their {@code global_event_order} when they insert, and the one that took the higher
 * order can commit first. A subscription that kept only a high-water mark of the global orders it had delivered
 * dropped the lower one when it arrived later - for good, as no source ever offered it again.
 * <p>
 * Every scenario here makes the higher order commit first deterministically: transaction A appends and is held open,
 * transaction B appends and commits, then A commits. The "dispatcher" is the test itself, publishing each event to the
 * bus right after it committed, in commit order, as {@code CdcDispatcher} does from the WAL. A bystander subscription
 * stays on the bus throughout, as the other subscriptions of an aggregate type do.
 */
class CdcEventStoreCommitOrderIT extends AbstractLogicalReplicationPostgresIT {
    private static final int      PAGE_SIZE        = 10;
    private static final Duration POLLING_INTERVAL = Duration.ofMillis(100);
    private static final Duration DEBOUNCE         = Duration.ofMillis(300);

    private PostgresqlEventStore<SeparateTablePerAggregateEventStreamConfiguration> eventStore;
    private CdcEventStore<SeparateTablePerAggregateEventStreamConfiguration>        cdcEventStore;
    private CdcEventBus                                                             cdcBus;
    private CdcAvailability                                                         availability;
    private PostgresqlDurableSubscriptionRepository                                 durableSubscriptionRepository;
    private final List<EventStoreSubscriptionManager>                               managers = new CopyOnWriteArrayList<>();
    private Disposable                                                              bystander;
    private ExecutorService                                                         heldTransactions;

    @BeforeEach
    void setup() {
        var persistenceStrategy = new SeparateTablePerAggregateTypePersistenceStrategy(
                jdbi,
                unitOfWorkFactory,
                new EventProcessorIT.TestPersistableEventMapper(),
                SeparateTablePerAggregateTypeEventStreamConfigurationFactory.defaultConfiguration(EssentialsJSONEventSerializers.create())
        );
        persistenceStrategy.addAggregateEventStreamConfiguration(ORDERS, OrderId.class);
        var gapHandler = new PostgresqlEventStreamGapHandler<SeparateTablePerAggregateEventStreamConfiguration>(unitOfWorkFactory);
        // One gap handler, shared by the polling path and CdcEventStore, as the Spring starter wires it
        eventStore = PostgresqlEventStore.<SeparateTablePerAggregateEventStreamConfiguration>builder()
                                         .setUnitOfWorkFactory(unitOfWorkFactory)
                                         .setPersistenceStrategy(persistenceStrategy)
                                         .setEventStreamGapHandlerFactory(store -> gapHandler)
                                         .build();

        var cdcProperties = new CdcProperties();
        cdcProperties.getHealthCheck().setActiveCutbackDebounce(DEBOUNCE);
        cdcBus = new CdcEventBus(cdcProperties.getEventBus());
        availability = new CdcAvailability();
        cdcEventStore = new CdcEventStore<>(eventStore,
                                            unitOfWorkFactory,
                                            gapHandler,
                                            cdcBus,
                                            cdcProperties,
                                            availability,
                                            Optional.of(new SimpleMeterRegistry()));
        durableSubscriptionRepository = new PostgresqlDurableSubscriptionRepository(jdbi, cdcEventStore);
        bystander = cdcBus.fluxForAggregate(ORDERS).subscribe();
        heldTransactions = Executors.newCachedThreadPool(runnable -> new Thread(runnable, "test-held-transaction"));
    }

    @AfterEach
    void cleanup() {
        if (heldTransactions != null) {
            heldTransactions.shutdownNow();
        }
        if (bystander != null) {
            bystander.dispose();
        }
        unitOfWorkFactory.getCurrentUnitOfWork().ifPresent(UnitOfWork::rollback);
        managers.forEach(EventStoreSubscriptionManager::stop);
    }

    /**
     * (a) The bare bus leg: the subscription starts on polling while CDC is INACTIVE and moves onto the bus once it is
     * ACTIVE - the warm-up every boot goes through. The bus hands it B, then A.
     */
    @Test
    void a_subscription_moved_onto_the_bus_at_warm_up_receives_an_event_whose_lower_global_order_committed_last() throws Exception {
        var manager  = startManager("node-1");
        var received = subscribe(manager, "bare-bus-leg");
        publish(appendAndCommit());
        await().atMost(Duration.ofSeconds(10)).until(() -> received.size() == 1);
        availability.active("commit-order-slot");
        awaitOnTheBus();

        var a = holdAppend();
        var b = appendAndCommit();
        publish(b);
        await().atMost(Duration.ofSeconds(10)).until(() -> received.contains(b));
        a.commit();
        publish(a.globalOrder());
        var c = appendAndCommit();
        publish(c);

        assertReceivedExactlyOnce(received, List.of(1L, a.globalOrder(), b, c));
    }

    /**
     * (b) {@code BackfillThenLiveOrdered}: the subscription starts while CDC is ACTIVE, so its live tail is the bus. A
     * holds the order right after the backfill's head. Its drain is strict, so it orders A before B rather than skipping
     * A - which it used to do only after the live-drain stall threshold (three minutes by default) re-subscribed it,
     * because the live source had dropped A.
     */
    @Test
    void an_ordered_subscription_receives_an_event_whose_lower_global_order_committed_last_without_waiting_for_the_stall_threshold() throws Exception {
        var manager = startManager("node-1");
        availability.active("commit-order-slot");
        var received = subscribe(manager, "ordered-live-tail");
        publishUntilReceived(received, appendAndCommit());

        var a = holdAppend();
        var b = appendAndCommit();
        publish(b);
        a.commit();
        publish(a.globalOrder());
        var c = appendAndCommit();
        publish(c);

        // Ordered: the drain held B and C back until A arrived
        assertReceivedExactlyOnce(received, List.of(1L, a.globalOrder(), b, c));
        assertThat(received).containsExactly(1L, a.globalOrder(), b, c);
    }

    /**
     * (b) {@code BackfillThenLiveOrdered}, with A at or below the head the backfill read: the backfill misses A
     * (uncommitted) and the bus delivers it once the ordered drain has already moved past the head. It used to be
     * dropped there as "already back-filled".
     */
    @Test
    void an_ordered_subscription_receives_an_event_below_its_backfill_head_that_committed_after_the_backfill() throws Exception {
        var manager = startManager("node-1");
        availability.active("commit-order-slot");
        publish(appendAndCommit());
        var a = holdAppend();
        var b = appendAndCommit();
        publish(b);

        var received = subscribe(manager, "ordered-below-head");
        await().atMost(Duration.ofSeconds(10)).until(() -> received.contains(b));
        a.commit();
        publish(a.globalOrder());
        var c = appendAndCommit();
        publish(c);

        assertReceivedExactlyOnce(received, List.of(1L, a.globalOrder(), b, c));
    }

    /**
     * (c) The polling leg under {@code CdcEventStore} (CDC INACTIVE throughout): polling misses A, its gap handler
     * records A as a transient gap and re-queries it on later polls, and returns it once committed - below what the
     * subscription had delivered, so the high-water mark dropped it, and the gap handler resolved the gap as found.
     */
    @Test
    void a_polling_subscription_receives_a_late_committed_event_its_gap_handler_fetches_later() throws Exception {
        var manager  = startManager("node-1");
        var received = subscribe(manager, "polling-leg");
        appendAndCommit();
        await().atMost(Duration.ofSeconds(10)).until(() -> received.size() == 1);

        var a = holdAppend();
        var b = appendAndCommit();
        await().atMost(Duration.ofSeconds(10)).until(() -> received.contains(b));
        a.commit();
        var c = appendAndCommit();

        assertReceivedExactlyOnce(received, List.of(1L, a.globalOrder(), b, c));
        assertThat(availability.isActive()).isFalse();
    }

    /**
     * Crash safety: the subscription's resume point moves past A once B is handled (gap-filled events are delivered out
     * of global order, so it only ever advances). That is safe only while A is recorded as a transient gap for the
     * subscriber - the polling path records it, the bus leg did not. Here the subscriber stops (as at a restart, or a
     * fenced-lock hand-over to another node) with its resume point past A, A commits while it is down, and it starts
     * again - on polling, as every boot does.
     */
    @Test
    void a_subscription_restarted_with_its_resume_point_past_an_open_hole_still_receives_the_event_that_fills_it() throws Exception {
        var subscriberId = SubscriberId.of("restarted-while-hole-open");
        var manager      = startManager("node-1");
        var received     = subscribe(manager, subscriberId.toString());
        publish(appendAndCommit());
        await().atMost(Duration.ofSeconds(10)).until(() -> received.size() == 1);
        availability.active("commit-order-slot");
        awaitOnTheBus();

        var a = holdAppend();
        var b = appendAndCommit();
        publish(b);
        await().atMost(Duration.ofSeconds(10)).until(() -> received.contains(b));
        await().atMost(Duration.ofSeconds(10)).until(() -> persistedResumePoint(subscriberId) > b);
        manager.stop();
        availability.inactive("commit-order-slot", "restart");

        a.commit();
        var c = appendAndCommit();
        var restarted = subscribe(startManager("node-2"), subscriberId.toString());

        await().atMost(Duration.ofSeconds(15)).untilAsserted(() -> assertThat(restarted).contains(a.globalOrder(), c));
        assertThat(restarted).doesNotHaveDuplicates().doesNotContain(1L, b);
    }

    /**
     * As above, restarted while CDC is ACTIVE ({@code BackfillThenLiveOrdered}), and A commits only after the restart,
     * so it reaches the restarted subscription on the bus, below where it resumed.
     */
    @Test
    void a_subscription_restarted_on_the_bus_with_its_resume_point_past_an_open_hole_receives_the_event_when_it_commits_later() throws Exception {
        var subscriberId = SubscriberId.of("restarted-on-the-bus");
        var manager      = startManager("node-1");
        var received     = subscribe(manager, subscriberId.toString());
        publish(appendAndCommit());
        await().atMost(Duration.ofSeconds(10)).until(() -> received.size() == 1);
        availability.active("commit-order-slot");
        awaitOnTheBus();

        var a = holdAppend();
        var b = appendAndCommit();
        publish(b);
        await().atMost(Duration.ofSeconds(10)).until(() -> received.contains(b));
        await().atMost(Duration.ofSeconds(10)).until(() -> persistedResumePoint(subscriberId) > b);
        manager.stop();

        var restarted = subscribe(startManager("node-2"), subscriberId.toString());
        var c         = appendAndCommit();
        publishUntilReceived(restarted, c);
        a.commit();
        publish(a.globalOrder());

        await().atMost(Duration.ofSeconds(15)).untilAsserted(() -> assertThat(restarted).contains(a.globalOrder(), c));
        await().pollDelay(Duration.ofMillis(500)).atMost(Duration.ofSeconds(5)).until(() -> true);
        assertThat(restarted).doesNotHaveDuplicates().doesNotContain(1L, b);
    }

    // ------------------------------------------------------------------------------------------------------------

    private EventStoreSubscriptionManager startManager(String instanceId) {
        var manager = EventStoreSubscriptionManager.builder()
                                                   .setEventStore(cdcEventStore)
                                                   .setEventStorePollingBatchSize(PAGE_SIZE)
                                                   .setEventStorePollingInterval(POLLING_INTERVAL)
                                                   .setFencedLockManager(PostgresqlFencedLockManager.builder()
                                                                                                    .setJdbi(jdbi)
                                                                                                    .setUnitOfWorkFactory(unitOfWorkFactory)
                                                                                                    .setLockManagerInstanceId(instanceId)
                                                                                                    .setLockTimeOut(Duration.ofSeconds(3))
                                                                                                    .setLockConfirmationInterval(Duration.ofMillis(500))
                                                                                                    .build())
                                                   .setSnapshotResumePointsEvery(Duration.ofMillis(200))
                                                   .setDurableSubscriptionRepository(durableSubscriptionRepository)
                                                   .build();
        manager.start();
        managers.add(manager);
        return manager;
    }

    private static List<Long> subscribe(EventStoreSubscriptionManager manager, String subscriberId) {
        var received = new CopyOnWriteArrayList<Long>();
        manager.subscribeToAggregateEventsAsynchronously(SubscriberId.of(subscriberId),
                                                         ORDERS,
                                                         GlobalEventOrder.FIRST_GLOBAL_EVENT_ORDER,
                                                         Optional.empty(),
                                                         (PersistedEventHandler) event -> received.add(event.globalEventOrder().longValue()));
        return received;
    }

    private long persistedResumePoint(SubscriberId subscriberId) {
        return durableSubscriptionRepository.getResumePoint(subscriberId, ORDERS)
                                            .map(resumePoint -> resumePoint.getResumeFromAndIncluding().longValue())
                                            .orElse(0L);
    }

    private static void assertReceivedExactlyOnce(List<Long> received, List<Long> expected) {
        await().atMost(Duration.ofSeconds(15)).untilAsserted(() -> assertThat(received).containsAll(expected));
        // Anything delivered twice would have arrived by now
        await().pollDelay(Duration.ofMillis(500)).atMost(Duration.ofSeconds(5)).until(() -> true);
        assertThat(received).containsExactlyInAnyOrderElementsOf(expected);
    }

    /**
     * Once the debounce has passed, the subscription is on the bus - the catch-up of an empty backlog is immediate
     */
    private static void awaitOnTheBus() {
        await().pollDelay(DEBOUNCE.multipliedBy(3)).atMost(Duration.ofSeconds(10)).until(() -> true);
    }

    /**
     * Publishes to the bus, as the dispatcher does once the transaction committed
     */
    private void publish(long globalOrder) {
        cdcBus.publish(List.of(load(globalOrder)));
    }

    /**
     * Republished until the subscription has it - its live tail may not be attached yet; the repeats are filtered out
     */
    private void publishUntilReceived(List<Long> received, long globalOrder) {
        var event = load(globalOrder);
        await().atMost(Duration.ofSeconds(10)).untilAsserted(() -> {
            cdcBus.publish(List.of(event));
            assertThat(received).contains(globalOrder);
        });
    }

    private PersistedEvent load(long globalOrder) {
        return unitOfWorkFactory.withUnitOfWork(() -> eventStore.loadEventsByGlobalOrder(ORDERS, LongRange.only(globalOrder), List.of()).toList())
                                .getFirst();
    }

    private long appendAndCommit() {
        return unitOfWorkFactory.withUnitOfWork(this::appendTo);
    }

    /**
     * Appends in a transaction of its own that stays open until {@link HeldAppend#commit()}: its global order is taken,
     * the event is not visible yet
     */
    private HeldAppend holdAppend() throws Exception {
        var globalOrder = new CompletableFuture<Long>();
        var mayCommit   = new CountDownLatch(1);
        var committed = heldTransactions.submit(() -> unitOfWorkFactory.usingUnitOfWork(unitOfWork -> {
            globalOrder.complete(appendTo(unitOfWork));
            if (!mayCommit.await(30, TimeUnit.SECONDS)) {
                throw new IllegalStateException("Held transaction was never released");
            }
        }));
        return new HeldAppend(globalOrder.get(10, TimeUnit.SECONDS), mayCommit, committed);
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

    private record HeldAppend(long globalOrder, CountDownLatch mayCommit, Future<?> committed) {
        void commit() throws Exception {
            mayCommit.countDown();
            committed.get(10, TimeUnit.SECONDS);
        }
    }
}
