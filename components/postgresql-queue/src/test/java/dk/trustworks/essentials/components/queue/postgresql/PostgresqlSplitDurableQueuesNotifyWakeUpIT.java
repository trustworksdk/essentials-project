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
import dk.trustworks.essentials.components.foundation.postgresql.*;
import dk.trustworks.essentials.components.foundation.test.EssentialsTestContainers;
import dk.trustworks.essentials.components.foundation.transaction.jdbi.JdbiUnitOfWorkFactory;
import dk.trustworks.essentials.reactive.LocalEventBus;
import org.awaitility.Awaitility;
import org.jdbi.v3.core.Jdbi;
import org.junit.jupiter.api.*;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.*;

import java.time.Duration;
import java.util.*;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Function;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * LISTEN/NOTIFY wake-up on the split (B5(a)). The split had none until this landed: v1 installs the
 * change-notification trigger inside its schema initialization, which a
 * {@link PostgresqlDurableQueues.Role#SPLIT_DELEGATE} skips, so a deployment moving onto the split with a
 * {@link MultiTableChangeListener} configured would silently have lost its wake-ups and polled at the fixed
 * interval.
 *
 * <h2>Why this asserts the mechanism and not a latency</h2>
 * The tempting test — enqueue and assert delivery is faster than the backed-off delay — is timing-dependent and
 * would be flaky. What is deterministic is that the notification reaches the queue's
 * {@link QueuePollingOptimizer#messageAdded(QueuedMessage)}, which is the single call that ends a backoff. So the
 * tests inject a recording optimizer and assert the wake-up arrives.
 *
 * <h2>The split-specific part</h2>
 * <b>Both</b> tables must wake the same queue. Wake-ups are routed by queue name rather than by table — which is
 * how v1 already routes them — so an ordered enqueue wakes the poll that also covers the unordered table. Keyed
 * by table instead, an ordered enqueue would advance state the queue's single poll decision never reads; that is
 * the same failure shape as reporting the polling outcome per table rather than once across both. See §7d/§7e of
 * {@code docs/durable-queues-implementation-plan.md}.
 */
@Testcontainers
class PostgresqlSplitDurableQueuesNotifyWakeUpIT {

    private static final String BASE_TABLE_NAME = "split_notify_queues";

    @Container
    private static final PostgreSQLContainer<?> postgreSQLContainer = EssentialsTestContainers.postgres("split-notify-db");

    private JdbiUnitOfWorkFactory                              unitOfWorkFactory;
    private MultiTableChangeListener<TableChangeNotification>  multiTableChangeListener;
    private PostgresqlSplitDurableQueues                       durableQueues;
    private final Map<QueueName, RecordingOptimizer>           optimizers = new HashMap<>();

    @BeforeEach
    void setUp() {
        unitOfWorkFactory = new JdbiUnitOfWorkFactory(Jdbi.create(postgreSQLContainer.getJdbcUrl(),
                                                                  postgreSQLContainer.getUsername(),
                                                                  postgreSQLContainer.getPassword()));
        unitOfWorkFactory.usingUnitOfWork(uow -> {
            uow.handle().execute("DROP TABLE IF EXISTS " + BASE_TABLE_NAME + PostgresqlSplitDurableQueues.UNORDERED_TABLE_SUFFIX);
            uow.handle().execute("DROP TABLE IF EXISTS " + BASE_TABLE_NAME + PostgresqlSplitDurableQueues.ORDERED_TABLE_SUFFIX);
        });
        optimizers.clear();
    }

    @AfterEach
    void tearDown() {
        if (durableQueues != null) {
            durableQueues.stop();
        }
        if (multiTableChangeListener != null) {
            multiTableChangeListener.stop();
        }
    }

    @Test
    void a_configured_listener_installs_the_notification_trigger_on_both_tables() {
        startWithListener();

        assertThat(notificationTriggerCountOn(durableQueues.getUnorderedTableName()))
                .as("the unordered table must notify, or unordered enqueues never wake a poll")
                .isEqualTo(1L);
        assertThat(notificationTriggerCountOn(durableQueues.getOrderedTableName()))
                .as("the ordered table must notify too - this is the one v1's per-table install would have missed")
                .isEqualTo(1L);
    }

    @Test
    void without_a_listener_neither_table_gets_a_trigger() {
        startWithoutListener();

        // The negative control: without it, a test that always found triggers would pass even if the install were
        // unconditional, and the polling-only configuration is a supported one.
        assertThat(notificationTriggerCountOn(durableQueues.getUnorderedTableName())).isZero();
        assertThat(notificationTriggerCountOn(durableQueues.getOrderedTableName())).isZero();
    }

    /**
     * The load-bearing one: an enqueue to <em>either</em> table wakes the queue's single consumer.
     */
    @Test
    void an_enqueue_to_either_table_wakes_the_queues_consumer() {
        startWithListener();
        var queueName = QueueName.of("WakeUp");
        consume(queueName);
        var optimizer = optimizers.get(queueName);

        durableQueues.queueMessage(queueName, Message.of("plain"));
        Awaitility.waitAtMost(Duration.ofSeconds(10))
                  .untilAsserted(() -> assertThat(optimizer.wakeUps()).as("unordered enqueue must wake the consumer").isNotEmpty());

        var afterUnordered = optimizer.wakeUps().size();
        durableQueues.queueMessage(queueName, OrderedMessage.of("ordered", "key-a", 0L));
        Awaitility.waitAtMost(Duration.ofSeconds(10))
                  .untilAsserted(() -> assertThat(optimizer.wakeUps().size())
                          .as("an ordered enqueue must wake the same consumer - the wake-up is keyed by queue, not by table")
                          .isGreaterThan(afterUnordered));
    }

    /**
     * The negative control for the test above, and the reason it can be believed: with no listener configured,
     * enqueueing and consuming the very same traffic produces <b>no</b> wake-ups. Without this, the wake-up
     * assertion would also pass if {@code messageAdded} were reached from some path other than a notification —
     * the shape of false pass this branch has already hit four times.
     */
    @Test
    void without_a_listener_the_same_traffic_produces_no_wake_ups() {
        startWithoutListener(this::recordingOptimizerFor);
        var queueName = QueueName.of("NoWakeUps");
        consume(queueName);
        var optimizer = optimizers.get(queueName);

        durableQueues.queueMessage(queueName, Message.of("plain"));
        durableQueues.queueMessage(queueName, OrderedMessage.of("ordered", "key-a", 0L));

        // Both messages are consumed - so the queue is demonstrably live, and the absence of wake-ups is about the
        // notification path specifically rather than about nothing having happened.
        Awaitility.waitAtMost(Duration.ofSeconds(15))
                  .untilAsserted(() -> assertThat(durableQueues.getTotalMessagesQueuedFor(queueName)).isZero());
        assertThat(optimizer.wakeUps()).isEmpty();
    }

    /**
     * A queue nobody consumes must not blow up the notification handler, and must not leave the handler unable to
     * serve the queues that <em>are</em> consumed. Cheap to get wrong: the routing looks the consumer up by name
     * and gets nothing back.
     */
    @Test
    void a_notification_for_a_queue_with_no_consumer_is_ignored_without_disturbing_the_others() {
        startWithListener();
        var consumed   = QueueName.of("Consumed");
        var unconsumed = QueueName.of("Unconsumed");
        consume(consumed);
        var optimizer = optimizers.get(consumed);

        durableQueues.queueMessage(unconsumed, Message.of("nobody-listens"));
        durableQueues.queueMessage(consumed, Message.of("somebody-does"));

        Awaitility.waitAtMost(Duration.ofSeconds(10))
                  .untilAsserted(() -> assertThat(optimizer.wakeUps()).isNotEmpty());
    }

    private void consume(QueueName queueName) {
        durableQueues.consumeFromQueue(ConsumeFromQueue.builder()
                                                       .setQueueName(queueName)
                                                       .setRedeliveryPolicy(RedeliveryPolicy.fixedBackoff()
                                                                                            .setRedeliveryDelay(Duration.ofMillis(100))
                                                                                            .setMaximumNumberOfRedeliveries(3)
                                                                                            .build())
                                                       .setParallelConsumers(1)
                                                       .setQueueMessageHandler(message -> {
                                                       })
                                                       .build());
    }

    private void startWithListener() {
        var jsonSerializer = EssentialsObjectMappers.createJSONSerializer();
        multiTableChangeListener = new MultiTableChangeListener<>(unitOfWorkFactory.getJdbi(),
                                                                 Duration.ofMillis(50),
                                                                 jsonSerializer,
                                                                 LocalEventBus.builder().busName("split-notify").build(),
                                                                 true);
        durableQueues = PostgresqlSplitDurableQueues.builder()
                                                    .setUnitOfWorkFactory(unitOfWorkFactory)
                                                    .setJsonSerializer(jsonSerializer)
                                                    .setBaseQueueTableName(BASE_TABLE_NAME)
                                                    .setMultiTableChangeListener(multiTableChangeListener)
                                                    .setCentralizedQueuePollingOptimizerFactory(this::recordingOptimizerFor)
                                                    .build();
        durableQueues.start();
    }

    private void startWithoutListener() {
        startWithoutListener(null);
    }

    private void startWithoutListener(Function<QueueName, QueuePollingOptimizer> optimizerFactory) {
        durableQueues = PostgresqlSplitDurableQueues.builder()
                                                    .setUnitOfWorkFactory(unitOfWorkFactory)
                                                    .setJsonSerializer(EssentialsObjectMappers.createJSONSerializer())
                                                    .setBaseQueueTableName(BASE_TABLE_NAME)
                                                    .setCentralizedQueuePollingOptimizerFactory(optimizerFactory)
                                                    .build();
        durableQueues.start();
    }

    private QueuePollingOptimizer recordingOptimizerFor(QueueName queueName) {
        return optimizers.computeIfAbsent(queueName, ignored -> new RecordingOptimizer());
    }

    private long notificationTriggerCountOn(String tableName) {
        return unitOfWorkFactory.withUnitOfWork(uow -> uow.handle()
                                                          .createQuery("""
                                                                       SELECT count(*)
                                                                         FROM pg_trigger t
                                                                         JOIN pg_class c ON c.oid = t.tgrelid
                                                                        WHERE c.relname = :table AND NOT t.tgisinternal
                                                                       """)
                                                          .bind("table", tableName)
                                                          .mapTo(Long.class)
                                                          .one());
    }

    /**
     * Records every {@code messageAdded} - the one call a NOTIFY makes, and therefore the observable end of the
     * wake-up path. Never skips polling, so the test's assertions cannot be confounded by backoff.
     */
    private static final class RecordingOptimizer implements QueuePollingOptimizer {
        private final List<QueueEntryId> wakeUps = new CopyOnWriteArrayList<>();

        List<QueueEntryId> wakeUps() {
            return wakeUps;
        }

        @Override
        public void messageAdded(QueuedMessage queuedMessage) {
            wakeUps.add(queuedMessage.getId());
        }

        @Override
        public void queuePollingReturnedNoMessages() {
        }

        @Override
        public void queuePollingReturnedMessage(QueuedMessage queuedMessage) {
        }

        @Override
        public boolean shouldSkipPolling() {
            return false;
        }
    }
}
