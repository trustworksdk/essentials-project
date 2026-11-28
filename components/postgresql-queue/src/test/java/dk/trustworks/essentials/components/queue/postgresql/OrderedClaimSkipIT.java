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
import dk.trustworks.essentials.components.foundation.messaging.RedeliveryPolicy;
import dk.trustworks.essentials.components.foundation.messaging.queue.*;
import dk.trustworks.essentials.components.foundation.messaging.queue.operations.ConsumeFromQueue;
import dk.trustworks.essentials.components.foundation.test.EssentialsTestContainers;
import dk.trustworks.essentials.components.foundation.transaction.jdbi.JdbiUnitOfWorkFactory;
import org.awaitility.Awaitility;
import org.jdbi.v3.core.Jdbi;
import org.junit.jupiter.api.*;
import org.testcontainers.junit.jupiter.*;
import org.testcontainers.postgresql.PostgreSQLContainer;

import java.time.Duration;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * A queue that holds no ordered message stops running the ordered claim on each poll ({@link OrderedClaimSkip}), and
 * an ordered message still reaches its consumer: at once when this node queued or resurrected it, within the re-check
 * interval when another node queued it.
 */
@Testcontainers
class OrderedClaimSkipIT {
    private static final String    QUEUE_TABLE = "ordered_claim_skip_queue";
    private static final QueueName QUEUE       = QueueName.of("OrderedClaimSkipQueue");

    @Container
    private static final PostgreSQLContainer postgreSQLContainer = EssentialsTestContainers.postgres("ordered-claim-skip-db");

    private JdbiUnitOfWorkFactory          consumingNodeUnitOfWorkFactory;
    private PostgresqlDurableQueues        consumingNode;
    private JdbiUnitOfWorkFactory          otherNodeUnitOfWorkFactory;
    private PostgresqlDurableQueues        otherNode;
    private DurableQueueConsumer           consumer;
    private final CopyOnWriteArrayList<Object> received = new CopyOnWriteArrayList<>();

    @BeforeEach
    void setUp() {
        consumingNodeUnitOfWorkFactory = unitOfWorkFactory();
        consumingNode = node(consumingNodeUnitOfWorkFactory);
        otherNodeUnitOfWorkFactory = unitOfWorkFactory();
        otherNode = node(otherNodeUnitOfWorkFactory);
        consumingNode.purgeQueue(QUEUE);
    }

    @AfterEach
    void tearDown() {
        if (consumer != null) {
            consumer.cancel();
        }
        consumingNode.stop();
        otherNode.stop();
    }

    @Test
    void a_queue_without_ordered_messages_skips_the_ordered_claim() {
        startConsumer();
        queueUnordered("unordered-1");
        Awaitility.waitAtMost(Duration.ofSeconds(5)).until(() -> consumingNode.isSkippingOrderedClaim(QUEUE));
        Awaitility.waitAtMost(Duration.ofSeconds(5)).until(() -> received.contains("unordered-1"));
    }

    @Test
    void an_ordered_message_queued_by_this_node_is_delivered_without_waiting_for_the_recheck() {
        consumingNode.setOrderedClaimSkipRecheckInterval(Duration.ofMinutes(1));
        startConsumer();
        Awaitility.waitAtMost(Duration.ofSeconds(5)).until(() -> consumingNode.isSkippingOrderedClaim(QUEUE));

        consumingNodeUnitOfWorkFactory.usingUnitOfWork(() -> consumingNode.queueMessage(QUEUE, OrderedMessage.of("ordered-1", "key-1", 0)));

        Awaitility.waitAtMost(Duration.ofSeconds(2)).until(() -> received.contains("ordered-1"));
    }

    @Test
    void an_ordered_message_in_a_batch_queued_by_this_node_is_delivered_without_waiting_for_the_recheck() {
        consumingNode.setOrderedClaimSkipRecheckInterval(Duration.ofMinutes(1));
        startConsumer();
        Awaitility.waitAtMost(Duration.ofSeconds(5)).until(() -> consumingNode.isSkippingOrderedClaim(QUEUE));

        consumingNodeUnitOfWorkFactory.usingUnitOfWork(() -> consumingNode.queueMessages(QUEUE, java.util.List.of(Message.of("unordered-1"),
                                                                                                                   OrderedMessage.of("ordered-1", "key-1", 0))));

        Awaitility.waitAtMost(Duration.ofSeconds(2)).until(() -> received.contains("ordered-1"));
    }

    @Test
    void an_ordered_message_resurrected_by_this_node_is_delivered_without_waiting_for_the_recheck() {
        consumingNode.setOrderedClaimSkipRecheckInterval(Duration.ofMinutes(1));
        var deadLetter = consumingNodeUnitOfWorkFactory.withUnitOfWork(() -> consumingNode.queueMessageAsDeadLetterMessage(QUEUE, OrderedMessage.of("ordered-1", "key-1", 0),
                                                                                                                          new RuntimeException("test")));
        startConsumer();
        Awaitility.waitAtMost(Duration.ofSeconds(5)).until(() -> consumingNode.isSkippingOrderedClaim(QUEUE));

        consumingNodeUnitOfWorkFactory.usingUnitOfWork(() -> consumingNode.resurrectDeadLetterMessage(deadLetter, Duration.ZERO));

        Awaitility.waitAtMost(Duration.ofSeconds(2)).until(() -> received.contains("ordered-1"));
    }

    @Test
    void an_ordered_message_queued_by_another_node_is_delivered_within_the_recheck_interval() {
        consumingNode.setOrderedClaimSkipRecheckInterval(Duration.ofSeconds(1));
        startConsumer();
        Awaitility.waitAtMost(Duration.ofSeconds(5)).until(() -> consumingNode.isSkippingOrderedClaim(QUEUE));

        otherNodeUnitOfWorkFactory.usingUnitOfWork(() -> otherNode.queueMessage(QUEUE, OrderedMessage.of("ordered-1", "key-1", 0)));

        Awaitility.waitAtMost(Duration.ofSeconds(3)).until(() -> received.contains("ordered-1"));
        Awaitility.waitAtMost(Duration.ofSeconds(3)).until(() -> consumingNode.isSkippingOrderedClaim(QUEUE));
    }

    @Test
    void a_queue_with_an_ordered_message_waiting_for_a_retry_does_not_skip() {
        consumingNode.setOrderedClaimSkipRecheckInterval(Duration.ofMinutes(1));
        var attempts = new java.util.concurrent.atomic.AtomicInteger();
        consumer = consumingNode.consumeFromQueue(ConsumeFromQueue.builder()
                                                                  .setQueueName(QUEUE)
                                                                  .setRedeliveryPolicy(RedeliveryPolicy.fixedBackoff(Duration.ofSeconds(2), 3))
                                                                  .setParallelConsumers(1)
                                                                  .setQueueMessageHandler(message -> {
                                                                      if (attempts.incrementAndGet() == 1) {
                                                                          throw new IllegalStateException("fail once");
                                                                      }
                                                                      received.add(message.getPayload());
                                                                  })
                                                                  .build());
        consumingNodeUnitOfWorkFactory.usingUnitOfWork(() -> consumingNode.queueMessage(QUEUE, OrderedMessage.of("ordered-1", "key-1", 0)));
        Awaitility.waitAtMost(Duration.ofSeconds(2)).until(() -> attempts.get() == 1);

        assertThat(consumingNode.isSkippingOrderedClaim(QUEUE)).isFalse();
        Awaitility.waitAtMost(Duration.ofSeconds(5)).until(() -> received.contains("ordered-1"));
    }

    private void startConsumer() {
        consumer = consumingNode.consumeFromQueue(ConsumeFromQueue.builder()
                                                                  .setQueueName(QUEUE)
                                                                  .setRedeliveryPolicy(RedeliveryPolicy.fixedBackoff(Duration.ofMillis(100), 3))
                                                                  .setParallelConsumers(2)
                                                                  .setQueueMessageHandler(message -> received.add(message.getPayload()))
                                                                  .build());
    }

    private void queueUnordered(String payload) {
        consumingNodeUnitOfWorkFactory.usingUnitOfWork(() -> consumingNode.queueMessage(QUEUE, Message.of(payload)));
    }

    private static JdbiUnitOfWorkFactory unitOfWorkFactory() {
        return new JdbiUnitOfWorkFactory(Jdbi.create(postgreSQLContainer.getJdbcUrl(), postgreSQLContainer.getUsername(), postgreSQLContainer.getPassword()));
    }

    private static PostgresqlDurableQueues node(JdbiUnitOfWorkFactory unitOfWorkFactory) {
        var node = PostgresqlDurableQueues.builder()
                                          .setUnitOfWorkFactory(unitOfWorkFactory)
                                          .setJsonSerializer(EssentialsObjectMappers.createJSONSerializer())
                                          .setSharedQueueTableName(QUEUE_TABLE)
                                          .build();
        node.start();
        return node;
    }
}
