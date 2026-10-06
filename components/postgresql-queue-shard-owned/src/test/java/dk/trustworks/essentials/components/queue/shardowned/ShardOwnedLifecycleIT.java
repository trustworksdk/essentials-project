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
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.*;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link dk.trustworks.essentials.shared.Lifecycle} semantics, which the rest of this project's
 * long-lived resources expose — {@code DurableQueues} and {@code DurableQueueConsumer} both extend it.
 * <p>
 * The contract has three requirements worth asserting rather than assuming: start and stop are both
 * idempotent, {@code isStarted} reports the truth, and a resource that has been stopped can be
 * started again. The last is the one that bites: an engine whose stop leaves stale owners behind
 * restarts into a state where its fences are long superseded.
 */
@Testcontainers(disabledWithoutDocker = true)
class ShardOwnedLifecycleIT {

    private static final short QUEUE_ID    = 1;
    private static final int   SHARD_COUNT = 4;

    @Container
    static PostgreSQLContainer postgres = LabPostgres.create();

    private HikariDataSource dataSource;

    @BeforeEach
    void setUp() throws Exception {
        var config = new HikariConfig();
        config.setJdbcUrl(postgres.getJdbcUrl());
        config.setUsername(postgres.getUsername());
        config.setPassword(postgres.getPassword());
        config.setMaximumPoolSize(40);
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

    @Test
    void a_configured_queue_starts_stops_and_starts_again_delivering_each_time() throws Exception {
        var delivered = ConcurrentHashMap.<String>newKeySet();
        var queue = new ShardOwnedQueue(dataSource, QUEUE_ID, SHARD_COUNT, "life-1");
        try {
            // Configured at construction time and started later, which is the shape a container needs:
            // it has nowhere to pass a handler at start().
            queue.configureUnordered((messageId, payload, payloadType) -> delivered.add(new String(payload, StandardCharsets.UTF_8)),
                                     ShardOwnerSettings.defaults(), SHARD_COUNT,
                                     RedeliveryPolicy.fixed(Duration.ofMillis(50), 3));
            assertThat(queue.isStarted()).isFalse();

            queue.start();
            assertThat(queue.isStarted()).isTrue();
            queue.start(); // idempotent — must not lease a second set of owners onto the same shards
            assertThat(queue.shardsHeld()).isEqualTo(SHARD_COUNT);

            queue.enqueue(List.of("a".getBytes(StandardCharsets.UTF_8)), 1);
            Awaitility.await().atMost(Duration.ofSeconds(20)).untilAsserted(() -> assertThat(delivered).contains("a"));

            queue.stop();
            assertThat(queue.isStarted()).isFalse();
            queue.stop(); // idempotent
            assertThat(queue.shardsHeld()).isZero();

            // The restart is the part that would break on a stop that left stale owners behind: the
            // engine must lease afresh rather than resurrect owners whose fences are superseded.
            queue.start();
            assertThat(queue.isStarted()).isTrue();
            queue.enqueue(List.of("b".getBytes(StandardCharsets.UTF_8)), 1);
            Awaitility.await().atMost(Duration.ofSeconds(20)).untilAsserted(() -> assertThat(delivered).contains("b"));
        } finally {
            queue.stop();
        }
    }

    @Test
    void the_spi_queue_and_its_subscription_report_and_honour_their_lifecycle() throws Exception {
        var delivered = ConcurrentHashMap.<String>newKeySet();
        try (var queue = new PostgresqlMessageQueue(dataSource, QUEUE_ID, SHARD_COUNT, "life-2")) {
            // Not started until something is actually running. This asserted isTrue() while the
            // flag was seeded true, which is the bug rather than the contract.
            assertThat(queue.isStarted()).isFalse();

            var subscription = queue.consume(
                    (messageId, key, payload, payloadType) -> delivered.add(new String(payload, StandardCharsets.UTF_8)),
                    ConsumerOptions.defaults());
            assertThat(subscription.isStarted()).isTrue();
            Awaitility.await().atMost(Duration.ofSeconds(20)).until(() -> subscription.shardsHeld() > 0);

            queue.enqueue(List.of(Message.of("one".getBytes(StandardCharsets.UTF_8), 1)));
            Awaitility.await().atMost(Duration.ofSeconds(20)).untilAsserted(() -> assertThat(delivered).contains("one"));

            // stop() releases the shards, so another instance can take them without waiting out a
            // lease — the same thing close() did, now under the name the framework uses.
            subscription.stop();
            assertThat(subscription.isStarted()).isFalse();
            assertThat(subscription.shardsHeld()).isZero();

            queue.stop();
            assertThat(queue.isStarted()).isFalse();
        }
    }

    /**
     * {@code ShardRuntime} is the last long-lived resource in the module, and it was the only one not
     * on {@code Lifecycle} — {@code AutoCloseable} and unrestartable, while everything above it could
     * be stopped and started again. Restarting matters because its executors cannot be revived, so a
     * restart has to rebuild them rather than reuse them.
     */
    /**
     * A queue stopped while its shared runtime keeps running - every Spring application, where the runtime
     * bean is destroyed only after all graceful-shutdown phases. Its owners used to stay in the pumps:
     * they went on reading and dispatching for the stopped queue until the liveness gate noticed the
     * cancelled heartbeat a lease TTL later, and every owner logged a WARN that it was pausing.
     */
    @Test
    void a_queue_stopped_on_a_shared_runtime_leaves_the_pumps_and_delivers_nothing_more() throws Exception {
        var delivered = ConcurrentHashMap.<String>newKeySet();
        try (var runtime = new ShardRuntime(dataSource, ShardOwnerSettings.defaults())) {
            var queue = ShardOwnedQueue.builder().setDataSource(dataSource).setQueueId(QUEUE_ID).setShardCount(SHARD_COUNT)
                                       .setInstanceId("stop-on-shared").setRuntime(runtime).build();
            queue.startConsuming((messageId, payload, payloadType) -> delivered.add(new String(payload, StandardCharsets.UTF_8)),
                                 ShardOwnerSettings.defaults(), SHARD_COUNT);
            queue.enqueue(List.of("before-stop".getBytes(StandardCharsets.UTF_8)), 1);
            Awaitility.await().atMost(Duration.ofSeconds(20)).untilAsserted(() -> assertThat(delivered).contains("before-stop"));
            assertThat(runtime.unitsServed()).isEqualTo(SHARD_COUNT);

            queue.stop();

            // Its owners leave the pumps, which keep running for everyone else.
            assertThat(runtime.isStarted()).isTrue();
            Awaitility.await().atMost(Duration.ofSeconds(5)).untilAsserted(() -> assertThat(runtime.unitsServed()).isZero());
            // The handled message was acknowledged on the way out, not left for a successor to redeliver.
            assertThat(queue.remaining()).isZero();

            // Nothing enqueued after the stop reaches the stopped queue's handler. Enqueued through a
            // queue that is not consuming, so there is no local hand-off to confuse the question.
            var producer = ShardOwnedQueue.builder().setDataSource(dataSource).setQueueId(QUEUE_ID).setShardCount(SHARD_COUNT)
                                          .setInstanceId("producer").setRuntime(runtime).build();
            producer.enqueue(List.of("after-stop".getBytes(StandardCharsets.UTF_8)), 1);
            Thread.sleep(2_000);
            assertThat(delivered).doesNotContain("after-stop");
            assertThat(queue.remaining()).isEqualTo(1);
        }
    }

    /**
     * A handler still running when stop begins. It used to lose its acknowledgement - the owner was
     * dropped or the pumps stopped under it - so the successor redelivered a message that had been
     * handled. Stop now drains: it waits, up to shedGrace, and the pumps acknowledge what finishes.
     */
    @Test
    void stop_waits_for_a_handler_in_flight_and_acknowledges_it() throws Exception {
        var entered = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        try (var runtime = new ShardRuntime(dataSource, ShardOwnerSettings.defaults())) {
            var queue = ShardOwnedQueue.builder().setDataSource(dataSource).setQueueId(QUEUE_ID).setShardCount(SHARD_COUNT)
                                       .setInstanceId("drain-unordered").setRuntime(runtime).build();
            queue.startConsuming((messageId, payload, payloadType) -> {
                entered.countDown();
                awaitQuietly(release);
            }, ShardOwnerSettings.defaults(), SHARD_COUNT);
            queue.enqueue(List.of("slow".getBytes(StandardCharsets.UTF_8)), 1);
            assertThat(entered.await(20, TimeUnit.SECONDS)).isTrue();

            var stopping = CompletableFuture.runAsync(queue::stop);
            Thread.sleep(300);
            assertThat(stopping).as("stop waits for the handler").isNotDone();

            release.countDown();
            stopping.get(10, TimeUnit.SECONDS);
            assertThat(queue.remaining()).as("the handled message was acknowledged, not left for a successor").isZero();
        } finally {
            release.countDown();
        }
    }

    /**
     * An ordered handler that outlasts the grace. Releasing its unit would let a successor start the
     * same key while it still runs here, so the unit is kept - and the instance stays registered - until
     * this instance's liveness lapses.
     */
    @Test
    void an_ordered_unit_whose_key_outlasts_the_grace_is_kept_rather_than_handed_on() throws Exception {
        var entered  = new CountDownLatch(1);
        var release  = new CountDownLatch(1);
        var settings = settingsWithShedGrace(Duration.ofMillis(300));
        try (var runtime = new ShardRuntime(dataSource, settings)) {
            var queue = ShardOwnedQueue.builder().setDataSource(dataSource).setQueueId(QUEUE_ID).setShardCount(SHARD_COUNT)
                                       .setInstanceId("drain-ordered").setRuntime(runtime).build();
            queue.startConsumingOrdered((messageId, key, payload, payloadType) -> {
                entered.countDown();
                awaitQuietly(release);
            }, settings, SHARD_COUNT);
            queue.enqueueOrdered(List.of(new ShardOwnedStorage.OrderedPayload("ACC-1", 0, "0".getBytes(StandardCharsets.UTF_8), 1)));
            assertThat(entered.await(20, TimeUnit.SECONDS)).isTrue();
            var heldBefore = orderedUnitsOwnedBy("drain-ordered");
            assertThat(heldBefore).isPositive();

            var started = System.nanoTime();
            queue.stop();
            assertThat(Duration.ofNanos(System.nanoTime() - started)).as("bounded by the grace").isLessThan(Duration.ofSeconds(5));

            assertThat(orderedUnitsOwnedBy("drain-ordered")).as("the busy unit is kept, the idle ones handed back").isEqualTo(1);
            assertThat(instanceRegistered("drain-ordered")).as("kept units need the instance to age out, not deregister").isTrue();

            // Restarting in the same JVM, under the same instance id, while the old handler still runs:
            // the kept unit is taken over under a NEW fence, so the old incarnation's writes are refused
            // rather than accepted alongside the new owner's.
            var keptFence = keptOrderedFence("drain-ordered");
            var restarted = ShardOwnedQueue.builder().setDataSource(dataSource).setQueueId(QUEUE_ID).setShardCount(SHARD_COUNT)
                                           .setInstanceId("drain-ordered").setRuntime(runtime).build();
            try {
                restarted.startConsumingOrdered((messageId, key, payload, payloadType) -> { }, settings, SHARD_COUNT);
                assertThat(fenceOf("ordered", keptFence.shard())).isGreaterThan(keptFence.fence());
            } finally {
                release.countDown();
                restarted.stop();
            }
        } finally {
            release.countDown();
        }
    }

    private static ShardOwnerSettings settingsWithShedGrace(Duration shedGrace) {
        var defaults = ShardOwnerSettings.defaults();
        return new ShardOwnerSettings(defaults.readBatchSize(), defaults.ackBatchSize(), Duration.ofMillis(1), Duration.ofMillis(2),
                                      Duration.ofMillis(300), Duration.ofMillis(100), 1_000, 8,
                                      Duration.ofMillis(50), Duration.ofSeconds(30), 2, shedGrace, Duration.ofMillis(1000), Duration.ofSeconds(60));
    }

    private int orderedUnitsOwnedBy(String instanceId) throws Exception {
        try (var connection = dataSource.getConnection();
             var statement = connection.prepareStatement("SELECT count(*) FROM " + ShardOwnedSchema.LEASE_TABLE
                                                                 + " WHERE queue_id = ? AND lane = 'ordered' AND owner = ?")) {
            statement.setShort(1, QUEUE_ID);
            statement.setString(2, instanceId);
            try (var resultSet = statement.executeQuery()) {
                resultSet.next();
                return resultSet.getInt(1);
            }
        }
    }

    private record UnitFence(int shard, long fence) {
    }

    private UnitFence keptOrderedFence(String instanceId) throws Exception {
        try (var connection = dataSource.getConnection();
             var statement = connection.prepareStatement("SELECT shard, fence FROM " + ShardOwnedSchema.LEASE_TABLE
                                                                 + " WHERE queue_id = ? AND lane = 'ordered' AND owner = ?")) {
            statement.setShort(1, QUEUE_ID);
            statement.setString(2, instanceId);
            try (var resultSet = statement.executeQuery()) {
                assertThat(resultSet.next()).isTrue();
                return new UnitFence(resultSet.getInt(1), resultSet.getLong(2));
            }
        }
    }

    private long fenceOf(String lane, int shard) throws Exception {
        try (var connection = dataSource.getConnection();
             var statement = connection.prepareStatement("SELECT fence FROM " + ShardOwnedSchema.LEASE_TABLE
                                                                 + " WHERE queue_id = ? AND lane = ? AND shard = ?")) {
            statement.setShort(1, QUEUE_ID);
            statement.setString(2, lane);
            statement.setInt(3, shard);
            try (var resultSet = statement.executeQuery()) {
                resultSet.next();
                return resultSet.getLong(1);
            }
        }
    }

    private boolean instanceRegistered(String instanceId) throws Exception {
        try (var connection = dataSource.getConnection();
             var statement = connection.prepareStatement("SELECT count(*) FROM " + ShardOwnedSchema.INSTANCE_TABLE + " WHERE queue_id = ? AND instance_id = ?")) {
            statement.setShort(1, QUEUE_ID);
            statement.setString(2, instanceId);
            try (var resultSet = statement.executeQuery()) {
                resultSet.next();
                return resultSet.getInt(1) > 0;
            }
        }
    }

    private static void awaitQuietly(CountDownLatch latch) {
        try {
            latch.await(30, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    @Test
    void the_runtime_stops_and_starts_again() throws Exception {
        var delivered = ConcurrentHashMap.<String>newKeySet();
        try (var runtime = new ShardRuntime(dataSource, ShardOwnerSettings.defaults())) {
            assertThat(runtime.isStarted()).isTrue();
            assertThat(runtime.pumpCount()).isPositive();

            runtime.stop();
            assertThat(runtime.isStarted()).isFalse();
            runtime.stop();
            assertThat(runtime.isStarted()).describedAs("stop is idempotent").isFalse();

            runtime.start();
            assertThat(runtime.isStarted()).isTrue();

            // A queue handed the restarted runtime must actually work on it — the point of the
            // restart is that the rebuilt pumps and listener serve shards, not merely that a flag
            // flipped back.
            var queue = ShardOwnedQueue.builder().setDataSource(dataSource).setQueueId(QUEUE_ID).setShardCount(SHARD_COUNT).setInstanceId("runtime-restart").setRuntime(runtime).build();
            try {
                queue.startConsuming((messageId, payload, payloadType) -> delivered.add(new String(payload, StandardCharsets.UTF_8)),
                                     ShardOwnerSettings.defaults(), SHARD_COUNT);
                queue.enqueue(List.of("after-restart".getBytes(StandardCharsets.UTF_8)), 1);
                Awaitility.await().atMost(Duration.ofSeconds(20))
                          .untilAsserted(() -> assertThat(delivered).contains("after-restart"));
            } finally {
                queue.stop();
            }
        }
    }
}
