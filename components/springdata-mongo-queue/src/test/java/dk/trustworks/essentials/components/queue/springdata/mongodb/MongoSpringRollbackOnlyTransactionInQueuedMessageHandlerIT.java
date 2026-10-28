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
import org.bson.Document;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.mongodb.test.autoconfigure.DataMongoTest;
import org.springframework.data.mongodb.*;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.*;
import org.springframework.transaction.support.*;
import org.testcontainers.junit.jupiter.*;
import org.testcontainers.mongodb.MongoDBContainer;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The Mongo counterpart of {@code SpringRollbackOnlyTransactionInQueuedMessageHandlerIT} in postgresql-queue: a queued
 * message handler does its work in a {@link SpringMongoTransactionAwareUnitOfWorkFactory} unit of work, calls an inner
 * Spring transactional participant (here a {@link TransactionTemplate}, as an inner {@code @Transactional} method
 * would) that fails, and catches the exception. The participant has marked the Spring transaction rollback-only, so it
 * can never commit.
 * <p>
 * {@link MongoDurableQueues} hands the message to the handler outside any unit of work - acknowledgement and retry run
 * in transactions of their own - so the handler opens the unit of work itself, as a handler on this implementation
 * must to make its writes transactional.
 * <p>
 * Committing that unit of work must complete the transaction - rolled back, with the handler's writes - and fail, so
 * the message is retried as a failed attempt rather than acknowledged as handled. Neither the transaction nor the unit
 * of work may stay bound to the consumer's worker thread.
 */
@Testcontainers
@DataMongoTest
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_EACH_TEST_METHOD)
class MongoSpringRollbackOnlyTransactionInQueuedMessageHandlerIT {
    private static final QueueName QUEUE                   = QueueName.of("SpringRollbackOnlyQueue");
    private static final String    HANDLER_WRITES          = "handler_writes";
    private static final String    FAILS_INNER_PARTICIPANT = "fails-inner-participant";

    @Container
    static MongoDBContainer mongoDBContainer = new MongoDBContainer(EssentialsTestContainers.MONGO_IMAGE).withReplicaSet();

    @DynamicPropertySource
    static void setProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.mongodb.uri", mongoDBContainer::getReplicaSetUrl);
    }

    @Autowired
    private MongoTemplate mongoTemplate;

    @Autowired
    private MongoDatabaseFactory databaseFactory;

    @Autowired
    private MongoTransactionManager transactionManager;

    private SpringMongoTransactionAwareUnitOfWorkFactory unitOfWorkFactory;
    private TransactionTemplate                          transactionTemplate;
    private MongoDurableQueues                           durableQueues;
    private DurableQueueConsumer                         consumer;

    private final List<Integer> redeliveryAttemptsPerDelivery = new CopyOnWriteArrayList<>();
    private final List<String>  leftBoundAfterHandling        = new CopyOnWriteArrayList<>();
    private final AtomicBoolean failInnerParticipantOnce      = new AtomicBoolean(true);

    @BeforeEach
    void setUp() {
        mongoTemplate.dropCollection(MongoDurableQueues.DEFAULT_DURABLE_QUEUES_COLLECTION_NAME);
        mongoTemplate.dropCollection(HANDLER_WRITES);
        // Created up front, so the handler's insert never has to create a collection inside its transaction
        mongoTemplate.createCollection(HANDLER_WRITES);
        unitOfWorkFactory = new SpringMongoTransactionAwareUnitOfWorkFactory(transactionManager, databaseFactory);
        transactionTemplate = new TransactionTemplate(transactionManager);
        durableQueues = MongoDurableQueues.builder()
                                          .setMongoTemplate(mongoTemplate)
                                          .setUnitOfWorkFactory(unitOfWorkFactory)
                                          .setJsonSerializer(EssentialsObjectMappers.createJSONSerializer())
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

    @Test
    void the_handlers_writes_are_rolled_back_and_the_message_is_retried_as_a_failed_attempt() {
        consumer = consume();
        var queueEntryId = unitOfWorkFactory.withUnitOfWork(() -> durableQueues.queueMessage(QUEUE, Message.of(FAILS_INNER_PARTICIPANT)));

        Awaitility.waitAtMost(Duration.ofSeconds(10))
                  .untilAsserted(() -> assertThat(durableQueues.getQueuedMessage(queueEntryId))
                          .as("retried after its rollback-only transaction rolled back, then handled and acknowledged")
                          .isEmpty());
        assertThat(redeliveryAttemptsPerDelivery).as("the rolled back delivery counted as a failed attempt")
                                                 .containsExactly(0, 1);
        assertThat(mongoTemplate.getCollection(HANDLER_WRITES).countDocuments())
                .as("only the retry's write committed - the first delivery's was rolled back")
                .isEqualTo(1);
        assertThat(durableQueues.getTotalDeadLetterMessagesQueuedFor(QUEUE)).isZero();
        assertThat(leftBoundAfterHandling).as("transaction state still bound to the worker thread once a message's handling returned")
                                          .isEmpty();
    }

    /**
     * The first delivery calls an inner transactional participant that fails, and catches its exception - the work in
     * the unit of work itself completes normally
     */
    private DurableQueueConsumer consume() {
        return durableQueues.consumeFromQueue(ConsumeFromQueue.builder()
                                                              .setQueueName(QUEUE)
                                                              .setRedeliveryPolicy(RedeliveryPolicy.fixedBackoff(Duration.ofMillis(50), 3))
                                                              .setParallelConsumers(1)
                                                              .setQueueMessageHandler(message -> {
                                                                  redeliveryAttemptsPerDelivery.add(message.getRedeliveryAttempts());
                                                                  try {
                                                                      unitOfWorkFactory.usingUnitOfWork(() -> {
                                                                          mongoTemplate.insert(new Document("payload", message.getPayload().toString()), HANDLER_WRITES);
                                                                          if (failInnerParticipantOnce.compareAndSet(true, false)) {
                                                                              try {
                                                                                  transactionTemplate.executeWithoutResult(status -> {
                                                                                      throw new IllegalStateException("the inner transactional participant fails");
                                                                                  });
                                                                              } catch (IllegalStateException caught) {
                                                                                  // The work carries on - but the participant has marked the transaction rollback-only
                                                                              }
                                                                          }
                                                                      });
                                                                  } finally {
                                                                      if (TransactionSynchronizationManager.isActualTransactionActive() || unitOfWorkFactory.getCurrentUnitOfWork().isPresent()) {
                                                                          leftBoundAfterHandling.add(message.getPayload() + ": actualTransactionActive=" + TransactionSynchronizationManager.isActualTransactionActive() +
                                                                                                             ", unitOfWork=" + unitOfWorkFactory.getCurrentUnitOfWork());
                                                                      }
                                                                  }
                                                              })
                                                              .build());
    }
}
