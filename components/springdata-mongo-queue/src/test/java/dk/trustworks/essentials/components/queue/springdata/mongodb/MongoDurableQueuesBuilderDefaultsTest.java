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

import dk.trustworks.essentials.components.foundation.json.JSONSerializer;
import dk.trustworks.essentials.components.foundation.messaging.queue.TransactionalMode;
import dk.trustworks.essentials.components.foundation.transaction.spring.mongo.SpringMongoTransactionAwareUnitOfWorkFactory;
import org.junit.jupiter.api.Test;
import org.springframework.data.mongodb.core.*;
import org.springframework.data.mongodb.core.index.IndexOperations;

import java.time.Duration;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

/**
 * Pins {@link MongoDurableQueues.Builder}'s defaults, because they are a behaviour contract and not an implementation
 * detail: until 0.40.x this builder produced {@link TransactionalMode#FullyTransactional} while
 * {@code PostgresqlDurableQueues.builder()} produced {@link TransactionalMode#SingleOperationTransaction}, so the same
 * application code got different delivery semantics depending on which database it ran against. That divergence was
 * closed by moving the MongoDB side, and nothing but a test stops it drifting open again — the integration suites
 * branch on {@code getTransactionalMode()} rather than asserting it, so they pass either way.
 * <p>
 * A mocked {@link MongoTemplate} is enough here: construction only reads the collection name and calls
 * {@code collectionExists}/{@code indexOps}, and this test asserts on the resulting instance, not on any queue
 * operation.
 */
class MongoDurableQueuesBuilderDefaultsTest {

    private static MongoTemplate mockMongoTemplate() {
        var mongoTemplate = mock(MongoTemplate.class);
        when(mongoTemplate.collectionExists(anyString())).thenReturn(true);
        var indexOperations = mock(IndexOperations.class);
        when(indexOperations.getIndexInfo()).thenReturn(List.of());
        when(mongoTemplate.indexOps(anyString())).thenReturn(indexOperations);
        return mongoTemplate;
    }

    private static MongoDurableQueues.Builder minimalBuilder() {
        return MongoDurableQueues.builder()
                                 .setMongoTemplate(mockMongoTemplate())
                                 .setJsonSerializer(mock(JSONSerializer.class))
                                 .setSharedQueueCollectionName(MongoDurableQueues.DEFAULT_DURABLE_QUEUES_COLLECTION_NAME);
    }

    @Test
    void test_the_default_transactional_mode_is_SingleOperationTransaction_matching_the_postgresql_builder() {
        assertThat(minimalBuilder().build().getTransactionalMode())
                .as("MongoDurableQueues.builder() must default to the same TransactionalMode as "
                            + "PostgresqlDurableQueues.builder(); a divergence here means the same application code "
                            + "gets different delivery semantics per database")
                .isEqualTo(TransactionalMode.SingleOperationTransaction);
    }

    @Test
    void test_the_default_message_handling_timeout_is_thirty_seconds_matching_the_postgresql_builder() {
        // Asserted against the literal rather than against PostgresqlDurableQueues.DEFAULT_MESSAGE_HANDLING_TIMEOUT,
        // which this module cannot see — springdata-mongo-queue does not depend on postgresql-queue, and adding that
        // dependency to share a constant would be a far worse trade than restating 30 seconds here.
        assertThat(MongoDurableQueues.DEFAULT_MESSAGE_HANDLING_TIMEOUT).isEqualTo(Duration.ofSeconds(30));
    }

    @Test
    void test_the_default_mode_needs_no_unitOfWorkFactory() {
        // The point of the default: SingleOperationTransaction is usable with nothing but a MongoTemplate. Under the
        // previous FullyTransactional default this same call threw, because that mode requires a unitOfWorkFactory.
        assertThat(minimalBuilder().build()).isNotNull();
    }

    @Test
    void test_FullyTransactional_remains_available_and_is_honoured() {
        var durableQueues = minimalBuilder()
                .setTransactionalMode(TransactionalMode.FullyTransactional)
                .setUnitOfWorkFactory(mock(SpringMongoTransactionAwareUnitOfWorkFactory.class))
                .build();

        assertThat(durableQueues.getTransactionalMode()).isEqualTo(TransactionalMode.FullyTransactional);
    }
}
