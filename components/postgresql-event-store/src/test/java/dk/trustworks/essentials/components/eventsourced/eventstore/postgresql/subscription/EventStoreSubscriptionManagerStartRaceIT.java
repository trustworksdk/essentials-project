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
package dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.subscription;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.UnsynchronizedAppenderBase;
import dk.trustworks.essentials.components.distributed.fencedlock.postgresql.PostgresqlFencedLockManager;
import dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.*;
import dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.eventstream.AggregateType;
import dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.observability.EventStoreSubscriptionObserver.NoOpEventStoreSubscriptionObserver;
import dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.persistence.table_per_aggregate_type.*;
import dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.processor.EventProcessorIT;
import dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.serializer.json.EssentialsJSONEventSerializers;
import dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.test_data.OrderId;
import dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.transaction.*;
import dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.types.GlobalEventOrder;
import dk.trustworks.essentials.components.foundation.fencedlock.FencedLock;
import dk.trustworks.essentials.components.foundation.test.EssentialsTestContainers;
import dk.trustworks.essentials.components.foundation.types.SubscriberId;
import org.jdbi.v3.core.Jdbi;
import org.jdbi.v3.postgres.PostgresPlugin;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.slf4j.LoggerFactory;
import org.testcontainers.junit.jupiter.*;
import org.testcontainers.postgresql.PostgreSQLContainer;

import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * A subscription added while the manager starts must be started once. The manager's {@code start()} starts every
 * subscription in its map, and adding a subscription starts it when the manager is already started - both decided
 * without coordinating, so a subscription added on another thread (an event processor's fenced-lock callback) right as
 * the manager started was started by both. Seen as a failed application start:
 * "Cannot createResumePoint ... since a ResumePoint already exists".
 * <p>
 * The test holds the adding thread just after it put the subscription in the map - at its "Added ... event store
 * subscription" log line - until the manager's start is about to start that subscription, and holds the manager's
 * start there until the adding thread is done.
 */
@Testcontainers
class EventStoreSubscriptionManagerStartRaceIT {
    private static final AggregateType ORDERS        = AggregateType.of("Orders");
    private static final SubscriberId  SUBSCRIBER_ID = SubscriberId.of("OrdersStartRace");
    private static final Duration      WAIT          = Duration.ofSeconds(10);

    @Container
    private static final PostgreSQLContainer postgreSQLContainer = EssentialsTestContainers.postgres("subscription-start-race-db");

    enum Kind {ASYNCHRONOUS, BATCHED, EXCLUSIVE_ASYNCHRONOUS, IN_TRANSACTION, EXCLUSIVE_IN_TRANSACTION}

    private final Map<SubscriberId, AtomicInteger> startingSubscriberCalls = new ConcurrentHashMap<>();
    private final CountDownLatch                   subscriptionAdded       = new CountDownLatch(1);
    private final CountDownLatch                   managerIsStartingIt     = new CountDownLatch(1);
    private final CountDownLatch                   subscribeReturned       = new CountDownLatch(1);
    private final List<String>                     timedOut                = new CopyOnWriteArrayList<>();
    private volatile Thread                         managerStartThread;
    private volatile Thread                         subscribeThread;

    private Logger                                    managerLogger;
    private UnsynchronizedAppenderBase<ILoggingEvent> holdSubscribeAfterAdding;
    private ExecutorService                           executor;
    private EventStoreSubscriptionManager             subscriptionManager;

    @BeforeEach
    void setup() {
        var jdbi = Jdbi.create(postgreSQLContainer.getJdbcUrl(), postgreSQLContainer.getUsername(), postgreSQLContainer.getPassword());
        jdbi.installPlugin(new PostgresPlugin());
        // The container is shared by the class, so start every test from an empty database
        jdbi.useHandle(handle -> handle.execute("DROP SCHEMA public CASCADE; CREATE SCHEMA public"));

        EventStoreUnitOfWorkFactory<EventStoreUnitOfWork> unitOfWorkFactory = new EventStoreManagedUnitOfWorkFactory(jdbi);
        var persistenceStrategy = new SeparateTablePerAggregateTypePersistenceStrategy(jdbi,
                                                                                       unitOfWorkFactory,
                                                                                       new EventProcessorIT.TestPersistableEventMapper(),
                                                                                       SeparateTablePerAggregateTypeEventStreamConfigurationFactory.defaultConfiguration(
                                                                                               EssentialsJSONEventSerializers.create()));
        var eventStore = new PostgresqlEventStore<>(unitOfWorkFactory, persistenceStrategy, new CountingObserver());
        eventStore.addAggregateEventStreamConfiguration(ORDERS, OrderId.class);

        subscriptionManager = EventStoreSubscriptionManager.builder()
                                                           .setEventStore(eventStore)
                                                           .setEventStorePollingBatchSize(10)
                                                           .setEventStorePollingInterval(Duration.ofMillis(100))
                                                           .setFencedLockManager(PostgresqlFencedLockManager.builder()
                                                                                                            .setJdbi(jdbi)
                                                                                                            .setUnitOfWorkFactory(unitOfWorkFactory)
                                                                                                            .setLockManagerInstanceId("StartRace")
                                                                                                            .setLockTimeOut(Duration.ofSeconds(3))
                                                                                                            .setLockConfirmationInterval(Duration.ofSeconds(1))
                                                                                                            .build())
                                                           .setSnapshotResumePointsEvery(Duration.ofSeconds(1))
                                                           .setDurableSubscriptionRepository(new PostgresqlDurableSubscriptionRepository(jdbi, eventStore))
                                                           .build();

        // Unsynchronized: AppenderBase holds its monitor while appending, which would hold the manager's own log lines too
        holdSubscribeAfterAdding = new UnsynchronizedAppenderBase<>() {
            @Override
            protected void append(ILoggingEvent event) {
                if (Thread.currentThread() == subscribeThread && event.getFormattedMessage().contains("Added ")) {
                    subscriptionAdded.countDown();
                    await(managerIsStartingIt, "the manager's start to start the subscription");
                }
            }
        };
        holdSubscribeAfterAdding.start();
        managerLogger = (Logger) LoggerFactory.getLogger(DefaultEventStoreSubscriptionManager.class);
        managerLogger.addAppender(holdSubscribeAfterAdding);
        executor = Executors.newFixedThreadPool(2);
    }

    @AfterEach
    void tearDown() {
        managerLogger.detachAppender(holdSubscribeAfterAdding);
        holdSubscribeAfterAdding.stop();
        executor.shutdownNow();
        if (subscriptionManager != null) {
            subscriptionManager.stop();
        }
    }

    @ParameterizedTest
    @EnumSource(Kind.class)
    void a_subscription_added_while_the_manager_starts_is_started_once(Kind kind) throws Exception {
        var subscribing = executor.submit(() -> {
            subscribeThread = Thread.currentThread();
            try {
                return subscribe(kind);
            } finally {
                subscribeReturned.countDown();
            }
        });
        // Only now, so the manager's start finds the subscription in its map
        await(subscriptionAdded, "the subscription to be added");
        var starting = executor.submit(() -> {
            managerStartThread = Thread.currentThread();
            subscriptionManager.start();
        });

        var subscription = subscribing.get(WAIT.toSeconds() * 3, TimeUnit.SECONDS);
        starting.get(WAIT.toSeconds() * 3, TimeUnit.SECONDS);

        assertThat(timedOut).as("waits that timed out - the interleaving did not happen").isEmpty();
        assertThat(startingSubscriberCalls.get(SUBSCRIBER_ID)).as("startingSubscriber calls").hasValue(1);
        assertThat(subscription.isStarted()).isTrue();
    }

    private EventStoreSubscription subscribe(Kind kind) {
        return switch (kind) {
            case ASYNCHRONOUS -> subscriptionManager.subscribeToAggregateEventsAsynchronously(SUBSCRIBER_ID,
                                                                                              ORDERS,
                                                                                              GlobalEventOrder.FIRST_GLOBAL_EVENT_ORDER,
                                                                                              event -> {
                                                                                              });
            case BATCHED -> subscriptionManager.batchSubscribeToAggregateEventsAsynchronously(SUBSCRIBER_ID,
                                                                                              ORDERS,
                                                                                              GlobalEventOrder.FIRST_GLOBAL_EVENT_ORDER,
                                                                                              Optional.empty(),
                                                                                              10,
                                                                                              Duration.ofMillis(100),
                                                                                              events -> events.size());
            case EXCLUSIVE_ASYNCHRONOUS -> subscriptionManager.exclusivelySubscribeToAggregateEventsAsynchronously(SUBSCRIBER_ID,
                                                                                                                   ORDERS,
                                                                                                                   GlobalEventOrder.FIRST_GLOBAL_EVENT_ORDER,
                                                                                                                   new FencedLockAwareSubscriber() {
                                                                                                                       @Override
                                                                                                                       public void onLockAcquired(FencedLock fencedLock, SubscriptionResumePoint resumeFromAndIncluding) {
                                                                                                                       }

                                                                                                                       @Override
                                                                                                                       public void onLockReleased(FencedLock fencedLock) {
                                                                                                                       }
                                                                                                                   },
                                                                                                                   event -> {
                                                                                                                   });
            case IN_TRANSACTION -> subscriptionManager.subscribeToAggregateEventsInTransaction(SUBSCRIBER_ID,
                                                                                               ORDERS,
                                                                                               (event, unitOfWork) -> {
                                                                                               });
            case EXCLUSIVE_IN_TRANSACTION -> subscriptionManager.exclusivelySubscribeToAggregateEventsInTransaction(SUBSCRIBER_ID,
                                                                                                                   ORDERS,
                                                                                                                   Optional.empty(),
                                                                                                                   (event, unitOfWork) -> {
                                                                                                                   });
        };
    }

    private void await(CountDownLatch latch, String what) {
        try {
            if (!latch.await(WAIT.toMillis(), TimeUnit.MILLISECONDS)) {
                timedOut.add(Thread.currentThread().getName() + " waiting for " + what);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private class CountingObserver extends NoOpEventStoreSubscriptionObserver {
        @Override
        public void startingSubscriber(EventStoreSubscription eventStoreSubscription) {
            startingSubscriberCalls.computeIfAbsent(eventStoreSubscription.subscriberId(), id -> new AtomicInteger()).incrementAndGet();
            if (Thread.currentThread() == managerStartThread) {
                managerIsStartingIt.countDown();
                await(subscribeReturned, "the subscribing thread to return");
            }
        }
    }
}
