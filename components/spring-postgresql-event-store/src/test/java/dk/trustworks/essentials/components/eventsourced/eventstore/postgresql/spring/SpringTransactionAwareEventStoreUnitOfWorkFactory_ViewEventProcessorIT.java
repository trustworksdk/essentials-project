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

package dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.spring;

import dk.trustworks.essentials.components.distributed.fencedlock.postgresql.PostgresqlFencedLockManager;
import dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.*;
import dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.bus.*;
import dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.eventstream.*;
import dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.gap.PostgresqlEventStreamGapHandler;
import dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.observability.EventStoreSubscriptionObserver;
import dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.persistence.*;
import dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.persistence.table_per_aggregate_type.*;
import dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.processor.*;
import dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.serializer.AggregateIdSerializer;
import dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.serializer.json.EssentialsJSONEventSerializers;
import dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.spring.test_data.OrderId;
import dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.subscription.*;
import dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.transaction.EventStoreUnitOfWork;
import dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.types.*;
import dk.trustworks.essentials.components.foundation.messaging.*;
import dk.trustworks.essentials.components.foundation.messaging.queue.*;
import dk.trustworks.essentials.components.foundation.postgresql.SqlExecutionTimeLogger;
import dk.trustworks.essentials.components.foundation.reactive.command.*;
import dk.trustworks.essentials.components.foundation.transaction.*;
import dk.trustworks.essentials.components.foundation.types.*;
import dk.trustworks.essentials.components.queue.postgresql.PostgresqlDurableQueues;
import org.awaitility.Awaitility;
import org.jdbi.v3.core.Jdbi;
import org.jdbi.v3.postgres.PostgresPlugin;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.*;
import org.springframework.transaction.PlatformTransactionManager;
import org.testcontainers.junit.jupiter.*;
import org.testcontainers.postgresql.PostgreSQLContainer;

import java.time.*;
import java.util.*;
import java.util.concurrent.CopyOnWriteArrayList;

import static dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.persistence.table_per_aggregate_type.SeparateTablePerAggregateTypeEventStreamConfigurationFactory.standardSingleTenantConfiguration;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@code ViewEventProcessorIT}'s escalation cases, run on the {@link SpringTransactionAwareEventStoreUnitOfWorkFactory} that Spring Boot
 * applications use instead of the {@code EventStoreManagedUnitOfWorkFactory} that test is built on.
 * <p>
 * {@link ViewEventProcessor} decides between queueing a failed event and escalating it to the subscription's {@link SubscriptionErrorPolicy}
 * from {@link EventStoreUnitOfWork#getNumberOfEventsPersisted()} and {@link UnitOfWork#hasLifecycleCallbackResourcesWithPendingChanges()},
 * and the Spring {@link UnitOfWork} has its own copy of both. Were either to fall out of step, a failed handler's appended events would be
 * queued in the {@link UnitOfWork} - committed, published, and appended once more when the queued event is retried.
 */
@SpringBootTest
@DirtiesContext
@Testcontainers
class SpringTransactionAwareEventStoreUnitOfWorkFactory_ViewEventProcessorIT {
    private static final AggregateType TEST_ORDERS                             = AggregateType.of("TestOrders");
    private static final EventMetaData META_DATA                               = EventMetaData.of("Key1", "Value1");
    /**
     * {@link OrderPlacedEvent#orderDetails} that makes the test processor append an {@link OrderConfirmedEvent} through the
     * {@link PostgresqlEventStore} and then fail
     */
    private static final String        APPENDS_EVENT_THEN_FAILS                = "AppendsEventThenFails order details";
    /**
     * {@link OrderPlacedEvent#orderDetails} that makes the test processor register a resource with the {@link UnitOfWork} whose callback
     * appends an {@link OrderConfirmedEvent} at commit time - as an aggregate repository does with the aggregate's uncommitted events -
     * and then fail
     */
    private static final String        REGISTERS_RESOURCE_THEN_FAILS           = "RegistersResourceThenFails order details";
    /**
     * {@link OrderPlacedEvent#orderDetails} that makes the test processor register a resource with the {@link UnitOfWork} whose callback
     * reports no pending changes for it - as an aggregate repository does for an aggregate that only was loaded - and then fail
     */
    private static final String        REGISTERS_UNCHANGED_RESOURCE_THEN_FAILS = "RegistersUnchangedResourceThenFails order details";

    @Container
    static PostgreSQLContainer postgreSQLContainer = new PostgreSQLContainer("postgres:18.4")
            .withDatabaseName("test")
            .withPassword("test")
            .withUsername("test");

    @DynamicPropertySource
    static void setProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", postgreSQLContainer::getJdbcUrl);
        registry.add("spring.datasource.password", postgreSQLContainer::getPassword);
        registry.add("spring.datasource.username", postgreSQLContainer::getUsername);
    }

    @Autowired
    Jdbi jdbi;

    @Autowired
    PlatformTransactionManager transactionManager;

    private SpringTransactionAwareEventStoreUnitOfWorkFactory                       unitOfWorkFactory;
    private PostgresqlEventStore<SeparateTablePerAggregateEventStreamConfiguration> eventStore;
    private PostgresqlFencedLockManager                                             fencedLockManager;
    private EventStoreSubscriptionManager                                           eventStoreSubscriptionManager;
    private PostgresqlDurableQueues                                                 durableQueues;
    private DurableLocalCommandBus                                                  commandBus;
    private TestOrderViewEventProcessor                                             testProcessor;
    /**
     * The events whose handling failed after the {@link SubscriptionErrorPolicy} gave up, as reported to the
     * {@link EventStoreSubscriptionObserver}
     */
    private final List<PersistedEvent>                                              handleEventFailedEvents = new CopyOnWriteArrayList<>();

    @BeforeEach
    void setup() {
        jdbi.installPlugin(new PostgresPlugin());
        jdbi.setSqlLogger(new SqlExecutionTimeLogger());
        // The container is shared by every test method, and each one starts from an empty database
        jdbi.useHandle(handle -> handle.execute("DROP SCHEMA public CASCADE; CREATE SCHEMA public"));

        unitOfWorkFactory = new SpringTransactionAwareEventStoreUnitOfWorkFactory(jdbi, transactionManager);
        var jsonSerializer = EssentialsJSONEventSerializers.create();
        var persistenceStrategy = new SeparateTablePerAggregateTypePersistenceStrategy(jdbi,
                                                                                       unitOfWorkFactory,
                                                                                       new TestPersistableEventMapper(),
                                                                                       standardSingleTenantConfiguration(jsonSerializer,
                                                                                                                         IdentifierColumnType.UUID,
                                                                                                                         JSONColumnType.JSONB));
        eventStore = PostgresqlEventStore.<SeparateTablePerAggregateEventStreamConfiguration>builder()
                                         .setUnitOfWorkFactory(unitOfWorkFactory)
                                         .setPersistenceStrategy(persistenceStrategy)
                                         .setEventStreamGapHandlerFactory(eventStore -> new PostgresqlEventStreamGapHandler<>(unitOfWorkFactory))
                                         .setEventStoreSubscriptionObserver(new EventStoreSubscriptionObserver.NoOpEventStoreSubscriptionObserver() {
                                             @Override
                                             public void handleEventFailed(PersistedEvent event, PersistedEventHandler eventHandler, Throwable cause, EventStoreSubscription eventStoreSubscription) {
                                                 handleEventFailedEvents.add(event);
                                             }
                                         })
                                         .build();

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
                                                                     .setSubscriptionErrorPolicy(SubscriptionErrorPolicy.skip())
                                                                     .build();
        eventStoreSubscriptionManager.start();

        durableQueues = PostgresqlDurableQueues.builder()
                                               .setJsonSerializer(jsonSerializer)
                                               .setMessageHandlingTimeout(Duration.ofSeconds(2))
                                               .setUnitOfWorkFactory(unitOfWorkFactory)
                                               .build();
        durableQueues.start();

        commandBus = DurableLocalCommandBus.builder()
                                           .setInterceptors(new UnitOfWorkControllingCommandBusInterceptor(unitOfWorkFactory))
                                           .setDurableQueues(durableQueues)
                                           .build();
        commandBus.start();

        testProcessor = new TestOrderViewEventProcessor(new ViewEventProcessorDependencies(eventStoreSubscriptionManager,
                                                                                           fencedLockManager,
                                                                                           durableQueues,
                                                                                           commandBus,
                                                                                           List.of()),
                                                        eventStore);
        testProcessor.start();
    }

    @AfterEach
    void tearDown() {
        if (testProcessor != null) {
            testProcessor.stop();
        }
        if (durableQueues != null) {
            durableQueues.stop();
        }
        if (commandBus != null) {
            commandBus.stop();
        }
        if (eventStoreSubscriptionManager != null) {
            eventStoreSubscriptionManager.stop();
        }
        if (fencedLockManager != null) {
            fencedLockManager.stop();
        }
    }

    /**
     * Events the failed handler appended are registered in the {@link UnitOfWork}, and committing it to queue the event would hand
     * them to the in-transaction subscriptions and publish them on the local event bus although their rows were rolled back to the
     * savepoint. {@link SpringTransactionAwareEventStoreUnitOfWorkFactory.SpringTransactionAwareEventStoreUnitOfWork#getNumberOfEventsPersisted()}
     * must therefore count them, so the event is escalated - the {@link UnitOfWork} rolled back - and only then queued in a
     * {@link UnitOfWork} of its own. Were it queued in the subscription's {@link UnitOfWork}, the appended event would be
     * persisted and published.
     */
    @Test
    void a_handler_that_appended_events_before_failing_is_queued_after_the_rollback_and_its_events_are_not_published() {
        var eventsSeenInTransaction    = new CopyOnWriteArrayList<PersistedEvent>();
        var eventsPublishedAfterCommit = new CopyOnWriteArrayList<PersistedEvent>();
        eventStoreSubscriptionManager.subscribeToAggregateEventsInTransaction(SubscriberId.of("InTransactionOrderEventsRecorder"),
                                                                              TEST_ORDERS,
                                                                              (event, unitOfWork) -> eventsSeenInTransaction.add(event));
        eventStore.localEventBus().addSyncSubscriber(event -> {
            if (event instanceof PersistedEvents persistedEvents && persistedEvents.commitStage == CommitStage.AfterCommit) {
                eventsPublishedAfterCommit.addAll(persistedEvents.events);
            }
        });

        var orderId = OrderId.random();
        appendOrderPlacedEvent(orderId, APPENDS_EVENT_THEN_FAILS);

        assertQueuedAfterTheRollbackAndDeadLettered(orderId);
        // Once directly, then on each queued redelivery
        assertThat(testProcessor.appendedEventsBeforeFailing).hasSizeGreaterThan(1);
        assertThat(orderConfirmedEventsIn(eventsSeenInTransaction)).isEmpty();
        assertThat(orderConfirmedEventsIn(eventsPublishedAfterCommit)).isEmpty();
        assertOnlyTheOrderPlacedEventIsPersisted(orderId);
    }

    /**
     * A resource with pending changes - an aggregate the failed handler loaded and changed - has its {@link UnitOfWorkLifecycleCallback}
     * persist the aggregate's uncommitted events when the {@link UnitOfWork} commits, which a savepoint does not prevent. The Spring
     * {@link UnitOfWork#hasLifecycleCallbackResourcesWithPendingChanges()} must report it, so the event is escalated - the
     * {@link UnitOfWork} rolled back - and only then queued in a {@link UnitOfWork} of its own.
     */
    @Test
    void a_handler_that_registered_a_resource_with_pending_changes_before_failing_is_queued_after_the_rollback_and_the_resource_is_not_committed() {
        var orderId = OrderId.random();
        appendOrderPlacedEvent(orderId, REGISTERS_RESOURCE_THEN_FAILS);

        assertQueuedAfterTheRollbackAndDeadLettered(orderId);
        assertOnlyTheOrderPlacedEventIsPersisted(orderId);
    }

    /**
     * A resource without pending changes - an aggregate the failed handler only loaded - is left untouched by the commit, so it is safe
     * to queue the event in the {@link UnitOfWork}, as for any other failure. The queued redeliveries fail as well, so the event ends up
     * as a dead letter, and the failure never reaches the {@link SubscriptionErrorPolicy}.
     */
    @Test
    void a_handler_that_registered_a_resource_without_pending_changes_before_failing_gets_queued() {
        var orderId = OrderId.random();
        appendOrderPlacedEvent(orderId, REGISTERS_UNCHANGED_RESOURCE_THEN_FAILS);

        var queueName = testProcessor.getDurableQueueName();
        Awaitility.waitAtMost(Duration.ofSeconds(30))
                  .untilAsserted(() -> assertThat(durableQueues.getTotalDeadLetterMessagesQueuedFor(queueName)).isEqualTo(1));
        var deadLetterMessages = durableQueues.getDeadLetterMessages(queueName, DurableQueues.QueueingSortOrder.ASC, 0, 10);
        assertThat(((OrderedMessage) deadLetterMessages.get(0).getMessage()).getKey()).isEqualTo(orderId.toString());
        assertThat(handleEventFailedEvents).isEmpty();
        assertOnlyTheOrderPlacedEventIsPersisted(orderId);
    }

    private void appendOrderPlacedEvent(OrderId orderId, String orderDetails) {
        unitOfWorkFactory.usingUnitOfWork(uow -> eventStore.appendToStream(TEST_ORDERS, orderId, List.of(new OrderPlacedEvent(orderId, orderDetails))));
    }

    /**
     * Queued once, after the subscription's {@link UnitOfWork} was rolled back, and the {@link SubscriptionErrorPolicy} never gives up
     * on it. The queued redeliveries fail as well, so it ends up as a dead letter.
     */
    private void assertQueuedAfterTheRollbackAndDeadLettered(OrderId orderId) {
        var queueName = testProcessor.getDurableQueueName();
        Awaitility.waitAtMost(Duration.ofSeconds(30))
                  .untilAsserted(() -> assertThat(durableQueues.getTotalDeadLetterMessagesQueuedFor(queueName)).isEqualTo(1));
        var deadLetterMessages = durableQueues.getDeadLetterMessages(queueName, DurableQueues.QueueingSortOrder.ASC, 0, 10);
        assertThat(((OrderedMessage) deadLetterMessages.get(0).getMessage()).getKey()).isEqualTo(orderId.toString());
        assertThat(durableQueues.getTotalMessagesQueuedFor(queueName)).isZero();
        assertThat(handleEventFailedEvents).isEmpty();
    }

    private void assertOnlyTheOrderPlacedEventIsPersisted(OrderId orderId) {
        // The event stream is read lazily, so it is resolved inside the UnitOfWork
        var persistedEventTypes = unitOfWorkFactory.withUnitOfWork(uow -> eventStore.fetchStream(TEST_ORDERS, orderId)
                                                                                    .map(eventStream -> eventStream.eventList()
                                                                                                                   .stream()
                                                                                                                   .map(event -> (Object) event.event().getEventTypeAsJavaClass().get())
                                                                                                                   .toList()));
        assertThat(persistedEventTypes).hasValueSatisfying(eventTypes -> assertThat(eventTypes).containsExactly(OrderPlacedEvent.class));
    }

    private static List<PersistedEvent> orderConfirmedEventsIn(List<PersistedEvent> events) {
        return events.stream()
                     .filter(event -> event.event().getEventTypeAsJavaClass().get().equals(OrderConfirmedEvent.class))
                     .toList();
    }

    public static class OrderPlacedEvent {
        public final OrderId orderId;
        public final String  orderDetails;

        public OrderPlacedEvent(OrderId orderId, String orderDetails) {
            this.orderId = orderId;
            this.orderDetails = orderDetails;
        }
    }

    public static class OrderConfirmedEvent {
        public final OrderId orderId;

        public OrderConfirmedEvent(OrderId orderId) {
            this.orderId = orderId;
        }
    }

    private static class TestPersistableEventMapper implements PersistableEventMapper {
        private final CorrelationId correlationId   = CorrelationId.random();
        private final EventId       causedByEventId = EventId.random();

        @Override
        public PersistableEvent map(Object aggregateId, AggregateEventStreamConfiguration aggregateEventStreamConfiguration, Object event, EventOrder eventOrder) {
            return PersistableEvent.from(EventId.random(),
                                         aggregateEventStreamConfiguration.aggregateType,
                                         aggregateId,
                                         EventTypeOrName.with(event.getClass()),
                                         event,
                                         eventOrder,
                                         EventRevision.of(1),
                                         META_DATA,
                                         OffsetDateTime.now(),
                                         causedByEventId,
                                         correlationId,
                                         null);
        }
    }

    /**
     * Fails every {@link OrderPlacedEvent} - after leaving the state its {@link OrderPlacedEvent#orderDetails} asks for in the
     * {@link UnitOfWork}
     */
    public static class TestOrderViewEventProcessor extends ViewEventProcessor {
        private final PostgresqlEventStore<?> eventStore;
        final         List<OrderId>           appendedEventsBeforeFailing = new CopyOnWriteArrayList<>();

        /**
         * Appends the registered events when the {@link UnitOfWork} commits - the way an aggregate repository's
         * {@link UnitOfWorkLifecycleCallback} persists an aggregate's uncommitted events. Doesn't override
         * {@link UnitOfWorkLifecycleCallback#hasPendingChanges(Object)}, so every resource counts as having pending changes
         */
        private final UnitOfWorkLifecycleCallback<OrderConfirmedEvent> appendEventsWhenCommitting = new NoOpUnitOfWorkLifecycleCallback() {
            @Override
            public BeforeCommitProcessingStatus beforeCommit(UnitOfWork unitOfWork, List<OrderConfirmedEvent> associatedResources) {
                associatedResources.forEach(event -> eventStore.appendToStream(TEST_ORDERS, event.orderId, event));
                return BeforeCommitProcessingStatus.COMPLETED;
            }
        };

        /**
         * Reports every registered resource as unchanged, so committing the {@link UnitOfWork} leaves it untouched - the way an
         * aggregate repository's {@link UnitOfWorkLifecycleCallback} treats an aggregate that only was loaded
         */
        private final UnitOfWorkLifecycleCallback<OrderConfirmedEvent> neverChangedResources = new NoOpUnitOfWorkLifecycleCallback() {
            @Override
            public boolean hasPendingChanges(OrderConfirmedEvent resource) {
                return false;
            }
        };

        public TestOrderViewEventProcessor(ViewEventProcessorDependencies eventProcessorDependencies, PostgresqlEventStore<?> eventStore) {
            super(eventProcessorDependencies);
            this.eventStore = eventStore;
            eventStore.addAggregateEventStreamConfiguration(TEST_ORDERS, AggregateIdSerializer.serializerFor(OrderId.class));
        }

        @Override
        public String getProcessorName() {
            return "SpringTestOrderViewEventProcessor";
        }

        @Override
        protected List<AggregateType> reactsToEventsRelatedToAggregateTypes() {
            return List.of(TEST_ORDERS);
        }

        @Override
        protected RedeliveryPolicy getDurableQueueRedeliveryPolicy() {
            return RedeliveryPolicy.fixedBackoff(Duration.ofMillis(200), 3);
        }

        @MessageHandler
        public void onOrderPlaced(OrderPlacedEvent event) {
            var unitOfWork = eventStore.getUnitOfWorkFactory().getRequiredUnitOfWork();
            switch (event.orderDetails) {
                case APPENDS_EVENT_THEN_FAILS -> {
                    eventStore.appendToStream(TEST_ORDERS, event.orderId, new OrderConfirmedEvent(event.orderId));
                    appendedEventsBeforeFailing.add(event.orderId);
                }
                case REGISTERS_RESOURCE_THEN_FAILS -> unitOfWork.registerLifecycleCallbackForResource(new OrderConfirmedEvent(event.orderId), appendEventsWhenCommitting);
                case REGISTERS_UNCHANGED_RESOURCE_THEN_FAILS -> unitOfWork.registerLifecycleCallbackForResource(new OrderConfirmedEvent(event.orderId), neverChangedResources);
                default -> {
                }
            }
            throw new RuntimeException(event.orderDetails);
        }
    }

    private static class NoOpUnitOfWorkLifecycleCallback implements UnitOfWorkLifecycleCallback<OrderConfirmedEvent> {
        @Override
        public BeforeCommitProcessingStatus beforeCommit(UnitOfWork unitOfWork, List<OrderConfirmedEvent> associatedResources) {
            return BeforeCommitProcessingStatus.COMPLETED;
        }

        @Override
        public void afterCommit(UnitOfWork unitOfWork, List<OrderConfirmedEvent> associatedResources) {
        }

        @Override
        public void beforeRollback(UnitOfWork unitOfWork, List<OrderConfirmedEvent> associatedResources, Throwable causeOfTheRollback) {
        }

        @Override
        public void afterRollback(UnitOfWork unitOfWork, List<OrderConfirmedEvent> associatedResources, Throwable causeOfTheRollback) {
        }
    }
}
