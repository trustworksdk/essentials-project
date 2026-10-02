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

import com.zaxxer.hikari.*;
import dk.trustworks.essentials.components.distributed.fencedlock.postgresql.PostgresqlFencedLockManager;
import dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.*;
import dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.bus.*;
import dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.eventstream.*;
import dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.gap.PostgresqlEventStreamGapHandler;
import dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.observability.EventStoreSubscriptionObserver;
import dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.persistence.EventMetaData;
import dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.persistence.table_per_aggregate_type.*;
import dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.serializer.AggregateIdSerializer;
import dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.serializer.json.*;
import dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.subscription.*;
import dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.transaction.*;
import dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.types.GlobalEventOrder;
import dk.trustworks.essentials.components.foundation.messaging.*;
import dk.trustworks.essentials.components.foundation.messaging.queue.*;
import dk.trustworks.essentials.components.foundation.postgresql.SqlExecutionTimeLogger;
import dk.trustworks.essentials.components.foundation.reactive.command.*;
import dk.trustworks.essentials.components.foundation.transaction.*;
import dk.trustworks.essentials.components.foundation.types.SubscriberId;
import dk.trustworks.essentials.components.queue.postgresql.PostgresqlDurableQueues;
import dk.trustworks.essentials.reactive.command.CmdHandler;
import dk.trustworks.essentials.shared.collections.Lists;
import org.awaitility.Awaitility;
import org.jdbi.v3.core.Jdbi;
import org.jdbi.v3.postgres.PostgresPlugin;
import org.junit.jupiter.api.*;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.*;

import java.lang.annotation.*;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;
import java.util.stream.IntStream;

import static dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.persistence.table_per_aggregate_type.SeparateTablePerAggregateTypeEventStreamConfigurationFactory.standardSingleTenantConfiguration;
import static dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.processor.EventProcessorIT.createObjectMapper;
import static dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.processor.ViewEventProcessorIT.TestOrderViewEventProcessor.TEST_ORDERS;
import static org.assertj.core.api.Assertions.assertThat;

@Testcontainers
public class ViewEventProcessorIT {

    public static final EventMetaData META_DATA                               = EventMetaData.of("Key1", "Value1", "Key2", "Value2");
    /**
     * {@link EventProcessorIT.OrderPlacedEvent#orderDetails} that makes the test processor's view update fail with a
     * primary key violation on its first attempt - after it already wrote a row - and succeed on later attempts
     */
    public static final String        SQL_FAILS_ON_FIRST_ATTEMPT              = "SqlFailsOnFirstAttempt order details";
    /**
     * {@link EventProcessorIT.OrderPlacedEvent#orderDetails} that makes the test processor append an
     * {@link EventProcessorIT.OrderConfirmedEvent} through the {@link PostgresqlEventStore} and then fail
     */
    public static final String        APPENDS_EVENT_THEN_FAILS                = "AppendsEventThenFails order details";
    /**
     * {@link EventProcessorIT.OrderPlacedEvent#orderDetails} that makes the test processor register a resource with the
     * {@link UnitOfWork} whose callback appends an {@link EventProcessorIT.OrderConfirmedEvent} at commit time - as an
     * aggregate repository does with the aggregate's uncommitted events - and then fail
     */
    public static final String        REGISTERS_RESOURCE_THEN_FAILS           = "RegistersResourceThenFails order details";
    /**
     * {@link EventProcessorIT.OrderPlacedEvent#orderDetails} that makes the test processor register a resource with the
     * {@link UnitOfWork} whose callback reports no pending changes for it - as an aggregate repository does for an
     * aggregate that only was loaded - and then fail
     */
    public static final String        REGISTERS_UNCHANGED_RESOURCE_THEN_FAILS = "RegistersUnchangedResourceThenFails order details";
    /**
     * {@link EventProcessorIT.OrderPlacedEvent#orderDetails} that makes the test processor join the {@link UnitOfWork}
     * through {@code usingUnitOfWork} and fail inside it, which marks the {@link UnitOfWork} rollback-only
     */
    public static final String        JOINS_UNIT_OF_WORK_THEN_FAILS           = "JoinsUnitOfWorkThenFails order details";

    private HikariConfig                                                            cfg;
    private HikariDataSource                                                        ds;
    private Jdbi                                                                    jdbi;
    private EventStoreUnitOfWorkFactory<EventStoreUnitOfWork>                       unitOfWorkFactory;
    private EventProcessorIT.TestPersistableEventMapper                             eventMapper;
    private PostgresqlEventStore<SeparateTablePerAggregateEventStreamConfiguration> eventStore;

    @Container
    private final PostgreSQLContainer postgreSQLContainer = new PostgreSQLContainer("postgres:18.4")
            .withDatabaseName("event-store")
            .withUsername("test-user")
            .withPassword("secret-password");

    private TestOrderViewEventProcessor   testProcessor;
    private PostgresqlDurableQueues       durableQueues;
    private EventStoreSubscriptionManager eventStoreSubscriptionManager;
    private PostgresqlFencedLockManager   fencedLockManager;
    private DurableLocalCommandBus        commandBus;
    private DurableSubscriptionRepository durableSubscriptionRepository;
    /**
     * The events whose handling failed after the {@link SubscriptionErrorPolicy} gave up, as reported to the
     * {@link EventStoreSubscriptionObserver}
     */
    private final List<PersistedEvent>    handleEventFailedEvents = new CopyOnWriteArrayList<>();

    /**
     * Runs the annotated test with {@link SubscriptionErrorPolicy#stop()} instead of the default {@link SubscriptionErrorPolicy#skip()}
     */
    @Retention(RetentionPolicy.RUNTIME)
    @Target(ElementType.METHOD)
    private @interface WithSubscriptionErrorPolicyStop {
    }

    @BeforeEach
    void setup(TestInfo testInfo) {
        var subscriptionErrorPolicy = testInfo.getTestMethod()
                                              .filter(method -> method.isAnnotationPresent(WithSubscriptionErrorPolicyStop.class))
                                              .map(method -> SubscriptionErrorPolicy.stop())
                                              .orElseGet(SubscriptionErrorPolicy::skip);
        cfg = new HikariConfig();
        cfg.setJdbcUrl(postgreSQLContainer.getJdbcUrl());
        cfg.setUsername(postgreSQLContainer.getUsername());
        cfg.setPassword(postgreSQLContainer.getPassword());
        cfg.setMaximumPoolSize(20);

        ds = new HikariDataSource(cfg);

        jdbi = Jdbi.create(ds);
        jdbi.installPlugin(new PostgresPlugin());
        jdbi.setSqlLogger(new SqlExecutionTimeLogger());
        jdbi.useHandle(handle -> handle.execute("CREATE TABLE IF NOT EXISTS order_view (order_id TEXT NOT NULL, status TEXT NOT NULL, PRIMARY KEY (order_id, status))"));

        unitOfWorkFactory = new EventStoreManagedUnitOfWorkFactory(jdbi);
        eventMapper = new EventProcessorIT.TestPersistableEventMapper();
        var jsonSerializer = EssentialsJSONEventSerializers.create();
        var persistenceStrategy = new SeparateTablePerAggregateTypePersistenceStrategy(jdbi,
                                                                                       unitOfWorkFactory,
                                                                                       eventMapper,
                                                                                       standardSingleTenantConfiguration(
                                                                                               jsonSerializer,
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

        durableSubscriptionRepository = new PostgresqlDurableSubscriptionRepository(jdbi, eventStore);

        eventStoreSubscriptionManager = EventStoreSubscriptionManager.builder()
                                                                     .setEventStore(eventStore)
                                                                     .setFencedLockManager(fencedLockManager)
                                                                     .setDurableSubscriptionRepository(durableSubscriptionRepository)
                                                                     .setSnapshotResumePointsEvery(Duration.ofSeconds(1))
                                                                     .setSubscriptionErrorPolicy(subscriptionErrorPolicy)
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
                                           .setCommandQueueRedeliveryPolicy(RedeliveryPolicy.fixedBackoff(Duration.ofMillis(200), 3))
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
    public void tearDown() {
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

    @Test
    public void verify_view_event_processor_with_load() {
        unitOfWorkFactory.usingUnitOfWork(uow -> {
            IntStream.range(1, 10_001).sequential().forEach(value -> {
                var orderId = EventProcessorIT.OrderId.random();
                var event   = new EventProcessorIT.OrderPlacedEvent(orderId, "Load Order Details " + value);
                eventStore.appendToStream(TEST_ORDERS, orderId, List.of(event));
            });
        });

        Awaitility.waitAtMost(Duration.ofMinutes(2)).untilAsserted(() -> {
            int orderPlacedEventCounter = testProcessor.getOrderPlacedEventCounter().get();
            assert orderPlacedEventCounter == 10_000;
        });
    }

    @Test
    public void verify_view_event_processor_flow() {
        var orderId      = EventProcessorIT.OrderId.random();
        var orderDetails = "Test order details";
        var placeCmd     = new EventProcessorIT.PlaceOrderCommand(orderId, orderDetails);

        // Send the command via the command bus
        commandBus.send(placeCmd);

        // Fetch the aggregate event stream for this order
        Awaitility.waitAtMost(Duration.ofSeconds(2))
                  .until(() -> unitOfWorkFactory.withUnitOfWork(() -> {
                      var streamOpt = eventStore.fetchStream(TEST_ORDERS, orderId);
                      return streamOpt.isPresent() && streamOpt.get().eventList().size() == 2;
                  }));

        // We expect two events: first the OrderPlacedEvent then the OrderConfirmedEvent
        var events = unitOfWorkFactory.withUnitOfWork(() -> eventStore.fetchStream(TEST_ORDERS, orderId).get().eventList());
        assertThat(events).hasSize(2);
        var event0 = events.get(0);
        var event1 = events.get(1);
        assertThat(event0.event().getEventTypeAsJavaClass()).isPresent().isEqualTo(Optional.of(EventProcessorIT.OrderPlacedEvent.class));
        assertThat(event1.event().getEventTypeAsJavaClass()).isPresent().isEqualTo(Optional.of(EventProcessorIT.OrderConfirmedEvent.class));
    }

    @Test
    void testFailingCommandSendViaCommandBus_sendAndDontWait_EventuallyEndsUpInAsADeadLetterMessage() throws Exception {
        var orderId    = EventProcessorIT.OrderId.random();
        var failingCmd = new EventProcessorIT.FailingCommandSentUsingAsyncAndDontWaitViaCommandBus(orderId, "Intentional failure for testing");

        // Verify we don't have any dead-letter messages
        var queueName = commandBus.getCommandQueueName();
        assertThat(durableQueues.getTotalDeadLetterMessagesQueuedFor(queueName)).isEqualTo(0);

        // Send the failing command using the command bus (asynchronous, fire-and-forget)
        commandBus.sendAndDontWait(failingCmd);

        Awaitility.waitAtMost(Duration.ofSeconds(2))
                  .untilAsserted(() -> {
                      assertThat(durableQueues.getTotalDeadLetterMessagesQueuedFor(queueName)).isEqualTo(1);
                  });

        var deadLetterMessages = durableQueues.getDeadLetterMessages(queueName, DurableQueues.QueueingSortOrder.ASC, 0, 10);
        assertThat(deadLetterMessages).hasSize(1);
        assertThat(deadLetterMessages.get(0).getPayload()).isInstanceOf(EventProcessorIT.FailingCommandSentUsingAsyncAndDontWaitViaCommandBus.class);
    }

    @Test
    public void verify_failed_event_handling_gets_queued() {
        var orderId      = EventProcessorIT.OrderId.random();
        var orderDetails = "Fail order details";
        var event        = new EventProcessorIT.OrderPlacedEvent(orderId, orderDetails);
        unitOfWorkFactory.usingUnitOfWork(uow -> {
            eventStore.appendToStream(TEST_ORDERS, orderId, List.of(event));
        });

        var queueName = testProcessor.getDurableQueueName();

        Awaitility.waitAtMost(Duration.ofSeconds(5))
                  .untilAsserted(() -> {
                      assertThat(durableQueues.getTotalDeadLetterMessagesQueuedFor(queueName)).isEqualTo(1);
                  });

        var deadLetterMessages = durableQueues.getDeadLetterMessages(queueName, DurableQueues.QueueingSortOrder.ASC, 0, 10);
        assertThat(deadLetterMessages).hasSize(1);
        assertThat(deadLetterMessages.get(0).getMessage()).isInstanceOf(OrderedMessage.class);
        assertThat(deadLetterMessages.get(0).getPayload()).isInstanceOf(AggregateType.class);
        assertThat(deadLetterMessages.get(0).getPayload()).isEqualTo(TEST_ORDERS);
        assertThat(((OrderedMessage) deadLetterMessages.get(0).getMessage()).getKey()).isEqualTo(orderId.toString());
        assertThat(((OrderedMessage) deadLetterMessages.get(0).getMessage()).getOrder()).isEqualTo(0);
    }

    /**
     * An event whose payload cannot be deserialized used to be deserialized before the failure handling started, so
     * the exception reached the subscription, which skipped the event. It must be queued like any other failure - on
     * the queue it cannot be deserialized either, so it ends up as a dead letter, where it is visible and can be dealt with.
     */
    @Test
    public void verify_an_event_that_cannot_be_deserialized_gets_queued() {
        var orderId = EventProcessorIT.OrderId.random();
        unitOfWorkFactory.usingUnitOfWork(uow -> {
            eventStore.appendToStream(TEST_ORDERS, orderId, List.of(new EventProcessorIT.OrderPlacedEvent(orderId, "Undeserializable order details")));
            // Corrupt the payload in the same transaction, so the subscription never sees an intact version:
            // Jackson refuses to bind a JSON object to the String orderDetails
            var rowsUpdated = uow.handle().createUpdate("UPDATE TestOrders_events SET event_payload = jsonb_set(event_payload, '{orderDetails}', '{\"not\": \"a string\"}'::jsonb) " +
                                                                "WHERE aggregate_id::text = :aggregateId")
                                 .bind("aggregateId", orderId.toString())
                                 .execute();
            assertThat(rowsUpdated).isEqualTo(1);
        });

        var queueName = testProcessor.getDurableQueueName();
        Awaitility.waitAtMost(Duration.ofSeconds(10))
                  .untilAsserted(() -> assertThat(durableQueues.getTotalDeadLetterMessagesQueuedFor(queueName)).isEqualTo(1));

        var deadLetterMessages = durableQueues.getDeadLetterMessages(queueName, DurableQueues.QueueingSortOrder.ASC, 0, 10);
        assertThat(deadLetterMessages).hasSize(1);
        assertThat(deadLetterMessages.get(0).getMessage()).isInstanceOf(OrderedMessage.class);
        assertThat(((OrderedMessage) deadLetterMessages.get(0).getMessage()).getKey()).isEqualTo(orderId.toString());
        assertThat(((OrderedMessage) deadLetterMessages.get(0).getMessage()).getOrder()).isEqualTo(0);
    }

    /**
     * A handler whose SQL fails aborts the Postgres transaction it runs in - the subscription's. The fallback that
     * queues the event runs in that same transaction, so it used to fail too, and the event was skipped. The direct
     * handler now runs under a savepoint: its failure rolls back only its own writes, and the event is queued.
     */
    @Test
    public void verify_an_event_whose_handler_sql_fails_gets_queued_and_the_handlers_partial_writes_are_rolled_back() {
        var orderId = EventProcessorIT.OrderId.random();
        unitOfWorkFactory.usingUnitOfWork(uow -> {
            eventStore.appendToStream(TEST_ORDERS, orderId, List.of(new EventProcessorIT.OrderPlacedEvent(orderId, SQL_FAILS_ON_FIRST_ATTEMPT)));
        });

        // The first (direct) attempt writes a 'partial' row and then violates the primary key. The queued redelivery succeeds
        var queueName = testProcessor.getDurableQueueName();
        Awaitility.waitAtMost(Duration.ofSeconds(10))
                  .untilAsserted(() -> assertThat(orderViewStatuses(orderId)).containsExactly("done"));
        assertThat(testProcessor.getSqlFailureAttempts(orderId)).isEqualTo(2);
        assertThat(durableQueues.getTotalMessagesQueuedFor(queueName)).isZero();
        assertThat(durableQueues.getTotalDeadLetterMessagesQueuedFor(queueName)).isZero();
    }

    private List<String> orderViewStatuses(EventProcessorIT.OrderId orderId) {
        return jdbi.withHandle(handle -> handle.createQuery("SELECT status FROM order_view WHERE order_id = :orderId ORDER BY status")
                                               .bind("orderId", orderId.toString())
                                               .mapTo(String.class)
                                               .list());
    }

    /**
     * A savepoint only undoes SQL. Events the failed handler appended through the event store are also registered in
     * the {@link UnitOfWork}, and committing it to queue the event would hand them to the in-transaction subscriptions
     * and publish them on the local event bus, although their rows were rolled back - and the queued retry would
     * append them once more. So the event is not queued in it: the whole {@link UnitOfWork} is rolled back, and the
     * event is then queued in a {@link UnitOfWork} of its own instead of the {@link SubscriptionErrorPolicy} skipping it.
     * The queued redeliveries fail as well, so it ends up as a dead letter.
     */
    @Test
    public void verify_a_handler_that_appended_events_before_failing_is_queued_after_the_rollback_and_its_events_are_not_published() {
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

        var orderId = EventProcessorIT.OrderId.random();
        unitOfWorkFactory.usingUnitOfWork(uow -> {
            eventStore.appendToStream(TEST_ORDERS, orderId, List.of(new EventProcessorIT.OrderPlacedEvent(orderId, APPENDS_EVENT_THEN_FAILS)));
        });

        assertQueuedAfterTheRollbackAndDeadLettered(orderId);
        // Once directly, then on each queued redelivery
        assertThat(testProcessor.getAppendedEventsBeforeFailing()).isGreaterThan(1);
        assertThat(orderConfirmedEventsIn(eventsSeenInTransaction)).isEmpty();
        assertThat(orderConfirmedEventsIn(eventsPublishedAfterCommit)).isEmpty();
        assertOnlyTheOrderPlacedEventIsPersisted(orderId);
    }

    /**
     * An aggregate the failed handler loaded and changed is registered in the {@link UnitOfWork}, and its
     * {@link UnitOfWorkLifecycleCallback} persists the aggregate's uncommitted events when the {@link UnitOfWork}
     * commits - which a savepoint does not prevent. So the event is not queued in it: the whole {@link UnitOfWork} is
     * rolled back, and the event is then queued in a {@link UnitOfWork} of its own.
     */
    @Test
    public void verify_a_handler_that_registered_a_resource_before_failing_is_queued_after_the_rollback_and_the_resource_is_not_committed() {
        var orderId = EventProcessorIT.OrderId.random();
        unitOfWorkFactory.usingUnitOfWork(uow -> {
            eventStore.appendToStream(TEST_ORDERS, orderId, List.of(new EventProcessorIT.OrderPlacedEvent(orderId, REGISTERS_RESOURCE_THEN_FAILS)));
        });

        assertQueuedAfterTheRollbackAndDeadLettered(orderId);
        assertOnlyTheOrderPlacedEventIsPersisted(orderId);
    }

    /**
     * An aggregate the failed handler only loaded is registered in the {@link UnitOfWork} too, but it has no pending
     * changes, so committing the {@link UnitOfWork} leaves it untouched. It is safe to queue the event in it, as for
     * any other failure - the queued redeliveries fail as well, so the event ends up as a dead letter.
     */
    @Test
    public void verify_a_handler_that_registered_a_resource_without_pending_changes_before_failing_gets_queued() {
        var orderId = EventProcessorIT.OrderId.random();
        unitOfWorkFactory.usingUnitOfWork(uow -> {
            eventStore.appendToStream(TEST_ORDERS, orderId, List.of(new EventProcessorIT.OrderPlacedEvent(orderId, REGISTERS_UNCHANGED_RESOURCE_THEN_FAILS)));
        });

        var queueName = testProcessor.getDurableQueueName();
        Awaitility.waitAtMost(Duration.ofSeconds(10))
                  .untilAsserted(() -> assertThat(durableQueues.getTotalDeadLetterMessagesQueuedFor(queueName)).isEqualTo(1));
        var deadLetterMessages = durableQueues.getDeadLetterMessages(queueName, DurableQueues.QueueingSortOrder.ASC, 0, 10);
        assertThat(((OrderedMessage) deadLetterMessages.get(0).getMessage()).getKey()).isEqualTo(orderId.toString());
        assertThat(handleEventFailedEvents).isEmpty();
        assertOnlyTheOrderPlacedEventIsPersisted(orderId);
    }

    /**
     * A handler that joins the {@link UnitOfWork} through {@code usingUnitOfWork} and fails marks it rollback-only, so
     * nothing written in it - the queued event included - can commit. Instead of being rolled back silently with it, the
     * event is queued in a {@link UnitOfWork} of its own after the rollback - which takes precedence over
     * {@link SubscriptionErrorPolicy#stop()}: the subscription carries on with the next event.
     */
    @Test
    @WithSubscriptionErrorPolicyStop
    public void verify_a_handler_that_marks_the_unit_of_work_rollback_only_is_queued_after_the_rollback_instead_of_stopping_the_subscription() {
        var orderId = EventProcessorIT.OrderId.random();
        unitOfWorkFactory.usingUnitOfWork(uow -> {
            eventStore.appendToStream(TEST_ORDERS, orderId, List.of(new EventProcessorIT.OrderPlacedEvent(orderId, JOINS_UNIT_OF_WORK_THEN_FAILS)));
        });

        assertQueuedAfterTheRollbackAndDeadLettered(orderId);

        // Not stopped: a later event is handled
        var laterOrderId = EventProcessorIT.OrderId.random();
        unitOfWorkFactory.usingUnitOfWork(uow -> {
            eventStore.appendToStream(TEST_ORDERS, laterOrderId, List.of(new EventProcessorIT.OrderPlacedEvent(laterOrderId, "Load order details")));
        });
        var subscriberId = AbstractEventProcessor.resolveSubscriberId(TEST_ORDERS, testProcessor.getProcessorName());
        Awaitility.waitAtMost(Duration.ofSeconds(10))
                  .untilAsserted(() -> {
                      assertThat(testProcessor.getOrderPlacedEventCounter().get()).isEqualTo(1);
                      assertThat(eventStoreSubscriptionManager.getCurrentEventOrder(subscriberId, TEST_ORDERS))
                              .hasValueSatisfying(order -> assertThat(order.longValue()).isEqualTo(3L));
                  });
    }

    /**
     * The event is queued once, after the subscription's {@link UnitOfWork} was rolled back, and the
     * {@link SubscriptionErrorPolicy} never gives up on it (the observer is not told of a failure). The queued
     * redeliveries fail like the direct handling did, so it ends up as a dead letter.
     */
    private void assertQueuedAfterTheRollbackAndDeadLettered(EventProcessorIT.OrderId orderId) {
        var queueName = testProcessor.getDurableQueueName();
        Awaitility.waitAtMost(Duration.ofSeconds(10))
                  .untilAsserted(() -> assertThat(durableQueues.getTotalDeadLetterMessagesQueuedFor(queueName)).isEqualTo(1));
        var deadLetterMessages = durableQueues.getDeadLetterMessages(queueName, DurableQueues.QueueingSortOrder.ASC, 0, 10);
        assertThat(((OrderedMessage) deadLetterMessages.get(0).getMessage()).getKey()).isEqualTo(orderId.toString());
        assertThat(durableQueues.getTotalMessagesQueuedFor(queueName)).isZero();
        assertThat(handleEventFailedEvents).isEmpty();
    }

    private void assertOnlyTheOrderPlacedEventIsPersisted(EventProcessorIT.OrderId orderId) {
        // The event stream is read lazily, so it is resolved inside the UnitOfWork
        var persistedEventTypes = unitOfWorkFactory.withUnitOfWork(uow -> eventStore.fetchStream(TEST_ORDERS, orderId)
                                                                                    .map(eventStream -> eventStream.eventList()
                                                                                                                   .stream()
                                                                                                                   .map(event -> (Object) event.event().getEventTypeAsJavaClass().get())
                                                                                                                   .toList()));
        assertThat(persistedEventTypes).hasValueSatisfying(eventTypes -> assertThat(eventTypes).containsExactly(EventProcessorIT.OrderPlacedEvent.class));
    }

    private static List<PersistedEvent> orderConfirmedEventsIn(List<PersistedEvent> events) {
        return events.stream()
                     .filter(event -> event.event().getEventTypeAsJavaClass().get().equals(EventProcessorIT.OrderConfirmedEvent.class))
                     .toList();
    }

    @Test
    public void verify_failed_event_handling_additional_event_gets_queued() {
        var orderId      = EventProcessorIT.OrderId.random();
        var orderDetails = "Fail order details";
        var event        = new EventProcessorIT.OrderPlacedEvent(orderId, orderDetails);
        unitOfWorkFactory.usingUnitOfWork(uow -> {
            eventStore.appendToStream(TEST_ORDERS, orderId, List.of(event));
        });

        var queueName = testProcessor.getDurableQueueName();

        Awaitility.waitAtMost(Duration.ofSeconds(3))
                  .untilAsserted(() -> {
                      assertThat(durableQueues.getTotalDeadLetterMessagesQueuedFor(queueName)).isEqualTo(1);
                  });
        assertThat(durableQueues.getTotalMessagesQueuedFor(queueName)).isEqualTo(0);

        var eventConfirmed = new EventProcessorIT.OrderConfirmedEvent(orderId);
        unitOfWorkFactory.usingUnitOfWork(uow -> {
            eventStore.appendToStream(TEST_ORDERS, orderId, List.of(eventConfirmed));
        });

        Awaitility.waitAtMost(Duration.ofSeconds(3))
                  .untilAsserted(() -> {
                      assertThat(durableQueues.getTotalMessagesQueuedFor(queueName)).isEqualTo(1);
                  });

        var queuedMessages = durableQueues.getQueuedMessages(queueName, DurableQueues.QueueingSortOrder.ASC, 0, 10);
        assertThat(queuedMessages).hasSize(1);
        assertThat(queuedMessages.get(0).getMessage()).isInstanceOf(OrderedMessage.class);
        assertThat(queuedMessages.get(0).getPayload()).isInstanceOf(AggregateType.class);
        assertThat(queuedMessages.get(0).getPayload()).isEqualTo(TEST_ORDERS);
        assertThat(((OrderedMessage) queuedMessages.get(0).getMessage()).getKey()).isEqualTo(orderId.toString());
        assertThat(((OrderedMessage) queuedMessages.get(0).getMessage()).getOrder()).isEqualTo(1);
    }

    @Test
    public void testResetAllSubscriptions() {
        IntStream.range(0, 5).forEach(i -> {
            var orderId      = EventProcessorIT.OrderId.random();
            var orderDetails = "Test order details";
            var placeCmd     = new EventProcessorIT.PlaceOrderCommand(orderId, orderDetails);
            commandBus.send(placeCmd);
        });
        var subscriberId = AbstractEventProcessor.resolveSubscriberId(TEST_ORDERS, testProcessor.getProcessorName());
        Awaitility.waitAtMost(Duration.ofSeconds(2)).untilAsserted(() -> {
            var currentEventOrder = eventStoreSubscriptionManager.getCurrentEventOrder(subscriberId, TEST_ORDERS);
            assertThat(currentEventOrder)
                    .hasValueSatisfying(order ->
                                                assertThat(order.longValue()).isEqualTo(11L) // 11 = 5 (aggregates) * 2 (events) + 1 (for the next global event order)
                                       );
        });

        var queueName = testProcessor.getDurableQueueName();
        assertThat(durableQueues.getTotalDeadLetterMessagesQueuedFor(queueName)).isEqualTo(0);
        assertThat(durableQueues.getTotalMessagesQueuedFor(queueName)).isEqualTo(0);
        // Send the failing command using the processor's inbox (asynchronous, fire-and-forget)
        var failingCmd = new EventProcessorIT.FailingCommandSentViaInbox(EventProcessorIT.OrderId.random(), "Intentional failure for testing");
        testProcessor.getDurableQueuesForTesting().queueMessage(queueName, Message.of(failingCmd));
        assertThat(durableQueues.getTotalMessagesQueuedFor(queueName)).isEqualTo(1);

        // Set a callback to verify that we do reset resumePoints
        testProcessor.setResetCallback(resetPoints -> {
            Awaitility.waitAtMost(Duration.ofSeconds(2)).untilAsserted(() -> {
                var currentEventOrder = eventStoreSubscriptionManager.getCurrentEventOrder(subscriberId, TEST_ORDERS);
                System.out.println("After resetting: Current event order: " + currentEventOrder.get());
                assertThat(currentEventOrder)
                        .hasValueSatisfying(order ->
                                                    assertThat(order.longValue()).isEqualTo(1)
                                           )
                        .describedAs("After resetting: Current event order");
                var currentResumePoint = durableSubscriptionRepository.getResumePoint(subscriberId, TEST_ORDERS);
                System.out.println("After resetting: Current resume point: " + currentEventOrder.get());
                assertThat(currentResumePoint)
                        .hasValueSatisfying(resumePoint -> assertThat(resumePoint.getResumeFromAndIncluding().longValue()).isEqualTo(1))
                        .describedAs("After resetting: Current resume point");
            });
        });
        testProcessor.resetAllSubscriptions();

        // Check subscriptions catchup again
        Awaitility.waitAtMost(Duration.ofSeconds(3)).untilAsserted(() -> {
            var currentEventOrder = eventStoreSubscriptionManager.getCurrentEventOrder(subscriberId, TEST_ORDERS);
            System.out.println("After resetting: Current event order: " + currentEventOrder.get());
            assertThat(currentEventOrder)
                    .hasValueSatisfying(order ->
                                                assertThat(order.longValue()).isEqualTo(11)
                                       )
                    .describedAs("After resetting: Current event order");
            var currentResumePoint = durableSubscriptionRepository.getResumePoint(subscriberId, TEST_ORDERS);
            System.out.println("After resetting: Current resume point: " + currentEventOrder.get());
            assertThat(currentResumePoint)
                    .hasValueSatisfying(resumePoint -> assertThat(resumePoint.getResumeFromAndIncluding().longValue()).isEqualTo(11))
                    .describedAs("After resetting: Current resume point");
        });

        // Assert Inbox is purged
        Awaitility.waitAtMost(Duration.ofSeconds(3)).untilAsserted(() -> {
            assertThat(durableQueues.getTotalDeadLetterMessagesQueuedFor(queueName)).isEqualTo(0);
            assertThat(durableQueues.getTotalMessagesQueuedFor(queueName)).isEqualTo(0);
        });
    }

    /**
     * TestOrderEventProcessor is a simple EventProcessor that:
     * <ul>
     *     <li>Reacts to events for the "TestOrders" aggregate type</li>
     *     <li>Handles PlaceOrderCommand by persisting an OrderPlacedEvent</li>
     *     <li>Handles OrderPlacedEvent (via a @MessageHandler) by sending a ConfirmOrderCommand synchronously</li>
     *     <li>Handles ConfirmOrderCommand by persisting an OrderConfirmedEvent</li>
     *     <li>Handles FailingCommand by throwing an exception</li>
     * </ul>
     */
    public static class TestOrderViewEventProcessor extends ViewEventProcessor {
        public static final AggregateType TEST_ORDERS = AggregateType.of("TestOrders");
        private final PostgresqlEventStore<?> eventStore;
        private final ConcurrentMap<AggregateType, GlobalEventOrder> resetPoints = new ConcurrentHashMap<>();
        private Consumer<ConcurrentMap<AggregateType, GlobalEventOrder>> resetCallback;
        private final AtomicInteger orderPlacedEventCounter = new AtomicInteger(0);
        private final ConcurrentMap<String, AtomicInteger> sqlFailureAttempts = new ConcurrentHashMap<>();
        private final AtomicInteger appendedEventsBeforeFailing = new AtomicInteger(0);
        /**
         * Appends the registered events when the {@link UnitOfWork} commits - the way an aggregate repository's
         * {@link UnitOfWorkLifecycleCallback} persists an aggregate's uncommitted events. Doesn't override
         * {@link UnitOfWorkLifecycleCallback#hasPendingChanges(Object)}, so every resource counts as having pending changes
         */
        private final UnitOfWorkLifecycleCallback<EventProcessorIT.OrderConfirmedEvent> appendEventsWhenCommitting = new UnitOfWorkLifecycleCallback<>() {
            @Override
            public BeforeCommitProcessingStatus beforeCommit(UnitOfWork unitOfWork, List<EventProcessorIT.OrderConfirmedEvent> associatedResources) {
                associatedResources.forEach(event -> eventStore.appendToStream(TEST_ORDERS, event.orderId, event));
                return BeforeCommitProcessingStatus.COMPLETED;
            }

            @Override
            public void afterCommit(UnitOfWork unitOfWork, List<EventProcessorIT.OrderConfirmedEvent> associatedResources) {
            }

            @Override
            public void beforeRollback(UnitOfWork unitOfWork, List<EventProcessorIT.OrderConfirmedEvent> associatedResources, Throwable causeOfTheRollback) {
            }

            @Override
            public void afterRollback(UnitOfWork unitOfWork, List<EventProcessorIT.OrderConfirmedEvent> associatedResources, Throwable causeOfTheRollback) {
            }
        };

        /**
         * Reports every registered resource as unchanged, so committing the {@link UnitOfWork} leaves it untouched - the
         * way an aggregate repository's {@link UnitOfWorkLifecycleCallback} treats an aggregate that only was loaded
         */
        private final UnitOfWorkLifecycleCallback<EventProcessorIT.OrderConfirmedEvent> neverChangedResources = new UnitOfWorkLifecycleCallback<>() {
            @Override
            public boolean hasPendingChanges(EventProcessorIT.OrderConfirmedEvent resource) {
                return false;
            }

            @Override
            public BeforeCommitProcessingStatus beforeCommit(UnitOfWork unitOfWork, List<EventProcessorIT.OrderConfirmedEvent> associatedResources) {
                return BeforeCommitProcessingStatus.COMPLETED;
            }

            @Override
            public void afterCommit(UnitOfWork unitOfWork, List<EventProcessorIT.OrderConfirmedEvent> associatedResources) {
            }

            @Override
            public void beforeRollback(UnitOfWork unitOfWork, List<EventProcessorIT.OrderConfirmedEvent> associatedResources, Throwable causeOfTheRollback) {
            }

            @Override
            public void afterRollback(UnitOfWork unitOfWork, List<EventProcessorIT.OrderConfirmedEvent> associatedResources, Throwable causeOfTheRollback) {
            }
        };

        public TestOrderViewEventProcessor(ViewEventProcessorDependencies eventProcessorDependencies,
                                           PostgresqlEventStore<?> eventStore) {
            super(eventProcessorDependencies);
            this.eventStore = eventStore;
            eventStore.addAggregateEventStreamConfiguration(TEST_ORDERS,
                                                            AggregateIdSerializer.serializerFor(EventProcessorIT.OrderId.class));
        }

        @Override
        public String getProcessorName() {
            return "TestOrderViewEventProcessor";
        }

        public DurableQueues getDurableQueuesForTesting() {
            return super.getDurableQueues();
        }

        public void setResetCallback(Consumer<ConcurrentMap<AggregateType, GlobalEventOrder>> resetCallback) {
            this.resetCallback = resetCallback;
        }

        @Override
        protected List<AggregateType> reactsToEventsRelatedToAggregateTypes() {
            return List.of(TEST_ORDERS);
        }

        @Override
        protected RedeliveryPolicy getDurableQueueRedeliveryPolicy() {
            return RedeliveryPolicy.fixedBackoff(Duration.ofMillis(200), 3);
        }

        @Override
        protected void onSubscriptionsReset(AggregateType aggregateType, GlobalEventOrder resubscribeFromAndIncluding) {
            if (resetPoints.containsKey(aggregateType)) {
                throw new IllegalStateException("resetPoints already contains key for " + aggregateType);
            }
            resetPoints.put(aggregateType, resubscribeFromAndIncluding);
            resetCallback.accept(resetPoints);
        }

        // ----- Command Handlers -----

        @CmdHandler
        public void handle(EventProcessorIT.PlaceOrderCommand cmd) {
            if (!eventStore.hasEventStream(TEST_ORDERS, cmd.orderId)) {
                // Start a new event stream for this order
                var event = new EventProcessorIT.OrderPlacedEvent(cmd.orderId, cmd.orderDetails);
                eventStore.startStream(TEST_ORDERS, cmd.orderId, List.of(event));
            }
        }

        @CmdHandler
        public void handle(EventProcessorIT.ConfirmOrderCommand cmd) {
            var eventStream = eventStore.fetchStream(TEST_ORDERS, cmd.orderId);
            if (eventStream.isPresent() && !Lists.last(eventStream.get().eventList()).get().event().getEventTypeAsJavaClass().get().equals(EventProcessorIT.OrderConfirmedEvent.class)) {
                // Append the confirmation event to the existing stream
                var event = new EventProcessorIT.OrderConfirmedEvent(cmd.orderId);
                eventStore.appendToStream(TEST_ORDERS, cmd.orderId, event);
            }
        }

        @CmdHandler
        public void handle(EventProcessorIT.FailingCommandSentUsingAsyncAndDontWaitViaCommandBus cmd) {
            System.out.println("*** Handling FailingCommand: " + cmd.reason);
            throw new RuntimeException(cmd.reason);
        }

        // ----- Message Handler -----

        @MessageHandler
        public void handle(EventProcessorIT.FailingCommandSentViaInbox cmd) {
            System.out.println("*** Handling FailingCommand: " + cmd.reason);
            throw new RuntimeException(cmd.reason);
        }

        /**
         * When an OrderPlacedEvent is processed, send a ConfirmOrderCommand synchronously.
         */
        @MessageHandler
        public void onOrderPlaced(EventProcessorIT.OrderPlacedEvent event) {
            var unitOfWork = eventStore.getUnitOfWorkFactory().getRequiredUnitOfWork();
            if (event.orderDetails.startsWith("Load")) {
                orderPlacedEventCounter.incrementAndGet();
            } else if (event.orderDetails.startsWith("Fail")) {
                throw new RuntimeException(event.orderDetails);
            } else if (event.orderDetails.equals(APPENDS_EVENT_THEN_FAILS)) {
                eventStore.appendToStream(TEST_ORDERS, event.orderId, new EventProcessorIT.OrderConfirmedEvent(event.orderId));
                appendedEventsBeforeFailing.incrementAndGet();
                throw new RuntimeException(event.orderDetails);
            } else if (event.orderDetails.equals(REGISTERS_RESOURCE_THEN_FAILS)) {
                unitOfWork.registerLifecycleCallbackForResource(new EventProcessorIT.OrderConfirmedEvent(event.orderId), appendEventsWhenCommitting);
                throw new RuntimeException(event.orderDetails);
            } else if (event.orderDetails.equals(REGISTERS_UNCHANGED_RESOURCE_THEN_FAILS)) {
                unitOfWork.registerLifecycleCallbackForResource(new EventProcessorIT.OrderConfirmedEvent(event.orderId), neverChangedResources);
                throw new RuntimeException(event.orderDetails);
            } else if (event.orderDetails.equals(JOINS_UNIT_OF_WORK_THEN_FAILS)) {
                eventStore.getUnitOfWorkFactory().usingUnitOfWork(joinedUnitOfWork -> {
                    throw new RuntimeException(event.orderDetails);
                });
            } else if (event.orderDetails.equals(SQL_FAILS_ON_FIRST_ATTEMPT)) {
                var orderId = event.orderId.toString();
                var attempt = sqlFailureAttempts.computeIfAbsent(orderId, id -> new AtomicInteger()).incrementAndGet();
                if (attempt == 1) {
                    // A partial view update, followed by a statement that violates the primary key and aborts the transaction
                    unitOfWork.handle().execute("INSERT INTO order_view (order_id, status) VALUES (?, 'partial')", orderId);
                    unitOfWork.handle().execute("INSERT INTO order_view (order_id, status) VALUES (?, 'partial')", orderId);
                } else {
                    unitOfWork.handle().execute("INSERT INTO order_view (order_id, status) VALUES (?, 'done')", orderId);
                }
            } else {
                getCommandBus().send(new EventProcessorIT.ConfirmOrderCommand(event.orderId));
            }
        }

        public AtomicInteger getOrderPlacedEventCounter() {
            return orderPlacedEventCounter;
        }

        public int getAppendedEventsBeforeFailing() {
            return appendedEventsBeforeFailing.get();
        }

        public int getSqlFailureAttempts(EventProcessorIT.OrderId orderId) {
            var attempts = sqlFailureAttempts.get(orderId.toString());
            return attempts != null ? attempts.get() : 0;
        }
    }
}
