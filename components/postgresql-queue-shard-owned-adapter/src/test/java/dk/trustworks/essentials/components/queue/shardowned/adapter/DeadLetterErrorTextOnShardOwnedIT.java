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

import static org.assertj.core.api.Assertions.assertThat;

/**
 * What a dead letter's {@link QueuedMessage#getLastDeliveryError()} says when the handler's exception reached the
 * engine wrapped.
 * <p>
 * The engine used to record only the outermost throwable, so a handler failing inside a wrapper - the framework's own
 * unit-of-work and reflection exceptions, or the application's - left a dead letter naming the wrapper and nothing
 * about why the message failed. The text now starts with the root cause, the way {@code PostgresqlDurableQueues}
 * renders it, on both lanes.
 */
@Testcontainers(disabledWithoutDocker = true)
class DeadLetterErrorTextOnShardOwnedIT {

    private static final QueueName QUEUE = QueueName.of("orders");

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
        durableQueues.consumeFromQueue(ConsumeFromQueue.builder()
                                                       .setQueueName(QUEUE)
                                                       .setRedeliveryPolicy(RedeliveryPolicy.fixedBackoff(Duration.ofMillis(10), 1))
                                                       .setParallelConsumers(1)
                                                       .setQueueMessageHandler(message -> {
                                                           throw new RuntimeException("handler wrapper",
                                                                                      new IllegalStateException("cannot ship " + message.getPayload()));
                                                       })
                                                       .build());
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
    void an_unordered_dead_letter_names_the_root_cause_of_a_wrapped_handler_exception() {
        var id = unitOfWorkFactory.withUnitOfWork(() -> durableQueues.queueMessage(QUEUE, Message.of("order-1")));

        assertRootCauseRecorded(id, "order-1");
    }

    @Test
    void an_ordered_dead_letter_names_the_root_cause_of_a_wrapped_handler_exception() {
        var id = unitOfWorkFactory.withUnitOfWork(() -> durableQueues.queueMessage(QUEUE, OrderedMessage.of("order-2", "key-1", 0)));

        assertRootCauseRecorded(id, "order-2");
    }

    private void assertRootCauseRecorded(QueueEntryId id, String payload) {
        Awaitility.await().atMost(Duration.ofSeconds(30))
                  .untilAsserted(() -> assertThat(durableQueues.getDeadLetterMessage(id)).isPresent());

        var lastError = durableQueues.getDeadLetterMessage(id).orElseThrow().getLastDeliveryError();
        assertThat(lastError)
                .as("the root cause comes first, then the outermost exception as before")
                .startsWith("Root cause: java.lang.IllegalStateException: cannot ship " + payload)
                .contains("java.lang.RuntimeException: handler wrapper");
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
                            .setInstanceId("dead-letter-error-text-test")
                            .build()));
        }

        @Override
        public synchronized void close() {
            built.values().forEach(dk.trustworks.essentials.components.queue.shardowned.PostgresqlMessageQueue::close);
        }
    }
}
