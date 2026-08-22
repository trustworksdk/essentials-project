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
import dk.trustworks.essentials.components.queue.postgresql.test_data.*;
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
 * Determines empirically whether {@link OrderedMessage} ordering actually holds across two
 * {@link PostgresqlDurableQueues} instances sharing one database — i.e. two cluster nodes consuming the same
 * queue as competing consumers, with no {@code SingleGlobalConsumer} / {@code FencedLock} coordination.
 *
 * <h2>Why this test exists</h2>
 * {@link DurableQueues}' javadoc states that in this configuration ordering is <em>not</em> guaranteed, and
 * gives a worked example: "Node 1 might fetch and process {@code Order-123:event-5} while Node 2
 * simultaneously fetches and processes {@code Order-123:event-3}".
 * <p>
 * That example is hard to reconcile with the SQL in the tree. Both fetch strategies gate every ordered
 * candidate on
 * <pre>{@code NOT EXISTS (SELECT 1 FROM q2 WHERE q2.key = q1.key AND q2.queue_name = q1.queue_name AND q2.key_order < q1.key_order)}</pre>
 * which carries no predicate on {@code q2}'s state. While node 2 holds {@code event-3}, that row still
 * exists, so {@code event-5} ought to be blocked for <em>every</em> node — not merely for the node already
 * busy with the key. Either the documentation predates that barrier, or the barrier has a hole.
 * <p>
 * The answer decides whether a queue redesign needs to solve cross-node ordering at all, so it is worth
 * establishing by experiment rather than by reading.
 *
 * <h2>What is asserted</h2>
 * Two independent invariants, because they fail differently:
 * <ul>
 *     <li><b>No overlap</b> — two messages sharing a key are never being handled at the same instant. This is
 *     the invariant the javadoc's example violates.</li>
 *     <li><b>Monotonic order</b> — per key, handler entry happens in increasing {@code key_order}. A run can
 *     satisfy no-overlap and still violate this if messages are handled strictly sequentially but out of
 *     sequence.</li>
 * </ul>
 * Each node gets its own {@link Jdbi}, so the two behave as separate processes against a shared database
 * rather than as two objects sharing a connection pool.
 */
@Testcontainers
class PostgresqlOrderedMessagesMultiNodeIT {

    private static final int      KEY_COUNT           = 3;
    private static final int      MESSAGES_PER_KEY    = 40;
    private static final int      PARALLEL_CONSUMERS  = 20;
    /**
     * Long enough that a genuine same-key overlap is wide enough to observe reliably, short enough that the
     * whole run stays far inside the 30s default {@code messageHandlingTimeout} — a stuck-message reset
     * mid-run would produce redeliveries and muddy the result.
     */
    private static final Duration HANDLER_DURATION    = Duration.ofMillis(5);

    @Container
    private static final PostgreSQLContainer<?> postgreSQLContainer = EssentialsTestContainers.postgres("ordered-multinode-queue-db");

    private JdbiUnitOfWorkFactory   node1UnitOfWorkFactory;
    private JdbiUnitOfWorkFactory   node2UnitOfWorkFactory;
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

    @ParameterizedTest(name = "useCentralizedMessageFetcher={0}")
    @ValueSource(booleans = {true, false})
    void ordered_messages_for_one_key_are_never_handled_concurrently_by_two_nodes(boolean useCentralizedMessageFetcher) {
        node1UnitOfWorkFactory = new JdbiUnitOfWorkFactory(newJdbi());
        node2UnitOfWorkFactory = new JdbiUnitOfWorkFactory(newJdbi());
        node1 = createNode(node1UnitOfWorkFactory, useCentralizedMessageFetcher);
        node2 = createNode(node2UnitOfWorkFactory, useCentralizedMessageFetcher);

        var queueName = QueueName.of("MultiNodeOrderedQueue");

        // Every message is queued before either node starts consuming, so both nodes see a full backlog and
        // the whole key range is contended from the first poll.
        var messages = new ArrayList<Message>(KEY_COUNT * MESSAGES_PER_KEY);
        for (var order = 0; order < MESSAGES_PER_KEY; order++) {
            for (var key = 0; key < KEY_COUNT; key++) {
                messages.add(OrderedMessage.of(new OrderEvent.OrderAccepted(OrderId.random()), "key-" + key, order));
            }
        }
        node1UnitOfWorkFactory.usingUnitOfWork(unitOfWork -> node1.queueMessages(queueName, messages));

        var handled = new ConcurrentLinkedQueue<Handled>();
        var consumer1 = consume(node1, queueName, "node-1", handled);
        var consumer2 = consume(node2, queueName, "node-2", handled);

        try {
            Awaitility.waitAtMost(Duration.ofMinutes(2))
                      .pollInterval(Duration.ofMillis(200))
                      .untilAsserted(() -> assertThat(handled).hasSize(KEY_COUNT * MESSAGES_PER_KEY));
        } finally {
            consumer1.cancel();
            consumer2.cancel();
        }

        var handledByKey = handled.stream().collect(Collectors.groupingBy(Handled::key));
        assertThat(handledByKey).hasSize(KEY_COUNT);

        // Both nodes must actually have participated, otherwise this degenerates into a single-node test and
        // proves nothing about cross-node behaviour.
        var nodesThatHandled = handled.stream().map(Handled::node).collect(Collectors.toSet());
        assertThat(nodesThatHandled).as("both nodes must consume, or the run is not exercising cross-node behaviour")
                                    .containsExactlyInAnyOrder("node-1", "node-2");

        var overlaps    = findOverlaps(handledByKey);
        var outOfOrders = new ArrayList<String>();
        handledByKey.forEach((key, handledForKey) -> {
            var byStart = handledForKey.stream()
                                       .sorted(Comparator.comparingLong(Handled::startNanos))
                                       .toList();
            for (var i = 1; i < byStart.size(); i++) {
                var previous = byStart.get(i - 1);
                var current  = byStart.get(i);
                if (current.order() <= previous.order()) {
                    outOfOrders.add("%s: order %d on %s was handled after order %d on %s".formatted(
                            key, current.order(), current.node(), previous.order(), previous.node()));
                }
            }
        });

        assertThat(overlaps).as("two messages sharing a key were handled concurrently across nodes").isEmpty();
        assertThat(outOfOrders).as("messages for a key were handled out of key_order across nodes").isEmpty();
    }

    /**
     * Negative control, and a documented limitation in its own right.
     * <p>
     * The ordered barrier compares {@code q2.key_order < q1.key_order} — <em>strictly</em> less than — and
     * nothing enforces uniqueness of {@code (queue_name, key, key_order)}. Two messages queued with the same
     * key <em>and</em> the same order therefore never block each other, and both are eligible at once.
     * <p>
     * Its job here is to prove the overlap detector in the test above can actually fire. A green result there
     * is only meaningful if a genuine violation would have been caught, and this establishes that against the
     * same recording and assertion machinery.
     */
    @Test
    void control_duplicate_key_order_is_not_serialised_which_proves_the_detector_fires() {
        node1UnitOfWorkFactory = new JdbiUnitOfWorkFactory(newJdbi());
        node2UnitOfWorkFactory = new JdbiUnitOfWorkFactory(newJdbi());
        node1 = createNode(node1UnitOfWorkFactory, true);
        node2 = createNode(node2UnitOfWorkFactory, true);

        var queueName = QueueName.of("MultiNodeDuplicateOrderQueue");

        // Every message for a key carries order 0, so the strict '<' barrier never blocks any of them.
        var messages = new ArrayList<Message>();
        for (var i = 0; i < MESSAGES_PER_KEY; i++) {
            for (var key = 0; key < KEY_COUNT; key++) {
                messages.add(OrderedMessage.of(new OrderEvent.OrderAccepted(OrderId.random()), "dup-key-" + key, 0L));
            }
        }
        node1UnitOfWorkFactory.usingUnitOfWork(unitOfWork -> node1.queueMessages(queueName, messages));

        var handled = new ConcurrentLinkedQueue<Handled>();
        // A wider handler window than the ordered test uses, so the overlap is unambiguous rather than
        // marginal - this test asserts that overlap DOES occur, so it must not be a photo finish.
        var consumer1 = consume(node1, queueName, "node-1", handled, Duration.ofMillis(50));
        var consumer2 = consume(node2, queueName, "node-2", handled, Duration.ofMillis(50));

        try {
            Awaitility.waitAtMost(Duration.ofMinutes(2))
                      .pollInterval(Duration.ofMillis(200))
                      .untilAsserted(() -> assertThat(handled).hasSize(KEY_COUNT * MESSAGES_PER_KEY));
        } finally {
            consumer1.cancel();
            consumer2.cancel();
        }

        var overlaps = findOverlaps(handled.stream().collect(Collectors.groupingBy(Handled::key)));
        assertThat(overlaps).as("duplicate (key, key_order) messages should NOT be serialised - if this is empty, "
                                       + "the overlap detector cannot fire and the ordered test above proves nothing")
                            .isNotEmpty();
    }

    private DurableQueueConsumer consume(PostgresqlDurableQueues node,
                                         QueueName queueName,
                                         String nodeName,
                                         Queue<Handled> handled) {
        return consume(node, queueName, nodeName, handled, HANDLER_DURATION);
    }

    private DurableQueueConsumer consume(PostgresqlDurableQueues node,
                                         QueueName queueName,
                                         String nodeName,
                                         Queue<Handled> handled,
                                         Duration handlerDuration) {
        return node.consumeFromQueue(ConsumeFromQueue.builder()
                                                     .setQueueName(queueName)
                                                     .setConsumerName(nodeName)
                                                     .setRedeliveryPolicy(RedeliveryPolicy.fixedBackoff(Duration.ofMillis(200), 3))
                                                     .setParallelConsumers(PARALLEL_CONSUMERS)
                                                     .setQueueMessageHandler(queuedMessage -> {
                                                         var orderedMessage = (OrderedMessage) queuedMessage.getMessage();
                                                         var start          = System.nanoTime();
                                                         try {
                                                             Thread.sleep(handlerDuration.toMillis());
                                                         } catch (InterruptedException e) {
                                                             Thread.currentThread().interrupt();
                                                             throw new IllegalStateException("Interrupted while simulating handler work", e);
                                                         }
                                                         handled.add(new Handled(orderedMessage.getKey(),
                                                                                 orderedMessage.getOrder(),
                                                                                 nodeName,
                                                                                 start,
                                                                                 System.nanoTime()));
                                                     })
                                                     .build());
    }

    private PostgresqlDurableQueues createNode(JdbiUnitOfWorkFactory unitOfWorkFactory, boolean useCentralizedMessageFetcher) {
        var durableQueues = PostgresqlDurableQueues.builder()
                                                   .setUnitOfWorkFactory(unitOfWorkFactory)
                                                   .setJsonSerializer(EssentialsObjectMappers.createJSONSerializer())
                                                   .setUseCentralizedMessageFetcher(useCentralizedMessageFetcher)
                                                   .build();
        durableQueues.start();
        return durableQueues;
    }

    private static Jdbi newJdbi() {
        return Jdbi.create(postgreSQLContainer.getJdbcUrl(),
                           postgreSQLContainer.getUsername(),
                           postgreSQLContainer.getPassword());
    }

    /**
     * Every pair of same-key handlings whose windows intersect in wall-clock time. Shared by the ordered test
     * (which requires this to be empty) and the control (which requires it not to be), so both are judged by
     * exactly the same logic.
     */
    private static List<String> findOverlaps(Map<String, List<Handled>> handledByKey) {
        var overlaps = new ArrayList<String>();
        handledByKey.forEach((key, handledForKey) -> {
            var byStart = handledForKey.stream()
                                       .sorted(Comparator.comparingLong(Handled::startNanos))
                                       .toList();
            for (var i = 1; i < byStart.size(); i++) {
                var previous = byStart.get(i - 1);
                var current  = byStart.get(i);
                if (current.startNanos() < previous.endNanos()) {
                    overlaps.add("%s: order %d on %s overlapped order %d on %s".formatted(
                            key, current.order(), current.node(), previous.order(), previous.node()));
                }
            }
        });
        return overlaps;
    }

    private record Handled(String key, long order, String node, long startNanos, long endNanos) {
    }
}
