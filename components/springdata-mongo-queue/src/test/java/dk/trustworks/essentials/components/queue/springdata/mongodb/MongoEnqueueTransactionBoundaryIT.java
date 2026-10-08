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
import dk.trustworks.essentials.components.foundation.messaging.queue.*;
import dk.trustworks.essentials.components.foundation.test.EssentialsTestContainers;
import dk.trustworks.essentials.components.foundation.transaction.NoActiveUnitOfWorkException;
import dk.trustworks.essentials.components.foundation.transaction.spring.mongo.SpringMongoTransactionAwareUnitOfWorkFactory;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.mongodb.test.autoconfigure.DataMongoTest;
import org.springframework.data.mongodb.*;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.*;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.junit.jupiter.*;
import org.testcontainers.mongodb.MongoDBContainer;

import java.util.List;

import static org.assertj.core.api.Assertions.*;

/**
 * Where an enqueue's transaction boundary is on {@link MongoDurableQueues}: it rolls back with an Essentials
 * {@code UnitOfWork} and with a plain Spring {@link TransactionTemplate}, and outside both it commits on its own - or,
 * with {@code requireActiveUnitOfWorkForEnqueue}, fails (RN-2).
 */
@Testcontainers
@DataMongoTest
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_EACH_TEST_METHOD)
class MongoEnqueueTransactionBoundaryIT {
    private static final QueueName QUEUE = QueueName.of("EnqueueBoundary");

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

    @BeforeEach
    void setUp() {
        mongoTemplate.dropCollection(MongoDurableQueues.DEFAULT_DURABLE_QUEUES_COLLECTION_NAME);
        unitOfWorkFactory = new SpringMongoTransactionAwareUnitOfWorkFactory(transactionManager, databaseFactory);
        transactionTemplate = new TransactionTemplate(transactionManager);
    }

    @AfterEach
    void tearDown() {
        if (durableQueues != null) {
            durableQueues.stop();
        }
    }

    // ------------------------------------------------------------ rollback

    @Test
    void an_enqueue_rolls_back_with_an_essentials_unit_of_work() {
        durableQueues = queues(false);

        assertThatThrownBy(() -> unitOfWorkFactory.usingUnitOfWork(() -> {
            durableQueues.queueMessage(QUEUE, Message.of("rolled-back"));
            durableQueues.queueMessages(QUEUE, List.of(Message.of("rolled-back-too")));
            throw new IllegalStateException("the caller's own work failed");
        })).hasRootCauseMessage("the caller's own work failed");

        assertThat(durableQueues.getTotalMessagesQueuedFor(QUEUE)).isZero();
    }

    @Test
    void an_enqueue_rolls_back_with_a_spring_transaction_template() {
        durableQueues = queues(false);

        assertThatThrownBy(() -> transactionTemplate.executeWithoutResult(status -> {
            durableQueues.queueMessage(QUEUE, Message.of("rolled-back"));
            durableQueues.queueMessages(QUEUE, List.of(Message.of("rolled-back-too")));
            throw new IllegalStateException("the caller's own work failed");
        })).hasMessage("the caller's own work failed");

        assertThat(durableQueues.getTotalMessagesQueuedFor(QUEUE)).isZero();
    }

    // --------------------------------------------- outside a unit of work

    @Test
    void by_default_an_enqueue_outside_a_unit_of_work_commits_on_its_own() {
        durableQueues = queues(false);

        durableQueues.queueMessage(QUEUE, Message.of("standalone"));
        durableQueues.queueMessages(QUEUE, List.of(Message.of("standalone-too")));

        assertThat(durableQueues.isRequireActiveUnitOfWorkForEnqueue()).isFalse();
        assertThat(durableQueues.getTotalMessagesQueuedFor(QUEUE)).isEqualTo(2);
    }

    @Test
    void when_required_an_enqueue_outside_a_unit_of_work_fails_and_queues_nothing() {
        durableQueues = queues(true);

        assertThatThrownBy(() -> durableQueues.queueMessage(QUEUE, Message.of("refused")))
                .isInstanceOf(NoActiveUnitOfWorkException.class)
                .hasMessageContaining(EnqueueOutsideUnitOfWorkGuard.REQUIRE_ACTIVE_UNIT_OF_WORK_FOR_ENQUEUE_PROPERTY);
        assertThatThrownBy(() -> durableQueues.queueMessages(QUEUE, List.of(Message.of("refused-too"))))
                .isInstanceOf(NoActiveUnitOfWorkException.class);

        assertThat(durableQueues.getTotalMessagesQueuedFor(QUEUE)).isZero();
    }

    @Test
    void when_required_an_enqueue_inside_an_essentials_unit_of_work_goes_ahead() {
        durableQueues = queues(true);

        unitOfWorkFactory.usingUnitOfWork(() -> durableQueues.queueMessage(QUEUE, Message.of("inside")));

        assertThat(durableQueues.getTotalMessagesQueuedFor(QUEUE)).isEqualTo(1);
    }

    @Test
    void when_required_an_enqueue_inside_a_plain_spring_transaction_goes_ahead() {
        durableQueues = queues(true);

        transactionTemplate.executeWithoutResult(status -> {
            durableQueues.queueMessage(QUEUE, Message.of("inside"));
            durableQueues.queueMessages(QUEUE, List.of(Message.of("inside-too")));
        });

        assertThat(durableQueues.getTotalMessagesQueuedFor(QUEUE)).isEqualTo(2);
    }

    private MongoDurableQueues queues(boolean requireActiveUnitOfWorkForEnqueue) {
        var queues = MongoDurableQueues.builder()
                                       .setMongoTemplate(mongoTemplate)
                                       .setUnitOfWorkFactory(unitOfWorkFactory)
                                       .setJsonSerializer(EssentialsObjectMappers.createJSONSerializer())
                                       .setRequireActiveUnitOfWorkForEnqueue(requireActiveUnitOfWorkForEnqueue)
                                       .build();
        queues.start();
        return queues;
    }
}
