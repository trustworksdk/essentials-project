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
import dk.trustworks.essentials.components.foundation.types.CorrelationId;
import dk.trustworks.essentials.jackson.types.EssentialTypesJacksonModule;
import dk.trustworks.essentials.reactive.LocalEventBus;
import dk.trustworks.essentials.shared.time.StopWatch;
import org.jdbi.v3.core.Jdbi;
import org.junit.jupiter.api.*;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.*;

import java.time.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.*;

import static dk.trustworks.essentials.jackson.immutable.EssentialsImmutableJacksonModule.createObjectMapper;
import static dk.trustworks.essentials.shared.MessageFormatter.msg;
import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.waitAtMost;

@Testcontainers
public abstract class PostgresqlDurableQueuesPerformanceIT extends DurableQueuesLoadIT<PostgresqlDurableQueues, GenericHandleAwareUnitOfWorkFactory.GenericHandleAwareUnitOfWork, JdbiUnitOfWorkFactory> {

    public static final int TOTAL_MESSAGES = 100000;
    public static final int BATCH_SIZE     = 500;

    @Container
    static PostgreSQLContainer<?> postgreSQLContainer = new PostgreSQLContainer<>("postgres:latest")
            .withDatabaseName("test")
            .withPassword("test")
            .withUsername("test");

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
    protected PostgresqlDurableQueues createDurableQueues(JdbiUnitOfWorkFactory unitOfWorkFactory) {
        return PostgresqlDurableQueues.builder()
                                      .setUnitOfWorkFactory(unitOfWorkFactory)
                                      .setQueuePollingOptimizerFactory(consumeFromQueue -> new QueuePollingOptimizer.SimpleQueuePollingOptimizer(consumeFromQueue, 100, 1000))
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
        return new JdbiUnitOfWorkFactory(Jdbi.create(postgreSQLContainer.getJdbcUrl(),
                                                     postgreSQLContainer.getUsername(),
                                                     postgreSQLContainer.getPassword()));
    }

    @Override
    protected void resetQueueStorage(JdbiUnitOfWorkFactory unitOfWorkFactory) {
        unitOfWorkFactory.usingUnitOfWork(uow -> uow.handle().execute("DROP TABLE IF EXISTS " + PostgresqlDurableQueues.DEFAULT_DURABLE_QUEUES_TABLE_NAME));
    }

    @Test
    void queue_a_large_number_of_unordered_messages() {
        // Given
        var queueName = QueueName.of("TestQueue");
        var now       = Instant.now();

        var stopwatch = StopWatch.start();

        var totalMessagesQueued = new AtomicLong();
        for (var batch = 0; batch < TOTAL_MESSAGES / BATCH_SIZE; batch++) {
            var batchStart = batch * BATCH_SIZE;
            var batchEnd   = (batch + 1) * BATCH_SIZE;
            System.out.println("batchStart: [" + batchStart + ", batchEnd: " + batchEnd + "[");
            unitOfWorkFactory.usingUnitOfWork(uow -> {
                var messages = IntStream.range(batchStart, batchEnd)
                                        .mapToObj(i -> Message.of(("Message" + i), MessageMetaData.of("correlation_id", CorrelationId.random(), "trace_id", UUID.randomUUID().toString())))
                                        .collect(Collectors.toList());
                var queueEntryIds = durableQueues.queueMessages(queueName,
                                                                messages);
                System.out.println("TotalMessagesQueued: " + totalMessagesQueued.addAndGet(messages.size()));
                assertThat(queueEntryIds).hasSize(messages.size());
            });
        }
        System.out.println(msg("-----> {} Queueing {} messages took {}", Instant.now(), TOTAL_MESSAGES, stopwatch.stop()));

        assertThat(durableQueues.getTotalMessagesQueuedFor(queueName)).isEqualTo(TOTAL_MESSAGES);
        var nextMessages = durableQueues.queryForMessagesSoonReadyForDelivery(queueName,
                                                                              now,
                                                                              10);
        assertThat(nextMessages).hasSize(10);

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

        List<String> keys = IntStream.range(0, 1000)
                                     .mapToObj(i -> "Key" + i)
                                     .toList();

        var stopWatch           = StopWatch.start();
        var totalMessagesQueued = new AtomicLong();
        int keyCount            = keys.size();
        for (int batch = 0; batch < TOTAL_MESSAGES / BATCH_SIZE; batch++) {
            int   start         = batch * BATCH_SIZE;
            int   end           = start + BATCH_SIZE;
            int[] orderCounters = new int[keyCount];
            unitOfWorkFactory.usingUnitOfWork(uow -> {
                List<OrderedMessage> messages = new ArrayList<>(BATCH_SIZE);
                for (int i = start; i < end; i++) {
                    int keyIdx = i % keyCount;
                    messages.add(OrderedMessage.of(
                            "Message" + i,
                            keys.get(keyIdx),
                            orderCounters[keyIdx]++,
                            MessageMetaData.of("correlation_id", CorrelationId.random(),
                                               "trace_id", UUID.randomUUID().toString())
                                                  ));
                }
                var ids = durableQueues.queueMessages(queueName, messages);
                assertThat(ids).hasSize(messages.size());
                totalMessagesQueued.addAndGet(messages.size());
            });
        }
        System.out.println("Enqueued " + totalMessagesQueued.get() +
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

        var stopWatch           = StopWatch.start();
        var totalMessagesQueued = new AtomicLong();
        for (int batch = 0; batch < TOTAL_MESSAGES / BATCH_SIZE; batch++) {
            int start = batch * BATCH_SIZE;
            int end   = start + BATCH_SIZE;
            unitOfWorkFactory.usingUnitOfWork(uow -> {
                List<Message> messages = new ArrayList<>(BATCH_SIZE);
                for (int i = start; i < end; i++) {
                    if (i % 2 == 0) {
                        // unordered
                        messages.add(Message.of(
                                "Msg" + i,
                                MessageMetaData.of("correlation_id", CorrelationId.random(),
                                                   "trace_id", UUID.randomUUID().toString())
                                               ));
                    } else {
                        // ordered, round‐robin 100 keys
                        String key = "Key" + (i % 100);
                        messages.add(OrderedMessage.of(
                                "Msg" + i,
                                key,
                                i / 2,  // simple per-key increment
                                MessageMetaData.of("correlation_id", CorrelationId.random(),
                                                   "trace_id", UUID.randomUUID().toString())
                                                      ));
                    }
                }
                var ids = durableQueues.queueMessages(queueName, messages);
                assertThat(ids).hasSize(messages.size());
                totalMessagesQueued.addAndGet(messages.size());
            });
        }
        System.out.println("Enqueued " + totalMessagesQueued.get() +
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