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

import dk.trustworks.essentials.components.foundation.test.messaging.queue.DuplicateConsumptionDurableQueuesIT;
import dk.trustworks.essentials.components.foundation.transaction.spring.mongo.SpringMongoTransactionAwareUnitOfWorkFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.data.mongo.DataMongoTest;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.*;
import org.testcontainers.containers.MongoDBContainer;
import org.testcontainers.junit.jupiter.*;

import java.time.Duration;

/**
 * Integration tests for duplicate consumption using MongoDB DurableQueues.
 * <p>
 * This test verifies that no duplicate message consumption occurs when
 * multiple MongoDurableQueues instances compete for the same messages.
 * <p>
 * Uses {@link dk.trustworks.essentials.components.foundation.messaging.queue.TransactionalMode#SingleOperationTransaction}
 * mode to avoid MongoDB write conflicts that occur with FullyTransactional mode.<br>
 * In FullyTransactional mode, long-running transactions (due to processing delay) cause
 * write conflicts when multiple consumers try to fetch messages, leading to excessive
 * backoff and one consumer getting all messages.
 * <p>
 * Note: MongoDB only supports the Traditional MessageFetcher approach.
 */
@Testcontainers
@DataMongoTest
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_EACH_TEST_METHOD)
class MongoDuplicateConsumptionDurableQueuesIT extends DuplicateConsumptionDurableQueuesIT<MongoDurableQueues, SpringMongoTransactionAwareUnitOfWorkFactory.SpringMongoTransactionAwareUnitOfWork, SpringMongoTransactionAwareUnitOfWorkFactory> {

    @Container
    static MongoDBContainer mongoDBContainer = new MongoDBContainer("mongo:latest");

    @DynamicPropertySource
    static void setProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.data.mongodb.uri", mongoDBContainer::getReplicaSetUrl);
    }

    @Autowired
    private MongoTemplate mongoTemplate;

    @Override
    protected void disruptDatabaseConnection() {
        var dockerClient = mongoDBContainer.getDockerClient();
        dockerClient.pauseContainerCmd(mongoDBContainer.getContainerId()).exec();
    }

    @Override
    protected void restoreDatabaseConnection() {
        var dockerClient = mongoDBContainer.getDockerClient();
        dockerClient.unpauseContainerCmd(mongoDBContainer.getContainerId()).exec();
    }

    @Override
    protected MongoDurableQueues createDurableQueues(SpringMongoTransactionAwareUnitOfWorkFactory unitOfWorkFactory) {
        // Use SingleOperationTransaction mode with messageHandlingTimeout
        // This avoids write conflicts that occur with FullyTransactional mode
        return new MongoDurableQueues(mongoTemplate,
                                      Duration.ofMillis(getMessageHandlingTimeoutMs()));
    }

    @Override
    protected SpringMongoTransactionAwareUnitOfWorkFactory createUnitOfWorkFactory() {
        // Not needed for SingleOperationTransaction mode
        return null;
    }

    @Override
    protected void resetQueueStorage(SpringMongoTransactionAwareUnitOfWorkFactory unitOfWorkFactory) {
        mongoTemplate.dropCollection(MongoDurableQueues.DEFAULT_DURABLE_QUEUES_COLLECTION_NAME);
    }
}
