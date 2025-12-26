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

package dk.trustworks.essentials.components.foundation.test.messaging.queue;

import dk.trustworks.essentials.components.foundation.messaging.RedeliveryPolicy;
import dk.trustworks.essentials.components.foundation.messaging.queue.*;
import dk.trustworks.essentials.components.foundation.test.messaging.queue.test_data.*;
import dk.trustworks.essentials.components.foundation.transaction.*;
import dk.trustworks.essentials.components.foundation.types.CorrelationId;
import org.awaitility.Awaitility;
import org.junit.jupiter.api.*;
import org.slf4j.*;

import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.*;

/**
 * Integration test to verify that DurableQueues messages are NOT consumed multiple times
 * when multiple competing consumers (pods) are used.
 * <p>
 * This test specifically targets Bug #19: DurableQueues messages are consumed multiple times
 * when multiple pods are used.
 * <p>
 * Key differences from {@link DistributedCompetingConsumersDurableQueuesIT}:
 * <ul>
 *     <li>Tracks consumption count per {@link QueueEntryId} (not just distinct messages)</li>
 *     <li>Records each consumption with the QueueEntryId</li>
 *     <li>Adds processing delay to increase race condition window</li>
 *     <li>Asserts that no message ID is consumed more than once</li>
 * </ul>
 *
 * @param <DURABLE_QUEUES> the DurableQueues implementation type
 * @param <UOW>            the UnitOfWork type
 * @param <UOW_FACTORY>    the UnitOfWorkFactory type
 */
public abstract class DuplicateConsumptionDurableQueuesIT<DURABLE_QUEUES extends DurableQueues, UOW extends UnitOfWork, UOW_FACTORY extends UnitOfWorkFactory<UOW>> {

    private static final Logger log = LoggerFactory.getLogger(DuplicateConsumptionDurableQueuesIT.class);

    /**
     * Number of messages to queue for testing.
     * More messages = more opportunities for the race condition to trigger.
     * <p>
     * Test duration estimate: 40 messages / 2 consumers × 3000ms = ~60 seconds + overhead
     */
    public static final int NUMBER_OF_MESSAGES = 40;

    /**
     * Delay in milliseconds between starting the two consumers.
     * A longer delay gives Instance1 more time to over-fetch and queue messages
     * before Instance2 starts competing, increasing the chance of triggering
     * the duplicate consumption race condition.
     */
    public static final long CONSUMER_START_DELAY_MS = 200;

    /**
     * Number of parallel consumers per DurableQueues consumer instance
     */
    public static final int PARALLEL_CONSUMERS = 1;

    /**
     * Processing delay in milliseconds to increase race condition window.
     * This must be long enough that messages pile up and sit in the worker queue
     * longer than messageHandlingTimeout, triggering the stuck message reset.
     * <p>
     * For Bug #19 reproduction, this should be MUCH LONGER than the message handling timeout
     * to ensure queued messages are reset multiple times before they can start processing.
     */
    public static final long PROCESSING_DELAY_MS = 3000;

    /**
     * Default message handling timeout in milliseconds.
     * <p>
     * This value is deliberately short (much less than {@link #PROCESSING_DELAY_MS}) to
     * reproduce the duplicate consumption issue described in Bug #19. When messages queue
     * up in the worker pool and wait longer than this timeout, they are reset as "stuck"
     * even though they haven't started processing yet. Another instance can then fetch
     * the reset message, potentially causing duplicate consumption.
     * <p>
     * With a 50ms timeout and 3000ms processing delay, queued messages will be reset
     * multiple times before they can start processing.
     */
    public static final long DEFAULT_MESSAGE_HANDLING_TIMEOUT_MS = 50;

    // Separate unit of work factories to simulate separate pods with separate connection pools
    private UOW_FACTORY                                unitOfWorkFactory1;
    private UOW_FACTORY                                unitOfWorkFactory2;
    private DURABLE_QUEUES                             durableQueues1;
    private DURABLE_QUEUES                             durableQueues2;
    private ConcurrentMap<QueueEntryId, AtomicInteger> consumptionCounts;
    private AtomicInteger                              instance1Count;
    private AtomicInteger                              instance2Count;

    @BeforeEach
    void setup() {
        // Create separate unit of work factories to simulate separate pods
        // This is critical for reproducing the duplicate consumption bug,
        // as each pod in production has its own connection pool
        unitOfWorkFactory1 = createUnitOfWorkFactory();
        unitOfWorkFactory2 = createUnitOfWorkFactory();

        resetQueueStorage(unitOfWorkFactory1);

        durableQueues1 = createDurableQueues(unitOfWorkFactory1);
        durableQueues1.start();

        durableQueues2 = createDurableQueues(unitOfWorkFactory2);
        durableQueues2.start();

        consumptionCounts = new ConcurrentHashMap<>();
        instance1Count = new AtomicInteger(0);
        instance2Count = new AtomicInteger(0);
    }

    @AfterEach
    void cleanup() {
        if (durableQueues1 != null) {
            durableQueues1.stop();
        }
        if (durableQueues2 != null) {
            durableQueues2.stop();
        }
    }

    /**
     * Creates a new DurableQueues instance for testing.
     *
     * @param unitOfWorkFactory the UnitOfWorkFactory to use
     * @return a new DurableQueues instance
     */
    protected abstract DURABLE_QUEUES createDurableQueues(UOW_FACTORY unitOfWorkFactory);

    /**
     * Creates the UnitOfWorkFactory for testing.
     *
     * @return the UnitOfWorkFactory
     */
    protected abstract UOW_FACTORY createUnitOfWorkFactory();

    /**
     * Resets the queue storage (drops/clears the queue table/collection).
     *
     * @param unitOfWorkFactory the UnitOfWorkFactory to use
     */
    protected abstract void resetQueueStorage(UOW_FACTORY unitOfWorkFactory);

    /**
     * Disrupts the database connection (e.g., by pausing the container).
     */
    protected abstract void disruptDatabaseConnection();

    /**
     * Restores the database connection (e.g., by unpausing the container).
     */
    protected abstract void restoreDatabaseConnection();

    /**
     * Returns the message handling timeout in milliseconds.
     * <p>
     * Default is {@link #DEFAULT_MESSAGE_HANDLING_TIMEOUT_MS}, which is deliberately
     * shorter than {@link #PROCESSING_DELAY_MS} to reproduce the duplicate consumption
     * issue described in Bug #19.
     * <p>
     * When the timeout is shorter than the processing delay, messages queued
     * in the worker pool (but not yet executing) will be reset as "stuck"
     * before they can start processing, allowing another instance to fetch them.
     * <p>
     * Override this method if a specific implementation needs a different timeout.
     *
     * @return the message handling timeout in milliseconds
     */
    protected long getMessageHandlingTimeoutMs() {
        return DEFAULT_MESSAGE_HANDLING_TIMEOUT_MS;
    }

    /**
     * Helper method to execute actions within a UnitOfWork if required by the transactional mode.
     */
    protected void usingDurableQueue(Runnable action) {
        if (durableQueues1.getTransactionalMode() == TransactionalMode.FullyTransactional) {
            unitOfWorkFactory1.usingUnitOfWork(uow -> action.run());
        } else {
            action.run();
        }
    }

    @Test
    void verify_no_duplicate_message_consumption() throws InterruptedException {
        test_no_duplicate_consumption(false);
    }

    @Test
    void verify_no_duplicate_message_consumption_with_db_connectivity_issues() throws InterruptedException {
        test_no_duplicate_consumption(true);
    }

    private void test_no_duplicate_consumption(boolean simulateDbConnectivityIssues) throws InterruptedException {
        // Given
        var random    = new Random();
        var queueName = QueueName.of("DuplicateConsumptionTestQueue");
        durableQueues1.purgeQueue(queueName);

        var messages = new ArrayList<Message>(NUMBER_OF_MESSAGES);

        for (var i = 0; i < NUMBER_OF_MESSAGES; i++) {
            Message message;
            if (i % 2 == 0) {
                message = Message.of(new OrderEvent.OrderAdded(OrderId.random(), CustomerId.random(), random.nextInt()),
                                     MessageMetaData.of("correlation_id", CorrelationId.random(),
                                                        "trace_id", UUID.randomUUID().toString()));
            } else if (i % 3 == 0) {
                message = Message.of(new OrderEvent.ProductAddedToOrder(OrderId.random(), ProductId.random(), random.nextInt()),
                                     MessageMetaData.of("correlation_id", CorrelationId.random(),
                                                        "trace_id", UUID.randomUUID().toString()));
            } else {
                message = Message.of(new OrderEvent.OrderAccepted(OrderId.random()),
                                     MessageMetaData.of("correlation_id", CorrelationId.random(),
                                                        "trace_id", UUID.randomUUID().toString()));
            }
            messages.add(message);
        }

        usingDurableQueue(() -> durableQueues1.queueMessages(queueName, messages));

        assertThat(durableQueues1.getTotalMessagesQueuedFor(queueName)).isEqualTo(NUMBER_OF_MESSAGES);
        assertThat(durableQueues2.getTotalMessagesQueuedFor(queueName)).isEqualTo(NUMBER_OF_MESSAGES);

        var handler1 = new ConsumptionTrackingHandler(consumptionCounts, instance1Count, "instance1");
        var handler2 = new ConsumptionTrackingHandler(consumptionCounts, instance2Count, "instance2");

        // When
        var consumer1 = durableQueues1.consumeFromQueue(queueName,
                                                        RedeliveryPolicy.fixedBackoff(Duration.ofMillis(1), 5),
                                                        PARALLEL_CONSUMERS,
                                                        handler1);
        // Delay before starting the second consumer to give Instance1 time to over-fetch
        // and queue messages. This increases the chance that Instance2 will reset and
        // fetch messages that are still queued in Instance1's worker pool.
        Thread.sleep(CONSUMER_START_DELAY_MS);
        var consumer2 = durableQueues2.consumeFromQueue(queueName,
                                                        RedeliveryPolicy.fixedBackoff(Duration.ofMillis(1), 5),
                                                        PARALLEL_CONSUMERS,
                                                        handler2);

        if (simulateDbConnectivityIssues) {
            Thread.sleep(2000);
            log.info("***** Disrupting DB connection *****");
            disruptDatabaseConnection();
            Thread.sleep(5000);
            log.info("***** Restoring DB connection *****");
            restoreDatabaseConnection();
        }

        // Then - wait for all messages to be consumed
        Awaitility.waitAtMost(Duration.ofSeconds(120))
                  .pollInterval(Duration.ofMillis(500))
                  .untilAsserted(() -> {
                      var totalConsumed = consumptionCounts.size();
                      log.debug("Total unique messages consumed so far: {}", totalConsumed);
                      assertThat(totalConsumed).isEqualTo(NUMBER_OF_MESSAGES);
                  });

        // Verify that both consumers were allowed to consume messages
        assertThat(instance1Count.get())
                .as("Instance 1 should have consumed some messages")
                .isGreaterThan(0);
        assertThat(instance2Count.get())
                .as("Instance 2 should have consumed some messages")
                .isGreaterThan(0);

        log.info("Instance 1 consumed {} messages, Instance 2 consumed {} messages",
                 instance1Count.get(), instance2Count.get());

        // Verify no message was consumed more than once
        var duplicates = consumptionCounts.entrySet().stream()
                                          .filter(e -> e.getValue().get() > 1)
                                          .toList();

        if (!duplicates.isEmpty()) {
            var duplicateDetails = duplicates.stream()
                                             .map(e -> e.getKey() + " (count: " + e.getValue().get() + ")")
                                             .collect(Collectors.joining(", "));
            log.error("DUPLICATE CONSUMPTION DETECTED! Messages consumed multiple times: {}", duplicateDetails);
            fail("Messages were consumed multiple times: " + duplicateDetails);
        }

        // Verify no additional messages are delivered after completion
        Awaitility.await()
                  .during(Duration.ofMillis(1990))
                  .atMost(Duration.ofSeconds(2000))
                  .until(() -> consumptionCounts.size() == NUMBER_OF_MESSAGES);

        consumer1.cancel();
        consumer2.cancel();

        log.info("Test completed successfully. All {} messages consumed exactly once.", NUMBER_OF_MESSAGES);
    }

    /**
     * Message handler that tracks consumption count per QueueEntryId.
     */
    private static class ConsumptionTrackingHandler implements QueuedMessageHandler {
        private static final Logger log = LoggerFactory.getLogger(ConsumptionTrackingHandler.class);

        private final ConcurrentMap<QueueEntryId, AtomicInteger> consumptionCounts;
        private final AtomicInteger                              instanceCount;
        private final String                                     instanceName;

        ConsumptionTrackingHandler(ConcurrentMap<QueueEntryId, AtomicInteger> consumptionCounts,
                                   AtomicInteger instanceCount,
                                   String instanceName) {
            this.consumptionCounts = consumptionCounts;
            this.instanceCount = instanceCount;
            this.instanceName = instanceName;
        }

        @Override
        public void handle(QueuedMessage message) {
            var entryId  = message.getId();
            var count    = consumptionCounts.computeIfAbsent(entryId, k -> new AtomicInteger(0));
            var newCount = count.incrementAndGet();

            if (newCount > 1) {
                log.warn("[{}] ⚠️ DUPLICATE: Message {} consumed {} times!",
                         instanceName, entryId, newCount);
            } else {
                log.debug("[{}] Consumed message {}", instanceName, entryId);
            }

            instanceCount.incrementAndGet();

            // Add processing delay to increase the race condition window
            if (PROCESSING_DELAY_MS > 0) {
                try {
                    Thread.sleep(PROCESSING_DELAY_MS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }
        }
    }
}
