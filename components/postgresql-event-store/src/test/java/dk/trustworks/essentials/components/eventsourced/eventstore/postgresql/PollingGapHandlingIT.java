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
import dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.test_data.*;
import dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.transaction.*;
import dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.types.*;
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
import reactor.core.publisher.BaseSubscriber;

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
    /**
     * The tenant {@link TenantPersistableEventMapper} stamps on the next event appended; {@code null} = no tenant
     */
    private volatile TenantId                                                       tenantOfNextEvent;

    @BeforeEach
    void setup() {
        var jdbi = Jdbi.create(postgreSQLContainer.getJdbcUrl(),
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
                                         .setEventStoreSubscriptionObserver(new EventStoreSubscriptionObserver.NoOpEventStoreSubscriptionObserver())
                                         .build();
        executor = Executors.newCachedThreadPool();
        tenantOfNextEvent = null;
    }

    @AfterEach
    void cleanup() {
        if (subscription != null) {
            subscription.dispose();
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

    // -------------------------------------------------------------------------------------------------------------------------------------------------

    private Disposable subscribe(PollingMode pollingMode, SubscriberId subscriberId, Optional<Tenant> tenant, List<Long> received) {
        var flux = switch (pollingMode) {
            case POLL_EVENTS -> eventStore.pollEvents(aggregateType,
                                                      GlobalEventOrder.FIRST_GLOBAL_EVENT_ORDER,
                                                      Optional.of(10),
                                                      Optional.of(POLLING_INTERVAL),
                                                      tenant,
                                                      Optional.of(subscriberId),
                                                      Optional.empty());
            case UNBOUNDED_POLL_FOR_EVENTS -> eventStore.unboundedPollForEvents(aggregateType,
                                                                                GlobalEventOrder.FIRST_GLOBAL_EVENT_ORDER.longValue(),
                                                                                Optional.of(10),
                                                                                Optional.of(POLLING_INTERVAL),
                                                                                tenant,
                                                                                Optional.of(subscriberId));
        };
        return flux.subscribe(event -> received.add(event.globalEventOrder().longValue()));
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
        var appended    = new CompletableFuture<Long>();
        var mayCommit   = new CountDownLatch(1);
        var committed   = new CompletableFuture<Void>();
        executor.execute(() -> {
            try {
                var unitOfWork = unitOfWorkFactory.getOrCreateNewUnitOfWork();
                var globalOrder = eventStore.appendToStream(aggregateType, OrderId.random(), List.of(new OrderEvent.OrderAccepted(OrderId.random())))
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
