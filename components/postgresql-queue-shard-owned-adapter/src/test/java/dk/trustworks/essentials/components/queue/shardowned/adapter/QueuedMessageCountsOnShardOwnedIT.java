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
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.*;

import java.time.*;
import java.util.*;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.assertj.core.api.Assertions.*;

/**
 * What {@link ShardOwnedDurableQueues#getQueuedMessageCountsFor} reports - the cluster-wide half of the admin
 * queue statistics.
 * <p>
 * The engine writes nothing per delivery, so the in-flight count is unknown and must come back as {@code null},
 * never 0: a 0 next to a growing oldest-ready age reads as a stalled queue. The oldest-ready timestamp is real,
 * and these tests pin what it covers: a waiting message counts, a delayed one does not, and an ordered key stopped
 * behind a dead letter shows as dead letters rather than as waiting work.
 */
@Testcontainers(disabledWithoutDocker = true)
class QueuedMessageCountsOnShardOwnedIT {

    private static final QueueName QUEUE = QueueName.of("orders");

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:17.5-bookworm");

    private HikariDataSource        dataSource;
    private JdbiUnitOfWorkFactory   unitOfWorkFactory;
    private TestMessageQueues       queues;
    private ShardOwnedDurableQueues durableQueues;

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

        queues = new TestMessageQueues(dataSource);
        durableQueues = ShardOwnedDurableQueues.builder()
                                               .setQueues(queues)
                                               .setJsonSerializer(EssentialsObjectMappers.createJSONSerializer())
                                               .setUnitOfWorkFactory(unitOfWorkFactory)
                                               .setDataSource(dataSource)
                                               .build();
        durableQueues.start();
    }

    @AfterEach
    void tearDown() {
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
    void an_empty_queue_reports_nothing_ready_and_an_unknown_in_flight_count() {
        var counts = durableQueues.getQueuedMessageCountsFor(QUEUE);

        assertThat(counts.numberOfQueuedMessages()).isZero();
        assertThat(counts.numberOfQueuedDeadLetterMessages()).isZero();
        assertThat(counts.numberOfMessagesBeingDelivered()).as("unknown, never 0").isNull();
        assertThat(counts.oldestReadyMessageTimestamp()).isNull();
    }

    @Test
    void a_waiting_message_sets_the_oldest_ready_timestamp() {
        var before = Instant.now();
        unitOfWorkFactory.usingUnitOfWork(() -> {
            durableQueues.queueMessage(QUEUE, Message.of(new OrderPlaced("order-1", 100)));
            durableQueues.queueMessage(QUEUE, Message.of(new OrderPlaced("order-2", 200)));
        });

        var counts = durableQueues.getQueuedMessageCountsFor(QUEUE);

        assertThat(counts.numberOfQueuedMessages()).isEqualTo(2);
        assertThat(counts.numberOfMessagesBeingDelivered()).isNull();
        // The timestamp is the database's now(), so allow for the container's clock
        assertThat(counts.oldestReadyMessageTimestamp()).isBetween(before.minusSeconds(5), Instant.now().plusSeconds(5));
    }

    @Test
    void a_delayed_message_is_queued_but_not_ready() {
        unitOfWorkFactory.usingUnitOfWork(() -> durableQueues.queueMessage(QUEUE, Message.of(new OrderPlaced("later", 1)), Duration.ofHours(1)));

        var counts = durableQueues.getQueuedMessageCountsFor(QUEUE);

        assertThat(counts.numberOfQueuedMessages()).isEqualTo(1);
        assertThat(counts.oldestReadyMessageTimestamp()).isNull();
    }

    /**
     * An ordered key whose head was dead-lettered stops there, and the engine dead-letters the messages behind it
     * unhandled - so the stopped key shows in the dead-letter count, which is what the dead-letter health indicator
     * watches, and leaves nothing behind that looks ready.
     */
    @Test
    void a_key_stopped_behind_a_dead_letter_shows_as_dead_letters_not_as_waiting_work() {
        var handled = new CopyOnWriteArrayList<Object>();
        durableQueues.consumeFromQueue(ConsumeFromQueue.builder()
                                                       .setQueueName(QUEUE)
                                                       .setRedeliveryPolicy(RedeliveryPolicy.fixedBackoff(Duration.ofMillis(10), 1))
                                                       .setParallelConsumers(1)
                                                       .setQueueMessageHandler(message -> {
                                                           handled.add(message.getPayload());
                                                           if ("poison".equals(message.getPayload())) {
                                                               throw new IllegalStateException("cannot handle poison");
                                                           }
                                                       })
                                                       .build());

        unitOfWorkFactory.usingUnitOfWork(() -> {
            durableQueues.queueMessage(QUEUE, OrderedMessage.of("poison", "key-1", 0));
            durableQueues.queueMessage(QUEUE, OrderedMessage.of("behind", "key-1", 1));
        });

        Awaitility.await().atMost(Duration.ofSeconds(30))
                  .untilAsserted(() -> assertThat(durableQueues.getQueuedMessageCountsFor(QUEUE).numberOfQueuedDeadLetterMessages())
                          .as("the failed head and the message behind it").isEqualTo(2));

        var counts = durableQueues.getQueuedMessageCountsFor(QUEUE);
        assertThat(handled).doesNotContain("behind");
        assertThat(counts.numberOfQueuedMessages()).isZero();
        assertThat(counts.numberOfMessagesBeingDelivered()).isNull();
        assertThat(counts.oldestReadyMessageTimestamp()).isNull();
    }

    private static dk.trustworks.essentials.components.queue.shardowned.spi.QueueName engineName(QueueName queueName) {
        return dk.trustworks.essentials.components.queue.shardowned.spi.QueueName.of(queueName.toString());
    }

    private record OrderPlaced(String id, int amount) {
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
                            .setInstanceId("counts-test")
                            .build()));
        }

        @Override
        public synchronized void close() {
            built.values().forEach(dk.trustworks.essentials.components.queue.shardowned.PostgresqlMessageQueue::close);
        }
    }
}
