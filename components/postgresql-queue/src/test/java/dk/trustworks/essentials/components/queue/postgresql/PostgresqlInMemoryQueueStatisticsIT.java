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
import dk.trustworks.essentials.components.foundation.messaging.queue.stats.*;
import dk.trustworks.essentials.components.foundation.test.EssentialsTestContainers;
import dk.trustworks.essentials.components.foundation.transaction.jdbi.JdbiUnitOfWorkFactory;
import org.awaitility.Awaitility;
import org.jdbi.v3.core.Jdbi;
import org.junit.jupiter.api.*;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.*;

import java.time.Duration;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Delivery statistics collected in Java, through {@link DurableQueueMessageObserver}, rather than by an
 * {@code AFTER DELETE} trigger on the queue table.
 *
 * <h2>What this establishes beyond "a number appears"</h2>
 * The trigger-based implementation had one integration test, which asserted {@code isPresent()} on the queue-level
 * aggregate — so it would have passed with a count of zero, a nonsensical latency, or every outcome lumped
 * together. The cases below are the ones that were previously unverifiable:
 * <ul>
 *     <li>no trigger is installed on the queue table any more;</li>
 *     <li>a retried message is not counted as delivered, and a dead-lettered one is not either;</li>
 *     <li>{@code purgeQueue} produces no statistics at all — the trigger counted a purge of N rows as N delivered
 *     messages, each with a latency measured to the moment of the purge;</li>
 *     <li>{@code getQueueStatisticsMessage} actually answers, which it never could before: the column was stored as
 *     an {@code INTERVAL} and read with {@code getInt}, so the read threw for every id.</li>
 * </ul>
 */
@Testcontainers
class PostgresqlInMemoryQueueStatisticsIT {

    @Container
    private static final PostgreSQLContainer<?> postgreSQLContainer = EssentialsTestContainers.postgres("queue-stats-db");

    private JdbiUnitOfWorkFactory          unitOfWorkFactory;
    private PostgresqlDurableQueues        durableQueues;
    private InMemoryDurableQueuesStatistics statistics;

    @BeforeEach
    void setUp() {
        unitOfWorkFactory = new JdbiUnitOfWorkFactory(Jdbi.create(postgreSQLContainer.getJdbcUrl(),
                                                                  postgreSQLContainer.getUsername(),
                                                                  postgreSQLContainer.getPassword()));
        unitOfWorkFactory.usingUnitOfWork(uow -> uow.handle()
                                                    .execute("DROP TABLE IF EXISTS " + PostgresqlDurableQueues.DEFAULT_DURABLE_QUEUES_TABLE_NAME));
        // The statistics object is built first and handed to the queue, which is the whole point: nothing about
        // enabling statistics touches the queue's schema any more.
        statistics = new InMemoryDurableQueuesStatistics();
        durableQueues = PostgresqlDurableQueues.builder()
                                               .setUnitOfWorkFactory(unitOfWorkFactory)
                                               .setJsonSerializer(EssentialsObjectMappers.createJSONSerializer())
                                               .setMessageObserver(statistics.observer())
                                               .build();
        durableQueues.start();
    }

    @AfterEach
    void tearDown() {
        if (durableQueues != null) {
            durableQueues.stop();
        }
    }

    @Test
    void enabling_statistics_installs_no_trigger_on_the_queue_table() {
        // The trigger was created by the statistics component against a table it does not own, which made
        // "enable statistics" a schema migration rather than a configuration change.
        var triggers = unitOfWorkFactory.withUnitOfWork(uow -> uow.handle()
                                                                  .createQuery("""
                                                                               SELECT count(*)
                                                                                 FROM pg_trigger t
                                                                                 JOIN pg_class c ON c.oid = t.tgrelid
                                                                                WHERE c.relname = :table AND NOT t.tgisinternal
                                                                               """)
                                                                  .bind("table", PostgresqlDurableQueues.DEFAULT_DURABLE_QUEUES_TABLE_NAME)
                                                                  .mapTo(Long.class)
                                                                  .one());
        assertThat(triggers).isZero();
    }

    @Test
    void a_handled_message_is_counted_with_a_plausible_latency_and_is_answerable_by_id() {
        var queueName = QueueName.of("StatsHandled");
        var id        = durableQueues.queueMessage(queueName, Message.of("payload"));
        consume(queueName, message -> {
        });

        Awaitility.waitAtMost(Duration.ofSeconds(10))
                  .untilAsserted(() -> assertThat(statistics.getQueueStatistics(queueName)).isPresent());

        var queueStatistics = statistics.getQueueStatistics(queueName).orElseThrow();
        assertThat((CharSequence) queueStatistics.queueName()).isEqualTo(queueName);
        assertThat(queueStatistics.totalMessagesDelivered()).isEqualTo(1L);
        // Not just "present": a latency measured from the wrong pair of timestamps typically lands negative or in
        // the millions, and the trigger version's own latency was measured to the moment of deletion.
        assertThat(queueStatistics.avgDeliveryLatencyMs()).isBetween(0, 60_000);
        assertThat(queueStatistics.firstDelivery()).isNotNull();
        assertThat(queueStatistics.lastDelivery()).isNotNull();

        // The per-message read, which the trigger implementation could never serve.
        var messageStatistics = statistics.getQueueStatisticsMessage(id).orElseThrow();
        assertThat((CharSequence) messageStatistics.getId()).isEqualTo(id);
        assertThat((CharSequence) messageStatistics.getQueueName()).isEqualTo(queueName);
        assertThat(messageStatistics.getTotalAttempts()).isEqualTo(1);
        assertThat(messageStatistics.getDeletionTimestamp()).isNotNull();
    }

    /**
     * A message that fails once and then succeeds must be counted as <b>one</b> delivery, not two and not zero.
     * The retry is a separate outcome.
     */
    @Test
    void a_retried_message_is_counted_once_when_it_finally_succeeds() {
        var queueName = QueueName.of("StatsRetried");
        durableQueues.queueMessage(queueName, Message.of("payload"));
        var attempts = new AtomicInteger();
        consume(queueName, message -> {
            if (attempts.incrementAndGet() == 1) {
                throw new RuntimeException("Thrown on purpose - first attempt");
            }
        });

        Awaitility.waitAtMost(Duration.ofSeconds(15))
                  .untilAsserted(() -> assertThat(statistics.getQueueStatistics(queueName)
                                                            .map(QueueStatistics::totalMessagesDelivered))
                          .contains(1L));
        assertThat(attempts.get()).isGreaterThanOrEqualTo(2);
    }

    /**
     * A dead-lettered message was never delivered successfully, so it must not be counted as one — otherwise the
     * "delivered" figure silently includes failures and an operator cannot tell a healthy queue from a broken one.
     */
    @Test
    void a_dead_lettered_message_is_not_counted_as_delivered() {
        var queueName = QueueName.of("StatsDeadLettered");
        durableQueues.queueMessage(queueName, Message.of("payload"));
        consume(queueName, message -> {
            throw new RuntimeException("Thrown on purpose - always");
        });

        Awaitility.waitAtMost(Duration.ofSeconds(20))
                  .untilAsserted(() -> assertThat(durableQueues.getTotalDeadLetterMessagesQueuedFor(queueName)).isEqualTo(1L));

        // Either nothing was recorded for the queue, or it was recorded with zero deliveries. Both are correct;
        // a non-zero delivered count is not.
        assertThat(statistics.getQueueStatistics(queueName).map(QueueStatistics::totalMessagesDelivered).orElse(0L))
                .isZero();
    }

    /**
     * The purge-amplification defect, asserted directly: the trigger fired once per deleted row, so purging a
     * queue both cost a second bulk insert and reported every purged message as delivered.
     */
    @Test
    void purging_a_queue_produces_no_statistics() {
        var queueName = QueueName.of("StatsPurged");
        for (var i = 0; i < 20; i++) {
            durableQueues.queueMessage(queueName, Message.of("payload-" + i));
        }

        assertThat(durableQueues.purgeQueue(queueName)).isEqualTo(20);

        assertThat(statistics.getQueueStatistics(queueName)).isEmpty();
        assertThat(statistics.registry().trackedQueueNames()).noneMatch(tracked -> tracked.equals(queueName));
    }

    /**
     * An observer that throws on every callback must not affect delivery. The guard is in
     * {@code DurableQueueMessageObserver.safe}, but this drives it through a real queue, because the guard is only
     * worth anything if the call sites actually go through it.
     */
    @Test
    void a_throwing_observer_does_not_stop_messages_being_delivered_and_acknowledged() {
        durableQueues.stop();
        durableQueues = PostgresqlDurableQueues.builder()
                                               .setUnitOfWorkFactory(unitOfWorkFactory)
                                               .setJsonSerializer(EssentialsObjectMappers.createJSONSerializer())
                                               .setMessageObserver(new DurableQueueMessageObserver() {
                                                   @Override
                                                   public void messageHandled(QueuedMessage message, Duration handlerDuration) {
                                                       throw new RuntimeException("Thrown on purpose from the observer");
                                                   }
                                               })
                                               .build();
        durableQueues.start();

        var queueName = QueueName.of("StatsThrowingObserver");
        durableQueues.queueMessage(queueName, Message.of("payload"));
        consume(queueName, message -> {
        });

        // Acknowledged despite the observer throwing - the queue drains.
        Awaitility.waitAtMost(Duration.ofSeconds(10))
                  .untilAsserted(() -> assertThat(durableQueues.getTotalMessagesQueuedFor(queueName)).isZero());
        assertThat(durableQueues.getTotalDeadLetterMessagesQueuedFor(queueName)).isZero();
    }

    private void consume(QueueName queueName, QueuedMessageHandler handler) {
        durableQueues.consumeFromQueue(ConsumeFromQueue.builder()
                                                       .setQueueName(queueName)
                                                       .setRedeliveryPolicy(RedeliveryPolicy.fixedBackoff()
                                                                                            .setRedeliveryDelay(Duration.ofMillis(100))
                                                                                            .setMaximumNumberOfRedeliveries(2)
                                                                                            .build())
                                                       .setParallelConsumers(1)
                                                       .setQueueMessageHandler(handler)
                                                       .build());
    }
}
