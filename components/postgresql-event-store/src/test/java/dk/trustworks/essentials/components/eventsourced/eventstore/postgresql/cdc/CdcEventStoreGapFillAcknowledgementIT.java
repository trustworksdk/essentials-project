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
import dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.*;
import dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.eventstream.*;
import dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.gap.PostgresqlEventStreamGapHandler;
import dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.observability.EventStoreSubscriptionObserver;
import dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.persistence.table_per_aggregate_type.*;
import dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.processor.EventProcessorIT;
import dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.serializer.json.EssentialsJSONEventSerializers;
import dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.subscription.*;
import dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.test_data.*;
import dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.types.*;
import dk.trustworks.essentials.components.foundation.transaction.UnitOfWork;
import dk.trustworks.essentials.components.foundation.types.*;
import dk.trustworks.essentials.shared.functional.tuple.Pair;
import dk.trustworks.essentials.types.LongRange;
import org.junit.jupiter.api.*;
import reactor.core.Disposable;

import java.io.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import java.util.function.LongConsumer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

/**
 * A gap fill - an event whose transaction committed after higher global orders - reaches a subscriber below its resume
 * point, so the transient gap is the only durable record that the fill is still owed. The {@link CdcEventStore} used to
 * resolve that gap once it had handed the fill on, which is not when the subscriber has handled it:
 * <ul>
 *     <li>(a) a batched subscriber holds it for its next batch,</li>
 *     <li>(b) a {@link PersistedEventSubscriber} retries an I/O failure asynchronously,</li>
 *     <li>(c) a subscriber that withholds demand leaves it in the {@code limitRate} queue in front of it.</li>
 * </ul>
 * A process that died in that window - or for (c) a plain stop, which drops the queue - restarted above the fill, without
 * a gap to ask for it again. The subscription manager's subscribers now acknowledge each event they handled, and the
 * gap is resolved on that acknowledgement, in the unit of work that handled it.
 * <p>
 * As in {@link CdcEventStoreCommitOrderIT}, the test is the dispatcher: it publishes each event to the bus once it
 * committed, in commit order.
 */
class CdcEventStoreGapFillAcknowledgementIT extends AbstractLogicalReplicationPostgresIT {
    private static final Duration POLLING_INTERVAL = Duration.ofMillis(100);
    private static final Duration DEBOUNCE         = Duration.ofMillis(300);
    /**
     * The polling batch size of the subscriptions that withhold demand: the events a subscriber asks for up front
     */
    private static final int      DEMAND_WINDOW    = 3;

    private PostgresqlEventStore<SeparateTablePerAggregateEventStreamConfiguration> eventStore;
    private CdcEventStore<SeparateTablePerAggregateEventStreamConfiguration>        cdcEventStore;
    private CdcEventBus                                                             cdcBus;
    private CdcAvailability                                                         availability;
    private PostgresqlDurableSubscriptionRepository                                 durableSubscriptionRepository;
    private final List<EventStoreSubscriptionManager>                               managers      = new CopyOnWriteArrayList<>();
    private final List<Disposable>                                                  subscriptions = new CopyOnWriteArrayList<>();
    private Disposable                                                              bystander;
    private ExecutorService                                                         heldTransactions;
    private final RecordingPolls                                                    polls         = new RecordingPolls();

    @BeforeEach
    void setup() {
        setup(new PostgresqlEventStreamGapHandler<>(unitOfWorkFactory));
        heldTransactions = Executors.newCachedThreadPool(runnable -> new Thread(runnable, "test-held-transaction"));
    }

    /**
     * The event store, the CDC event store over it and the bus, all with {@code gapHandler} - called again by a test that
     * needs another gap handler, before it subscribes
     */
    private void setup(PostgresqlEventStreamGapHandler<SeparateTablePerAggregateEventStreamConfiguration> gapHandler) {
        if (bystander != null) {
            bystander.dispose();
        }
        var persistenceStrategy = new SeparateTablePerAggregateTypePersistenceStrategy(
                jdbi,
                unitOfWorkFactory,
                new EventProcessorIT.TestPersistableEventMapper(),
                SeparateTablePerAggregateTypeEventStreamConfigurationFactory.defaultConfiguration(EssentialsJSONEventSerializers.create())
        );
        persistenceStrategy.addAggregateEventStreamConfiguration(ORDERS, OrderId.class);
        eventStore = PostgresqlEventStore.<SeparateTablePerAggregateEventStreamConfiguration>builder()
                                         .setUnitOfWorkFactory(unitOfWorkFactory)
                                         .setPersistenceStrategy(persistenceStrategy)
                                         .setEventStreamGapHandlerFactory(store -> gapHandler)
                                         .setEventStoreSubscriptionObserver(polls)
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
                                            availability);
        durableSubscriptionRepository = new PostgresqlDurableSubscriptionRepository(jdbi, cdcEventStore);
        bystander = cdcBus.fluxForAggregate(ORDERS).subscribe();
    }

    @AfterEach
    void cleanup() {
        if (heldTransactions != null) {
            heldTransactions.shutdownNow();
        }
        if (bystander != null) {
            bystander.dispose();
        }
        subscriptions.forEach(Disposable::dispose);
        subscriptions.clear();
        unitOfWorkFactory.getCurrentUnitOfWork().ifPresent(UnitOfWork::rollback);
        managers.forEach(EventStoreSubscriptionManager::stop);
        managers.clear();
    }

    /**
     * (a) on the bus: the fill waits for its batch when the process dies
     */
    @Test
    void a_gap_fill_from_the_bus_waiting_for_its_batch_when_the_process_dies_is_delivered_after_the_restart() throws Exception {
        var subscriberId = SubscriberId.of("cdc-batched-fill-crash");
        var manager      = startManager(10);
        var handled      = new CopyOnWriteArrayList<Long>();
        var subscription = manager.batchSubscribeToAggregateEventsAsynchronously(subscriberId,
                                                                                 ORDERS,
                                                                                 GlobalEventOrder.FIRST_GLOBAL_EVENT_ORDER,
                                                                                 Optional.empty(),
                                                                                 100,
                                                                                 // Long enough for the crash below to come first
                                                                                 Duration.ofSeconds(4),
                                                                                 events -> {
                                                                                     events.forEach(event -> handled.add(event.globalEventOrder().longValue()));
                                                                                     return events.size();
                                                                                 });
        var fill = handOnAGapFillFromTheBus(subscriberId, handled);

        simulateCrash(subscription, subscriberId, fill.resumePointPersisted());
        assertThat(handled).doesNotContain(fill.globalOrder());

        subscription.start();
        await().atMost(Duration.ofSeconds(20)).untilAsserted(() -> assertThat(handled).contains(fill.globalOrder()));
        await().atMost(Duration.ofSeconds(10)).until(() -> transientGapsOf(subscriberId).isEmpty());
        assertThat(handled).doesNotHaveDuplicates();
    }

    /**
     * (b) on the bus: the fill's handling waits for an I/O retry when the process dies
     */
    @Test
    void a_gap_fill_from_the_bus_whose_handling_waits_for_an_io_retry_when_the_process_dies_is_delivered_after_the_restart() throws Exception {
        var subscriberId    = SubscriberId.of("cdc-io-retry-fill-crash");
        var manager         = startManager(10);
        var handled         = new CopyOnWriteArrayList<Long>();
        var fillOrder       = new AtomicLong(Long.MAX_VALUE);
        var failingAttempts = new AtomicInteger();
        var failTheFill     = new AtomicBoolean(true);
        var subscription = manager.subscribeToAggregateEventsAsynchronously(subscriberId,
                                                                            ORDERS,
                                                                            GlobalEventOrder.FIRST_GLOBAL_EVENT_ORDER,
                                                                            Optional.empty(),
                                                                            (PersistedEventHandler) event -> {
                                                                                if (event.globalEventOrder().longValue() == fillOrder.get() && failTheFill.get()) {
                                                                                    failingAttempts.incrementAndGet();
                                                                                    throw new UncheckedIOException(new IOException("Intentional I/O failure handling the gap fill"));
                                                                                }
                                                                                handled.add(event.globalEventOrder().longValue());
                                                                            });
        var fill = handOnAGapFillFromTheBus(subscriberId, handled, 1, fillOrder::set, () -> failingAttempts.get() >= 2);

        simulateCrash(subscription, subscriberId, fill.resumePointPersisted());
        assertThat(handled).doesNotContain(fill.globalOrder());

        failTheFill.set(false);
        subscription.start();
        await().atMost(Duration.ofSeconds(20)).untilAsserted(() -> assertThat(handled).contains(fill.globalOrder()));
        await().atMost(Duration.ofSeconds(10)).until(() -> transientGapsOf(subscriberId).isEmpty());
        assertThat(handled).doesNotHaveDuplicates();
    }

    /**
     * (c) on the bus: the fill waits in the queue in front of a subscriber that withholds demand, and is dropped with it
     * by a plain stop
     */
    @Test
    void a_gap_fill_from_the_bus_waiting_for_demand_when_the_subscription_stops_is_delivered_after_the_restart() throws Exception {
        var subscriberId   = SubscriberId.of("cdc-withheld-demand-fill-stop");
        var manager        = startManager(DEMAND_WINDOW);
        var handled        = new CopyOnWriteArrayList<Long>();
        var withholdDemand = new AtomicBoolean(true);
        var subscription = manager.subscribeToAggregateEventsAsynchronously(subscriberId,
                                                                            ORDERS,
                                                                            GlobalEventOrder.FIRST_GLOBAL_EVENT_ORDER,
                                                                            Optional.empty(),
                                                                            withholdingDemand(withholdDemand, handled));
        // The first event and the two committed after the fill's global order use up the demand window
        var fill = handOnAGapFillFromTheBus(subscriberId, handled, 2, order -> {}, CdcEventStoreGapFillAcknowledgementIT::afterASecond);

        subscription.stop();
        assertThat(handled).doesNotContain(fill.globalOrder());

        withholdDemand.set(false);
        subscription.start();
        await().atMost(Duration.ofSeconds(20)).untilAsserted(() -> assertThat(handled).contains(fill.globalOrder()));
        await().atMost(Duration.ofSeconds(10)).until(() -> transientGapsOf(subscriberId).isEmpty());
        assertThat(handled).doesNotHaveDuplicates();
    }

    /**
     * (c) for a subscription started while CDC is ACTIVE: the fill comes out of {@code BackfillThenLiveOrdered}'s ordered
     * output into the queue in front of a subscriber that withholds demand
     */
    @Test
    void an_ordered_subscription_s_gap_fill_waiting_for_demand_when_the_subscription_stops_is_delivered_after_the_restart() throws Exception {
        var subscriberId   = SubscriberId.of("cdc-ordered-withheld-demand-fill-stop");
        var manager        = startManager(DEMAND_WINDOW);
        availability.active("ack-slot");
        var handled        = new CopyOnWriteArrayList<Long>();
        var withholdDemand = new AtomicBoolean(true);
        var subscription = manager.subscribeToAggregateEventsAsynchronously(subscriberId,
                                                                            ORDERS,
                                                                            GlobalEventOrder.FIRST_GLOBAL_EVENT_ORDER,
                                                                            Optional.empty(),
                                                                            withholdingDemand(withholdDemand, handled));
        publishUntilHandled(handled, appendAndCommit());

        // The first event and the two committed after the fill's global order use up the demand window
        var a = holdAppend();
        var b = appendAndCommit();
        var c = appendAndCommit();
        publish(b);
        publish(c);
        await().atMost(Duration.ofSeconds(10)).until(() -> handled.containsAll(List.of(b, c)));
        await().atMost(Duration.ofSeconds(10)).until(() -> persistedResumePoint(subscriberId) == c + 1);
        a.commit();
        publish(a.globalOrder());
        // Handed into the queue in front of the subscriber, which asks for nothing
        await().pollDelay(Duration.ofSeconds(1)).atMost(Duration.ofSeconds(5)).until(() -> true);

        subscription.stop();
        assertThat(handled).doesNotContain(a.globalOrder());

        withholdDemand.set(false);
        subscription.start();
        await().atMost(Duration.ofSeconds(20)).untilAsserted(() -> assertThat(handled).contains(a.globalOrder()));
        await().atMost(Duration.ofSeconds(10)).until(() -> transientGapsOf(subscriberId).isEmpty());
        assertThat(handled).doesNotHaveDuplicates();
    }

    /**
     * (c) on the polling leg (CDC INACTIVE throughout): the delegate's poll hands the fill through the CDC pipeline into
     * the queue in front of a subscriber that withholds demand
     */
    @Test
    void a_polled_gap_fill_waiting_for_demand_when_the_subscription_stops_is_delivered_after_the_restart() throws Exception {
        var subscriberId   = SubscriberId.of("cdc-polled-withheld-demand-fill-stop");
        var manager        = startManager(DEMAND_WINDOW);
        var handled        = new CopyOnWriteArrayList<Long>();
        var withholdDemand = new AtomicBoolean(true);
        var subscription = manager.subscribeToAggregateEventsAsynchronously(subscriberId,
                                                                            ORDERS,
                                                                            GlobalEventOrder.FIRST_GLOBAL_EVENT_ORDER,
                                                                            Optional.empty(),
                                                                            withholdingDemand(withholdDemand, handled));
        appendAndCommit();
        await().atMost(Duration.ofSeconds(10)).until(() -> handled.size() == 1);

        // The first event and the two committed after the fill's global order use up the demand window
        var a = holdAppend();
        var b = appendAndCommit();
        var c = appendAndCommit();
        await().atMost(Duration.ofSeconds(10)).until(() -> handled.containsAll(List.of(b, c)));
        await().atMost(Duration.ofSeconds(10)).until(() -> transientGapsOf(subscriberId).equals(List.of(GlobalEventOrder.of(a.globalOrder()))));
        await().atMost(Duration.ofSeconds(10)).until(() -> persistedResumePoint(subscriberId) == c + 1);
        a.commit();
        // Polled, and handed into the queue in front of the subscriber, which asks for nothing
        await().pollDelay(Duration.ofSeconds(1)).atMost(Duration.ofSeconds(5)).until(() -> true);

        subscription.stop();
        assertThat(handled).doesNotContain(a.globalOrder());

        withholdDemand.set(false);
        subscription.start();
        await().atMost(Duration.ofSeconds(20)).untilAsserted(() -> assertThat(handled).contains(a.globalOrder()));
        await().atMost(Duration.ofSeconds(10)).until(() -> transientGapsOf(subscriberId).isEmpty());
        assertThat(handled).doesNotHaveDuplicates();
        assertThat(availability.isActive()).isFalse();
    }

    /**
     * The delivery tracker keeps a gap fill that was handed on and not acknowledged yet from being delivered twice in the
     * same subscription - although its gap stays open, so the polling leg and the catch-up onto the bus both read it
     * again - and its gap is resolved once it is acknowledged in a unit of work that commits
     */
    @Test
    void an_unacknowledged_gap_fill_is_delivered_once_by_its_subscription_however_often_it_is_read_again_and_is_resolved_when_acknowledged() throws Exception {
        var subscriberId    = SubscriberId.of("cdc-unacknowledged-fill-read-again");
        var acknowledgement = SubscriberAcknowledgement.create();
        var received        = new CopyOnWriteArrayList<PersistedEvent>();
        subscriptions.add(cdcEventStore.pollEvents(ORDERS, 1, Optional.of(10), Optional.of(POLLING_INTERVAL), Optional.empty(), Optional.of(subscriberId), Optional.empty(), acknowledgement)
                                       .subscribe(received::add));
        var first = appendAndCommit();
        publish(first);
        await().atMost(Duration.ofSeconds(10)).until(() -> received.size() == 1);
        assertThat(acknowledgement.isHonoured()).isTrue();
        availability.active("ack-slot");
        awaitOnTheBus();

        var a = holdAppend();
        var b = appendAndCommit();
        publish(b);
        await().atMost(Duration.ofSeconds(10)).until(() -> globalOrdersOf(received).contains(b));
        await().atMost(Duration.ofSeconds(10)).until(() -> transientGapsOf(subscriberId).equals(List.of(GlobalEventOrder.of(a.globalOrder()))));
        a.commit();
        publish(a.globalOrder());
        await().atMost(Duration.ofSeconds(10)).until(() -> globalOrdersOf(received).contains(a.globalOrder()));
        var gapFill = received.stream().filter(event -> event.globalEventOrder().longValue() == a.globalOrder()).findFirst().orElseThrow();
        // Everything but the fill is acknowledged
        received.stream().filter(event -> event != gapFill).forEach(acknowledgement::acknowledge);

        // Off the bus onto polling, whose gap query reads the fill again, and back - the catch-up reads it again too
        availability.inactive("ack-slot", "test");
        await().pollDelay(POLLING_INTERVAL.multipliedBy(10)).atMost(Duration.ofSeconds(5)).until(() -> true);
        availability.active("ack-slot");
        awaitOnTheBus();
        var c = appendAndCommit();
        publish(c);
        await().atMost(Duration.ofSeconds(15)).until(() -> globalOrdersOf(received).contains(c));
        await().pollDelay(Duration.ofMillis(500)).atMost(Duration.ofSeconds(5)).until(() -> true);
        assertThat(globalOrdersOf(received)).containsExactlyInAnyOrder(first, b, a.globalOrder(), c);
        assertThat(transientGapsOf(subscriberId)).containsExactly(GlobalEventOrder.of(a.globalOrder()));

        unitOfWorkFactory.usingUnitOfWork(unitOfWork -> acknowledgement.acknowledge(gapFill));
        assertThat(transientGapsOf(subscriberId)).isEmpty();
    }

    /**
     * On the polling leg (CDC INACTIVE throughout), the delivery tracker gives a gap up once it is older than the gap
     * handler's threshold, and records that with the gap handler: the gap is promoted, so a restarted subscription does
     * not wait for it either. The event for it that commits late is then dropped by both - the running subscription
     * drops the delegate poll's copy (and acknowledges it to the poll, which handed it on as a gap fill), and the
     * restarted one no longer asks for it. Before, the gap stayed transient, and only a restart delivered the event.
     * <p>
     * The promotion strategy states a threshold of 1 second - what the tracker waits - but never promotes on its own, so
     * the gap is promoted by the give-up and by nothing else: no poll can promote it first, and the test does not
     * depend on which of them gets there first.
     */
    @Test
    void a_gap_given_up_on_the_polling_leg_is_promoted_and_its_late_event_is_delivered_neither_by_the_subscription_nor_after_a_restart() throws Exception {
        setup(new PostgresqlEventStreamGapHandler<>(unitOfWorkFactory,
                                                    Duration.ofSeconds(60),
                                                    PostgresqlEventStreamGapHandler.ResolveTransientGapsToIncludeInQueryStrategy.defaultSelection(),
                                                    new PostgresqlEventStreamGapHandler.ResolveTransientGapsToPermanentGapsPromotionStrategy() {
                                                        @Override
                                                        public Optional<Duration> permanentGapThreshold() {
                                                            return Optional.of(Duration.ofSeconds(1));
                                                        }

                                                        @Override
                                                        public List<GlobalEventOrder> resolveTransientGapsReadyToBePromotedToPermanentGaps(AggregateType forAggregateType,
                                                                                                                                         List<Pair<GlobalEventOrder, OffsetDateTime>> allTransientGaps) {
                                                            return List.of();
                                                        }
                                                    }));
        var subscriberId = SubscriberId.of("cdc-polled-given-up-gap");
        var manager      = startManager(10);
        var handled      = new CopyOnWriteArrayList<Long>();
        var subscription = manager.subscribeToAggregateEventsAsynchronously(subscriberId,
                                                                            ORDERS,
                                                                            GlobalEventOrder.FIRST_GLOBAL_EVENT_ORDER,
                                                                            Optional.empty(),
                                                                            (PersistedEventHandler) event -> handled.add(event.globalEventOrder().longValue()));
        appendAndCommit();
        await().atMost(Duration.ofSeconds(10)).until(() -> handled.size() == 1);

        var a = holdAppend();
        var b = appendAndCommit();
        await().atMost(Duration.ofSeconds(10)).until(() -> handled.contains(b));
        await().atMost(Duration.ofSeconds(10)).until(() -> transientGapsOf(subscriberId).equals(List.of(GlobalEventOrder.of(a.globalOrder()))));

        // Older than the tracker waits for: the next event it delivers gives the gap up
        await().pollDelay(Duration.ofMillis(1_500)).atMost(Duration.ofSeconds(5)).until(() -> true);
        var c = appendAndCommit();
        await().atMost(Duration.ofSeconds(10)).until(() -> handled.contains(c));
        await().atMost(Duration.ofSeconds(10)).until(() -> transientGapsOf(subscriberId).isEmpty());
        assertThat(permanentGaps()).contains(GlobalEventOrder.of(a.globalOrder()));

        // The delegate's poll still asks for the gap - its gap handler has it cached - and loads the event now. The gate
        // drops it and acknowledges it to the poll, which then stops asking for it: the gate is done with the event
        a.commit();
        await().atMost(Duration.ofSeconds(10)).until(() -> polls.stoppedAskingAfterReading(a.globalOrder()));
        assertThat(handled).doesNotContain(a.globalOrder());

        var pollsBeforeTheRestart = polls.count();
        subscription.stop();
        subscription.start();
        var d = appendAndCommit();
        await().atMost(Duration.ofSeconds(20)).until(() -> handled.contains(d));
        // The restarted subscription polls above the gap without asking for it - so no poll of it can read the event
        await().atMost(Duration.ofSeconds(10)).until(() -> polls.since(pollsBeforeTheRestart)
                                                                .stream()
                                                                .anyMatch(poll -> poll.fromInclusive() > a.globalOrder() && poll.transientGapsAskedFor().isEmpty()));
        assertThat(polls.since(pollsBeforeTheRestart)).noneMatch(poll -> poll.transientGapsAskedFor().contains(GlobalEventOrder.of(a.globalOrder()))
                                                                         || poll.read().contains(a.globalOrder()));
        assertThat(handled).doesNotContain(a.globalOrder())
                           .doesNotHaveDuplicates();
        assertThat(transientGapsOf(subscriberId)).isEmpty();
        assertThat(availability.isActive()).isFalse();
    }

    // ------------------------------------------------------------------------------------------------------------

    private record Poll(long fromInclusive, List<GlobalEventOrder> transientGapsAskedFor, List<Long> read) {
    }

    /**
     * Records what each poll of the event store (the CDC event store's delegate) asked for and read
     */
    private static final class RecordingPolls extends EventStoreSubscriptionObserver.NoOpEventStoreSubscriptionObserver {
        private final List<Poll> polls = new CopyOnWriteArrayList<>();

        @Override
        public void eventStorePolled(SubscriberId subscriberId,
                                     AggregateType aggregateType,
                                     LongRange globalOrderRange,
                                     List<GlobalEventOrder> transientGapsToInclude,
                                     Optional<Tenant> onlyIncludeEventIfItBelongsToTenant,
                                     List<PersistedEvent> persistedEventsReturnedFromPoll,
                                     Duration pollDuration) {
            polls.add(new Poll(globalOrderRange.getFromInclusive(),
                               transientGapsToInclude == null ? List.of() : List.copyOf(transientGapsToInclude),
                               globalOrdersOf(persistedEventsReturnedFromPoll)));
        }

        int count() {
            return polls.size();
        }

        /**
         * @return the polls after the first {@code count} recorded
         */
        List<Poll> since(int count) {
            var snapshot = List.copyOf(polls);
            return snapshot.subList(count, snapshot.size());
        }

        /**
         * @return true once a poll read the event with {@code globalOrder}, as the gap it asked for, and a later poll no
         * longer asked for it - the poll's gap was resolved, which it is once the event is acknowledged to the poll
         */
        boolean stoppedAskingAfterReading(long globalOrder) {
            var snapshot = List.copyOf(polls);
            var gap      = GlobalEventOrder.of(globalOrder);
            for (var read = 0; read < snapshot.size(); read++) {
                if (snapshot.get(read).transientGapsAskedFor().contains(gap) && snapshot.get(read).read().contains(globalOrder)) {
                    return snapshot.subList(read + 1, snapshot.size())
                                   .stream()
                                   .anyMatch(poll -> !poll.transientGapsAskedFor().contains(gap));
                }
            }
            return false;
        }
    }

    private List<GlobalEventOrder> permanentGaps() {
        return unitOfWorkFactory.withUnitOfWork(() -> eventStore.getEventStreamGapHandler().getPermanentGapsFor(ORDERS).toList());
    }

    private static List<Long> globalOrdersOf(List<PersistedEvent> events) {
        return events.stream().map(event -> event.globalEventOrder().longValue()).toList();
    }

    private record HandedOnGapFill(long globalOrder, long resumePointPersisted) {
    }

    private HandedOnGapFill handOnAGapFillFromTheBus(SubscriberId subscriberId, List<Long> handled) throws Exception {
        // Handed into the batch the subscriber collects
        return handOnAGapFillFromTheBus(subscriberId, handled, 1, order -> {}, CdcEventStoreGapFillAcknowledgementIT::afterASecond);
    }

    /**
     * Time for the fill to travel from the bus into the subscriber (or the queue in front of it), which cannot be observed
     */
    private static boolean afterASecond() {
        await().pollDelay(Duration.ofSeconds(1)).atMost(Duration.ofSeconds(5)).until(() -> true);
        return true;
    }

    /**
     * The subscription is warmed up on polling and moved onto the bus; A takes a global order and commits after the
     * {@code higherEvents} committed after it, so the subscriber handles those, its resume point is persisted past A, and
     * A then arrives from the bus as a gap fill
     *
     * @param fillOrder told A's global order before anything is published
     * @param handedOn  true once the fill has reached where the test wants it
     */
    private HandedOnGapFill handOnAGapFillFromTheBus(SubscriberId subscriberId,
                                                     List<Long> handled,
                                                     int higherEvents,
                                                     LongConsumer fillOrder,
                                                     Callable<Boolean> handedOn) throws Exception {
        publish(appendAndCommit());
        await().atMost(Duration.ofSeconds(10)).until(() -> handled.size() == 1);
        availability.active("ack-slot");
        awaitOnTheBus();

        var a = holdAppend();
        fillOrder.accept(a.globalOrder());
        var higher = new ArrayList<Long>();
        for (var i = 0; i < higherEvents; i++) {
            higher.add(appendAndCommit());
        }
        higher.forEach(this::publish);
        await().atMost(Duration.ofSeconds(10)).until(() -> handled.containsAll(higher));
        await().atMost(Duration.ofSeconds(10)).until(() -> transientGapsOf(subscriberId).equals(List.of(GlobalEventOrder.of(a.globalOrder()))));
        var resumePoint = higher.getLast() + 1;
        await().atMost(Duration.ofSeconds(10)).until(() -> persistedResumePoint(subscriberId) == resumePoint);

        a.commit();
        publish(a.globalOrder());
        await().atMost(Duration.ofSeconds(10)).until(handedOn);
        return new HandedOnGapFill(a.globalOrder(), resumePoint);
    }

    /**
     * A process that dies leaves the database as it was: the resume point its periodic checkpoint persisted last, and the
     * gap rows as they were. The subscription is stopped, and whatever its stop path wrote is replaced by the resume point
     * persisted before it, as if it had never run.
     */
    private void simulateCrash(EventStoreSubscription subscription, SubscriberId subscriberId, long resumePointPersistedBeforeTheCrash) {
        assertThat(persistedResumePoint(subscriberId)).isEqualTo(resumePointPersistedBeforeTheCrash);
        subscription.stop();
        var resumePoint = durableSubscriptionRepository.getResumePoint(subscriberId, ORDERS).orElseThrow();
        resumePoint.setResumeFromAndIncluding(GlobalEventOrder.of(resumePointPersistedBeforeTheCrash));
        durableSubscriptionRepository.saveResumePoint(resumePoint);
        assertThat(persistedResumePoint(subscriberId)).isEqualTo(resumePointPersistedBeforeTheCrash);
    }

    /**
     * Handles every event, asking for no further event while {@code withholdDemand} - so once the events the subscriber
     * asked for up front ({@link #DEMAND_WINDOW}) are handled, the next one waits in the queue in front of it
     */
    private static PersistedEventHandler withholdingDemand(AtomicBoolean withholdDemand, List<Long> handled) {
        return new PersistedEventHandler() {
            @Override
            public void handle(PersistedEvent event) {
                handled.add(event.globalEventOrder().longValue());
            }

            @Override
            public int handleWithBackPressure(PersistedEvent event) {
                handle(event);
                return withholdDemand.get() ? 0 : 1;
            }
        };
    }

    private List<GlobalEventOrder> transientGapsOf(SubscriberId subscriberId) {
        return eventStore.getEventStreamGapHandler().gapHandlerFor(subscriberId).getTransientGapsFor(ORDERS);
    }

    private EventStoreSubscriptionManager startManager(int eventStorePollingBatchSize) {
        var manager = EventStoreSubscriptionManager.builder()
                                                   .setEventStore(cdcEventStore)
                                                   .setEventStorePollingBatchSize(eventStorePollingBatchSize)
                                                   .setEventStorePollingInterval(POLLING_INTERVAL)
                                                   .setFencedLockManager(PostgresqlFencedLockManager.builder()
                                                                                                    .setJdbi(jdbi)
                                                                                                    .setUnitOfWorkFactory(unitOfWorkFactory)
                                                                                                    .setLockManagerInstanceId("node-1")
                                                                                                    .setLockTimeOut(Duration.ofSeconds(3))
                                                                                                    .setLockConfirmationInterval(Duration.ofMillis(500))
                                                                                                    .build())
                                                   .setSnapshotResumePointsEvery(Duration.ofMillis(100))
                                                   .setDurableSubscriptionRepository(durableSubscriptionRepository)
                                                   .build();
        manager.start();
        managers.add(manager);
        return manager;
    }

    private long persistedResumePoint(SubscriberId subscriberId) {
        return durableSubscriptionRepository.getResumePoint(subscriberId, ORDERS)
                                            .map(resumePoint -> resumePoint.getResumeFromAndIncluding().longValue())
                                            .orElse(0L);
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
    private void publishUntilHandled(List<Long> handled, long globalOrder) {
        var event = load(globalOrder);
        await().atMost(Duration.ofSeconds(10)).untilAsserted(() -> {
            cdcBus.publish(List.of(event));
            assertThat(handled).contains(globalOrder);
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
        var mayEnd      = new CountDownLatch(1);
        var ended = heldTransactions.submit(() -> unitOfWorkFactory.usingUnitOfWork(unitOfWork -> {
            globalOrder.complete(appendTo(unitOfWork));
            if (!mayEnd.await(30, TimeUnit.SECONDS)) {
                throw new IllegalStateException("Held transaction was never released");
            }
        }));
        return new HeldAppend(globalOrder.get(10, TimeUnit.SECONDS), mayEnd, ended);
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

    private record HeldAppend(long globalOrder, CountDownLatch mayEnd, Future<?> ended) {
        void commit() throws Exception {
            mayEnd.countDown();
            ended.get(10, TimeUnit.SECONDS);
        }
    }
}
