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
import org.jdbi.v3.core.statement.*;
import org.junit.jupiter.api.*;
import org.testcontainers.junit.jupiter.*;
import org.testcontainers.postgresql.PostgreSQLContainer;

import java.time.Duration;
import java.util.*;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Polling with a long backoff cap: a message that becomes due is delivered when it is due, not up to a whole backoff
 * later, and a poll claims ordered and unordered messages in one statement.
 */
@Testcontainers
class PostgresqlDurableQueuesDueTimeIT {
    private static final String    QUEUE_TABLE   = "due_time_queue";
    private static final QueueName QUEUE         = QueueName.of("DueTimeQueue");
    private static final Duration  DELAY         = Duration.ofSeconds(6);
    /**
     * A poll every 20 ms, so a message due when the backoff has been bypassed is picked up within a few of them
     */
    private static final Duration  ALLOWED_DELAY = Duration.ofMillis(400);

    @Container
    private static final PostgreSQLContainer postgreSQLContainer = EssentialsTestContainers.postgres("due-time-db");

    private Jdbi                    jdbi;
    private JdbiUnitOfWorkFactory   unitOfWorkFactory;
    private PostgresqlDurableQueues durableQueues;
    private DurableQueueConsumer    consumer;

    @BeforeEach
    void setUp() {
        jdbi = Jdbi.create(postgreSQLContainer.getJdbcUrl(), postgreSQLContainer.getUsername(), postgreSQLContainer.getPassword());
        unitOfWorkFactory = new JdbiUnitOfWorkFactory(jdbi);
        durableQueues = PostgresqlDurableQueues.builder()
                                               .setUnitOfWorkFactory(unitOfWorkFactory)
                                               .setJsonSerializer(EssentialsObjectMappers.createJSONSerializer())
                                               .setSharedQueueTableName(QUEUE_TABLE)
                                               // A cap far longer than the delays: a due message that waited out
                                               // the backoff would be seconds late
                                               .setCentralizedQueuePollingOptimizerFactory(queueName -> new CentralizedQueuePollingOptimizer(queueName, 50, 10_000, 1.5, 0.1))
                                               .build();
        durableQueues.start();
        durableQueues.purgeQueue(QUEUE);
    }

    @AfterEach
    void tearDown() {
        if (consumer != null) {
            consumer.cancel();
        }
        durableQueues.stop();
    }

    @Test
    void a_retry_is_delivered_when_it_is_due_on_a_queue_that_has_backed_off() {
        var deliveredAt = new CopyOnWriteArrayList<Long>();
        consumer = consume(RedeliveryPolicy.fixedBackoff(DELAY, 3), message -> {
            deliveredAt.add(System.nanoTime());
            if (deliveredAt.size() == 1) {
                throw new IllegalStateException("retry me");
            }
        });
        unitOfWorkFactory.usingUnitOfWork(() -> durableQueues.queueMessage(QUEUE, Message.of("payload")));

        Awaitility.waitAtMost(DELAY.multipliedBy(3)).until(() -> deliveredAt.size() == 2);
        var late = Duration.ofNanos(deliveredAt.get(1) - deliveredAt.get(0)).minus(DELAY);
        System.out.printf("%n=== Retry due after %d ms delivered %d ms late ===%n%n", DELAY.toMillis(), late.toMillis());
        assertThat(late).isLessThan(ALLOWED_DELAY);
    }

    @Test
    void a_delayed_message_is_delivered_when_it_is_due_on_a_queue_that_has_backed_off() {
        var deliveredAt = new CopyOnWriteArrayList<Long>();
        consumer = consume(RedeliveryPolicy.fixedBackoff(Duration.ofMillis(100), 3), message -> deliveredAt.add(System.nanoTime()));

        var queuedAt = System.nanoTime();
        unitOfWorkFactory.usingUnitOfWork(() -> durableQueues.queueMessage(QUEUE, Message.of("payload"), DELAY));

        Awaitility.waitAtMost(DELAY.multipliedBy(3)).until(() -> deliveredAt.size() == 1);
        var late = Duration.ofNanos(deliveredAt.getFirst() - queuedAt).minus(DELAY);
        System.out.printf("%n=== Message delayed %d ms delivered %d ms late ===%n%n", DELAY.toMillis(), late.toMillis());
        assertThat(late).isLessThan(ALLOWED_DELAY);
    }

    @Test
    void a_poll_claims_ordered_and_unordered_messages_in_one_statement() {
        var claimStatements = new AtomicInteger();
        jdbi.setSqlLogger(new SqlLogger() {
            @Override
            public void logBeforeExecution(StatementContext context) {
                var sql = context.getRenderedSql();
                if (sql != null && (sql.contains("cte_ordered") || sql.contains("cte_unordered"))) {
                    claimStatements.incrementAndGet();
                }
            }
        });
        // Stopped, so the consumer is registered but nothing polls: the test drives the fetch itself
        durableQueues.stop();
        consumer = consume(RedeliveryPolicy.fixedBackoff(Duration.ofMillis(100), 3), message -> { });
        unitOfWorkFactory.usingUnitOfWork(() -> {
            durableQueues.queueMessage(QUEUE, OrderedMessage.of("ordered", "key-1", 0));
            durableQueues.queueMessage(QUEUE, Message.of("unordered"));
        });
        claimStatements.set(0);

        var fetched = durableQueues.fetchNextBatchOfMessages(List.of(QUEUE), Map.of(QUEUE, Set.of()), Map.of(QUEUE, 4));

        assertThat(fetched).hasSize(2);
        assertThat(fetched.getFirst().getMessage()).as("ordered messages first").isInstanceOf(OrderedMessage.class);
        assertThat(claimStatements.get()).isEqualTo(1);
    }

    private DurableQueueConsumer consume(RedeliveryPolicy redeliveryPolicy, QueuedMessageHandler handler) {
        return durableQueues.consumeFromQueue(ConsumeFromQueue.builder()
                                                              .setQueueName(QUEUE)
                                                              .setRedeliveryPolicy(redeliveryPolicy)
                                                              .setParallelConsumers(1)
                                                              .setQueueMessageHandler(handler)
                                                              .build());
    }
}
