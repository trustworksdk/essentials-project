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
import dk.trustworks.essentials.components.foundation.messaging.*;
import dk.trustworks.essentials.components.foundation.messaging.queue.*;
import dk.trustworks.essentials.components.foundation.messaging.queue.observability.*;
import dk.trustworks.essentials.components.foundation.messaging.queue.operations.ConsumeFromQueue;
import dk.trustworks.essentials.components.foundation.test.EssentialsTestContainers;
import dk.trustworks.essentials.components.foundation.transaction.jdbi.JdbiUnitOfWorkFactory;
import org.awaitility.Awaitility;
import org.jdbi.v3.core.Jdbi;
import org.junit.jupiter.api.*;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.*;

import java.time.*;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * End-to-end proof that {@link DurableQueueMessageObserver} is notified for each way a delivery can end, driven
 * through a real queue rather than by calling the observer directly.
 */
@Testcontainers
class DurableQueueMessageObserverIT {
    @Container
    private static final PostgreSQLContainer<?> postgreSQLContainer = EssentialsTestContainers.postgres("observer-db");

    private JdbiUnitOfWorkFactory   unitOfWorkFactory;
    private PostgresqlDurableQueues durableQueues;
    private QueueStatisticsRegistry registry;
    private DurableQueueConsumer    consumer;

    @BeforeEach
    void setUp() {
        unitOfWorkFactory = new JdbiUnitOfWorkFactory(Jdbi.create(postgreSQLContainer.getJdbcUrl(),
                                                                   postgreSQLContainer.getUsername(),
                                                                   postgreSQLContainer.getPassword()));
        registry = new QueueStatisticsRegistry();
        durableQueues = PostgresqlDurableQueues.builder()
                                               .setUnitOfWorkFactory(unitOfWorkFactory)
                                               .setJsonSerializer(EssentialsObjectMappers.createJSONSerializer())
                                               .setMessageObserver(new StatisticsCollectingDurableQueueMessageObserver(registry))
                                               .build();
        durableQueues.start();
    }

    @AfterEach
    void tearDown() {
        if (consumer != null) {
            consumer.cancel();
        }
        if (durableQueues != null) {
            durableQueues.stop();
        }
    }

    private void consumeWith(QueueName queueName, QueuedMessageHandler handler, int maximumNumberOfRedeliveries) {
        consumer = durableQueues.consumeFromQueue(ConsumeFromQueue.builder()
                                                                  .setQueueName(queueName)
                                                                  .setRedeliveryPolicy(RedeliveryPolicy.fixedBackoff(Duration.ofMillis(50),
                                                                                                                      maximumNumberOfRedeliveries))
                                                                  .setParallelConsumers(1)
                                                                  .setQueueMessageHandler(handler)
                                                                  .build());
    }

    @Test
    void a_successful_delivery_is_reported_as_handled() {
        var queueName = QueueName.of("ObserverHandledQueue");
        durableQueues.purgeQueue(queueName);
        unitOfWorkFactory.usingUnitOfWork(() -> durableQueues.queueMessage(queueName, Message.of("payload")));

        consumeWith(queueName, message -> {
        }, 3);

        Awaitility.waitAtMost(Duration.ofSeconds(10)).untilAsserted(() -> {
            var delivery = registry.findStatistics(queueName).orElseThrow().delivery();
            assertThat(delivery.messagesHandled()).isEqualTo(1);
        });

        var statistics = registry.findStatistics(queueName).orElseThrow();
        assertThat(statistics.delivery().lastHandledAt()).isNotNull();
        assertThat(statistics.delivery().averageHandlerDuration()).isNotNull();
        assertThat(statistics.outcomes().messagesRetried()).isZero();
        assertThat(statistics.outcomes().messagesDeadLettered()).isZero();
    }

    @Test
    void a_failure_that_later_succeeds_is_reported_as_retried_then_handled() {
        var queueName = QueueName.of("ObserverRetriedQueue");
        durableQueues.purgeQueue(queueName);
        unitOfWorkFactory.usingUnitOfWork(() -> durableQueues.queueMessage(queueName, Message.of("payload")));

        var attempts = new AtomicInteger();
        consumeWith(queueName, message -> {
            if (attempts.incrementAndGet() == 1) {
                throw new IllegalStateException("fail once");
            }
        }, 3);

        Awaitility.waitAtMost(Duration.ofSeconds(10)).untilAsserted(() -> {
            var statistics = registry.findStatistics(queueName).orElseThrow();
            assertThat(statistics.outcomes().messagesRetried()).isEqualTo(1);
            assertThat(statistics.delivery().messagesHandled()).isEqualTo(1);
        });

        assertThat(registry.findStatistics(queueName).orElseThrow().outcomes().lastFailureReason())
                .isEqualTo("IllegalStateException: fail once");
    }

    @Test
    void a_permanent_failure_is_reported_as_dead_lettered() {
        var queueName = QueueName.of("ObserverDeadLetteredQueue");
        durableQueues.purgeQueue(queueName);
        unitOfWorkFactory.usingUnitOfWork(() -> durableQueues.queueMessage(queueName, Message.of("payload")));

        // IllegalArgumentException is on the built-in permanent list, so this dead-letters on the first attempt
        consumeWith(queueName, message -> {
            throw new IllegalArgumentException("never valid");
        }, 3);

        Awaitility.waitAtMost(Duration.ofSeconds(10)).untilAsserted(() -> {
            var outcomes = registry.findStatistics(queueName).orElseThrow().outcomes();
            assertThat(outcomes.messagesDeadLettered()).isEqualTo(1);
        });

        var statistics = registry.findStatistics(queueName).orElseThrow();
        assertThat(statistics.delivery().messagesHandled()).isZero();
        assertThat(statistics.outcomes().messagesRetried()).isZero();
        assertThat(statistics.outcomes().lastFailureReason()).isEqualTo("IllegalArgumentException: never valid");
    }

    @Test
    void purging_a_queue_is_not_reported_as_a_delivery() {
        // The trigger removed in 0.60 counted a 100 000-row purge as 100 000 delivered messages. The observer
        // is notified from the delivery path only, so administrative operations cannot do that.
        var queueName = QueueName.of("ObserverPurgedQueue");
        durableQueues.purgeQueue(queueName);
        unitOfWorkFactory.usingUnitOfWork(() -> {
            durableQueues.queueMessage(queueName, Message.of("one"));
            durableQueues.queueMessage(queueName, Message.of("two"));
        });

        durableQueues.purgeQueue(queueName);

        assertThat(registry.findStatistics(queueName)).isEmpty();
    }
}
