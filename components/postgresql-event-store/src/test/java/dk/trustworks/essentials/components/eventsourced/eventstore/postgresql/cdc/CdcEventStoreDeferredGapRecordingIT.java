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
import org.junit.jupiter.api.*;

import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;

import static dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.gap.PostgresqlEventStreamGapHandler.TRANSIENT_SUBSCRIBER_GAPS_TABLE_NAME;
import static org.assertj.core.api.Assertions.*;
import static org.awaitility.Awaitility.await;

/**
 * A gap a CDC subscription opens - an event whose global order is above one not delivered yet, as concurrent appenders
 * committing out of global order produce all the time - is kept in memory by the subscription's delivery gate, and only
 * recorded as a transient gap before a resume point save of the subscriber (see {@code DeferredGapRecording}). A gap that
 * fills before the next save costs no database write; one still open when a resume point past it is saved is recorded
 * first, so a restart from that resume point still waits for its event.
 * <p>
 * As in {@link CdcEventStoreCommitOrderIT}, the test is the dispatcher, publishing each event to the bus after it
 * committed; a held transaction makes the lower global order commit last. Inserts into the transient gap table are
 * counted by a trigger.
 */
class CdcEventStoreDeferredGapRecordingIT extends AbstractLogicalReplicationPostgresIT {
    private static final int      PAGE_SIZE        = 10;
    private static final Duration POLLING_INTERVAL = Duration.ofMillis(100);
    private static final Duration DEBOUNCE         = Duration.ofMillis(300);

    private PostgresqlEventStore<SeparateTablePerAggregateEventStreamConfiguration> eventStore;
    private CdcEventStore<SeparateTablePerAggregateEventStreamConfiguration>        cdcEventStore;
    private CdcEventBus                                                             cdcBus;
    private CdcAvailability                                                         availability;
    private PostgresqlDurableSubscriptionRepository                                 durableSubscriptionRepository;
    private final List<EventStoreSubscriptionManager>                               managers = new CopyOnWriteArrayList<>();
    private ExecutorService                                                         heldTransactions;
    /**
     * Every transient gap a query of the gap handler included - see {@link #setup()}
     */
    private final Set<Long>                                                         includedInGapHandlerQueries = ConcurrentHashMap.newKeySet();
    /**
     * Another subscriber of the aggregate type on the bus, as in any real deployment: the bus retains nothing for a
     * subscription that left it while others remain
     */
    private reactor.core.Disposable                                                 bystander;

    @BeforeEach
    void setup() {
        var persistenceStrategy = new SeparateTablePerAggregateTypePersistenceStrategy(
                jdbi,
                unitOfWorkFactory,
                new EventProcessorIT.TestPersistableEventMapper(),
                SeparateTablePerAggregateTypeEventStreamConfigurationFactory.defaultConfiguration(EssentialsJSONEventSerializers.create())
        );
        persistenceStrategy.addAggregateEventStreamConfiguration(ORDERS, OrderId.class);
        // The default selection and promotion, recording every gap a gap handler query includes
        var defaultSelection = PostgresqlEventStreamGapHandler.ResolveTransientGapsToIncludeInQueryStrategy.defaultSelection();
        includedInGapHandlerQueries.clear();
        var gapHandler = new PostgresqlEventStreamGapHandler<SeparateTablePerAggregateEventStreamConfiguration>(
                unitOfWorkFactory,
                Duration.ofSeconds(60),
                (aggregateType, range, allTransientGaps) -> {
                    var included = defaultSelection.resolveTransientGaps(aggregateType, range, allTransientGaps);
                    if (included != null) included.forEach(gap -> includedInGapHandlerQueries.add(gap.longValue()));
                    return included;
                },
                PostgresqlEventStreamGapHandler.ResolveTransientGapsToPermanentGapsPromotionStrategy.thresholdBased(120));
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
                                            Optional.empty());
        durableSubscriptionRepository = new PostgresqlDurableSubscriptionRepository(jdbi, cdcEventStore);
        heldTransactions = Executors.newCachedThreadPool(runnable -> new Thread(runnable, "test-held-transaction"));
        bystander = cdcBus.fluxForAggregate(ORDERS).subscribe();

        jdbi.useHandle(handle -> {
            handle.execute("CREATE TABLE IF NOT EXISTS transient_gap_inserts (gap_global_event_order bigint)");
            handle.execute("TRUNCATE transient_gap_inserts");
            handle.execute("CREATE OR REPLACE FUNCTION count_transient_gap_insert() RETURNS trigger AS $$ BEGIN " +
                                   "INSERT INTO transient_gap_inserts VALUES (NEW.gap_global_event_order); RETURN NEW; END $$ LANGUAGE plpgsql");
            handle.execute("CREATE OR REPLACE TRIGGER count_transient_gap_inserts AFTER INSERT ON " + TRANSIENT_SUBSCRIBER_GAPS_TABLE_NAME +
                                   " FOR EACH ROW EXECUTE FUNCTION count_transient_gap_insert()");
        });
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

    @Test
    void gaps_opened_and_filled_between_resume_point_saves_write_no_transient_gap() throws Exception {
        var subscriberId = SubscriberId.of("deferred-and-filled");
        // No periodic save while the gaps open and fill
        var manager  = startManager("node-1", Duration.ofHours(1));
        var received = subscribe(manager, subscriberId);
        publish(appendAndCommit());
        await().atMost(Duration.ofSeconds(10)).until(() -> received.size() == 1);
        availability.active("deferred-gaps-slot");
        awaitOnTheBus();

        var expected = new ArrayList<Long>(received);
        for (var i = 0; i < 5; i++) {
            var a = holdAppend();
            var b = appendAndCommit();
            publishUntilReceived(received, b);
            a.commit();
            publish(a.globalOrder());
            await().atMost(Duration.ofSeconds(10)).until(() -> received.contains(a.globalOrder()));
            expected.add(b);
            expected.add(a.globalOrder());
        }

        await().pollDelay(Duration.ofMillis(300)).atMost(Duration.ofSeconds(5)).until(() -> true);
        assertThat(received).containsExactlyElementsOf(expected);
        assertThat(transientGapInserts()).as("transient gaps written").isEmpty();
        assertThat(transientGapsOf(subscriberId)).isEmpty();

        // A save with nothing kept writes nothing either
        manager.stop();
        assertThat(transientGapInserts()).as("transient gaps written").isEmpty();
    }

    @Test
    void a_gap_still_open_when_a_resume_point_past_it_is_saved_is_recorded_first_so_a_restart_after_a_crash_still_receives_its_event() throws Exception {
        var subscriberId = SubscriberId.of("deferred-then-saved");
        var crashed      = startManager("node-1", Duration.ofMillis(200));
        var received     = subscribe(crashed, subscriberId);
        publish(appendAndCommit());
        await().atMost(Duration.ofSeconds(10)).until(() -> received.size() == 1);
        availability.active("deferred-gaps-slot");
        awaitOnTheBus();

        var a = holdAppend();
        var b = appendAndCommit();
        publishUntilReceived(received, b);
        // The periodic save moves the resume point past the open gap - recording the gap first
        await().atMost(Duration.ofSeconds(10)).until(() -> persistedResumePoint(subscriberId) > b);
        assertThat(transientGapsOf(subscriberId)).containsExactly(GlobalEventOrder.of(a.globalOrder()));

        // node-1 "crashes": it is never stopped, so nothing it would record on stop is recorded - node-2 starts from the
        // saved resume point, past the gap
        var restarted = subscribe(startManager("node-2", Duration.ofMillis(200)), subscriberId);
        var c         = appendAndCommit();
        publishUntilReceived(restarted, c);
        a.commit();
        publish(a.globalOrder());

        await().atMost(Duration.ofSeconds(15)).untilAsserted(() -> assertThat(restarted).contains(a.globalOrder(), c));
        assertThat(restarted).doesNotHaveDuplicates().doesNotContain(1L, b);
        // Its fill resolves the gap once handled
        await().atMost(Duration.ofSeconds(10)).until(() -> transientGapsOf(subscriberId).isEmpty());
    }

    @Test
    void a_gap_still_open_when_the_subscription_stops_is_recorded_and_its_event_delivered_after_the_restart() throws Exception {
        var subscriberId = SubscriberId.of("deferred-then-stopped");
        var manager      = startManager("node-1", Duration.ofHours(1));
        var received     = subscribe(manager, subscriberId);
        publish(appendAndCommit());
        await().atMost(Duration.ofSeconds(10)).until(() -> received.size() == 1);
        availability.active("deferred-gaps-slot");
        awaitOnTheBus();

        var a = holdAppend();
        var b = appendAndCommit();
        publishUntilReceived(received, b);
        assertThat(transientGapInserts()).as("kept in memory while no save ran").isEmpty();
        // The stop saves the resume point past the gap
        manager.stop();
        assertThat(persistedResumePoint(subscriberId)).isGreaterThan(b);
        assertThat(transientGapsOf(subscriberId)).containsExactly(GlobalEventOrder.of(a.globalOrder()));

        var restarted = subscribe(startManager("node-2", Duration.ofHours(1)), subscriberId);
        var c         = appendAndCommit();
        publishUntilReceived(restarted, c);
        a.commit();
        publish(a.globalOrder());
        await().atMost(Duration.ofSeconds(15)).untilAsserted(() -> assertThat(restarted).contains(a.globalOrder(), c));
        assertThat(restarted).doesNotHaveDuplicates().doesNotContain(1L, b);
    }

    @Test
    void a_hole_no_event_fills_is_awaited_while_kept_in_memory_without_a_write() throws Exception {
        var subscriberId = SubscriberId.of("deferred-and-rolled-back");
        var manager      = startManager("node-1", Duration.ofHours(1));
        var received     = subscribe(manager, subscriberId);
        publish(appendAndCommit());
        await().atMost(Duration.ofSeconds(10)).until(() -> received.size() == 1);
        availability.active("deferred-gaps-slot");
        awaitOnTheBus();

        // A rolled-back append leaves a hole no event ever fills; kept in memory, nothing recorded
        var a = holdAppend();
        var b = appendAndCommit();
        publishUntilReceived(received, b);
        a.rollBack();
        assertThat(transientGapInserts()).isEmpty();
        assertThat(cdcEventStore.isAwaitedAsGap(subscriberId, ORDERS, GlobalEventOrder.of(a.globalOrder()))).as("awaited while kept in memory").isTrue();
    }

    /**
     * A subscription that falls behind the bus catches up from the event store: what it has not been handed past its
     * highest delivered order, plus - by order - the gaps below it whose fill may have committed while it was off the bus.
     * The tracker names at most {@code MAX_AWAITED_GAPS_TO_REQUERY} (1 000) of them, lowest first; a newer gap used to be
     * covered by its transient gap row, which the catch-up's gap handler query includes newest first. A gap kept in memory
     * has no row: with more than 1 000 older orders awaited (here: rolled-back appends), its fill - published while the
     * subscription was off the bus - was not loaded, and reached the subscriber only once older gaps were given up, after
     * later events of the same aggregate (trading demo: views applying OrderAccepted before OrderPlaced).
     */
    @Test
    void a_gap_kept_in_memory_whose_fill_arrives_while_the_subscription_is_off_the_bus_is_filled_by_the_catch_up() throws Exception {
        var subscriberId = SubscriberId.of("deferred-and-caught-up");
        var manager      = startManager("node-1", Duration.ofHours(1));
        var mayHandle    = new AtomicReference<>(new CountDownLatch(0));
        var received     = new CopyOnWriteArrayList<Long>();
        manager.subscribeToAggregateEventsAsynchronously(subscriberId,
                                                         ORDERS,
                                                         GlobalEventOrder.FIRST_GLOBAL_EVENT_ORDER,
                                                         Optional.empty(),
                                                         (PersistedEventHandler) event -> {
                                                             try {
                                                                 mayHandle.get().await(30, TimeUnit.SECONDS);
                                                             } catch (InterruptedException e) {
                                                                 Thread.currentThread().interrupt();
                                                             }
                                                             received.add(event.globalEventOrder().longValue());
                                                         });
        publish(appendAndCommit());
        await().atMost(Duration.ofSeconds(10)).until(() -> received.size() == 1);
        availability.active("deferred-gaps-slot");
        awaitOnTheBus();

        // More than 1 000 orders awaited below the gap that matters: a rolled-back batch of appends
        rollBackAppends(1_100);
        publishUntilReceived(received, appendAndCommit());

        // The gap kept in memory
        var a = holdAppend();
        publishUntilReceived(received, appendAndCommit());
        assertThat(transientGapsOf(subscriberId)).doesNotContain(GlobalEventOrder.of(a.globalOrder()));

        // The subscriber stalls; the bus overflows its hand-over and the subscription leaves the bus
        mayHandle.set(new CountDownLatch(1));
        var later = new ArrayList<Long>();
        for (var i = 0; i < 60; i++) {
            var order = appendAndCommit();
            later.add(order);
            publish(order);
        }
        // ... and the fill is published while it is off the bus
        a.commit();
        publish(a.globalOrder());
        mayHandle.get().countDown();

        await().atMost(Duration.ofSeconds(20)).untilAsserted(() -> assertThat(received).containsAll(later));
        await().atMost(Duration.ofSeconds(10)).untilAsserted(() -> assertThat(received).contains(a.globalOrder()));
    }

    /**
     * Every gap the subscription awaits is re-read by a catch-up, not only those some selection happens to name: a gap
     * recorded as a transient gap that is neither among the tracker's lowest 1 000 awaited orders nor among the gap
     * handler query's newest 20 / lowest 10 rows - its fill published while the subscription is off the bus - used to be
     * found only by chance (the 20 rotating rows), else given up after the gap timeout: a permanent gap over an event
     * that exists.
     * <p>
     * Deterministic against the old catch-up: 3 000 recorded gaps lie below the gap, so the rotation, which advances 20
     * per gap handler query from the lowest, cannot reach it within this test's catch-up pages - and the test asserts
     * that no gap handler query included it.
     */
    @Test
    void a_recorded_gap_that_no_selection_names_is_still_re_read_by_a_catch_up() throws Exception {
        var subscriberId = SubscriberId.of("recorded-and-caught-up");
        // Saves often, so every gap kept in memory is recorded as a transient gap
        var manager      = startManager("node-1", Duration.ofMillis(200));
        var mayHandle    = new AtomicReference<>(new CountDownLatch(0));
        var received     = new CopyOnWriteArrayList<Long>();
        manager.subscribeToAggregateEventsAsynchronously(subscriberId,
                                                         ORDERS,
                                                         GlobalEventOrder.FIRST_GLOBAL_EVENT_ORDER,
                                                         Optional.empty(),
                                                         (PersistedEventHandler) event -> {
                                                             try {
                                                                 mayHandle.get().await(30, TimeUnit.SECONDS);
                                                             } catch (InterruptedException e) {
                                                                 Thread.currentThread().interrupt();
                                                             }
                                                             received.add(event.globalEventOrder().longValue());
                                                         });
        publish(appendAndCommit());
        await().atMost(Duration.ofSeconds(10)).until(() -> received.size() == 1);
        availability.active("deferred-gaps-slot");
        awaitOnTheBus();

        // 3 000 lower awaited orders: the tracker's lowest 1 000 are all below the gap that matters, and the rotation of
        // the gap handler query would need 150 queries to reach it
        rollBackAppends(3_000);
        publishUntilReceived(received, appendAndCommit());
        // The gap that matters, recorded by a save
        var a = holdAppend();
        publishUntilReceived(received, appendAndCommit());
        await().atMost(Duration.ofSeconds(10)).until(() -> transientGapsOf(subscriberId).contains(GlobalEventOrder.of(a.globalOrder())));
        // 30 newer recorded gaps: it is not among the gap handler query's newest 20 either
        for (var i = 0; i < 30; i++) {
            rollBackAppends(1);
            publishUntilReceived(received, appendAndCommit());
        }
        await().atMost(Duration.ofSeconds(10)).until(() -> transientGapsOf(subscriberId).size() >= 3_000 + 1 + 30);
        var lowerAwaited = transientGapsOf(subscriberId).stream().filter(gap -> gap.longValue() < a.globalOrder()).count();
        var newerAwaited = transientGapsOf(subscriberId).stream().filter(gap -> gap.longValue() > a.globalOrder()).count();
        assertThat(lowerAwaited).as("awaited below the gap - more than the tracker's lowest 1 000").isGreaterThanOrEqualTo(3_000);
        assertThat(newerAwaited).as("recorded above the gap - more than the gap handler query's newest 20").isGreaterThanOrEqualTo(30);

        // The subscriber stalls; the bus overflows its hand-over and the subscription leaves the bus
        mayHandle.set(new CountDownLatch(1));
        var later = new ArrayList<Long>();
        for (var i = 0; i < 60; i++) {
            var order = appendAndCommit();
            later.add(order);
            publish(order);
        }
        // ... and the fill is published while it is off the bus
        a.commit();
        publish(a.globalOrder());
        mayHandle.get().countDown();

        await().atMost(Duration.ofSeconds(20)).untilAsserted(() -> assertThat(received).containsAll(later));
        // The catch-ups are done: no gap handler query named the gap - only a complete re-read finds its fill
        assertThat(includedInGapHandlerQueries).as("gaps the gap handler queries included").doesNotContain(a.globalOrder());
        await().atMost(Duration.ofSeconds(10)).untilAsserted(() -> assertThat(received).contains(a.globalOrder()));
    }

    // ------------------------------------------------------------------------------------------------------------

    private List<Long> transientGapInserts() {
        return jdbi.withHandle(handle -> handle.createQuery("SELECT gap_global_event_order FROM transient_gap_inserts").mapTo(Long.class).list());
    }

    private List<GlobalEventOrder> transientGapsOf(SubscriberId subscriberId) {
        return eventStore.getEventStreamGapHandler().gapHandlerFor(subscriberId).getTransientGapsFor(ORDERS);
    }

    private EventStoreSubscriptionManager startManager(String instanceId, Duration snapshotResumePointsEvery) {
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
                                                   .setSnapshotResumePointsEvery(snapshotResumePointsEvery)
                                                   .setDurableSubscriptionRepository(durableSubscriptionRepository)
                                                   .build();
        manager.start();
        managers.add(manager);
        return manager;
    }

    private static List<Long> subscribe(EventStoreSubscriptionManager manager, SubscriberId subscriberId) {
        var received = new CopyOnWriteArrayList<Long>();
        manager.subscribeToAggregateEventsAsynchronously(subscriberId,
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

    private static void awaitOnTheBus() {
        await().pollDelay(DEBOUNCE.multipliedBy(3)).atMost(Duration.ofSeconds(10)).until(() -> true);
    }

    private void publish(long globalOrder) {
        cdcBus.publish(List.of(load(globalOrder)));
    }

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

    private void rollBackAppends(int count) {
        assertThatThrownBy(() -> unitOfWorkFactory.usingUnitOfWork(unitOfWork -> {
            for (var i = 0; i < count; i++) {
                appendTo(unitOfWork);
            }
            throw new RolledBack();
        })).hasRootCauseInstanceOf(RolledBack.class);
    }

    private long appendAndCommit() {
        return unitOfWorkFactory.withUnitOfWork(this::appendTo);
    }

    private HeldAppend holdAppend() throws Exception {
        var globalOrder = new CompletableFuture<Long>();
        var mayEnd      = new CountDownLatch(1);
        var rollBack    = new AtomicBoolean();
        var ended = heldTransactions.submit(() -> unitOfWorkFactory.usingUnitOfWork(unitOfWork -> {
            globalOrder.complete(appendTo(unitOfWork));
            if (!mayEnd.await(30, TimeUnit.SECONDS)) {
                throw new IllegalStateException("Held transaction was never released");
            }
            if (rollBack.get()) {
                throw new RolledBack();
            }
        }));
        return new HeldAppend(globalOrder.get(10, TimeUnit.SECONDS), mayEnd, rollBack, ended);
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

    private record HeldAppend(long globalOrder, CountDownLatch mayEnd, AtomicBoolean rollBackRequested, Future<?> ended) {
        void commit() throws Exception {
            mayEnd.countDown();
            ended.get(10, TimeUnit.SECONDS);
        }

        void rollBack() {
            rollBackRequested.set(true);
            mayEnd.countDown();
            assertThatThrownBy(() -> ended.get(10, TimeUnit.SECONDS)).hasRootCauseInstanceOf(RolledBack.class);
        }
    }

    private static final class RolledBack extends RuntimeException {
    }
}
