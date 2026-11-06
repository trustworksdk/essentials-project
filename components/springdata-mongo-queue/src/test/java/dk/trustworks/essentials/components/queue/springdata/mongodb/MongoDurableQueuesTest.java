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
import dk.trustworks.essentials.components.foundation.mongo.InvalidCollectionNameException;
import org.junit.jupiter.api.Test;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.index.IndexOperations;

import java.time.Duration;
import java.util.List;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

class MongoDurableQueuesTest {
    /**
     * The stuck-message reset takes back claims at least one timeout old and runs at most once per timeout, so a claim
     * made just after a reset is taken back by the one after the next: up to two timeouts unserved. A handler may
     * still be overrun after one timeout.
     */
    @Test
    void a_dead_consumers_message_stays_unserved_for_up_to_two_message_handling_timeouts() {
        var mongoTemplate = mock(MongoTemplate.class);
        when(mongoTemplate.collectionExists(anyString())).thenReturn(true);
        var indexOperations = mock(IndexOperations.class);
        when(indexOperations.getIndexInfo()).thenReturn(List.of());
        when(mongoTemplate.indexOps(anyString())).thenReturn(indexOperations);

        var durableQueues = MongoDurableQueues.builder()
                                              .setMongoTemplate(mongoTemplate)
                                              .setMessageHandlingTimeout(Duration.ofSeconds(30))
                                              .setJsonSerializer(mock(JSONSerializer.class))
                                              .build();

        assertThat(durableQueues.maxUnservedAfterInstanceLoss()).contains(Duration.ofSeconds(60));
        assertThat(durableQueues.messageHandlingTimeout()).contains(Duration.ofSeconds(30));
    }

    @Test
    void initializeWithInvalidOverriddenCollectionName() {
        assertThatThrownBy(() ->
                                   MongoDurableQueues.builder()
                                                      .setMongoTemplate(mock(MongoTemplate.class))
                                                      .setMessageHandlingTimeout(Duration.ofSeconds(30))
                                                      .setJsonSerializer(mock(JSONSerializer.class))
                                                      .setSharedQueueCollectionName("system.collection")
                                                      .build())
                .isInstanceOf(InvalidCollectionNameException.class);

        assertThatThrownBy(() ->
                                   MongoDurableQueues.builder()
                                                      .setMongoTemplate(mock(MongoTemplate.class))
                                                      .setMessageHandlingTimeout(Duration.ofSeconds(30))
                                                      .setJsonSerializer(mock(JSONSerializer.class))
                                                      .setSharedQueueCollectionName("my$_collection")
                                                      .build())
                .isInstanceOf(InvalidCollectionNameException.class);
        assertThatThrownBy(() ->
                                   MongoDurableQueues.builder()
                                                      .setMongoTemplate(mock(MongoTemplate.class))
                                                      .setMessageHandlingTimeout(Duration.ofSeconds(30))
                                                      .setJsonSerializer(mock(JSONSerializer.class))
                                                      .setSharedQueueCollectionName("collection\0name")
                                                      .build())
                .isInstanceOf(InvalidCollectionNameException.class);
        assertThatThrownBy(() ->
                                   MongoDurableQueues.builder()
                                                      .setMongoTemplate(mock(MongoTemplate.class))
                                                      .setMessageHandlingTimeout(Duration.ofSeconds(30))
                                                      .setJsonSerializer(mock(JSONSerializer.class))
                                                      .setSharedQueueCollectionName("Invalid Name With Spaces")
                                                      .build())
                .isInstanceOf(InvalidCollectionNameException.class);
    }
}
