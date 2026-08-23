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
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.*;

import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The gate on the multi-queue batched claim path: does it stay correct under competing consumers?
 *
 * <h2>Why this test exists</h2>
 * {@code DurableQueuesSql.buildBatchedSqlStatement} and
 * {@code PostgresqlDurableQueues.fetchNextBatchOfMessagesBatched} were both marked "work in progress —
 * doesn't handle competing consumers yet", and the flag that reaches them
 * ({@link PostgresqlDurableQueuesBuilder#setUseBatchedFetch(boolean)}) is off by default because of it. The
 * SQL has since grown a defence: the candidate rows are chosen in a CTE without locking, and then a second
 * scan re-checks {@code is_being_delivered = FALSE} under {@code FOR UPDATE SKIP LOCKED} so that a row
 * claimed by another instance between the two steps is dropped rather than claimed twice.
 * <p>
 * Whether that defence works was, until this test, unestablished — the existing
 * {@link BatchedFetchStrategyIT} deliberately runs with no consumers at all, so it pins the statement's
 * semantics and says nothing about concurrency. A stale warning in a javadoc is not evidence either way, and
 * neither is the absence of one. So this establishes it by experiment.
 *
 * <h2>Shape</h2>
 * Two {@link PostgresqlDurableQueues} instances, each with its own {@link Jdbi}, so they behave as separate
 * processes against one database rather than two objects sharing a pool. Several queues, because a
 * single-queue run would not exercise the multi-queue statement at all. Every message is queued before
 * either instance starts consuming, so the whole backlog is contended from the first poll.
 * <p>
 * The test is parameterized over {@code useBatchedFetch} rather than run only on the batched path. The
 * per-queue path is the known-good control: if both fail the harness is wrong, and if only the batched one
 * fails the fault is localised to the statement under test. A green result on the batched path alone would
 * not distinguish "correct" from "assertions that cannot fail".
 *
 * <h2>What is asserted</h2>
 * <ul>
 *     <li><b>Nothing lost</b> — every queued message is handled, and every queue drains.</li>
 *     <li><b>Nothing duplicated</b> — no message is handled twice. At-least-once permits duplicates when a
 *     handler fails or exceeds {@code messageHandlingTimeout}, so the handler here is fast and never fails
 *     and the run stays far inside the 30s default. Any duplicate is therefore the claim path handing the
 *     same row to both instances, which is exactly Bug #19's symptom.</li>
 *     <li><b>Both instances participate</b> — otherwise the run degenerates into a single-instance test and
 *     proves nothing about competing consumers.</li>
 * </ul>
 */
@Testcontainers
class PostgresqlBatchedFetchCompetingConsumersIT {

    private static final int QUEUE_COUNT         = 4;
    private static final int MESSAGES_PER_QUEUE  = 150;
    private static final int PARALLEL_CONSUMERS  = 10;

    @Container
    private static final PostgreSQLContainer<?> postgreSQLContainer = EssentialsTestContainers.postgres("batched-fetch-competing-queue-db");

    private PostgresqlDurableQueues node1;
    private PostgresqlDurableQueues node2;

    @BeforeEach
    void setUp() {
        // The container is shared across test methods, so start from an empty table.
        new JdbiUnitOfWorkFactory(newJdbi()).usingUnitOfWork(unitOfWork -> unitOfWork.handle()
                                                                                     .execute("DROP TABLE IF EXISTS " + PostgresqlDurableQueues.DEFAULT_DURABLE_QUEUES_TABLE_NAME));
    }

    @AfterEach
    void tearDown() {
        if (node1 != null) node1.stop();
        if (node2 != null) node2.stop();
    }

    @ParameterizedTest(name = "useBatchedFetch={0}")
    @ValueSource(booleans = {true, false})
    void every_message_is_handled_exactly_once_across_two_competing_instances(boolean useBatchedFetch) {
        var node1UnitOfWorkFactory = new JdbiUnitOfWorkFactory(newJdbi());
        var node2UnitOfWorkFactory = new JdbiUnitOfWorkFactory(newJdbi());
        node1 = createNode(node1UnitOfWorkFactory, useBatchedFetch);
        node2 = createNode(node2UnitOfWorkFactory, useBatchedFetch);

        var queueNames = new ArrayList<QueueName>();
        for (var queue = 0; queue < QUEUE_COUNT; queue++) {
            queueNames.add(QueueName.of("BatchedFetchCompeting" + useBatchedFetch + "-" + queue));
        }

        var expectedPayloads = new HashSet<String>();
        for (var queueName : queueNames) {
            var messages = new ArrayList<Message>(MESSAGES_PER_QUEUE);
            for (var i = 0; i < MESSAGES_PER_QUEUE; i++) {
                var payload = queueName + "#" + i;
                expectedPayloads.add(payload);
                messages.add(Message.of(payload));
            }
            node1UnitOfWorkFactory.usingUnitOfWork(unitOfWork -> node1.queueMessages(queueName, messages));
        }

        var handled   = new ConcurrentLinkedQueue<Handled>();
        var consumers = new ArrayList<DurableQueueConsumer>();
        try {
            for (var queueName : queueNames) {
                consumers.add(consume(node1, queueName, "node-1", handled));
                consumers.add(consume(node2, queueName, "node-2", handled));
            }

            Awaitility.waitAtMost(Duration.ofMinutes(2))
                      .pollInterval(Duration.ofMillis(200))
                      .untilAsserted(() -> assertThat(handled).hasSize(QUEUE_COUNT * MESSAGES_PER_QUEUE));
        } finally {
            consumers.forEach(DurableQueueConsumer::cancel);
        }

        // Duplicates are reported by payload rather than by count alone, so a failure names the messages that
        // were double-claimed instead of only saying that some were.
        var handledCountsByPayload = handled.stream().collect(Collectors.groupingBy(Handled::payload, Collectors.counting()));
        var duplicates = handledCountsByPayload.entrySet().stream()
                                               .filter(entry -> entry.getValue() > 1)
                                               .map(entry -> entry.getKey() + " handled " + entry.getValue() + " times")
                                               .toList();
        assertThat(duplicates).as("no message may be handled twice - a duplicate here is the claim path handing "
                                          + "the same row to both instances (Bug #19's symptom)")
                              .isEmpty();

        assertThat(handledCountsByPayload.keySet()).as("every queued message must be handled exactly once")
                                                   .containsExactlyInAnyOrderElementsOf(expectedPayloads);

        var nodesThatHandled = handled.stream().map(Handled::node).collect(Collectors.toSet());
        assertThat(nodesThatHandled).as("both instances must consume, or the run is not exercising competing consumers")
                                    .containsExactlyInAnyOrder("node-1", "node-2");

        // And the table really is empty afterwards, so nothing was left claimed-but-unacknowledged.
        queueNames.forEach(queueName -> {
            long remaining = node1UnitOfWorkFactory.withUnitOfWork(unitOfWork -> node1.getTotalMessagesQueuedFor(queueName));
            assertThat(remaining).as("queue %s must be fully drained", queueName).isZero();
        });
    }

    /**
     * Negative control: proves the duplicate detector in the test above can actually fire.
     * <p>
     * A green "no duplicates" result is only meaningful if a genuine duplicate would have been caught by the
     * same recording and assertion machinery. This forces one the legitimate way — a handler that runs far
     * longer than {@code messageHandlingTimeout}, so {@code resetMessagesStuckBeingDelivered} decides the
     * in-flight message was abandoned and makes it claimable again while the first handler is still working.
     * That is at-least-once behaving as designed, not a bug; here it is used purely as a fault injector.
     * <p>
     * If this test ever goes green-with-no-duplicates, the assertion in the test above has stopped meaning
     * anything and both need revisiting.
     */
    @Test
    void control_a_handler_that_outlives_the_handling_timeout_produces_duplicates_which_proves_the_detector_fires() {
        var node1UnitOfWorkFactory = new JdbiUnitOfWorkFactory(newJdbi());
        var node2UnitOfWorkFactory = new JdbiUnitOfWorkFactory(newJdbi());
        // One second, against a handler that sleeps for three: the reset is certain to fire mid-handling.
        node1 = createNode(node1UnitOfWorkFactory, true, Duration.ofSeconds(1));
        node2 = createNode(node2UnitOfWorkFactory, true, Duration.ofSeconds(1));

        var queueName = QueueName.of("BatchedFetchDuplicateControl");
        var messages  = new ArrayList<Message>();
        for (var i = 0; i < 8; i++) {
            messages.add(Message.of(queueName + "#" + i));
        }
        node1UnitOfWorkFactory.usingUnitOfWork(unitOfWork -> node1.queueMessages(queueName, messages));

        var handled   = new ConcurrentLinkedQueue<Handled>();
        var consumers = List.of(consumeSlowly(node1, queueName, "node-1", handled),
                                consumeSlowly(node2, queueName, "node-2", handled));
        try {
            // Wait for more handlings than there are messages - that surplus IS the duplicate.
            Awaitility.waitAtMost(Duration.ofMinutes(2))
                      .pollInterval(Duration.ofMillis(200))
                      .untilAsserted(() -> assertThat(handled.size()).isGreaterThan(messages.size()));
        } finally {
            consumers.forEach(DurableQueueConsumer::cancel);
        }

        var duplicates = handled.stream()
                                .collect(Collectors.groupingBy(Handled::payload, Collectors.counting()))
                                .entrySet().stream()
                                .filter(entry -> entry.getValue() > 1)
                                .toList();
        assertThat(duplicates).as("a handler outliving the handling timeout must produce a duplicate - if this is "
                                          + "empty, the duplicate detector cannot fire and the test above proves nothing")
                              .isNotEmpty();
    }

    private DurableQueueConsumer consumeSlowly(PostgresqlDurableQueues node,
                                               QueueName queueName,
                                               String nodeName,
                                               Queue<Handled> handled) {
        return node.consumeFromQueue(ConsumeFromQueue.builder()
                                                     .setQueueName(queueName)
                                                     .setConsumerName(nodeName)
                                                     .setRedeliveryPolicy(RedeliveryPolicy.fixedBackoff(Duration.ofMillis(200), 3))
                                                     .setParallelConsumers(PARALLEL_CONSUMERS)
                                                     .setQueueMessageHandler(queuedMessage -> {
                                                         handled.add(new Handled(queuedMessage.getPayload().toString(), nodeName));
                                                         try {
                                                             Thread.sleep(3_000);
                                                         } catch (InterruptedException e) {
                                                             Thread.currentThread().interrupt();
                                                         }
                                                     })
                                                     .build());
    }

    private DurableQueueConsumer consume(PostgresqlDurableQueues node,
                                         QueueName queueName,
                                         String nodeName,
                                         Queue<Handled> handled) {
        return node.consumeFromQueue(ConsumeFromQueue.builder()
                                                     .setQueueName(queueName)
                                                     .setConsumerName(nodeName)
                                                     .setRedeliveryPolicy(RedeliveryPolicy.fixedBackoff(Duration.ofMillis(200), 3))
                                                     .setParallelConsumers(PARALLEL_CONSUMERS)
                                                     .setQueueMessageHandler(queuedMessage -> handled.add(
                                                             new Handled(queuedMessage.getPayload().toString(), nodeName)))
                                                     .build());
    }

    private PostgresqlDurableQueues createNode(JdbiUnitOfWorkFactory unitOfWorkFactory, boolean useBatchedFetch) {
        return createNode(unitOfWorkFactory, useBatchedFetch, PostgresqlDurableQueues.DEFAULT_MESSAGE_HANDLING_TIMEOUT);
    }

    private PostgresqlDurableQueues createNode(JdbiUnitOfWorkFactory unitOfWorkFactory,
                                               boolean useBatchedFetch,
                                               Duration messageHandlingTimeout) {
        var durableQueues = PostgresqlDurableQueues.builder()
                                                   .setMessageHandlingTimeout(messageHandlingTimeout)
                                                   .setUnitOfWorkFactory(unitOfWorkFactory)
                                                   .setJsonSerializer(EssentialsObjectMappers.createJSONSerializer())
                                                   .setUseCentralizedMessageFetcher(true)
                                                   .setUseBatchedFetch(useBatchedFetch)
                                                   // Threshold 0 so any non-empty set of active queues takes the
                                                   // batched statement. The default of 4 would let a run with
                                                   // exactly 4 queues fall back to per-queue fetching and quietly
                                                   // test the wrong path.
                                                   .setBatchedFetchSwitchThreshold(0)
                                                   .build();
        durableQueues.start();
        return durableQueues;
    }

    private static Jdbi newJdbi() {
        return Jdbi.create(postgreSQLContainer.getJdbcUrl(),
                           postgreSQLContainer.getUsername(),
                           postgreSQLContainer.getPassword());
    }

    private record Handled(String payload, String node) {
    }
}
