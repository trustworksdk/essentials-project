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

package dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.gap;

import dk.trustworks.essentials.components.foundation.json.EssentialsObjectMappers;
import tools.jackson.databind.*;
import dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.PostgresqlEventStore;
import dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.eventstream.*;
import dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.gap.PostgresqlEventStreamGapHandler.ResolveTransientGapsToIncludeInQueryStrategy;
import dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.gap.PostgresqlEventStreamGapHandler.ResolveTransientGapsToPermanentGapsPromotionStrategy;
import dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.observability.EventStoreSubscriptionObserver;
import dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.persistence.*;
import dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.persistence.table_per_aggregate_type.*;
import dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.serializer.json.*;
import dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.test_data.*;
import dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.transaction.*;
import dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.types.*;
import dk.trustworks.essentials.components.foundation.postgresql.SqlExecutionTimeLogger;
import dk.trustworks.essentials.components.foundation.transaction.UnitOfWork;
import dk.trustworks.essentials.components.foundation.types.*;
import dk.trustworks.essentials.shared.functional.tuple.Pair;
import dk.trustworks.essentials.types.LongRange;
import org.jdbi.v3.core.Jdbi;
import org.jdbi.v3.postgres.PostgresPlugin;
import org.junit.jupiter.api.*;
import org.slf4j.*;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.*;
import org.testcontainers.shaded.org.awaitility.Awaitility;

import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.stream.*;

import static org.assertj.core.api.Assertions.*;
import static dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.gap.PostgresqlEventStreamGapHandler.TRANSIENT_SUBSCRIBER_GAPS_TABLE_NAME;

@Testcontainers
class PostgresqlEventStreamGapHandlerIT {
    private static final Logger                 log               = LoggerFactory.getLogger(PostgresqlEventStreamGapHandlerIT.class);
    public static final  AggregateType          ORDERS            = AggregateType.of("Orders");
    private static final List<GlobalEventOrder> NO_TRANSIENT_GAPS = List.of();

    private Jdbi                                                                    jdbi;
    private AggregateType                                                           aggregateType;
    private EventStoreUnitOfWorkFactory<EventStoreUnitOfWork>                       unitOfWorkFactory;
    private TestPersistableEventMapper                                              eventMapper;
    private PostgresqlEventStore<SeparateTablePerAggregateEventStreamConfiguration> eventStore;

    @Container
    private final PostgreSQLContainer postgreSQLContainer = new PostgreSQLContainer("postgres:18.4")
            .withDatabaseName("event-store")
            .withUsername("test-user")
            .withPassword("secret-password");


    @BeforeEach
    void setup() {
        jdbi = Jdbi.create(postgreSQLContainer.getJdbcUrl(),
                           postgreSQLContainer.getUsername(),
                           postgreSQLContainer.getPassword());
        jdbi.installPlugin(new PostgresPlugin());
        jdbi.setSqlLogger(new SqlExecutionTimeLogger());

        aggregateType = ORDERS;
        unitOfWorkFactory = new EventStoreManagedUnitOfWorkFactory(jdbi);
        eventMapper = new TestPersistableEventMapper();
        var persistenceStrategy = new SeparateTablePerAggregateTypePersistenceStrategy(jdbi,
                                                                                       unitOfWorkFactory,
                                                                                       eventMapper,
                                                                                       SeparateTablePerAggregateTypeEventStreamConfigurationFactory.standardSingleTenantConfiguration(EssentialsJSONEventSerializers.create(),
                                                                                                                                                                                      IdentifierColumnType.UUID,
                                                                                                                                                                                      JSONColumnType.JSONB));
        persistenceStrategy.addAggregateEventStreamConfiguration(aggregateType,
                                                                 OrderId.class);
        eventStore = PostgresqlEventStore.<SeparateTablePerAggregateEventStreamConfiguration>builder()
                                         .setUnitOfWorkFactory(unitOfWorkFactory)
                                         .setPersistenceStrategy(persistenceStrategy)
                                         .setEventStreamGapHandlerFactory(eventStore -> new PostgresqlEventStreamGapHandler<>(unitOfWorkFactory,
                                                                                                                              Duration.ofMillis(1000),
                                                                                                                              (forAggregateType, globalOrderQueryRange, allTransientGaps) -> {
                                                                                                                                  var numberOfGaps          = allTransientGaps.size();
                                                                                                                                  var numberOfGapsToInclude = Math.min(numberOfGaps, 2);
                                                                                                                                  return numberOfGapsToInclude > 0 ? allTransientGaps.subList(0, numberOfGapsToInclude)
                                                                                                                                                                                     .stream()
                                                                                                                                                                                     .map(Pair::_1)
                                                                                                                                                                                     .collect(Collectors.toList()) : NO_TRANSIENT_GAPS;
                                                                                                                              },
                                                                                                                              ResolveTransientGapsToPermanentGapsPromotionStrategy.thresholdBased(1)))
                                         .setEventStoreSubscriptionObserver(new EventStoreSubscriptionObserver.NoOpEventStoreSubscriptionObserver())
                                         .build();
    }

    @AfterEach
    void cleanup() {
        unitOfWorkFactory.getCurrentUnitOfWork().ifPresent(UnitOfWork::rollback);
        assertThat(unitOfWorkFactory.getCurrentUnitOfWork()).isEmpty();
    }

    @Test
    void test_transient_and_permanent_gap_handling() {
        var orderEventsReceived = new ArrayList<PersistedEvent>();
        var ordersSubscriberId  = SubscriberId.of("OrdersSub1");
        var orderEventsFlux = eventStore.pollEvents(ORDERS,
                                                    GlobalEventOrder.FIRST_GLOBAL_EVENT_ORDER,
                                                    Optional.of(10),
                                                    Optional.of(Duration.ofMillis(100)),
                                                    Optional.empty(),
                                                    Optional.of(ordersSubscriberId),
                                                    Optional.empty())
                                        .subscribe(e -> {
                                            orderEventsReceived.add(e);
                                        });

        var testData   = createTestEvents();
        var orderId    = testData._1;
        var testEvents = testData._2;
        assertThat(testEvents.size()).isEqualTo(7);

        var firstEventPersisted        = new CountDownLatch(1);
        var eventsTwoToFourPersisted   = new CountDownLatch(1);
        var eventsSixAndSevenPersisted = new CountDownLatch(1);
        var eventsFiveRolledBack       = new CountDownLatch(1);

        var executor = Executors.newFixedThreadPool(3);
        executor.execute(() -> {
            Thread.currentThread().setName("Append events 2-4");
            try {
                log.info("*** Waiting for Event 1 to be persisted and committed to the event store");
                firstEventPersisted.await();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new RuntimeException(e);
            }
            // Persist events (1 based) number 2-4 AFTER event number 1 has been persisted.
            // (event 5 will be persisted and rolled back and re-persisted to ensure global order 5 is marked as a permanent gap and the re-persisted event number 5 will be the last to be delivered)
            var unitOfWork = unitOfWorkFactory.getOrCreateNewUnitOfWork();
            log.info("*** Appending events 2-4 out of 7 (1 based) to Stream");
            var aggregateEventStream = eventStore.appendToStream(aggregateType,
                                                                 orderId,
                                                                 testEvents.subList(1, 4));
            assertThat((CharSequence) aggregateEventStream.aggregateId()).isEqualTo(orderId);
            assertThat(aggregateEventStream.isPartialEventStream()).isTrue();
            assertThat(aggregateEventStream.eventList().size()).isEqualTo(3);
            log.info("*** Appending Events 2-4 resulted in (event-order, global-order): {}", aggregateEventStream.eventList().stream().map(persistedEvent -> "(" + persistedEvent.eventOrder() + ", " + persistedEvent.globalEventOrder() + ")").reduce((s, s2) -> s + ", " + s2).get());
            assertThat(aggregateEventStream.eventList().get(0).eventOrder().longValue()).isEqualTo(1);
            assertThat(aggregateEventStream.eventList().get(0).globalEventOrder().longValue()).isEqualTo(2);
            assertThat(aggregateEventStream.eventList().get(1).eventOrder().longValue()).isEqualTo(2);
            assertThat(aggregateEventStream.eventList().get(1).globalEventOrder().longValue()).isEqualTo(3);
            assertThat(aggregateEventStream.eventList().get(2).eventOrder().longValue()).isEqualTo(3);
            assertThat(aggregateEventStream.eventList().get(2).globalEventOrder().longValue()).isEqualTo(4);

            log.info("*** Committing Append of events 2-4 out of 7 (1 based)");
            unitOfWork.commit();

            eventsTwoToFourPersisted.countDown();
            Thread.currentThread().setName("Re-Append event 5");
            try {
                log.info("*** Waiting for Events 6-7 to be persisted and committed to the event store");
                eventsSixAndSevenPersisted.await();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new RuntimeException(e);
            }

            // Append event 5 again
            unitOfWork = unitOfWorkFactory.getOrCreateNewUnitOfWork();
            log.info("*** Appending events 5 out of 7 (1 based) to Stream");
            aggregateEventStream = eventStore.appendToStream(aggregateType,
                                                             orderId,
                                                             testEvents.subList(4, 5));
            assertThat((CharSequence) aggregateEventStream.aggregateId()).isEqualTo(orderId);
            assertThat(aggregateEventStream.isPartialEventStream()).isTrue();
            assertThat(aggregateEventStream.eventList().size()).isEqualTo(1);
            log.info("*** Appending Event 5 resulted in (event-order, global-order): {}", aggregateEventStream.events().map(persistedEvent -> "(" + persistedEvent.eventOrder() + ", " + persistedEvent.globalEventOrder() + ")").reduce((s, s2) -> s + ", " + s2).get());
            assertThat(aggregateEventStream.eventList().get(0).eventOrder().longValue()).isEqualTo(6);
            assertThat(aggregateEventStream.eventList().get(0).globalEventOrder().longValue()).isEqualTo(8);
            log.info("*** Committing Append of events 5 out of 7 (1 based)");
            unitOfWork.commit();
        });
        executor.execute(() -> {
            Thread.currentThread().setName("Append and rollback event 5");
            try {
                log.info("*** Waiting for Events 2-4 to be persisted to the event store");
                eventsTwoToFourPersisted.await();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new RuntimeException(e);
            }
            // Append event 5 but rollback it back to create a permanent gap
            var unitOfWork = unitOfWorkFactory.getOrCreateNewUnitOfWork();
            log.info("*** Appending events 5 out of 7 (1 based) to Stream");
            var aggregateEventStream = eventStore.appendToStream(aggregateType,
                                                                 orderId,
                                                                 testEvents.subList(4, 5));
            assertThat((CharSequence) aggregateEventStream.aggregateId()).isEqualTo(orderId);
            assertThat(aggregateEventStream.isPartialEventStream()).isTrue();
            assertThat(aggregateEventStream.eventList().size()).isEqualTo(1);
            log.info("*** Appending Event 5 resulted in (event-order, global-order): {}", aggregateEventStream.events().map(persistedEvent -> "(" + persistedEvent.eventOrder() + ", " + persistedEvent.globalEventOrder() + ")").reduce((s, s2) -> s + ", " + s2).get());
            assertThat(aggregateEventStream.eventList().get(0).eventOrder().longValue()).isEqualTo(4);
            assertThat(aggregateEventStream.eventList().get(0).globalEventOrder().longValue()).isEqualTo(5);
            log.info("*** Rolling back Append of events 5 out of 7 (1 based)");
            unitOfWork.rollback();

            eventsFiveRolledBack.countDown();
        });
        executor.execute(() -> {
            Thread.currentThread().setName("Append events 6-7");
            try {
                log.info("*** Waiting for Event 5 to be appended and rolled back");
                eventsFiveRolledBack.await();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new RuntimeException(e);
            }
            // Persist events (1 based) number events 6-7
            var unitOfWork = unitOfWorkFactory.getOrCreateNewUnitOfWork();
            log.info("*** Appending events 6-7 out of 7 (1 based) to Stream");
            var aggregateEventStream = eventStore.appendToStream(aggregateType,
                                                                 orderId,
                                                                 testEvents.subList(5, 7));
            assertThat((CharSequence) aggregateEventStream.aggregateId()).isEqualTo(orderId);
            assertThat(aggregateEventStream.isPartialEventStream()).isTrue();
            assertThat(aggregateEventStream.eventList().size()).isEqualTo(2);
            log.info("*** Appending Events 6-7 resulted in (event-order, global-order): {}", aggregateEventStream.events().map(persistedEvent -> "(" + persistedEvent.eventOrder() + ", " + persistedEvent.globalEventOrder() + ")").reduce((s, s2) -> s + ", " + s2).get());
            assertThat(aggregateEventStream.eventList().get(0).eventOrder().longValue()).isEqualTo(4);
            assertThat(aggregateEventStream.eventList().get(0).globalEventOrder().longValue()).isEqualTo(6);
            assertThat(aggregateEventStream.eventList().get(1).eventOrder().longValue()).isEqualTo(5);
            assertThat(aggregateEventStream.eventList().get(1).globalEventOrder().longValue()).isEqualTo(7);
            log.info("*** Committing Append of events 6-7 out of 7 (1 based)");
            unitOfWork.commit();
            eventsSixAndSevenPersisted.countDown();
        });


        var unitOfWork = unitOfWorkFactory.getOrCreateNewUnitOfWork();
        log.info("*** Appending event 1 out of 7 (1 based) to Stream");
        var aggregateEventStream = eventStore.appendToStream(aggregateType,
                                                             orderId,
                                                             testEvents.subList(0, 1));
        assertThat((CharSequence) aggregateEventStream.aggregateId()).isEqualTo(orderId);
        assertThat(aggregateEventStream.isPartialEventStream()).isTrue();
        assertThat(aggregateEventStream.eventList().size()).isEqualTo(1);
        log.info("*** Appending Event 1 resulted in (event-order, global-order): {}", aggregateEventStream.events().map(persistedEvent -> "(" + persistedEvent.eventOrder() + ", " + persistedEvent.globalEventOrder() + ")").reduce((s, s2) -> s + ", " + s2).get());
        assertThat(aggregateEventStream.eventList().get(0).eventOrder().longValue()).isEqualTo(0);
        assertThat(aggregateEventStream.eventList().get(0).globalEventOrder().longValue()).isEqualTo(1);
        log.info("*** Committing Append of event 1 out of 7 (1 based)");
        unitOfWork.commit();
        firstEventPersisted.countDown();

        // Verify we received all Order events
        Awaitility.waitAtMost(Duration.ofSeconds(10))
                  .untilAsserted(() -> assertThat(orderEventsReceived.size()).isEqualTo(testEvents.size()));
        assertThat(orderEventsReceived.stream().filter(persistedEvent -> !persistedEvent.aggregateType().equals(ORDERS)).findAny()).isEmpty();
        assertThat(orderEventsReceived.stream()
                                      .map(persistedEvent -> persistedEvent.globalEventOrder().longValue())
                                      .collect(Collectors.toList()))
                .isEqualTo(List.of(1L, 2L, 3L, 4L, 6L, 7L, 8L));

        assertThat(eventStore.getEventStreamGapHandler()
                             .gapHandlerFor(ordersSubscriberId)
                             .getTransientGapsFor(ORDERS)).isEqualTo(List.of(GlobalEventOrder.of(5)));

        Awaitility.waitAtMost(Duration.ofSeconds(5))
                  .untilAsserted(() ->
                                         assertThat(eventStore.getEventStreamGapHandler()
                                                              .getPermanentGapsFor(ORDERS)
                                                              .collect(Collectors.toList())).isEqualTo(List.of(GlobalEventOrder.of(5))));
        assertThat(eventStore.getEventStreamGapHandler()
                             .gapHandlerFor(ordersSubscriberId)
                             .getTransientGapsFor(ORDERS)).isEmpty();

        orderEventsFlux.dispose();
    }

    @Test
    void resolving_transient_gap_only_deletes_rows_for_current_subscriber() {
        var subscriberA = SubscriberId.of("gap-sub-a");
        var subscriberB = SubscriberId.of("gap-sub-b");

        var persistedEvent = unitOfWorkFactory.withUnitOfWork(() -> eventStore.appendToStream(aggregateType,
                                                                                                OrderId.random(),
                                                                                                List.of(new OrderEvent.OrderAccepted(OrderId.random()))))
                                              .eventList()
                                              .get(0);
        var gapGlobalOrder = persistedEvent.globalEventOrder();

        unitOfWorkFactory.withUnitOfWork(unitOfWork -> {
            var now = OffsetDateTime.now();
            unitOfWork.handle()
                      .createUpdate("INSERT INTO " + TRANSIENT_SUBSCRIBER_GAPS_TABLE_NAME + " (subscriber_id, aggregate_type, gap_global_event_order, first_discovered) " +
                                            "VALUES (:subscriber_id, :aggregate_type, :gap_global_event_order, :first_discovered)")
                      .bind("subscriber_id", subscriberA)
                      .bind("aggregate_type", aggregateType)
                      .bind("gap_global_event_order", gapGlobalOrder)
                      .bind("first_discovered", now)
                      .execute();
            unitOfWork.handle()
                      .createUpdate("INSERT INTO " + TRANSIENT_SUBSCRIBER_GAPS_TABLE_NAME + " (subscriber_id, aggregate_type, gap_global_event_order, first_discovered) " +
                                            "VALUES (:subscriber_id, :aggregate_type, :gap_global_event_order, :first_discovered)")
                      .bind("subscriber_id", subscriberB)
                      .bind("aggregate_type", aggregateType)
                      .bind("gap_global_event_order", gapGlobalOrder)
                      .bind("first_discovered", now)
                      .execute();
            return null;
        });

        var subscriberAGapHandler = eventStore.getEventStreamGapHandler().gapHandlerFor(subscriberA);
        var subscriberBGapHandler = eventStore.getEventStreamGapHandler().gapHandlerFor(subscriberB);

        assertThat(subscriberAGapHandler.getTransientGapsFor(aggregateType)).containsExactly(gapGlobalOrder);
        assertThat(subscriberBGapHandler.getTransientGapsFor(aggregateType)).containsExactly(gapGlobalOrder);

        unitOfWorkFactory.withUnitOfWork(unitOfWork -> {
            subscriberAGapHandler.reconcileGaps(aggregateType,
                                                LongRange.only(gapGlobalOrder.longValue()),
                                                List.of(persistedEvent),
                                                List.of(gapGlobalOrder));
            return null;
        });

        assertThat(subscriberAGapHandler.getTransientGapsFor(aggregateType)).isEmpty();
        assertThat(subscriberBGapHandler.getTransientGapsFor(aggregateType)).containsExactly(gapGlobalOrder);

        var remainingRows = unitOfWorkFactory.withUnitOfWork(unitOfWork ->
                unitOfWork.handle()
                          .createQuery("SELECT subscriber_id FROM " + TRANSIENT_SUBSCRIBER_GAPS_TABLE_NAME + " WHERE aggregate_type = :aggregate_type and gap_global_event_order = :gap_global_event_order")
                          .bind("aggregate_type", aggregateType)
                          .bind("gap_global_event_order", gapGlobalOrder)
                          .mapTo(String.class)
                          .list());
        assertThat(remainingRows).containsExactly(subscriberB.toString());
    }

    /**
     * The outcome feeds the subscription gap statistics, so each count must be what the reconciliation actually
     * changed in the database - including zero when the gap was already registered, which is what a repeated query
     * over the same range, or a concurrent non-exclusive reconciler, produces.
     */
    @Test
    void reconciliation_reports_what_it_changed() throws InterruptedException {
        var subscriber = SubscriberId.of("gap-outcome-sub");
        var gapHandler = eventStore.getEventStreamGapHandler().gapHandlerFor(subscriber);
        var events = unitOfWorkFactory.withUnitOfWork(() -> eventStore.appendToStream(aggregateType,
                                                                                        OrderId.random(),
                                                                                        List.of(new OrderEvent.OrderAccepted(OrderId.random()),
                                                                                                new OrderEvent.OrderAccepted(OrderId.random()),
                                                                                                new OrderEvent.OrderAccepted(OrderId.random()))))
                                      .eventList();
        var first  = events.get(0);
        var middle = events.get(1);
        var last   = events.get(2);
        var range  = LongRange.between(first.globalEventOrder().longValue(), last.globalEventOrder().longValue());

        // The middle event is missing from the result: one new transient gap
        var detected = unitOfWorkFactory.withUnitOfWork(unitOfWork -> gapHandler.reconcileGapsAndReport(aggregateType, range, List.of(first, last), List.of()));
        assertThat(detected).isEqualTo(new GapReconciliation(1, 0, 0));

        // The same result again finds the same gap, but it is already registered: nothing new
        var repeated = unitOfWorkFactory.withUnitOfWork(unitOfWork -> gapHandler.reconcileGapsAndReport(aggregateType, range, List.of(first, last), List.of()));
        assertThat(repeated).isEqualTo(GapReconciliation.NONE);

        // Asked for again and returned this time: resolved
        var resolved = unitOfWorkFactory.withUnitOfWork(unitOfWork -> gapHandler.reconcileGapsAndReport(aggregateType,
                                                                                                          LongRange.only(middle.globalEventOrder().longValue()),
                                                                                                          List.of(middle),
                                                                                                          List.of(middle.globalEventOrder())));
        assertThat(resolved).isEqualTo(new GapReconciliation(0, 1, 0));
        assertThat(gapHandler.getTransientGapsFor(aggregateType)).isEmpty();

        // Missing again, and still missing past the promotion threshold: promoted to permanent. thresholdBased(1)
        // promotes once MORE than one whole second has elapsed, so a gap needs two
        unitOfWorkFactory.withUnitOfWork(unitOfWork -> gapHandler.reconcileGapsAndReport(aggregateType, range, List.of(first, last), List.of()));
        Thread.sleep(2_500);
        var promoted = unitOfWorkFactory.withUnitOfWork(unitOfWork -> gapHandler.reconcileGapsAndReport(aggregateType,
                                                                                                          LongRange.only(middle.globalEventOrder().longValue()),
                                                                                                          List.of(),
                                                                                                          List.of(middle.globalEventOrder())));
        assertThat(promoted.promotedToPermanentGaps()).isEqualTo(1);
        List<GlobalEventOrder> permanentGaps = unitOfWorkFactory.withUnitOfWork(unitOfWork -> gapHandler.getPermanentGapsFor(aggregateType).toList());
        assertThat(permanentGaps).contains(middle.globalEventOrder());
    }

    /**
     * A gap is promoted only by a query that asked for it and did not get its event. A reconciler whose query did not
     * include the gap (a bounded or custom include strategy, another node, the CDC delegate poll) must leave it
     * transient: its event may exist, delivered and awaiting acknowledgement - promoting it would drop the gap before
     * the event was handled.
     */
    @Test
    void a_gap_the_query_did_not_ask_for_is_never_promoted_and_is_asked_for_by_the_next_query() throws InterruptedException {
        var subscriber = SubscriberId.of("gap-promotion-requires-query-sub");
        // An include strategy that never asks for any gap
        var gapHandler = new PostgresqlEventStreamGapHandler<SeparateTablePerAggregateEventStreamConfiguration>(unitOfWorkFactory,
                                                                                                               Duration.ofMillis(1000),
                                                                                                               (forAggregateType, range, allTransientGaps) -> NO_TRANSIENT_GAPS,
                                                                                                               ResolveTransientGapsToPermanentGapsPromotionStrategy.thresholdBased(1))
                .gapHandlerFor(subscriber);
        var events = unitOfWorkFactory.withUnitOfWork(() -> eventStore.appendToStream(aggregateType,
                                                                                        OrderId.random(),
                                                                                        List.of(new OrderEvent.OrderAccepted(OrderId.random()),
                                                                                                new OrderEvent.OrderAccepted(OrderId.random()),
                                                                                                new OrderEvent.OrderAccepted(OrderId.random()))))
                                      .eventList();
        var first  = events.get(0);
        var middle = events.get(1);
        var last   = events.get(2);
        var range  = LongRange.between(first.globalEventOrder().longValue(), last.globalEventOrder().longValue());

        unitOfWorkFactory.withUnitOfWork(unitOfWork -> gapHandler.reconcileGapsAndReport(aggregateType, range, List.of(first, last), List.of()));
        assertThat(gapHandler.getTransientGapsFor(aggregateType)).containsExactly(middle.globalEventOrder());
        Thread.sleep(2_500);

        // Old enough to be promoted, but this query did not ask for it
        var notAskedFor = unitOfWorkFactory.withUnitOfWork(unitOfWork -> gapHandler.reconcileGapsAndReport(aggregateType, LongRange.from(last.globalEventOrder().longValue() + 1), List.of(), List.of()));
        assertThat(notAskedFor.promotedToPermanentGaps()).isZero();
        assertThat(gapHandler.getTransientGapsFor(aggregateType)).containsExactly(middle.globalEventOrder());
        assertThat(permanentGaps(gapHandler)).doesNotContain(middle.globalEventOrder());

        // ... so the next query asks for it, whatever the include strategy says
        var queried = unitOfWorkFactory.withUnitOfWork(unitOfWork -> gapHandler.findTransientGapsToIncludeInQuery(aggregateType, range));
        assertThat(queried).containsExactly(middle.globalEventOrder());

        // If its event is there it is resolved, never promoted
        var delivered = unitOfWorkFactory.withUnitOfWork(unitOfWork -> gapHandler.reconcileGapsAndReport(aggregateType, LongRange.only(middle.globalEventOrder().longValue()), List.of(middle), queried));
        assertThat(delivered).isEqualTo(new GapReconciliation(0, 1, 0));
        assertThat(permanentGaps(gapHandler)).doesNotContain(middle.globalEventOrder());
    }

    @Test
    void a_gap_the_query_asked_for_and_did_not_get_is_promoted() throws InterruptedException {
        var subscriber = SubscriberId.of("gap-promotion-asked-for-sub");
        var gapHandler = eventStore.getEventStreamGapHandler().gapHandlerFor(subscriber);
        var events = unitOfWorkFactory.withUnitOfWork(() -> eventStore.appendToStream(aggregateType,
                                                                                        OrderId.random(),
                                                                                        List.of(new OrderEvent.OrderAccepted(OrderId.random()),
                                                                                                new OrderEvent.OrderAccepted(OrderId.random()),
                                                                                                new OrderEvent.OrderAccepted(OrderId.random()))))
                                      .eventList();
        var first  = events.get(0);
        var middle = events.get(1);
        var last   = events.get(2);
        var range  = LongRange.between(first.globalEventOrder().longValue(), last.globalEventOrder().longValue());

        unitOfWorkFactory.withUnitOfWork(unitOfWork -> gapHandler.reconcileGapsAndReport(aggregateType, range, List.of(first, last), List.of()));
        Thread.sleep(2_500);

        var queried  = unitOfWorkFactory.withUnitOfWork(unitOfWork -> gapHandler.findTransientGapsToIncludeInQuery(aggregateType, range));
        var promoted = unitOfWorkFactory.withUnitOfWork(unitOfWork -> gapHandler.reconcileGapsAndReport(aggregateType, range, List.of(first, last), queried));

        assertThat(promoted.promotedToPermanentGaps()).isEqualTo(1);
        assertThat(permanentGaps(gapHandler)).contains(middle.globalEventOrder());
    }

    /**
     * A subscription that tracks gaps itself (CDC) gives a gap up after the promotion strategy's threshold: the gap is
     * promoted then, without waiting for the strategy to see it as old enough by its own clock. A given-up order that is
     * no transient gap of the subscriber - resolved or promoted meanwhile - is not recorded as a permanent gap.
     */
    @Test
    void a_gap_given_up_after_the_strategy_s_threshold_is_promoted_at_once() {
        var subscriber = SubscriberId.of("gap-given-up-sub");
        var gapHandler = new PostgresqlEventStreamGapHandler<SeparateTablePerAggregateEventStreamConfiguration>(unitOfWorkFactory,
                                                                                                               Duration.ofMillis(1000),
                                                                                                               ResolveTransientGapsToIncludeInQueryStrategy.defaultSelection(),
                                                                                                               ResolveTransientGapsToPermanentGapsPromotionStrategy.thresholdBased(120))
                .gapHandlerFor(subscriber);
        var events = appendEvents(4);
        var first  = events.get(0);
        var second = events.get(1);
        var third  = events.get(2);
        var last   = events.get(3);
        var range  = LongRange.between(first.globalEventOrder().longValue(), last.globalEventOrder().longValue());
        unitOfWorkFactory.withUnitOfWork(unitOfWork -> gapHandler.reconcileGapsAndReport(aggregateType, range, List.of(first, last), List.of()));
        assertThat(gapHandler.getTransientGapsFor(aggregateType)).containsExactly(second.globalEventOrder(), third.globalEventOrder());
        var noTransientGap = GlobalEventOrder.of(last.globalEventOrder().longValue() + 100);

        var givenUp = unitOfWorkFactory.withUnitOfWork(unitOfWork -> gapHandler.giveUpTransientGaps(aggregateType, List.of(second.globalEventOrder(), noTransientGap)));

        assertThat(givenUp).isEqualTo(new GapReconciliation(0, 0, 1));
        assertThat(gapHandler.getTransientGapsFor(aggregateType)).containsExactly(third.globalEventOrder());
        assertThat(currentTransientGaps(subscriber)).containsExactly(third.globalEventOrder());
        assertThat(permanentGaps(gapHandler)).contains(second.globalEventOrder())
                                             .doesNotContain(third.globalEventOrder(), noTransientGap);
    }

    /**
     * With a promotion strategy that states no threshold the subscription gave the gap up after a default the strategy
     * knows nothing about: only what the strategy itself considers ready is promoted
     */
    @Test
    void a_gap_given_up_is_promoted_only_when_a_strategy_without_a_threshold_considers_it_ready() {
        var subscriber = SubscriberId.of("gap-given-up-custom-strategy-sub");
        var gapHandler = new PostgresqlEventStreamGapHandler<SeparateTablePerAggregateEventStreamConfiguration>(unitOfWorkFactory,
                                                                                                               Duration.ofMillis(1000),
                                                                                                               ResolveTransientGapsToIncludeInQueryStrategy.defaultSelection(),
                                                                                                               (forAggregateType, allTransientGaps) -> List.of())
                .gapHandlerFor(subscriber);
        var events = appendEvents(3);
        var range  = LongRange.between(events.get(0).globalEventOrder().longValue(), events.get(2).globalEventOrder().longValue());
        unitOfWorkFactory.withUnitOfWork(unitOfWork -> gapHandler.reconcileGapsAndReport(aggregateType, range, List.of(events.get(0), events.get(2)), List.of()));

        var givenUp = unitOfWorkFactory.withUnitOfWork(unitOfWork -> gapHandler.giveUpTransientGaps(aggregateType, List.of(events.get(1).globalEventOrder())));

        assertThat(givenUp.promotedToPermanentGaps()).isZero();
        assertThat(currentTransientGaps(subscriber)).containsExactly(events.get(1).globalEventOrder());
        assertThat(permanentGaps(gapHandler)).doesNotContain(events.get(1).globalEventOrder());
    }

    /**
     * A wide hole in the global order - a rolled back bulk append - is given up one order per missing event, more than
     * PostgreSQL binds into one statement (65 535 parameters): the give-up is deleted in chunks, in one transaction, and
     * recorded in full
     */
    @Test
    void giving_up_more_gaps_than_one_statement_can_bind_promotes_all_of_them() {
        var subscriber = SubscriberId.of("gap-given-up-wide-hole-sub");
        var gapHandler = new PostgresqlEventStreamGapHandler<SeparateTablePerAggregateEventStreamConfiguration>(unitOfWorkFactory,
                                                                                                               Duration.ofSeconds(60),
                                                                                                               ResolveTransientGapsToIncludeInQueryStrategy.defaultSelection(),
                                                                                                               ResolveTransientGapsToPermanentGapsPromotionStrategy.thresholdBased(120))
                .gapHandlerFor(subscriber);
        var numberOfGaps = 70_000;
        unitOfWorkFactory.usingUnitOfWork(unitOfWork -> unitOfWork.handle()
                                                                  .createUpdate("INSERT INTO " + TRANSIENT_SUBSCRIBER_GAPS_TABLE_NAME + " (subscriber_id, aggregate_type, gap_global_event_order, first_discovered) " +
                                                                                        "SELECT :subscriber_id, :aggregate_type, order_, now() FROM generate_series(1, :number_of_gaps) AS order_")
                                                                  .bind("subscriber_id", subscriber)
                                                                  .bind("aggregate_type", aggregateType)
                                                                  .bind("number_of_gaps", numberOfGaps)
                                                                  .execute());
        var givenUp = LongStream.rangeClosed(1, numberOfGaps).mapToObj(GlobalEventOrder::of).toList();

        var outcome = unitOfWorkFactory.withUnitOfWork(unitOfWork -> gapHandler.giveUpTransientGaps(aggregateType, givenUp));

        assertThat(outcome).isEqualTo(new GapReconciliation(0, 0, numberOfGaps));
        assertThat(currentTransientGaps(subscriber)).isEmpty();
        assertThat(permanentGaps(gapHandler)).hasSize(numberOfGaps);
    }

    /**
     * Ranges are given up in one statement, whatever their width: every transient gap of the subscriber within them is
     * promoted, an order within them that is no transient gap is not recorded, and a transient gap outside them is kept
     */
    @Test
    void giving_up_ranges_promotes_exactly_the_transient_gaps_within_them() {
        var subscriber = SubscriberId.of("gap-given-up-ranges-sub");
        var gapHandler = new PostgresqlEventStreamGapHandler<SeparateTablePerAggregateEventStreamConfiguration>(unitOfWorkFactory,
                                                                                                               Duration.ofSeconds(60),
                                                                                                               ResolveTransientGapsToIncludeInQueryStrategy.defaultSelection(),
                                                                                                               ResolveTransientGapsToPermanentGapsPromotionStrategy.thresholdBased(120))
                .gapHandlerFor(subscriber);
        // Transient gaps 1..70 000 and 100 000; nothing at 80 000..90 000
        unitOfWorkFactory.usingUnitOfWork(unitOfWork -> unitOfWork.handle()
                                                                  .createUpdate("INSERT INTO " + TRANSIENT_SUBSCRIBER_GAPS_TABLE_NAME + " (subscriber_id, aggregate_type, gap_global_event_order, first_discovered) " +
                                                                                        "SELECT :subscriber_id, :aggregate_type, order_, now() FROM generate_series(1, 70000) AS order_ " +
                                                                                        "UNION ALL SELECT :subscriber_id, :aggregate_type, 100000, now()")
                                                                  .bind("subscriber_id", subscriber)
                                                                  .bind("aggregate_type", aggregateType)
                                                                  .execute());

        var outcome = unitOfWorkFactory.withUnitOfWork(unitOfWork -> gapHandler.giveUpTransientGapRanges(aggregateType,
                                                                                                         List.of(LongRange.between(1, 70_000), LongRange.between(80_000, 90_000))));

        assertThat(outcome).isEqualTo(new GapReconciliation(0, 0, 70_000));
        assertThat(currentTransientGaps(subscriber)).containsExactly(GlobalEventOrder.of(100_000));
        assertThat(gapHandler.getTransientGapsFor(aggregateType)).containsExactly(GlobalEventOrder.of(100_000));
        var permanent = permanentGaps(gapHandler);
        assertThat(permanent).hasSize(70_000)
                             .doesNotContain(GlobalEventOrder.of(80_000), GlobalEventOrder.of(100_000));
    }

    /**
     * Recording a range of transient gaps records each order once, except those that are permanent gaps of the
     * aggregate type already, and changes no other gap
     */
    @Test
    void adding_transient_gaps_records_the_orders_that_are_no_permanent_gaps() {
        var eventStreamGapHandler = new PostgresqlEventStreamGapHandler<SeparateTablePerAggregateEventStreamConfiguration>(unitOfWorkFactory,
                                                                                                                          Duration.ofSeconds(60),
                                                                                                                          ResolveTransientGapsToIncludeInQueryStrategy.defaultSelection(),
                                                                                                                          ResolveTransientGapsToPermanentGapsPromotionStrategy.thresholdBased(120));
        var subscriber = SubscriberId.of("gap-added-range-sub");
        var gapHandler = eventStreamGapHandler.gapHandlerFor(subscriber);
        eventStreamGapHandler.registerPermanentGaps(aggregateType, List.of(GlobalEventOrder.of(1_002)), "test");

        var added = unitOfWorkFactory.withUnitOfWork(unitOfWork -> gapHandler.addTransientGaps(aggregateType, LongRange.between(1_000, 1_004)));
        var again = unitOfWorkFactory.withUnitOfWork(unitOfWork -> gapHandler.addTransientGaps(aggregateType, LongRange.between(1_003, 1_005)));

        assertThat(added).isEqualTo(new GapReconciliation(4, 0, 0));
        assertThat(again).as("1 003 and 1 004 were recorded already").isEqualTo(new GapReconciliation(1, 0, 0));
        var expected = List.of(GlobalEventOrder.of(1_000), GlobalEventOrder.of(1_001), GlobalEventOrder.of(1_003), GlobalEventOrder.of(1_004), GlobalEventOrder.of(1_005));
        assertThat(currentTransientGaps(subscriber)).containsExactlyElementsOf(expected);
        assertThat(gapHandler.getTransientGapsFor(aggregateType)).containsExactlyElementsOf(expected);
    }

    /**
     * A give-up records a permanent gap of the aggregate type, as a polling promotion does - not one of the subscriber
     * that gave up alone: another subscriber that reconciles a range spanning it afterwards does not record it as a
     * transient gap, so never asks for it. A gap nobody gave up still becomes its transient gap.
     */
    @Test
    void a_gap_one_subscriber_gave_up_is_skipped_by_every_other_subscriber_of_the_aggregate_type() {
        var eventStreamGapHandler = new PostgresqlEventStreamGapHandler<SeparateTablePerAggregateEventStreamConfiguration>(unitOfWorkFactory,
                                                                                                                          Duration.ofSeconds(60),
                                                                                                                          ResolveTransientGapsToIncludeInQueryStrategy.defaultSelection(),
                                                                                                                          ResolveTransientGapsToPermanentGapsPromotionStrategy.thresholdBased(120));
        var givingUp    = eventStreamGapHandler.gapHandlerFor(SubscriberId.of("gap-given-up-by-this-sub"));
        var anotherOne  = eventStreamGapHandler.gapHandlerFor(SubscriberId.of("gap-given-up-by-another-sub"));
        var events      = appendEvents(4);
        var first       = events.get(0);
        var givenUpGap  = events.get(1).globalEventOrder();
        var keptGap     = events.get(2).globalEventOrder();
        var last        = events.get(3);
        var range       = LongRange.between(first.globalEventOrder().longValue(), last.globalEventOrder().longValue());
        unitOfWorkFactory.withUnitOfWork(unitOfWork -> givingUp.reconcileGapsAndReport(aggregateType, range, List.of(first, last), List.of()));

        unitOfWorkFactory.withUnitOfWork(unitOfWork -> givingUp.giveUpTransientGaps(aggregateType, List.of(givenUpGap)));
        var reconciled = unitOfWorkFactory.withUnitOfWork(unitOfWork -> anotherOne.reconcileGapsAndReport(aggregateType, range, List.of(first, last), List.of()));

        assertThat(reconciled.newTransientGaps()).isEqualTo(1);
        assertThat(anotherOne.getTransientGapsFor(aggregateType)).containsExactly(keptGap);
        assertThat(transientGapsToIncludeInQuery(anotherOne, LongRange.from(last.globalEventOrder().longValue() + 1))).containsExactly(keptGap);
        assertThat(permanentGaps(anotherOne)).contains(givenUpGap);
    }

    /**
     * The default selection rotates through more than 50 gaps per subscription - also when the include strategy only
     * wraps it, so the gap handler cannot recognise it: two subscriptions, one polling twice as often, each see the
     * rotation of their own
     */
    @Test
    void a_wrapped_default_selection_keeps_a_rotation_per_subscription() {
        var defaultSelection = ResolveTransientGapsToIncludeInQueryStrategy.defaultSelection();
        var eventStreamGapHandler = new PostgresqlEventStreamGapHandler<SeparateTablePerAggregateEventStreamConfiguration>(unitOfWorkFactory,
                                                                                                                          Duration.ofSeconds(60),
                                                                                                                          (type, range, gaps) -> defaultSelection.resolveTransientGaps(type, range, gaps),
                                                                                                                          ResolveTransientGapsToPermanentGapsPromotionStrategy.thresholdBased(120));
        var first  = eventStreamGapHandler.gapHandlerFor(SubscriberId.of("gap-rotation-first-sub"));
        var second = eventStreamGapHandler.gapHandlerFor(SubscriberId.of("gap-rotation-second-sub"));
        var events = appendEvents(102);
        var range  = LongRange.between(events.getFirst().globalEventOrder().longValue(), events.getLast().globalEventOrder().longValue());
        unitOfWorkFactory.usingUnitOfWork(unitOfWork -> {
            first.reconcileGapsAndReport(aggregateType, range, List.of(events.getFirst(), events.getLast()), List.of());
            second.reconcileGapsAndReport(aggregateType, range, List.of(events.getFirst(), events.getLast()), List.of());
        });
        var allGaps = events.subList(1, 101).stream()
                            .map(event -> Pair.of(event.globalEventOrder(), OffsetDateTime.now()))
                            .collect(Collectors.toList());
        var firstReference  = new TransientGapsQuerySelection();
        var secondReference = new TransientGapsQuerySelection();
        var nextRange       = LongRange.from(events.getLast().globalEventOrder().longValue() + 1);

        for (var poll = 0; poll < 4; poll++) {
            assertThat(transientGapsToIncludeInQuery(first, nextRange))
                    .isEqualTo(firstReference.select(allGaps));
            assertThat(transientGapsToIncludeInQuery(second, nextRange))
                    .isEqualTo(secondReference.select(allGaps));
            assertThat(transientGapsToIncludeInQuery(second, nextRange))
                    .isEqualTo(secondReference.select(allGaps));
        }
    }

    private List<GlobalEventOrder> transientGapsToIncludeInQuery(SubscriptionGapHandler gapHandler, LongRange range) {
        List<GlobalEventOrder> gaps = unitOfWorkFactory.withUnitOfWork(unitOfWork -> gapHandler.findTransientGapsToIncludeInQuery(aggregateType, range));
        return gaps;
    }

    private List<PersistedEvent> appendEvents(int count) {
        var orderId = OrderId.random();
        var events  = new ArrayList<OrderEvent>();
        for (var i = 0; i < count; i++) {
            events.add(new OrderEvent.OrderAccepted(orderId));
        }
        return unitOfWorkFactory.withUnitOfWork(() -> eventStore.appendToStream(aggregateType, orderId, events))
                                .eventList();
    }

    /**
     * A gap handler built on another {@link EventStoreUnitOfWorkFactory} than the event store's resolves in a
     * transaction of its own: it commits even when the caller's unit of work rolls back. It must work (and warn once)
     * rather than fail, and the same handler on the event store's factory must roll back with the caller.
     */
    @Test
    void a_gap_handler_on_another_unit_of_work_factory_resolves_in_its_own_transaction() {
        var subscriber = SubscriberId.of("gap-foreign-uow-sub");
        var events = unitOfWorkFactory.withUnitOfWork(() -> eventStore.appendToStream(aggregateType,
                                                                                        OrderId.random(),
                                                                                        List.of(new OrderEvent.OrderAccepted(OrderId.random()),
                                                                                                new OrderEvent.OrderAccepted(OrderId.random()),
                                                                                                new OrderEvent.OrderAccepted(OrderId.random()))))
                                      .eventList();
        var first  = events.get(0);
        var middle = events.get(1);
        var last   = events.get(2);
        var range  = LongRange.between(first.globalEventOrder().longValue(), last.globalEventOrder().longValue());

        var sameFactoryHandler    = eventStore.getEventStreamGapHandler().gapHandlerFor(subscriber);
        var foreignFactoryHandler = new PostgresqlEventStreamGapHandler<SeparateTablePerAggregateEventStreamConfiguration>(new EventStoreManagedUnitOfWorkFactory(jdbi)).gapHandlerFor(subscriber);
        unitOfWorkFactory.withUnitOfWork(unitOfWork -> sameFactoryHandler.reconcileGapsAndReport(aggregateType, range, List.of(first, last), List.of()));
        assertThat(sameFactoryHandler.getTransientGapsFor(aggregateType)).containsExactly(middle.globalEventOrder());

        // Same factory: resolved in the caller's unit of work, which rolls back
        assertThatThrownBy(() -> unitOfWorkFactory.usingUnitOfWork(unitOfWork -> {
            sameFactoryHandler.resolveFilledGaps(aggregateType, List.of(middle));
            throw new IllegalStateException("rollback");
        })).hasRootCauseInstanceOf(IllegalStateException.class);
        assertThat(currentTransientGaps(subscriber)).containsExactly(middle.globalEventOrder());

        // Another factory: works, but in a transaction of its own - the gap is gone though the caller rolled back
        assertThatThrownBy(() -> unitOfWorkFactory.usingUnitOfWork(unitOfWork -> {
            var outcome = foreignFactoryHandler.resolveFilledGaps(aggregateType, List.of(middle));
            assertThat(outcome.resolvedTransientGaps()).isEqualTo(1);
            throw new IllegalStateException("rollback");
        })).hasRootCauseInstanceOf(IllegalStateException.class);
        assertThat(currentTransientGaps(subscriber)).isEmpty();
    }

    private List<GlobalEventOrder> permanentGaps(SubscriptionGapHandler gapHandler) {
        List<GlobalEventOrder> gaps = unitOfWorkFactory.withUnitOfWork(unitOfWork -> gapHandler.getPermanentGapsFor(aggregateType).toList());
        return gaps;
    }

    private List<GlobalEventOrder> currentTransientGaps(SubscriberId subscriber) {
        return unitOfWorkFactory.withUnitOfWork(unitOfWork -> unitOfWork.handle()
                                                                         .createQuery("SELECT gap_global_event_order FROM " + TRANSIENT_SUBSCRIBER_GAPS_TABLE_NAME + " WHERE aggregate_type = :aggregate_type AND subscriber_id = :subscriber_id")
                                                                         .bind("aggregate_type", aggregateType)
                                                                         .bind("subscriber_id", subscriber)
                                                                         .mapTo(GlobalEventOrder.class)
                                                                         .list());
    }

    private Pair<OrderId, List<? extends OrderEvent>> createTestEvents() {
        var orderId   = OrderId.random();
        var productId = ProductId.random();
        var orderEvents = List.of(
                new OrderEvent.OrderAdded(orderId, CustomerId.random(), 100),
                new OrderEvent.ProductAddedToOrder(orderId, productId, 3),
                new OrderEvent.ProductAddedToOrder(orderId, ProductId.random(), 1),
                new OrderEvent.ProductOrderQuantityAdjusted(orderId, productId, 10),
                new OrderEvent.ProductRemovedFromOrder(orderId, productId),
                new OrderEvent.ProductAddedToOrder(orderId, ProductId.random(), 1),
                new OrderEvent.OrderAccepted(orderId));
        return Pair.of(orderId, orderEvents);
    }


    private ObjectMapper createObjectMapper() {
        return EssentialsObjectMappers.createJackson3ObjectMapper();
    }

    private static class TestPersistableEventMapper implements PersistableEventMapper {
        private final CorrelationId correlationId   = CorrelationId.random();
        private final EventId       causedByEventId = EventId.random();

        @Override
        public PersistableEvent map(Object aggregateId,
                                    AggregateEventStreamConfiguration aggregateEventStreamConfiguration,
                                    Object event,
                                    EventOrder eventOrder) {
            return PersistableEvent.from(EventId.random(),
                                         aggregateEventStreamConfiguration.aggregateType,
                                         aggregateId,
                                         EventTypeOrName.with(event.getClass()),
                                         event,
                                         eventOrder,
                                         EventRevision.of(1),
                                         new EventMetaData(),
                                         OffsetDateTime.now(),
                                         causedByEventId,
                                         correlationId,
                                         null);
        }
    }
}
