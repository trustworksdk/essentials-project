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

package dk.trustworks.essentials.components.queue.springdata.mongodb;

import dk.trustworks.essentials.components.foundation.json.EssentialsObjectMappers;
import dk.trustworks.essentials.components.foundation.messaging.RedeliveryPolicy;
import dk.trustworks.essentials.components.foundation.messaging.queue.*;
import dk.trustworks.essentials.components.foundation.messaging.queue.operations.ConsumeFromQueue;
import dk.trustworks.essentials.components.foundation.test.EssentialsTestContainers;
import dk.trustworks.essentials.components.foundation.transaction.spring.mongo.SpringMongoTransactionAwareUnitOfWorkFactory;
import org.awaitility.Awaitility;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.mongodb.test.autoconfigure.DataMongoTest;
import org.springframework.data.mongodb.*;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.*;
import org.testcontainers.junit.jupiter.*;
import org.testcontainers.mongodb.MongoDBContainer;

import java.time.*;
import java.util.*;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.assertj.core.api.Assertions.*;

/**
 * When {@link MongoDurableQueues} delivers a message that is not deliverable at once - a retry, a delayed enqueue, a
 * delayed resurrection: not before its due time, and not a polling backoff later either.
 * <p>
 * Until 0.60 a retry never stored its due time, so every Mongo retry was redelivered at once whatever the
 * {@link RedeliveryPolicy} said. And nothing told the consumer's {@link QueuePollingOptimizer} when a delayed message
 * came due, so on a queue that had backed off it waited up to the backoff cap. The consumers here run with a 10 s
 * backoff that a single empty poll reaches, so a message delivered "near its due time" was signalled.
 */
@Testcontainers
@DataMongoTest
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_EACH_TEST_METHOD)
class MongoDurableQueuesDeliveryDelayIT {
    private static final QueueName QUEUE        = QueueName.of("DeliveryDelayQueue");
    private static final Duration  DELAY        = Duration.ofSeconds(2);
    private static final long      BACKOFF_CAP_MS = 10_000;
    /**
     * How late after its due time a message may arrive and still count as signalled - well short of {@link #BACKOFF_CAP_MS}
     */
    private static final Duration  SLACK        = Duration.ofMillis(1500);

    @Container
    static MongoDBContainer mongoDBContainer = new MongoDBContainer(EssentialsTestContainers.MONGO_IMAGE).withReplicaSet();

    @DynamicPropertySource
    static void setProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.mongodb.uri", mongoDBContainer::getReplicaSetUrl);
    }

    @Autowired
    private MongoTemplate            mongoTemplate;
    @Autowired
    private MongoDatabaseFactory     databaseFactory;
    @Autowired
    private MongoTransactionManager  transactionManager;

    private SpringMongoTransactionAwareUnitOfWorkFactory unitOfWorkFactory;
    private MongoDurableQueues                           durableQueues;
    private DurableQueueConsumer                         consumer;
    private final List<Instant>                          scheduledFor = new CopyOnWriteArrayList<>();
    private final List<Instant>                          deliveries   = new CopyOnWriteArrayList<>();

    @BeforeEach
    void setUp() {
        mongoTemplate.dropCollection(MongoDurableQueues.DEFAULT_DURABLE_QUEUES_COLLECTION_NAME);
        unitOfWorkFactory = new SpringMongoTransactionAwareUnitOfWorkFactory(transactionManager, databaseFactory);
        durableQueues = MongoDurableQueues.builder()
                                          .setMongoTemplate(mongoTemplate)
                                          .setUnitOfWorkFactory(unitOfWorkFactory)
                                          .setJsonSerializer(EssentialsObjectMappers.createJSONSerializer())
                                          .setQueuePollingOptimizerFactory(RecordingOptimizer::new)
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

    /**
     * One empty poll backs the consumer off to the full {@link #BACKOFF_CAP_MS}; records every due time it is told
     */
    private final class RecordingOptimizer extends SimpleQueuePollingOptimizer {
        RecordingOptimizer(ConsumeFromQueue consumeFromQueue) {
            super(consumeFromQueue, BACKOFF_CAP_MS, BACKOFF_CAP_MS);
        }

        @Override
        public void messageScheduledFor(Instant dueAt) {
            scheduledFor.add(dueAt);
            super.messageScheduledFor(dueAt);
        }
    }

    private void consume(RedeliveryPolicy redeliveryPolicy, QueuedMessageHandler handler) {
        consumer = durableQueues.consumeFromQueue(ConsumeFromQueue.builder()
                                                                  .setQueueName(QUEUE)
                                                                  .setRedeliveryPolicy(redeliveryPolicy)
                                                                  .setParallelConsumers(1)
                                                                  .setPollingInterval(Duration.ofMillis(100))
                                                                  .setQueueMessageHandler(message -> {
                                                                      deliveries.add(Instant.now());
                                                                      handler.handle(message);
                                                                  })
                                                                  .build());
    }

    private void consumeSucceeding() {
        consume(RedeliveryPolicy.fixedBackoff(Duration.ofMillis(100), 3), message -> {
        });
    }

    @Test
    void a_failed_delivery_is_retried_after_the_redelivery_policy_delay_not_at_once() {
        unitOfWorkFactory.usingUnitOfWork(() -> durableQueues.queueMessage(QUEUE, Message.of("payload")));
        consume(RedeliveryPolicy.fixedBackoff(DELAY, 3), message -> {
            if (deliveries.size() == 1) {
                throw new IllegalStateException("Thrown on purpose - first delivery");
            }
        });

        // The retry stores its due time
        Awaitility.waitAtMost(Duration.ofSeconds(10))
                  .untilAsserted(() -> assertThat(durableQueues.getQueuedMessages(QUEUE, DurableQueues.QueueingSortOrder.ASC, 0, 10))
                          .singleElement()
                          .satisfies(queued -> {
                              assertThat(queued.getRedeliveryAttempts()).isEqualTo(1);
                              assertThat(queued.isBeingDelivered()).isFalse();
                          }));
        var firstDelivery = deliveries.getFirst();
        var retried       = durableQueues.getQueuedMessages(QUEUE, DurableQueues.QueueingSortOrder.ASC, 0, 10).getFirst();
        assertThat(retried.getNextDeliveryTimestamp().toInstant()).isAfterOrEqualTo(firstDelivery.plus(DELAY).minusMillis(5));
        assertThat(deliveries).hasSize(1);

        // ... and is redelivered once it is due, not before
        Awaitility.waitAtMost(Duration.ofSeconds(15))
                  .untilAsserted(() -> assertThat(deliveries).hasSize(2));
        assertThat(Duration.between(firstDelivery, deliveries.get(1))).isGreaterThanOrEqualTo(DELAY.minusMillis(5));
        Awaitility.waitAtMost(Duration.ofSeconds(10))
                  .untilAsserted(() -> assertThat(durableQueues.getTotalMessagesQueuedFor(QUEUE)).isZero());
    }
}
