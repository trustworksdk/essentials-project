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

import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
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
import dk.trustworks.essentials.components.foundation.messaging.eip.store_and_forward.MessageHandlerInterceptor;
import dk.trustworks.essentials.components.foundation.messaging.eip.store_and_forward.operation.InvokeMessageHandlerMethod;
import dk.trustworks.essentials.components.foundation.messaging.queue.*;
import dk.trustworks.essentials.components.foundation.postgresql.SqlExecutionTimeLogger;
import dk.trustworks.essentials.components.foundation.reactive.command.*;
import dk.trustworks.essentials.components.foundation.transaction.*;
import dk.trustworks.essentials.components.foundation.types.SubscriberId;
import dk.trustworks.essentials.components.queue.postgresql.PostgresqlDurableQueues;
import dk.trustworks.essentials.reactive.command.CmdHandler;
import dk.trustworks.essentials.shared.Exceptions;
import dk.trustworks.essentials.shared.collections.Lists;
import dk.trustworks.essentials.shared.interceptor.InterceptorChain;
import org.awaitility.Awaitility;
import org.jdbi.v3.core.Jdbi;
import org.jdbi.v3.postgres.PostgresPlugin;
import org.junit.jupiter.api.*;
import org.slf4j.LoggerFactory;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.*;

import java.lang.annotation.*;
import java.lang.reflect.*;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;
import java.util.stream.IntStream;

import static dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.persistence.table_per_aggregate_type.SeparateTablePerAggregateTypeEventStreamConfigurationFactory.standardSingleTenantConfiguration;
import static dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.processor.EventProcessorIT.createObjectMapper;
import static dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.processor.ViewEventProcessorIT.TestOrderViewEventProcessor.TEST_ORDERS;
import static org.assertj.core.api.Assertions.*;

@Testcontainers
public class ViewEventProcessorIT {

    public static final EventMetaData META_DATA                               = EventMetaData.of("Key1", "Value1", "Key2", "Value2");
    /**
     * {@link EventProcessorIT.OrderPlacedEvent#orderDetails} that makes the test processor's view update fail with a
     * primary key violation on its first attempt - after it already wrote a row - and succeed on later attempts
     */
    public static final String        SQL_FAILS_ON_FIRST_ATTEMPT              = "SqlFailsOnFirstAttempt order details";
    /**
     * {@link EventProcessorIT.OrderPlacedEvent#orderDetails} that makes the test processor write a view row on its first
     * attempt, after which {@link CheckedExceptionThrowingInterceptor} sneaky-throws a checked exception; later attempts
     * succeed
     */
    public static final String        INTERCEPTOR_THROWS_CHECKED_ON_FIRST_ATTEMPT = "InterceptorThrowsCheckedOnFirstAttempt order details";
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
     * What {@link TestOrderViewEventProcessor} logs, from before it is started
     */
    private ListAppender<ILoggingEvent>   processorLogAppender;

    /**
     * Runs the annotated test with {@link SubscriptionErrorPolicy#stop()} (without auto-resume) instead of {@link SubscriptionErrorPolicy#skip()}
     */
    @Retention(RetentionPolicy.RUNTIME)
    @Target(ElementType.METHOD)
    private @interface WithSubscriptionErrorPolicyStop {
    }

    /**
     * Runs the annotated test with the {@link EventStoreSubscriptionManager} - and so the processor - given an application
     * decorator around the {@link PostgresqlEventStore} instead of the store itself (see {@link #decorate(PostgresqlEventStore, boolean)})
     */
    @Retention(RetentionPolicy.RUNTIME)
    @Target(ElementType.METHOD)
    private @interface WithDecoratedEventStore {
        /**
         * @return whether the decorator implements {@link AwaitedGapInspector} and forwards it to the {@link PostgresqlEventStore}
         */
        boolean forwardsAwaitedGapInspector();
    }

    @BeforeEach
    void setup(TestInfo testInfo) {
        var subscriptionErrorPolicy = testInfo.getTestMethod()
                                              .filter(method -> method.isAnnotationPresent(WithSubscriptionErrorPolicyStop.class))
                                              .map(method -> SubscriptionErrorPolicy.stop().withoutAutoResume())
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

        var subscribedEventStore = testInfo.getTestMethod()
                                           .map(method -> method.getAnnotation(WithDecoratedEventStore.class))
                                           .<EventStore>map(decorator -> decorate(eventStore, decorator.forwardsAwaitedGapInspector()))
                                           .orElse(eventStore);
        eventStoreSubscriptionManager = EventStoreSubscriptionManager.builder()
                                                                     .setEventStore(subscribedEventStore)
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

        processorLogAppender = new ListAppender<>();
        processorLogAppender.start();
        ((ch.qos.logback.classic.Logger) LoggerFactory.getLogger(TestOrderViewEventProcessor.class)).addAppender(processorLogAppender);

        testProcessor = new TestOrderViewEventProcessor(new ViewEventProcessorDependencies(eventStoreSubscriptionManager,
                                                                                           fencedLockManager,
                                                                                           durableQueues,
                                                                                           commandBus,
                                                                                           List.of(new CheckedExceptionThrowingInterceptor())),
                                                        eventStore);
        testProcessor.start();
    }


    @AfterEach
    public void tearDown() {
        if (testProcessor != null) {
            testProcessor.stop();
        }
        if (processorLogAppender != null) {
            ((ch.qos.logback.classic.Logger) LoggerFactory.getLogger(TestOrderViewEventProcessor.class)).detachAppender(processorLogAppender);
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
        // The container is per test, so an unclosed pool outlives it and keeps retrying its port for the rest of the fork
        if (ds != null) {
            ds.close();
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

    /**
     * ES-4: the subscription moves its resume point past an event whose direct handling failed as soon as the event is
     * queued, so the resume point alone would release the barrier before the redelivery has updated the view. The
     * barrier also waits for the queued message to be handled
     */
    @Test
    public void awaitProcessed_waits_for_the_redelivery_of_an_event_whose_direct_handling_failed() {
        var orderId = EventProcessorIT.OrderId.random();
        var appended = unitOfWorkFactory.withUnitOfWork(() -> eventStore.appendToStream(TEST_ORDERS, orderId, List.of(new EventProcessorIT.OrderPlacedEvent(orderId, SQL_FAILS_ON_FIRST_ATTEMPT)))
                                                                       .firstEvent());

        testProcessor.awaitProcessed(TEST_ORDERS, appended.globalEventOrder(), Duration.ofSeconds(10));

        // No polling: the barrier only returns once the queued redelivery has written the view row
        assertThat(testProcessor.getSqlFailureAttempts(orderId)).isEqualTo(2);
        assertThat(orderViewStatuses(orderId)).containsExactly("done");
        assertThat(durableQueues.hasOrderedMessageQueuedForKey(testProcessor.getDurableQueueName(), orderId.toString())).isFalse();
    }

    @Test
    public void awaitProcessedAsync_completes_once_the_event_has_been_handled_directly() {
        var orderId = EventProcessorIT.OrderId.random();
        var appended = unitOfWorkFactory.withUnitOfWork(() -> eventStore.appendToStream(TEST_ORDERS, orderId, List.of(new EventProcessorIT.OrderPlacedEvent(orderId, "Load order details")))
                                                                       .firstEvent());

        testProcessor.awaitProcessedAsync(TEST_ORDERS, appended.globalEventOrder(), Duration.ofSeconds(10))
                     .block(Duration.ofSeconds(15));

        assertThat(testProcessor.getOrderPlacedEventCounter().get()).isEqualTo(1);
    }

    /**
     * ES-4: an event that was dead-lettered never counts as processed, and the timeout says why - naming the processor,
     * the event, the resume point and the queue
     */
    @Test
    public void awaitProcessed_times_out_with_a_description_of_how_far_the_processor_got_when_the_event_was_dead_lettered() {
        var orderId = EventProcessorIT.OrderId.random();
        var appended = unitOfWorkFactory.withUnitOfWork(() -> eventStore.appendToStream(TEST_ORDERS, orderId, List.of(new EventProcessorIT.OrderPlacedEvent(orderId, "Fail order details")))
                                                                       .firstEvent());
        var queueName = testProcessor.getDurableQueueName();
        Awaitility.waitAtMost(Duration.ofSeconds(10))
                  .untilAsserted(() -> assertThat(durableQueues.getTotalDeadLetterMessagesQueuedFor(queueName)).isEqualTo(1));

        assertThatThrownBy(() -> testProcessor.awaitProcessed(TEST_ORDERS, appended.globalEventOrder(), Duration.ofMillis(500)))
                .isInstanceOfSatisfying(AwaitProcessedTimeoutException.class, e -> {
                    assertThat(e.getProcessorName()).isEqualTo("TestOrderViewEventProcessor");
                    assertThat((Object) e.getAggregateType()).isEqualTo(TEST_ORDERS);
                    assertThat((Object) e.getGlobalEventOrder()).isEqualTo(appended.globalEventOrder());
                    assertThat(e.getResumeFromAndIncluding()).hasValueSatisfying(resumePoint -> assertThat(resumePoint.longValue()).isGreaterThan(appended.globalEventOrder().longValue()));
                    assertThat(e.getQueuedMessageCounts()).hasValueSatisfying(counts -> assertThat(counts.numberOfQueuedDeadLetterMessages()).isEqualTo(1));
                    assertThat(e.getMessage()).contains("TestOrderViewEventProcessor")
                                              .contains("'TestOrders'")
                                              .contains("global-event-order " + appended.globalEventOrder())
                                              .contains("a message for aggregate '" + orderId + "' is still queued")
                                              .contains("1 dead-letter message(s)");
                });
    }

    @Test
    public void awaitProcessed_rejects_an_aggregate_type_the_processor_does_not_react_to() {
        assertThatThrownBy(() -> testProcessor.awaitProcessed(AggregateType.of("Unknown"), GlobalEventOrder.of(1), Duration.ofSeconds(1)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("'Unknown'");
    }

    /**
     * ES-4: two writers whose global-event-orders commit out of order. The subscription moves its resume point past the
     * earlier event while it is still uncommitted, so the resume point alone would release the barrier before the event
     * was handled - the barrier also waits for the subscription's transient gap at that global-event-order to be filled
     */
    @Test
    public void awaitProcessed_waits_for_an_event_that_committed_after_a_later_one() throws Exception {
        withAnEventThatCommitsAfterALaterOne(this::assertAwaitProcessedWaitsForTheLateEvent);
    }

    /**
     * ES-4 behind an application decorator around the event store, as {@link AwaitedGapInspector} asks of one: a decorator
     * that implements it and forwards to its delegate keeps the barrier waiting for the transient gap to be filled
     */
    @Test
    @WithDecoratedEventStore(forwardsAwaitedGapInspector = true)
    public void awaitProcessed_behind_a_decorator_forwarding_AwaitedGapInspector_waits_for_an_event_that_committed_after_a_later_one() throws Exception {
        withAnEventThatCommitsAfterALaterOne(this::assertAwaitProcessedWaitsForTheLateEvent);
        assertThat(awaitedGapInspectorWarnings()).isEmpty();
    }

    /**
     * ES-4 behind an application decorator that is no {@link AwaitedGapInspector}: the barrier cannot ask whether the
     * event is still awaited as a gap, so that check is off - it no longer reports the transient gap, only what it can see
     * - and the processor WARNs about it exactly once, however often it is awaited. That the WARN is logged at start is
     * pinned by {@link #a_processor_behind_a_decorator_that_is_no_AwaitedGapInspector_WARNs_once_at_start_before_any_awaitProcessed()}
     */
    @Test
    @WithDecoratedEventStore(forwardsAwaitedGapInspector = false)
    public void awaitProcessed_behind_a_decorator_that_is_no_AwaitedGapInspector_turns_the_transient_gap_check_off_with_one_WARN() throws Exception {
        withAnEventThatCommitsAfterALaterOne(lateCommit -> {
            assertThatThrownBy(() -> testProcessor.awaitProcessed(TEST_ORDERS, lateCommit.lateGlobalEventOrder(), Duration.ofMillis(300)))
                    .isInstanceOfSatisfying(AwaitProcessedTimeoutException.class, e -> {
                        assertThat(e.getResumeFromAndIncluding()).hasValueSatisfying(resumePoint -> assertThat(resumePoint.longValue()).isGreaterThan(lateCommit.laterGlobalEventOrder().longValue()));
                        assertThat(e.getReason()).doesNotContain("awaited as a gap")
                                                 .contains("no event with that global-event-order exists");
                    });

            lateCommit.commit();
            testProcessor.awaitProcessed(TEST_ORDERS, lateCommit.lateGlobalEventOrder(), Duration.ofSeconds(10));
            testProcessor.awaitProcessed(TEST_ORDERS, lateCommit.laterGlobalEventOrder(), Duration.ofSeconds(10));
        });

        assertThat(awaitedGapInspectorWarnings()).hasSize(1);
    }

    /**
     * The WARN about an event store that is no {@link AwaitedGapInspector} comes when the processor starts - before an
     * {@code awaitProcessed} could have returned early - and is worded so that an application that never calls
     * {@code awaitProcessed} can see it does not apply
     */
    @Test
    @WithDecoratedEventStore(forwardsAwaitedGapInspector = false)
    public void a_processor_behind_a_decorator_that_is_no_AwaitedGapInspector_WARNs_once_at_start_before_any_awaitProcessed() {
        // setup() has started the processor; nothing has called awaitProcessed
        assertThat(awaitedGapInspectorWarnings()).singleElement()
                                                 .satisfies(warning -> assertThat(warning.getFormattedMessage()).contains("If you call awaitProcessed on this processor")
                                                                                                                .contains("ignore this"));
    }

    /**
     * The barrier times out on the late event while it is uncommitted, saying it is awaited as a gap, and returns only once the
     * subscription has handled it after it committed
     */
    private void assertAwaitProcessedWaitsForTheLateEvent(LateCommit lateCommit) throws Exception {
        assertThatThrownBy(() -> testProcessor.awaitProcessed(TEST_ORDERS, lateCommit.lateGlobalEventOrder(), Duration.ofMillis(300)))
                .isInstanceOfSatisfying(AwaitProcessedTimeoutException.class, e -> {
                    assertThat((Object) e.getGlobalEventOrder()).isEqualTo(lateCommit.lateGlobalEventOrder());
                    assertThat(e.getResumeFromAndIncluding()).hasValueSatisfying(resumePoint -> assertThat(resumePoint.longValue()).isGreaterThan(lateCommit.laterGlobalEventOrder().longValue()));
                    assertThat(e.getReason()).contains("awaited as a gap");
                });

        // Commit UnitOfWork A: the barrier returns only once the subscription has handled the late event
        lateCommit.commit();
        testProcessor.awaitProcessed(TEST_ORDERS, lateCommit.lateGlobalEventOrder(), Duration.ofSeconds(10));

        // No polling: the barrier only returns once the gap fill has been handled
        assertThat(testProcessor.hasHandledLoadOrder(lateCommit.lateOrderId())).isTrue();
    }

    /**
     * Two writers whose global-event-orders commit out of order: the {@link LateCommit} handed to the scenario has the
     * subscription moved past the later event while the late one is still uncommitted - a transient gap
     */
    private void withAnEventThatCommitsAfterALaterOne(LateCommitScenario scenario) throws Exception {
        var lateOrderId       = EventProcessorIT.OrderId.random();
        var laterOrderId      = EventProcessorIT.OrderId.random();
        var lateEventAppended = new CompletableFuture<GlobalEventOrder>();
        var commitLateEvent   = new CountDownLatch(1);
        var lateWriter        = Executors.newSingleThreadExecutor();
        var subscriberId      = AbstractEventProcessor.resolveSubscriberId(TEST_ORDERS, testProcessor.getProcessorName());
        // The processor subscribes once it has acquired its fenced lock, on another thread. A subscription that starts
        // after both appends starts from the lowest persisted global-event-order - past the uncommitted event, never awaiting it
        Awaitility.waitAtMost(Duration.ofSeconds(10))
                  .untilAsserted(() -> assertThat(eventStoreSubscriptionManager.getCurrentEventOrder(subscriberId, TEST_ORDERS)).isPresent());
        try {
            // UnitOfWork A: appends the first event and stays open - on its own thread, as a UnitOfWork is bound to its thread
            var lateWriterDone = lateWriter.submit(() -> unitOfWorkFactory.usingUnitOfWork(uow -> {
                try {
                    var appended = eventStore.appendToStream(TEST_ORDERS, lateOrderId, List.of(new EventProcessorIT.OrderPlacedEvent(lateOrderId, "Load late order details")));
                    lateEventAppended.complete(appended.firstEvent().globalEventOrder());
                } catch (RuntimeException e) {
                    lateEventAppended.completeExceptionally(e);
                    throw e;
                }
                assertThat(commitLateEvent.await(30, TimeUnit.SECONDS)).isTrue();
            }));
            var lateGlobalEventOrder = lateEventAppended.get(10, TimeUnit.SECONDS);

            // UnitOfWork B: appends and commits the second event
            var laterGlobalEventOrder = unitOfWorkFactory.withUnitOfWork(() -> eventStore.appendToStream(TEST_ORDERS, laterOrderId, List.of(new EventProcessorIT.OrderPlacedEvent(laterOrderId, "Load later order details")))
                                                                                        .firstEvent()
                                                                                        .globalEventOrder());
            assertThat(laterGlobalEventOrder.longValue()).isGreaterThan(lateGlobalEventOrder.longValue());

            // The subscription moves past the second event, leaving the uncommitted first one as a transient gap
            Awaitility.waitAtMost(Duration.ofSeconds(10))
                      .untilAsserted(() -> {
                          assertThat(testProcessor.hasHandledLoadOrder(laterOrderId)).isTrue();
                          assertThat(eventStoreSubscriptionManager.getCurrentEventOrder(subscriberId, TEST_ORDERS))
                                  .hasValueSatisfying(order -> assertThat(order.longValue()).isGreaterThan(laterGlobalEventOrder.longValue()));
                      });
            assertThat(testProcessor.hasHandledLoadOrder(lateOrderId)).isFalse();

            scenario.run(new LateCommit(lateOrderId, lateGlobalEventOrder, laterGlobalEventOrder, () -> {
                commitLateEvent.countDown();
                lateWriterDone.get(10, TimeUnit.SECONDS);
            }));
        } finally {
            commitLateEvent.countDown();
            lateWriter.shutdownNow();
        }
    }

    @FunctionalInterface
    private interface LateCommitScenario {
        void run(LateCommit lateCommit) throws Exception;
    }

    /**
     * @param lateOrderId           the order whose event is appended first and committed last
     * @param lateGlobalEventOrder  that event's global-event-order
     * @param laterGlobalEventOrder the global-event-order of the event appended and committed in between
     * @param commitAction          commits the late event's UnitOfWork and waits for it to have committed
     */
    private record LateCommit(EventProcessorIT.OrderId lateOrderId, GlobalEventOrder lateGlobalEventOrder, GlobalEventOrder laterGlobalEventOrder, Committer commitAction) {
        void commit() throws Exception {
            commitAction.commit();
        }
    }

    @FunctionalInterface
    private interface Committer {
        void commit() throws Exception;
    }

    /**
     * @return the WARNs {@link TestOrderViewEventProcessor} logged about the event store being no {@link AwaitedGapInspector}
     */
    private List<ILoggingEvent> awaitedGapInspectorWarnings() {
        return processorLogAppender.list.stream()
                                        .filter(event -> event.getLevel() == ch.qos.logback.classic.Level.WARN)
                                        .filter(event -> event.getFormattedMessage().contains(AwaitedGapInspector.class.getSimpleName()))
                                        .toList();
    }

    /**
     * An application decorator around the {@link PostgresqlEventStore}, built as a JDK dynamic proxy that forwards every
     * call to it - so the test needn't hand-write a forwarder for every {@link ConfigurableEventStore} method. It is a
     * {@link ConfigurableEventStore}, as a processor needs to resolve one from its event store, and an
     * {@link AwaitedGapInspector} only when asked to be - as a decorator written before that role interface existed is not
     */
    private static ConfigurableEventStore<?> decorate(PostgresqlEventStore<?> delegate, boolean forwardsAwaitedGapInspector) {
        var interfaces = forwardsAwaitedGapInspector ?
                         new Class<?>[]{ConfigurableEventStore.class, AwaitedGapInspector.class} :
                         new Class<?>[]{ConfigurableEventStore.class};
        return (ConfigurableEventStore<?>) Proxy.newProxyInstance(ViewEventProcessorIT.class.getClassLoader(), interfaces, (proxy, method, args) -> {
            try {
                return method.invoke(delegate, args);
            } catch (InvocationTargetException e) {
                throw e.getCause();
            }
        });
    }

    /**
     * A {@link MessageHandlerInterceptor} written in Kotlin, or one that sneaky-throws, can raise a checked exception
     * around the direct handler. It must be rolled back to the savepoint like any other failure, so the handler's
     * partial writes are undone before the event is queued in the same transaction - not committed with it
     */
    @Test
    public void verify_a_checked_exception_from_an_interceptor_rolls_back_the_handlers_partial_writes_and_gets_queued() {
        var orderId = EventProcessorIT.OrderId.random();
        unitOfWorkFactory.usingUnitOfWork(uow -> {
            eventStore.appendToStream(TEST_ORDERS, orderId, List.of(new EventProcessorIT.OrderPlacedEvent(orderId, INTERCEPTOR_THROWS_CHECKED_ON_FIRST_ATTEMPT)));
        });

        // The first (direct) attempt writes a 'partial' row, then the interceptor throws a checked exception. The queued
        // redelivery succeeds - once
        var queueName = testProcessor.getDurableQueueName();
        Awaitility.waitAtMost(Duration.ofSeconds(10))
                  .untilAsserted(() -> assertThat(orderViewStatuses(orderId)).containsExactly("done"));
        assertThat(testProcessor.getInterceptorFailureAttempts(orderId)).isEqualTo(2);
        assertThat(durableQueues.getTotalMessagesQueuedFor(queueName)).isZero();
        assertThat(durableQueues.getTotalDeadLetterMessagesQueuedFor(queueName)).isZero();
        assertThat(handleEventFailedEvents).isEmpty();
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
                assertThat(currentEventOrder)
                        .hasValueSatisfying(order ->
                                                    assertThat(order.longValue()).isEqualTo(1)
                                           )
                        .describedAs("After resetting: Current event order");
                var currentResumePoint = durableSubscriptionRepository.getResumePoint(subscriberId, TEST_ORDERS);
                assertThat(currentResumePoint)
                        .hasValueSatisfying(resumePoint -> assertThat(resumePoint.getResumeFromAndIncluding().longValue()).isEqualTo(1))
                        .describedAs("After resetting: Current resume point");
            });
        });
        testProcessor.resetAllSubscriptions();

        // Check subscriptions catchup again
        Awaitility.waitAtMost(Duration.ofSeconds(3)).untilAsserted(() -> {
            var currentEventOrder = eventStoreSubscriptionManager.getCurrentEventOrder(subscriberId, TEST_ORDERS);
            assertThat(currentEventOrder)
                    .hasValueSatisfying(order ->
                                                assertThat(order.longValue()).isEqualTo(11)
                                       )
                    .describedAs("After resetting: Current event order");
            var currentResumePoint = durableSubscriptionRepository.getResumePoint(subscriberId, TEST_ORDERS);
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
        /**
         * The orders whose "Load" {@link EventProcessorIT.OrderPlacedEvent} was handled
         */
        private final Set<String> handledLoadOrders = ConcurrentHashMap.newKeySet();
        private final ConcurrentMap<String, AtomicInteger> sqlFailureAttempts = new ConcurrentHashMap<>();
        private final ConcurrentMap<String, AtomicInteger> interceptorFailureAttempts = new ConcurrentHashMap<>();
        /**
         * Set by a handler on the delivery thread to have {@link CheckedExceptionThrowingInterceptor} fail the attempt
         * after the handler returned
         */
        private final ThreadLocal<Boolean>                 checkedFailureRequested    = ThreadLocal.withInitial(() -> false);
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
            throw new RuntimeException(cmd.reason);
        }

        // ----- Message Handler -----

        @MessageHandler
        public void handle(EventProcessorIT.FailingCommandSentViaInbox cmd) {
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
                handledLoadOrders.add(event.orderId.toString());
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
            } else if (event.orderDetails.equals(INTERCEPTOR_THROWS_CHECKED_ON_FIRST_ATTEMPT)) {
                var orderId = event.orderId.toString();
                var attempt = interceptorFailureAttempts.computeIfAbsent(orderId, id -> new AtomicInteger()).incrementAndGet();
                if (attempt == 1) {
                    // A partial view update - the interceptor then fails the attempt with a checked exception
                    unitOfWork.handle().execute("INSERT INTO order_view (order_id, status) VALUES (?, 'partial')", orderId);
                    checkedFailureRequested.set(true);
                } else {
                    unitOfWork.handle().execute("INSERT INTO order_view (order_id, status) VALUES (?, 'done')", orderId);
                }
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

        public boolean hasHandledLoadOrder(EventProcessorIT.OrderId orderId) {
            return handledLoadOrders.contains(orderId.toString());
        }

        public int getAppendedEventsBeforeFailing() {
            return appendedEventsBeforeFailing.get();
        }

        public int getInterceptorFailureAttempts(EventProcessorIT.OrderId orderId) {
            var attempts = interceptorFailureAttempts.get(orderId.toString());
            return attempts != null ? attempts.get() : 0;
        }

        /**
         * @return true, once, after a handler asked {@link CheckedExceptionThrowingInterceptor} to fail the attempt on this thread
         */
        boolean takeCheckedFailureRequest() {
            var requested = checkedFailureRequested.get();
            checkedFailureRequested.remove();
            return requested;
        }

        public int getSqlFailureAttempts(EventProcessorIT.OrderId orderId) {
            var attempts = sqlFailureAttempts.get(orderId.toString());
            return attempts != null ? attempts.get() : 0;
        }
    }

    /**
     * Fails a handler invocation with a <b>checked</b> exception after the handler returned, when the handler asked for it
     * - what a {@link MessageHandlerInterceptor} written in Kotlin, or one using a sneaky throw, can do. The handler's own
     * checked exceptions arrive wrapped in a {@code ReflectionException}; an interceptor's are rethrown as they are
     */
    static final class CheckedExceptionThrowingInterceptor implements MessageHandlerInterceptor {
        @Override
        public void intercept(InvokeMessageHandlerMethod operation, InterceptorChain<InvokeMessageHandlerMethod, Void, MessageHandlerInterceptor> interceptorChain) {
            interceptorChain.proceed();
            if (operation.invokeMethodOn instanceof TestOrderViewEventProcessor processor && processor.takeCheckedFailureRequest()) {
                Exceptions.sneakyThrow(new InterceptorCheckedException("Intentional checked failure from an interceptor"));
            }
        }
    }

    static final class InterceptorCheckedException extends Exception {
        InterceptorCheckedException(String message) {
            super(message);
        }
    }
}
