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

package dk.trustworks.essentials.components.queue.shardowned;

import com.zaxxer.hikari.*;
import dk.trustworks.essentials.components.queue.shardowned.spi.*;
import org.awaitility.Awaitility;
import org.junit.jupiter.api.*;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.*;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

import static org.assertj.core.api.Assertions.*;

/**
 * The three things an operator needs that this engine used to refuse: acting on a message by id,
 * reading a payload without a decoder, and changing a queue's shard count.
 */
@Testcontainers(disabledWithoutDocker = true)
class ShardOwnedAdminSurfaceIT {

    private static final short QUEUE_ID    = 1;
    private static final int   SHARD_COUNT = 4;

    @Container
    static PostgreSQLContainer<?> postgres = LabPostgres.create();

    private HikariDataSource dataSource;

    @BeforeEach
    void setUp() throws Exception {
        var config = new HikariConfig();
        config.setJdbcUrl(postgres.getJdbcUrl());
        config.setUsername(postgres.getUsername());
        config.setPassword(postgres.getPassword());
        config.setMaximumPoolSize(30);
        dataSource = new HikariDataSource(config);
        ShardOwnedSchema.recreate(dataSource);
        ShardOwnedSchema.registerQueue(dataSource, QUEUE_ID, SHARD_COUNT);
    }

    @AfterEach
    void tearDown() {
        if (dataSource != null) {
            dataSource.close();
        }
    }

    private PostgresqlMessageQueue queue(String instanceId) {
        return new PostgresqlMessageQueue(dataSource, QUEUE_ID, SHARD_COUNT, instanceId);
    }

    // ---------------------------------------------------------------- by id

    /**
     * The whole by-id family against a queue with no consumer, which is the state an operator
     * inspecting a backlog is actually in.
     */
    @Test
    void a_message_can_be_read_retried_dead_lettered_and_deleted_by_id() throws Exception {
        try (var queue = queue("admin-1")) {
            var ids = queue.enqueue(List.of(Message.of("read-me".getBytes(StandardCharsets.UTF_8), 42),
                                            Message.of("retry-me".getBytes(StandardCharsets.UTF_8), 1),
                                            Message.of("park-me".getBytes(StandardCharsets.UTF_8), 1),
                                            Message.of("delete-me".getBytes(StandardCharsets.UTF_8), 1),
                                            Message.ordered("ordered-me".getBytes(StandardCharsets.UTF_8), 9, "k", 0L)));

            var read = queue.getMessage(ids.get(0)).orElseThrow();
            assertThat(new String(read.payload(), StandardCharsets.UTF_8)).isEqualTo("read-me");
            assertThat(read.payloadType()).isEqualTo(42);
            assertThat(read.attempts()).isZero();
            assertThat(read.visibleAt()).isNotNull();

            var ordered = queue.getMessage(ids.get(4)).orElseThrow();
            assertThat(ordered.key()).describedAs("the ordered lane is addressable by id too").isEqualTo("k");
            assertThat(ordered.payloadType()).isEqualTo(9);

            assertThat(queue.retryMessage(ids.get(1), Duration.ofHours(1))).isTrue();
            assertThat(queue.getMessage(ids.get(1)).orElseThrow().visibleAt())
                    .describedAs("a retry moves visibility out by the delay asked for")
                    .isAfter(java.time.Instant.now().plus(Duration.ofMinutes(50)));

            assertThat(queue.markAsDeadLetter(ids.get(2), "operator parked it")).isTrue();
            assertThat(queue.getMessage(ids.get(2)))
                    .describedAs("a dead letter has left its lane")
                    .isEmpty();
            assertThat(queue.deadLetters(0, 10))
                    .anySatisfy(deadLetter -> assertThat(deadLetter.lastError()).isEqualTo("operator parked it"));

            assertThat(queue.deleteMessage(ids.get(3))).isTrue();
            assertThat(queue.getMessage(ids.get(3))).isEmpty();
            assertThat(queue.deleteMessage(ids.get(3)))
                    .describedAs("deleting what is already gone reports false rather than throwing")
                    .isFalse();
        }
    }

    @Test
    void by_id_operations_report_false_for_a_message_that_does_not_exist() throws Exception {
        try (var queue = queue("admin-2")) {
            var missing = new MessageId(MessageId.Lane.UNORDERED, 0, 999_999L);
            assertThat(queue.getMessage(missing)).isEmpty();
            assertThat(queue.deleteMessage(missing)).isFalse();
            assertThat(queue.retryMessage(missing, Duration.ZERO)).isFalse();
            assertThat(queue.markAsDeadLetter(missing, "nope")).isFalse();
        }
    }

    /**
     * Deleting by id while a consumer runs must not corrupt the owner. The engine already tolerates
     * an acknowledgement that matches no row — a fenced-out owner produces the same thing — so the
     * queue must keep working afterwards rather than stalling the shard.
     */
    @Test
    void deleting_by_id_under_a_live_consumer_leaves_the_queue_working() throws Exception {
        var delivered = ConcurrentHashMap.<String>newKeySet();
        try (var queue = queue("admin-3")) {
            queue.consume((key, payload, payloadType) -> delivered.add(new String(payload, StandardCharsets.UTF_8)),
                          ConsumerOptions.defaults());

            var ids = queue.enqueue(List.of(Message.of("first".getBytes(StandardCharsets.UTF_8), 1)));
            Awaitility.await().atMost(Duration.ofSeconds(20)).until(() -> delivered.contains("first"));

            // Already handled and acknowledged, so this is the no-op case; what matters is that the
            // engine keeps delivering afterwards.
            queue.deleteMessage(ids.getFirst());

            queue.enqueue(List.of(Message.of("second".getBytes(StandardCharsets.UTF_8), 1)));
            Awaitility.await().atMost(Duration.ofSeconds(20))
                      .untilAsserted(() -> assertThat(delivered)
                              .describedAs("the shard still delivers after an administrative delete")
                              .contains("second"));
        }
    }

    // ---------------------------------------------------------------- psql readability

    /**
     * Payloads are {@code bytea} so the write path stays cheap, which means {@code SELECT payload}
     * returns hex and an operator cannot read their own message. The views fix the ergonomics without
     * touching the storage.
     */
    @Test
    void payloads_are_readable_through_the_views() throws Exception {
        try (var queue = queue("admin-4")) {
            queue.enqueue(List.of(Message.of("{\"order\":\"1234\"}".getBytes(StandardCharsets.UTF_8), 1),
                                  Message.ordered("{\"ship\":\"9\"}".getBytes(StandardCharsets.UTF_8), 1, "k", 0L)));
            var parked = queue.enqueue(List.of(Message.of("poison".getBytes(StandardCharsets.UTF_8), 1)));
            queue.markAsDeadLetter(parked.getFirst(), "for the view");

            assertThat(readColumn(ShardOwnedSchema.UNORDERED_VIEW, "payload"))
                    .describedAs("JSON is valid UTF-8, so it renders as itself rather than as hex")
                    .contains("{\"order\":\"1234\"}");
            assertThat(readColumn(ShardOwnedSchema.ORDERED_VIEW, "payload")).contains("{\"ship\":\"9\"}");
            assertThat(readColumn(ShardOwnedSchema.DLQ_VIEW, "payload")).contains("poison");
        }
    }

    /** A payload that is not text must not break the view for every row beside it. */
    @Test
    void a_binary_payload_falls_back_to_hex_rather_than_failing_the_view() throws Exception {
        try (var queue = queue("admin-5")) {
            queue.enqueue(List.of(Message.of(new byte[]{(byte) 0xff, (byte) 0xfe, (byte) 0x00}, 1),
                                  Message.of("readable".getBytes(StandardCharsets.UTF_8), 1)));

            var payloads = readColumn(ShardOwnedSchema.UNORDERED_VIEW, "payload");
            assertThat(payloads).describedAs("the text row is unaffected by the binary one").contains("readable");
            assertThat(payloads).hasSize(2);
        }
    }

    // ---------------------------------------------------------------- shard count

    /**
     * The unordered lane can grow, because round-robin assignment means nothing depends on which
     * shard a message landed in.
     */
    @Test
    void an_unordered_queue_can_grow_its_shard_count() throws Exception {
        var name = QueueName.of("growable");
        var registered = ShardOwnedSchema.registerQueue(dataSource, name, 2);

        try (var queue = PostgresqlMessageQueue.builder()
                                               .setDataSource(dataSource)
                                               .setQueueName(name)
                                               .setInstanceId("grow-before")
                                               .build()) {
            queue.enqueue(List.of(Message.of("before-growth".getBytes(StandardCharsets.UTF_8), 1)));
        }

        var grown = ShardOwnedSchema.growShardCount(dataSource, name, 8);
        assertThat(grown.shardCount()).isEqualTo(8);
        assertThat(grown.queueId()).describedAs("the id is stable across a growth").isEqualTo(registered.queueId());
        assertThat(ShardOwnedSchema.resolve(dataSource, name).orElseThrow().shardCount()).isEqualTo(8);

        // A consumer built after the growth sees eight shards, and the message enqueued when there
        // were two is still delivered — it never had to move.
        var delivered = ConcurrentHashMap.<String>newKeySet();
        try (var queue = PostgresqlMessageQueue.builder()
                                               .setDataSource(dataSource)
                                               .setQueueName(name)
                                               .setInstanceId("grow-after")
                                               .build()) {
            assertThat(queue.shardCount()).isEqualTo(8);
            queue.consume((key, payload, payloadType) -> delivered.add(new String(payload, StandardCharsets.UTF_8)),
                          ConsumerOptions.defaults());
            queue.enqueue(List.of(Message.of("after-growth".getBytes(StandardCharsets.UTF_8), 1)));

            Awaitility.await().atMost(Duration.ofSeconds(30))
                      .untilAsserted(() -> assertThat(delivered)
                              .describedAs("messages from both sides of the growth are delivered")
                              .contains("before-growth", "after-growth"));
        }
    }

    /** Growing while ordered messages exist would send a key's next message to a different shard. */
    @Test
    void growing_is_refused_while_the_ordered_lane_holds_anything() throws Exception {
        var name = QueueName.of("ordered-pinned");
        ShardOwnedSchema.registerQueue(dataSource, name, 2);
        try (var queue = PostgresqlMessageQueue.builder()
                                               .setDataSource(dataSource)
                                               .setQueueName(name)
                                               .setInstanceId("pinned")
                                               .build()) {
            queue.enqueue(List.of(Message.ordered("k1".getBytes(StandardCharsets.UTF_8), 1, "key", 0L)));
        }

        assertThatThrownBy(() -> ShardOwnedSchema.growShardCount(dataSource, name, 4))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("ordered")
                .hasMessageContaining("reorders");
    }

    @Test
    void shrinking_is_refused_because_the_removed_shards_messages_have_nowhere_to_go() throws Exception {
        var name = QueueName.of("shrinkable");
        ShardOwnedSchema.registerQueue(dataSource, name, 8);
        assertThatThrownBy(() -> ShardOwnedSchema.growShardCount(dataSource, name, 4))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("can only grow");
    }

    private List<String> readColumn(String view, String column) throws Exception {
        try (var connection = dataSource.getConnection();
             var statement = connection.prepareStatement("SELECT " + column + " FROM " + view);
             var resultSet = statement.executeQuery()) {
            var values = new ArrayList<String>();
            while (resultSet.next()) {
                values.add(resultSet.getString(1));
            }
            return values;
        }
    }

    /**
     * A running consumer must pick up a grown shard count without being restarted.
     * <p>
     * The count used to be fixed at construction, so growing a queue meant redeploying every
     * instance — the operationally expensive half of resharding, and an artefact of where the number
     * lived rather than anything the design required. The heartbeat already reads the database and
     * already rebalances every tick.
     */
    @Test
    void a_running_consumer_picks_up_a_grown_shard_count_without_a_restart() throws Exception {
        var name = QueueName.of("grow-live");
        ShardOwnedSchema.registerQueue(dataSource, name, 2);

        var delivered = ConcurrentHashMap.<String>newKeySet();
        try (var queue = PostgresqlMessageQueue.builder()
                                               .setDataSource(dataSource)
                                               .setQueueName(name)
                                               .setInstanceId("grow-live-1")
                                               .setSettings(fastHeartbeat())
                                               .build()) {
            var subscription = queue.consume(
                    (key, payload, payloadType) -> delivered.add(new String(payload, StandardCharsets.UTF_8)),
                    ConsumerOptions.defaults());
            Awaitility.await().atMost(Duration.ofSeconds(20))
                      .untilAsserted(() -> assertThat(subscription.shardsHeld()).isEqualTo(2));

            // Grow it underneath the running consumer. No restart, no redeploy.
            ShardOwnedSchema.growShardCount(dataSource, name, 8);

            Awaitility.await().atMost(Duration.ofSeconds(30))
                      .untilAsserted(() -> assertThat(subscription.shardsHeld())
                              .describedAs("the running consumer takes the new shards on a heartbeat")
                              .isEqualTo(8));

            // And it must actually deliver from them: enqueue enough to reach every shard.
            for (var i = 0; i < 200; i++) {
                queue.enqueue(Message.of(("g" + i).getBytes(StandardCharsets.UTF_8), 1));
            }
            Awaitility.await().atMost(Duration.ofSeconds(30))
                      .untilAsserted(() -> assertThat(delivered)
                              .describedAs("messages routed to the newly added shards are delivered")
                              .hasSize(200));
        }
    }

    /** A registry reporting fewer shards must be ignored, not obeyed — the difference is stranded rows. */
    @Test
    void a_shrunken_registry_count_is_ignored_by_a_running_consumer() throws Exception {
        var name = QueueName.of("shrink-live");
        var registered = ShardOwnedSchema.registerQueue(dataSource, name, 4);

        try (var queue = PostgresqlMessageQueue.builder()
                                               .setDataSource(dataSource)
                                               .setQueueName(name)
                                               .setInstanceId("shrink-live-1")
                                               .setSettings(fastHeartbeat())
                                               .build()) {
            var subscription = queue.consume((key, payload, payloadType) -> {
            }, ConsumerOptions.defaults());
            Awaitility.await().atMost(Duration.ofSeconds(20))
                      .untilAsserted(() -> assertThat(subscription.shardsHeld()).isEqualTo(4));

            // growShardCount refuses to shrink, so write it directly — this asserts the engine's own
            // guard rather than the schema helper's.
            try (var connection = dataSource.getConnection();
                 var statement = connection.prepareStatement(
                         "UPDATE " + ShardOwnedSchema.REGISTRY_TABLE + " SET shard_count = 2 WHERE queue_id = ?")) {
                statement.setShort(1, registered.queueId());
                statement.executeUpdate();
            }

            Thread.sleep(4_000);
            assertThat(subscription.shardsHeld())
                    .describedAs("dropping shards at runtime would strand whatever is in them")
                    .isEqualTo(4);
        }
    }

    /** Heartbeat at a third of the lease, so growth is observed in seconds rather than in ten. */
    private static ShardOwnerSettings fastHeartbeat() {
        var defaults = ShardOwnerSettings.defaults();
        return new ShardOwnerSettings(defaults.readBatchSize(), defaults.ackBatchSize(),
                                      defaults.ackFlushInterval(), defaults.chaseDelay(),
                                      defaults.holeExpiry(), defaults.sweepInterval(),
                                      defaults.maxHolesPerChase(), defaults.keyConcurrency(),
                                      defaults.pollBackstop(), defaults.maxSweepInterval(),
                                      defaults.pumpThreads(), defaults.shedGrace(),
                                      Duration.ofSeconds(3));
    }

    /**
     * Redeploying and autoscaling do not change the shard count, so they never open a modulus
     * window. Worth asserting rather than reasoning about: the reshard hazard is easy to mistake for
     * a general operational one, and the everyday operations are the ones that must be boring.
     */
    @Test
    void restarting_and_scaling_instances_never_changes_the_shard_count() throws Exception {
        var name = QueueName.of("steady");
        ShardOwnedSchema.registerQueue(dataSource, name, 4);

        var counts = new java.util.HashSet<Integer>();
        var delivered = ConcurrentHashMap.<String>newKeySet();

        PostgresqlMessageQueue previous = null;
        for (var generation = 0; generation < 3; generation++) {
            var next = PostgresqlMessageQueue.builder()
                                             .setDataSource(dataSource)
                                             .setQueueName(name)
                                             .setInstanceId("rollout-" + generation)
                                             .build();
            next.consume((key, payload, payloadType) -> delivered.add(new String(payload, StandardCharsets.UTF_8)),
                         ConsumerOptions.defaults());
            counts.add(next.shardCount());
            next.enqueue(List.of(Message.ordered(("gen" + generation).getBytes(StandardCharsets.UTF_8),
                                                 1, "key-" + generation, 0L)));
            if (previous != null) {
                previous.stop();   // the old pod goes away, as in a rolling deploy
            }
            previous = next;
        }
        counts.add(previous.shardCount());

        assertThat(counts)
                .describedAs("every generation saw the same shard count — a redeploy is not a reshard")
                .containsExactly(4);
        Awaitility.await().atMost(Duration.ofSeconds(30))
                  .untilAsserted(() -> assertThat(delivered).hasSize(3));
        previous.stop();
    }

    /**
     * The producing side learns about growth too. Wiring only the consumer side would leave a
     * producer routing by the old modulus while its own consumers leased the new shards.
     * <p>
     * No consumer here on purpose: with one running the rows are deleted as fast as they are
     * written, so counting what is left measures the consumer rather than the routing.
     */
    @Test
    void a_queue_built_with_a_stale_shard_count_heals_itself_from_the_registry() throws Exception {
        var name = QueueName.of("stale-belief");
        var registered = ShardOwnedSchema.registerQueue(dataSource, name, 8);

        try (var queue = new PostgresqlMessageQueue(dataSource, registered.queueId(), 4,
                                                    "believes-four", ShardOwnerSettings.defaults())) {
            assertThat(queue.shardCount()).describedAs("it starts out wrong").isEqualTo(4);

            Awaitility.await().atMost(Duration.ofSeconds(40))
                      .untilAsserted(() -> {
                          queue.enqueue(Message.of("probe".getBytes(StandardCharsets.UTF_8), 1));
                          assertThat(queue.shardCount())
                                  .describedAs("the registry is the single source of truth, for the "
                                               + "producing side as well as the consuming one")
                                  .isEqualTo(8);
                      });

            for (var i = 0; i < 40; i++) {
                queue.enqueue(Message.of(("after" + i).getBytes(StandardCharsets.UTF_8), 1));
            }
            assertThat(distinctShardsUsed(registered.queueId()))
                    .describedAs("routing uses all eight shards it learned about, not the four it was built with")
                    .isEqualTo(8);
        }
    }

    private int distinctShardsUsed(short queueId) throws Exception {
        try (var connection = dataSource.getConnection();
             var statement = connection.prepareStatement(
                     "SELECT count(DISTINCT shard) FROM " + ShardOwnedSchema.UNORDERED_TABLE
                     + " WHERE queue_id = ?")) {
            statement.setShort(1, queueId);
            try (var resultSet = statement.executeQuery()) {
                resultSet.next();
                return resultSet.getInt(1);
            }
        }
    }
}
