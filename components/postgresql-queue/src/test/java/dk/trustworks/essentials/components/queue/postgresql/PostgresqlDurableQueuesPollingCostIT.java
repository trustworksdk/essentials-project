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

import java.time.Duration;
import java.util.*;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Polling cost with the builder's default wiring, which matches the Spring Boot starter's.
 * <ul>
 *     <li>An idle queue backs off. A builder-made instance used to get no {@code MultiTableChangeListener} and so no
 *     polling optimizer, and was polled every interval forever, two claim statements a poll. Opting out of the
 *     listener still gives that.</li>
 *     <li>A message this node queues resets the backoff when its transaction commits, so a local producer does not
 *     wait out the backoff.</li>
 *     <li>A poll whose ordered claim finds fewer messages than there are free slots fills the rest with unordered
 *     messages, rather than leaving them for the next poll.</li>
 * </ul>
 */
@Testcontainers
class PostgresqlDurableQueuesPollingCostIT {
    private static final String    QUEUE_TABLE      = "polling_cost_queue";
    private static final QueueName QUEUE            = QueueName.of("PollingCostQueue");
    private static final Duration  POLLING_INTERVAL = Duration.ofMillis(20);

    @Container
    private static final PostgreSQLContainer postgreSQLContainer = EssentialsTestContainers.postgres("polling-cost-db");

    private JdbiUnitOfWorkFactory   unitOfWorkFactory;
    private PostgresqlDurableQueues durableQueues;
    private DurableQueueConsumer    consumer;

    @BeforeEach
    void setUp() {
        unitOfWorkFactory = new JdbiUnitOfWorkFactory(Jdbi.create(postgreSQLContainer.getJdbcUrl(),
                                                                   postgreSQLContainer.getUsername(),
                                                                   postgreSQLContainer.getPassword()));
        durableQueues = PostgresqlDurableQueues.builder()
                                               .setUnitOfWorkFactory(unitOfWorkFactory)
                                               .setJsonSerializer(EssentialsObjectMappers.createJSONSerializer())
                                               .setSharedQueueTableName(QUEUE_TABLE)
                                               .setCentralizedMessageFetcherPollingInterval(POLLING_INTERVAL)
                                               .build();
    }

    @AfterEach
    void tearDown() {
        if (consumer != null) {
            consumer.cancel();
        }
        durableQueues.stop();
    }

    @Test
    void an_idle_queue_backs_off() throws Exception {
        durableQueues.start();
        consumer = consumeWith(1, message -> { });
        // Let the backoff reach its cap, then measure
        Thread.sleep(1_500);
        var scansBefore = queueTableScans();
        var measuredFor = Duration.ofSeconds(3);
        Thread.sleep(measuredFor.toMillis());
        var scans = queueTableScans() - scansBefore;

        var pollsWithoutBackoff = measuredFor.toMillis() / POLLING_INTERVAL.toMillis();
        System.out.printf("%n=== Idle queue, %d ms polling: %d queue-table scans in %d ms (without backoff: %d polls, 2 claims each) ===%n%n",
                          POLLING_INTERVAL.toMillis(), scans, measuredFor.toMillis(), pollsWithoutBackoff);
        assertThat(scans)
                .as("an idle queue polled every %s ms runs at least %s claim statements in %s", POLLING_INTERVAL.toMillis(), pollsWithoutBackoff * 2, measuredFor)
                .isLessThan(pollsWithoutBackoff / 2);
    }

    @Test
    void without_a_listener_an_idle_queue_is_polled_every_interval() throws Exception {
        // Nothing could wake a backed-off queue, so it must not back off
        durableQueues.stop();
        durableQueues = PostgresqlDurableQueues.builder()
                                               .setUnitOfWorkFactory(unitOfWorkFactory)
                                               .setJsonSerializer(EssentialsObjectMappers.createJSONSerializer())
                                               .setSharedQueueTableName(QUEUE_TABLE)
                                               .setCentralizedMessageFetcherPollingInterval(POLLING_INTERVAL)
                                               .setCreateMultiTableChangeListener(false)
                                               .build();
        durableQueues.start();
        consumer = consumeWith(1, message -> { });
        var optimizer = ((CentralizedMessageFetcherDurableQueueConsumer) consumer).getQueuePollingOptimizer();
        Thread.sleep(500);

        assertThat(optimizer.shouldSkipPolling()).isFalse();
        assertThat(optimizer.toString()).isEqualTo("NoQueuePollingOptimizer");
    }

    @Test
    void a_local_enqueue_resets_the_backoff_when_it_commits() {
        durableQueues.start();
        consumer = consumeWith(1, message -> { });
        var optimizer = ((CentralizedMessageFetcherDurableQueueConsumer) consumer).getQueuePollingOptimizer();
        Awaitility.waitAtMost(Duration.ofSeconds(10))
                  .pollInterval(Duration.ofMillis(5))
                  .until(optimizer::shouldSkipPolling);

        unitOfWorkFactory.usingUnitOfWork(() -> {
            durableQueues.queueMessage(QUEUE, Message.of("payload"));
        });

        assertThat(optimizer.shouldSkipPolling()).as("the commit must have reset the backoff").isFalse();
    }

    @Test
    void a_poll_fills_the_slots_the_ordered_claim_left_with_unordered_messages() {
        // Not started: the test drives the fetch itself, so no poll races it for the messages
        consumer = consumeWith(8, message -> { });
        unitOfWorkFactory.usingUnitOfWork(() -> {
            durableQueues.queueMessage(QUEUE, OrderedMessage.of("ordered", "key-1", 0));
            for (var i = 0; i < 10; i++) {
                durableQueues.queueMessage(QUEUE, Message.of("unordered-" + i));
            }
        });

        var fetched = durableQueues.fetchNextBatchOfMessages(List.of(QUEUE), Map.of(QUEUE, Set.of()), Map.of(QUEUE, 8));

        assertThat(fetched).hasSize(8);
        assertThat(fetched).filteredOn(queued -> queued.getMessage() instanceof OrderedMessage).hasSize(1);
    }

    private DurableQueueConsumer consumeWith(int parallelConsumers, QueuedMessageHandler handler) {
        return durableQueues.consumeFromQueue(ConsumeFromQueue.builder()
                                                              .setQueueName(QUEUE)
                                                              .setRedeliveryPolicy(RedeliveryPolicy.fixedBackoff(Duration.ofMillis(100), 3))
                                                              .setParallelConsumers(parallelConsumers)
                                                              .setQueueMessageHandler(handler)
                                                              .build());
    }

    /**
     * Every claim statement scans the queue table, through an index or sequentially
     */
    private long queueTableScans() {
        return unitOfWorkFactory.withUnitOfWork(uow -> {
            uow.handle().execute("SELECT pg_stat_force_next_flush()");
            return uow.handle()
                      .createQuery("SELECT coalesce(seq_scan, 0) + coalesce(idx_scan, 0) FROM pg_stat_user_tables WHERE relname = :table")
                      .bind("table", QUEUE_TABLE)
                      .mapTo(Long.class)
                      .one();
        });
    }
}
