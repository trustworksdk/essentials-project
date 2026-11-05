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
import dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.eventstream.*;
import dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.gap.PostgresqlEventStreamGapHandler;
import dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.observability.EventStoreSubscriptionObserver;
import dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.persistence.table_per_aggregate_type.*;
import dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.processor.EventProcessorIT.*;
import dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.serializer.AggregateIdSerializer;
import dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.serializer.json.EssentialsJSONEventSerializers;
import dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.subscription.*;
import dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.transaction.*;
import dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.types.GlobalEventOrder;
import dk.trustworks.essentials.components.foundation.json.EssentialsObjectMappers;
import dk.trustworks.essentials.components.foundation.messaging.*;
import dk.trustworks.essentials.components.foundation.messaging.RedeliveryPolicy;
import dk.trustworks.essentials.components.foundation.messaging.queue.*;
import dk.trustworks.essentials.components.foundation.reactive.command.DurableLocalCommandBus;
import dk.trustworks.essentials.components.queue.shardowned.*;
import dk.trustworks.essentials.components.queue.shardowned.adapter.ShardOwnedDurableQueues;
import org.awaitility.Awaitility;
import org.jdbi.v3.core.Jdbi;
import org.jdbi.v3.postgres.PostgresPlugin;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.testcontainers.junit.jupiter.*;
import org.testcontainers.postgresql.PostgreSQLContainer;

import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;

import static dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.persistence.table_per_aggregate_type.SeparateTablePerAggregateTypeEventStreamConfigurationFactory.standardSingleTenantConfiguration;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * The {@link PartitionedViewEventProcessor} counterpart of {@link EventProcessorReForwardOnShardOwnedIT}: the reader
 * ({@code <processorName>:lock}) fails over uncleanly and the next reader re-forwards, from the resume point the first
 * one never saved, events that are still queued in {@code <processorName>:queue}. The class's idempotency contract rests
 * on the shard-owned queue absorbing that re-forward - {@link AbstractEventProcessor.EventReferenceOrderedMessage} and
 * {@link PartitionedViewEventProcessor.EventPayloadOrderedMessage} are {@link IdentifiedByKeyAndOrder} - instead of the
 * ordered lane's primary key refusing it and the reader stopping by its error policy.
 * <p>
 * Constructed, not hoped for: instance A holds the reader and forwards the events while every handler is held, so they
 * stay queued, and never snapshots its resume point ({@code snapshotResumePointsEvery} 1 h); A's lock manager is then
 * paused, so its reader lock times out without a release, and instance B takes the reader over from the resume point A
 * never moved. Run for both forwarding modes: event references (the default) and {@link PartitionedViewEventProcessor#forwardsEventPayloads()}.
 */
@Testcontainers
class PartitionedViewReForwardOnShardOwnedIT {
    private static final String        VIEW   = "ReForwardView";
    private static final QueueName     QUEUE  = QueueName.of(VIEW + ":queue");
    private static final AggregateType ORDERS = AggregateType.of("PvReForwardOrders");
    private static final int           EVENTS = 10;

    @Container
    private static final PostgreSQLContainer postgres = new PostgreSQLContainer("postgres:18.4");

    private final List<AutoCloseable>           toClose       = new ArrayList<>();
    private final CountDownLatch                release       = new CountDownLatch(1);
    /**
     * "order@instance" per handled event, in handling order (one aggregate)
     */
    private final ConcurrentLinkedQueue<String> handled       = new ConcurrentLinkedQueue<>();
    /**
     * "globalOrder@instance" per event a reader forwarded - every attempt, so a retried batch shows twice
     */
    private final ConcurrentLinkedQueue<String> forwarded     = new ConcurrentLinkedQueue<>();
    /**
     * Every stop of a subscription by its error policy, and every event it gave up on
     */
    private final ConcurrentLinkedQueue<String> stops         = new ConcurrentLinkedQueue<>();
    private       boolean                       forwardPayloads;
    private       HikariDataSource              dataSource;
    private       PostgresqlEventStore<SeparateTablePerAggregateEventStreamConfiguration> eventStore;
    private       EventStoreManagedUnitOfWorkFactory unitOfWorkFactory;

    @BeforeEach
    void setUp() throws Exception {
        var config = new HikariConfig();
        config.setJdbcUrl(postgres.getJdbcUrl());
        config.setUsername(postgres.getUsername());
        config.setPassword(postgres.getPassword());
        config.setMaximumPoolSize(40);
        dataSource = new HikariDataSource(config);
        ShardOwnedSchema.recreate(dataSource);
        // The container is shared by both parameterized runs: each starts from an empty event store and no resume points
        Jdbi.create(dataSource).useHandle(handle -> handle.execute("DROP TABLE IF EXISTS fenced_locks, durable_subscriptions, pvreforwardorders_events CASCADE"));
        ShardOwnedSchema.registerQueue(dataSource, engineName(QUEUE), 2);
        ShardOwnedSchema.registerQueue(dataSource, engineName(DurableLocalCommandBus.DEFAULT_COMMAND_QUEUE_NAME), 2);

        var jdbi = Jdbi.create(dataSource);
        jdbi.installPlugin(new PostgresPlugin());
        unitOfWorkFactory = new EventStoreManagedUnitOfWorkFactory(jdbi);
        var persistenceStrategy = new SeparateTablePerAggregateTypePersistenceStrategy(jdbi,
                                                                                       unitOfWorkFactory,
                                                                                       new TestPersistableEventMapper(),
                                                                                       standardSingleTenantConfiguration(EssentialsJSONEventSerializers.create(),
                                                                                                                         IdentifierColumnType.TEXT,
                                                                                                                         JSONColumnType.JSONB));
        eventStore = PostgresqlEventStore.<SeparateTablePerAggregateEventStreamConfiguration>builder()
                                         .setUnitOfWorkFactory(unitOfWorkFactory)
                                         .setPersistenceStrategy(persistenceStrategy)
                                         .setEventStreamGapHandlerFactory(store -> new PostgresqlEventStreamGapHandler<>(unitOfWorkFactory))
                                         .setEventStoreSubscriptionObserver(new EventStoreSubscriptionObserver.NoOpEventStoreSubscriptionObserver() {
                                             @Override
                                             public void subscriptionStoppedByErrorPolicy(GlobalEventOrder stoppedAtGlobalEventOrder, Throwable cause,
                                                                                          EventStoreSubscription eventStoreSubscription) {
                                                 stops.add("stopped at " + stoppedAtGlobalEventOrder + ": " + cause);
                                             }

                                             @Override
                                             public void subscriptionSkippedEventAfterAutoResumes(GlobalEventOrder skippedGlobalEventOrder, int autoResumes,
                                                                                                  Throwable cause, EventStoreSubscription eventStoreSubscription) {
                                                 stops.add("skipped " + skippedGlobalEventOrder + ": " + cause);
                                             }
                                         })
                                         .build();
        eventStore.addAggregateEventStreamConfiguration(ORDERS, AggregateIdSerializer.serializerFor(OrderId.class));
    }

    @AfterEach
    void tearDown() {
        release.countDown();
        Collections.reverse(toClose);
        for (var closeable : toClose) {
            try {
                closeable.close();
            } catch (Exception e) {
                // best effort
            }
        }
        dataSource.close();
    }

    @ParameterizedTest(name = "forwardsEventPayloads = {0}")
    @ValueSource(booleans = {false, true})
    void a_reader_that_fails_over_uncleanly_re_forwards_still_queued_events_without_stopping_and_every_event_is_applied_in_order(boolean forwardsEventPayloads) {
        forwardPayloads = forwardsEventPayloads;
        var a = instance("a");
        Awaitility.await().atMost(Duration.ofSeconds(20)).until(() -> readerLockHolder().equals(Optional.of("a")));
        var b = instance("b");
        Awaitility.await().atMost(Duration.ofSeconds(20)).until(() -> liveConsumers(a) == 2);

        var orderId = OrderId.random();
        for (var index = 0; index < EVENTS; index++) {
            var details = "event-" + index;
            unitOfWorkFactory.usingUnitOfWork(() -> eventStore.appendToStream(ORDERS, orderId, new OrderPlacedEvent(orderId, details)));
        }
        // Forwarded by A and held in the queue: the first is in a held handler, the rest wait behind it on the key
        Awaitility.await().atMost(Duration.ofSeconds(20)).until(() -> a.durableQueues.getTotalMessagesQueuedFor(QUEUE) == EVENTS);
        assertThat(storedResumePoint()).as("A's resume point was saved - B would not re-forward").isLessThanOrEqualTo(1L);

        // A dies without releasing anything: its reader lock times out, B takes the reader over from the resume point A never moved
        a.lockManager.pause();
        Awaitility.await().atMost(Duration.ofSeconds(20)).until(() -> readerLockHolder().equals(Optional.of("b")));
        // B re-forwards every event A forwarded, all of them still queued (the ordered lane's primary key refuses the
        // batch once, the adapter retries it "if absent")
        Awaitility.await().atMost(Duration.ofSeconds(20))
                  .untilAsserted(() -> assertThat(forwarded.stream().filter(entry -> entry.endsWith("@b")).map(entry -> Long.parseLong(entry.substring(0, entry.indexOf('@')))))
                          .as("events re-forwarded by B")
                          .containsAll(globalOrders(1, EVENTS)));

        // The reader keeps going: an event appended after the takeover is forwarded, and nothing is queued twice
        unitOfWorkFactory.usingUnitOfWork(() -> eventStore.appendToStream(ORDERS, orderId, new OrderPlacedEvent(orderId, "after-takeover")));
        Awaitility.await().atMost(Duration.ofSeconds(20)).until(() -> b.durableQueues.getTotalMessagesQueuedFor(QUEUE) == EVENTS + 1);
        // The originals stay queued for longer than the subscription's retries of the re-forward last (RETRY_N_THEN_STOP,
        // 3 retries, 100 ms doubling): were the re-forward refused for good, the reader would have stopped by now
        sleep(Duration.ofSeconds(5));
        assertThat(b.durableQueues.getTotalMessagesQueuedFor(QUEUE)).as("messages queued after the re-forward").isEqualTo(EVENTS + 1);

        release.countDown();
        Awaitility.await().atMost(Duration.ofSeconds(60))
                  .untilAsserted(() -> assertThat(handled.stream().map(entry -> entry.substring(0, entry.indexOf('@'))).distinct().count())
                          .as("distinct events handled: %s", handled)
                          .isEqualTo(EVENTS + 1));
        Awaitility.await().atMost(Duration.ofSeconds(20)).until(() -> b.durableQueues.getTotalMessagesQueuedFor(QUEUE) == 0);
        System.out.printf("%nPartitioned view re-forward after an unclean reader failover (forwardsEventPayloads=%s): %d deliveries of %d events: %s; " +
                                  "forwarded: %s; stops: %s%n%n", forwardsEventPayloads, handled.size(), EVENTS + 1, handled, forwarded, stops);

        assertThat(stops).as("the reader stopped by its error policy - the re-forward of a still queued event was refused").isEmpty();
        // The contract: handlers guard each row with the event's order and ignore one not above it. Applied that way, the
        // deliveries must apply every event of the aggregate, in order - a duplicate is allowed only as a no-op
        var applied     = new ArrayList<Long>();
        var lastApplied = -1L;
        for (var entry : handled) {
            var order = Long.parseLong(entry.substring(0, entry.indexOf('@')));
            if (order > lastApplied) {
                applied.add(order);
                lastApplied = order;
            }
        }
        assertThat(applied).as("event orders applied by an order-guarded handler, from the deliveries %s", handled).isEqualTo(globalOrders(0, EVENTS));
    }

    private static List<Long> globalOrders(long fromInclusive, long toInclusive) {
        var orders = new ArrayList<Long>();
        for (var order = fromInclusive; order <= toInclusive; order++) {
            orders.add(order);
        }
        return orders;
    }

    private static void sleep(Duration duration) {
        try {
            Thread.sleep(duration.toMillis());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        }
    }

    private int liveConsumers(Instance instance) {
        return instance.durableQueues.getQueueConsumers(QUEUE).orElseThrow().liveInstances();
    }

    /**
     * The holder of the view's reader lock ({@code <processorName>:lock}), as the lock table has it
     */
    private Optional<String> readerLockHolder() {
        return Jdbi.create(dataSource).withHandle(handle -> handle.createQuery("SELECT locked_by_lockmanager_instance_id FROM fenced_locks"
                                                                                       + " WHERE lock_name = :name AND locked_by_lockmanager_instance_id IS NOT NULL")
                                                                  .bind("name", VIEW + ":lock")
                                                                  .mapTo(String.class)
                                                                  .findOne());
    }

    private long storedResumePoint() {
        return Jdbi.create(dataSource).withHandle(handle -> handle.createQuery("SELECT resume_from_and_including_global_eventorder FROM durable_subscriptions WHERE subscriber_id = :id AND aggregate_type = :type")
                                                                  .bind("id", AbstractEventProcessor.resolveSubscriberId(ORDERS, VIEW).toString())
                                                                  .bind("type", ORDERS.toString())
                                                                  .mapTo(Long.class)
                                                                  .findOne()
                                                                  .orElse(1L));
    }

    private Instance instance(String id) {
        var lockManager = PostgresqlFencedLockManager.builder()
                                                     .setJdbi(Jdbi.create(dataSource))
                                                     .setUnitOfWorkFactory(unitOfWorkFactory)
                                                     .setLockManagerInstanceId(id)
                                                     .setLockTimeOut(Duration.ofSeconds(2))
                                                     .setLockConfirmationInterval(Duration.ofMillis(500))
                                                     .buildAndStart();
        toClose.add(lockManager::stop);
        var subscriptionManager = EventStoreSubscriptionManager.builder()
                                                               .setEventStore(eventStore)
                                                               .setFencedLockManager(lockManager)
                                                               .setDurableSubscriptionRepository(new PostgresqlDurableSubscriptionRepository(Jdbi.create(dataSource), eventStore))
                                                               // never snapshotted while the test runs: an unclean failover re-forwards everything
                                                               .setSnapshotResumePointsEvery(Duration.ofHours(1))
                                                               .build();
        subscriptionManager.start();
        toClose.add(subscriptionManager::stop);
        var queues = new RegisteredMessageQueues(dataSource, id);
        toClose.add(queues);
        var durableQueues = ShardOwnedDurableQueues.builder()
                                                   .setQueues(queues)
                                                   .setJsonSerializer(EssentialsObjectMappers.createJSONSerializer())
                                                   .setUnitOfWorkFactory(unitOfWorkFactory)
                                                   .setDataSource(dataSource)
                                                   .build();
        durableQueues.start();
        toClose.add(durableQueues::stop);
        var commandBus = DurableLocalCommandBus.builder().setDurableQueues(durableQueues).build();
        commandBus.start();
        toClose.add(commandBus::stop);
        var view = new View(new ViewEventProcessorDependencies(subscriptionManager, lockManager, durableQueues, commandBus, List.of()), id);
        view.start();
        toClose.add(view::stop);
        return new Instance(lockManager, durableQueues);
    }

    private record Instance(PostgresqlFencedLockManager lockManager, ShardOwnedDurableQueues durableQueues) {
    }

    private class View extends PartitionedViewEventProcessor {
        private final String instance;

        View(ViewEventProcessorDependencies dependencies, String instance) {
            super(dependencies);
            this.instance = instance;
        }

        @Override
        public String getProcessorName() {
            return VIEW;
        }

        @Override
        protected List<AggregateType> reactsToEventsRelatedToAggregateTypes() {
            return List.of(ORDERS);
        }

        @Override
        protected boolean forwardsEventPayloads() {
            return forwardPayloads;
        }

        @Override
        protected RedeliveryPolicy getDurableQueueRedeliveryPolicy() {
            return RedeliveryPolicy.fixedBackoff(Duration.ofMillis(200), 100);
        }

        @Override
        void forwardBatch(List<PersistedEvent> events) {
            events.forEach(event -> forwarded.add(event.globalEventOrder().longValue() + "@" + instance));
            super.forwardBatch(events);
        }

        @MessageHandler
        void on(OrderPlacedEvent event, OrderedMessage message) throws InterruptedException {
            release.await();
            handled.add(message.getOrder() + "@" + instance);
        }
    }

    private static dk.trustworks.essentials.components.queue.shardowned.spi.QueueName engineName(QueueName queueName) {
        return dk.trustworks.essentials.components.queue.shardowned.spi.QueueName.of(queueName.toString());
    }

    /**
     * Builds each engine queue on demand with a one-second lease, as the adapter's own ITs do
     */
    private static final class RegisteredMessageQueues
            implements dk.trustworks.essentials.components.queue.shardowned.spi.MessageQueues, AutoCloseable {
        private final javax.sql.DataSource dataSource;
        private final String               instanceId;
        private final Map<dk.trustworks.essentials.components.queue.shardowned.spi.QueueName, PostgresqlMessageQueue> built = new LinkedHashMap<>();

        RegisteredMessageQueues(javax.sql.DataSource dataSource, String instanceId) {
            this.dataSource = dataSource;
            this.instanceId = instanceId;
        }

        @Override
        public synchronized List<dk.trustworks.essentials.components.queue.shardowned.spi.QueueName> queueNames() throws java.sql.SQLException {
            return ShardOwnedSchema.queueNames(dataSource);
        }

        @Override
        public synchronized Optional<dk.trustworks.essentials.components.queue.shardowned.spi.MessageQueue> findQueue(
                dk.trustworks.essentials.components.queue.shardowned.spi.QueueName queueName) throws java.sql.SQLException {
            if (ShardOwnedSchema.resolve(dataSource, queueName).isEmpty()) {
                return Optional.empty();
            }
            return Optional.of(built.computeIfAbsent(queueName, name -> PostgresqlMessageQueue.builder()
                                                                                              .setDataSource(dataSource)
                                                                                              .setQueueName(name)
                                                                                              .setInstanceId(instanceId)
                                                                                              .setSettings(ShardOwnerSettings.builder()
                                                                                                                             .setSweepInterval(Duration.ofMillis(100))
                                                                                                                             .setMaxSweepInterval(Duration.ofMillis(200))
                                                                                                                             .setLeaseTtl(Duration.ofSeconds(1))
                                                                                                                             .build())
                                                                                              .build()));
        }

        @Override
        public synchronized void close() {
            built.values().forEach(PostgresqlMessageQueue::close);
        }
    }
}
