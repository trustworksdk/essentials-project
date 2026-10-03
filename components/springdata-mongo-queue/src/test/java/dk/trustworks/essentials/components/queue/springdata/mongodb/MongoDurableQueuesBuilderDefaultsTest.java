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
 * detail: an application that swaps database module gets the same delivery semantics only for as long as this builder
 * and {@code PostgresqlDurableQueues.builder()} agree.
 * <p>
 * The transactional-mode assertions this test used to carry are gone with {@code TransactionalMode} itself — every
 * queue operation now runs in its own transaction, so there is no longer a choice to diverge on.
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
    void test_the_default_message_handling_timeout_is_thirty_seconds_matching_the_postgresql_builder() {
        // Asserted against the literal rather than against PostgresqlDurableQueues.DEFAULT_MESSAGE_HANDLING_TIMEOUT,
        // which this module cannot see — springdata-mongo-queue does not depend on postgresql-queue, and adding that
        // dependency to share a constant would be a far worse trade than restating 30 seconds here.
        assertThat(MongoDurableQueues.DEFAULT_MESSAGE_HANDLING_TIMEOUT).isEqualTo(Duration.ofSeconds(30));
    }

    @Test
    void test_the_builder_needs_no_unitOfWorkFactory() {
        // Usable with nothing but a MongoTemplate. Under the pre-0.40.x FullyTransactional default this same call
        // threw, because that mode required a unitOfWorkFactory.
        assertThat(minimalBuilder().build()).isNotNull();
    }

}
