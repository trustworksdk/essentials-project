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

import dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.PostgresqlEventStore;
import dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.eventstream.PersistedEvent;
import dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.gap.PostgresqlEventStreamGapHandler;
import dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.gap.PostgresqlEventStreamGapHandler.ResolveTransientGapsToPermanentGapsPromotionStrategy;
import dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.persistence.table_per_aggregate_type.*;
import dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.processor.EventProcessorIT;
import dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.serializer.json.EssentialsJSONEventSerializers;
import dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.test_data.*;
import dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.types.*;
import dk.trustworks.essentials.components.foundation.transaction.UnitOfWork;
import dk.trustworks.essentials.components.foundation.types.SubscriberId;
import dk.trustworks.essentials.shared.functional.tuple.Pair;
import dk.trustworks.essentials.types.LongRange;
import org.junit.jupiter.api.*;
import org.slf4j.*;
import reactor.core.Disposable;

import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

/**
 * Several writers append to one aggregate type concurrently, each holding its transaction open for a random moment, so
 * global order and commit order disagree all the time; some transactions roll back and leave holes. Each writer
 * publishes its event to the bus once it committed - the order a CDC dispatcher sees commits in. Three subscriptions
 * watch: one moved onto the bus at warm-up, one started while CDC is ACTIVE ({@code BackfillThenLiveOrdered}), and one
 * on polling throughout; CDC also fails and recovers part-way, so the first two catch up while holes are still open.
 * <p>
 * Every subscription must receive exactly the persisted events: each once, none missing. The schedule is random, the
 * assertion is not.
 */
class CdcEventStoreConcurrentWritersIT extends AbstractLogicalReplicationPostgresIT {
    private static final Logger   log                = LoggerFactory.getLogger(CdcEventStoreConcurrentWritersIT.class);
    private static final int      WRITERS            = 4;
    private static final int      APPENDS_PER_WRITER = 75;
    private static final int      PAGE_SIZE          = 10;
    private static final Duration DEBOUNCE           = Duration.ofMillis(200);

    private PostgresqlEventStore<SeparateTablePerAggregateEventStreamConfiguration> eventStore;
    private CdcEventBus                                                             cdcBus;
    private CdcAvailability                                                         availability;
    private CdcEventStore<SeparateTablePerAggregateEventStreamConfiguration>        cdcEventStore;
    private CdcEventStore<SeparateTablePerAggregateEventStreamConfiguration>        neverActiveCdcEventStore;
    private final List<Disposable>                                                  subscriptions = new CopyOnWriteArrayList<>();
    private ExecutorService                                                         writers;

    @BeforeEach
    void setup() {
        var persistenceStrategy = new SeparateTablePerAggregateTypePersistenceStrategy(
                jdbi,
                unitOfWorkFactory,
                new EventProcessorIT.TestPersistableEventMapper(),
                SeparateTablePerAggregateTypeEventStreamConfigurationFactory.defaultConfiguration(EssentialsJSONEventSerializers.create())
        );
        persistenceStrategy.addAggregateEventStreamConfiguration(ORDERS, OrderId.class);
        // Every transient gap is re-queried on every poll. The default includes only the two lowest, so a rolled-back
        // hole would keep the polling subscription from re-querying any later late commit until the hole is promoted
        // to permanent (two minutes) - a limit of that strategy, not of what is under test
        var gapHandler = new PostgresqlEventStreamGapHandler<SeparateTablePerAggregateEventStreamConfiguration>(
                unitOfWorkFactory,
                Duration.ofSeconds(60),
                (aggregateType, globalOrderQueryRange, allTransientGaps) -> allTransientGaps.stream().map(Pair::_1).toList(),
                ResolveTransientGapsToPermanentGapsPromotionStrategy.thresholdBased(120));
        eventStore = PostgresqlEventStore.<SeparateTablePerAggregateEventStreamConfiguration>builder()
                                         .setUnitOfWorkFactory(unitOfWorkFactory)
                                         .setPersistenceStrategy(persistenceStrategy)
                                         .setEventStreamGapHandlerFactory(store -> gapHandler)
                                         .build();

        var cdcProperties = new CdcProperties();
        cdcProperties.getHealthCheck().setActiveCutbackDebounce(DEBOUNCE);
        // BackfillThenLiveOrdered's drain is strict: a rolled-back append is a hole in its live tail it cannot tell from
        // a late commit, so it waits the stall threshold (three minutes by default) and then back-fills past it. A
        // lower order committing late is no such hole any more - the tracker lets it through to the drain
        cdcProperties.getEventBus().setLiveDrainStallThreshold(Duration.ofSeconds(1));
        cdcBus = new CdcEventBus(cdcProperties.getEventBus());
        availability = new CdcAvailability();
        cdcEventStore = new CdcEventStore<>(eventStore, unitOfWorkFactory, gapHandler, cdcBus, cdcProperties, availability, Optional.empty());
        neverActiveCdcEventStore = new CdcEventStore<>(eventStore, unitOfWorkFactory, gapHandler, cdcBus, cdcProperties, new CdcAvailability(), Optional.empty());
        subscriptions.add(cdcBus.fluxForAggregate(ORDERS).subscribe());
        writers = Executors.newFixedThreadPool(WRITERS, runnable -> new Thread(runnable, "test-writer"));
    }

    @AfterEach
    void cleanup() {
        if (writers != null) {
            writers.shutdownNow();
        }
        subscriptions.forEach(Disposable::dispose);
        unitOfWorkFactory.getCurrentUnitOfWork().ifPresent(UnitOfWork::rollback);
    }

    @Test
    void every_subscription_receives_every_persisted_event_exactly_once_while_writers_commit_out_of_global_order() throws Exception {
        var movedOntoTheBus = subscribe(cdcEventStore, "moved-onto-the-bus");
        availability.active("concurrent-writers-slot");
        await().pollDelay(DEBOUNCE.multipliedBy(3)).atMost(Duration.ofSeconds(5)).until(() -> true);
        var ordered = subscribe(cdcEventStore, "ordered");
        var polling = subscribe(neverActiveCdcEventStore, "polling");

        var rolledBack = new AtomicInteger();
        var dispatcher = new Object();
        var appended   = new CountDownLatch(WRITERS);
        for (int writer = 0; writer < WRITERS; writer++) {
            writers.submit(() -> {
                var random = ThreadLocalRandom.current();
                try {
                    for (int append = 0; append < APPENDS_PER_WRITER; append++) {
                        var rollBack = random.nextInt(10) == 0;
                        try {
                            var event = unitOfWorkFactory.withUnitOfWork(unitOfWork -> {
                                var appendedEvent = appendTo(unitOfWork);
                                // Holds the global order while other writers take - and commit - later ones
                                Thread.sleep(random.nextInt(5));
                                if (rollBack) {
                                    throw new IllegalStateException("Simulated failure - rolls the append back");
                                }
                                return appendedEvent;
                            });
                            synchronized (dispatcher) {
                                cdcBus.publish(List.of(event));
                            }
                        } catch (RuntimeException e) {
                            if (!rollBack) {
                                throw e;
                            }
                            rolledBack.incrementAndGet();
                        }
                    }
                } finally {
                    appended.countDown();
                }
                return null;
            });
            if (writer == WRITERS / 2) {
                // CDC fails and recovers part-way: both bus subscriptions poll through it and catch up on their way back
                await().pollDelay(Duration.ofMillis(150)).atMost(Duration.ofSeconds(5)).until(() -> true);
                availability.failed("concurrent-writers-slot", "simulated outage");
                await().pollDelay(Duration.ofMillis(300)).atMost(Duration.ofSeconds(5)).until(() -> true);
                availability.active("concurrent-writers-slot");
            }
        }
        assertThat(appended.await(30, TimeUnit.SECONDS)).isTrue();

        var persisted = unitOfWorkFactory.withUnitOfWork(() -> eventStore.loadEventsByGlobalOrder(ORDERS, LongRange.from(1), List.of())
                                                                         .map(event -> event.globalEventOrder().longValue())
                                                                         .toList());
        log.info("Persisted {} events, {} appends rolled back", persisted.size(), rolledBack.get());
        assertThat(persisted).hasSize(WRITERS * APPENDS_PER_WRITER - rolledBack.get());

        await().atMost(Duration.ofSeconds(20)).untilAsserted(() -> {
            assertThat(movedOntoTheBus).as("moved onto the bus at warm-up").containsAll(persisted);
            assertThat(ordered).as("started while CDC is ACTIVE").containsAll(persisted);
            assertThat(polling).as("polling").containsAll(persisted);
        });
        // Anything delivered twice would have arrived by now
        await().pollDelay(Duration.ofMillis(500)).atMost(Duration.ofSeconds(5)).until(() -> true);
        assertThat(movedOntoTheBus).as("moved onto the bus at warm-up").doesNotHaveDuplicates().containsExactlyInAnyOrderElementsOf(persisted);
        assertThat(ordered).as("started while CDC is ACTIVE").doesNotHaveDuplicates().containsExactlyInAnyOrderElementsOf(persisted);
        assertThat(polling).as("polling").doesNotHaveDuplicates().containsExactlyInAnyOrderElementsOf(persisted);
    }

    private List<Long> subscribe(CdcEventStore<SeparateTablePerAggregateEventStreamConfiguration> store, String subscriberId) {
        var received = new CopyOnWriteArrayList<Long>();
        subscriptions.add(store.pollEvents(ORDERS,
                                           GlobalEventOrder.FIRST_GLOBAL_EVENT_ORDER.longValue(),
                                           Optional.of(PAGE_SIZE),
                                           Optional.of(Duration.ofMillis(50)),
                                           Optional.empty(),
                                           Optional.of(SubscriberId.of(subscriberId)),
                                           Optional.empty())
                               .subscribe(event -> received.add(event.globalEventOrder().longValue())));
        return received;
    }

    private PersistedEvent appendTo(UnitOfWork unitOfWork) {
        var orderId = OrderId.random();
        return eventStore.appendToStream(ORDERS,
                                         orderId,
                                         EventOrder.NO_EVENTS_PREVIOUSLY_PERSISTED,
                                         List.of(new OrderEvent.OrderAdded(orderId, CustomerId.random(), 1)))
                         .eventList()
                         .getFirst();
    }
}
