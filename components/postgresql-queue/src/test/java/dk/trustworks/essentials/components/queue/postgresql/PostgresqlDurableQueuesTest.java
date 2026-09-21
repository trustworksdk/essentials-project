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

import dk.trustworks.essentials.components.foundation.json.JSONSerializer;
import dk.trustworks.essentials.components.foundation.messaging.queue.TransactionalMode;
import dk.trustworks.essentials.components.foundation.postgresql.*;
import dk.trustworks.essentials.components.foundation.transaction.jdbi.HandleAwareUnitOfWorkFactory;
import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.mock;

class PostgresqlDurableQueuesTest {

    @Test
    void initializeWithDefaultTableName() {
        var durableQueues = PostgresqlDurableQueues.builder()
                                           .setUnitOfWorkFactory(mock(HandleAwareUnitOfWorkFactory.class))
                                           .setJsonSerializer(mock(JSONSerializer.class))
                                           .setSharedQueueTableName(PostgresqlDurableQueues.DEFAULT_DURABLE_QUEUES_TABLE_NAME)
                                           .setTransactionalMode(TransactionalMode.FullyTransactional)
                                           .setMessageHandlingTimeout(Duration.ofSeconds(30))
                                           .build();
        assertThat(durableQueues.getSharedQueueTableName()).isEqualTo(PostgresqlDurableQueues.DEFAULT_DURABLE_QUEUES_TABLE_NAME);
    }

    @Test
    void initializeWithOverriddenTableName() {
        var overriddenTableName = "overridden_table_name";
        var durableQueues = PostgresqlDurableQueues.builder()
                                           .setUnitOfWorkFactory(mock(HandleAwareUnitOfWorkFactory.class))
                                           .setJsonSerializer(mock(JSONSerializer.class))
                                           .setSharedQueueTableName(overriddenTableName)
                                           .setTransactionalMode(TransactionalMode.FullyTransactional)
                                           .setMessageHandlingTimeout(Duration.ofSeconds(30))
                                           .build();
        assertThat(durableQueues.getSharedQueueTableName()).isEqualTo(overriddenTableName);
    }

    @Test
    void initializeWithInvalidOverriddenTableName() {
        assertThatThrownBy(() ->
                                   PostgresqlDurableQueues.builder()
                                           .setUnitOfWorkFactory(mock(HandleAwareUnitOfWorkFactory.class))
                                           .setJsonSerializer(mock(JSONSerializer.class))
                                           .setSharedQueueTableName("where")
                                           .setTransactionalMode(TransactionalMode.FullyTransactional)
                                           .setMessageHandlingTimeout(Duration.ofSeconds(30))
                                           .build())
                .isInstanceOf(InvalidTableOrColumnNameException.class);

        assertThatThrownBy(() ->
                                   PostgresqlDurableQueues.builder()
                                           .setUnitOfWorkFactory(mock(HandleAwareUnitOfWorkFactory.class))
                                           .setJsonSerializer(mock(JSONSerializer.class))
                                           .setSharedQueueTableName("OR 1=1")
                                           .setTransactionalMode(TransactionalMode.FullyTransactional)
                                           .setMessageHandlingTimeout(Duration.ofSeconds(30))
                                           .build())
                .isInstanceOf(InvalidTableOrColumnNameException.class);
    }
}
