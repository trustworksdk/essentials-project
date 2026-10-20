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

package dk.trustworks.essentials.components.queue.shardowned.adapter;

import com.zaxxer.hikari.*;
import dk.trustworks.essentials.components.foundation.json.EssentialsObjectMappers;
import dk.trustworks.essentials.components.foundation.messaging.RedeliveryPolicy;
import dk.trustworks.essentials.components.foundation.messaging.queue.*;
import dk.trustworks.essentials.components.foundation.messaging.queue.operations.ConsumeFromQueue;
import dk.trustworks.essentials.components.foundation.transaction.jdbi.JdbiUnitOfWorkFactory;
import dk.trustworks.essentials.components.queue.shardowned.*;
import org.awaitility.Awaitility;
import org.jdbi.v3.core.Jdbi;
import org.junit.jupiter.api.*;
import org.testcontainers.junit.jupiter.*;
import org.testcontainers.postgresql.PostgreSQLContainer;

import java.time.Duration;
import java.util.*;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@code ShardOwnedOrderedRetryHandoverIT} through {@code DurableQueues} (N-16, RN-4): an {@link OrderedMessage}
 * waiting out its {@link RedeliveryPolicy} backoff keeps its key blocked when the shard-owned engine hands its
 * unit to another instance — by a rebalance, a graceful stop or a crash. Before the fix the successor delivered the
 * key's next message while the failed one was still in its backoff.
 * <p>
 * Every key's first message fails until {@link #release} is set, which happens only once the second instance has
 * delivered something, so ownership has demonstrably moved while every head was in backoff. Each instance has its
 * own {@code DataSource}, and therefore its own runtime, so a crash can be modelled by taking one instance's pool
 * away: it can no longer renew its liveness, stops dispatching, and its units become takeable once its membership
 * row is older than the lease.
 */
@Testcontainers(disabledWithoutDocker = true)
class OrderedRetryHandoverOnShardOwnedIT {

    private static final QueueName QUEUE   = QueueName.of("retry-handover");
    private static final int       KEYS    = 32;
    private static final Duration  BACKOFF = Duration.ofSeconds(4);

    @Container
    static PostgreSQLContainer postgres = new PostgreSQLContainer("postgres:17.5-bookworm");

    private final AtomicBoolean                release    = new AtomicBoolean();
    private final ConcurrentLinkedQueue<Event> deliveries = new ConcurrentLinkedQueue<>();
    private final List<Instance>               instances  = new ArrayList<>();

    private record Event(String key, long keyOrder, String instance, boolean succeeded) {
    }

    /**
     * One application instance: its own pool, unit of work factory, engine queues and {@code DurableQueues}.
     */
    private record Instance(String id, HikariDataSource dataSource, JdbiUnitOfWorkFactory unitOfWorkFactory,
                            RegisteredMessageQueues queues, ShardOwnedDurableQueues durableQueues) {
        void close() {
            try {
                durableQueues.stop();
            } finally {
                queues.close();
                dataSource.close();
            }
        }
    }

    @BeforeEach
    void setUp() throws Exception {
        try (var dataSource = dataSource()) {
            ShardOwnedSchema.recreate(dataSource);
            ShardOwnedSchema.registerQueue(dataSource, engineName(QUEUE), 2);
        }
    }

    @AfterEach
    void tearDown() {
        release.set(true);
        instances.forEach(Instance::close);
    }

    private static HikariDataSource dataSource() {
        var config = new HikariConfig();
        config.setJdbcUrl(postgres.getJdbcUrl());
        config.setUsername(postgres.getUsername());
        config.setPassword(postgres.getPassword());
        config.setMaximumPoolSize(20);
        return new HikariDataSource(config);
    }

    /**
     * Builds and starts an instance. It joins {@link #instances} before it starts, so {@link #tearDown()} closes it
     * also when {@code start()} throws; a failure before that closes what was already built here.
     */
    private Instance instance(String id) {
        var dataSource = dataSource();
        // PMD.CloseResource: queues is handed to the Instance, whose close() closes it; until the Instance exists,
        // the catch below does
        @SuppressWarnings("PMD.CloseResource")
        var queues = new RegisteredMessageQueues(dataSource, id);
        Instance instance;
        try {
            var unitOfWorkFactory = new JdbiUnitOfWorkFactory(Jdbi.create(dataSource));
            var durableQueues = ShardOwnedDurableQueues.builder()
                                                       .setQueues(queues)
                                                       .setJsonSerializer(EssentialsObjectMappers.createJSONSerializer())
                                                       .setUnitOfWorkFactory(unitOfWorkFactory)
                                                       .setDataSource(dataSource)
                                                       .build();
            instance = new Instance(id, dataSource, unitOfWorkFactory, queues, durableQueues);
        } catch (RuntimeException | Error e) {
            try {
                queues.close();
            } finally {
                dataSource.close();
            }
            throw e;
        }
        instances.add(instance);
        instance.durableQueues().start();
        return instance;
    }

    private void consume(Instance instance) {
        instance.durableQueues().consumeFromQueue(
                ConsumeFromQueue.builder()
                                .setQueueName(QUEUE)
                                .setRedeliveryPolicy(RedeliveryPolicy.fixedBackoff(BACKOFF, 1_000))
                                .setParallelConsumers(4)
                                .setQueueMessageHandler(message -> {
                                    var ordered = (OrderedMessage) message.getMessage();
                                    var fail    = ordered.getOrder() == 0 && !release.get();
                                    deliveries.add(new Event(ordered.getKey(), ordered.getOrder(), instance.id(), !fail));
                                    if (fail) {
                                        throw new RuntimeException("head of " + ordered.getKey() + " fails until released");
                                    }
                                })
                                .build());
    }

    private void enqueueTwoPerKey(Instance instance) {
        instance.unitOfWorkFactory().usingUnitOfWork(() -> {
            for (var order = 0; order < 2; order++) {
                for (var key = 0; key < KEYS; key++) {
                    instance.durableQueues().queueMessage(QUEUE, OrderedMessage.of("key-" + key + "#" + order, "key-" + key, order));
                }
            }
        });
    }

    private void awaitEveryHeadFailedOn(String instance) {
        Awaitility.await().atMost(Duration.ofSeconds(30)).until(() -> deliveries.stream()
                                                                                .filter(event -> event.instance().equals(instance) && !event.succeeded())
                                                                                .map(Event::key).distinct().count() == KEYS);
    }

    /**
     * The successor delivering anything is the evidence that ownership moved; only then are the heads let through.
     */
    private void releaseOnceDeliveringOn(String instance) {
        Awaitility.await().atMost(Duration.ofSeconds(30)).until(() -> deliveries.stream().anyMatch(event -> event.instance().equals(instance)));
        release.set(true);
    }

    private void awaitDrainedInOrder() {
        Awaitility.await().atMost(Duration.ofSeconds(60)).untilAsserted(() -> assertThat(
                deliveries.stream().filter(event -> event.keyOrder() == 1).map(Event::key).distinct().count())
                .isEqualTo(KEYS));
        var overtaken = new TreeSet<String>();
        var headDone  = new HashSet<String>();
        for (var event : List.copyOf(deliveries)) {
            if (event.keyOrder() == 0 && event.succeeded()) {
                headDone.add(event.key());
            } else if (event.keyOrder() == 1 && !headDone.contains(event.key())) {
                overtaken.add(event.key() + " (on " + event.instance() + ")");
            }
        }
        assertThat(overtaken)
                .as("a key's second message was delivered while its first was still waiting out a retry backoff")
                .isEmpty();
    }

    @Test
    void a_rebalance_does_not_let_a_later_message_overtake_one_waiting_out_its_backoff() {
        var first = instance("adapter-retry-a");
        consume(first);
        enqueueTwoPerKey(first);
        awaitEveryHeadFailedOn("adapter-retry-a");

        consume(instance("adapter-retry-b"));
        releaseOnceDeliveringOn("adapter-retry-b");

        awaitDrainedInOrder();
        assertThat(deliveries).as("some key must have finished on the instance that took it over mid-backoff")
                              .anyMatch(event -> event.instance().equals("adapter-retry-b") && event.keyOrder() == 0 && event.succeeded());
    }

    @Test
    void a_graceful_stop_does_not_let_a_later_message_overtake_one_waiting_out_its_backoff() {
        var first = instance("adapter-retry-c");
        consume(first);
        enqueueTwoPerKey(first);
        awaitEveryHeadFailedOn("adapter-retry-c");
        first.durableQueues().stop();

        consume(instance("adapter-retry-d"));
        releaseOnceDeliveringOn("adapter-retry-d");

        awaitDrainedInOrder();
    }

    @Test
    void a_crash_does_not_let_a_later_message_overtake_one_waiting_out_its_backoff() {
        var first = instance("adapter-retry-e");
        consume(first);
        enqueueTwoPerKey(first);
        awaitEveryHeadFailedOn("adapter-retry-e");
        // The instance loses its database without handing anything back: no drain, no release, no deregistration.
        // Closed on another thread, because the pool waits for the connections the pumps hold.
        Thread.ofPlatform().daemon().start(first.dataSource()::close);

        consume(instance("adapter-retry-f"));
        releaseOnceDeliveringOn("adapter-retry-f");

        awaitDrainedInOrder();
    }

    private static dk.trustworks.essentials.components.queue.shardowned.spi.QueueName engineName(QueueName queueName) {
        return dk.trustworks.essentials.components.queue.shardowned.spi.QueueName.of(queueName.toString());
    }

    /**
     * Builds each queue with a one-second lease, so a crashed instance's units are takeable in about a second.
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
        public synchronized List<dk.trustworks.essentials.components.queue.shardowned.spi.QueueName> queueNames()
                throws java.sql.SQLException {
            return ShardOwnedSchema.queueNames(dataSource);
        }

        @Override
        public synchronized Optional<dk.trustworks.essentials.components.queue.shardowned.spi.MessageQueue> findQueue(
                dk.trustworks.essentials.components.queue.shardowned.spi.QueueName queueName) throws java.sql.SQLException {
            var registered = ShardOwnedSchema.resolve(dataSource, queueName);
            if (registered.isEmpty()) {
                return Optional.empty();
            }
            return Optional.of(built.computeIfAbsent(queueName, name -> PostgresqlMessageQueue.builder()
                                                                                              .setDataSource(dataSource)
                                                                                              .setQueueName(name)
                                                                                              .setInstanceId(instanceId)
                                                                                              .setSettings(settings())
                                                                                              .build()));
        }

        private static ShardOwnerSettings settings() {
            return new ShardOwnerSettings(500, 200, Duration.ofMillis(1), Duration.ofMillis(2),
                                          Duration.ofMillis(300), Duration.ofMillis(100), 1_000, 8,
                                          Duration.ofMillis(50), Duration.ofMillis(200), 2, Duration.ofSeconds(5),
                                          Duration.ofMillis(1000), Duration.ofSeconds(60),
                                          ShardOwnerSettings.DEFAULT_STUCK_HANDLER_TIMEOUT);
        }

        @Override
        public synchronized void close() {
            built.values().forEach(PostgresqlMessageQueue::close);
        }
    }
}
