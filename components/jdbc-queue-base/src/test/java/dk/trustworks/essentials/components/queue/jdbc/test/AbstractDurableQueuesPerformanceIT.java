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

package dk.trustworks.essentials.components.queue.jdbc.test;

import dk.trustworks.essentials.components.foundation.messaging.RedeliveryPolicy;
import dk.trustworks.essentials.components.foundation.messaging.queue.*;
import dk.trustworks.essentials.components.foundation.messaging.queue.operations.ConsumeFromQueue;
import dk.trustworks.essentials.components.foundation.test.messaging.queue.DurableQueuesLoadIT;
import dk.trustworks.essentials.components.foundation.transaction.jdbi.*;
import dk.trustworks.essentials.components.queue.postgresql.test_data.TestMessageFactory;
import dk.trustworks.essentials.shared.time.StopWatch;
import org.junit.jupiter.api.Test;

import java.time.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicLong;

import static dk.trustworks.essentials.shared.MessageFormatter.msg;
import static dk.trustworks.essentials.shared.collections.Lists.partition;
import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.waitAtMost;

public abstract class AbstractDurableQueuesPerformanceIT<DURABLE_QUEUES extends DurableQueues>
        extends DurableQueuesLoadIT<DURABLE_QUEUES, GenericHandleAwareUnitOfWorkFactory.GenericHandleAwareUnitOfWork, JdbiUnitOfWorkFactory> {

    public static final int TOTAL_MESSAGES = 100000;
    public static final int BATCH_SIZE     = 500;

    protected abstract long totalMessagesConsumedTarget();

    protected abstract Duration consumerPollInterval();

    protected abstract Duration timeToWait();

    protected abstract boolean logMessagesReceivedDuringProcessing();

    @Test
    void queue_a_large_number_of_unordered_messages() {
        var queueName = QueueName.of("TestQueue");
        var stopwatch = StopWatch.start();

        Map<QueueName, List<Message>> unorderedMessages = TestMessageFactory.createUnorderedMessages(TOTAL_MESSAGES, List.of(queueName));
        unitOfWorkFactory.usingUnitOfWork(uow -> {
            List<Message> messages = unorderedMessages.get(queueName);
            for (List<Message> chunk : partition(messages, BATCH_SIZE)) {
                var ids = durableQueues.queueMessages(queueName, chunk);
                assertThat(ids).hasSize(chunk.size());
            }
        });
        System.out.println(msg("-----> {} Queueing {} messages took {}", Instant.now(), TOTAL_MESSAGES, stopwatch.stop()));

        assertThat(durableQueues.getTotalMessagesQueuedFor(queueName)).isEqualTo(TOTAL_MESSAGES);

        var handler = new RecordingQueuedMessageHandler();
        consumer = durableQueues.consumeFromQueue(ConsumeFromQueue.builder()
                                                                  .setQueueName(queueName)
                                                                  .setRedeliveryPolicy(RedeliveryPolicy.fixedBackoff(Duration.ofMillis(100), 0))
                                                                  .setParallelConsumers(1)
                                                                  .setConsumerName("TestConsumer")
                                                                  .setPollingInterval(consumerPollInterval())
                                                                  .setQueueMessageHandler(handler)
                                                                  .build());

        stopwatch = StopWatch.start();
        waitAtMost(timeToWait())
                .untilAsserted(() -> {
                    if (logMessagesReceivedDuringProcessing()) {
                        System.out.println("-----> " + Instant.now() + " messages received: " + handler.messagesReceived.get());
                    }
                    assertThat(handler.messagesReceived.get()).isGreaterThanOrEqualTo(totalMessagesConsumedTarget());
                });
        System.out.println("Processed all unordered messages '" + handler.messagesReceived.get() + "' in " + stopwatch.stop().duration.getSeconds() + " sec");
    }

    @Test
    void queue_a_large_number_of_ordered_messages() {
        QueueName queueName = QueueName.of("TestQueue");

        var                                  stopWatch       = StopWatch.start();
        Map<QueueName, List<OrderedMessage>> orderedMessages = TestMessageFactory.createOrderedMessages(TOTAL_MESSAGES, List.of(queueName), 75000);
        unitOfWorkFactory.usingUnitOfWork(uow -> {
            List<OrderedMessage> messages = orderedMessages.get(queueName);
            for (List<OrderedMessage> chunk : partition(messages, BATCH_SIZE)) {
                var ids = durableQueues.queueMessages(queueName, chunk);
                assertThat(ids).hasSize(chunk.size());
            }
        });
        System.out.println("Enqueued " + TOTAL_MESSAGES +
                                   " ordered messages in " + stopWatch.stop().duration.toMillis() + " ms");

        assertThat(durableQueues.getTotalMessagesQueuedFor(queueName))
                .isEqualTo(TOTAL_MESSAGES);

        var handler = new RecordingQueuedMessageHandler();
        consumer = durableQueues.consumeFromQueue(
                ConsumeFromQueue.builder()
                                .setQueueName(queueName)
                                .setRedeliveryPolicy(RedeliveryPolicy.fixedBackoff(Duration.ofMillis(100), 0))
                                .setParallelConsumers(1)
                                .setPollingInterval(consumerPollInterval())
                                .setQueueMessageHandler(handler)
                                .build()
                                                 );

        stopWatch = StopWatch.start();
        waitAtMost(timeToWait())
                .untilAsserted(() -> {
                    if (logMessagesReceivedDuringProcessing()) {
                        System.out.println("-----> " + Instant.now() + " messages received: " + handler.messagesReceived.get());
                    }
                    assertThat(handler.messagesReceived.get()).isGreaterThanOrEqualTo(totalMessagesConsumedTarget());
                });
        System.out.println("Processed all ordered messages '" + handler.messagesReceived.get() + "' in " + stopWatch.stop().duration.getSeconds() + " sec");
    }

    @Test
    void queue_a_large_number_of_mixed_messages() {
        QueueName queueName = QueueName.of("TestQueue");
        var queuesList = List.of(queueName);

        var stopWatch    = StopWatch.start();
        int half         = TOTAL_MESSAGES / 2;
        var unorderedMap = TestMessageFactory.createUnorderedMessages(half, queuesList);
        var orderedMap   = TestMessageFactory.createOrderedMessages(half, queuesList, 40000);

        unitOfWorkFactory.usingUnitOfWork(uow -> {
            var unOrderedMessages = unorderedMap.get(queueName);
            for (List<Message> chunk : partition(unOrderedMessages, BATCH_SIZE)) {
                var unOrderedIds = durableQueues.queueMessages(queueName, chunk);
                assertThat(unOrderedIds).hasSize(chunk.size());
            }

            var orderedMessages = orderedMap.get(queueName);
            for (List<OrderedMessage> chunk : partition(orderedMessages, BATCH_SIZE)) {
                var orderedIds = durableQueues.queueMessages(queueName, chunk);
                assertThat(orderedIds).hasSize(chunk.size());
            }
        });
        System.out.println("Enqueued " + TOTAL_MESSAGES +
                                   " mixed messages in " + stopWatch.stop().duration.toMillis() + " ms");

        assertThat(durableQueues.getTotalMessagesQueuedFor(queueName))
                .isEqualTo(TOTAL_MESSAGES);

        var handler = new RecordingQueuedMessageHandler();
        consumer = durableQueues.consumeFromQueue(
                ConsumeFromQueue.builder()
                                .setQueueName(queueName)
                                .setRedeliveryPolicy(RedeliveryPolicy.fixedBackoff(Duration.ofMillis(100), 0))
                                .setParallelConsumers(1)
                                .setPollingInterval(consumerPollInterval())
                                .setQueueMessageHandler(handler)
                                .build()
                                                 );

        stopWatch = StopWatch.start();
        waitAtMost(timeToWait())
                .untilAsserted(() -> {
                    if (logMessagesReceivedDuringProcessing()) {
                        System.out.println("-----> " + Instant.now() + " messages received: " + handler.messagesReceived.get());
                    }
                    assertThat(handler.messagesReceived.get()).isGreaterThanOrEqualTo(totalMessagesConsumedTarget());
                });
        System.out.println("Processed all mixed messages '" + handler.messagesReceived.get() + "' in " + stopWatch.stop().duration.getSeconds() + " sec");
    }

    static class RecordingQueuedMessageHandler implements QueuedMessageHandler {
        AtomicLong messagesReceived = new AtomicLong();

        @Override
        public void handle(QueuedMessage message) {
            messagesReceived.getAndIncrement();
        }
    }
}
