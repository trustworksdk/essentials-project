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
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.*;

import java.time.Duration;
import java.util.*;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * What the split adds on top of the SPI, and therefore what {@link PostgresqlSplitDurableQueuesIT}'s shared suite
 * cannot see: that messages physically land in the right table, that the composite's bookkeeping survives a batch
 * mixing both modes, and that one consumer drains both tables.
 * <p>
 * The shared suite passing proves the split is <em>indistinguishable</em> through the SPI. These tests prove the
 * split is actually happening — without them a composite that quietly put everything in one table would pass
 * every other test in this module.
 */
@Testcontainers
class PostgresqlSplitDurableQueuesRoutingIT {

    private static final String BASE_TABLE_NAME = "split_routing_queues";

    @Container
    private static final PostgreSQLContainer<?> postgreSQLContainer = EssentialsTestContainers.postgres("split-routing-db");

    private JdbiUnitOfWorkFactory        unitOfWorkFactory;
    private PostgresqlSplitDurableQueues durableQueues;

    @BeforeEach
    void setUp() {
        unitOfWorkFactory = new JdbiUnitOfWorkFactory(Jdbi.create(postgreSQLContainer.getJdbcUrl(),
                                                                  postgreSQLContainer.getUsername(),
                                                                  postgreSQLContainer.getPassword()));
        unitOfWorkFactory.usingUnitOfWork(uow -> {
            uow.handle().execute("DROP TABLE IF EXISTS " + BASE_TABLE_NAME + PostgresqlSplitDurableQueues.UNORDERED_TABLE_SUFFIX);
            uow.handle().execute("DROP TABLE IF EXISTS " + BASE_TABLE_NAME + PostgresqlSplitDurableQueues.ORDERED_TABLE_SUFFIX);
        });
        durableQueues = PostgresqlSplitDurableQueues.builder()
                                                    .setUnitOfWorkFactory(unitOfWorkFactory)
                                                    .setJsonSerializer(EssentialsObjectMappers.createJSONSerializer())
                                                    .setBaseQueueTableName(BASE_TABLE_NAME)
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
    void an_ordered_message_lands_in_the_ordered_table_and_a_plain_one_in_the_unordered_table() {
        var queueName = QueueName.of("Routing");

        durableQueues.queueMessage(queueName, Message.of("plain"));
        durableQueues.queueMessage(queueName, OrderedMessage.of("ordered", "key-a", 0L));

        assertThat(rowCountOf(durableQueues.getUnorderedTableName())).isEqualTo(1L);
        assertThat(rowCountOf(durableQueues.getOrderedTableName())).isEqualTo(1L);
        // And the SPI cannot tell: the counts it reports are the merged ones.
        assertThat(durableQueues.getTotalMessagesQueuedFor(queueName)).isEqualTo(2L);
    }

    /**
     * A batch mixing both modes is split across two tables, but callers correlate the returned ids to their
     * messages <b>positionally</b>. Regrouping them by table - the obvious implementation - silently pairs every
     * id with the wrong message.
     */
    @Test
    void a_mixed_batch_returns_its_ids_in_the_order_the_caller_supplied_the_messages() {
        var queueName = QueueName.of("MixedBatch");
        var messages = List.<Message>of(Message.of("u0"),
                                        OrderedMessage.of("o1", "key-a", 0L),
                                        Message.of("u2"),
                                        OrderedMessage.of("o3", "key-a", 1L),
                                        OrderedMessage.of("o4", "key-b", 0L));

        var ids = durableQueues.queueMessages(queueName, messages);

        assertThat(ids).hasSize(messages.size());
        assertThat(ids).doesNotContainNull();
        for (var i = 0; i < messages.size(); i++) {
            var queued = durableQueues.getQueuedMessage(ids.get(i));
            assertThat(queued).as("id at index %d must resolve", i).isPresent();
            assertThat(queued.get().getPayload())
                    .as("the id at index %d must belong to the message the caller put there", i)
                    .isEqualTo(messages.get(i).getPayload());
        }

        assertThat(rowCountOf(durableQueues.getUnorderedTableName())).isEqualTo(2L);
        assertThat(rowCountOf(durableQueues.getOrderedTableName())).isEqualTo(3L);
    }

    /**
     * By-id operations have to try both tables, because a {@link QueueEntryId} carries no delivery mode. Asserted
     * on the ordered table specifically: it is the one the dual lookup tries second, so an implementation that
     * only ever asked the unordered store would pass a test that used a plain message.
     */
    @Test
    void an_ordered_message_can_be_found_and_acknowledged_by_id_alone() {
        var queueName = QueueName.of("ById");
        var id        = durableQueues.queueMessage(queueName, OrderedMessage.of("ordered", "key-a", 0L));

        assertThat(durableQueues.getQueuedMessage(id)).isPresent();
        assertThat(durableQueues.getQueueNameFor(id)).contains(queueName);

        assertThat(durableQueues.acknowledgeMessageAsHandled(id)).isTrue();
        assertThat(rowCountOf(durableQueues.getOrderedTableName())).isZero();
    }

    /**
     * One consumer, one {@code parallelConsumers} budget, both tables. The single fetcher owned by the composite
     * is what makes this hold - a fetcher per delegate would give each its own budget and double the in-flight
     * work.
     */
    @Test
    void a_single_consumer_drains_both_tables_and_delivers_each_message_once() {
        var queueName = QueueName.of("OneConsumer");
        var expected  = new ArrayList<String>();
        for (var i = 0; i < 20; i++) {
            durableQueues.queueMessage(queueName, Message.of("plain-" + i));
            expected.add("plain-" + i);
            durableQueues.queueMessage(queueName, OrderedMessage.of("ordered-" + i, "key-" + (i % 4), i));
            expected.add("ordered-" + i);
        }

        var received = new CopyOnWriteArrayList<String>();
        durableQueues.consumeFromQueue(ConsumeFromQueue.builder()
                                                       .setQueueName(queueName)
                                                       .setRedeliveryPolicy(RedeliveryPolicy.fixedBackoff()
                                                                                            .setRedeliveryDelay(Duration.ofMillis(100))
                                                                                            .setMaximumNumberOfRedeliveries(3)
                                                                                            .build())
                                                       .setParallelConsumers(3)
                                                       .setQueueMessageHandler(message -> received.add((String) message.getPayload()))
                                                       .build());

        Awaitility.waitAtMost(Duration.ofSeconds(20))
                  .untilAsserted(() -> assertThat(received).hasSize(expected.size()));

        // Exactly once, not merely eventually: a dual-table drain is exactly where a message could be handed out
        // by both paths.
        assertThat(received).containsExactlyInAnyOrderElementsOf(expected);
        assertThat(rowCountOf(durableQueues.getUnorderedTableName())).isZero();
        assertThat(rowCountOf(durableQueues.getOrderedTableName())).isZero();
    }

    private long rowCountOf(String tableName) {
        return unitOfWorkFactory.withUnitOfWork(uow -> uow.handle()
                                                          .createQuery("SELECT count(*) FROM " + tableName)
                                                          .mapTo(Long.class)
                                                          .one());
    }
}
