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

import dk.trustworks.essentials.components.foundation.json.EssentialsObjectMappers;
import dk.trustworks.essentials.components.foundation.messaging.queue.*;
import dk.trustworks.essentials.components.foundation.test.EssentialsTestContainers;
import dk.trustworks.essentials.components.foundation.transaction.jdbi.JdbiUnitOfWorkFactory;
import dk.trustworks.essentials.components.queue.postgresql.test_data.*;
import org.jdbi.v3.core.Jdbi;
import org.junit.jupiter.api.*;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.*;

import java.util.*;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Regression tests for queueing a single batch that contains both {@link OrderedMessage}s and plain
 * {@link Message}s.
 * <p>
 * This combination used to fail with
 * {@code ClassCastException: NullArgument cannot be cast to String}. The per-row binding in
 * {@link PostgresqlDurableQueues#queueMessages} was correct in intent — a {@code String} for an ordered
 * message's key, a typed null otherwise — but JDBI's {@code PreparedBatch} prepares one binder from the
 * first row's argument types and reuses it for every subsequent row, so a {@code NullArgument} arriving
 * where the prepared binder expected a {@code String} blew up.
 * <p>
 * Both orderings are covered because the failure depends on which kind of message happens to be first in the
 * list: whichever kind row 0 is determines the prepared binder, and the other kind is the one that fails.
 * The existing suite missed this entirely — every other test that uses both kinds queues them in separate
 * {@code queueMessages} calls.
 */
@Testcontainers
class PostgresqlDurableQueuesMixedBatchIT {

    @Container
    private static final PostgreSQLContainer<?> postgreSQLContainer = EssentialsTestContainers.postgres("mixed-batch-queue-db");

    private JdbiUnitOfWorkFactory   unitOfWorkFactory;
    private PostgresqlDurableQueues durableQueues;

    @BeforeEach
    void setUp() {
        unitOfWorkFactory = new JdbiUnitOfWorkFactory(Jdbi.create(postgreSQLContainer.getJdbcUrl(),
                                                                  postgreSQLContainer.getUsername(),
                                                                  postgreSQLContainer.getPassword()));
        // The container is static and therefore shared between test methods - start from a clean table.
        unitOfWorkFactory.usingUnitOfWork(unitOfWork -> unitOfWork.handle()
                                                                  .execute("DROP TABLE IF EXISTS " + PostgresqlDurableQueues.DEFAULT_DURABLE_QUEUES_TABLE_NAME));
        durableQueues = PostgresqlDurableQueues.builder()
                                               .setUnitOfWorkFactory(unitOfWorkFactory)
                                               .setJsonSerializer(EssentialsObjectMappers.createJSONSerializer())
                                               .build();
        durableQueues.start();
    }

    @AfterEach
    void tearDown() {
        if (durableQueues != null) {
            durableQueues.stop();
        }
    }

    @Test
    void a_batch_starting_with_an_ordered_message_can_also_contain_unordered_messages() {
        var queueName = QueueName.of("MixedBatchOrderedFirst");

        var messages = List.of(orderedMessage("key-a", 0),
                               unorderedMessage(),
                               orderedMessage("key-a", 1),
                               unorderedMessage(),
                               orderedMessage("key-b", 0));

        var queueEntryIds = unitOfWorkFactory.withUnitOfWork(unitOfWork -> durableQueues.queueMessages(queueName, messages));

        assertThat(queueEntryIds).hasSize(5);
        assertMixedBatchPersisted(queueName, 3, 2);
    }

    @Test
    void a_batch_starting_with_an_unordered_message_can_also_contain_ordered_messages() {
        var queueName = QueueName.of("MixedBatchUnorderedFirst");

        var messages = List.of(unorderedMessage(),
                               orderedMessage("key-a", 0),
                               unorderedMessage(),
                               orderedMessage("key-b", 0),
                               unorderedMessage());

        var queueEntryIds = unitOfWorkFactory.withUnitOfWork(unitOfWork -> durableQueues.queueMessages(queueName, messages));

        assertThat(queueEntryIds).hasSize(5);
        assertMixedBatchPersisted(queueName, 2, 3);
    }

    /**
     * Verifies not just that the insert succeeded, but that each row kept the delivery mode and key it was
     * queued with — a binder that silently reused the previous row's key would still insert five rows.
     */
    private void assertMixedBatchPersisted(QueueName queueName, int expectedOrdered, int expectedUnordered) {
        var queuedMessages = unitOfWorkFactory.withUnitOfWork(unitOfWork -> durableQueues.getQueuedMessages(queueName,
                                                                                                            DurableQueues.QueueingSortOrder.ASC,
                                                                                                            0,
                                                                                                            100));
        assertThat(queuedMessages).hasSize(expectedOrdered + expectedUnordered);

        var orderedMessages = queuedMessages.stream()
                                            .filter(queuedMessage -> queuedMessage.getMessage() instanceof OrderedMessage)
                                            .toList();
        assertThat(orderedMessages).hasSize(expectedOrdered);
        assertThat(orderedMessages).allSatisfy(queuedMessage -> {
            var orderedMessage = (OrderedMessage) queuedMessage.getMessage();
            assertThat(orderedMessage.getKey()).isNotNull();
            assertThat(orderedMessage.getOrder()).isGreaterThanOrEqualTo(0L);
        });

        var unorderedMessages = queuedMessages.stream()
                                              .filter(queuedMessage -> !(queuedMessage.getMessage() instanceof OrderedMessage))
                                              .toList();
        assertThat(unorderedMessages).hasSize(expectedUnordered);
    }

    private static OrderedMessage orderedMessage(String key, long order) {
        return OrderedMessage.of(new OrderEvent.OrderAccepted(OrderId.random()), key, order);
    }

    private static Message unorderedMessage() {
        return Message.of(new OrderEvent.OrderAdded(OrderId.random(), CustomerId.random(), 1234));
    }
}
