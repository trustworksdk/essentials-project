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
import dk.trustworks.essentials.components.foundation.messaging.queue.operations.ConsumeFromQueue;
import dk.trustworks.essentials.components.foundation.test.EssentialsTestContainers;
import dk.trustworks.essentials.components.foundation.transaction.jdbi.JdbiUnitOfWorkFactory;
import org.awaitility.Awaitility;
import org.jdbi.v3.core.Jdbi;
import org.junit.jupiter.api.*;
import org.testcontainers.junit.jupiter.*;
import org.testcontainers.postgresql.PostgreSQLContainer;

import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * A handler that runs longer than {@code messageHandlingTimeout} on a live instance: is its message (or, for an
 * {@link OrderedMessage}, its key) ever in two handlers at once?
 * <p>
 * Recovery of a claimed message is time-based - the stuck-message reset takes back any claim whose {@code delivery_ts}
 * is older than the timeout - and nothing refreshes {@code delivery_ts} while the handler runs.
 */
@Testcontainers
class PostgresqlDurableQueuesSlowHandlerIT {
    private static final String    QUEUE_TABLE      = "slow_handler_queue";
    private static final QueueName QUEUE            = QueueName.of("SlowHandlerQueue");
    private static final Duration  HANDLING_TIMEOUT = Duration.ofSeconds(1);
    /**
     * How long each handler blocks: three timeouts
     */
    private static final Duration  HANDLER_DURATION = HANDLING_TIMEOUT.multipliedBy(3);

    @Container
    private static final PostgreSQLContainer postgreSQLContainer = EssentialsTestContainers.postgres("slow-handler-db");

    private final List<PostgresqlDurableQueues> instances = new ArrayList<>();
    private final List<DurableQueueConsumer>    consumers = new ArrayList<>();
    private final CountDownLatch                release   = new CountDownLatch(1);

    private final ConcurrentHashMap<String, AtomicInteger> inFlightPerMessageId = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, AtomicInteger> inFlightPerKey       = new ConcurrentHashMap<>();
    private final AtomicInteger                            maxPerMessageId      = new AtomicInteger();
    private final AtomicInteger                            maxPerKey            = new AtomicInteger();
    private final List<String>                             deliveries           = new CopyOnWriteArrayList<>();

    private JdbiUnitOfWorkFactory unitOfWorkFactory;

    @BeforeEach
    void setUp() {
        unitOfWorkFactory = new JdbiUnitOfWorkFactory(Jdbi.create(postgreSQLContainer.getJdbcUrl(),
                                                                   postgreSQLContainer.getUsername(),
                                                                   postgreSQLContainer.getPassword()));
    }

    @AfterEach
    void tearDown() {
        release.countDown();
        consumers.forEach(DurableQueueConsumer::cancel);
        instances.forEach(PostgresqlDurableQueues::stop);
        unitOfWorkFactory.usingUnitOfWork(uow -> uow.handle().execute("DROP TABLE IF EXISTS " + QUEUE_TABLE));
    }

    @Test
    void an_unordered_message_is_not_delivered_twice_at_once_with_the_centralized_fetcher() {
        var queues = startInstance(true);
        consumers.add(consume(queues, 2));
        unitOfWorkFactory.usingUnitOfWork(() -> queues.queueMessage(QUEUE, Message.of("slow")));

        assertNoConcurrentDelivery(maxPerMessageId, "the same message");
    }

    @Test
    void an_unordered_message_is_not_delivered_twice_at_once_with_the_traditional_consumer() {
        var queues = startInstance(false);
        consumers.add(consume(queues, 2));
        unitOfWorkFactory.usingUnitOfWork(() -> queues.queueMessage(QUEUE, Message.of("slow")));

        assertNoConcurrentDelivery(maxPerMessageId, "the same message");
    }

    @Test
    void two_messages_of_one_key_are_not_handled_at_once_across_two_instances() {
        // Two instances on one table: a single centralized fetcher keeps an in-flight key out of its own claims, so the
        // second delivery can only come from another instance
        var first  = startInstance(true);
        var second = startInstance(true);
        consumers.add(consume(first, 2));
        consumers.add(consume(second, 2));
        unitOfWorkFactory.usingUnitOfWork(() -> {
            first.queueMessage(QUEUE, OrderedMessage.of("slow-0", "key-1", 0));
            first.queueMessage(QUEUE, OrderedMessage.of("slow-1", "key-1", 1));
        });

        assertNoConcurrentDelivery(maxPerKey, "the same key");
    }

    private void assertNoConcurrentDelivery(AtomicInteger max, String what) {
        Awaitility.waitAtMost(Duration.ofSeconds(5))
                  .pollInterval(Duration.ofMillis(10))
                  .until(() -> !deliveries.isEmpty());
        var started = Instant.now();
        try {
            // Long enough for at least two stuck-message reset checks past the first claim's timeout
            Awaitility.await()
                      .during(HANDLER_DURATION.minusMillis(200))
                      .atMost(HANDLER_DURATION)
                      .pollInterval(Duration.ofMillis(10))
                      .until(() -> max.get() <= 1);
        } catch (org.awaitility.core.ConditionTimeoutException e) {
            // fall through to the assertion, which carries the evidence
        }
        System.out.printf("%n=== Deliveries (checked for %d ms) ===%n%s%n%n", Duration.between(started, Instant.now()).toMillis(), String.join("\n", deliveries));
        assertThat(max.get())
                .as("%s in two handlers at once, messageHandlingTimeout %s ms, handler %s ms. Deliveries:%n%s",
                    what, HANDLING_TIMEOUT.toMillis(), HANDLER_DURATION.toMillis(), String.join("\n", deliveries))
                .isLessThanOrEqualTo(1);
    }

    private PostgresqlDurableQueues startInstance(boolean centralized) {
        var queues = PostgresqlDurableQueues.builder()
                                            .setUnitOfWorkFactory(unitOfWorkFactory)
                                            .setJsonSerializer(EssentialsObjectMappers.createJSONSerializer())
                                            .setSharedQueueTableName(QUEUE_TABLE)
                                            .setMessageHandlingTimeout(HANDLING_TIMEOUT)
                                            .setUseCentralizedMessageFetcher(centralized)
                                            .setCentralizedMessageFetcherPollingInterval(Duration.ofMillis(20))
                                            .build();
        instances.add(queues);
        queues.start();
        return queues;
    }

    private DurableQueueConsumer consume(PostgresqlDurableQueues queues, int parallelConsumers) {
        var instance = instances.indexOf(queues);
        return queues.consumeFromQueue(ConsumeFromQueue.builder()
                                                       .setQueueName(QUEUE)
                                                       .setRedeliveryPolicy(RedeliveryPolicy.fixedBackoff(Duration.ofMillis(100), 3))
                                                       .setParallelConsumers(parallelConsumers)
                                                       .setPollingInterval(Duration.ofMillis(20))
                                                       .setQueueMessageHandler(queued -> handleSlowly(instance, queued))
                                                       .build());
    }

    private void handleSlowly(int instance, QueuedMessage queued) {
        var id  = queued.getId().toString();
        var key = queued.getMessage() instanceof OrderedMessage ordered ? ordered.getKey() : id;
        var nowInFlightForId  = inFlightPerMessageId.computeIfAbsent(id, k -> new AtomicInteger()).incrementAndGet();
        var nowInFlightForKey = inFlightPerKey.computeIfAbsent(key, k -> new AtomicInteger()).incrementAndGet();
        maxPerMessageId.accumulateAndGet(nowInFlightForId, Math::max);
        maxPerKey.accumulateAndGet(nowInFlightForKey, Math::max);
        deliveries.add(String.format("%s instance=%d thread=%s id=%s key=%s payload=%s redeliveryAttempts=%d deliveryTs=%s inFlightForId=%d inFlightForKey=%d",
                                     Instant.now(), instance, Thread.currentThread().getName(), id, key, queued.getPayload(),
                                     queued.getRedeliveryAttempts(), queued.getDeliveryTimestamp(), nowInFlightForId, nowInFlightForKey));
        try {
            release.await(HANDLER_DURATION.toMillis(), TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } finally {
            inFlightPerMessageId.get(id).decrementAndGet();
            inFlightPerKey.get(key).decrementAndGet();
        }
    }
}
