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
import dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.gap.PostgresqlEventStreamGapHandler;
import dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.observability.EventStoreSubscriptionObserver;
import dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.persistence.table_per_aggregate_type.*;
import dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.persistence.*;
import dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.processor.EventProcessorIT.*;
import dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.serializer.AggregateIdSerializer;
import dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.serializer.json.EssentialsJSONEventSerializers;
import dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.subscription.*;
import dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.transaction.*;
import dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.eventstream.*;
import dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.types.GlobalEventOrder;
import dk.trustworks.essentials.components.foundation.json.EssentialsObjectMappers;
import dk.trustworks.essentials.components.foundation.messaging.*;
import dk.trustworks.essentials.components.foundation.messaging.eip.store_and_forward.*;
import dk.trustworks.essentials.components.foundation.messaging.queue.*;
import dk.trustworks.essentials.components.foundation.reactive.command.DurableLocalCommandBus;
import dk.trustworks.essentials.components.queue.shardowned.*;
import dk.trustworks.essentials.components.queue.shardowned.adapter.ShardOwnedDurableQueues;
import org.awaitility.Awaitility;
import org.jdbi.v3.core.Jdbi;
import org.jdbi.v3.postgres.PostgresPlugin;
import org.junit.jupiter.api.*;
import org.testcontainers.junit.jupiter.*;
import org.testcontainers.postgresql.PostgreSQLContainer;

import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;

import static dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.persistence.table_per_aggregate_type.SeparateTablePerAggregateTypeEventStreamConfigurationFactory.standardSingleTenantConfiguration;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * IT-4 of {@code docs/key-partitioned-event-processing.md}: an exclusive {@link EventProcessor} on the shard-owned
 * engine whose reader fails over uncleanly re-forwards the events since its last resume-point snapshot. Some of them are
 * still queued - the ordered lane's primary key is {@code (queue_id, shard, msg_key, key_order)} and its insert has no
 * {@code ON CONFLICT} - so the re-forward collides with them.
 * <p>
 * Constructed, not hoped for: instance A forwards the events while its handler is held, so they stay queued, and never
 * snapshots its resume point; A's lock manager is then paused, so its lock times out without a release, and instance B
 * takes over from the resume point A never moved. What this asserts is the property the partitioned processors need: the
 * reader does not stop - B forwards an event appended after the takeover, and every event is handled.
 */
@Testcontainers
class EventProcessorReForwardOnShardOwnedIT {
    private static final String        PROCESSOR = "ReForwardProcessor";
    private static final AggregateType ORDERS    = AggregateType.of("ReForwardOrders");
    private static final int           EVENTS    = 10;

    @Container
    private static final PostgreSQLContainer postgres = new PostgreSQLContainer("postgres:18.4");

    private final List<AutoCloseable>              toClose  = new ArrayList<>();
    private final CountDownLatch                   release  = new CountDownLatch(1);
    /**
     * "orderDetails@instance" per handled event
     */
    private final ConcurrentLinkedQueue<String>    handled  = new ConcurrentLinkedQueue<>();
    /**
     * The cause of every stop of a subscription by its error policy
     */
    private final ConcurrentLinkedQueue<String>    stops    = new ConcurrentLinkedQueue<>();
    private       HikariDataSource                 dataSource;
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
        ShardOwnedSchema.registerQueue(dataSource, engineName(InboxName.of(PROCESSOR).asQueueName()), 2);
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
                                                 stops.add(stoppedAtGlobalEventOrder + ": " + cause);
                                             }
                                         })
                                         .build();
        eventStore.addAggregateEventStreamConfiguration(ORDERS, AggregateIdSerializer.serializerFor(OrderId.class));
    }

    @AfterEach
    void tearDown() throws Exception {
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

    @Test
    void a_reader_that_fails_over_uncleanly_and_re_forwards_queued_events_does_not_stop() {
        var a = instance("a");
        var orderId = OrderId.random();
        for (var index = 0; index < EVENTS; index++) {
            var details = "event-" + index;
            unitOfWorkFactory.usingUnitOfWork(() -> eventStore.appendToStream(ORDERS, orderId, new OrderPlacedEvent(orderId, details)));
        }
        // Forwarded, and held in the queue: the first is in A's held handler, the rest wait behind it on the key
        var inbox = InboxName.of(PROCESSOR).asQueueName();
        Awaitility.await().atMost(Duration.ofSeconds(20)).until(() -> a.durableQueues.getTotalMessagesQueuedFor(inbox) == EVENTS);

        var b = instance("b");
        // A dies without releasing anything: its locks time out, B takes the reader over from the resume point A never moved
        a.lockManager.pause();
        Awaitility.await().atMost(Duration.ofSeconds(20)).until(() -> readerLockHolder().equals(Optional.of("b")));

        // The reader keeps going: an event appended after the takeover is forwarded by B
        unitOfWorkFactory.usingUnitOfWork(() -> eventStore.appendToStream(ORDERS, orderId, new OrderPlacedEvent(orderId, "after-takeover")));
        // The originals stay queued for longer than the subscription's retries of the re-forward last (RETRY_N_THEN_STOP,
        // 3 retries, 100 ms doubling): a backlog that drains in seconds, not in a few hundred milliseconds
        sleep(Duration.ofSeconds(5));
        release.countDown();
        Awaitility.await().atMost(Duration.ofSeconds(60))
                  .untilAsserted(() -> assertThat(handled.stream().map(entry -> entry.substring(0, entry.indexOf('@'))).distinct().count())
                          .as("distinct events handled: %s", handled)
                          .isEqualTo(EVENTS + 1));
        System.out.printf("%nRe-forward after an unclean reader failover: %d deliveries of %d events: %s; stops: %s%n%n", handled.size(), EVENTS + 1, handled, stops);
        assertThat(stops).as("the reader stopped by its error policy - the re-forward of a still queued event was refused").isEmpty();
    }

    private static void sleep(Duration duration) {
        try {
            Thread.sleep(duration.toMillis());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        }
    }

    /**
     * The holder of the reader's lock, {@code [<subscriber id>-<aggregate type>]}, as the lock table has it
     */
    private Optional<String> readerLockHolder() {
        return Jdbi.create(dataSource).withHandle(handle -> handle.createQuery("SELECT locked_by_lockmanager_instance_id FROM fenced_locks"
                                                                                       + " WHERE lock_name LIKE :name AND lock_name NOT LIKE 'Inbox:%'"
                                                                                       + " AND locked_by_lockmanager_instance_id IS NOT NULL")
                                                                  .bind("name", "%" + PROCESSOR + "%")
                                                                  .mapTo(String.class)
                                                                  .findOne());
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
        var processor = new ReForwardProcessor(new EventProcessorDependencies(subscriptionManager,
                                                                              new Inboxes.DurableQueueBasedInboxes(durableQueues, lockManager),
                                                                              commandBus,
                                                                              List.of()),
                                               id);
        processor.start();
        toClose.add(processor::stop);
        return new Instance(lockManager, durableQueues);
    }

    private record Instance(PostgresqlFencedLockManager lockManager, ShardOwnedDurableQueues durableQueues) {
    }

    private class ReForwardProcessor extends EventProcessor {
        private final String instance;

        ReForwardProcessor(EventProcessorDependencies dependencies, String instance) {
            super(dependencies);
            this.instance = instance;
        }

        @Override
        public String getProcessorName() {
            return PROCESSOR;
        }

        @Override
        protected List<AggregateType> reactsToEventsRelatedToAggregateTypes() {
            return List.of(ORDERS);
        }

        @Override
        protected dk.trustworks.essentials.components.foundation.messaging.RedeliveryPolicy getInboxRedeliveryPolicy() {
            return dk.trustworks.essentials.components.foundation.messaging.RedeliveryPolicy.fixedBackoff(Duration.ofMillis(200), 100);
        }

        @MessageHandler
        void on(OrderPlacedEvent event) throws InterruptedException {
            release.await();
            handled.add(event.orderDetails + "@" + instance);
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
