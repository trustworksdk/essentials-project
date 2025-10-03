/*
 * Copyright 2021-2025 the original author or authors.
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


import com.fasterxml.jackson.datatype.jdk8.Jdk8Module;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import dk.trustworks.essentials.components.foundation.json.JacksonJSONSerializer;
import dk.trustworks.essentials.components.foundation.messaging.RedeliveryPolicy;
import dk.trustworks.essentials.components.foundation.messaging.queue.*;
import dk.trustworks.essentials.components.foundation.messaging.queue.operations.ConsumeFromQueue;
import dk.trustworks.essentials.components.foundation.postgresql.MultiTableChangeListener;
import dk.trustworks.essentials.components.foundation.test.messaging.queue.DurableQueuesLoadIT;
import dk.trustworks.essentials.components.foundation.transaction.jdbi.*;
import dk.trustworks.essentials.components.queue.mssql.MsSqlDurableQueues;
import dk.trustworks.essentials.components.queue.postgresql.test_data.TestMessageFactory;
import dk.trustworks.essentials.jackson.types.EssentialTypesJacksonModule;
import dk.trustworks.essentials.reactive.LocalEventBus;
import dk.trustworks.essentials.shared.time.StopWatch;
import org.jdbi.v3.core.Jdbi;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.MSSQLServerContainer;
import org.testcontainers.junit.jupiter.*;

import java.time.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicLong;

import static dk.trustworks.essentials.jackson.immutable.EssentialsImmutableJacksonModule.createObjectMapper;
import static dk.trustworks.essentials.shared.MessageFormatter.msg;
import static dk.trustworks.essentials.shared.collections.Lists.partition;
import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.waitAtMost;

@Testcontainers
public abstract class MsSqlDurableQueuesPerformanceIT extends DurableQueuesLoadIT<MsSqlDurableQueues, GenericHandleAwareUnitOfWorkFactory.GenericHandleAwareUnitOfWork, JdbiUnitOfWorkFactory> {

    public static final int TOTAL_MESSAGES = 100000;
    public static final int BATCH_SIZE     = 500;

    @Container
    static MSSQLServerContainer<?> msSQLContainer = new MSSQLServerContainer<>("mcr.microsoft.com/mssql/server:2022-latest")
            .acceptLicense();

    /**
     * Determine whether to use the centralized message fetcher
     *
     * @return true for centralized message fetcher, false for traditional consumer
     */
    protected abstract boolean useCentralizedMessageFetcher();

    /**
     * Determine whether to use the centralized message fetcher
     *
     * @return true for centralized message fetcher, false for traditional consumer
     */
    protected abstract boolean useOrderedUnorderedQuery();

    protected abstract long totalMessagesConsumedTarget();

    protected abstract Duration consumerPollInterval();

    protected abstract Duration timeToWait();

    protected abstract boolean logMessagesReceivedDuringProcessing();

    @Override
    protected MsSqlDurableQueues createDurableQueues(JdbiUnitOfWorkFactory unitOfWorkFactory) {
        return MsSqlDurableQueues.builder()
                                      .setUnitOfWorkFactory(unitOfWorkFactory)
                                      .setQueuePollingOptimizerFactory(consumeFromQueue -> new SimpleQueuePollingOptimizer(consumeFromQueue, 100, 1000))
                                      .setMultiTableChangeListener(new MultiTableChangeListener<>(unitOfWorkFactory.getJdbi(),
                                                                                                  Duration.ofMillis(100),
                                                                                                  new JacksonJSONSerializer(
                                                                                                          createObjectMapper(
                                                                                                                  new Jdk8Module(),
                                                                                                                  new JavaTimeModule(),
                                                                                                                  new EssentialTypesJacksonModule())
                                                                                                  ),
                                                                                                  LocalEventBus.builder().build(),
                                                                                                  true))
                                      .setUseCentralizedMessageFetcher(useCentralizedMessageFetcher())
                                      .setCentralizedMessageFetcherPollingInterval(consumerPollInterval())
                                      .setUseOrderedUnorderedQuery(useOrderedUnorderedQuery())
                                      .build();
    }

    @Override
    protected JdbiUnitOfWorkFactory createUnitOfWorkFactory() {
        return new JdbiUnitOfWorkFactory(Jdbi.create(msSQLContainer.getJdbcUrl(),
                                                     msSQLContainer.getUsername(),
                                                     msSQLContainer.getPassword()));
    }

    @Override
    protected void resetQueueStorage(JdbiUnitOfWorkFactory unitOfWorkFactory) {
        unitOfWorkFactory.usingUnitOfWork(uow -> uow.handle().execute("DROP TABLE IF EXISTS " + MsSqlDurableQueues.DEFAULT_DURABLE_QUEUES_TABLE_NAME));
    }

    @Test
    void queue_a_large_number_of_unordered_messages() {
        // Given
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

        var stopWatch           = StopWatch.start();
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