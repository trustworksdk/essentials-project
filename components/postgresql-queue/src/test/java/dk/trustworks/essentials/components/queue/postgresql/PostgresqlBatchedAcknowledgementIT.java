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

package dk.trustworks.essentials.components.queue.postgresql;

import dk.trustworks.essentials.components.foundation.json.EssentialsObjectMappers;
import dk.trustworks.essentials.components.foundation.messaging.RedeliveryPolicy;
import dk.trustworks.essentials.components.foundation.messaging.queue.*;
import dk.trustworks.essentials.components.foundation.messaging.queue.operations.*;
import dk.trustworks.essentials.components.foundation.test.EssentialsTestContainers;
import dk.trustworks.essentials.components.foundation.transaction.jdbi.JdbiUnitOfWorkFactory;
import dk.trustworks.essentials.shared.interceptor.InterceptorChain;
import org.awaitility.Awaitility;
import org.jdbi.v3.core.Jdbi;
import org.junit.jupiter.api.*;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.*;

import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Covers batched acknowledgement — the largest per-message win available in the queue, and the one with the
 * sharpest failure mode.
 * <p>
 * Acknowledging one message at a time measured <b>16.5x</b> more expensive on drain time than acknowledging a
 * batch, because the cost is the transaction rather than the {@code DELETE}; see
 * {@code docs/durable-queues-measurements.md} §2. This suite asserts the two things that make
 * batching safe to turn on rather than the speed-up, which belongs in the performance lab:
 * <ol>
 *     <li><b>Nothing is lost.</b> Every message is handled exactly once from the handler's point of view and
 *     the queue really does end up empty — a buffer that drops acknowledgements would leave rows behind, and a
 *     buffer that never flushes would too.</li>
 *     <li><b>Ordered messages are not buffered.</b> The per-key barrier reads completion from the
 *     <em>absence</em> of a lower-{@code key_order} row, so a buffered acknowledgement stalls every later
 *     message for that key. Deferring ordered acknowledgements measured 0.82x — worse than not batching. The
 *     exclusion is asserted directly, by counting which acknowledgement operation each message went through,
 *     because a regression here is a silent performance and latency cliff rather than a failure.</li>
 * </ol>
 */
@Testcontainers
class PostgresqlBatchedAcknowledgementIT {

    @Container
    private static final PostgreSQLContainer<?> postgreSQLContainer = EssentialsTestContainers.postgres("batched-ack-queue-db");

    private JdbiUnitOfWorkFactory      unitOfWorkFactory;
    private PostgresqlDurableQueues    durableQueues;
    private AcknowledgementModeCounter acknowledgementCounter;

    @BeforeEach
    void setUp() {
        unitOfWorkFactory = new JdbiUnitOfWorkFactory(Jdbi.create(postgreSQLContainer.getJdbcUrl(),
                                                                  postgreSQLContainer.getUsername(),
                                                                  postgreSQLContainer.getPassword()));
        // The container is static and therefore shared between test methods - start from a clean table.
        unitOfWorkFactory.usingUnitOfWork(unitOfWork -> unitOfWork.handle()
                                                                  .execute("DROP TABLE IF EXISTS " + PostgresqlDurableQueues.DEFAULT_DURABLE_QUEUES_TABLE_NAME));
        acknowledgementCounter = new AcknowledgementModeCounter();
        durableQueues = PostgresqlDurableQueues.builder()
                                               .setUnitOfWorkFactory(unitOfWorkFactory)
                                               .setJsonSerializer(EssentialsObjectMappers.createJSONSerializer())
                                               .setUseBatchedAcknowledgement(true)
                                               .setAcknowledgementMaxBatchSize(16)
                                               .setAcknowledgementFlushInterval(Duration.ofMillis(50))
                                               .build();
        durableQueues.addInterceptor(acknowledgementCounter);
        durableQueues.start();
    }

    @AfterEach
    void tearDown() {
        if (durableQueues != null) {
            durableQueues.stop();
        }
    }

    @Test
    void unordered_messages_are_acknowledged_in_batches_and_none_are_left_behind() {
        var queueName    = QueueName.of("BatchedAckUnordered");
        var messageCount = 200;
        var handled      = new CopyOnWriteArrayList<String>();

        unitOfWorkFactory.usingUnitOfWork(unitOfWork -> durableQueues.queueMessages(queueName, unorderedMessages(messageCount)));

        var consumer = consume(queueName, message -> handled.add(message.getPayload().toString()));
        try {
            Awaitility.waitAtMost(Duration.ofSeconds(30))
                      .untilAsserted(() -> assertThat(handled).hasSize(messageCount));

            // The queue must actually empty out. A buffer that swallowed acknowledgements would satisfy the
            // handler-count assertion above while leaving every row in place.
            Awaitility.waitAtMost(Duration.ofSeconds(30))
                      .untilAsserted(() -> {
                          long remaining = unitOfWorkFactory.withUnitOfWork(unitOfWork -> durableQueues.getTotalMessagesQueuedFor(queueName));
                          assertThat(remaining).isZero();
                      });
        } finally {
            consumer.cancel();
        }

        assertThat(handled).doesNotHaveDuplicates();
        // The point of the feature: far fewer acknowledgement operations than messages. With a batch size of
        // 16 the exact count depends on flush timing, so this asserts the property rather than a number -
        // anything at or above one operation per message means batching did not happen.
        assertThat(acknowledgementCounter.batchedOperations.get()).isPositive();
        assertThat(acknowledgementCounter.batchedOperations.get()).isLessThan(messageCount);
        assertThat(acknowledgementCounter.batchedMessages.get()).isEqualTo(messageCount);
        assertThat(acknowledgementCounter.singleOperations.get()).isZero();
    }

    @Test
    void ordered_messages_are_acknowledged_immediately_even_when_batching_is_enabled() {
        var queueName        = QueueName.of("BatchedAckOrdered");
        var keys             = List.of("key-a", "key-b", "key-c");
        var messagesPerKey   = 20;
        var handledOrderPerKey = new ConcurrentHashMap<String, List<Long>>();

        var messages = new ArrayList<Message>();
        for (var order = 0; order < messagesPerKey; order++) {
            for (var key : keys) {
                messages.add(OrderedMessage.of("payload-" + key + "-" + order, key, order));
            }
        }
        unitOfWorkFactory.usingUnitOfWork(unitOfWork -> durableQueues.queueMessages(queueName, messages));

        var consumer = consume(queueName, message -> {
            var orderedMessage = (OrderedMessage) message.getMessage();
            handledOrderPerKey.computeIfAbsent(orderedMessage.getKey(), key -> new CopyOnWriteArrayList<>())
                              .add(orderedMessage.getOrder());
        });
        try {
            Awaitility.waitAtMost(Duration.ofSeconds(60))
                      .untilAsserted(() -> assertThat(handledOrderPerKey.values().stream().mapToInt(List::size).sum())
                              .isEqualTo(keys.size() * messagesPerKey));
        } finally {
            consumer.cancel();
        }

        // Ordering per key must still hold. If ordered acknowledgements were buffered, the barrier would stall
        // each key until a flush - which shows up as a timeout above rather than as disorder - but a partial
        // regression that flushed eagerly could reorder, so this is asserted too.
        keys.forEach(key -> assertThat(handledOrderPerKey.get(key))
                .as("delivery order for key %s", key)
                .containsExactlyElementsOf(expectedOrders(messagesPerKey)));

        // The exclusion itself: every ordered message went through the single-message operation, and none
        // through the batch.
        assertThat(acknowledgementCounter.singleOperations.get()).isEqualTo(keys.size() * messagesPerKey);
        assertThat(acknowledgementCounter.batchedOperations.get()).isZero();
    }

    private DurableQueueConsumer consume(QueueName queueName, java.util.function.Consumer<QueuedMessage> handler) {
        return durableQueues.consumeFromQueue(queueName,
                                              RedeliveryPolicy.fixedBackoff(Duration.ofMillis(200), 3),
                                              1,
                                              handler::accept);
    }

    private static List<Long> expectedOrders(int messagesPerKey) {
        var orders = new ArrayList<Long>();
        for (var order = 0L; order < messagesPerKey; order++) {
            orders.add(order);
        }
        return orders;
    }

    private static List<Message> unorderedMessages(int count) {
        var messages = new ArrayList<Message>(count);
        for (var i = 0; i < count; i++) {
            messages.add(Message.of("payload-" + i));
        }
        return messages;
    }

    /**
     * Counts which acknowledgement operation each message went through. This is the only way to assert the
     * ordered-message exclusion from outside the component, and it doubles as the check that batching is
     * really batching rather than issuing a one-element batch per message.
     */
    private static final class AcknowledgementModeCounter implements DurableQueuesInterceptor {
        private final AtomicInteger singleOperations  = new AtomicInteger();
        private final AtomicInteger batchedOperations = new AtomicInteger();
        private final AtomicInteger batchedMessages   = new AtomicInteger();

        @Override
        public void setDurableQueues(DurableQueues durableQueues) {
        }

        @Override
        public boolean intercept(AcknowledgeMessageAsHandled operation, InterceptorChain<AcknowledgeMessageAsHandled, Boolean, DurableQueuesInterceptor> interceptorChain) {
            singleOperations.incrementAndGet();
            return interceptorChain.proceed();
        }

        @Override
        public int intercept(AcknowledgeMessagesAsHandled operation, InterceptorChain<AcknowledgeMessagesAsHandled, Integer, DurableQueuesInterceptor> interceptorChain) {
            batchedOperations.incrementAndGet();
            batchedMessages.addAndGet(operation.queueEntryIds.size());
            return interceptorChain.proceed();
        }
    }
}
