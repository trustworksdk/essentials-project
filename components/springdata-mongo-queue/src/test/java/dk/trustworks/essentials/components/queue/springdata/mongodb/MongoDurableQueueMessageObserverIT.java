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
import dk.trustworks.essentials.components.foundation.messaging.*;
import dk.trustworks.essentials.components.foundation.messaging.queue.*;
import dk.trustworks.essentials.components.foundation.messaging.queue.micrometer.MicrometerDurableQueueMessageObserver;
import dk.trustworks.essentials.components.foundation.messaging.queue.observability.*;
import dk.trustworks.essentials.components.foundation.messaging.queue.operations.ConsumeFromQueue;
import dk.trustworks.essentials.components.foundation.test.EssentialsTestContainers;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.awaitility.Awaitility;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.mongodb.test.autoconfigure.DataMongoTest;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.*;
import org.testcontainers.containers.MongoDBContainer;
import org.testcontainers.junit.jupiter.*;

import java.time.Duration;
import java.util.List;

import static dk.trustworks.essentials.components.foundation.messaging.queue.micrometer.MicrometerDurableQueueMessageObserver.*;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * Proof that the observer callbacks fire on the MongoDB implementation too, driven through a real queue.
 * <p>
 * Nothing in {@link MicrometerDurableQueueMessageObserver} or
 * {@link StatisticsCollectingDurableQueueMessageObserver} is database-specific — both consumers call the
 * observer — but {@link MongoDurableQueues} reaches them by a different route than
 * {@code PostgresqlDurableQueues} does: it has no centralized fetcher, so every notification comes from
 * {@code DefaultDurableQueueConsumer}. Until 0.60 the Mongo builder had no {@code setMessageObserver} at all,
 * so this pins the wiring as much as the callbacks.
 */
@Testcontainers
@DataMongoTest
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_EACH_TEST_METHOD)
class MongoDurableQueueMessageObserverIT {
    @Container
    static MongoDBContainer mongoDBContainer = new MongoDBContainer(EssentialsTestContainers.MONGO_IMAGE);

    @DynamicPropertySource
    static void setProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.mongodb.uri", mongoDBContainer::getReplicaSetUrl);
    }

    @Autowired
    private MongoTemplate mongoTemplate;

    private QueueStatisticsRegistry registry;
    private SimpleMeterRegistry     meterRegistry;
    private MongoDurableQueues      durableQueues;
    private DurableQueueConsumer    consumer;

    @BeforeEach
    void setUp() {
        mongoTemplate.dropCollection(MongoDurableQueues.DEFAULT_DURABLE_QUEUES_COLLECTION_NAME);
        registry = new QueueStatisticsRegistry();
        meterRegistry = new SimpleMeterRegistry();
        durableQueues = MongoDurableQueues.builder()
                                          .setMongoTemplate(mongoTemplate)
                                          .setJsonSerializer(EssentialsObjectMappers.createJSONSerializer())
                                          .setMessageHandlingTimeout(Duration.ofSeconds(5))
                                          .setMessageObserver(DurableQueueMessageObserver.composite(
                                                  List.of(new StatisticsCollectingDurableQueueMessageObserver(registry),
                                                          new MicrometerDurableQueueMessageObserver(meterRegistry, "MongoTestModule"))))
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

    private double deadLetteredCount(String reason) {
        var counter = meterRegistry.find(DEAD_LETTERED_COUNTER_NAME).tag(REASON_TAG, reason).counter();
        return counter != null ? counter.count() : 0d;
    }

    @Test
    void the_builder_installs_the_observer_and_a_successful_delivery_is_reported_as_handled() {
        var queueName = QueueName.of("MongoObserverHandledQueue");
        durableQueues.queueMessage(queueName, Message.of("payload"));

        consumeWith(queueName, message -> {
        }, 3);

        // hasValueSatisfying, not orElseThrow: an absent Optional must fail as an AssertionError, which
        // Awaitility retries. NoSuchElementException is not, so orElseThrow aborts the wait on the first poll.
        Awaitility.waitAtMost(Duration.ofSeconds(20))
                  .untilAsserted(() -> assertThat(registry.findStatistics(queueName))
                          .hasValueSatisfying(statistics -> assertThat(statistics.delivery().messagesHandled()).isEqualTo(1)));

        var statistics = registry.findStatistics(queueName).orElseThrow();
        assertThat(statistics.outcomes().messagesDeadLettered()).isZero();
        assertThat(meterRegistry.find(DEAD_LETTERED_COUNTER_NAME).counter()).isNull();
    }

    @Test
    void exhausting_the_redeliveries_increments_the_dead_letter_counter() {
        var queueName = QueueName.of("MongoObserverExhaustedQueue");
        durableQueues.queueMessage(queueName, Message.of("payload"));

        consumeWith(queueName, message -> {
            throw new IllegalStateException("always fails");
        }, 1);

        Awaitility.waitAtMost(Duration.ofSeconds(20))
                  .untilAsserted(() -> assertThat(deadLetteredCount("redeliveries_exhausted")).isEqualTo(1d));

        assertThat(registry.findStatistics(queueName).orElseThrow().outcomes().messagesDeadLettered()).isEqualTo(1);
        assertThat(deadLetteredCount("permanent_error")).isZero();
    }

    @Test
    void a_permanent_error_is_counted_under_its_own_reason() {
        // IllegalArgumentException is on the built-in permanent-error list, so this dead-letters on the first
        // delivery rather than after the redeliveries are used up — a different tag value, and the distinction
        // the counter exists to make.
        var queueName = QueueName.of("MongoObserverPermanentQueue");
        durableQueues.queueMessage(queueName, Message.of("payload"));

        consumeWith(queueName, message -> {
            throw new IllegalArgumentException("never valid");
        }, 5);

        Awaitility.waitAtMost(Duration.ofSeconds(20))
                  .untilAsserted(() -> assertThat(deadLetteredCount("permanent_error")).isEqualTo(1d));

        assertThat(deadLetteredCount("redeliveries_exhausted")).isZero();

        var counter = meterRegistry.find(DEAD_LETTERED_COUNTER_NAME).counter();
        assertThat(counter.getId().getTag(QUEUE_NAME_TAG)).isEqualTo(queueName.toString());
        assertThat(counter.getId().getTag(MODULE_TAG)).isEqualTo("MongoTestModule");
    }
}
