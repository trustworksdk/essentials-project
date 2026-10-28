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

package dk.trustworks.essentials.components.distributed.fencedlock.springdata.mongo;

import dk.trustworks.essentials.components.foundation.test.fencedlock.DBFencedLockManagerIT;
import dk.trustworks.essentials.components.foundation.transaction.spring.mongo.SpringMongoTransactionAwareUnitOfWorkFactory;
import dk.trustworks.essentials.components.foundation.test.EssentialsTestContainers;
import org.bson.Document;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.mongodb.test.autoconfigure.DataMongoTest;
import org.springframework.data.mongodb.*;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.test.context.*;
import org.testcontainers.mongodb.MongoDBContainer;
import org.testcontainers.junit.jupiter.*;

import java.time.Duration;
import java.util.*;
import java.util.stream.*;

import static org.assertj.core.api.Assertions.assertThat;

@Testcontainers
@DataMongoTest
class MongoFencedLockManagerIT extends DBFencedLockManagerIT<MongoFencedLockManager> {
    @Container
    static MongoDBContainer mongoDBContainer = new MongoDBContainer(EssentialsTestContainers.MONGO_IMAGE).withReplicaSet();

    @DynamicPropertySource
    static void setProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.mongodb.uri", mongoDBContainer::getReplicaSetUrl);
    }

    /**
     * {@code verify_loosing_db_connection_no_locks_are_released} pauses the database while a lock confirmation is in flight.
     * A commit that fails during the pause can leave its write in a transaction the server holds open - with the lock
     * document write-locked, so node 2's acquire and the next test's {@code deleteAllLocksInDB} get a WriteConflict -
     * until transactionLifetimeLimitSeconds (60 s by default) aborts it. Shortened here, as in
     * {@link MongoFencedLockManager_MultiNode_ReleaseLockIT}, so the test does not wait out a minute.
     */
    @BeforeAll
    static void shortenAbandonedTransactionLifetime() throws Exception {
        var result = mongoDBContainer.execInContainer("mongosh", "--quiet", "--eval",
                                                      "db.adminCommand({setParameter: 1, transactionLifetimeLimitSeconds: 2}).ok");
        if (result.getExitCode() != 0 || !result.getStdout().trim().equals("1")) {
            throw new IllegalStateException("Failed to set transactionLifetimeLimitSeconds: " + result.getStdout() + result.getStderr());
        }
    }

    @Autowired
    private MongoTemplate mongoTemplate;

    @Autowired
    private MongoTransactionManager transactionManager;

    @Autowired
    private MongoDatabaseFactory databaseFactory;

    @Override
    protected MongoFencedLockManager createLockManagerNode2() {
        return new MongoFencedLockManager(mongoTemplate,
                                          new SpringMongoTransactionAwareUnitOfWorkFactory(transactionManager,
                                                                                           databaseFactory),
                                          Optional.of("node2"),
                                          Duration.ofSeconds(3),
                                          Duration.ofSeconds(1),
                                          false,
                                          Optional.empty());
    }

    @Override
    protected MongoFencedLockManager createLockManagerNode1() {
        return new MongoFencedLockManager(mongoTemplate,
                                          new SpringMongoTransactionAwareUnitOfWorkFactory(transactionManager,
                                                                                           databaseFactory),
                                          Optional.of("node1"),
                                          Duration.ofSeconds(3),
                                          Duration.ofSeconds(1),
                                          false,
                                          Optional.empty());
    }


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
    protected boolean isConnectionRestored() {
        try {
            var pingResult = mongoTemplate.executeCommand(new Document("ping", 1));
            return pingResult.getDouble("ok") == 1.0;
        } catch (Exception e) {
            return false;
        }
    }

    @Test
    void verify_indexes() {
        var indexes = mongoTemplate.getCollection(MongoFencedLockStorage.DEFAULT_FENCED_LOCKS_COLLECTION_NAME).listIndexes();
        var indexNames      = StreamSupport.stream(indexes.spliterator(), false).map(document -> (String) document.get("name")).collect(Collectors.toList());

        var allIndexes = List.of("_id_", "find_lock", "confirm_lock");
        assertThat(indexNames).containsAll(allIndexes);
        assertThat(allIndexes).containsAll(indexNames);
    }
}