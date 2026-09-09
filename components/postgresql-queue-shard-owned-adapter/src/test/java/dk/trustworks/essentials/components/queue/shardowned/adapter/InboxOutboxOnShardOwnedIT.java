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
import dk.trustworks.essentials.components.distributed.fencedlock.postgresql.PostgresqlFencedLockManager;
import dk.trustworks.essentials.components.foundation.json.EssentialsObjectMappers;
import dk.trustworks.essentials.components.foundation.messaging.*;
import dk.trustworks.essentials.components.foundation.messaging.eip.store_and_forward.*;
import dk.trustworks.essentials.components.foundation.messaging.queue.*;
import dk.trustworks.essentials.components.foundation.transaction.jdbi.*;
import dk.trustworks.essentials.components.queue.shardowned.ShardOwnedSchema;
import org.awaitility.Awaitility;
import org.jdbi.v3.core.Jdbi;
import org.junit.jupiter.api.*;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.*;

import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;

import static org.assertj.core.api.Assertions.*;

/**
 * The claim this adapter exists to make: {@code Inbox} and {@code Outbox} run on the shard-owned
 * engine <b>unchanged</b>.
 * <p>
 * So these tests use the real {@link Inboxes} and {@link Outboxes} over a real
 * {@link PostgresqlFencedLockManager}, and the only thing swapped out is the {@link DurableQueues}
 * underneath. A test that drove {@link ShardOwnedDurableQueues} directly would prove the adapter's
 * methods work and say nothing about whether the machinery on top of them does.
 */
@Testcontainers(disabledWithoutDocker = true)
class InboxOutboxOnShardOwnedIT {

    /**
     * {@code InboxName.asQueueName()} produces {@code Inbox:<name>} and the outbox equivalent
     * {@code Outbox:<name>} — names that contain the separator the adapter puts inside a
     * {@link QueueEntryId}. They are spelled out here rather than derived so that the collision stays
     * visible: an encoder that split on the first colon would truncate both to {@code "Inbox"} /
     * {@code "Outbox"} and fail against a queue that exists.
     */
    private static final QueueName INBOX_QUEUE  = QueueName.of("Inbox:orders");
    private static final QueueName OUTBOX_QUEUE = QueueName.of("Outbox:orders");

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:17.5-bookworm");

    private HikariDataSource                dataSource;
    private Jdbi                            jdbi;
    private JdbiUnitOfWorkFactory           unitOfWorkFactory;
    private TestMessageQueues               queues;
    private ShardOwnedDurableQueues         durableQueues;
    private PostgresqlFencedLockManager     lockManager;

    @BeforeEach
    void setUp() throws Exception {
        var config = new HikariConfig();
        config.setJdbcUrl(postgres.getJdbcUrl());
        config.setUsername(postgres.getUsername());
        config.setPassword(postgres.getPassword());
        config.setMaximumPoolSize(20);
        dataSource = new HikariDataSource(config);
        jdbi = Jdbi.create(dataSource);
        unitOfWorkFactory = new JdbiUnitOfWorkFactory(jdbi);

        ShardOwnedSchema.recreate(dataSource);
        ShardOwnedSchema.registerQueue(dataSource, engineName(INBOX_QUEUE), 4);
        ShardOwnedSchema.registerQueue(dataSource, engineName(OUTBOX_QUEUE), 4);

        queues = new TestMessageQueues(dataSource);
        durableQueues = ShardOwnedDurableQueues.builder()
                                               .setQueues(queues)
                                               .setJsonSerializer(EssentialsObjectMappers.createJSONSerializer())
                                               .setUnitOfWorkFactory(unitOfWorkFactory)
                                               .build();
        durableQueues.start();

        lockManager = PostgresqlFencedLockManager.builder()
                                                 .setJdbi(jdbi)
                                                 .setUnitOfWorkFactory(unitOfWorkFactory)
                                                 .setLockTimeOut(Duration.ofSeconds(3))
                                                 .setLockConfirmationInterval(Duration.ofSeconds(1))
                                                 .buildAndStart();
    }

    @AfterEach
    void tearDown() {
        if (lockManager != null) {
            lockManager.stop();
        }
        if (durableQueues != null) {
            durableQueues.stop();
        }
        if (queues != null) {
            queues.close();
        }
        if (dataSource != null) {
            dataSource.close();
        }
    }

    // ----------------------------------------------------------------- inbox

    @Test
    void an_inbox_delivers_its_messages_through_the_shard_owned_engine() {
        var received = new CopyOnWriteArrayList<OrderPlaced>();
        var inbox = Inboxes.durableQueueBasedInboxes(durableQueues, lockManager)
                           .getOrCreateInbox(InboxConfig.builder()
                                                        .inboxName(InboxName.of("orders"))
                                                        .redeliveryPolicy(RedeliveryPolicy.fixedBackoff(Duration.ofMillis(200), 3))
                                                        .messageConsumptionMode(MessageConsumptionMode.SingleGlobalConsumer)
                                                        .numberOfParallelMessageConsumers(2)
                                                        .build(),
                                             message -> received.add((OrderPlaced) message.getPayload()));

        inbox.addMessageReceived(Message.of(new OrderPlaced("order-1", 100)));
        inbox.addMessagesReceived(List.of(Message.of(new OrderPlaced("order-2", 200)),
                                          Message.of(new OrderPlaced("order-3", 300))));

        Awaitility.await().atMost(Duration.ofSeconds(30))
                  .untilAsserted(() -> assertThat(received).hasSize(3));
        assertThat(received).extracting(OrderPlaced::id)
                            .containsExactlyInAnyOrder("order-1", "order-2", "order-3");
        assertThat(inbox.getNumberOfUndeliveredMessages()).isZero();
    }

    /**
     * The metadata has to survive the envelope, because {@code SingleGlobalConsumer} puts the fenced
     * lock's token into it on the way to the handler and applications routinely carry correlation ids
     * there. It travels as a separate field rather than inside the payload, so a payload that happens
     * to have a {@code metaData} property cannot collide with it.
     */
    @Test
    void message_metadata_survives_the_round_trip() {
        var received = new CopyOnWriteArrayList<Message>();
        var inbox = Inboxes.durableQueueBasedInboxes(durableQueues, lockManager)
                           .getOrCreateInbox(InboxConfig.builder()
                                                        .inboxName(InboxName.of("orders"))
                                                        .redeliveryPolicy(RedeliveryPolicy.fixedBackoff(Duration.ofMillis(200), 3))
                                                        .messageConsumptionMode(MessageConsumptionMode.SingleGlobalConsumer)
                                                        .numberOfParallelMessageConsumers(1)
                                                        .build(),
                                             received::add);

        var metaData = new MessageMetaData();
        metaData.put("correlation-id", "abc-123");
        inbox.addMessageReceived(Message.of(new OrderPlaced("order-1", 100), metaData));

        Awaitility.await().atMost(Duration.ofSeconds(30)).untilAsserted(() -> assertThat(received).hasSize(1));
        assertThat(received.get(0).getMetaData().get("correlation-id")).isEqualTo("abc-123");
    }

    // ---------------------------------------------------------------- outbox

    /**
     * The property an Outbox exists for: the message and the caller's own database work commit
     * together, or neither does.
     * <p>
     * This is the test that would fail if the adapter enqueued on its own connection instead of on the
     * unit of work's — and it would fail in the direction that matters, leaving a message describing
     * work that was rolled back.
     */
    @Test
    void an_outbox_enqueue_rolls_back_with_the_callers_transaction() {
        var received = new CopyOnWriteArrayList<OrderPlaced>();
        var outbox = outbox(received);

        assertThatThrownBy(() -> unitOfWorkFactory.usingUnitOfWork(unitOfWork -> {
            outbox.sendMessage(Message.of(new OrderPlaced("rolled-back", 1)));
            throw new IllegalStateException("the caller's own work failed");
        })).hasMessageContaining("the caller's own work failed");

        assertThat(durableQueues.getTotalMessagesQueuedFor(OUTBOX_QUEUE))
                .describedAs("the enqueue joined the caller's transaction, so it died with it")
                .isZero();

        // And the committing case still delivers, so the test above is not passing because nothing works.
        unitOfWorkFactory.usingUnitOfWork(unitOfWork -> outbox.sendMessage(Message.of(new OrderPlaced("committed", 2))));

        Awaitility.await().atMost(Duration.ofSeconds(30))
                  .untilAsserted(() -> assertThat(received).extracting(OrderPlaced::id).containsExactly("committed"));
    }

    private Outbox outbox(List<OrderPlaced> received) {
        return Outboxes.durableQueueBasedOutboxes(durableQueues, lockManager)
                       .getOrCreateOutbox(OutboxConfig.builder()
                                                      .setOutboxName(OutboxName.of("orders"))
                                                      .setRedeliveryPolicy(RedeliveryPolicy.fixedBackoff(Duration.ofMillis(200), 3))
                                                      .setMessageConsumptionMode(MessageConsumptionMode.SingleGlobalConsumer)
                                                      .setNumberOfParallelMessageConsumers(1)
                                                      .build(),
                                          message -> received.add((OrderPlaced) message.getPayload()));
    }

    // ------------------------------------------------------------- test data

    record OrderPlaced(String id, int amount) {
    }

    private static dk.trustworks.essentials.components.queue.shardowned.spi.QueueName engineName(QueueName queueName) {
        return dk.trustworks.essentials.components.queue.shardowned.spi.QueueName.of(queueName.toString());
    }

    private static final class TestMessageQueues
            implements dk.trustworks.essentials.components.queue.shardowned.spi.MessageQueues, AutoCloseable {
        private final javax.sql.DataSource dataSource;
        private final Map<dk.trustworks.essentials.components.queue.shardowned.spi.QueueName,
                dk.trustworks.essentials.components.queue.shardowned.PostgresqlMessageQueue> built = new LinkedHashMap<>();

        TestMessageQueues(javax.sql.DataSource dataSource) {
            this.dataSource = dataSource;
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
            return Optional.of(built.computeIfAbsent(
                    queueName,
                    name -> dk.trustworks.essentials.components.queue.shardowned.PostgresqlMessageQueue
                            .builder()
                            .setDataSource(dataSource)
                            .setQueueName(name)
                            .setInstanceId("adapter-test")
                            .build()));
        }

        @Override
        public synchronized void close() {
            built.values().forEach(dk.trustworks.essentials.components.queue.shardowned.PostgresqlMessageQueue::close);
        }
    }
}
