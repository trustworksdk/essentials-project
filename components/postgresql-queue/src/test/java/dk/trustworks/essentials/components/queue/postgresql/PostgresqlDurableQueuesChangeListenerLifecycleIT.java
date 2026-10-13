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
import org.testcontainers.junit.jupiter.*;
import org.testcontainers.postgresql.PostgreSQLContainer;

import java.time.Duration;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.*;

/**
 * The {@link MultiTableChangeListener} a {@link PostgresqlDurableQueues} is wired with across a stop and a start:
 * <ul>
 *     <li>one the builder created is built once, and stopped and resumed with the queues - it used to be replaced on
 *     every start, leaving the old one's event bus threads behind</li>
 *     <li>the queues subscribe to its bus once, however often they are started - a listener the application supplied
 *     used to get one more subscriber on every start, so each NOTIFY woke the queue's consumer once per start</li>
 * </ul>
 */
@Testcontainers
class PostgresqlDurableQueuesChangeListenerLifecycleIT {
    private static final String    QUEUE_TABLE = "change_listener_lifecycle_queue";
    private static final QueueName QUEUE       = QueueName.of("ChangeListenerLifecycleQueue");

    @Container
    private static final PostgreSQLContainer postgreSQLContainer = EssentialsTestContainers.postgres("change-listener-lifecycle-db");

    private JdbiUnitOfWorkFactory   unitOfWorkFactory;
    private PostgresqlDurableQueues durableQueues;
    /**
     * Another node: queues without a listener, so the consumer only hears of its messages through NOTIFY
     */
    private PostgresqlDurableQueues otherNode;
    private NotificationCountingOptimizer optimizer;

    @BeforeEach
    void setUp() {
        unitOfWorkFactory = new JdbiUnitOfWorkFactory(Jdbi.create(postgreSQLContainer.getJdbcUrl(),
                                                                   postgreSQLContainer.getUsername(),
                                                                   postgreSQLContainer.getPassword()));
        optimizer = new NotificationCountingOptimizer();
    }

    @AfterEach
    void tearDown() {
        if (durableQueues != null) {
            durableQueues.close();
        }
    }

    @Test
    void a_builder_made_listener_survives_stop_and_start_with_one_subscriber() {
        durableQueues = builder().build();
        var listener = durableQueues.getMultiTableChangeListener().orElseThrow();

        durableQueues.start();
        assertThat(pollThreadIsRunning()).as("polls while started").isTrue();
        durableQueues.stop();
        assertThat(listener.isStarted()).isFalse();
        Awaitility.waitAtMost(Duration.ofSeconds(5)).until(() -> !pollThreadIsRunning());
        durableQueues.start();

        assertThat(durableQueues.getMultiTableChangeListener()).as("the same listener, resumed").containsSame(listener);
        assertThat(listener.isStarted()).isTrue();
        assertThat(pollThreadIsRunning()).isTrue();
        assertThat(durableQueues.isSubscribedToQueueTableNotifications()).isTrue();
        // A stop removes the queue's consumers, so it is registered on the last start
        consume();
        assertOneNotificationPerMessageFromAnotherNode();

        durableQueues.stop();
        assertThat(listener.isStarted()).isFalse();
    }

    @Test
    void close_releases_the_builder_made_listeners_event_bus_for_good() {
        durableQueues = builder().build();
        var eventBus = durableQueues.getMultiTableChangeListener().orElseThrow().getEventBus();
        durableQueues.start();

        durableQueues.close();

        assertThat(durableQueues.isStarted()).isFalse();
        assertThat(eventBus.isStarted()).isFalse();
        assertThatThrownBy(durableQueues::start).isInstanceOf(IllegalStateException.class);
        // Idempotent
        durableQueues.close();
    }

    @Test
    void an_application_supplied_listener_gets_one_subscriber_however_often_the_queues_are_started() {
        var eventBus = new LocalEventBus("application-bus");
        var listener = MultiTableChangeListener.builder()
                                               .setJdbi(unitOfWorkFactory.getJdbi())
                                               .setPollingInterval(Duration.ofMillis(20))
                                               .setJsonSerializer(EssentialsObjectMappers.createJSONSerializer())
                                               .setEventBus(eventBus)
                                               .setFilterDuplicateNotifications(true)
                                               .<TableChangeNotification>build();
        listener.start();
        try {
            durableQueues = builder().setMultiTableChangeListener(listener).build();
            durableQueues.start();
            durableQueues.stop();
            durableQueues.start();
            consume();

            assertOneNotificationPerMessageFromAnotherNode();

            durableQueues.close();
            assertThat(listener.isStarted()).as("the application's listener is the application's to stop").isTrue();
            assertThat(eventBus.isStarted()).isTrue();
        } finally {
            listener.stop();
            eventBus.stop();
        }
    }

    private PostgresqlDurableQueuesBuilder builder() {
        return PostgresqlDurableQueues.builder()
                                      .setUnitOfWorkFactory(unitOfWorkFactory)
                                      .setJsonSerializer(EssentialsObjectMappers.createJSONSerializer())
                                      .setSharedQueueTableName(QUEUE_TABLE)
                                      // The CentralizedMessageFetcher cannot be started again after a stop
                                      .setUseCentralizedMessageFetcher(false)
                                      .setQueuePollingOptimizerFactory(consumeFromQueue -> optimizer);
    }

    private void consume() {
        durableQueues.consumeFromQueue(ConsumeFromQueue.builder()
                                                       .setQueueName(QUEUE)
                                                       .setRedeliveryPolicy(RedeliveryPolicy.fixedBackoff(Duration.ofMillis(100), 3))
                                                       .setParallelConsumers(1)
                                                       .setQueueMessageHandler(message -> { })
                                                       .build());
    }

    /**
     * The optimizer never lets the consumer poll, so the message stays queued and its INSERT is the only change
     * notified. A second subscriber on the bus would hand the consumer the same notification twice
     */
    private void assertOneNotificationPerMessageFromAnotherNode() {
        otherNode = PostgresqlDurableQueues.builder()
                                           .setUnitOfWorkFactory(unitOfWorkFactory)
                                           .setJsonSerializer(EssentialsObjectMappers.createJSONSerializer())
                                           .setSharedQueueTableName(QUEUE_TABLE)
                                           .setCreateMultiTableChangeListener(false)
                                           .build();
        otherNode.purgeQueue(QUEUE);
        unitOfWorkFactory.usingUnitOfWork(() -> otherNode.queueMessage(QUEUE, Message.of("from another node")));

        Awaitility.waitAtMost(Duration.ofSeconds(5))
                  .until(() -> optimizer.notifications.get() >= 1);
        Awaitility.await()
                  .during(Duration.ofMillis(500))
                  .atMost(Duration.ofSeconds(2))
                  .until(() -> optimizer.notifications.get() == 1);
    }

    private static boolean pollThreadIsRunning() {
        return Thread.getAllStackTraces().keySet().stream()
                     .anyMatch(thread -> thread.getName().equals("DurableQueues-" + QUEUE_TABLE));
    }

    private static final class NotificationCountingOptimizer implements QueuePollingOptimizer {
        private final AtomicInteger notifications = new AtomicInteger();

        @Override
        public void messageAdded(QueuedMessage queuedMessage) {
            notifications.incrementAndGet();
        }

        @Override
        public boolean shouldSkipPolling() {
            return true;
        }

        @Override
        public void queuePollingReturnedNoMessages() {
        }

        @Override
        public void queuePollingReturnedMessage(QueuedMessage queuedMessage) {
        }
    }
}
