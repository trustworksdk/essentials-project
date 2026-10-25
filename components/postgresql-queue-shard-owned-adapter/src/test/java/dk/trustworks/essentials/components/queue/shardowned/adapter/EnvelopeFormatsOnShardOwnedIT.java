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

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * A message 0.60.0 wrote in envelope format 1 must still be readable everywhere the adapter reads one: the admin
 * listing, the dead-letter listing, and delivery - including after it has been dead-lettered and resurrected, which
 * keeps the format version it was written in. The format-1 rows are put in through the engine, exactly as 0.60.0
 * stored them, since nothing in this version writes that format any more.
 */
@Testcontainers(disabledWithoutDocker = true)
class EnvelopeFormatsOnShardOwnedIT {

    private static final QueueName QUEUE = QueueName.of("legacy");

    @Container
    static PostgreSQLContainer postgres = new PostgreSQLContainer("postgres:17.5-bookworm");

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
    void a_format_1_message_is_listed_dead_lettered_resurrected_and_delivered() throws Exception {
        engineQueue().enqueue(dk.trustworks.essentials.components.queue.shardowned.spi.Message.of(
                formatOne(new OrderPlaced("o-1", 100), "c-1"), MessageEnvelope.FORMAT_VERSION_1));

        var listed = durableQueues.getQueuedMessages(QUEUE, DurableQueues.QueueingSortOrder.ASC, 0, 10);
        assertThat(listed).singleElement().satisfies(message -> {
            assertThat(message.getPayload()).isEqualTo(new OrderPlaced("o-1", 100));
            assertThat(message.getMetaData()).containsEntry("correlationId", "c-1");
        });

        durableQueues.markAsDeadLetterMessage(listed.getFirst().getId());
        var deadLetters = durableQueues.getDeadLetterMessages(QUEUE, DurableQueues.QueueingSortOrder.ASC, 0, 10);
        assertThat(deadLetters).singleElement().satisfies(message -> assertThat(message.getPayload()).isEqualTo(new OrderPlaced("o-1", 100)));

        durableQueues.resurrectDeadLetterMessage(deadLetters.getFirst().getId(), Duration.ZERO);
        var delivered = consume();

        Awaitility.await().atMost(Duration.ofSeconds(15)).untilAsserted(() -> assertThat(delivered).hasSize(1));
        assertThat(delivered.getFirst().getPayload()).isEqualTo(new OrderPlaced("o-1", 100));
        assertThat(delivered.getFirst().getMetaData()).containsEntry("correlationId", "c-1");
    }

    @Test
    void a_queue_holding_both_formats_delivers_both_in_key_order() throws Exception {
        engineQueue().enqueue(dk.trustworks.essentials.components.queue.shardowned.spi.Message.ordered(
                formatOne(new OrderPlaced("o-1", 1), "c-old"), MessageEnvelope.FORMAT_VERSION_1, "o-1", 0));
        unitOfWorkFactory.usingUnitOfWork(() -> durableQueues.queueMessage(QUEUE, OrderedMessage.of(new OrderPlaced("o-1", 2), "o-1", 1)));
        var delivered = consume();

        Awaitility.await().atMost(Duration.ofSeconds(15)).untilAsserted(() -> assertThat(delivered).hasSize(2));
        assertThat(delivered).extracting(Message::getPayload).containsExactly(new OrderPlaced("o-1", 1), new OrderPlaced("o-1", 2));
        assertThat(delivered).allSatisfy(message -> assertThat(message).isInstanceOf(OrderedMessage.class));
        assertThat(delivered.getFirst().getMetaData()).containsEntry("correlationId", "c-old");
    }

    private List<Message> consume() {
        var delivered = new CopyOnWriteArrayList<Message>();
        durableQueues.consumeFromQueue(ConsumeFromQueue.builder()
                                                       .setQueueName(QUEUE)
                                                       .setRedeliveryPolicy(RedeliveryPolicy.fixedBackoff(Duration.ofMillis(10), 1))
                                                       .setParallelConsumers(1)
                                                       .setQueueMessageHandler(message -> delivered.add(message.getMessage()))
                                                       .build());
        return delivered;
    }

    private dk.trustworks.essentials.components.queue.shardowned.spi.MessageQueue engineQueue() throws Exception {
        return queues.findQueue(engineName(QUEUE)).orElseThrow();
    }

    /**
     * The bytes 0.60.0 wrote: payload and metadata serialized, then each held as a JSON string in a flat envelope.
     */
    private static byte[] formatOne(OrderPlaced payload, String correlationId) {
        var json = EssentialsObjectMappers.createJSONSerializer();
        return json.serialize(new MessageEnvelope(OrderPlaced.class.getName(),
                                                  json.serialize(payload),
                                                  json.serialize(MessageMetaData.of("correlationId", correlationId))))
                   .getBytes(StandardCharsets.UTF_8);
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
                            .setInstanceId("envelope-formats-test")
                            .build()));
        }

        @Override
        public synchronized void close() {
            built.values().forEach(dk.trustworks.essentials.components.queue.shardowned.PostgresqlMessageQueue::close);
        }
    }
}
