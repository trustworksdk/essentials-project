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

import dk.trustworks.essentials.components.foundation.json.EssentialsObjectMappers;
import tools.jackson.databind.*;
import dk.trustworks.essentials.components.distributed.fencedlock.postgresql.PostgresqlFencedLockManager;
import dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.PostgresqlEventStore;
import dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.eventstream.*;
import dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.gap.PostgresqlEventStreamGapHandler;
import dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.observability.EventStoreSubscriptionObserver;
import dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.persistence.*;
import dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.persistence.table_per_aggregate_type.*;
import dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.serializer.AggregateIdSerializer;
import dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.serializer.json.*;
import dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.subscription.*;
import dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.transaction.*;
import dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.types.*;
import dk.trustworks.essentials.components.foundation.messaging.*;
import dk.trustworks.essentials.components.foundation.messaging.eip.store_and_forward.*;
import dk.trustworks.essentials.components.foundation.messaging.queue.*;
import dk.trustworks.essentials.components.foundation.postgresql.SqlExecutionTimeLogger;
import dk.trustworks.essentials.components.foundation.reactive.command.*;
import dk.trustworks.essentials.components.foundation.types.*;
import dk.trustworks.essentials.components.queue.postgresql.PostgresqlDurableQueues;
import dk.trustworks.essentials.reactive.command.CmdHandler;
import dk.trustworks.essentials.shared.collections.Lists;
import dk.trustworks.essentials.types.CharSequenceType;
import org.jdbi.v3.core.Jdbi;
import org.jdbi.v3.postgres.PostgresPlugin;
import org.junit.jupiter.api.*;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.*;
import org.testcontainers.shaded.org.awaitility.Awaitility;

import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;
import java.util.stream.IntStream;
import org.slf4j.*;

import static dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.persistence.table_per_aggregate_type.SeparateTablePerAggregateTypeEventStreamConfigurationFactory.standardSingleTenantConfiguration;
import static dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.processor.EventProcessorIT.TestOrderEventProcessor.TEST_ORDERS;
import static org.assertj.core.api.Assertions.*;

/**
 * Integration test for the EventProcessor using the following scenarios:
 * <ol>
 *   <li>A PlaceOrderCommand causes an OrderPlacedEvent to be persisted.</li>
 *   <li>The OrderPlacedEvent is handled by a @MessageHandler which synchronously sends a ConfirmOrderCommand via getCommandBus().send(...),
 *       whose handler then persists an OrderConfirmedEvent.</li>
 *   <li>A FailingCommand sent via getInbox().sendAndDontWait(...) always fails.</li>
 * </ol>
 */
@Testcontainers
public class EventProcessorIT {
    private static final Logger log = LoggerFactory.getLogger(EventProcessorIT.class);

    public static final EventMetaData META_DATA = EventMetaData.of("Key1", "Value1", "Key2", "Value2");

    private Jdbi                                                                    jdbi;
    private EventStoreUnitOfWorkFactory<EventStoreUnitOfWork>                       unitOfWorkFactory;
    private TestPersistableEventMapper                                              eventMapper;
    private PostgresqlEventStore<SeparateTablePerAggregateEventStreamConfiguration> eventStore;

    @Container
    private final PostgreSQLContainer postgreSQLContainer = new PostgreSQLContainer("postgres:18.4")
            .withDatabaseName("event-store")
            .withUsername("test-user")
            .withPassword("secret-password");

    private TestOrderEventProcessor          testProcessor;
    private PostgresqlDurableQueues          durableQueues;
    private EventStoreSubscriptionManager    eventStoreSubscriptionManager;
    private PostgresqlFencedLockManager      fencedLockManager;
    private Inboxes.DurableQueueBasedInboxes inboxes;
    private DurableLocalCommandBus           commandBus;
    private DurableSubscriptionRepository    durableSubscriptionRepository;
    /**
     * "processorName:EventType" per {@link EventStoreSubscriptionObserver#eventTypeNotHandledByProcessor} call
     */
    private final List<String>               eventsNotHandledByProcessor = new CopyOnWriteArrayList<>();

    @BeforeEach
    void setup() {
        jdbi = Jdbi.create(postgreSQLContainer.getJdbcUrl(),
                           postgreSQLContainer.getUsername(),
                           postgreSQLContainer.getPassword());
        jdbi.installPlugin(new PostgresPlugin());
        jdbi.setSqlLogger(new SqlExecutionTimeLogger());

        unitOfWorkFactory = new EventStoreManagedUnitOfWorkFactory(jdbi);
        eventMapper = new TestPersistableEventMapper();
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
                                             public void eventTypeNotHandledByProcessor(String processorName, PersistedEvent event) {
                                                 eventsNotHandledByProcessor.add(processorName + ":" + event.event().getEventTypeAsJavaClass().map(Class::getSimpleName).orElse("?"));
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
                                                                     .build();
        eventStoreSubscriptionManager.start();

        durableQueues = PostgresqlDurableQueues.builder()
                                               .setJsonSerializer(jsonSerializer)
                                               .setMessageHandlingTimeout(Duration.ofSeconds(2))
                                               .setUnitOfWorkFactory(unitOfWorkFactory)
                                               .build();
        durableQueues.start();

        inboxes = new Inboxes.DurableQueueBasedInboxes(durableQueues, fencedLockManager);

        commandBus = DurableLocalCommandBus.builder()
                                           .setInterceptors(new UnitOfWorkControllingCommandBusInterceptor(unitOfWorkFactory))
                                           .setDurableQueues(durableQueues)
                                           .setCommandQueueRedeliveryPolicy(RedeliveryPolicy.fixedBackoff(Duration.ofMillis(200), 3))
                                           .build();
        commandBus.start();

        testProcessor = new TestOrderEventProcessor(new EventProcessorDependencies(eventStoreSubscriptionManager,
                                                                                   inboxes,
                                                                                   commandBus,
                                                                                   List.of()),
                                                    eventStore);
        testProcessor.start();
    }

    @AfterEach
    void teardown() {
        if (testProcessor != null) {
            testProcessor.stop();
        }
        if (eventStoreSubscriptionManager != null) {
            eventStoreSubscriptionManager.stop();
        }
        if (fencedLockManager != null) {
            fencedLockManager.stop();
        }
        if (commandBus != null) {
            commandBus.stop();
        }
        if (durableQueues != null) {
            durableQueues.stop();
        }
    }


    // -------------------------------------------------------------------------------------------------------------------

    /**
     * Scenario 1 & 2:
     * Sending a PlaceOrderCommand should cause an OrderPlacedEvent to be persisted.
     * Its MessageHandler then synchronously sends a ConfirmOrderCommand which persists an OrderConfirmedEvent.
     */
    @Test
    void testPlaceOrderAndConfirmationFlow() throws Exception {
        var orderId      = OrderId.random();
        var orderDetails = "Test order details";
        var placeCmd     = new PlaceOrderCommand(orderId, orderDetails);

        // Send the command via the command bus - the command bus' UnitOfWork has committed the OrderPlacedEvent when send returns
        commandBus.send(placeCmd);
        var orderPlaced = unitOfWorkFactory.withUnitOfWork(() -> eventStore.fetchStream(TEST_ORDERS, orderId).get().eventList().get(0));

        // ES-4: wait for the processor instead of polling the event stream under a guessed timeout. The subscription only
        // forwards the event to the Inbox, so the barrier also waits for the Inbox to have handled it
        testProcessor.awaitProcessed(orderPlaced, Duration.ofSeconds(10));

        // We expect two events: first the OrderPlacedEvent then the OrderConfirmedEvent, appended by the Inbox handler
        var events = unitOfWorkFactory.withUnitOfWork(() -> eventStore.fetchStream(TEST_ORDERS, orderId).get().eventList());
        assertThat(events).hasSize(2);
        var event0 = events.get(0);
        var event1 = events.get(1);
        assertThat(event0.event().getEventTypeAsJavaClass()).isPresent().isEqualTo(Optional.of(OrderPlacedEvent.class));
        assertThat(event1.event().getEventTypeAsJavaClass()).isPresent().isEqualTo(Optional.of(OrderConfirmedEvent.class));
        assertThat(testProcessor.getOrderPlacedEventCounter().get()).isEqualTo(1);
        assertThat(testProcessor.getInboxForTesting().hasOrderedMessageQueuedForKey(orderId.toString())).isFalse();
    }

    /**
     * ES-4 + CB-4: the processor has no handler for {@link OrderConfirmedEvent}. The barrier still releases for it, and the
     * ignored event is reported to the {@link EventStoreSubscriptionObserver}
     */
    @Test
    void awaitProcessedAsync_releases_for_an_event_type_the_processor_ignores_and_the_ignored_type_is_reported() {
        var orderId = OrderId.random();
        var placed = unitOfWorkFactory.withUnitOfWork(() -> eventStore.startStream(TEST_ORDERS, orderId, List.of(new OrderPlacedEvent(orderId, "Test order details")))
                                                                      .firstEvent());
        testProcessor.awaitProcessedAsync(TEST_ORDERS, placed.globalEventOrder(), Duration.ofSeconds(10)).block(Duration.ofSeconds(15));
        var confirmed = unitOfWorkFactory.withUnitOfWork(() -> eventStore.fetchStream(TEST_ORDERS, orderId).get().eventList().get(1));

        testProcessor.awaitProcessedAsync(TEST_ORDERS, confirmed.globalEventOrder(), Duration.ofSeconds(10)).block(Duration.ofSeconds(15));

        assertThat(eventsNotHandledByProcessor).containsExactly("TestOrderEventProcessor:OrderConfirmedEvent");
    }

    @Test
    void awaitProcessed_times_out_naming_the_processor_the_event_and_the_resume_point() {
        var orderId = OrderId.random();
        var placed = unitOfWorkFactory.withUnitOfWork(() -> eventStore.startStream(TEST_ORDERS, orderId, List.of(new OrderPlacedEvent(orderId, "Test order details")))
                                                                      .firstEvent());
        testProcessor.awaitProcessed(placed, Duration.ofSeconds(10));
        var neverAppended = GlobalEventOrder.of(placed.globalEventOrder().longValue() + 100);

        assertThatThrownBy(() -> testProcessor.awaitProcessed(TEST_ORDERS, neverAppended, Duration.ofMillis(300)))
                .isInstanceOf(AwaitProcessedTimeoutException.class)
                .hasMessageContaining("Processor 'TestOrderEventProcessor' had not processed the 'TestOrders' event with global-event-order " + neverAppended)
                .hasMessageContaining("the subscription hasn't moved past the event yet")
                .hasMessageContaining("Resume point (next global-event-order to handle): ")
                .hasMessageContaining("0 queued message(s) and 0 dead-letter message(s)");
    }

    /**
     * ES-4: an {@link InTransactionEventProcessor} handles the event in the appending UnitOfWork, so the barrier is
     * satisfied as soon as that UnitOfWork has committed. CB-4: an event type it ignores is reported
     */
    @Test
    void an_in_transaction_processor_has_processed_the_event_once_the_appending_unit_of_work_committed() {
        var inTransactionProcessor = new TestInTransactionOrderProcessor(new EventProcessorDependencies(eventStoreSubscriptionManager, inboxes, commandBus, List.of()));
        inTransactionProcessor.start();
        try {
            var orderId = OrderId.random();
            var placed = unitOfWorkFactory.withUnitOfWork(() -> eventStore.startStream(TEST_ORDERS, orderId, List.of(new OrderPlacedEvent(orderId, "Test order details")))
                                                                          .firstEvent());

            inTransactionProcessor.awaitProcessed(placed, Duration.ZERO);
            inTransactionProcessor.awaitProcessedAsync(TEST_ORDERS, placed.globalEventOrder(), Duration.ZERO).block(Duration.ofSeconds(1));
            assertThat(inTransactionProcessor.ordersPlaced).containsExactly(orderId);

            unitOfWorkFactory.usingUnitOfWork(() -> eventStore.appendToStream(TEST_ORDERS, orderId, new OrderConfirmedEvent(orderId)));
            assertThat(eventsNotHandledByProcessor).contains("TestInTransactionOrderProcessor:OrderConfirmedEvent");
        } finally {
            inTransactionProcessor.stop();
        }
    }

    /**
     * A stopped processor that is started again subscribes again with the same subscriber ids. Its stop() must therefore
     * unsubscribe, not just stop, its subscriptions - the manager hands a subscription it still holds back to that
     * subscribe call without starting it - and its start() must consume from the Inbox its stop() stopped consuming
     */
    @Test
    void an_event_processor_that_is_stopped_and_started_again_handles_an_event_appended_after_the_restart() {
        // Running before it stops: the subscription holds its fenced lock and the Inbox consumes. Not straight after
        // start(): a stop while the first lock-acquiring tick is still in flight can leave the lock held for the stopped
        // subscription - an open race, RW-11 in docs/ROADMAP-0.6x.md, which this test does not cover
        Awaitility.waitAtMost(Duration.ofSeconds(10))
                  .until(() -> testProcessor.isActive() && testProcessor.getInboxForTesting().isConsumingMessages());
        testProcessor.stop();
        testProcessor.start();
        assertThat(testProcessor.isStarted()).isTrue();

        var orderId = OrderId.random();
        var placed = unitOfWorkFactory.withUnitOfWork(() -> eventStore.startStream(TEST_ORDERS, orderId, List.of(new OrderPlacedEvent(orderId, "Test order details")))
                                                                      .firstEvent());

        testProcessor.awaitProcessed(placed, Duration.ofSeconds(10));
        Awaitility.waitAtMost(Duration.ofSeconds(10))
                  .untilAsserted(() -> assertThat(testProcessor.getOrderPlacedEventCounter().get()).isEqualTo(1));
    }

    /**
     * The {@link InTransactionEventProcessor} counterpart of
     * {@link #an_event_processor_that_is_stopped_and_started_again_handles_an_event_appended_after_the_restart()}
     */
    @Test
    void an_in_transaction_processor_that_is_stopped_and_started_again_handles_an_event_appended_after_the_restart() {
        var inTransactionProcessor = new TestInTransactionOrderProcessor(new EventProcessorDependencies(eventStoreSubscriptionManager, inboxes, commandBus, List.of()));
        inTransactionProcessor.start();
        try {
            inTransactionProcessor.stop();
            inTransactionProcessor.start();
            assertThat(inTransactionProcessor.isStarted()).isTrue();

            var orderId = OrderId.random();
            unitOfWorkFactory.usingUnitOfWork(() -> eventStore.startStream(TEST_ORDERS, orderId, List.of(new OrderPlacedEvent(orderId, "Test order details"))));

            assertThat(inTransactionProcessor.ordersPlaced).containsExactly(orderId);
        } finally {
            inTransactionProcessor.stop();
        }
    }

    /**
     * The exclusive ({@link InTransactionEventProcessor#isUseExclusively()}) variant of
     * {@link #an_in_transaction_processor_that_is_stopped_and_started_again_handles_an_event_appended_after_the_restart()}:
     * its subscriptions hand events to the handler only while they hold their fenced lock
     */
    @Test
    void an_exclusive_in_transaction_processor_that_is_stopped_and_started_again_handles_an_event_appended_after_the_restart() {
        var inTransactionProcessor = new TestInTransactionOrderProcessor(new EventProcessorDependencies(eventStoreSubscriptionManager, inboxes, commandBus, List.of()), true);
        inTransactionProcessor.start();
        try {
            // Holding its fenced lock before it stops. Not straight after start(): a stop while the first lock-acquiring
            // tick is still in flight can leave the lock held for the stopped subscription - an open race, RW-11 in
            // docs/ROADMAP-0.6x.md, which this test does not cover
            Awaitility.waitAtMost(Duration.ofSeconds(10))
                      .until(inTransactionProcessor::isActive);
            inTransactionProcessor.stop();
            inTransactionProcessor.start();
            assertThat(inTransactionProcessor.isStarted()).isTrue();
            // The restarted subscription acquires the fenced lock again before it hands events on
            Awaitility.waitAtMost(Duration.ofSeconds(10))
                      .until(inTransactionProcessor::isActive);

            var orderId = OrderId.random();
            unitOfWorkFactory.usingUnitOfWork(() -> eventStore.startStream(TEST_ORDERS, orderId, List.of(new OrderPlacedEvent(orderId, "Test order details"))));

            assertThat(inTransactionProcessor.ordersPlaced).containsExactly(orderId);
        } finally {
            inTransactionProcessor.stop();
        }
    }

    /**
     * Scenario 3A:
     * Send a FailingCommandSentUsingAsyncAndDontWaitViaCommandBus via the CommandBus. Its handler always throws an exception.
     * This test also verifies that such a command does not prevent the integration test from shutting down.
     */
    @Test
    void testFailingCommandSendViaCommandBus_sendAndDontWait_EventuallyEndsUpInAsADeadLetterMessage() throws Exception {
        var orderId    = OrderId.random();
        var failingCmd = new FailingCommandSentUsingAsyncAndDontWaitViaCommandBus(orderId, "Intentional failure for testing");

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
        assertThat(deadLetterMessages.get(0).getPayload()).isInstanceOf(FailingCommandSentUsingAsyncAndDontWaitViaCommandBus.class);
    }

    /**
     * Scenario 3B:
     * Send a FailingCommandSentViaInbox via the event processors Inbox. Its handler always throws an exception.
     */
    @Test
    void testFailingCommandSentViaInboxEventuallyEndsUpInAsADeadLetterMessage() throws Exception {
        var orderId    = OrderId.random();
        var failingCmd = new FailingCommandSentViaInbox(orderId, "Intentional failure for testing");

        // Verify we don't have any dead-letter messages
        var queueName = testProcessor.getInboxForTesting().name().asQueueName();
        assertThat(durableQueues.getTotalDeadLetterMessagesQueuedFor(queueName)).isEqualTo(0);

        // Send the failing command using the processor's inbox (asynchronous, fire-and-forget)
        testProcessor.getInboxForTesting().addMessageReceived(failingCmd);

        Awaitility.waitAtMost(Duration.ofSeconds(2))
                  .untilAsserted(() -> {
                      assertThat(durableQueues.getTotalDeadLetterMessagesQueuedFor(queueName)).isEqualTo(1);
                  });

        var deadLetterMessages = durableQueues.getDeadLetterMessages(queueName, DurableQueues.QueueingSortOrder.ASC, 0, 10);
        assertThat(deadLetterMessages).hasSize(1);
        assertThat(deadLetterMessages.get(0).getPayload()).isInstanceOf(FailingCommandSentViaInbox.class);
    }

    @Test
    public void testResetAllSubscriptions() {
        IntStream.range(0, 5).forEach(i -> {
            var orderId      = OrderId.random();
            var orderDetails = "Test order details";
            var placeCmd     = new PlaceOrderCommand(orderId, orderDetails);
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

        var queueName = testProcessor.getInboxForTesting().name().asQueueName();
        assertThat(durableQueues.getTotalDeadLetterMessagesQueuedFor(queueName)).isEqualTo(0);
        assertThat(durableQueues.getTotalMessagesQueuedFor(queueName)).isEqualTo(0);
        // Send the failing command using the processor's inbox (asynchronous, fire-and-forget)
        var failingCmd = new FailingCommandSentViaInbox(OrderId.random(), "Intentional failure for testing");
        testProcessor.getInboxForTesting().addMessageReceived(failingCmd);

        assertThat(durableQueues.getTotalMessagesQueuedFor(queueName)).isEqualTo(1);

        // Set a callback to verify that we do reset resumePoints
        testProcessor.setResetCallback(resetPoints -> {
            Awaitility.waitAtMost(Duration.ofSeconds(2)).untilAsserted(() -> {
                var currentEventOrder = eventStoreSubscriptionManager.getCurrentEventOrder(subscriberId, TEST_ORDERS);
                log.info("After resetting: Current event order: {}", currentEventOrder);
                assertThat(currentEventOrder)
                        .hasValueSatisfying(order ->
                                                    assertThat(order.longValue()).isEqualTo(1)
                                           )
                        .describedAs("After resetting: Current event order");
                var currentResumePoint = durableSubscriptionRepository.getResumePoint(subscriberId, TEST_ORDERS);
                log.info("After resetting: Current resume point: {}", currentResumePoint);
                assertThat(currentResumePoint)
                        .hasValueSatisfying(resumePoint -> assertThat(resumePoint.getResumeFromAndIncluding().longValue()).isEqualTo(1))
                        .describedAs("After resetting: Current resume point");
            });
        });
        testProcessor.resetAllSubscriptions();

        // Check subscriptions catchup again
        Awaitility.waitAtMost(Duration.ofSeconds(3)).untilAsserted(() -> {
            var currentEventOrder = eventStoreSubscriptionManager.getCurrentEventOrder(subscriberId, TEST_ORDERS);
            log.info("After resetting: Current event order: {}", currentEventOrder);
            assertThat(currentEventOrder)
                    .hasValueSatisfying(order ->
                                                assertThat(order.longValue()).isEqualTo(11)
                                       )
                    .describedAs("After resetting: Current event order");
            var currentResumePoint = durableSubscriptionRepository.getResumePoint(subscriberId, TEST_ORDERS);
            log.info("After resetting: Current resume point: {}", currentResumePoint);
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

    @Test
    public void verify_view_event_processor_with_load() {
        unitOfWorkFactory.usingUnitOfWork(uow -> {
            IntStream.range(1, 1001).sequential().forEach(value -> {
                var orderId = EventProcessorIT.OrderId.random();
                var event   = new EventProcessorIT.OrderPlacedEvent(orderId, "Load Order Details " + value);
                eventStore.appendToStream(ViewEventProcessorIT.TestOrderViewEventProcessor.TEST_ORDERS, orderId, List.of(event));
            });
        });

        org.awaitility.Awaitility.waitAtMost(Duration.ofMinutes(2)).untilAsserted(() -> {
            int orderPlacedEventCounter = testProcessor.getOrderPlacedEventCounter().get();
            assert orderPlacedEventCounter == 1000;
        });
    }


    // --------------------------
    // Supporting classes for the test
    // --------------------------

    /**
     * OrderId
     */
    public static class OrderId extends CharSequenceType<OrderId> {
        protected OrderId(CharSequence value) {
            super(value);
        }

        public static OrderId random() {
            return new OrderId(RandomIdGenerator.generate());
        }

        public static OrderId of(CharSequence id) {
            return new OrderId(id);
        }
    }

    // --- Commands ---

    public static class PlaceOrderCommand {
        public final OrderId orderId;
        public final String  orderDetails;

        public PlaceOrderCommand(OrderId orderId, String orderDetails) {
            this.orderId = orderId;
            this.orderDetails = orderDetails;
        }
    }

    public static class ConfirmOrderCommand {
        public final OrderId orderId;

        public ConfirmOrderCommand(OrderId orderId) {
            this.orderId = orderId;
        }
    }

    public static class FailingCommandSentViaInbox {
        public final OrderId orderId;
        public final String  reason;

        public FailingCommandSentViaInbox(OrderId orderId, String reason) {
            this.orderId = orderId;
            this.reason = reason;
        }
    }

    public static class FailingCommandSentUsingAsyncAndDontWaitViaCommandBus {
        public final OrderId orderId;
        public final String  reason;

        public FailingCommandSentUsingAsyncAndDontWaitViaCommandBus(OrderId orderId, String reason) {
            this.orderId = orderId;
            this.reason = reason;
        }
    }

    // --- Events ---

    @Revision(1)
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

    // --------------------------
    // Test EventProcessor
    // --------------------------

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
    public static class TestInTransactionOrderProcessor extends InTransactionEventProcessor {
        final List<OrderId> ordersPlaced = new CopyOnWriteArrayList<>();

        public TestInTransactionOrderProcessor(EventProcessorDependencies eventProcessorDependencies) {
            super(eventProcessorDependencies);
        }

        public TestInTransactionOrderProcessor(EventProcessorDependencies eventProcessorDependencies, boolean useExclusively) {
            super(eventProcessorDependencies, useExclusively);
        }

        @Override
        public String getProcessorName() {
            return "TestInTransactionOrderProcessor";
        }

        @Override
        protected List<AggregateType> reactsToEventsRelatedToAggregateTypes() {
            return List.of(TEST_ORDERS);
        }

        @MessageHandler
        void onOrderPlaced(OrderPlacedEvent event) {
            ordersPlaced.add(event.orderId);
        }
    }

    public static class TestOrderEventProcessor extends EventProcessor {
        public static final AggregateType TEST_ORDERS = AggregateType.of("TestOrders");

        private final PostgresqlEventStore<?> eventStore;

        private final ConcurrentMap<AggregateType, GlobalEventOrder> resetPoints = new ConcurrentHashMap<>();

        private Consumer<ConcurrentMap<AggregateType, GlobalEventOrder>> resetCallback;

        private final AtomicInteger orderPlacedEventCounter = new AtomicInteger(0);

        public TestOrderEventProcessor(EventProcessorDependencies eventProcessorDependencies,
                                       PostgresqlEventStore<?> eventStore) {
            this(eventProcessorDependencies, eventStore,
                 resetPoints -> {
                     throw new IllegalStateException("No reset callback configured");
                 });
        }

        public TestOrderEventProcessor(EventProcessorDependencies eventProcessorDependencies,
                                       PostgresqlEventStore<?> eventStore,
                                       Consumer<ConcurrentMap<AggregateType, GlobalEventOrder>> resetCallback) {
            super(eventProcessorDependencies);
            this.eventStore = eventStore;
            this.resetCallback = resetCallback;
            eventStore.addAggregateEventStreamConfiguration(TEST_ORDERS,
                                                            AggregateIdSerializer.serializerFor(OrderId.class));
        }

        public void setResetCallback(Consumer<ConcurrentMap<AggregateType, GlobalEventOrder>> resetCallback) {
            this.resetCallback = resetCallback;
        }

        @Override
        public String getProcessorName() {
            return "TestOrderEventProcessor";
        }

        @Override
        protected List<AggregateType> reactsToEventsRelatedToAggregateTypes() {
            return List.of(TEST_ORDERS);
        }


        public Inbox getInboxForTesting() {
            return super.getInbox();
        }

        @Override
        protected RedeliveryPolicy getInboxRedeliveryPolicy() {
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
        public void handle(PlaceOrderCommand cmd) {
            if (!eventStore.hasEventStream(TEST_ORDERS, cmd.orderId)) {
                // Start a new event stream for this order
                var event = new OrderPlacedEvent(cmd.orderId, cmd.orderDetails);
                eventStore.startStream(TEST_ORDERS, cmd.orderId, List.of(event));
            }
        }

        @CmdHandler
        public void handle(ConfirmOrderCommand cmd) {
            var eventStream = eventStore.fetchStream(TEST_ORDERS, cmd.orderId);
            if (eventStream.isPresent() && !Lists.last(eventStream.get().eventList()).get().event().getEventTypeAsJavaClass().get().equals(OrderConfirmedEvent.class)) {
                // Append the confirmation event to the existing stream
                var event = new OrderConfirmedEvent(cmd.orderId);
                eventStore.appendToStream(TEST_ORDERS, cmd.orderId, event);
            }
        }


        @CmdHandler
        public void handle(FailingCommandSentUsingAsyncAndDontWaitViaCommandBus cmd) {
            log.info("*** Handling FailingCommand: {}", cmd.reason);
            throw new RuntimeException(cmd.reason);
        }


        // ----- Message Handler -----

        @MessageHandler
        public void handle(FailingCommandSentViaInbox cmd) {
            log.info("*** Handling FailingCommand: {}", cmd.reason);
            throw new RuntimeException(cmd.reason);
        }

        /**
         * When an OrderPlacedEvent is processed, send a ConfirmOrderCommand synchronously.
         */
        @MessageHandler
        public void onOrderPlaced(OrderPlacedEvent event) {
            orderPlacedEventCounter.incrementAndGet();
            getCommandBus().send(new ConfirmOrderCommand(event.orderId));
        }

        public AtomicInteger getOrderPlacedEventCounter() {
            return orderPlacedEventCounter;
        }
    }


    // -------------------------------------------------------------------------------------------------------------------

    public static ObjectMapper createObjectMapper() {
        return EssentialsObjectMappers.createJackson3ObjectMapper();
    }

    public static class TestPersistableEventMapper implements PersistableEventMapper {
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
                                         null, // Leave reading the EventRevision to the PersistableEvent's from method
                                         META_DATA,
                                         OffsetDateTime.now(),
                                         causedByEventId,
                                         correlationId,
                                         null);
        }
    }
}
