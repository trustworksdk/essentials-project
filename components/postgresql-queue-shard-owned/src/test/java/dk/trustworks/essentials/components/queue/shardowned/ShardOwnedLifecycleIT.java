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

import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.zaxxer.hikari.*;
import dk.trustworks.essentials.components.queue.shardowned.spi.*;
import org.awaitility.Awaitility;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.slf4j.LoggerFactory;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.*;

import java.nio.charset.StandardCharsets;
import java.sql.SQLException;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;

import static org.assertj.core.api.Assertions.*;

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
     * A {@code start()} called on another thread while {@code stop()} is still draining waits for that stop, and the run it
     * then starts is whole. The stop clears {@code running} at its top and drains for up to {@code shedGrace} before it drops
     * its owners. A start in that window used to set {@code running} again and list its own owners, and the stop's final
     * clears then dropped them from the queue while they were on the pumps and serving: the queue reported holding
     * nothing, and its next stop released and reported nothing, leaving every lease row naming the stopped instance. A
     * handler held past the grace keeps the stop in its drain while the start is called.
     */
    @Test
    void a_start_called_while_a_stop_is_still_draining_waits_for_it_and_its_run_is_intact() throws Exception {
        var entered    = new CountDownLatch(1);
        var release    = new CountDownLatch(1);
        var settings   = settingsWithShedGrace(Duration.ofSeconds(2));
        var instanceId = "start-mid-stop";
        var reported   = new RecordedOwnership();
        var metrics    = new ShardOwnerMetrics(reported);
        try (var runtime = new ShardRuntime(dataSource, settings)) {
            var queue = new ShardOwnedQueue(dataSource, QUEUE_ID, SHARD_COUNT, instanceId, metrics).useRuntime(runtime);
            try {
                queue.startConsuming((messageId, payload, payloadType) -> {
                    entered.countDown();
                    awaitQuietly(release);
                }, settings, SHARD_COUNT);
                queue.enqueue(List.of("slow".getBytes(StandardCharsets.UTF_8)), 1);
                assertThat(entered.await(20, TimeUnit.SECONDS)).isTrue();

                var stopping = CompletableFuture.runAsync(queue::stop);
                Awaitility.await().atMost(Duration.ofSeconds(5)).until(() -> !queue.isStarted());
                var starting = CompletableFuture.runAsync(queue::start);
                stopping.get(10, TimeUnit.SECONDS);
                release.countDown();
                starting.get(10, TimeUnit.SECONDS);

                assertThat(queue.isStarted()).isTrue();
                assertThat(queue.shardsHeld()).as("the new run's owners are this queue's").isEqualTo(SHARD_COUNT);
                assertThat(unorderedShardsOwnedBy(instanceId)).as("the new run holds every shard's row").isEqualTo(SHARD_COUNT);
                // The stopped run's owners leave the pumps on their next pass; the new run's stay, one per shard.
                Awaitility.await().atMost(Duration.ofSeconds(5))
                          .untilAsserted(() -> assertThat(runtime.unitsServed()).as("one owner per shard on the pumps").isEqualTo(SHARD_COUNT));

                queue.stop();

                assertThat(unorderedShardsOwnedBy(instanceId)).as("the next stop releases the new run's shards").isZero();
                reported.assertEveryReleaseFollowsItsAcquisition();
                assertThat(metrics.shardsReleased.sum()).as("every acquisition of both runs is reported released")
                                                        .isEqualTo(metrics.shardsAcquired.sum())
                                                        .isEqualTo(2L * SHARD_COUNT);
            } finally {
                release.countDown();
                queue.stop();
            }
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

    /**
     * A heartbeat tick still running when the queue stops on a borrowed shared runtime finishes quietly. {@code stop()}
     * cancels the heartbeat without interrupting a tick already running, then lets go of the runtime, and the tick's
     * rebalance read the field it had just nulled: a NullPointerException, logged as "Rebalance failed" with a stack trace
     * during an ordinary shutdown. The tick is held inside its own membership heartbeat by a trigger that sleeps, so the
     * stop happens part-way through it; the test holds a second borrow, so the shared runtime outlives the queue.
     */
    @Test
    void a_heartbeat_tick_overlapping_stop_on_a_borrowed_shared_runtime_finishes_without_a_null_pointer() throws Exception {
        var settings    = settingsWithShedGrace(Duration.ofSeconds(1));
        var logged      = new ListAppender<ILoggingEvent>();
        var queueLogger = (ch.qos.logback.classic.Logger) LoggerFactory.getLogger(ShardOwnedQueue.class);
        logged.start();
        queueLogger.addAppender(logged);
        var runtime = ShardRuntime.acquireShared(dataSource, settings);
        try {
            var queue = new ShardOwnedQueue(dataSource, QUEUE_ID, SHARD_COUNT, "stop-mid-tick");
            queue.startConsuming((messageId, payload, payloadType) -> { }, settings, SHARD_COUNT);
            try (var connection = dataSource.getConnection(); var statement = connection.createStatement()) {
                statement.execute("CREATE OR REPLACE FUNCTION slow_heartbeat() RETURNS trigger AS $$ BEGIN "
                                          + "PERFORM pg_sleep(1.5); RETURN NEW; END $$ LANGUAGE plpgsql");
                statement.execute("CREATE TRIGGER slow_heartbeat BEFORE UPDATE ON " + ShardOwnedSchema.INSTANCE_TABLE
                                          + " FOR EACH ROW WHEN (OLD.instance_id = 'stop-mid-tick') EXECUTE FUNCTION slow_heartbeat()");
            }
            try {
                Awaitility.await().atMost(Duration.ofSeconds(10)).until(this::aHeartbeatIsSleeping);

                queue.stop();

                // The whole tick, its rebalance included, not just the sleeping statement: until then the assertions
                // below would run before the regression could be logged.
                awaitHeartbeatTickFinished(runtime);
            } finally {
                try (var connection = dataSource.getConnection(); var statement = connection.createStatement()) {
                    statement.execute("DROP TRIGGER IF EXISTS slow_heartbeat ON " + ShardOwnedSchema.INSTANCE_TABLE);
                }
                queue.stop();
            }
            var nullPointers = logged.list.stream()
                                          .filter(event -> event.getThrowableProxy() != null)
                                          .filter(event -> NullPointerException.class.getName().equals(event.getThrowableProxy().getClassName()))
                                          .map(ILoggingEvent::getFormattedMessage)
                                          .toList();
            assertThat(nullPointers).as("the tick overlapping the stop met no released runtime").isEmpty();
            assertThat(logged.list).extracting(ILoggingEvent::getFormattedMessage).doesNotContain("Rebalance failed");
        } finally {
            ShardRuntime.releaseShared(dataSource);
            queueLogger.detachAppender(logged);
        }
    }

    /**
     * A heartbeat tick that is acquiring a unit when the queue stops leaves no owner behind. {@code stop()} ends and
     * releases the owners it finds; one the tick acquires after that was neither, and on a runtime shared with other
     * queues, whose pumps outlive the stopped queue, it went on reading and dispatching for it, its lease row naming an
     * instance that had just deregistered. The tick is held inside the lease UPDATE of the acquire by a trigger that
     * sleeps: the test frees one of the queue's shards, the next tick's lease check ends that owner and its rebalance
     * takes the shard again, and the queue stops while that UPDATE sleeps.
     */
    @Test
    void a_unit_a_heartbeat_tick_acquires_while_the_queue_stops_is_not_left_served_on_a_shared_runtime() throws Exception {
        var settings = settingsWithShedGrace(Duration.ofSeconds(1));
        var runtime  = ShardRuntime.acquireShared(dataSource, settings);
        var reported = new RecordedOwnership();
        try {
            var queue = new ShardOwnedQueue(dataSource, QUEUE_ID, SHARD_COUNT, "stop-mid-acquire", new ShardOwnerMetrics(reported));
            queue.startConsuming((messageId, payload, payloadType) -> { }, settings, SHARD_COUNT);
            assertThat(queue.shardsHeld()).isEqualTo(SHARD_COUNT);

            stopWhileAHeartbeatTickAcquiresUnitZero(queue, runtime, "unordered", "stop-mid-acquire");
            reported.assertEveryReleaseFollowsItsAcquisition();

            // The owners stop() ended are dropped on their pump's next pass; one the tick left registered is never dropped.
            // Every owner on the runtime counts: this test's queue is the only one on it.
            Awaitility.await().atMost(Duration.ofSeconds(5))
                      .untilAsserted(() -> assertThat(runtime.unitsServed())
                              .as("no owner of the stopped queue is still on the shared runtime's pumps").isZero());
            assertThat(unorderedShardsOwnedBy("stop-mid-acquire")).as("no lease row names the stopped instance").isZero();
        } finally {
            ShardRuntime.releaseShared(dataSource);
        }
    }

    /**
     * The ordered lane's acquire loop meets the same stop. A unit leased there after {@code stop()} read its owners was
     * left on the shared runtime's pumps like an unordered shard, and on this lane its keys could then be in a handler
     * here and on the instance that took the unit next, because the stop had deregistered this one.
     */
    @Test
    void an_ordered_unit_a_heartbeat_tick_acquires_while_the_queue_stops_is_not_left_served_on_a_shared_runtime() throws Exception {
        var settings = settingsWithShedGrace(Duration.ofSeconds(1));
        var runtime  = ShardRuntime.acquireShared(dataSource, settings);
        var reported = new RecordedOwnership();
        try {
            var queue = new ShardOwnedQueue(dataSource, QUEUE_ID, SHARD_COUNT, "stop-mid-acquire-ordered", new ShardOwnerMetrics(reported));
            queue.startConsumingOrdered((messageId, key, payload, payloadType) -> { }, settings, SHARD_COUNT);
            var unitsHeld = orderedUnitsOwnedBy("stop-mid-acquire-ordered");
            assertThat(unitsHeld).as("the sole instance holds every ordered unit").isPositive();
            assertThat(queue.shardsHeld()).isEqualTo(unitsHeld);

            stopWhileAHeartbeatTickAcquiresUnitZero(queue, runtime, "ordered", "stop-mid-acquire-ordered");
            reported.assertEveryReleaseFollowsItsAcquisition();

            // Every owner on the runtime counts: this test's queue is the only one on it.
            Awaitility.await().atMost(Duration.ofSeconds(5))
                      .untilAsserted(() -> assertThat(runtime.unitsServed())
                              .as("no owner of the stopped queue is still on the shared runtime's pumps").isZero());
            assertThat(orderedUnitsOwnedBy("stop-mid-acquire-ordered")).as("no lease row names the stopped instance").isZero();
        } finally {
            ShardRuntime.releaseShared(dataSource);
        }
    }

    /**
     * A heartbeat tick that acquired a unit after {@code stop()} began, and cannot release it again, still leaves nothing
     * served: the acquisition is undone with no owner built for it, and the WARN saying so is true - the unit is free
     * to another instance once the stopped one's membership row is gone. A trigger refuses every release of that unit, and
     * only of that one, so the stop releases the others and deregisters as it does on a healthy database. It is in place
     * before the tick acquires: created while the tick's UPDATE holds the row, it would wait for that transaction.
     */
    @ParameterizedTest
    @ValueSource(strings = {"unordered", "ordered"})
    void a_unit_a_heartbeat_tick_acquires_while_the_queue_stops_and_cannot_release_again_is_ended_and_free_once_the_instance_is_gone(String lane) throws Exception {
        var settings    = settingsWithShedGrace(Duration.ofSeconds(1));
        var instanceId  = "stop-mid-acquire-refused-" + lane;
        var runtime     = ShardRuntime.acquireShared(dataSource, settings);
        var logged      = new ListAppender<ILoggingEvent>();
        var queueLogger = (ch.qos.logback.classic.Logger) LoggerFactory.getLogger(ShardOwnedQueue.class);
        logged.start();
        queueLogger.addAppender(logged);
        var queue = new ShardOwnedQueue(dataSource, QUEUE_ID, SHARD_COUNT, instanceId);
        try {
            startConsuming(queue, lane, settings);
            LeaseReleaseRefusals.failLeaseReleasesOf(dataSource, new short[]{QUEUE_ID}, lane, 0, true);
            try {
                holdTheNextAcquireOfUnitZero(lane, instanceId, 1.5);

                queue.stop();
                assertThat(aHeartbeatIsSleeping()).as("premise: the stop finished while the tick was still acquiring").isTrue();
                awaitHeartbeatTickFinished(runtime);

                assertThat(LeaseReleaseRefusals.releasesRefused(dataSource, QUEUE_ID, 0))
                        .as("premise: the tick tried to release unit 0 again, and was refused").isPositive();
                // Every owner on the runtime counts: this test's queue is the only one on it.
                Awaitility.await().atMost(Duration.ofSeconds(5))
                          .untilAsserted(() -> assertThat(runtime.unitsServed())
                                  .as("no owner of the stopped queue is on the shared runtime's pumps").isZero());
                assertThat(logged.list).filteredOn(event -> event.getLevel() == ch.qos.logback.classic.Level.WARN)
                                       .extracting(ILoggingEvent::getFormattedMessage)
                                       .filteredOn(message -> message.contains("could not release it again"))
                                       .as("one WARN for the unit the tick could not release")
                                       .hasSize(1)
                                       .allMatch(message -> message.contains("free to another instance once this instance's membership row is gone"));
                assertThat(unitsOwnedBy(lane, instanceId)).as("only the refused unit is still leased to the stopped instance").isEqualTo(1);
                assertThat(instanceRegistered(instanceId)).as("the stop deregistered the instance, as the WARN relies on").isFalse();
            } finally {
                dropSlowAcquire();
                LeaseReleaseRefusals.failLeaseReleasesOf(dataSource, new short[]{QUEUE_ID}, lane, 0, false);
            }

            assertThat(new ShardOwnedStorage(dataSource, QUEUE_ID).acquireLeaseGrant(lane, 0, "successor", settings.leaseTtlMillis()))
                    .as("another instance takes the unit the stopped one could not release").isPresent();
        } finally {
            dropSlowAcquire();
            queue.stop();
            ShardRuntime.releaseShared(dataSource);
            queueLogger.detachAppender(logged);
        }
    }

    /**
     * A heartbeat tick still inside its acquire when the queue is stopped and started again in the same process, under
     * the same instance id, does not hand its owner to the new run. The restarted queue sets {@code running} again, and
     * the tick, which checked only that, kept its owner: listed, on a pump and reported acquired beside the new run's own
     * owner of the same unit, until a lease check found its fence superseded. Undoing it must not cost the new run the
     * unit either: the tick's release is bounded by its own fence, so the row the new run took under a newer one stays.
     * <p>
     * Nor the unit's notifications: the tick's refused acquisition must leave the wake-up the new run placed for the unit
     * alone, or a NOTIFY for it reaches no owner and the unit is served only when its sweep or poll backstop comes due.
     * Both are 30 s here, so a message enqueued on the unit from outside the queue - by NOTIFY, not by local hand-off - is
     * handled within the test's bound only if the new run's owner was woken for it. That proves something only when the
     * new run has placed the unit before the tick refuses its acquisition: refused first, anything the refusal did to the
     * unit's wake-up would be over before the placement, which then builds a fresh one. Left to themselves the two race,
     * as the start places its units outside the queue's runLock once it has leased them all, so the test holds that lock
     * - the one the tick refuses under - from just after the start has set {@code running} until the start has returned.
     */
    @ParameterizedTest
    @ValueSource(strings = {"unordered", "ordered"})
    void a_heartbeat_tick_acquiring_while_the_queue_is_stopped_and_started_again_does_not_join_the_new_run(String lane) throws Exception {
        var settings    = settingsReadingOnlyWhenNotified();
        var instanceId  = "restart-mid-acquire-" + lane;
        var runtime     = ShardRuntime.acquireShared(dataSource, settings);
        var reported    = new RecordedOwnership();
        var metrics     = new ShardOwnerMetrics(reported);
        var delivered   = ConcurrentHashMap.<String>newKeySet();
        var logged      = new ListAppender<ILoggingEvent>();
        var queueLogger = (ch.qos.logback.classic.Logger) LoggerFactory.getLogger(ShardOwnedQueue.class);
        var queueLevel  = queueLogger.getLevel();
        logged.start();
        queueLogger.addAppender(logged);
        // The refused acquisition's release is logged at DEBUG only.
        queueLogger.setLevel(ch.qos.logback.classic.Level.DEBUG);
        var queue = new ShardOwnedQueue(dataSource, QUEUE_ID, SHARD_COUNT, instanceId, metrics);
        try {
            startConsuming(queue, lane, settings, delivered);
            var units = unitsOwnedBy(lane, instanceId);
            assertThat(units).as("premise: the sole instance holds every unit of the lane").isPositive();
            holdTheNextAcquireOfUnitZero(lane, instanceId, 1.5);

            queue.stop();
            assertThat(aHeartbeatIsSleeping()).as("premise: the tick was still acquiring when the queue stopped").isTrue();
            var lostBefore        = metrics.leasesLost.sum();
            var acquiredBefore    = metrics.shardsAcquired.sum();
            var reportsBeforeRestart = reported.events().size();
            // The new run's start acquires unit 0 once the held tick has committed its acquire, under a newer fence. The tick
            // decides whether to adopt its owner under the queue's runLock (adoptIntoRun), which the start takes only to set
            // `running`. Holding that lock from then until the start has returned keeps the tick from refusing until the new
            // run has placed unit 0, whichever of the two would otherwise have got there first.
            var starting = CompletableFuture.runAsync(() -> {
                try {
                    startConsuming(queue, lane, settings, delivered);
                } catch (SQLException e) {
                    throw new IllegalStateException(e);
                }
            });
            Awaitility.await().atMost(Duration.ofSeconds(10)).until(queue::isStarted);
            synchronized (runLockOf(queue)) {
                assertThat(aHeartbeatIsSleeping()).as("premise: the tick was still acquiring when the test took the lock it refuses under").isTrue();
                starting.get(20, TimeUnit.SECONDS);
            }
            awaitHeartbeatTickFinished(runtime);
            assertThat(logged.list).filteredOn(event -> event.getLevel() == ch.qos.logback.classic.Level.DEBUG)
                                   .extracting(ILoggingEvent::getFormattedMessage)
                                   .filteredOn(message -> message.contains(" unit 0 on queue " + QUEUE_ID
                                                                                   + " again: the queue stopped while it was being acquired"))
                                   .as("premise: the tick's acquisition of unit 0 was refused and released again")
                                   .hasSize(1);

            // Three of the new run's lease checks, each counting a renewal per unit it still holds. A tick's owner in the
            // new run is found superseded by one; a new-run owner whose row the tick released ends at its takeover or its
            // lease check, and the rebalance after that takes the unit again.
            var renewalsBefore = metrics.leaseRenewals.sum();
            Awaitility.await().atMost(Duration.ofSeconds(10)).until(() -> metrics.leaseRenewals.sum() >= renewalsBefore + 3L * (units - 1));
            assertThat(metrics.shardsAcquired.sum() - acquiredBefore).as("only the new run's own start-up acquisitions are counted").isEqualTo(units);
            assertThat(reported.events().subList(reportsBeforeRestart, reported.events().size()))
                    .filteredOn(event -> event.shard() == 0)
                    .as("unit 0 is reported acquired once since the stop, by the new run's start")
                    .containsExactly(new Reported(0, true));
            assertThat(metrics.leasesLost.sum()).as("no owner of the new run found its unit taken or its fence superseded").isEqualTo(lostBefore);
            assertThat(queue.shardsHeld()).as("the new run serves every unit once").isEqualTo(units);
            assertThat(unitsOwnedBy(lane, instanceId)).as("the new run still holds every unit's row").isEqualTo(units);
            assertThat(runtime.unitsServed()).as("one owner per unit on the shared runtime's pumps").isEqualTo(units);

            var honouredBefore = metrics.wakeupsHonoured.sum();
            enqueueFromAnotherProducer(lane, 0, "after-restart");
            Awaitility.await().atMost(Duration.ofSeconds(5))
                      .untilAsserted(() -> assertThat(delivered).as("unit 0's notification reached the new run's owner, well before its 30 s sweep")
                                                                .contains("after-restart"));
            assertThat(metrics.wakeupsHonoured.sum()).as("the owner was woken by its unit's wake-up").isGreaterThan(honouredBefore);
        } finally {
            dropSlowAcquire();
            queue.stop();
            ShardRuntime.releaseShared(dataSource);
            queueLogger.detachAppender(logged);
            queueLogger.setLevel(queueLevel);
        }
    }

    /**
     * {@link #settingsWithShedGrace} with a 1 s grace, whose head sweep, its backed-off ceiling and the poll backstop are all
     * 30 s: within a test's few seconds an owner reads its unit when a notification wakes it, and not otherwise.
     */
    private static ShardOwnerSettings settingsReadingOnlyWhenNotified() {
        var base = settingsWithShedGrace(Duration.ofSeconds(1));
        return new ShardOwnerSettings(base.readBatchSize(), base.ackBatchSize(), base.ackFlushInterval(), base.chaseDelay(), base.holeExpiry(),
                                      Duration.ofSeconds(30), base.maxHolesPerChase(), base.keyConcurrency(), Duration.ofSeconds(30),
                                      Duration.ofSeconds(30), base.pumpThreads(), base.shedGrace(), base.leaseTtl(), base.watermarkCap());
    }

    /**
     * Enqueue one message on {@code unit} of {@code lane} through a storage of its own rather than through a queue, so it
     * reaches the unit's owner by NOTIFY and never by local hand-off. On the ordered lane, under a key that routes to
     * {@code unit} in the queue's routing space ({@link ShardOwnedSchema#ORDERED_UNITS}: the queue is registered by id).
     */
    private void enqueueFromAnotherProducer(String lane, int unit, String payload) throws Exception {
        var producer = new ShardOwnedStorage(dataSource, QUEUE_ID);
        var bytes    = payload.getBytes(StandardCharsets.UTF_8);
        try (var connection = dataSource.getConnection()) {
            if ("ordered".equals(lane)) {
                var key = 0;
                while (ShardOwnedSchema.unitForKey("key-" + key, ShardOwnedSchema.ORDERED_UNITS) != unit) {
                    key++;
                }
                producer.enqueueOrderedBatch(connection, unit, List.of(new ShardOwnedStorage.OrderedPayload("key-" + key, 0, bytes, 1)));
            } else {
                producer.enqueueBatch(connection, unit, List.of(bytes), 1);
            }
        }
    }

    /**
     * A heartbeat tick that acquired a shard as the queue stopped, and could not release it again, does not leave that
     * shard leased and unserved for good once the queue is started again under the same instance id. The restarted run
     * lists no owner for the shard, so neither its lease check nor its fair share counted the row, and while it held as
     * many shards as it may (here its {@code maxShards}; in a cluster its fair share) its rebalance never took the shard
     * back. No other instance could take it either: the row names a live instance. The release is now retried on every
     * heartbeat. A trigger refuses every release of the last shard until the test lifts it; the restarted run's
     * {@code maxShards} leaves that shard out of its own start, so only the retry can free it.
     */
    @Test
    void a_shard_a_heartbeat_tick_could_not_release_as_the_queue_stopped_is_released_once_the_restarted_queue_can() throws Exception {
        var settings    = settingsWithShedGrace(Duration.ofSeconds(1));
        var instanceId  = "restart-failed-undo";
        var stranded    = SHARD_COUNT - 1;
        var runtime     = ShardRuntime.acquireShared(dataSource, settings);
        var logged      = new ListAppender<ILoggingEvent>();
        var queueLogger = (ch.qos.logback.classic.Logger) LoggerFactory.getLogger(ShardOwnedQueue.class);
        logged.start();
        queueLogger.addAppender(logged);
        var queue = new ShardOwnedQueue(dataSource, QUEUE_ID, SHARD_COUNT, instanceId);
        try {
            queue.startConsuming((messageId, payload, payloadType) -> { }, settings, SHARD_COUNT);
            LeaseReleaseRefusals.failLeaseReleasesOf(dataSource, new short[]{QUEUE_ID}, "unordered", stranded, true);
            try {
                holdTheNextAcquireOf("unordered", instanceId, stranded, 1.5);

                queue.stop();
                // Every shard but the stranded one: the restarted run is at its limit without it.
                queue.startConsuming((messageId, payload, payloadType) -> { }, settings, SHARD_COUNT - 1);
                awaitHeartbeatTickFinished(runtime);

                assertThat(LeaseReleaseRefusals.releasesRefused(dataSource, QUEUE_ID, stranded))
                        .as("premise: the earlier run's tick tried to release the shard again, and was refused").isPositive();
                assertThat(ownerOf("unordered", stranded)).as("premise: the refused shard is still leased to the instance").isEqualTo(instanceId);
                assertThat(queue.shardsHeld()).as("premise: the restarted run serves every shard but the stranded one").isEqualTo(SHARD_COUNT - 1);
            } finally {
                dropSlowAcquire();
                LeaseReleaseRefusals.failLeaseReleasesOf(dataSource, new short[]{QUEUE_ID}, "unordered", stranded, false);
            }

            Awaitility.await().atMost(Duration.ofSeconds(5))
                      .untilAsserted(() -> assertThat(ownerOf("unordered", stranded))
                              .as("the restarted queue's heartbeat released the shard the earlier run's tick could not").isNull());
            assertThat(logged.list).filteredOn(event -> event.getLevel() == ch.qos.logback.classic.Level.WARN)
                                   .extracting(ILoggingEvent::getFormattedMessage)
                                   .filteredOn(message -> message.contains("could not release it again"))
                                   .as("one WARN for the shard the tick could not release, saying the release is retried")
                                   .hasSize(1)
                                   .allMatch(message -> message.contains("retried on every heartbeat"));
            assertThat(queue.shardsHeld()).as("the restarted run kept every shard it serves").isEqualTo(SHARD_COUNT - 1);
            assertThat(unorderedShardsOwnedBy(instanceId)).as("and their rows").isEqualTo(SHARD_COUNT - 1);
        } finally {
            dropSlowAcquire();
            queue.stop();
            ShardRuntime.releaseShared(dataSource);
            queueLogger.detachAppender(logged);
        }
    }

    /**
     * A {@code QueueObserver} told of a shard a heartbeat tick acquired does not hold up {@code stop()}. The tick used to
     * tell it while holding the lock a stop takes to end the run, so an observer that blocked - a slow metrics backend, a
     * tracing exporter - kept the stop waiting for as long as it did. The observer here blocks on the tick's acquisition
     * of shard 0 until the test lets it go, and the stop runs on another thread meanwhile.
     */
    @Test
    void a_stop_does_not_wait_for_an_observer_told_of_a_shard_a_heartbeat_tick_acquired() throws Exception {
        var settings   = settingsWithShedGrace(Duration.ofSeconds(1));
        var instanceId = "slow-observer";
        var runtime    = ShardRuntime.acquireShared(dataSource, settings);
        var armed      = new AtomicBoolean();
        var told       = new CountDownLatch(1);
        var letGo      = new CountDownLatch(1);
        var observer = new QueueObserver() {
            @Override
            public void shardOwnershipChanged(int shard, boolean acquired) {
                if (acquired && shard == 0 && armed.compareAndSet(true, false)) {
                    told.countDown();
                    awaitQuietly(letGo);
                }
            }
        };
        var queue = new ShardOwnedQueue(dataSource, QUEUE_ID, SHARD_COUNT, instanceId, new ShardOwnerMetrics(observer));
        var stopper = Executors.newSingleThreadExecutor();
        try {
            queue.startConsuming((messageId, payload, payloadType) -> { }, settings, SHARD_COUNT);
            armed.set(true);
            // The next tick's lease check finds shard 0 gone and ends its owner, and its rebalance takes the shard again.
            try (var connection = dataSource.getConnection(); var statement = connection.createStatement()) {
                statement.execute("UPDATE " + ShardOwnedSchema.LEASE_TABLE + " SET owner = 'departed' WHERE queue_id = " + QUEUE_ID
                                          + " AND lane = 'unordered' AND shard = 0");
            }
            assertThat(told.await(10, TimeUnit.SECONDS)).as("premise: a heartbeat tick acquired shard 0 again and told the observer").isTrue();

            var stopped = stopper.submit(queue::stop);

            assertThatCode(() -> stopped.get(10, TimeUnit.SECONDS)).as("the stop finished while the observer was still blocked")
                                                                   .doesNotThrowAnyException();
            assertThat(letGo.getCount()).as("premise: the observer was blocked throughout").isOne();
        } finally {
            letGo.countDown();
            awaitHeartbeatTickFinished(runtime);
            stopper.shutdownNow();
            queue.stop();
            ShardRuntime.releaseShared(dataSource);
        }
    }

    private String ownerOf(String lane, int shard) throws Exception {
        try (var connection = dataSource.getConnection();
             var statement = connection.prepareStatement("SELECT owner FROM " + ShardOwnedSchema.LEASE_TABLE
                                                                 + " WHERE queue_id = ? AND lane = ? AND shard = ?")) {
            statement.setShort(1, QUEUE_ID);
            statement.setString(2, lane);
            statement.setInt(3, shard);
            try (var resultSet = statement.executeQuery()) {
                resultSet.next();
                return resultSet.getString(1);
            }
        }
    }

    /**
     * A shard (unordered) or unit (ordered) a heartbeat tick is releasing to rebalance while the queue stops is reported
     * released once. The stop releases every owner it finds, the one the tick is releasing included, and both used to
     * report it: a release counted, and told to the observer, twice for one acquisition. The tick's release is held
     * inside its UPDATE by a trigger that sleeps; a second, never-ageing membership row gives the queue a fair share
     * below what it holds, so the tick sheds.
     */
    @ParameterizedTest
    @ValueSource(strings = {"unordered", "ordered"})
    void a_unit_a_heartbeat_tick_releases_while_the_queue_stops_is_reported_released_once(String lane) throws Exception {
        var settings   = settingsWithShedGrace(Duration.ofSeconds(1));
        var instanceId = "stop-mid-shed-" + lane;
        var runtime    = ShardRuntime.acquireShared(dataSource, settings);
        var reported   = new RecordedOwnership();
        var metrics    = new ShardOwnerMetrics(reported);
        var queue      = new ShardOwnedQueue(dataSource, QUEUE_ID, SHARD_COUNT, instanceId, metrics);
        try {
            startConsuming(queue, lane, settings);
            var units = unitsOwnedBy(lane, instanceId);
            assertThat(units).as("premise: the sole instance holds every unit of the lane").isPositive();
            // Fair share against two live instances; the first unit past it is the first the tick releases.
            var firstShed = (units + 1) / 2;
            try (var connection = dataSource.getConnection(); var statement = connection.createStatement()) {
                statement.execute("CREATE OR REPLACE FUNCTION slow_release() RETURNS trigger AS $$ BEGIN "
                                          + "PERFORM pg_sleep(1); RETURN NULL; END $$ LANGUAGE plpgsql");
                statement.execute("CREATE TRIGGER slow_release AFTER UPDATE ON " + ShardOwnedSchema.LEASE_TABLE
                                          + " FOR EACH ROW WHEN (OLD.owner = '" + instanceId + "' AND NEW.owner IS NULL"
                                          + " AND NEW.lane = '" + lane + "' AND NEW.shard = " + firstShed + ")"
                                          + " EXECUTE FUNCTION slow_release()");
                statement.execute("INSERT INTO " + ShardOwnedSchema.INSTANCE_TABLE + " (queue_id, instance_id, last_seen)"
                                          + " VALUES (" + QUEUE_ID + ", 'phantom', now() + interval '1 hour')");
            }
            try {
                Awaitility.await().atMost(Duration.ofSeconds(10)).until(this::aHeartbeatIsSleeping);

                queue.stop();

                awaitHeartbeatTickFinished(runtime);
            } finally {
                try (var connection = dataSource.getConnection(); var statement = connection.createStatement()) {
                    statement.execute("DROP TRIGGER IF EXISTS slow_release ON " + ShardOwnedSchema.LEASE_TABLE);
                }
            }

            assertThat(unitsOwnedBy(lane, instanceId)).as("premise: every unit was released").isZero();
            reported.assertEveryReleaseFollowsItsAcquisition();
            assertThat(metrics.shardsReleased.sum()).as("each unit's release counted once").isEqualTo(units);
            assertThat(metrics.shardsAcquired.sum()).as("each unit's acquisition counted once").isEqualTo(units);
        } finally {
            queue.stop();
            ShardRuntime.releaseShared(dataSource);
        }
    }

    /**
     * A unit a heartbeat tick adopted is reported acquired to the {@code QueueObserver} even when a later unit's acquire
     * statement fails and ends the tick's acquire loop. The observer used to be told only after the loop, so those units
     * were listed, on their pumps and counted in {@code shardsAcquired}, but never reported acquired, while their releases
     * were reported: the observer's released count ran ahead of its acquired count. Units 0 and 1 are taken from the queue
     * by a departed instance; the next tick's lease check drops their owners, and its acquire loop takes unit 0 again and
     * then fails on unit 1, which a trigger refuses to lease to the queue.
     */
    @ParameterizedTest
    @ValueSource(strings = {"unordered", "ordered"})
    void a_unit_a_heartbeat_tick_acquires_before_a_later_acquire_fails_is_reported_acquired(String lane) throws Exception {
        var settings   = settingsWithShedGrace(Duration.ofSeconds(1));
        var instanceId = "acquire-then-fail-" + lane;
        var runtime    = ShardRuntime.acquireShared(dataSource, settings);
        var reported   = new RecordedOwnership();
        var metrics    = new ShardOwnerMetrics(reported);
        var queue      = new ShardOwnedQueue(dataSource, QUEUE_ID, SHARD_COUNT, instanceId, metrics);
        try {
            startConsuming(queue, lane, settings);
            var units = unitsOwnedBy(lane, instanceId);
            assertThat(units).as("premise: the sole instance holds units 0 and 1 and more").isGreaterThan(2);
            try (var connection = dataSource.getConnection(); var statement = connection.createStatement()) {
                statement.execute("CREATE OR REPLACE FUNCTION refuse_acquire() RETURNS trigger AS $$ BEGIN "
                                          + "RAISE EXCEPTION 'simulated failure acquiring % unit %', NEW.lane, NEW.shard; END $$ LANGUAGE plpgsql");
                statement.execute("CREATE TRIGGER refuse_acquire BEFORE UPDATE ON " + ShardOwnedSchema.LEASE_TABLE
                                          + " FOR EACH ROW WHEN (OLD.owner IS DISTINCT FROM NEW.owner AND NEW.owner = '" + instanceId + "'"
                                          + " AND NEW.lane = '" + lane + "' AND NEW.shard = 1)"
                                          + " EXECUTE FUNCTION refuse_acquire()");
                statement.execute("UPDATE " + ShardOwnedSchema.LEASE_TABLE + " SET owner = 'departed' WHERE queue_id = " + QUEUE_ID
                                          + " AND lane = '" + lane + "' AND shard IN (0, 1)");
            }
            try {
                Awaitility.await().atMost(Duration.ofSeconds(10))
                          .untilAsserted(() -> assertThat(ownerOf(lane, 0)).as("premise: a heartbeat tick took unit 0 back").isEqualTo(instanceId));
                awaitHeartbeatTickFinished(runtime);
                assertThat(ownerOf(lane, 1)).as("premise: the tick's acquire of unit 1 failed").isEqualTo("departed");
                assertThat(metrics.shardsAcquired.sum()).as("premise: the tick adopted unit 0 and counted it").isEqualTo(units + 1);

                assertThat(reported.events()).filteredOn(event -> event.shard() == 0 && event.acquired())
                                             .as("unit 0 reported acquired at start-up and again by the tick whose loop then failed")
                                             .hasSize(2);
            } finally {
                try (var connection = dataSource.getConnection(); var statement = connection.createStatement()) {
                    statement.execute("DROP TRIGGER IF EXISTS refuse_acquire ON " + ShardOwnedSchema.LEASE_TABLE);
                }
            }

            queue.stop();

            var acquiredReports = reported.events().stream().filter(Reported::acquired).count();
            var releasedReports = reported.events().stream().filter(event -> !event.acquired()).count();
            assertThat(acquiredReports).as("every acquisition counted is reported").isEqualTo(metrics.shardsAcquired.sum());
            assertThat(releasedReports).as("every release counted is reported").isEqualTo(metrics.shardsReleased.sum());
            assertThat(releasedReports).as("the observer is never told of more releases than acquisitions").isLessThanOrEqualTo(acquiredReports);
            reported.assertEveryReleaseFollowsItsAcquisition();
        } finally {
            queue.stop();
            ShardRuntime.releaseShared(dataSource);
        }
    }

    private static void startConsuming(ShardOwnedQueue queue, String lane, ShardOwnerSettings settings) throws SQLException {
        if ("ordered".equals(lane)) {
            queue.startConsumingOrdered((messageId, key, payload, payloadType) -> { }, settings, SHARD_COUNT);
        } else {
            queue.startConsuming((messageId, payload, payloadType) -> { }, settings, SHARD_COUNT);
        }
    }

    /**
     * {@link #startConsuming(ShardOwnedQueue, String, ShardOwnerSettings)}, with a handler that records each payload it
     * is given, as text.
     */
    private static void startConsuming(ShardOwnedQueue queue, String lane, ShardOwnerSettings settings, Set<String> delivered) throws SQLException {
        if ("ordered".equals(lane)) {
            queue.startConsumingOrdered((messageId, key, payload, payloadType) -> delivered.add(new String(payload, StandardCharsets.UTF_8)),
                                        settings, SHARD_COUNT);
        } else {
            queue.startConsuming((messageId, payload, payloadType) -> delivered.add(new String(payload, StandardCharsets.UTF_8)),
                                 settings, SHARD_COUNT);
        }
    }

    private int unitsOwnedBy(String lane, String instanceId) throws Exception {
        return "ordered".equals(lane) ? orderedUnitsOwnedBy(instanceId) : unorderedShardsOwnedBy(instanceId);
    }

    private record Reported(int shard, boolean acquired) {
    }

    /**
     * The ownership changes a queue reports to its observer, in order.
     */
    private static final class RecordedOwnership implements QueueObserver {
        private final List<Reported> events = new CopyOnWriteArrayList<>();

        @Override
        public void shardOwnershipChanged(int shard, boolean acquired) {
            events.add(new Reported(shard, acquired));
        }

        List<Reported> events() {
            return events;
        }

        /**
         * Per unit, acquisitions and releases alternate, an acquisition first: no release is reported twice, or before
         * the acquisition it ends. A unit found taken is not reported released, so an acquisition may go unmatched.
         */
        void assertEveryReleaseFollowsItsAcquisition() {
            var held = new HashMap<Integer, Boolean>();
            for (var event : events) {
                if (event.acquired()) {
                    held.put(event.shard(), true);
                } else {
                    assertThat(held.getOrDefault(event.shard(), false))
                            .as("unit %d is reported released only while reported acquired; reported: %s", event.shard(), events)
                            .isTrue();
                    held.put(event.shard(), false);
                }
            }
        }
    }

    /**
     * A {@code start()} that fails after borrowing the runtime shared per {@code DataSource} gives the borrow back. The
     * borrow comes before the start's acquire loop, which is where a database error lands, so a failed start that kept it
     * left the shared runtime a borrower that never stops: its pumps, listener and connections were never closed. The test
     * holds one borrow of its own, makes every lease acquire by the queue fail with a trigger, and then gives its borrow
     * back: the last borrower closes the shared runtime, so it stops only if the failed start's borrow is gone.
     */
    @ParameterizedTest
    @ValueSource(strings = {"unordered", "ordered"})
    void a_start_that_fails_after_borrowing_the_shared_runtime_gives_the_borrow_back(String lane) throws Exception {
        var settings   = ShardOwnerSettings.defaults();
        var instanceId = "failed-start-" + lane;
        var held       = ShardRuntime.acquireShared(dataSource, settings);
        var heldGiven  = false;
        try {
            failLeaseAcquiresBy(instanceId, true);
            try {
                var queue = new ShardOwnedQueue(dataSource, QUEUE_ID, SHARD_COUNT, instanceId);
                assertThatThrownBy(() -> {
                    if ("ordered".equals(lane)) {
                        queue.startConsumingOrdered((messageId, key, payload, payloadType) -> { }, settings, SHARD_COUNT);
                    } else {
                        queue.startConsuming((messageId, payload, payloadType) -> { }, settings, SHARD_COUNT);
                    }
                }).isInstanceOf(IllegalStateException.class)
                  .hasMessageStartingWith("Failed to start consuming shards for instance " + instanceId)
                  .hasCauseInstanceOf(SQLException.class)
                  .as("premise: the start failed in its acquire loop, where the trigger fires")
                  .hasStackTraceContaining("simulated failure acquiring a lease");
                assertThat(queue.isStarted()).isFalse();
            } finally {
                failLeaseAcquiresBy(instanceId, false);
            }
            assertThat(held.isStarted()).as("premise: the test's own borrow keeps the shared runtime running").isTrue();

            ShardRuntime.releaseShared(dataSource);
            heldGiven = true;

            assertThat(held.isStarted()).as("the test's borrow was the last one: the failed start gave its borrow back").isFalse();
        } finally {
            if (!heldGiven) {
                ShardRuntime.releaseShared(dataSource);
            }
            // Should the failed start have kept its borrow, the shared runtime must still not outlive the test.
            held.stop();
        }
    }

    /**
     * Make every lease acquire by {@code instanceId} fail, as a database error would, or stop doing so. A trigger rather
     * than a revoked grant: the test's role owns the table, and an owner keeps every privilege.
     */
    private void failLeaseAcquiresBy(String instanceId, boolean fail) throws Exception {
        try (var connection = dataSource.getConnection(); var statement = connection.createStatement()) {
            if (fail) {
                statement.execute("CREATE OR REPLACE FUNCTION fail_lease_acquire() RETURNS trigger AS $$ BEGIN "
                                          + "RAISE EXCEPTION 'simulated failure acquiring a lease on % unit %', NEW.lane, NEW.shard; "
                                          + "END $$ LANGUAGE plpgsql");
                statement.execute("CREATE TRIGGER fail_lease_acquire BEFORE UPDATE ON " + ShardOwnedSchema.LEASE_TABLE
                                          + " FOR EACH ROW WHEN (OLD.owner IS NULL AND NEW.owner = '" + instanceId + "')"
                                          + " EXECUTE FUNCTION fail_lease_acquire()");
            } else {
                statement.execute("DROP TRIGGER IF EXISTS fail_lease_acquire ON " + ShardOwnedSchema.LEASE_TABLE);
            }
        }
    }

    /**
     * Free unit 0 of {@code lane}, hold the heartbeat tick that leases it again inside the lease UPDATE (see
     * {@link #holdTheNextAcquireOfUnitZero}), stop the queue while it sleeps and wait for that tick to finish.
     */
    private void stopWhileAHeartbeatTickAcquiresUnitZero(ShardOwnedQueue queue, ShardRuntime runtime, String lane, String instanceId) throws Exception {
        try {
            holdTheNextAcquireOfUnitZero(lane, instanceId, 1.5);

            queue.stop();

            awaitHeartbeatTickFinished(runtime);
        } finally {
            dropSlowAcquire();
            queue.stop();
        }
    }

    /**
     * Give unit 0 of {@code lane} to an instance that is not running, and return once the heartbeat tick that leases it
     * again is held inside the lease UPDATE, by a trigger that sleeps {@code sleepSeconds}. The tick's lease check finds the
     * unit gone and ends its owner, and its rebalance drops that owner and acquires the unit again - into the trigger. The
     * trigger fires only on an acquire of unit 0 by {@code instanceId} from another owner, so another acquire of the unit by
     * the same instance, once the tick has taken it, does not sleep. The unit is handed to a departed instance rather than
     * released, so a test refusing releases can be set up before it. Drop the trigger with {@link #dropSlowAcquire}.
     */
    private void holdTheNextAcquireOfUnitZero(String lane, String instanceId, double sleepSeconds) throws Exception {
        holdTheNextAcquireOf(lane, instanceId, 0, sleepSeconds);
    }

    /**
     * {@link #holdTheNextAcquireOfUnitZero}, for {@code unit} instead of unit 0.
     */
    private void holdTheNextAcquireOf(String lane, String instanceId, int unit, double sleepSeconds) throws Exception {
        try (var connection = dataSource.getConnection(); var statement = connection.createStatement()) {
            statement.execute("CREATE OR REPLACE FUNCTION slow_acquire() RETURNS trigger AS $$ BEGIN "
                                      + "PERFORM pg_sleep(" + sleepSeconds + "); RETURN NEW; END $$ LANGUAGE plpgsql");
            statement.execute("CREATE TRIGGER slow_acquire BEFORE UPDATE ON " + ShardOwnedSchema.LEASE_TABLE
                                      + " FOR EACH ROW WHEN (OLD.owner IS DISTINCT FROM NEW.owner AND NEW.owner = '" + instanceId + "'"
                                      + " AND NEW.lane = '" + lane + "' AND NEW.shard = " + unit + ")"
                                      + " EXECUTE FUNCTION slow_acquire()");
            statement.execute("UPDATE " + ShardOwnedSchema.LEASE_TABLE + " SET owner = 'departed' WHERE queue_id = " + QUEUE_ID
                                      + " AND lane = '" + lane + "' AND shard = " + unit);
        }
        Awaitility.await().atMost(Duration.ofSeconds(10)).until(this::aHeartbeatIsSleeping);
    }

    /**
     * The monitor {@code queue} decides under whether a heartbeat tick's acquisition joins its run (its private
     * {@code runLock}, taken by {@code adoptIntoRun}). A test holding it keeps every tick of the queue from adopting or
     * refusing a unit until it lets go.
     */
    private static Object runLockOf(ShardOwnedQueue queue) throws ReflectiveOperationException {
        var field = ShardOwnedQueue.class.getDeclaredField("runLock");
        field.setAccessible(true);
        return field.get(queue);
    }

    private void dropSlowAcquire() throws Exception {
        try (var connection = dataSource.getConnection(); var statement = connection.createStatement()) {
            statement.execute("DROP TRIGGER IF EXISTS slow_acquire ON " + ShardOwnedSchema.LEASE_TABLE);
        }
    }

    /**
     * Wait until the heartbeat tick running now, if any, has finished: every queue's heartbeat runs on the runtime's one
     * heartbeat thread, so a task queued behind it runs only once the tick is done.
     */
    private static void awaitHeartbeatTickFinished(ShardRuntime runtime) throws InterruptedException {
        var behindTheTick = new CountDownLatch(1);
        var task          = runtime.scheduleHeartbeat(behindTheTick::countDown, 1);
        try {
            assertThat(behindTheTick.await(20, TimeUnit.SECONDS)).as("the overlapping heartbeat tick finished").isTrue();
        } finally {
            task.cancel(false);
        }
    }

    private int unorderedShardsOwnedBy(String instanceId) throws Exception {
        try (var connection = dataSource.getConnection();
             var statement = connection.prepareStatement("SELECT count(*) FROM " + ShardOwnedSchema.LEASE_TABLE
                                                                 + " WHERE queue_id = ? AND lane = 'unordered' AND owner = ?")) {
            statement.setShort(1, QUEUE_ID);
            statement.setString(2, instanceId);
            try (var resultSet = statement.executeQuery()) {
                resultSet.next();
                return resultSet.getInt(1);
            }
        }
    }

    private boolean aHeartbeatIsSleeping() throws Exception {
        try (var connection = dataSource.getConnection();
             var statement = connection.createStatement();
             var resultSet = statement.executeQuery("SELECT count(*) FROM pg_stat_activity WHERE wait_event = 'PgSleep'")) {
            resultSet.next();
            return resultSet.getInt(1) > 0;
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
