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
import dk.trustworks.essentials.components.queue.shardowned.ShardOwnedSchema;
import org.awaitility.Awaitility;
import org.jdbi.v3.core.Jdbi;
import org.junit.jupiter.api.*;
import org.testcontainers.junit.jupiter.*;
import org.testcontainers.postgresql.PostgreSQLContainer;

import java.time.Duration;
import java.util.*;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.assertj.core.api.Assertions.*;

/**
 * The adapter enqueues inside the caller's {@code UnitOfWork} without the engine's in-transaction {@code NOTIFY}, and
 * wakes the owners from the unit of work's {@code afterCommit}. With the consumer in this process the wake-up is an
 * in-memory signal, so these tests expect deliveries and no notifications at all - counted by a {@code LISTEN} of the
 * test's own on the wake-up channel.
 */
@Testcontainers(disabledWithoutDocker = true)
class WakeUpAfterCommitOnShardOwnedIT {

    private static final QueueName QUEUE = QueueName.of("wake-up-after-commit");

    @Container
    static PostgreSQLContainer postgres = new PostgreSQLContainer("postgres:17.5-bookworm");

    private HikariDataSource        dataSource;
    private JdbiUnitOfWorkFactory   unitOfWorkFactory;
    private CachingMessageQueues    queues;
    private ShardOwnedDurableQueues durableQueues;
    private final List<Object>      delivered = new CopyOnWriteArrayList<>();
    private java.sql.Connection     listening;

    @BeforeEach
    void setUp() throws Exception {
        var config = new HikariConfig();
        config.setJdbcUrl(postgres.getJdbcUrl());
        config.setUsername(postgres.getUsername());
        config.setPassword(postgres.getPassword());
        config.setMaximumPoolSize(20);
        dataSource = new HikariDataSource(config);
        unitOfWorkFactory = new JdbiUnitOfWorkFactory(Jdbi.create(dataSource));

        ShardOwnedSchema.recreate(dataSource);
        ShardOwnedSchema.registerQueue(dataSource, engineName(QUEUE), 2);

        queues = new CachingMessageQueues(dataSource);
        durableQueues = ShardOwnedDurableQueues.builder()
                                               .setQueues(queues)
                                               .setJsonSerializer(EssentialsObjectMappers.createJSONSerializer())
                                               .setUnitOfWorkFactory(unitOfWorkFactory)
                                               .setDataSource(dataSource)
                                               .build();
        durableQueues.start();
        durableQueues.consumeFromQueue(ConsumeFromQueue.builder()
                                                       .setQueueName(QUEUE)
                                                       .setRedeliveryPolicy(RedeliveryPolicy.fixedBackoff(Duration.ofMillis(10), 1))
                                                       .setParallelConsumers(1)
                                                       .setQueueMessageHandler(message -> delivered.add(message.getPayload()))
                                                       .build());
        // Every unit owned before anything is enqueued, so each wake-up has an owner here to reach
        Thread.sleep(3_000);
        listening = dataSource.getConnection();
        try (var statement = listening.createStatement()) {
            statement.execute("LISTEN " + dk.trustworks.essentials.components.queue.shardowned.ShardWakeupListener.CHANNEL);
        }
    }

    @AfterEach
    void tearDown() throws Exception {
        if (listening != null) {
            listening.close();
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

    @Test
    void a_message_queued_in_a_unit_of_work_is_delivered_after_commit_without_a_notification() throws Exception {
        unitOfWorkFactory.usingUnitOfWork(() -> {
            durableQueues.queueMessage(QUEUE, Message.of(new Event("unordered")));
            durableQueues.queueMessage(QUEUE, OrderedMessage.of(new Event("ordered"), "key-1", 0));
        });

        Awaitility.await().atMost(Duration.ofSeconds(10))
                  .untilAsserted(() -> assertThat(delivered).containsExactlyInAnyOrder(new Event("unordered"), new Event("ordered")));
        assertThat(notificationsSent()).as("notifications").isZero();
    }

    @Test
    void a_message_queued_in_a_unit_of_work_that_rolls_back_is_neither_delivered_nor_notified() throws Exception {
        assertThatThrownBy(() -> unitOfWorkFactory.usingUnitOfWork(() -> {
            durableQueues.queueMessage(QUEUE, Message.of(new Event("rolled-back")));
            throw new IllegalStateException("roll back");
        })).hasRootCauseInstanceOf(IllegalStateException.class);

        Thread.sleep(1_500);
        assertThat(delivered).isEmpty();
        assertThat(notificationsSent()).isZero();
    }

    @Test
    void a_message_queued_outside_a_unit_of_work_is_delivered_without_a_notification() throws Exception {
        durableQueues.queueMessage(QUEUE, Message.of(new Event("no-unit-of-work")));

        Awaitility.await().atMost(Duration.ofSeconds(10)).untilAsserted(() -> assertThat(delivered).containsExactly(new Event("no-unit-of-work")));
        assertThat(notificationsSent()).isZero();
    }

    private int notificationsSent() throws Exception {
        Thread.sleep(500);
        var notifications = listening.unwrap(org.postgresql.PGConnection.class).getNotifications(500);
        return notifications == null ? 0 : notifications.length;
    }

    private static dk.trustworks.essentials.components.queue.shardowned.spi.QueueName engineName(QueueName queueName) {
        return dk.trustworks.essentials.components.queue.shardowned.spi.QueueName.of(queueName.toString());
    }

    private record Event(String id) {
    }

    /**
     * Resolves a queue against the registry once and keeps it - every lookup after that is free, as in a running
     * application, so the only connections the tests measure are the key check's own.
     */
    private static final class CachingMessageQueues
            implements dk.trustworks.essentials.components.queue.shardowned.spi.MessageQueues, AutoCloseable {
        private final javax.sql.DataSource dataSource;
        private final Map<dk.trustworks.essentials.components.queue.shardowned.spi.QueueName,
                dk.trustworks.essentials.components.queue.shardowned.PostgresqlMessageQueue> built = new LinkedHashMap<>();

        CachingMessageQueues(javax.sql.DataSource dataSource) {
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
            var queue = built.get(queueName);
            if (queue == null) {
                if (ShardOwnedSchema.resolve(dataSource, queueName).isEmpty()) {
                    return Optional.empty();
                }
                queue = dk.trustworks.essentials.components.queue.shardowned.PostgresqlMessageQueue
                        .builder()
                        .setDataSource(dataSource)
                        .setQueueName(queueName)
                        .setInstanceId("wake-up-test")
                        .build();
                built.put(queueName, queue);
            }
            return Optional.of(queue);
        }

        @Override
        public synchronized void close() {
            built.values().forEach(dk.trustworks.essentials.components.queue.shardowned.PostgresqlMessageQueue::close);
        }
    }
}
