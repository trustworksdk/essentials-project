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

package dk.trustworks.essentials.components.eventsourced.eventstore.postgresql;

import dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.eventstream.*;
import dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.gap.PostgresqlEventStreamGapHandler;
import dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.observability.EventStoreSubscriptionObserver;
import dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.persistence.*;
import dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.persistence.table_per_aggregate_type.*;
import dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.serializer.TenantSerializer;
import dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.serializer.json.EssentialsJSONEventSerializers;
import dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.subscription.*;
import dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.test_data.*;
import dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.transaction.*;
import dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.types.*;
import dk.trustworks.essentials.components.distributed.fencedlock.postgresql.PostgresqlFencedLockManager;
import dk.trustworks.essentials.components.foundation.transaction.UnitOfWork;
import dk.trustworks.essentials.components.foundation.types.*;
import org.jdbi.v3.core.Jdbi;
import org.jdbi.v3.postgres.PostgresPlugin;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.testcontainers.junit.jupiter.*;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.reactivestreams.Subscription;
import org.testcontainers.shaded.org.awaitility.Awaitility;
import reactor.core.Disposable;
import reactor.core.publisher.*;

import java.io.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Gap handling as a polling subscription sees it, through both polling flavours, with the default
 * {@link PostgresqlEventStreamGapHandler}:
 * <ul>
 *     <li>an event that fills a gap is delivered once, and the read position does not move back to it - which used to
 *     deliver every event above the gap again</li>
 *     <li>an event committing late above many rolled-back holes is picked up within a few polls - it used to wait
 *     until the holes below it were promoted to permanent gaps (120 s), and was lost when it was promoted with them</li>
 *     <li>a tenant-filtered subscription records no gaps for other tenants' events - they used to be recorded as
 *     transient gaps and promoted to permanent ones</li>
 *     <li>a gap fill a subscription had not handled when it stopped is delivered once it is restarted from its resume
 *     point, which lies above the fill - the poll used to resolve the gap before handing the fill on, so a stop in
 *     between lost it for good</li>
 * </ul>
 */
@Testcontainers
class PollingGapHandlingIT {
    private static final AtomicInteger AGGREGATE_TYPE_COUNTER = new AtomicInteger();
    private static final TenantId      TENANT_A               = TenantId.of("TenantA");
    private static final TenantId      TENANT_B               = TenantId.of("TenantB");
    private static final Duration      POLLING_INTERVAL       = Duration.ofMillis(50);

    @Container
    private static final PostgreSQLContainer postgreSQLContainer = new PostgreSQLContainer("postgres:18.4")
            .withDatabaseName("event-store")
            .withUsername("test-user")
            .withPassword("secret-password");

    enum PollingMode {
        POLL_EVENTS,
        UNBOUNDED_POLL_FOR_EVENTS
    }

    private EventStoreManagedUnitOfWorkFactory                                      unitOfWorkFactory;
    private PostgresqlEventStore<SeparateTablePerAggregateEventStreamConfiguration> eventStore;
    private AggregateType                                                           aggregateType;
    private ExecutorService                                                         executor;
    private Disposable                                                              subscription;
    private Jdbi                                                                    jdbi;
    private EventStoreSubscriptionManager                                           subscriptionManager;
    /**
     * The global orders the event store handed to a subscriber - see {@link EventStoreSubscriptionObserver#publishEvent}
     */
    private final List<Long>                                                        published = new CopyOnWriteArrayList<>();
    /**
     * The tenant {@link TenantPersistableEventMapper} stamps on the next event appended; {@code null} = no tenant
     */
    private volatile TenantId                                                       tenantOfNextEvent;

    @BeforeEach
    void setup() {
        jdbi = Jdbi.create(postgreSQLContainer.getJdbcUrl(),
                               postgreSQLContainer.getUsername(),
                               postgreSQLContainer.getPassword());
        jdbi.installPlugin(new PostgresPlugin());

        // A fresh aggregate type per test: own event table, own gap rows - nothing to clean up between tests
        aggregateType = AggregateType.of("GapOrders" + AGGREGATE_TYPE_COUNTER.incrementAndGet());
        unitOfWorkFactory = new EventStoreManagedUnitOfWorkFactory(jdbi);
        var persistenceStrategy = new SeparateTablePerAggregateTypePersistenceStrategy(jdbi,
                                                                                       unitOfWorkFactory,
                                                                                       new TenantPersistableEventMapper(),
                                                                                       SeparateTablePerAggregateTypeEventStreamConfigurationFactory.standardConfiguration(
                                                                                               type -> type.toString().toLowerCase() + "_events",
                                                                                               EventStreamTableColumnNames.defaultColumnNames(),
                                                                                               EssentialsJSONEventSerializers.create(),
                                                                                               IdentifierColumnType.UUID,
                                                                                               JSONColumnType.JSONB,
                                                                                               new TenantSerializer.TenantIdSerializer()));
        persistenceStrategy.addAggregateEventStreamConfiguration(aggregateType, OrderId.class);
        eventStore = PostgresqlEventStore.<SeparateTablePerAggregateEventStreamConfiguration>builder()
                                         .setUnitOfWorkFactory(unitOfWorkFactory)
                                         .setPersistenceStrategy(persistenceStrategy)
                                         // The default configuration - it is the default gap query strategy under test
                                         .setEventStreamGapHandlerFactory(store -> new PostgresqlEventStreamGapHandler<>(unitOfWorkFactory))
                                         .setEventStoreSubscriptionObserver(new EventStoreSubscriptionObserver.NoOpEventStoreSubscriptionObserver() {
                                             @Override
                                             public void publishEvent(SubscriberId subscriberId, AggregateType aggregateType, PersistedEvent persistedEvent, Duration publishEventDuration) {
                                                 published.add(persistedEvent.globalEventOrder().longValue());
                                             }
                                         })
                                         .build();
        executor = Executors.newCachedThreadPool();
        tenantOfNextEvent = null;
        published.clear();
        subscriptionManager = null;
    }

    @AfterEach
    void cleanup() {
        if (subscription != null) {
            subscription.dispose();
        }
        if (subscriptionManager != null) {
            subscriptionManager.stop();
        }
        executor.shutdownNow();
        unitOfWorkFactory.getCurrentUnitOfWork().ifPresent(UnitOfWork::rollback);
    }

    @ParameterizedTest
    @EnumSource(PollingMode.class)
    void a_gap_fill_is_delivered_once_and_nothing_above_it_is_delivered_again(PollingMode pollingMode) throws Exception {
        var subscriberId = SubscriberId.of("gap-fill-once-" + pollingMode);
        var received     = new CopyOnWriteArrayList<Long>();

        // Global order 1 is taken by a transaction that commits late
        var lateCommit = appendAndHoldOpen();
        var second     = appendCommitted();
        assertThat(lateCommit.globalOrder).isEqualTo(1);
        assertThat(second).isEqualTo(2);

        subscription = subscribe(pollingMode, subscriberId, Optional.empty(), received);
        Awaitility.waitAtMost(Duration.ofSeconds(10)).untilAsserted(() -> assertThat(received).containsExactly(2L));
        Awaitility.waitAtMost(Duration.ofSeconds(10)).untilAsserted(() -> assertThat(transientGapsOf(subscriberId)).containsExactly(GlobalEventOrder.of(1)));

        // When the late transaction commits, a poll returns just the gap-filling event, below the read position
        lateCommit.commit();
        Awaitility.waitAtMost(Duration.ofSeconds(10)).untilAsserted(() -> assertThat(received).contains(1L));

        // Then: a marker event appended afterwards arrives with nothing re-delivered before it
        var marker = appendCommitted();
        Awaitility.waitAtMost(Duration.ofSeconds(10)).untilAsserted(() -> assertThat(received).contains(marker));
        assertThat(received).containsExactly(2L, 1L, marker);
        assertThat(transientGapsOf(subscriberId)).isEmpty();
    }

    /**
     * {@link EventStore#pollEvents} publishes no more events than demanded. Gap fills come first in a poll's result,
     * so the ones beyond the demand must stay gaps the next poll asks for again - not be resolved and then dropped.
     */
    @Test
    void gap_fills_beyond_the_demand_are_delivered_by_a_later_poll() throws Exception {
        var subscriberId = SubscriberId.of("gap-fills-beyond-demand");
        var received     = new CopyOnWriteArrayList<Long>();

        var firstLateCommit  = appendAndHoldOpen();
        var secondLateCommit = appendAndHoldOpen();
        var committed        = appendCommitted();
        assertThat(List.of(firstLateCommit.globalOrder, secondLateCommit.globalOrder, committed)).containsExactly(1L, 2L, 3L);

        // Demand one event at a time - and none after the first until the test says so
        var requestMore = new AtomicBoolean(false);
        var subscriber = new BaseSubscriber<PersistedEvent>() {
            @Override
            protected void hookOnSubscribe(Subscription subscription) {
                request(1);
            }

            @Override
            protected void hookOnNext(PersistedEvent event) {
                received.add(event.globalEventOrder().longValue());
                if (requestMore.get()) {
                    request(1);
                }
            }
        };
        subscription = subscriber;
        eventStore.pollEvents(aggregateType,
                              GlobalEventOrder.FIRST_GLOBAL_EVENT_ORDER,
                              Optional.of(10),
                              Optional.of(POLLING_INTERVAL),
                              Optional.empty(),
                              Optional.of(subscriberId),
                              Optional.empty())
                  .subscribe(subscriber);
        Awaitility.waitAtMost(Duration.ofSeconds(10)).untilAsserted(() -> assertThat(received).containsExactly(committed));
        Awaitility.waitAtMost(Duration.ofSeconds(10)).untilAsserted(() -> assertThat(transientGapsOf(subscriberId)).containsExactly(GlobalEventOrder.of(1), GlobalEventOrder.of(2)));

        // Both gaps are filled before the next poll, which therefore returns two events against a demand of one
        firstLateCommit.commit();
        secondLateCommit.commit();
        requestMore.set(true);
        subscriber.request(1);

        var marker = appendCommitted();
        Awaitility.waitAtMost(Duration.ofSeconds(10)).untilAsserted(() -> assertThat(received).contains(marker));
        assertThat(received).containsExactly(committed, 1L, 2L, marker);
        assertThat(transientGapsOf(subscriberId)).isEmpty();
    }

    @ParameterizedTest
    @EnumSource(PollingMode.class)
    void a_late_commit_above_many_rolled_back_holes_is_delivered_without_waiting_for_them_to_become_permanent(PollingMode pollingMode) throws Exception {
        var subscriberId = SubscriberId.of("late-commit-above-holes-" + pollingMode);
        var received     = new CopyOnWriteArrayList<Long>();

        // More rolled-back holes than one query includes, all below the late commit
        var numberOfHoles = 60;
        for (var i = 0; i < numberOfHoles; i++) {
            appendRolledBack();
        }
        var lateCommit = appendAndHoldOpen();
        var committed  = appendCommitted();
        assertThat(lateCommit.globalOrder).isEqualTo(numberOfHoles + 1);
        assertThat(committed).isEqualTo(numberOfHoles + 2);

        subscription = subscribe(pollingMode, subscriberId, Optional.empty(), received);
        Awaitility.waitAtMost(Duration.ofSeconds(10)).untilAsserted(() -> assertThat(received).containsExactly(committed));
        Awaitility.waitAtMost(Duration.ofSeconds(10)).untilAsserted(() -> assertThat(transientGapsOf(subscriberId)).hasSize(numberOfHoles + 1));

        lateCommit.commit();

        // Well within the 120 s it took before - when the holes below it are promoted
        Awaitility.waitAtMost(Duration.ofSeconds(10)).untilAsserted(() -> assertThat(received).containsExactly(committed, lateCommit.globalOrder));
        assertThat(transientGapsOf(subscriberId)).hasSize(numberOfHoles)
                                                 .doesNotContain(GlobalEventOrder.of(lateCommit.globalOrder));
    }

    @ParameterizedTest
    @EnumSource(PollingMode.class)
    void a_tenant_filtered_subscription_records_no_gaps_for_other_tenants_events(PollingMode pollingMode) {
        var subscriberId = SubscriberId.of("tenant-filtered-" + pollingMode);
        var received     = new CopyOnWriteArrayList<Long>();

        // Tenants interleave in global order; an event without a tenant belongs to every tenant
        var expected = new ArrayList<Long>();
        expected.add(appendCommitted(TENANT_A));
        appendCommitted(TENANT_B);
        appendCommitted(TENANT_B);
        expected.add(appendCommitted(TENANT_A));
        appendCommitted(TENANT_B);
        expected.add(appendCommitted(null));
        appendCommitted(TENANT_B);
        expected.add(appendCommitted(TENANT_A));

        subscription = subscribe(pollingMode, subscriberId, Optional.of(TENANT_A), received);
        Awaitility.waitAtMost(Duration.ofSeconds(10)).untilAsserted(() -> assertThat(received).containsExactlyElementsOf(expected));

        // Live: more of the other tenant's events, then one of ours - several polls over other tenants' events
        appendCommitted(TENANT_B);
        appendCommitted(TENANT_B);
        expected.add(appendCommitted(TENANT_A));
        Awaitility.waitAtMost(Duration.ofSeconds(10)).untilAsserted(() -> assertThat(received).containsExactlyElementsOf(expected));

        assertThat(transientGapsOf(subscriberId)).isEmpty();
        assertThat(unitOfWorkFactory.withUnitOfWork(() -> eventStore.getEventStreamGapHandler().getPermanentGapsFor(aggregateType).toList())).isEmpty();
    }


    /**
     * A poll used to resolve the gaps its events fill before handing those events on. A subscription stopped in between
     * - here right after it handled the first of two gap fills in one poll - resumes above the fills (its resume point
     * moved past the higher events it handled before), and the gap was no longer recorded, so the second fill was
     * never asked for again.
     */
    @ParameterizedTest
    @EnumSource(PollingMode.class)
    void a_gap_fill_a_stopped_subscription_did_not_get_to_is_delivered_once_it_restarts_from_its_resume_point(PollingMode pollingMode) throws Exception {
        var subscriberId = SubscriberId.of("gap-fill-after-stop-" + pollingMode);

        // Global orders 1 and 2 are taken by one transaction that commits late, so both gaps fill in the same poll
        var lateCommit = appendAndHoldOpen(2);
        var committed  = appendCommitted();
        assertThat(lateCommit.globalOrder).isEqualTo(1);
        assertThat(committed).isEqualTo(3);

        var receivedBeforeTheStop = new CopyOnWriteArrayList<Long>();
        var stoppingSubscriber = new BaseSubscriber<PersistedEvent>() {
            @Override
            protected void hookOnSubscribe(Subscription subscription) {
                requestUnbounded();
            }

            @Override
            protected void hookOnNext(PersistedEvent event) {
                receivedBeforeTheStop.add(event.globalEventOrder().longValue());
                if (event.globalEventOrder().longValue() < committed) {
                    // Stopped right after handling the first gap fill - a shutdown, a fenced-lock hand-over, a resetFrom
                    dispose();
                }
            }
        };
        subscription = stoppingSubscriber;
        poll(pollingMode, subscriberId, GlobalEventOrder.FIRST_GLOBAL_EVENT_ORDER.longValue(), Optional.empty()).subscribe(stoppingSubscriber);
        Awaitility.waitAtMost(Duration.ofSeconds(10)).untilAsserted(() -> assertThat(receivedBeforeTheStop).containsExactly(committed));
        Awaitility.waitAtMost(Duration.ofSeconds(10)).untilAsserted(() -> assertThat(transientGapsOf(subscriberId)).containsExactly(GlobalEventOrder.of(1), GlobalEventOrder.of(2)));

        lateCommit.commit();
        Awaitility.waitAtMost(Duration.ofSeconds(10)).until(stoppingSubscriber::isDisposed);
        assertThat(receivedBeforeTheStop).containsExactly(committed, 1L);

        // Restarted from the resume point the subscriber had reached: past the highest event it handled
        var receivedAfterTheRestart = new CopyOnWriteArrayList<Long>();
        subscription = poll(pollingMode, subscriberId, committed + 1, Optional.empty()).subscribe(event -> receivedAfterTheRestart.add(event.globalEventOrder().longValue()));
        var marker = appendCommitted();
        Awaitility.waitAtMost(Duration.ofSeconds(10)).untilAsserted(() -> assertThat(receivedAfterTheRestart).contains(2L, marker));
        assertThat(receivedAfterTheRestart).doesNotContain(committed);
        Awaitility.waitAtMost(Duration.ofSeconds(10)).untilAsserted(() -> assertThat(transientGapsOf(subscriberId)).isEmpty());
    }

    /**
     * A batched subscription holds what the event store hands it until its batch is full or its latency is up, so a
     * gap fill handed on is not handled yet. Stopped in that window, it used to save a resume point above the fill
     * while the poll had already resolved the gap - the fill was lost.
     */
    @Test
    void a_gap_fill_still_waiting_for_its_batch_when_the_subscription_stops_is_delivered_once_it_restarts() throws Exception {
        var subscriberId = SubscriberId.of("batched-gap-fill-after-stop");
        // A first subscription starts at the lowest global order persisted, so the gap must lie above it
        var first      = appendCommitted();
        var lateCommit = appendAndHoldOpen(1);
        var committed  = appendCommitted();
        assertThat(List.of(first, lateCommit.globalOrder, committed)).containsExactly(1L, 2L, 3L);

        var durableSubscriptionRepository = startSubscriptionManager(10);
        var handled = new CopyOnWriteArrayList<Long>();
        var batchedSubscription = subscriptionManager.batchSubscribeToAggregateEventsAsynchronously(subscriberId,
                                                                                                     aggregateType,
                                                                                                     GlobalEventOrder.FIRST_GLOBAL_EVENT_ORDER,
                                                                                                     Optional.empty(),
                                                                                                     100,
                                                                                                     // Long enough for the stop below to come first
                                                                                                     Duration.ofSeconds(3),
                                                                                                     events -> {
                                                                                                         events.forEach(event -> handled.add(event.globalEventOrder().longValue()));
                                                                                                         return events.size();
                                                                                                     });
        Awaitility.waitAtMost(Duration.ofSeconds(15)).untilAsserted(() -> assertThat(handled).containsExactly(first, committed));
        Awaitility.waitAtMost(Duration.ofSeconds(10)).untilAsserted(() -> assertThat(persistedResumePoint(durableSubscriptionRepository, subscriberId)).isEqualTo(committed + 1));
        assertThat(transientGapsOf(subscriberId)).containsExactly(GlobalEventOrder.of(lateCommit.globalOrder));

        // The gap fill is handed to the subscription, which holds it for its next batch - and is stopped meanwhile
        lateCommit.commit();
        Awaitility.waitAtMost(Duration.ofSeconds(10)).untilAsserted(() -> assertThat(published).contains(lateCommit.globalOrder));
        batchedSubscription.stop();
        assertThat(handled).containsExactly(first, committed);

        batchedSubscription.start();
        Awaitility.waitAtMost(Duration.ofSeconds(15)).untilAsserted(() -> assertThat(handled).contains(lateCommit.globalOrder));
        Awaitility.waitAtMost(Duration.ofSeconds(10)).untilAsserted(() -> assertThat(transientGapsOf(subscriberId)).isEmpty());
    }

    /**
     * (a) A batched subscription holds a gap fill until its batch is full or its latency is up. The poll used to resolve
     * the fill's gap once it had handed the fill on, so a process that died in that window - its resume point, persisted
     * by the periodic checkpoint, already above the fill - restarted without the fill: it was never handled. Now the gap is
     * resolved only once the batch holding the fill was handled.
     */
    @Test
    void a_gap_fill_waiting_for_its_batch_when_the_process_dies_is_delivered_after_the_restart() throws Exception {
        var subscriberId = SubscriberId.of("batched-gap-fill-crash");
        var first        = appendCommitted();
        var lateCommit   = appendAndHoldOpen(1);
        var committed    = appendCommitted();

        var durableSubscriptionRepository = startSubscriptionManager(10);
        var handled = new CopyOnWriteArrayList<Long>();
        var batchedSubscription = subscriptionManager.batchSubscribeToAggregateEventsAsynchronously(subscriberId,
                                                                                                     aggregateType,
                                                                                                     GlobalEventOrder.FIRST_GLOBAL_EVENT_ORDER,
                                                                                                     Optional.empty(),
                                                                                                     100,
                                                                                                     // Long enough for the crash below to come first
                                                                                                     Duration.ofSeconds(3),
                                                                                                     events -> {
                                                                                                         events.forEach(event -> handled.add(event.globalEventOrder().longValue()));
                                                                                                         return events.size();
                                                                                                     });
        Awaitility.waitAtMost(Duration.ofSeconds(15)).untilAsserted(() -> assertThat(handled).containsExactly(first, committed));
        Awaitility.waitAtMost(Duration.ofSeconds(10)).untilAsserted(() -> assertThat(persistedResumePoint(durableSubscriptionRepository, subscriberId)).isEqualTo(committed + 1));

        // The gap fill is handed to the subscription, which holds it for its next batch - and the process dies meanwhile
        lateCommit.commit();
        Awaitility.waitAtMost(Duration.ofSeconds(10)).untilAsserted(() -> assertThat(published).contains(lateCommit.globalOrder));
        simulateCrash(batchedSubscription, durableSubscriptionRepository, subscriberId, committed + 1);
        assertThat(handled).containsExactly(first, committed);

        batchedSubscription.start();
        Awaitility.waitAtMost(Duration.ofSeconds(15)).untilAsserted(() -> assertThat(handled).contains(lateCommit.globalOrder));
        Awaitility.waitAtMost(Duration.ofSeconds(10)).untilAsserted(() -> assertThat(transientGapsOf(subscriberId)).isEmpty());
        assertThat(handled).containsExactly(first, committed, lateCommit.globalOrder);
    }

    /**
     * (b) A {@link PersistedEventSubscriber} retries an I/O failure asynchronously: the handling of the gap fill waits in
     * the retry backoff, while the poll that handed it on carries on - and used to resolve the fill's gap. A process
     * that died before a retry succeeded restarted above the fill, which was never handled.
     */
    @Test
    void a_gap_fill_whose_handling_waits_for_an_io_retry_when_the_process_dies_is_delivered_after_the_restart() throws Exception {
        var subscriberId = SubscriberId.of("io-retry-gap-fill-crash");
        var first        = appendCommitted();
        var lateCommit   = appendAndHoldOpen(1);
        var committed    = appendCommitted();

        var durableSubscriptionRepository = startSubscriptionManager(10);
        var handled        = new CopyOnWriteArrayList<Long>();
        var failingAttempts = new AtomicInteger();
        var failTheFill    = new AtomicBoolean(true);
        var subscription = subscriptionManager.subscribeToAggregateEventsAsynchronously(subscriberId,
                                                                                        aggregateType,
                                                                                        GlobalEventOrder.FIRST_GLOBAL_EVENT_ORDER,
                                                                                        Optional.empty(),
                                                                                        (PersistedEventHandler) event -> {
                                                                                            if (event.globalEventOrder().longValue() == lateCommit.globalOrder && failTheFill.get()) {
                                                                                                failingAttempts.incrementAndGet();
                                                                                                throw new UncheckedIOException(new IOException("Intentional I/O failure handling the gap fill"));
                                                                                            }
                                                                                            handled.add(event.globalEventOrder().longValue());
                                                                                        });
        Awaitility.waitAtMost(Duration.ofSeconds(15)).untilAsserted(() -> assertThat(handled).containsExactly(first, committed));
        Awaitility.waitAtMost(Duration.ofSeconds(10)).untilAsserted(() -> assertThat(persistedResumePoint(durableSubscriptionRepository, subscriberId)).isEqualTo(committed + 1));

        // The gap fill's handling fails with an I/O error and waits for its retry - and the process dies meanwhile
        lateCommit.commit();
        Awaitility.waitAtMost(Duration.ofSeconds(10)).until(() -> failingAttempts.get() >= 2);
        simulateCrash(subscription, durableSubscriptionRepository, subscriberId, committed + 1);
        assertThat(handled).containsExactly(first, committed);

        failTheFill.set(false);
        subscription.start();
        Awaitility.waitAtMost(Duration.ofSeconds(15)).untilAsserted(() -> assertThat(handled).contains(lateCommit.globalOrder));
        Awaitility.waitAtMost(Duration.ofSeconds(10)).untilAsserted(() -> assertThat(transientGapsOf(subscriberId)).isEmpty());
        assertThat(handled).containsExactly(first, committed, lateCommit.globalOrder);
    }

    /**
     * (c) A handler that returns no demand from {@link PersistedEventHandler#handleWithBackPressure} leaves what the poll
     * hands on in the {@code limitRate} queue in front of the subscriber. The poll used to resolve the gap of a fill it
     * handed into that queue, and a stop drops the queue - even a clean stop lost the fill.
     */
    @Test
    void a_gap_fill_waiting_for_demand_when_the_subscription_stops_is_delivered_after_the_restart() throws Exception {
        var subscriberId    = SubscriberId.of("withheld-demand-gap-fill-stop");
        var first           = appendCommitted();
        var lateCommit      = appendAndHoldOpen(1);
        var committed       = appendCommitted();
        var secondCommitted = appendCommitted();

        // The subscriber asks for three events up front, and for none after them
        var durableSubscriptionRepository = startSubscriptionManager(3);
        var handled        = new CopyOnWriteArrayList<Long>();
        var withholdDemand = new AtomicBoolean(true);
        var subscription = subscriptionManager.subscribeToAggregateEventsAsynchronously(subscriberId,
                                                                                        aggregateType,
                                                                                        GlobalEventOrder.FIRST_GLOBAL_EVENT_ORDER,
                                                                                        Optional.empty(),
                                                                                        withholdingDemand(withholdDemand, handled));
        Awaitility.waitAtMost(Duration.ofSeconds(15)).untilAsserted(() -> assertThat(handled).containsExactly(first, committed, secondCommitted));
        Awaitility.waitAtMost(Duration.ofSeconds(10)).untilAsserted(() -> assertThat(persistedResumePoint(durableSubscriptionRepository, subscriberId)).isEqualTo(secondCommitted + 1));

        // The gap fill is handed on into the queue in front of the subscriber, which asks for nothing - and is stopped
        lateCommit.commit();
        Awaitility.waitAtMost(Duration.ofSeconds(10)).untilAsserted(() -> assertThat(published).contains(lateCommit.globalOrder));
        subscription.stop();
        assertThat(handled).containsExactly(first, committed, secondCommitted);
        assertThat(persistedResumePoint(durableSubscriptionRepository, subscriberId)).isEqualTo(secondCommitted + 1);

        withholdDemand.set(false);
        subscription.start();
        Awaitility.waitAtMost(Duration.ofSeconds(15)).untilAsserted(() -> assertThat(handled).contains(lateCommit.globalOrder));
        Awaitility.waitAtMost(Duration.ofSeconds(10)).untilAsserted(() -> assertThat(transientGapsOf(subscriberId)).isEmpty());
        assertThat(handled).containsExactly(first, committed, secondCommitted, lateCommit.globalOrder);
    }

    /**
     * The contract of {@link SubscriberAcknowledgement} as a caller of the event store sees it: a gap fill handed on is
     * not handed on again by the same subscription while it is not acknowledged - although later polls read it again, as
     * its gap stays open - and its gap is resolved when it is acknowledged in a unit of work that commits, not in one that
     * rolls back.
     */
    @ParameterizedTest
    @EnumSource(PollingMode.class)
    void with_an_acknowledgement_a_gap_fill_s_gap_is_resolved_only_once_it_is_acknowledged_in_a_unit_of_work_that_commits(PollingMode pollingMode) throws Exception {
        var subscriberId    = SubscriberId.of("acknowledged-gap-fill-" + pollingMode);
        var lateCommit      = appendAndHoldOpen();
        var committed       = appendCommitted();
        var acknowledgement = SubscriberAcknowledgement.create();
        var received        = new CopyOnWriteArrayList<PersistedEvent>();
        subscription = poll(pollingMode, subscriberId, GlobalEventOrder.FIRST_GLOBAL_EVENT_ORDER.longValue(), acknowledgement).subscribe(received::add);
        Awaitility.waitAtMost(Duration.ofSeconds(10)).untilAsserted(() -> assertThat(globalOrdersOf(received)).containsExactly(committed));
        assertThat(acknowledgement.isHonoured()).isTrue();
        acknowledgement.acknowledge(received.getFirst());
        Awaitility.waitAtMost(Duration.ofSeconds(10)).untilAsserted(() -> assertThat(transientGapsOf(subscriberId)).containsExactly(GlobalEventOrder.of(lateCommit.globalOrder)));

        lateCommit.commit();
        Awaitility.waitAtMost(Duration.ofSeconds(10)).untilAsserted(() -> assertThat(globalOrdersOf(received)).contains(lateCommit.globalOrder));
        var gapFill = received.stream().filter(event -> event.globalEventOrder().longValue() == lateCommit.globalOrder).findFirst().orElseThrow();

        // Not acknowledged: the gap stays open, and the polls that follow - which read the fill again - do not hand it on again
        var marker = appendCommitted();
        Awaitility.waitAtMost(Duration.ofSeconds(10)).untilAsserted(() -> assertThat(globalOrdersOf(received)).contains(marker));
        Awaitility.await().pollDelay(POLLING_INTERVAL.multipliedBy(10)).atMost(Duration.ofSeconds(5)).until(() -> true);
        assertThat(globalOrdersOf(received)).containsExactly(committed, lateCommit.globalOrder, marker);
        assertThat(transientGapsOf(subscriberId)).containsExactly(GlobalEventOrder.of(lateCommit.globalOrder));

        // Acknowledged in a unit of work that rolls back: the gap stays open
        var rolledBack = unitOfWorkFactory.getOrCreateNewUnitOfWork();
        acknowledgement.acknowledge(gapFill);
        rolledBack.rollback();
        assertThat(transientGapsOf(subscriberId)).containsExactly(GlobalEventOrder.of(lateCommit.globalOrder));

        // ... and resolved once acknowledged in one that commits
        unitOfWorkFactory.usingUnitOfWork(unitOfWork -> acknowledgement.acknowledge(gapFill));
        assertThat(transientGapsOf(subscriberId)).isEmpty();
        var secondMarker = appendCommitted();
        Awaitility.waitAtMost(Duration.ofSeconds(10)).untilAsserted(() -> assertThat(globalOrdersOf(received)).contains(secondMarker));
        assertThat(globalOrdersOf(received)).containsExactly(committed, lateCommit.globalOrder, marker, secondMarker);
    }

    /**
     * A gap fill a subscription was handed and never acknowledged - it stopped, or its process died, before it handled
     * it - keeps its gap, so the next subscription, which resumes above it, is handed it again
     */
    @ParameterizedTest
    @EnumSource(PollingMode.class)
    void a_gap_fill_handed_on_and_never_acknowledged_is_handed_to_the_next_subscription(PollingMode pollingMode) throws Exception {
        var subscriberId = SubscriberId.of("unacknowledged-gap-fill-" + pollingMode);
        var lateCommit   = appendAndHoldOpen();
        var committed    = appendCommitted();
        var received     = new CopyOnWriteArrayList<PersistedEvent>();
        subscription = poll(pollingMode, subscriberId, GlobalEventOrder.FIRST_GLOBAL_EVENT_ORDER.longValue(), SubscriberAcknowledgement.create()).subscribe(received::add);
        Awaitility.waitAtMost(Duration.ofSeconds(10)).untilAsserted(() -> assertThat(globalOrdersOf(received)).containsExactly(committed));
        Awaitility.waitAtMost(Duration.ofSeconds(10)).untilAsserted(() -> assertThat(transientGapsOf(subscriberId)).containsExactly(GlobalEventOrder.of(lateCommit.globalOrder)));
        lateCommit.commit();
        Awaitility.waitAtMost(Duration.ofSeconds(10)).untilAsserted(() -> assertThat(globalOrdersOf(received)).contains(lateCommit.globalOrder));
        subscription.dispose();
        assertThat(transientGapsOf(subscriberId)).containsExactly(GlobalEventOrder.of(lateCommit.globalOrder));

        // Resumes above the fill, as a subscription whose resume point moved past it does
        var acknowledgement = SubscriberAcknowledgement.create();
        var restarted       = new CopyOnWriteArrayList<PersistedEvent>();
        subscription = poll(pollingMode, subscriberId, committed + 1, acknowledgement).subscribe(event -> {
            unitOfWorkFactory.usingUnitOfWork(unitOfWork -> acknowledgement.acknowledge(event));
            restarted.add(event);
        });
        Awaitility.waitAtMost(Duration.ofSeconds(10)).untilAsserted(() -> assertThat(globalOrdersOf(restarted)).containsExactly(lateCommit.globalOrder));
        Awaitility.waitAtMost(Duration.ofSeconds(10)).untilAsserted(() -> assertThat(transientGapsOf(subscriberId)).isEmpty());
    }

    // -------------------------------------------------------------------------------------------------------------------------------------------------

    private Flux<PersistedEvent> poll(PollingMode pollingMode, SubscriberId subscriberId, long fromInclusiveGlobalOrder, SubscriberAcknowledgement acknowledgement) {
        return switch (pollingMode) {
            case POLL_EVENTS -> eventStore.pollEvents(aggregateType,
                                                      fromInclusiveGlobalOrder,
                                                      Optional.of(10),
                                                      Optional.of(POLLING_INTERVAL),
                                                      Optional.empty(),
                                                      Optional.of(subscriberId),
                                                      Optional.empty(),
                                                      acknowledgement);
            case UNBOUNDED_POLL_FOR_EVENTS -> eventStore.unboundedPollForEvents(aggregateType,
                                                                                fromInclusiveGlobalOrder,
                                                                                Optional.of(10),
                                                                                Optional.of(POLLING_INTERVAL),
                                                                                Optional.empty(),
                                                                                Optional.of(subscriberId),
                                                                                acknowledgement);
        };
    }

    private static List<Long> globalOrdersOf(List<PersistedEvent> events) {
        return events.stream().map(event -> event.globalEventOrder().longValue()).toList();
    }

    /**
     * Start {@link #subscriptionManager}, checkpointing resume points every 100 ms
     *
     * @return its durable subscription repository
     */
    private PostgresqlDurableSubscriptionRepository startSubscriptionManager(int eventStorePollingBatchSize) {
        var durableSubscriptionRepository = new PostgresqlDurableSubscriptionRepository(jdbi, eventStore);
        subscriptionManager = EventStoreSubscriptionManager.builder()
                                                           .setEventStore(eventStore)
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
        subscriptionManager.start();
        return durableSubscriptionRepository;
    }

    /**
     * A process that dies leaves the database as it was: the resume point its periodic checkpoint persisted last, and the
     * gap rows as they were. The subscription is stopped - nothing else can end it in a test - and whatever its stop path
     * wrote is replaced by the resume point persisted before it, as if it had never run.
     */
    private void simulateCrash(EventStoreSubscription subscription,
                               DurableSubscriptionRepository durableSubscriptionRepository,
                               SubscriberId subscriberId,
                               long resumePointPersistedBeforeTheCrash) {
        assertThat(persistedResumePoint(durableSubscriptionRepository, subscriberId)).isEqualTo(resumePointPersistedBeforeTheCrash);
        subscription.stop();
        var resumePoint = durableSubscriptionRepository.getResumePoint(subscriberId, aggregateType).orElseThrow();
        resumePoint.setResumeFromAndIncluding(GlobalEventOrder.of(resumePointPersistedBeforeTheCrash));
        durableSubscriptionRepository.saveResumePoint(resumePoint);
        assertThat(persistedResumePoint(durableSubscriptionRepository, subscriberId)).isEqualTo(resumePointPersistedBeforeTheCrash);
    }

    /**
     * Handles every event, asking for no further event while {@code withholdDemand} - so once the events the subscriber
     * asked for up front (the polling batch size) are handled, the next one waits in the queue in front of it
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

    private Disposable subscribe(PollingMode pollingMode, SubscriberId subscriberId, Optional<Tenant> tenant, List<Long> received) {
        return poll(pollingMode, subscriberId, GlobalEventOrder.FIRST_GLOBAL_EVENT_ORDER.longValue(), tenant)
                .subscribe(event -> received.add(event.globalEventOrder().longValue()));
    }

    private Flux<PersistedEvent> poll(PollingMode pollingMode, SubscriberId subscriberId, long fromInclusiveGlobalOrder, Optional<Tenant> tenant) {
        return switch (pollingMode) {
            case POLL_EVENTS -> eventStore.pollEvents(aggregateType,
                                                      fromInclusiveGlobalOrder,
                                                      Optional.of(10),
                                                      Optional.of(POLLING_INTERVAL),
                                                      tenant,
                                                      Optional.of(subscriberId),
                                                      Optional.empty());
            case UNBOUNDED_POLL_FOR_EVENTS -> eventStore.unboundedPollForEvents(aggregateType,
                                                                                fromInclusiveGlobalOrder,
                                                                                Optional.of(10),
                                                                                Optional.of(POLLING_INTERVAL),
                                                                                tenant,
                                                                                Optional.of(subscriberId));
        };
    }

    private long persistedResumePoint(DurableSubscriptionRepository durableSubscriptionRepository, SubscriberId subscriberId) {
        return durableSubscriptionRepository.getResumePoint(subscriberId, aggregateType)
                                            .map(resumePoint -> resumePoint.getResumeFromAndIncluding().longValue())
                                            .orElse(0L);
    }

    private List<GlobalEventOrder> transientGapsOf(SubscriberId subscriberId) {
        return eventStore.getEventStreamGapHandler().gapHandlerFor(subscriberId).getTransientGapsFor(aggregateType);
    }

    private long appendCommitted() {
        return appendCommitted(null);
    }

    private long appendCommitted(TenantId tenant) {
        tenantOfNextEvent = tenant;
        return unitOfWorkFactory.withUnitOfWork(() -> eventStore.appendToStream(aggregateType,
                                                                                 OrderId.random(),
                                                                                 List.of(new OrderEvent.OrderAccepted(OrderId.random()))))
                                .eventList()
                                .get(0)
                                .globalEventOrder()
                                .longValue();
    }

    private void appendRolledBack() {
        var unitOfWork = unitOfWorkFactory.getOrCreateNewUnitOfWork();
        eventStore.appendToStream(aggregateType, OrderId.random(), List.of(new OrderEvent.OrderAccepted(OrderId.random())));
        unitOfWork.rollback();
    }

    /**
     * Appends an event in a transaction on another thread and keeps it open until {@link OpenTransaction#commit()}
     */
    private OpenTransaction appendAndHoldOpen() throws Exception {
        return appendAndHoldOpen(1);
    }

    /**
     * Appends {@code numberOfEvents} events in one transaction on another thread and keeps it open until
     * {@link OpenTransaction#commit()}; {@link OpenTransaction#globalOrder()} is the lowest of their global orders
     */
    private OpenTransaction appendAndHoldOpen(int numberOfEvents) throws Exception {
        var appended    = new CompletableFuture<Long>();
        var mayCommit   = new CountDownLatch(1);
        var committed   = new CompletableFuture<Void>();
        executor.execute(() -> {
            try {
                var unitOfWork = unitOfWorkFactory.getOrCreateNewUnitOfWork();
                var events = new ArrayList<Object>();
                for (var i = 0; i < numberOfEvents; i++) {
                    events.add(new OrderEvent.OrderAccepted(OrderId.random()));
                }
                var globalOrder = eventStore.appendToStream(aggregateType, OrderId.random(), events)
                                            .eventList()
                                            .get(0)
                                            .globalEventOrder()
                                            .longValue();
                appended.complete(globalOrder);
                mayCommit.await();
                unitOfWork.commit();
                committed.complete(null);
            } catch (Throwable e) {
                appended.completeExceptionally(e);
                committed.completeExceptionally(e);
            }
        });
        return new OpenTransaction(appended.get(10, TimeUnit.SECONDS), mayCommit, committed);
    }

    private record OpenTransaction(long globalOrder, CountDownLatch mayCommit, CompletableFuture<Void> committed) {
        void commit() throws Exception {
            mayCommit.countDown();
            committed.get(10, TimeUnit.SECONDS);
        }
    }

    private class TenantPersistableEventMapper implements PersistableEventMapper {
        @Override
        public PersistableEvent map(Object aggregateId, AggregateEventStreamConfiguration aggregateEventStreamConfiguration, Object event, EventOrder eventOrder) {
            return PersistableEvent.from(EventId.random(),
                                         aggregateEventStreamConfiguration.aggregateType,
                                         aggregateId,
                                         EventTypeOrName.with(event.getClass()),
                                         event,
                                         eventOrder,
                                         EventRevision.of(1),
                                         new EventMetaData(),
                                         OffsetDateTime.now(),
                                         null,
                                         CorrelationId.random(),
                                         tenantOfNextEvent);
        }
    }
}
