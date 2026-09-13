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
import dk.trustworks.essentials.components.foundation.messaging.queue.operations.*;
import dk.trustworks.essentials.components.foundation.transaction.jdbi.JdbiUnitOfWorkFactory;
import dk.trustworks.essentials.components.queue.shardowned.ShardOwnedSchema;
import dk.trustworks.essentials.shared.interceptor.InterceptorChain;
import org.awaitility.Awaitility;
import org.jdbi.v3.core.Jdbi;
import org.junit.jupiter.api.*;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.*;

import java.time.Duration;
import java.util.*;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.assertj.core.api.Assertions.*;

/**
 * A {@link DurableQueuesInterceptor} added to this adapter runs, and runs where it does on the other
 * engine.
 * <p>
 * The adapter used to refuse {@code addInterceptor} outright, on the grounds that the shard-owned
 * engine has its own chain. That was true and still is — but it left
 * {@code spring-boot-starter-postgresql-queue-shard-owned} silently dropping every interceptor bean
 * the application had, including the framework's own measurement one. These tests pin the two things
 * that make running them here honest: every operation is seen exactly once, and a delivery
 * interceptor is told what the partial message can actually answer.
 */
@Testcontainers(disabledWithoutDocker = true)
class InterceptorsOnShardOwnedIT {

    private static final QueueName QUEUE = QueueName.of("orders");

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:17.5-bookworm");

    private HikariDataSource        dataSource;
    private JdbiUnitOfWorkFactory   unitOfWorkFactory;
    private TestMessageQueues       queues;
    private ShardOwnedDurableQueues durableQueues;
    private RecordingInterceptor    interceptor;

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
        interceptor = new RecordingInterceptor();
        durableQueues.addInterceptor(interceptor);
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
    void an_added_interceptor_is_reported_as_added() {
        assertThat(durableQueues.getInterceptors()).containsExactly(interceptor);

        durableQueues.removeInterceptor(interceptor);
        assertThat(durableQueues.getInterceptors()).isEmpty();
    }

    /**
     * The claim the whole change rests on: an interceptor sees the enqueue and it sees the delivery.
     * {@code HandleQueuedMessage} is the one that is not an adapter method at all — it happens inside
     * the engine's own delivery, and reaches the chain through the consumer.
     */
    @Test
    void an_interceptor_sees_the_enqueue_and_the_delivery() {
        var handled = new CopyOnWriteArrayList<Object>();
        durableQueues.consumeFromQueue(ConsumeFromQueue.builder()
                                                       .setQueueName(QUEUE)
                                                       .setRedeliveryPolicy(RedeliveryPolicy.fixedBackoff(Duration.ofMillis(200), 3))
                                                       .setParallelConsumers(1)
                                                       .setQueueMessageHandler(message -> handled.add(message.getPayload()))
                                                       .build());

        unitOfWorkFactory.usingUnitOfWork(() -> durableQueues.queueMessage(QUEUE, Message.of(new OrderPlaced("order-1", 100))));

        Awaitility.await().atMost(Duration.ofSeconds(30))
                  .untilAsserted(() -> assertThat(handled).hasSize(1));
        Awaitility.await().atMost(Duration.ofSeconds(30))
                  .untilAsserted(() -> assertThat(interceptor.operations).contains("HandleQueuedMessage"));

        assertThat(interceptor.operations).contains("ConsumeFromQueue", "QueueMessage", "HandleQueuedMessage");
    }

    /**
     * {@code queueMessage} is implemented over the same enqueue as {@code queueMessages}, and routing
     * one through the other would show an interceptor two operations for one call — which a metrics
     * interceptor would report as two enqueues.
     */
    @Test
    void queueing_one_message_is_one_operation_and_not_two() {
        unitOfWorkFactory.usingUnitOfWork(() -> durableQueues.queueMessage(QUEUE, Message.of(new OrderPlaced("order-1", 100))));

        assertThat(interceptor.operations).containsExactly("QueueMessage");
    }

    /** The same, in the other direction: a batch is one {@code QueueMessages}, not N. */
    @Test
    void queueing_a_batch_is_one_operation_and_not_one_per_message() {
        unitOfWorkFactory.usingUnitOfWork(() -> durableQueues.queueMessages(QUEUE,
                                                                            List.of(Message.of(new OrderPlaced("order-1", 100)),
                                                                                    Message.of(new OrderPlaced("order-2", 200)),
                                                                                    Message.of(new OrderPlaced("order-3", 300)))));

        assertThat(interceptor.operations).containsExactly("QueueMessages");
    }

    /**
     * An interceptor is in the call path, not beside it. Not proceeding has to mean nothing was
     * written — otherwise a filtering or multi-tenancy interceptor would be decoration.
     */
    @Test
    void an_interceptor_that_does_not_proceed_enqueues_nothing() {
        durableQueues.removeInterceptor(interceptor);
        durableQueues.addInterceptor(new RefusingInterceptor());

        var entryIds = unitOfWorkFactory.withUnitOfWork(() -> durableQueues.queueMessages(
                QUEUE, List.of(Message.of(new OrderPlaced("order-1", 100)))));

        assertThat(entryIds).isEmpty();
        assertThat(durableQueues.getTotalMessagesQueuedFor(QUEUE)).isZero();
    }

    /**
     * What a delivery interceptor may read off the message it is handed.
     * <p>
     * The engine hands the handler {@code (key, payload, payloadType)} only, so the message on the
     * delivery path is the partial shape: the queue name and the payload are there, the id and the
     * attempt count are not and throw rather than report a made-up value. This is exactly what
     * {@code RecordExecutionTimeDurableQueueInterceptor} tags with, and the boundary an interceptor
     * author has to know — reaching for the id fails the delivery.
     */
    @Test
    void a_delivery_interceptor_gets_the_queue_name_and_the_payload_but_not_the_id() {
        var seen = new CopyOnWriteArrayList<QueuedMessage>();
        durableQueues.removeInterceptor(interceptor);
        durableQueues.addInterceptor(new DurableQueuesInterceptor() {
            @Override
            public void setDurableQueues(DurableQueues durableQueues) {
            }

            @Override
            public Void intercept(HandleQueuedMessage operation, InterceptorChain<HandleQueuedMessage, Void, DurableQueuesInterceptor> interceptorChain) {
                seen.add(operation.message);
                return interceptorChain.proceed();
            }
        });

        var handled = new CopyOnWriteArrayList<Object>();
        durableQueues.consumeFromQueue(ConsumeFromQueue.builder()
                                                       .setQueueName(QUEUE)
                                                       .setRedeliveryPolicy(RedeliveryPolicy.fixedBackoff(Duration.ofMillis(200), 3))
                                                       .setParallelConsumers(1)
                                                       .setQueueMessageHandler(message -> handled.add(message.getPayload()))
                                                       .build());
        unitOfWorkFactory.usingUnitOfWork(() -> durableQueues.queueMessage(QUEUE, Message.of(new OrderPlaced("order-1", 100))));

        Awaitility.await().atMost(Duration.ofSeconds(30)).untilAsserted(() -> assertThat(seen).hasSize(1));

        var message = seen.get(0);
        assertThat(message.getQueueName().toString()).isEqualTo(QUEUE.toString());
        assertThat(message.getPayload()).isEqualTo(new OrderPlaced("order-1", 100));
        assertThatThrownBy(message::getId)
                .as("the id is not on the delivery path, and a made-up one would be worse than a throw")
                .isInstanceOf(UnsupportedOperationException.class);
        assertThatThrownBy(message::getTotalDeliveryAttempts)
                .isInstanceOf(UnsupportedOperationException.class);
    }

    private static dk.trustworks.essentials.components.queue.shardowned.spi.QueueName engineName(QueueName queueName) {
        return dk.trustworks.essentials.components.queue.shardowned.spi.QueueName.of(queueName.toString());
    }

    /** Records the name of every operation it is asked about, in the order it sees them. */
    private static final class RecordingInterceptor implements DurableQueuesInterceptor {
        private final List<String> operations = new CopyOnWriteArrayList<>();

        @Override
        public void setDurableQueues(DurableQueues durableQueues) {
        }

        @Override
        public QueueEntryId intercept(QueueMessage operation, InterceptorChain<QueueMessage, QueueEntryId, DurableQueuesInterceptor> interceptorChain) {
            operations.add("QueueMessage");
            return interceptorChain.proceed();
        }

        @Override
        public List<QueueEntryId> intercept(QueueMessages operation, InterceptorChain<QueueMessages, List<QueueEntryId>, DurableQueuesInterceptor> interceptorChain) {
            operations.add("QueueMessages");
            return interceptorChain.proceed();
        }

        @Override
        public DurableQueueConsumer intercept(ConsumeFromQueue operation, InterceptorChain<ConsumeFromQueue, DurableQueueConsumer, DurableQueuesInterceptor> interceptorChain) {
            operations.add("ConsumeFromQueue");
            return interceptorChain.proceed();
        }

        @Override
        public Void intercept(HandleQueuedMessage operation, InterceptorChain<HandleQueuedMessage, Void, DurableQueuesInterceptor> interceptorChain) {
            operations.add("HandleQueuedMessage");
            return interceptorChain.proceed();
        }
    }

    /** Stops the chain rather than proceeding, and answers for the operation itself. */
    private static final class RefusingInterceptor implements DurableQueuesInterceptor {
        @Override
        public void setDurableQueues(DurableQueues durableQueues) {
        }

        @Override
        public List<QueueEntryId> intercept(QueueMessages operation, InterceptorChain<QueueMessages, List<QueueEntryId>, DurableQueuesInterceptor> interceptorChain) {
            return List.of();
        }
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
                            .setInstanceId("interceptor-test")
                            .build()));
        }

        @Override
        public synchronized void close() {
            built.values().forEach(dk.trustworks.essentials.components.queue.shardowned.PostgresqlMessageQueue::close);
        }
    }
}
