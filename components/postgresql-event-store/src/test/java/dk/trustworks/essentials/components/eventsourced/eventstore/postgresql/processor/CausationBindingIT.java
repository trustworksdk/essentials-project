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

package dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.processor;

import dk.trustworks.essentials.components.distributed.fencedlock.postgresql.PostgresqlFencedLockManager;
import dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.PostgresqlEventStore;
import dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.eventstream.*;
import dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.gap.PostgresqlEventStreamGapHandler;
import dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.observability.EventStoreSubscriptionObserver;
import dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.persistence.table_per_aggregate_type.*;
import dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.processor.EventProcessorIT.OrderId;
import dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.serializer.AggregateIdSerializer;
import dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.serializer.json.EssentialsJSONEventSerializers;
import dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.subscription.*;
import dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.transaction.*;
import dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.persistence.PersistableEvent;
import dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.types.*;
import dk.trustworks.essentials.components.foundation.causation.CausationContext;
import dk.trustworks.essentials.components.foundation.messaging.*;
import dk.trustworks.essentials.components.foundation.messaging.eip.store_and_forward.Inboxes;
import dk.trustworks.essentials.components.foundation.reactive.command.*;
import dk.trustworks.essentials.components.foundation.transaction.*;
import dk.trustworks.essentials.components.foundation.types.*;
import dk.trustworks.essentials.components.queue.postgresql.PostgresqlDurableQueues;
import org.jdbi.v3.core.Jdbi;
import org.jdbi.v3.postgres.PostgresPlugin;
import org.junit.jupiter.api.*;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.*;
import org.testcontainers.shaded.org.awaitility.Awaitility;

import java.time.Duration;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicReference;

import static dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.persistence.table_per_aggregate_type.SeparateTablePerAggregateTypeEventStreamConfigurationFactory.standardSingleTenantConfiguration;
import static org.assertj.core.api.Assertions.*;

/**
 * Every framework delivery site that holds the {@link PersistedEvent} being delivered binds its {@link EventId} in
 * {@link CausationContext} around the handler - and, where the site owns the {@link UnitOfWork}, around its commit
 * too, which is where lazily appended events are written.
 * <p>
 * The {@link CausationPersistableEventEnricher} turns the binding into the persisted {@code caused_by_event_id}; the
 * write-path tests check that end to end.
 */
@Testcontainers
class CausationBindingIT {
    private static final AggregateType ORDERS    = AggregateType.of("Orders");
    private static final AggregateType SHIPMENTS = AggregateType.of("Shipments");
    private static final Duration      WAIT   = Duration.ofSeconds(15);

    @Container
    private static final PostgreSQLContainer<?> postgreSQLContainer = new PostgreSQLContainer<>("postgres:18.4")
            .withDatabaseName("event-store")
            .withUsername("test-user")
            .withPassword("secret-password");

    private Jdbi                                                                    jdbi;
    private EventStoreUnitOfWorkFactory<EventStoreUnitOfWork>                       unitOfWorkFactory;
    private PostgresqlEventStore<SeparateTablePerAggregateEventStreamConfiguration> eventStore;
    private EventStoreSubscriptionManager                                           eventStoreSubscriptionManager;
    private PostgresqlFencedLockManager                                             fencedLockManager;
    private PostgresqlDurableQueues                                                 durableQueues;
    private DurableLocalCommandBus                                                  commandBus;
    private final List<Runnable>                                                    stopActions = new ArrayList<>();

    /**
     * What each handler saw, keyed by "where it looked"
     */
    private final Map<String, Optional<EventId>> observed = new ConcurrentHashMap<>();

    @BeforeEach
    void setup() {
        jdbi = Jdbi.create(postgreSQLContainer.getJdbcUrl(),
                           postgreSQLContainer.getUsername(),
                           postgreSQLContainer.getPassword());
        jdbi.installPlugin(new PostgresPlugin());
        // The container is shared by the class, so start every test from an empty database
        jdbi.useHandle(handle -> handle.execute("DROP SCHEMA public CASCADE; CREATE SCHEMA public"));

        unitOfWorkFactory = new EventStoreManagedUnitOfWorkFactory(jdbi);
        var jsonSerializer = EssentialsJSONEventSerializers.create();
        // A mapper that sets no cause, so the enricher decides it - as with the starter's default mapper
        var persistenceStrategy = SeparateTablePerAggregateTypePersistenceStrategy.builder()
                                                                                  .setJdbi(jdbi)
                                                                                  .setUnitOfWorkFactory(unitOfWorkFactory)
                                                                                  .setEventMapper((aggregateId, configuration, event, eventOrder) ->
                                                                                                          PersistableEvent.builder()
                                                                                                                          .setEvent(event)
                                                                                                                          .setAggregateType(configuration.aggregateType)
                                                                                                                          .setAggregateId(aggregateId)
                                                                                                                          .setEventTypeOrName(EventTypeOrName.with(event.getClass()))
                                                                                                                          .setEventOrder(eventOrder)
                                                                                                                          .build())
                                                                                  .setAggregateEventStreamConfigurationFactory(standardSingleTenantConfiguration(
                                                                                          jsonSerializer,
                                                                                          IdentifierColumnType.UUID,
                                                                                          JSONColumnType.JSONB))
                                                                                  .setPersistableEventEnrichers(List.of(new CausationPersistableEventEnricher()))
                                                                                  .build();
        eventStore = PostgresqlEventStore.<SeparateTablePerAggregateEventStreamConfiguration>builder()
                                         .setUnitOfWorkFactory(unitOfWorkFactory)
                                         .setPersistenceStrategy(persistenceStrategy)
                                         .setEventStreamGapHandlerFactory(eventStore -> new PostgresqlEventStreamGapHandler<>(unitOfWorkFactory))
                                         .setEventStoreSubscriptionObserver(new EventStoreSubscriptionObserver.NoOpEventStoreSubscriptionObserver())
                                         .build();
        eventStore.addAggregateEventStreamConfiguration(ORDERS, AggregateIdSerializer.serializerFor(OrderId.class));
        eventStore.addAggregateEventStreamConfiguration(SHIPMENTS, AggregateIdSerializer.serializerFor(OrderId.class));

        fencedLockManager = PostgresqlFencedLockManager.builder()
                                                       .setEventBus(eventStore.localEventBus())
                                                       .setJdbi(jdbi)
                                                       .setLockTimeOut(Duration.ofSeconds(2))
                                                       .setLockConfirmationInterval(Duration.ofSeconds(1))
                                                       .setReleaseAcquiredLocksInCaseOfIOExceptionsDuringLockConfirmation(true)
                                                       .setUnitOfWorkFactory(unitOfWorkFactory)
                                                       .buildAndStart();

        eventStoreSubscriptionManager = EventStoreSubscriptionManager.builder()
                                                                     .setEventStore(eventStore)
                                                                     .setFencedLockManager(fencedLockManager)
                                                                     .setDurableSubscriptionRepository(new PostgresqlDurableSubscriptionRepository(jdbi, eventStore))
                                                                     .setSnapshotResumePointsEvery(Duration.ofSeconds(1))
                                                                     .build();
        eventStoreSubscriptionManager.start();

        durableQueues = PostgresqlDurableQueues.builder()
                                               .setJsonSerializer(jsonSerializer)
                                               .setUnitOfWorkFactory(unitOfWorkFactory)
                                               .build();
        durableQueues.start();

        commandBus = DurableLocalCommandBus.builder()
                                           .setInterceptors(new UnitOfWorkControllingCommandBusInterceptor(unitOfWorkFactory))
                                           .setDurableQueues(durableQueues)
                                           .build();
        commandBus.start();
    }

    @AfterEach
    void teardown() {
        stopActions.reversed().forEach(Runnable::run);
        stopActions.clear();
        eventStoreSubscriptionManager.stop();
        fencedLockManager.stop();
        commandBus.stop();
        durableQueues.stop();
    }

    // ------------------------------------------------------------------------------------------------ EventProcessor

    @Test
    void an_EventProcessor_REQUIRED_handler_sees_the_delivered_event_as_cause_while_handling_and_while_committing() {
        start(new RecordingEventProcessor(eventProcessorDependencies()));

        var delivered = append(new OrderPlaced(OrderId.random()));

        awaitObserved("REQUIRED.handler", "REQUIRED.beforeCommit");
        assertThat(observed.get("REQUIRED.handler")).contains(delivered);
        assertThat(observed.get("REQUIRED.beforeCommit")).contains(delivered);
    }

    @Test
    void an_EventProcessor_NONE_handler_sees_the_delivered_event_as_cause_before_and_inside_its_own_UnitOfWork() {
        start(new RecordingEventProcessor(eventProcessorDependencies()));

        var delivered = append(new OrderShipped(OrderId.random()));

        awaitObserved("NONE.handler", "NONE.beforeCommit");
        assertThat(observed.get("NONE.handler")).contains(delivered);
        assertThat(observed.get("NONE.beforeCommit")).contains(delivered);
    }

    @Test
    void an_explicit_binding_inside_a_handler_overrides_the_delivered_cause() {
        start(new RecordingEventProcessor(eventProcessorDependencies()));

        append(new OrderCancelled(OrderId.random()));

        awaitObserved("explicit.handler");
        assertThat(observed.get("explicit.handler")).contains(RecordingEventProcessor.EXPLICIT_CAUSE);
    }

    @Test
    void the_appending_thread_sees_no_cause_after_the_event_has_been_delivered() {
        start(new RecordingEventProcessor(eventProcessorDependencies()));

        append(new OrderPlaced(OrderId.random()));

        awaitObserved("REQUIRED.handler");
        assertThat(CausationContext.current()).isEmpty();
    }

    @Test
    void an_event_appended_by_a_handler_is_persisted_with_the_delivered_event_as_its_cause() {
        start(new RecordingEventProcessor(eventProcessorDependencies()));
        var orderId = OrderId.random();

        var delivered = append(new OrderConfirmed(orderId));

        Awaitility.waitAtMost(WAIT).untilAsserted(() -> assertThat(shipmentEvents(orderId)).hasSize(1));
        assertThat(shipmentEvents(orderId).getFirst().causedByEventId()).contains(delivered);
    }

    @Test
    void an_event_appended_with_no_cause_bound_is_persisted_without_one() {
        var orderId = OrderId.random();
        append(new OrderPlaced(orderId));

        var persisted = unitOfWorkFactory.withUnitOfWork(() -> eventStore.fetchStream(ORDERS, orderId).orElseThrow().eventList());

        assertThat(persisted.getFirst().causedByEventId()).isEmpty();
    }

    // -------------------------------------------------------------------------------------------- ViewEventProcessor

    @Test
    void a_ViewEventProcessor_handler_on_the_direct_path_sees_the_delivered_event_as_cause() {
        start(new RecordingViewEventProcessor(ViewEventProcessorDependencies.builder()
                                                                            .setEventStoreSubscriptionManager(eventStoreSubscriptionManager)
                                                                            .setFencedLockManager(fencedLockManager)
                                                                            .setDurableQueues(durableQueues)
                                                                            .setCommandBus(commandBus)
                                                                            .setMessageHandlerInterceptors(List.of())
                                                                            .build()));

        var delivered = append(new OrderPlaced(OrderId.random()));

        awaitObserved("view.handler");
        assertThat(observed.get("view.handler")).contains(delivered);
    }

    // --------------------------------------------------------------------------------- InTransactionEventProcessor

    @Test
    void an_InTransactionEventProcessor_handler_sees_the_delivered_event_as_cause() {
        start(new RecordingInTransactionEventProcessor(eventProcessorDependencies()));

        var delivered = append(new OrderPlaced(OrderId.random()));

        awaitObserved("inTransactionProcessor.handler");
        assertThat(observed.get("inTransactionProcessor.handler")).contains(delivered);
    }

    // ------------------------------------------------------------------------------------------ Raw subscriptions

    @Test
    void an_asynchronous_subscription_handler_sees_the_delivered_event_as_cause_while_handling_and_while_committing() {
        eventStoreSubscriptionManager.subscribeToAggregateEventsAsynchronously(SubscriberId.of("async"),
                                                                               ORDERS,
                                                                               GlobalEventOrder.FIRST_GLOBAL_EVENT_ORDER,
                                                                               Optional.empty(),
                                                                               event -> {
                                                                                   observed.put("async.handler", CausationContext.current());
                                                                                   recordCauseAtCommit("async.beforeCommit");
                                                                               });

        var delivered = append(new OrderPlaced(OrderId.random()));

        awaitObserved("async.handler", "async.beforeCommit");
        assertThat(observed.get("async.handler")).contains(delivered);
        assertThat(observed.get("async.beforeCommit")).contains(delivered);
    }

    @Test
    void a_non_exclusive_in_transaction_subscription_handler_sees_the_delivered_event_as_cause() {
        eventStoreSubscriptionManager.subscribeToAggregateEventsInTransaction(SubscriberId.of("inTransaction"),
                                                                              ORDERS,
                                                                              (event, unitOfWork) -> observed.put("inTransaction.handler", CausationContext.current()));

        var delivered = append(new OrderPlaced(OrderId.random()));

        awaitObserved("inTransaction.handler");
        assertThat(observed.get("inTransaction.handler")).contains(delivered);
    }

    @Test
    void an_exclusive_in_transaction_subscription_handler_sees_the_delivered_event_as_cause() {
        var subscription = eventStoreSubscriptionManager.exclusivelySubscribeToAggregateEventsInTransaction(SubscriberId.of("exclusiveInTransaction"),
                                                                                                            ORDERS,
                                                                                                            Optional.empty(),
                                                                                                            (event, unitOfWork) -> observed.put("exclusiveInTransaction.handler", CausationContext.current()));
        Awaitility.waitAtMost(WAIT).until(subscription::isActive);

        var delivered = append(new OrderPlaced(OrderId.random()));

        awaitObserved("exclusiveInTransaction.handler");
        assertThat(observed.get("exclusiveInTransaction.handler")).contains(delivered);
    }

    /**
     * Pins the fact the lazily appending repositories have to design around: an in-transaction handler runs inside the
     * <em>appending</em> {@link UnitOfWork}'s commit, so a lifecycle callback it registers runs in a later pass of that
     * commit - outside the per-event binding, under whatever the appender had bound. A repository that registered an
     * aggregate from inside the handler and resolved its cause at commit would record the appender's cause, not the
     * event it was handed.
     * <p>
     * The appender here appends the way the lazily appending repositories do - from {@code beforeCommit}, returning
     * {@code REQUIRED} so the commit makes another pass. That extra pass is what runs the handler's callback.
     */
    @Test
    void the_commit_of_an_in_transaction_handlers_UnitOfWork_runs_outside_the_per_event_binding() {
        eventStoreSubscriptionManager.subscribeToAggregateEventsInTransaction(SubscriberId.of("inTransactionCommit"),
                                                                              ORDERS,
                                                                              (event, unitOfWork) -> {
                                                                                  observed.put("inTransactionCommit.handler", CausationContext.current());
                                                                                  recordCauseAtCommit("inTransactionCommit.beforeCommit");
                                                                              });
        var appendersCause = EventId.random();
        var delivered      = new AtomicReference<EventId>();

        CausationContext.where(appendersCause)
                        .run(() -> unitOfWorkFactory.usingUnitOfWork(unitOfWork -> appendLazily(unitOfWork, new OrderPlaced(OrderId.random()), delivered)));

        awaitObserved("inTransactionCommit.handler", "inTransactionCommit.beforeCommit");
        assertThat(observed.get("inTransactionCommit.handler")).contains(delivered.get());
        assertThat(observed.get("inTransactionCommit.beforeCommit")).contains(appendersCause);
    }

    @Test
    void a_batched_subscription_handler_sees_no_cause() {
        eventStoreSubscriptionManager.batchSubscribeToAggregateEventsAsynchronously(SubscriberId.of("batched"),
                                                                                    ORDERS,
                                                                                    GlobalEventOrder.FIRST_GLOBAL_EVENT_ORDER,
                                                                                    Optional.empty(),
                                                                                    10,
                                                                                    Duration.ofMillis(100),
                                                                                    events -> {
                                                                                        observed.put("batched.handler", CausationContext.current());
                                                                                        return events.size();
                                                                                    });

        append(new OrderPlaced(OrderId.random()));

        awaitObserved("batched.handler");
        assertThat(observed.get("batched.handler")).isEmpty();
    }

    // ------------------------------------------------------------------------------------------------------- helpers

    private EventProcessorDependencies eventProcessorDependencies() {
        return new EventProcessorDependencies(eventStoreSubscriptionManager,
                                              new Inboxes.DurableQueueBasedInboxes(durableQueues, fencedLockManager),
                                              commandBus,
                                              List.of());
    }

    private void start(AbstractEventProcessor processor) {
        processor.start();
        stopActions.add(processor::stop);
    }

    private void start(InTransactionEventProcessor processor) {
        processor.start();
        stopActions.add(processor::stop);
    }

    /**
     * Append the event in its own UnitOfWork
     *
     * @return the id the event was persisted with
     */
    private EventId append(Object event) {
        var orderId = ((OrderEvent) event).orderId();
        return unitOfWorkFactory.withUnitOfWork(() -> eventStore.appendToStream(ORDERS, orderId, event)
                                                                .eventList()
                                                                .getFirst()
                                                                .eventId());
    }

    /**
     * Record what {@link CausationContext#current()} returns when the current {@link UnitOfWork} commits
     */
    private void recordCauseAtCommit(String key) {
        unitOfWorkFactory.getRequiredUnitOfWork()
                         .registerLifecycleCallbackForResource(key, new UnitOfWorkLifecycleCallback<String>() {
                             @Override
                             public BeforeCommitProcessingStatus beforeCommit(UnitOfWork unitOfWork, List<String> associatedResources) {
                                 observed.put(key, CausationContext.current());
                                 return BeforeCommitProcessingStatus.COMPLETED;
                             }

                             @Override
                             public void afterCommit(UnitOfWork unitOfWork, List<String> associatedResources) {
                             }

                             @Override
                             public void beforeRollback(UnitOfWork unitOfWork, List<String> associatedResources, Throwable causeOfTheRollback) {
                             }

                             @Override
                             public void afterRollback(UnitOfWork unitOfWork, List<String> associatedResources, Throwable causeOfTheRollback) {
                             }
                         });
    }

    /**
     * Append the event when the {@link UnitOfWork} commits, the way the lazily appending repositories do: from
     * {@code beforeCommit}, returning {@code REQUIRED} after appending so the commit makes another pass
     */
    private void appendLazily(UnitOfWork unitOfWork, OrderEvent event, AtomicReference<EventId> persistedEventId) {
        unitOfWork.registerLifecycleCallbackForResource(event, new UnitOfWorkLifecycleCallback<OrderEvent>() {
            private boolean appended;

            @Override
            public BeforeCommitProcessingStatus beforeCommit(UnitOfWork unitOfWork, List<OrderEvent> associatedResources) {
                if (appended) {
                    return BeforeCommitProcessingStatus.COMPLETED;
                }
                appended = true;
                persistedEventId.set(eventStore.appendToStream(ORDERS, event.orderId(), event)
                                               .eventList()
                                               .getFirst()
                                               .eventId());
                return BeforeCommitProcessingStatus.REQUIRED;
            }

            @Override
            public void afterCommit(UnitOfWork unitOfWork, List<OrderEvent> associatedResources) {
            }

            @Override
            public void beforeRollback(UnitOfWork unitOfWork, List<OrderEvent> associatedResources, Throwable causeOfTheRollback) {
            }

            @Override
            public void afterRollback(UnitOfWork unitOfWork, List<OrderEvent> associatedResources, Throwable causeOfTheRollback) {
            }
        });
    }

    private List<PersistedEvent> shipmentEvents(OrderId orderId) {
        return unitOfWorkFactory.withUnitOfWork(() -> eventStore.fetchStream(SHIPMENTS, orderId)
                                                                .map(stream -> stream.eventList())
                                                                .orElse(List.of()));
    }

    private void awaitObserved(String... keys) {
        Awaitility.waitAtMost(WAIT).untilAsserted(() -> assertThat(observed).containsKeys(keys));
    }

    // ----------------------------------------------------------------------------------------------------- test data

    sealed interface OrderEvent permits OrderPlaced, OrderShipped, OrderCancelled, OrderConfirmed {
        OrderId orderId();
    }

    record OrderPlaced(OrderId orderId) implements OrderEvent {
    }

    record OrderShipped(OrderId orderId) implements OrderEvent {
    }

    record OrderCancelled(OrderId orderId) implements OrderEvent {
    }

    record OrderConfirmed(OrderId orderId) implements OrderEvent {
    }

    record ShipmentRequested(OrderId orderId) {
    }

    class RecordingEventProcessor extends EventProcessor {
        static final EventId EXPLICIT_CAUSE = EventId.of("explicit-cause");

        RecordingEventProcessor(EventProcessorDependencies dependencies) {
            super(dependencies);
        }

        @Override
        public String getProcessorName() {
            return "RecordingEventProcessor";
        }

        @Override
        protected List<AggregateType> reactsToEventsRelatedToAggregateTypes() {
            return List.of(ORDERS);
        }

        @MessageHandler
        void on(OrderPlaced e) {
            observed.put("REQUIRED.handler", CausationContext.current());
            recordCauseAtCommit("REQUIRED.beforeCommit");
        }

        @MessageHandler(unitOfWork = UnitOfWorkMode.NONE)
        void on(OrderShipped e) {
            observed.put("NONE.handler", CausationContext.current());
            usingUnitOfWork(() -> recordCauseAtCommit("NONE.beforeCommit"));
        }

        @MessageHandler
        void on(OrderConfirmed e) {
            eventStore.appendToStream(SHIPMENTS, e.orderId(), new ShipmentRequested(e.orderId()));
        }

        @MessageHandler
        void on(OrderCancelled e) {
            CausationContext.where(EXPLICIT_CAUSE).run(() -> observed.put("explicit.handler", CausationContext.current()));
        }
    }

    class RecordingViewEventProcessor extends ViewEventProcessor {
        RecordingViewEventProcessor(ViewEventProcessorDependencies dependencies) {
            super(dependencies);
        }

        @Override
        public String getProcessorName() {
            return "RecordingViewEventProcessor";
        }

        @Override
        protected List<AggregateType> reactsToEventsRelatedToAggregateTypes() {
            return List.of(ORDERS);
        }

        @MessageHandler
        void on(OrderPlaced e) {
            observed.put("view.handler", CausationContext.current());
        }
    }

    class RecordingInTransactionEventProcessor extends InTransactionEventProcessor {
        RecordingInTransactionEventProcessor(EventProcessorDependencies dependencies) {
            super(dependencies);
        }

        @Override
        public String getProcessorName() {
            return "RecordingInTransactionEventProcessor";
        }

        @Override
        protected List<AggregateType> reactsToEventsRelatedToAggregateTypes() {
            return List.of(ORDERS);
        }

        @MessageHandler
        void on(OrderPlaced e) {
            observed.put("inTransactionProcessor.handler", CausationContext.current());
        }
    }
}
