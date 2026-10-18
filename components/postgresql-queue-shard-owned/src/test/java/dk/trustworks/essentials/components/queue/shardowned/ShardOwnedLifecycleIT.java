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
import ch.qos.logback.core.UnsynchronizedAppenderBase;
import ch.qos.logback.core.read.ListAppender;
import com.zaxxer.hikari.*;
import dk.trustworks.essentials.components.queue.shardowned.spi.*;
import dk.trustworks.essentials.shared.Lifecycle;
import org.awaitility.Awaitility;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.*;
import org.slf4j.LoggerFactory;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.*;

import javax.sql.DataSource;
import java.io.PrintWriter;
import java.nio.charset.StandardCharsets;
import java.sql.*;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import java.util.function.*;

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
     * then starts is whole. The stop ends its run at its top and drains for up to {@code shedGrace} before it drops
     * its owners. A start in that window used to mark the queue running again and list its own owners, and the stop's final
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
                // The wait itself, asserted while the stop is still in its drain: the final state alone cannot show it, since
                // the stop works only on the run it took out and its fenced release leaves rows re-taken under newer fences.
                Awaitility.await().during(Duration.ofMillis(500)).atMost(Duration.ofSeconds(1)).until(() -> !starting.isDone());
                assertThat(stopping).as("the stop is still draining the held handler").isNotDone();
                assertThat(starting).as("start waits for the draining stop").isNotDone();
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
                reported.assertEveryAcquisitionIsReleasedOnceAfterIt();
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

    /**
     * Every unit {@code stop()} gives up is reported released, the one it keeps included. An ordered unit with a key still
     * in a handler after the grace keeps its lease row until this instance's liveness lapses, but the stopped queue lists
     * it no more, so nothing would report it later: it used to be left reported acquired for good, and the observer's -
     * and the Micrometer counter's - acquired count ran one ahead of its released count after every such stop.
     */
    @Test
    void an_ordered_unit_kept_by_stop_is_reported_released() throws Exception {
        var entered    = new CountDownLatch(1);
        var release    = new CountDownLatch(1);
        var settings   = settingsWithShedGrace(Duration.ofMillis(300));
        var instanceId = "kept-reported";
        var reported   = new RecordedOwnership();
        var metrics    = new ShardOwnerMetrics(reported);
        try (var runtime = new ShardRuntime(dataSource, settings)) {
            var queue = new ShardOwnedQueue(dataSource, QUEUE_ID, SHARD_COUNT, instanceId, metrics).useRuntime(runtime);
            try {
                queue.startConsumingOrdered((messageId, key, payload, payloadType) -> {
                    entered.countDown();
                    awaitQuietly(release);
                }, settings, SHARD_COUNT);
                queue.enqueueOrdered(List.of(new ShardOwnedStorage.OrderedPayload("ACC-1", 0, "0".getBytes(StandardCharsets.UTF_8), 1)));
                assertThat(entered.await(20, TimeUnit.SECONDS)).isTrue();

                queue.stop();

                assertThat(orderedUnitsOwnedBy(instanceId)).as("premise: the busy unit is kept").isEqualTo(1);
                reported.assertEveryAcquisitionIsReleasedOnceAfterIt();
                assertThat(metrics.shardsReleased.sum()).as("every acquisition counted released, the kept unit's included")
                                                        .isEqualTo(metrics.shardsAcquired.sum());
            } finally {
                release.countDown();
                queue.stop();
            }
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
                if (!resultSet.next()) {
                    throw new IllegalStateException("No lease row for queue " + QUEUE_ID + " " + lane + " unit " + shard);
                }
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
            reported.assertEveryAcquisitionIsReleasedOnceAfterIt();

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
            reported.assertEveryAcquisitionIsReleasedOnceAfterIt();

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
     * the same instance id, does not hand its owner to the new run. The restarted queue was marked running again, and
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
     * as the start places its units once it has leased them all, so the test holds the monitor of the stopped run - the
     * one the tick refuses under, and not the one the new run adopts its units under - from just after the start has
     * installed its run until the start has returned.
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

            var stoppedRun = queue.currentRunMonitor();
            queue.stop();
            assertThat(aHeartbeatIsSleeping()).as("premise: the tick was still acquiring when the queue stopped").isTrue();
            var lostBefore        = metrics.leasesLost.sum();
            var acquiredBefore    = metrics.shardsAcquired.sum();
            var reportsBeforeRestart = reported.events().size();
            // The new run's start acquires unit 0 once the held tick has committed its acquire, under a newer fence. The tick
            // decides whether to adopt its owner under the monitor of the run it was scheduled for (adoptIntoRun), the stopped
            // one; the start adopts its own units under its new run's. Holding the stopped run's from once the new run is
            // installed until the start has returned keeps the tick from refusing until the new run has placed unit 0,
            // whichever of the two would otherwise have got there first.
            var starting = CompletableFuture.runAsync(() -> {
                try {
                    startConsuming(queue, lane, settings, delivered);
                } catch (SQLException e) {
                    throw new IllegalStateException(e);
                }
            });
            Awaitility.await().atMost(Duration.ofSeconds(10)).until(queue::isStarted);
            synchronized (stoppedRun) {
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
                if (!resultSet.next()) {
                    throw new IllegalStateException("No lease row for queue " + QUEUE_ID + " " + lane + " unit " + shard);
                }
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
            reported.assertEveryAcquisitionIsReleasedOnceAfterIt();
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
            reported.assertEveryAcquisitionIsReleasedOnceAfterIt();
        } finally {
            queue.stop();
            ShardRuntime.releaseShared(dataSource);
        }
    }

    /**
     * A shard (unordered) or unit (ordered) another instance takes from a running queue is reported released, by the
     * heartbeat tick whose rebalance drops the owner its lease check found taken, before the queue acquires it again. It
     * used to be dropped unreported: every unit lost to a takeover left the observer's acquired count - and so the
     * Micrometer counter's - one further ahead of its released count, for good. Units 0 and 1 are handed to a departed
     * instance, which leaves them free, so the tick that finds them taken acquires them again.
     */
    @ParameterizedTest
    @ValueSource(strings = {"unordered", "ordered"})
    void a_unit_found_taken_is_reported_released_before_it_is_acquired_again(String lane) throws Exception {
        var settings   = settingsWithShedGrace(Duration.ofSeconds(1));
        var instanceId = "taken-reported-" + lane;
        var runtime    = ShardRuntime.acquireShared(dataSource, settings);
        var reported   = new RecordedOwnership();
        var metrics    = new ShardOwnerMetrics(reported);
        var queue      = new ShardOwnedQueue(dataSource, QUEUE_ID, SHARD_COUNT, instanceId, metrics);
        try {
            startConsuming(queue, lane, settings);
            var units = unitsOwnedBy(lane, instanceId);
            assertThat(units).as("premise: the sole instance holds units 0 and 1").isGreaterThan(1);
            try (var connection = dataSource.getConnection(); var statement = connection.createStatement()) {
                statement.execute("UPDATE " + ShardOwnedSchema.LEASE_TABLE + " SET owner = 'departed' WHERE queue_id = " + QUEUE_ID
                                          + " AND lane = '" + lane + "' AND shard IN (0, 1)");
            }
            Awaitility.await().atMost(Duration.ofSeconds(10))
                      .untilAsserted(() -> assertThat(metrics.shardsAcquired.sum()).as("premise: a heartbeat tick took units 0 and 1 back")
                                                                                .isEqualTo(units + 2L));
            // The tick tells the observer of what it acquired once its acquire loop has ended.
            awaitHeartbeatTickFinished(runtime);

            for (var unit : List.of(0, 1)) {
                assertThat(reported.events()).filteredOn(event -> event.shard() == unit)
                                             .as("unit %d reported acquired at start-up, released when found taken, acquired again", unit)
                                             .containsExactly(new Reported(unit, true), new Reported(unit, false), new Reported(unit, true));
            }
            assertThat(metrics.shardsReleased.sum()).as("the two units found taken are counted released").isEqualTo(2L);

            queue.stop();

            reported.assertEveryAcquisitionIsReleasedOnceAfterIt();
            assertThat(metrics.shardsReleased.sum()).as("every acquisition counted released once")
                                                    .isEqualTo(metrics.shardsAcquired.sum());
        } finally {
            queue.stop();
            ShardRuntime.releaseShared(dataSource);
        }
    }

    /**
     * An observer that throws for the first unit a start-up reports acquired is still told of every other one, so the
     * releases {@code stop()} reports later each follow an acquisition. The start used to tell it unit by unit and stop at
     * the throw: every unit after the first was left untold acquired, yet counted acquired and so reported released at
     * stop, and the observer saw more releases than acquisitions. The observer's failure still escapes the start, first.
     * A non-critical {@code Error}, a test double's {@code AssertionError}, is no different: the units after it are told
     * too, and it escapes as it was thrown.
     */
    @ParameterizedTest
    @CsvSource({"unordered, IllegalStateException", "ordered, IllegalStateException", "unordered, AssertionError", "ordered, AssertionError"})
    void an_observer_throwing_for_a_start_up_acquisition_is_still_told_of_every_unit_acquired(String lane, String thrownType) throws Exception {
        var settings   = settingsWithShedGrace(Duration.ofSeconds(1));
        var instanceId = "start-observer-throws-" + lane + "-" + thrownType;
        var reported   = new RecordedOwnership();
        var failure    = simulatedObserverFailure(thrownType, "simulated observer failure on the first acquisition");
        var thrown     = new AtomicBoolean();
        var metrics = new ShardOwnerMetrics(new QueueObserver() {
            @Override
            public void shardOwnershipChanged(int shard, boolean acquired) {
                reported.shardOwnershipChanged(shard, acquired);
                if (acquired && thrown.compareAndSet(false, true)) {
                    throwAsThrown(failure);
                }
            }
        });
        var queue = new ShardOwnedQueue(dataSource, QUEUE_ID, SHARD_COUNT, instanceId, metrics);
        try {
            assertThatThrownBy(() -> startConsuming(queue, lane, settings)).as("the observer's failure escapes the start").isSameAs(failure);
            var units = unitsOwnedBy(lane, instanceId);
            assertThat(units).as("premise: the start acquired more than the unit the observer threw for").isGreaterThan(1);
            assertThat(reported.events()).filteredOn(Reported::acquired)
                                          .as("every unit the start acquired is reported acquired, the ones after the throw included")
                                          .hasSize(units);

            queue.stop();

            reported.assertEveryAcquisitionIsReleasedOnceAfterIt();
            assertThat(metrics.shardsReleased.sum()).as("every acquisition counted released once")
                                                    .isEqualTo(metrics.shardsAcquired.sum());
        } finally {
            queue.stop();
        }
    }

    /**
     * An observer that throws for the first unit a stop reports released leaves nothing of the stop undone: every lease
     * row is released, the instance deregistered and every unit reported released, and the observer's failure escapes the
     * stop. The stop used to tell the observer of each release on its shutdown-budget thread, before the deregistration:
     * the throw ended that thread there, so the membership row stayed and held the surviving instances at a stale fair
     * share until it aged out, and the failure went to the thread's default handler instead of the caller. A non-critical
     * {@code Error}, a test double's {@code AssertionError}, is no different: every unit after it is still reported
     * released, and it escapes the stop as it was thrown.
     */
    @ParameterizedTest
    @CsvSource({"unordered, IllegalStateException", "ordered, IllegalStateException", "unordered, AssertionError", "ordered, AssertionError"})
    void an_observer_throwing_for_a_release_at_stop_still_lets_the_stop_release_every_unit_and_deregister(String lane, String thrownType) throws Exception {
        var settings   = settingsWithShedGrace(Duration.ofSeconds(1));
        var instanceId = "stop-observer-throws-" + lane + "-" + thrownType;
        var reported   = new RecordedOwnership();
        var failure    = simulatedObserverFailure(thrownType, "simulated observer failure on the first release");
        var thrown     = new AtomicBoolean();
        var metrics = new ShardOwnerMetrics(new QueueObserver() {
            @Override
            public void shardOwnershipChanged(int shard, boolean acquired) {
                reported.shardOwnershipChanged(shard, acquired);
                if (!acquired && thrown.compareAndSet(false, true)) {
                    throwAsThrown(failure);
                }
            }
        });
        var queue = new ShardOwnedQueue(dataSource, QUEUE_ID, SHARD_COUNT, instanceId, metrics);
        try {
            startConsuming(queue, lane, settings);
            assertThat(unitsOwnedBy(lane, instanceId)).as("premise: the start acquired more than the unit the observer throws for")
                                                      .isGreaterThan(1);
            assertThat(instanceRegistered(instanceId)).as("premise: the start registered the instance").isTrue();

            assertThatThrownBy(queue::stop).as("the observer's failure escapes the stop").isSameAs(failure);

            assertThat(instanceRegistered(instanceId)).as("the stop deregistered the instance despite the observer's throw").isFalse();
            assertThat(unitsOwnedBy(lane, instanceId)).as("the stop released every lease row").isZero();
            reported.assertEveryAcquisitionIsReleasedOnceAfterIt();
            assertThat(metrics.shardsReleased.sum()).as("every acquisition counted released once")
                                                    .isEqualTo(metrics.shardsAcquired.sum());
        } finally {
            queue.stop();
        }
    }

    /**
     * An observer that throws for every release still lets a {@code PostgresqlMessageQueue.stop()}, or a
     * {@code Subscription.stop()}, stop both lane consumers, and its failure escapes once they have. Each lane's consumer
     * tells the observer of its releases last, and throws its failure after that; the queue stopped its consumers one after
     * another and the subscription its two lanes, so the first consumer's throw skipped the stop of the ordered lane, which
     * went on leasing, heartbeating and dispatching after the queue reported itself stopped. A non-critical {@code Error},
     * a test double's {@code AssertionError}, is no different: only {@code RuntimeException} was caught, so it skipped the
     * ordered lane's stop all the same.
     */
    @ParameterizedTest
    @CsvSource({"queue, IllegalStateException", "subscription, IllegalStateException", "queue, AssertionError", "subscription, AssertionError"})
    void an_observer_throwing_for_every_release_still_lets_the_spi_queue_stop_both_lanes(String stoppedBy, String thrownType) throws Exception {
        var asAssertionError = "AssertionError".equals(thrownType);
        var instanceId       = "spi-stop-observer-throws-" + stoppedBy + "-" + thrownType;
        var throwing         = new AtomicBoolean(true);
        try (var queue = new PostgresqlMessageQueue(dataSource, QUEUE_ID, SHARD_COUNT, instanceId)) {
            queue.addObserver(throwingForEveryRelease(throwing, asAssertionError));
            var subscription = queue.consume((messageId, key, payload, payloadType) -> { }, ConsumerOptions.defaults());
            try {
                Awaitility.await().atMost(Duration.ofSeconds(20))
                          .alias("premise: both lanes hold units")
                          .until(() -> subscription.unorderedShardsHeld() > 0 && subscription.orderedUnitsHeld() > 0);

                assertThatThrownBy("queue".equals(stoppedBy) ? queue::stop : subscription::stop)
                        .as("the observer's failure escapes the stop")
                        .isInstanceOf(asAssertionError ? AssertionError.class : IllegalStateException.class)
                        .hasMessageStartingWith("simulated observer failure releasing");

                assertThat(subscription.isStarted()).as("neither lane's consumer is still started").isFalse();
                assertThat(unitsOwnedBy("unordered", instanceId)).as("no unordered lease row names the instance").isZero();
                assertThat(unitsOwnedBy("ordered", instanceId)).as("no ordered lease row names the instance").isZero();
                assertThat(instanceRegistered(instanceId)).as("the instance is deregistered").isFalse();
            } finally {
                throwing.set(false);
                subscription.stop();
            }
        }
    }

    /**
     * A {@code consume()} whose start fails stops both lanes and throws the start's failure even when the observer throws
     * for a release while it stops them: the stop's failure is added to the start's. It used to be thrown in its place,
     * so the caller saw an observer's failure for a start that had failed on the database. A trigger fails the ordered
     * lane's acquire of unit 1, after the unordered lane has started.
     */
    @Test
    void a_consume_whose_start_fails_throws_the_start_failure_when_the_observer_throws_while_it_stops_the_lanes() throws Exception {
        var instanceId = "spi-start-fails-observer-throws";
        var throwing   = new AtomicBoolean(true);
        failTheAcquireOf("ordered", instanceId, 1, true);
        try (var queue = new PostgresqlMessageQueue(dataSource, QUEUE_ID, SHARD_COUNT, instanceId)) {
            queue.addObserver(throwingForEveryRelease(throwing));

            var thrown = catchThrowable(() -> queue.consume((messageId, key, payload, payloadType) -> { }, ConsumerOptions.defaults()));

            assertThat(thrown).as("the start's failure escapes, not the observer's")
                              .isInstanceOf(IllegalStateException.class)
                              .hasCauseInstanceOf(SQLException.class)
                              .hasStackTraceContaining("simulated failure acquiring ordered unit 1");
            assertThat(thrown.getSuppressed()).as("the observer's failure while the lanes stopped is added to it")
                                              .anySatisfy(suppressed -> assertThat(suppressed)
                                                      .hasMessageStartingWith("simulated observer failure releasing"));
            assertThat(queue.isStarted()).isFalse();
            assertThat(unitsOwnedBy("unordered", instanceId)).as("no unordered lease row names the instance").isZero();
            assertThat(unitsOwnedBy("ordered", instanceId)).as("no ordered lease row names the instance").isZero();
            assertThat(instanceRegistered(instanceId)).as("the instance is deregistered").isFalse();
        } finally {
            throwing.set(false);
            failTheAcquireOf("ordered", instanceId, 1, false);
        }
    }

    /**
     * A {@code consume()} whose start throws a non-critical {@code Error} - an observer's {@code AssertionError} for an
     * acquisition, which escapes a lane's start with that lane running - stops both lanes and throws it. Only a
     * {@code RuntimeException} undid a failed start, so the lane went on leasing and dispatching with no subscription
     * returned to stop it.
     */
    @Test
    void a_consume_whose_start_throws_an_observer_error_stops_both_lanes_and_throws_it() throws Exception {
        var instanceId = "spi-start-observer-asserts";
        var throwing   = new AtomicBoolean(true);
        try (var queue = new PostgresqlMessageQueue(dataSource, QUEUE_ID, SHARD_COUNT, instanceId)) {
            queue.addObserver(new QueueObserver() {
                @Override
                public void shardOwnershipChanged(int shard, boolean acquired) {
                    if (acquired && throwing.get()) {
                        throw new AssertionError("simulated observer assertion acquiring unit " + shard);
                    }
                }
            });

            var thrown = catchThrowable(() -> queue.consume((messageId, key, payload, payloadType) -> { }, ConsumerOptions.defaults()));

            assertThat(thrown).as("the observer's error escapes the consume()")
                              .isInstanceOf(AssertionError.class)
                              .hasMessageStartingWith("simulated observer assertion acquiring");
            assertThat(queue.isStarted()).isFalse();
            assertThat(unitsOwnedBy("unordered", instanceId)).as("no unordered lease row names the instance").isZero();
            assertThat(unitsOwnedBy("ordered", instanceId)).as("no ordered lease row names the instance").isZero();
            assertThat(instanceRegistered(instanceId)).as("the instance is deregistered").isFalse();
        } finally {
            throwing.set(false);
        }
    }

    /**
     * A restart of the queue or of a subscription whose first lane's start throws - an observer failing for that lane's
     * acquisition, which escapes the start with the lane running - stops both lanes, throws the observer's failure and
     * leaves the restarted thing not started, so a second start runs again. Both started their consumers one after the
     * other and stopped at the first throw: the unordered lane went on leasing and dispatching, the ordered lane was never
     * started, and the queue reported itself started, so a second {@code start()} did nothing.
     */
    @ParameterizedTest
    @CsvSource({"queue, IllegalStateException", "subscription, IllegalStateException", "queue, AssertionError", "subscription, AssertionError"})
    void a_restart_whose_first_lane_start_throws_an_observer_failure_stops_both_lanes_and_starts_again(String startedBy, String thrownType) throws Exception {
        var instanceId = "spi-restart-observer-throws-" + startedBy + "-" + thrownType;
        var throwing   = new AtomicBoolean();
        try (var queue = new PostgresqlMessageQueue(dataSource, QUEUE_ID, SHARD_COUNT, instanceId)) {
            queue.addObserver(new QueueObserver() {
                @Override
                public void shardOwnershipChanged(int shard, boolean acquired) {
                    if (acquired && throwing.get()) {
                        throwAsThrown(simulatedObserverFailure(thrownType, "simulated observer failure acquiring unit " + shard));
                    }
                }
            });
            var subscription = queue.consume((messageId, key, payload, payloadType) -> { }, ConsumerOptions.defaults());
            Lifecycle restarted = "queue".equals(startedBy) ? queue : subscription;
            try {
                restarted.stop();
                assertThat(subscription.isStarted()).as("premise: both lanes stopped").isFalse();
                throwing.set(true);

                assertThatThrownBy(restarted::start)
                        .as("the observer's failure escapes the start")
                        .isInstanceOf("AssertionError".equals(thrownType) ? AssertionError.class : IllegalStateException.class)
                        .hasMessageStartingWith("simulated observer failure acquiring");

                assertThat(restarted.isStarted()).as("the restarted %s is not started", startedBy).isFalse();
                assertThat(subscription.isStarted()).as("neither lane's consumer is still started").isFalse();
                assertThat(unitsOwnedBy("unordered", instanceId)).as("no unordered lease row names the instance").isZero();
                assertThat(unitsOwnedBy("ordered", instanceId)).as("no ordered lease row names the instance").isZero();

                throwing.set(false);
                restarted.start();

                assertThat(restarted.isStarted()).as("a second start runs again").isTrue();
                Awaitility.await().atMost(Duration.ofSeconds(20))
                          .alias("both lanes hold units after the second start")
                          .until(() -> subscription.unorderedShardsHeld() > 0 && subscription.orderedUnitsHeld() > 0);
            } finally {
                throwing.set(false);
                subscription.stop();
            }
        }
    }

    /**
     * A new {@code AssertionError} when {@code thrownType} is {@code "AssertionError"}, a new
     * {@code IllegalStateException} when it is {@code "IllegalStateException"}: the two kinds of observer failure the
     * engine collects alike, a {@code RuntimeException} and a non-critical {@code Error}.
     */
    private static Throwable simulatedObserverFailure(String thrownType, String message) {
        return switch (thrownType) {
            case "AssertionError" -> new AssertionError(message);
            case "IllegalStateException" -> new IllegalStateException(message);
            default -> throw new IllegalArgumentException("Unknown failure type " + thrownType);
        };
    }

    /**
     * Throw {@code failure}, which {@link #simulatedObserverFailure} built as a {@code RuntimeException} or an
     * {@code Error}, as the instance it is.
     */
    private static void throwAsThrown(Throwable failure) {
        switch (failure) {
            case RuntimeException e -> throw e;
            case Error e -> throw e;
            default -> throw new IllegalArgumentException("Neither a RuntimeException nor an Error", failure);
        }
    }

    /**
     * An observer that throws a new {@code IllegalStateException} for every release it is told of, while {@code throwing}
     * is set.
     */
    private static QueueObserver throwingForEveryRelease(AtomicBoolean throwing) {
        return throwingForEveryRelease(throwing, false);
    }

    /**
     * An observer that throws a new {@code AssertionError} when {@code asAssertionError}, a new
     * {@code IllegalStateException} otherwise, for every release it is told of, while {@code throwing} is set.
     */
    private static QueueObserver throwingForEveryRelease(AtomicBoolean throwing, boolean asAssertionError) {
        return new QueueObserver() {
            @Override
            public void shardOwnershipChanged(int shard, boolean acquired) {
                if (!acquired && throwing.get()) {
                    var message = "simulated observer failure releasing unit " + shard;
                    if (asAssertionError) {
                        throw new AssertionError(message);
                    }
                    throw new IllegalStateException(message);
                }
            }
        };
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
         * For a queue that has stopped: per unit, acquisitions and releases alternate, an acquisition first, and the last
         * one is a release. No release is reported twice, or before the acquisition it ends, and no acquisition goes
         * unmatched - whether the unit was released by the queue, found taken by another instance, or kept or left
         * unreleased by the stop.
         */
        void assertEveryAcquisitionIsReleasedOnceAfterIt() {
            var held = new HashMap<Integer, Boolean>();
            for (var event : events) {
                if (event.acquired()) {
                    assertThat(held.getOrDefault(event.shard(), false))
                            .as("unit %d is reported acquired only while reported released; reported: %s", event.shard(), events)
                            .isFalse();
                    held.put(event.shard(), true);
                } else {
                    assertThat(held.getOrDefault(event.shard(), false))
                            .as("unit %d is reported released only while reported acquired; reported: %s", event.shard(), events)
                            .isTrue();
                    held.put(event.shard(), false);
                }
            }
            assertThat(held.entrySet()).filteredOn(Map.Entry::getValue)
                                       .extracting(Map.Entry::getKey)
                                       .as("units reported acquired and never reported released, once the queue has stopped; reported: %s", events)
                                       .isEmpty();
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
     * A {@code start()} that fails with an unchecked exception is not started, and a second {@code start()} runs again.
     * Only an {@link SQLException} used to undo the run the start had installed: anything else - the ordered lane's
     * refusal to start where {@code backend_xid} is not readable, which is an {@link IllegalStateException} by design, or
     * a pool or proxy that fails unchecked - escaped with the run still installed. The queue then reported itself started
     * with nothing leased and no heartbeat, and a retried start did nothing, since a run was already installed. The
     * failure is made the start's first database access - the ordered lane's prerequisites check, the unordered lane's
     * membership row - by a {@link DataSource} that refuses connections unchecked while told to.
     */
    @ParameterizedTest
    @ValueSource(strings = {"unordered", "ordered"})
    void a_start_failing_with_an_unchecked_exception_is_not_started_and_a_second_start_runs_again(String lane) throws Exception {
        var settings   = settingsWithShedGrace(Duration.ofSeconds(1));
        var instanceId = "unchecked-start-failure-" + lane;
        var refusing   = new ConnectionRefusingDataSource(dataSource);
        var queue      = new ShardOwnedQueue(refusing, QUEUE_ID, SHARD_COUNT, instanceId);
        try {
            refusing.refuse(true);
            assertThatThrownBy(() -> startConsuming(queue, lane, settings))
                    .as("an unchecked failure escapes the start as it was thrown")
                    .isSameAs(refusing.failure);
            assertThat(refusing.refused()).as("premise: the start's first database access was refused").isPositive();
            assertThat(queue.isStarted()).as("a start that failed is not started").isFalse();
            assertThat(queue.shardsHeld()).isZero();

            refusing.refuse(false);
            startConsuming(queue, lane, settings);

            assertThat(queue.isStarted()).as("the second start ran").isTrue();
            var units = unitsOwnedBy(lane, instanceId);
            assertThat(units).as("the second start leased").isPositive();
            assertThat(queue.shardsHeld()).isEqualTo(units);
        } finally {
            refusing.refuse(false);
            queue.stop();
        }
        assertThat(unitsOwnedBy(lane, instanceId)).as("the stop released what the second start leased").isZero();
    }

    /**
     * A {@code start()} whose lease loop fails part-way leaves no lease row naming the instance. The start fails, so no
     * owner is built for the units it had already leased, and no stop or heartbeat of it will ever release them: they
     * used to stay leased to the instance, served by nobody, and no other instance could take them for as long as the
     * instance's membership row - written before the loop - was live. A trigger fails the acquire of unit 1 only, after
     * the start has leased unit 0.
     */
    @ParameterizedTest
    @ValueSource(strings = {"unordered", "ordered"})
    void a_start_failing_part_way_through_its_lease_loop_releases_the_units_it_had_leased(String lane) throws Exception {
        var settings   = settingsWithShedGrace(Duration.ofSeconds(1));
        var instanceId = "start-fails-mid-lease-" + lane;
        var queue      = new ShardOwnedQueue(dataSource, QUEUE_ID, SHARD_COUNT, instanceId);
        failTheAcquireOf(lane, instanceId, 1, true);
        try {
            assertThatThrownBy(() -> startConsuming(queue, lane, settings))
                    .isInstanceOf(IllegalStateException.class)
                    .hasCauseInstanceOf(SQLException.class)
                    .as("premise: the start failed acquiring unit 1, where the trigger fires, after it had leased unit 0")
                    .hasStackTraceContaining("simulated failure acquiring " + lane + " unit 1");
            assertThat(queue.isStarted()).isFalse();
            assertThat(unitsOwnedBy(lane, instanceId)).as("no lease row names the instance whose start failed").isZero();
        } finally {
            failTheAcquireOf(lane, instanceId, 1, false);
            queue.stop();
        }
        assertThat(new ShardOwnedStorage(dataSource, QUEUE_ID).acquireLeaseGrant(lane, 0, "successor", settings.leaseTtlMillis()))
                .as("another instance takes unit 0 at once, while the failed instance's membership row is still live").isPresent();
    }

    /**
     * A {@code start()} that fails after it has adopted units ends them as a {@code stop()} ends a run - drained, ended and
     * released, the observer told of each one's acquisition before its release - but keeps the instance's membership row:
     * the start's Javadoc leaves it to age out rather than deleting it, so the sibling lane sharing the instance id keeps
     * its membership. Every other failing start in this class fails before any unit is adopted, with nothing in its run.
     * Here the heartbeat's scheduling fails: the test stops the runtime it passed the queue while the start sleeps in the
     * acquire of unit 1, and a stopped runtime's heartbeat refuses the task - by when the start has adopted every unit it
     * leased.
     */
    @ParameterizedTest
    @ValueSource(strings = {"unordered", "ordered"})
    void a_start_that_fails_after_adopting_units_releases_them_and_keeps_the_membership_row(String lane) throws Exception {
        var settings   = settingsWithShedGrace(Duration.ofSeconds(1));
        var instanceId = "fails-after-adoption-" + lane;
        var reported   = new RecordedOwnership();
        var metrics    = new ShardOwnerMetrics(reported);
        try (var runtime = new ShardRuntime(dataSource, settings)) {
            var queue = ShardOwnedQueue.builder().setDataSource(dataSource).setQueueId(QUEUE_ID).setShardCount(SHARD_COUNT)
                                       .setInstanceId(instanceId).setRuntime(runtime).setMetrics(metrics).build();
            try {
                slowTheAcquireOf(lane, instanceId, 1, 3);
                var starting = CompletableFuture.supplyAsync(() -> catchThrowable(() -> startConsuming(queue, lane, settings)));
                Awaitility.await().atMost(Duration.ofSeconds(10)).until(this::aHeartbeatIsSleeping);

                runtime.stop();

                var thrown = starting.get(30, TimeUnit.SECONDS);
                assertThat(thrown).as("the heartbeat's refusal escapes the start as it was thrown")
                                  .isInstanceOf(RejectedExecutionException.class);
                assertThat(metrics.shardsAcquired.sum()).as("premise: the start had adopted units when it failed").isGreaterThan(1L);
                assertThat(queue.isStarted()).as("a start that failed is not started").isFalse();
                assertThat(unitsOwnedBy(lane, instanceId)).as("the failed start released every unit it had adopted").isZero();
                assertThat(instanceRegistered(instanceId)).as("a failed start keeps the membership row, for it to age out").isTrue();
                reported.assertEveryAcquisitionIsReleasedOnceAfterIt();
                assertThat(metrics.shardsReleased.sum()).as("every acquisition counted released once")
                                                        .isEqualTo(metrics.shardsAcquired.sum());
                assertThat(runtime.wakeupsHeld()).as("no wake-up of the failed start is left on the runtime").isZero();
            } finally {
                dropSlowAcquire();
                queue.stop();
            }
        }
    }

    /**
     * A {@code start()} whose owner for a later unit cannot be built releases every unit - those adopted before it are
     * ended with the run, that unit and those leased after it released again - keeps the membership row, and leaves no
     * wake-up behind on the runtime it was passed, which keeps running. The unit's placement used to keep its wake-up: the
     * runtime forgets the wake-ups of the owners a run lists when the run ends, and the unit whose owner could not be built
     * was never listed, so its entry stayed until the runtime stopped. No real owner build fails short of a critical error,
     * so the test makes the build of unit 1 throw ({@code ownerBuildFault}), after unit 0 has been adopted.
     */
    @ParameterizedTest
    @ValueSource(strings = {"unordered", "ordered"})
    void a_start_whose_owner_for_a_later_unit_cannot_be_built_releases_every_unit_and_leaves_no_wake_up_behind(String lane) throws Exception {
        var settings   = settingsWithShedGrace(Duration.ofSeconds(1));
        var instanceId = "owner-build-fails-" + lane;
        var reported   = new RecordedOwnership();
        var metrics    = new ShardOwnerMetrics(reported);
        var failure    = new IllegalStateException("simulated failure building the owner of unit 1");
        try (var runtime = new ShardRuntime(dataSource, settings)) {
            var queue = ShardOwnedQueue.builder().setDataSource(dataSource).setQueueId(QUEUE_ID).setShardCount(SHARD_COUNT)
                                       .setInstanceId(instanceId).setRuntime(runtime).setMetrics(metrics).build();
            var buildsAttempted = new CopyOnWriteArrayList<Integer>();
            queue.ownerBuildFault = (builtLane, unit) -> {
                buildsAttempted.add(unit);
                if (unit == 1) {
                    throw failure;
                }
            };
            try {
                assertThatThrownBy(() -> startConsuming(queue, lane, settings))
                        .as("the build's failure escapes the start as it was thrown")
                        .isSameAs(failure);
                assertThat(buildsAttempted).as("premise: unit 0 was adopted before the build of unit 1 failed").containsExactly(0, 1);
                assertThat(queue.isStarted()).as("a start that failed is not started").isFalse();
                assertThat(unitsOwnedBy(lane, instanceId)).as("no lease row names the instance whose start failed").isZero();
                assertThat(instanceRegistered(instanceId)).as("a failed start keeps the membership row, for it to age out").isTrue();
                assertThat(reported.events()).as("unit 0 is reported acquired, then released")
                                             .containsExactly(new Reported(0, true), new Reported(0, false));
                reported.assertEveryAcquisitionIsReleasedOnceAfterIt();
                assertThat(metrics.shardsAcquired.sum()).as("only unit 0 was adopted").isEqualTo(1L);
                assertThat(metrics.shardsReleased.sum()).as("its release counted once").isEqualTo(1L);
                assertThat(runtime.isStarted()).as("premise: the runtime the queue was passed keeps running").isTrue();
                assertThat(runtime.wakeupsHeld()).as("neither unit 0's wake-up nor that of unit 1, whose owner was never built, is left on the runtime")
                                                 .isZero();
            } finally {
                queue.ownerBuildFault = (builtLane, unit) -> { };
                queue.stop();
            }
        }
    }

    /**
     * A {@code stop()} called while a {@code start()} is still leasing returns within its shutdown budget, and no part of
     * the start's run outlives it. The stop used to wait for the start to finish, its database work included, outside the
     * 2 s budget, and with nothing in the queue to bound it. It now waits up to the budget, then takes the run out without
     * waiting further; the start, once its database work returns, adopts nothing, schedules no heartbeat, and does the
     * stop's work itself - releasing the units it leased, handing back the runtime and deregistering the instance. Had the
     * start scheduled the run's heartbeat regardless, no stop would ever cancel it, and each of its ticks would keep the
     * stopped instance live in the membership table. A trigger holds the start inside the acquire of unit 1 for longer
     * than the budget.
     */
    @ParameterizedTest
    @ValueSource(strings = {"unordered", "ordered"})
    void a_stop_called_while_a_start_is_still_leasing_returns_within_its_budget_and_leaves_nothing_of_the_start_running(String lane) throws Exception {
        var settings   = settingsWithShedGrace(Duration.ofSeconds(1));
        var instanceId = "stop-mid-start-" + lane;
        var runtime    = ShardRuntime.acquireShared(dataSource, settings);
        var reported   = new RecordedOwnership();
        var metrics    = new ShardOwnerMetrics(reported);
        var queue      = new ShardOwnedQueue(dataSource, QUEUE_ID, SHARD_COUNT, instanceId, metrics);
        try {
            slowTheAcquireOf(lane, instanceId, 1, 5);
            var starting = CompletableFuture.runAsync(() -> startUnchecked(queue, lane, settings));
            Awaitility.await().atMost(Duration.ofSeconds(10)).until(this::aHeartbeatIsSleeping);
            assertThat(queue.isStarted()).as("premise: the start has installed its run").isTrue();

            var stopCalled = System.nanoTime();
            queue.stop();
            var stopTook = Duration.ofNanos(System.nanoTime() - stopCalled);

            assertThat(stopTook).as("the stop waits no longer than its 2 s budget, and a margin").isLessThan(Duration.ofMillis(3_500));
            assertThat(starting).as("premise: the start was still leasing when the stop returned").isNotDone();
            assertThat(queue.isStarted()).as("the stop ended the run the start installed").isFalse();

            starting.get(20, TimeUnit.SECONDS);

            assertThat(queue.isStarted()).as("the start, finding its run ended, left the queue stopped").isFalse();
            // More than one heartbeat interval (a third of the 1 s lease TTL): a heartbeat of the started run would have
            // registered the instance again by then.
            Awaitility.await().during(Duration.ofSeconds(1)).atMost(Duration.ofSeconds(5)).until(
                    () -> !instanceRegistered(instanceId) && unitsOwnedBy(lane, instanceId) == 0);
            assertThat(runtime.unitsServed()).as("no owner of the start is on the shared runtime's pumps").isZero();
            reported.assertEveryAcquisitionIsReleasedOnceAfterIt();
            assertThat(metrics.shardsReleased.sum()).as("every acquisition counted released once")
                                                    .isEqualTo(metrics.shardsAcquired.sum());
        } finally {
            dropSlowAcquire();
            queue.stop();
            ShardRuntime.releaseShared(dataSource);
        }
    }

    /**
     * A {@code stopAbruptly()} called while a {@code start()} is still leasing does not wait for it at all: it stands in
     * for a crash, which waits for nothing. It used to wait for the start to finish, its database work included. The start
     * then finds its run ended, adopts and serves nothing, and hands the runtime back.
     */
    @Test
    void a_stop_abruptly_called_while_a_start_is_still_leasing_does_not_wait_for_it() throws Exception {
        var settings   = settingsWithShedGrace(Duration.ofSeconds(1));
        var instanceId = "stop-abruptly-mid-start";
        var runtime    = ShardRuntime.acquireShared(dataSource, settings);
        var queue      = new ShardOwnedQueue(dataSource, QUEUE_ID, SHARD_COUNT, instanceId);
        try {
            slowTheAcquireOf("unordered", instanceId, 1, 3);
            var starting = CompletableFuture.runAsync(() -> startUnchecked(queue, "unordered", settings));
            Awaitility.await().atMost(Duration.ofSeconds(10)).until(this::aHeartbeatIsSleeping);

            var stopCalled = System.nanoTime();
            queue.stopAbruptly();
            var stopTook = Duration.ofNanos(System.nanoTime() - stopCalled);

            assertThat(stopTook).as("stopAbruptly does not wait for the start").isLessThan(Duration.ofMillis(1_000));
            assertThat(starting).as("premise: the start was still leasing when stopAbruptly returned").isNotDone();
            assertThat(queue.isStarted()).isFalse();

            starting.get(20, TimeUnit.SECONDS);

            assertThat(queue.isStarted()).as("the start, finding its run ended, left the queue stopped").isFalse();
            assertThat(runtime.unitsServed()).as("the start placed nothing on the shared runtime's pumps").isZero();
        } finally {
            dropSlowAcquire();
            queue.stop();
            ShardRuntime.releaseShared(dataSource);
        }
    }

    /**
     * A {@code stop()} on an interrupted thread, called while a {@code start()} is still leasing, returns at once with the
     * thread's interrupt flag still set, and says it was interrupted: the timed wait for the lifecycle lock throws at once
     * on an interrupted thread, and the WARN used to say the stop had given up waiting its full 2 s budget. The start,
     * once its database work returns, does the stop's work itself, as for a stop that waited out its budget.
     */
    @Test
    void a_stop_on_an_interrupted_thread_while_a_start_is_still_leasing_returns_at_once_and_keeps_the_interrupt() throws Exception {
        var settings    = settingsWithShedGrace(Duration.ofSeconds(1));
        var instanceId  = "interrupted-stop-mid-start";
        var logged      = new ListAppender<ILoggingEvent>();
        var queueLogger = (ch.qos.logback.classic.Logger) LoggerFactory.getLogger(ShardOwnedQueue.class);
        logged.start();
        queueLogger.addAppender(logged);
        var queue = new ShardOwnedQueue(dataSource, QUEUE_ID, SHARD_COUNT, instanceId);
        try {
            slowTheAcquireOf("unordered", instanceId, 1, 3);
            var starting = CompletableFuture.runAsync(() -> startUnchecked(queue, "unordered", settings));
            Awaitility.await().atMost(Duration.ofSeconds(10)).until(this::aHeartbeatIsSleeping);
            assertThat(queue.isStarted()).as("premise: the start has installed its run").isTrue();

            var stopTook         = new AtomicLong();
            var stillInterrupted = new AtomicBoolean();
            var stopping = new Thread(() -> {
                Thread.currentThread().interrupt();
                var stopCalled = System.nanoTime();
                queue.stop();
                stopTook.set(System.nanoTime() - stopCalled);
                stillInterrupted.set(Thread.currentThread().isInterrupted());
            });
            stopping.start();
            stopping.join(10_000);

            assertThat(stopping.isAlive()).as("the interrupted stop returned").isFalse();
            assertThat(Duration.ofNanos(stopTook.get())).as("an interrupted stop does not wait for the start").isLessThan(Duration.ofMillis(1_000));
            assertThat(stillInterrupted).as("the stop left the thread's interrupt flag set").isTrue();
            assertThat(starting).as("premise: the start was still leasing when the stop returned").isNotDone();
            assertThat(queue.isStarted()).as("the stop ended the run the start installed").isFalse();
            var warnings = logged.list.stream().map(ILoggingEvent::getFormattedMessage).filter(message -> message.contains("stop")).toList();
            assertThat(warnings).as("the WARN says the stop was interrupted, not that it waited out its budget")
                                .anyMatch(message -> message.contains("was interrupted"))
                                .noneMatch(message -> message.contains("gave up waiting"));

            starting.get(20, TimeUnit.SECONDS);

            assertThat(queue.isStarted()).as("the start, finding its run ended, left the queue stopped").isFalse();
            Awaitility.await().during(Duration.ofSeconds(1)).atMost(Duration.ofSeconds(5)).until(
                    () -> !instanceRegistered(instanceId) && unitsOwnedBy("unordered", instanceId) == 0);
        } finally {
            dropSlowAcquire();
            queue.stop();
            queueLogger.detachAppender(logged);
        }
    }

    /**
     * A {@code start()} called after a {@code stop()} that gave up waiting for an earlier start waits for that start - which
     * does the stop's work once its statements return - and says so: one WARN naming the queue and the instance once it
     * has waited the 2 s budget, however long it goes on waiting. It used to block with nothing in the log for as long as
     * the earlier start's statement did. It then starts the queue afresh. A trigger holds the earlier start inside the
     * acquire of unit 1 for 8 s.
     */
    @Test
    void a_start_called_while_an_earlier_start_still_owes_a_stop_waits_for_it_and_says_so_once() throws Exception {
        var settings    = settingsWithShedGrace(Duration.ofSeconds(1));
        var instanceId  = "restart-behind-start";
        var logged      = new ListAppender<ILoggingEvent>();
        var queueLogger = (ch.qos.logback.classic.Logger) LoggerFactory.getLogger(ShardOwnedQueue.class);
        logged.start();
        queueLogger.addAppender(logged);
        var queue = new ShardOwnedQueue(dataSource, QUEUE_ID, SHARD_COUNT, instanceId);
        try {
            slowTheAcquireOf("unordered", instanceId, 1, 8);
            var starting = CompletableFuture.runAsync(() -> startUnchecked(queue, "unordered", settings));
            Awaitility.await().atMost(Duration.ofSeconds(10)).until(this::aHeartbeatIsSleeping);
            queue.stop();
            assertThat(starting).as("premise: the stop handed its work to the start still leasing").isNotDone();

            var restarting = CompletableFuture.runAsync(queue::start);
            Awaitility.await().atMost(Duration.ofSeconds(5)).until(() -> !waitingToStart(logged).isEmpty());
            assertThat(restarting).as("the restart waits for the earlier start").isNotDone();
            assertThat(waitingToStart(logged).getFirst()).as("the WARN names the instance, the queue and what it waits for")
                                                         .contains("Instance " + instanceId)
                                                         .contains("queue " + QUEUE_ID)
                                                         .contains("an earlier start");

            // Waits for the held UPDATE to commit, so the restart's own acquire of unit 1 does not sleep.
            dropSlowAcquire();
            starting.get(20, TimeUnit.SECONDS);
            restarting.get(20, TimeUnit.SECONDS);

            assertThat(waitingToStart(logged)).as("one WARN, though the restart waited out more than one budget").hasSize(1);
            assertThat(queue.isStarted()).as("the restart started the queue afresh").isTrue();
            Awaitility.await().atMost(Duration.ofSeconds(10)).until(
                    () -> instanceRegistered(instanceId) && unitsOwnedBy("unordered", instanceId) > 0);
        } finally {
            dropSlowAcquire();
            queue.stop();
            queueLogger.detachAppender(logged);
        }
    }

    private static List<String> waitingToStart(ListAppender<ILoggingEvent> logged) {
        return List.copyOf(logged.list).stream()
                   .map(ILoggingEvent::getFormattedMessage)
                   .filter(message -> message.contains("waiting to start queue") || message.contains("waiting to consume queue"))
                   .toList();
    }

    /**
     * A {@code MessageQueue.stop()} called while a {@code consume()} or {@code start()} of the {@link PostgresqlMessageQueue}
     * is still leasing returns within its budget, and leaves nothing of that start running. The queue's stop used to take
     * the monitor {@code consume()} and {@code start()} hold across their consumers' starts, database work included, so
     * it waited for the whole of a start caught by a network partition and never reached the timed wait each
     * {@code ShardOwnedQueue.stop()} keeps to. It now waits up to the 2 s budget for the lock (on an interrupted thread
     * not at all), then stops without it: the unordered lane's consumer, still leasing, waits up to its own 2 s budget and
     * hands the stop's work to its start; the {@code consume()} or start then starts no further lane and stops both lanes
     * again. A trigger holds the unordered lane's start inside the acquire of shard 1 for 10 s.
     * <p>
     * A {@code Subscription.start()} after the queue's stop is such a start too. It took neither the queue's lock nor its
     * started flag, so the stop, finding the queue not started, returned at once having stopped nothing, and the
     * subscription's start went on to start both lanes, which no later stop of the queue reached.
     */
    @ParameterizedTest
    @CsvSource({"consume, false", "start, false", "consume, true", "subscription, false"})
    void a_message_queue_stop_called_while_a_start_is_still_leasing_returns_within_its_budget_and_leaves_nothing_running(String startedBy,
                                                                                                                          boolean interrupted) throws Exception {
        var instanceId   = "spi-stop-mid-" + startedBy + "-" + interrupted;
        var subscription = new AtomicReference<Subscription>();
        try (var queue = new PostgresqlMessageQueue(dataSource, QUEUE_ID, SHARD_COUNT, instanceId)) {
            CompletableFuture<Void> starting;
            if ("consume".equals(startedBy)) {
                slowTheAcquireOf("unordered", instanceId, 1, 10);
                starting = CompletableFuture.runAsync(() -> subscription.set(consumeUnchecked(queue)));
            } else {
                subscription.set(consumeUnchecked(queue));
                Awaitility.await().atMost(Duration.ofSeconds(20))
                          .alias("premise: both lanes hold units before the queue is stopped and started again")
                          .until(() -> subscription.get().unorderedShardsHeld() > 0 && subscription.get().orderedUnitsHeld() > 0);
                queue.stop();
                slowTheAcquireOf("unordered", instanceId, 1, 10);
                starting = "subscription".equals(startedBy) ? CompletableFuture.runAsync(subscription.get()::start)
                                                            : CompletableFuture.runAsync(queue::start);
            }
            Awaitility.await().atMost(Duration.ofSeconds(10)).until(this::aHeartbeatIsSleeping);

            var stopTook = new AtomicLong();
            var stopping = new Thread(() -> {
                if (interrupted) {
                    Thread.currentThread().interrupt();
                }
                var stopCalled = System.nanoTime();
                queue.stop();
                stopTook.set(System.nanoTime() - stopCalled);
            });
            stopping.start();
            stopping.join(20_000);

            assertThat(stopping.isAlive()).as("the stop returned").isFalse();
            assertThat(Duration.ofNanos(stopTook.get()))
                    .as(interrupted ? "an interrupted stop waits for neither the queue's lock nor the lane's start"
                                    : "the stop waits 2 s for the queue's lock and 2 s for the lane's start, and a margin")
                    .isLessThan(Duration.ofMillis(interrupted ? 1_500 : 5_500));
            assertThat(starting).as("premise: the " + startedBy + "() was still leasing when the stop returned").isNotDone();
            assertThat(queue.isStarted()).as("the stop left the queue not started").isFalse();

            starting.get(30, TimeUnit.SECONDS);

            assertThat(queue.isStarted()).as("the " + startedBy + "(), finding the stop, left the queue not started").isFalse();
            assertThat(subscription.get()).as("a consume() still returns its subscription").isNotNull();
            assertThat(subscription.get().isStarted()).as("neither lane is started").isFalse();
            // The start registered the instance and leased shard 0 before it reached shard 1: the stop's work, done by the
            // start, deregisters and releases them, and nothing leases or registers again afterwards.
            Awaitility.await().during(Duration.ofSeconds(1)).atMost(Duration.ofSeconds(10)).until(
                    () -> !instanceRegistered(instanceId) && unitsOwnedBy("unordered", instanceId) == 0 && unitsOwnedBy("ordered", instanceId) == 0);
        } finally {
            dropSlowAcquire();
        }
    }

    /**
     * A {@code MessageQueue.start()} called after a {@code MessageQueue.stop()} that gave up waiting for a {@code consume()}
     * waits for that {@code consume()} - which stops both lanes again once its starts return - and says so: one WARN naming
     * the queue and the instance once it has waited the 2 s budget, however long it goes on waiting. It used to block on
     * the queue's lifecycle lock with nothing in the log for as long as the lane's statement did. It then starts both
     * lanes afresh. A trigger holds the unordered lane's start inside the acquire of shard 1 for 10 s.
     */
    @Test
    void a_message_queue_start_called_while_an_earlier_consume_still_owes_a_stop_waits_for_it_and_says_so_once() throws Exception {
        var instanceId   = "spi-restart-behind-consume";
        var subscription = new AtomicReference<Subscription>();
        var logged       = new ListAppender<ILoggingEvent>();
        var queueLogger  = (ch.qos.logback.classic.Logger) LoggerFactory.getLogger(PostgresqlMessageQueue.class);
        logged.start();
        queueLogger.addAppender(logged);
        try (var queue = new PostgresqlMessageQueue(dataSource, QUEUE_ID, SHARD_COUNT, instanceId)) {
            slowTheAcquireOf("unordered", instanceId, 1, 10);
            var consuming = CompletableFuture.runAsync(() -> subscription.set(consumeUnchecked(queue)));
            Awaitility.await().atMost(Duration.ofSeconds(10)).until(this::aHeartbeatIsSleeping);
            queue.stop();
            assertThat(consuming).as("premise: the stop handed its work to the consume() still leasing").isNotDone();

            var restarting = CompletableFuture.runAsync(queue::start);
            Awaitility.await().atMost(Duration.ofSeconds(5)).until(() -> !waitingToStart(logged).isEmpty());
            assertThat(restarting).as("the restart waits for the earlier consume()").isNotDone();
            assertThat(waitingToStart(logged).getFirst()).as("the WARN names the instance, the queue and what it waits for")
                                                         .contains("Instance " + instanceId)
                                                         .contains("queue " + QUEUE_ID)
                                                         .contains("an earlier consume() or start()");

            // Waits for the held UPDATE to commit, so the restart's own acquire of shard 1 does not sleep.
            dropSlowAcquire();
            consuming.get(30, TimeUnit.SECONDS);
            restarting.get(30, TimeUnit.SECONDS);

            assertThat(waitingToStart(logged)).as("one WARN, though the restart waited out more than one budget").hasSize(1);
            assertThat(queue.isStarted()).as("the restart started the queue afresh").isTrue();
            Awaitility.await().atMost(Duration.ofSeconds(10)).until(
                    () -> subscription.get().unorderedShardsHeld() > 0 && subscription.get().orderedUnitsHeld() > 0);
        } finally {
            dropSlowAcquire();
            queueLogger.detachAppender(logged);
        }
    }

    /**
     * A {@code consume()} that a {@code MessageQueue.stop()} gave up waiting for leaves the queue not started without
     * marking it started on the way. It used to mark it started as it registered its pair, and to mark it not started
     * again only after waiting for every stop still stopping without the lock, so {@code isStarted()} read true after
     * a stop had returned with the queue stopped. A trigger holds the unordered lane's start inside the acquire of shard
     * 1 for 10 s; the first stop returns while it is held, and a second stop that also gave up waiting is held inside its
     * WARN, so the {@code consume()} waits for it with its pair registered.
     */
    @Test
    void a_consume_a_message_queue_stop_gave_up_waiting_for_never_reads_as_started_after_that_stop_returned() throws Exception {
        var instanceId        = "spi-stop-owed-not-started";
        var subscription      = new AtomicReference<Subscription>();
        var holdTheNextStop   = new AtomicBoolean();
        var secondStopHeld    = new CountDownLatch(1);
        var releaseSecondStop = new CountDownLatch(1);
        var holdingAppender = new UnsynchronizedAppenderBase<ILoggingEvent>() {
            @Override
            protected void append(ILoggingEvent event) {
                if (event.getFormattedMessage().contains("gave up waiting") && holdTheNextStop.compareAndSet(true, false)) {
                    secondStopHeld.countDown();
                    awaitQuietly(releaseSecondStop);
                }
            }
        };
        var queueLogger = (ch.qos.logback.classic.Logger) LoggerFactory.getLogger(PostgresqlMessageQueue.class);
        holdingAppender.start();
        queueLogger.addAppender(holdingAppender);
        try (var queue = new PostgresqlMessageQueue(dataSource, QUEUE_ID, SHARD_COUNT, instanceId)) {
            slowTheAcquireOf("unordered", instanceId, 1, 10);
            var consuming = new Thread(() -> subscription.set(consumeUnchecked(queue)));
            consuming.start();
            Awaitility.await().atMost(Duration.ofSeconds(10)).until(this::aHeartbeatIsSleeping);

            queue.stop();

            assertThat(consuming.isAlive()).as("premise: the consume() was still leasing when the first stop returned").isTrue();
            assertThat(queue.isStarted()).as("the first stop left the queue not started").isFalse();

            holdTheNextStop.set(true);
            var secondStop = new Thread(queue::stop);
            secondStop.start();
            assertThat(secondStopHeld.await(10, TimeUnit.SECONDS)).as("premise: a second stop gave up waiting and is held in its WARN").isTrue();
            // Read through the queue, not the consume()'s stack: the second stop is still counted as stopping without the
            // lock, and the consume() has parked for good - once its pair is registered, the one untimed wait it has left
            // is the wait for that stop (letGoOfLifecycle); before it, it is in its lane's statements and timed waits.
            Awaitility.await().atMost(Duration.ofSeconds(20)).during(Duration.ofMillis(500))
                      .alias("premise: the consume() has registered its pair and waits for the second stop to finish stopping")
                      .until(() -> queue.stopsWithoutLock() == 1 && consuming.getState() == Thread.State.WAITING);

            assertThat(queue.isStarted()).as("the consume() did not mark the queue started after the first stop returned").isFalse();

            releaseSecondStop.countDown();
            secondStop.join(20_000);
            consuming.join(20_000);

            assertThat(secondStop.isAlive()).as("the second stop returned").isFalse();
            assertThat(consuming.isAlive()).as("the consume() returned").isFalse();
            assertThat(queue.isStarted()).as("the consume() left the queue not started").isFalse();
            assertThat(subscription.get()).as("a consume() still returns its subscription").isNotNull();
            assertThat(subscription.get().isStarted()).as("neither lane is started").isFalse();
            Awaitility.await().during(Duration.ofSeconds(1)).atMost(Duration.ofSeconds(10)).until(
                    () -> !instanceRegistered(instanceId) && unitsOwnedBy("unordered", instanceId) == 0 && unitsOwnedBy("ordered", instanceId) == 0);
        } finally {
            releaseSecondStop.countDown();
            queueLogger.detachAppender(holdingAppender);
            dropSlowAcquire();
        }
    }

    /**
     * A {@code MessageQueue.stop()} called while another stop holds the queue's lifecycle lock past the 2 s budget returns
     * once it has waited that budget, without waiting for the other stop, which is stopping every consumer: the queue is
     * not started when it returns, and both lanes are stopped once the other stop is done. A handler held on a latch, with
     * a 10 s {@code shedGrace}, keeps the first stop draining the unordered lane while the second stop is called.
     */
    @Test
    void a_message_queue_stop_called_while_another_stop_is_still_stopping_returns_after_its_budget_without_waiting_for_it() throws Exception {
        var instanceId = "spi-stop-behind-a-stop";
        var entered    = new CountDownLatch(1);
        var release    = new CountDownLatch(1);
        try (var queue = new PostgresqlMessageQueue(dataSource, QUEUE_ID, SHARD_COUNT, instanceId, settingsWithShedGrace(Duration.ofSeconds(10)))) {
            var subscription = queue.consume((messageId, key, payload, payloadType) -> {
                entered.countDown();
                awaitQuietly(release);
            }, ConsumerOptions.defaults());
            try {
                queue.enqueue(List.of(Message.of("slow".getBytes(StandardCharsets.UTF_8), 1)));
                assertThat(entered.await(20, TimeUnit.SECONDS)).as("premise: a handler is running").isTrue();

                var firstStop = CompletableFuture.runAsync(queue::stop);
                Awaitility.await().atMost(Duration.ofSeconds(5)).alias("premise: the first stop holds the lock").until(() -> !queue.isStarted());

                var secondStopTook = new AtomicLong();
                var secondStop = CompletableFuture.runAsync(() -> {
                    var stopCalled = System.nanoTime();
                    queue.stop();
                    secondStopTook.set(System.nanoTime() - stopCalled);
                });

                assertThat(secondStop).as("the second stop returned without waiting for the first").succeedsWithin(Duration.ofSeconds(5));
                assertThat(Duration.ofNanos(secondStopTook.get())).as("the second stop waits 2 s for the queue's lock, and a margin")
                                                                  .isLessThan(ShardOwnedQueue.SHUTDOWN_BUDGET.plusMillis(1_500));
                assertThat(firstStop).as("premise: the first stop is still draining the held handler").isNotDone();
                assertThat(queue.isStarted()).as("the queue is not started when the second stop returns").isFalse();

                release.countDown();
                firstStop.get(20, TimeUnit.SECONDS);

                assertThat(queue.isStarted()).as("the queue stays not started").isFalse();
                assertThat(subscription.isStarted()).as("the first stop stopped both lanes").isFalse();
                Awaitility.await().during(Duration.ofSeconds(1)).atMost(Duration.ofSeconds(10)).until(
                        () -> !instanceRegistered(instanceId) && unitsOwnedBy("unordered", instanceId) == 0 && unitsOwnedBy("ordered", instanceId) == 0);
            } finally {
                release.countDown();
                subscription.stop();
            }
        }
    }

    /**
     * A {@code MessageQueue.stop()} arriving while a {@code consume()} is doing the stop it owes - after a stop called by an
     * observer on its own thread, told of the first acquisition - is owed again: the {@code consume()} waits for that
     * further stop to finish stopping, stops every consumer once more and only then lets go of the lock, with no stop left
     * owed. The queue then ends not started, with no lease or membership row naming the instance, and a later
     * {@code start()} starts both lanes again. An observer told of the {@code consume()}'s first release - the owed stop
     * stopping the unordered lane - holds it until the further stop has given up waiting for the lock. A stop left owed
     * after the {@code consume()} let go would make that {@code start()} start nothing.
     */
    @Test
    void a_message_queue_stop_arriving_while_a_consume_does_the_stop_it_owes_is_done_before_the_consume_lets_go() throws Exception {
        var instanceId      = "spi-stop-during-owed-stop";
        var stopOnAcquire   = new AtomicBoolean(true);
        var holderThread    = new AtomicReference<Thread>();
        var holdTheRelease  = new AtomicBoolean(true);
        var releaseHeld     = new CountDownLatch(1);
        var letTheReleaseGo = new CountDownLatch(1);
        var subscription    = new AtomicReference<Subscription>();
        try (var queue = new PostgresqlMessageQueue(dataSource, QUEUE_ID, SHARD_COUNT, instanceId)) {
            queue.addObserver(new QueueObserver() {
                @Override
                public void shardOwnershipChanged(int shard, boolean acquired) {
                    if (acquired && stopOnAcquire.compareAndSet(true, false)) {
                        holderThread.set(Thread.currentThread());
                        queue.stop();
                    } else if (!acquired && Thread.currentThread() == holderThread.get() && holdTheRelease.compareAndSet(true, false)) {
                        releaseHeld.countDown();
                        awaitQuietly(letTheReleaseGo);
                    }
                }
            });
            try {
                var consuming = new Thread(() -> subscription.set(consumeUnchecked(queue)));
                consuming.start();
                assertThat(releaseHeld.await(20, TimeUnit.SECONDS))
                        .as("premise: the consume() is doing the stop it owes, held in the observer told of a release").isTrue();
                assertThat(queue.stopsWithoutLock()).as("premise: no other stop is stopping yet").isZero();

                var furtherStop = new Thread(queue::stop);
                furtherStop.start();
                Awaitility.await().atMost(Duration.ofSeconds(10))
                          .alias("premise: the further stop gave up waiting for the lock and stops the queue without it")
                          .until(() -> queue.stopsWithoutLock() == 1);
                assertThat(consuming.isAlive()).as("premise: the consume() is still doing the stop it owes").isTrue();

                letTheReleaseGo.countDown();
                consuming.join(20_000);

                assertThat(consuming.isAlive()).as("the consume() returned").isFalse();
                assertThat(queue.stopsWithoutLock()).as("the consume() waited for the further stop before it let go").isZero();
                furtherStop.join(20_000);
                assertThat(furtherStop.isAlive()).as("the further stop returned").isFalse();
                assertThat(queue.isStarted()).as("the consume() left the queue not started").isFalse();
                assertThat(subscription.get()).as("a consume() still returns its subscription").isNotNull();
                assertThat(subscription.get().isStarted()).as("neither lane is started").isFalse();
                Awaitility.await().during(Duration.ofSeconds(1)).atMost(Duration.ofSeconds(10)).until(
                        () -> !instanceRegistered(instanceId) && unitsOwnedBy("unordered", instanceId) == 0 && unitsOwnedBy("ordered", instanceId) == 0);

                queue.start();

                assertThat(queue.isStarted()).as("no stop was left owed, so a start starts the queue again").isTrue();
                Awaitility.await().atMost(Duration.ofSeconds(20))
                          .alias("both lanes hold units again")
                          .until(() -> subscription.get().unorderedShardsHeld() > 0 && subscription.get().orderedUnitsHeld() > 0);
            } finally {
                letTheReleaseGo.countDown();
                // What the queue's stop did not reach, so a failure leaves nothing leasing into the next test.
                if (subscription.get() != null) {
                    subscription.get().stop();
                }
            }
        }
    }

    /**
     * A {@code MessageQueue.stop()} called by an observer from inside a {@code consume()} or {@code start()} - on its first
     * acquisition, which a lane's start tells on the starting thread - stops the queue: once that start returns, the queue
     * is not started, neither lane is, and no lease or membership row names the instance. The stop took the queue's
     * lifecycle lock again, reentrantly, and did what a stop holding it does. Inside a {@code consume()}, where the queue
     * was not started yet, that was nothing, and the {@code consume()} then marked the queue started with both lanes
     * running. Inside a {@code start()}, it stopped every consumer, and the start went on to start the ordered lane, with
     * the queue marked not started, so no later stop reached it.
     */
    @ParameterizedTest
    @ValueSource(strings = {"consume", "start"})
    void a_message_queue_stop_called_by_an_observer_inside_a_start_stops_the_queue(String startedBy) throws Exception {
        var instanceId        = "spi-observer-stops-" + startedBy;
        var stopping          = new AtomicBoolean();
        var stopReturned      = new AtomicBoolean();
        var startedAfterStop  = new AtomicReference<Boolean>();
        var subscription      = new AtomicReference<Subscription>();
        try (var queue = new PostgresqlMessageQueue(dataSource, QUEUE_ID, SHARD_COUNT, instanceId)) {
            queue.addObserver(new QueueObserver() {
                @Override
                public void shardOwnershipChanged(int shard, boolean acquired) {
                    if (acquired && stopping.compareAndSet(true, false)) {
                        queue.stop();
                        stopReturned.set(true);
                        startedAfterStop.set(queue.isStarted());
                    }
                }
            });
            try {
                if ("consume".equals(startedBy)) {
                    stopping.set(true);
                    subscription.set(consumeUnchecked(queue));
                } else {
                    subscription.set(consumeUnchecked(queue));
                    Awaitility.await().atMost(Duration.ofSeconds(20))
                              .alias("premise: both lanes hold units before the queue is stopped and started again")
                              .until(() -> subscription.get().unorderedShardsHeld() > 0 && subscription.get().orderedUnitsHeld() > 0);
                    queue.stop();
                    stopping.set(true);
                    queue.start();
                }

                assertThat(stopReturned).as("premise: the observer's stop was called inside the %s() and returned", startedBy).isTrue();
                assertThat(startedAfterStop.get()).as("the queue read not started once the observer's stop returned").isFalse();
                assertThat(queue.isStarted()).as("the %s() left the queue not started", startedBy).isFalse();
                assertThat(subscription.get().isStarted()).as("neither lane is started").isFalse();
                Awaitility.await().during(Duration.ofSeconds(1)).atMost(Duration.ofSeconds(10)).until(
                        () -> !instanceRegistered(instanceId) && unitsOwnedBy("unordered", instanceId) == 0 && unitsOwnedBy("ordered", instanceId) == 0);
            } finally {
                // What the queue's stop did not reach, so a failure leaves nothing leasing into the next test.
                if (subscription.get() != null) {
                    subscription.get().stop();
                }
            }
        }
    }

    /**
     * A {@code MessageQueue.start()} called while a stop is owed - here by an observer that stops the queue and then starts
     * it again from inside a {@code consume()} - does not mark the queue started: the stop has marked it not started and
     * returned, and the {@code consume()} stops every consumer before it returns. It marked the queue started before it
     * looked at the stop owed, so {@code isStarted()} read true after that stop had returned, until the {@code consume()}
     * marked it not started again. A stop that gave up waiting for the {@code start()} reaches the same moment from another
     * thread when it lands between the start taking the lock and marking the queue started, which no test can time.
     */
    @Test
    void a_message_queue_start_called_while_a_stop_is_owed_does_not_mark_the_queue_started() throws Exception {
        var instanceId        = "spi-start-while-stop-owed";
        var stopping          = new AtomicBoolean(true);
        var startReturned     = new AtomicBoolean();
        var startedAfterStart = new AtomicReference<Boolean>();
        try (var queue = new PostgresqlMessageQueue(dataSource, QUEUE_ID, SHARD_COUNT, instanceId)) {
            queue.addObserver(new QueueObserver() {
                @Override
                public void shardOwnershipChanged(int shard, boolean acquired) {
                    if (acquired && stopping.compareAndSet(true, false)) {
                        queue.stop();
                        queue.start();
                        startReturned.set(true);
                        startedAfterStart.set(queue.isStarted());
                    }
                }
            });

            var subscription = consumeUnchecked(queue);
            try {
                assertThat(startReturned).as("premise: the observer's stop and start were called inside the consume() and returned").isTrue();
                assertThat(startedAfterStart.get()).as("the start, called while the stop was owed, left the queue not started").isFalse();
                assertThat(queue.isStarted()).as("the consume() left the queue not started").isFalse();
                assertThat(subscription.isStarted()).as("neither lane is started").isFalse();
                Awaitility.await().during(Duration.ofSeconds(1)).atMost(Duration.ofSeconds(10)).until(
                        () -> !instanceRegistered(instanceId) && unitsOwnedBy("unordered", instanceId) == 0 && unitsOwnedBy("ordered", instanceId) == 0);
            } finally {
                subscription.stop();
            }
        }
    }

    /**
     * A {@code Subscription.start()} after the queue's {@code stop()} marks the queue started, so the queue's
     * {@code close()} stops both lanes again: no lease or membership row names the instance afterwards. It used to start
     * both lanes with the queue still reporting itself not started, and the queue's {@code close()} (its {@code stop()})
     * then found it not started and stopped nothing, so the lanes leased, heartbeated and dispatched until the process
     * exited.
     */
    @Test
    void a_subscription_started_after_the_message_queue_stopped_is_stopped_by_closing_the_queue() throws Exception {
        var instanceId = "spi-subscription-restart";
        var queue      = new PostgresqlMessageQueue(dataSource, QUEUE_ID, SHARD_COUNT, instanceId);
        var subscribed = new AtomicReference<Subscription>();
        try {
            var subscription = consumeUnchecked(queue);
            subscribed.set(subscription);
            Awaitility.await().atMost(Duration.ofSeconds(20))
                      .alias("premise: both lanes hold units before the queue is stopped")
                      .until(() -> subscription.unorderedShardsHeld() > 0 && subscription.orderedUnitsHeld() > 0);
            queue.stop();
            assertThat(subscription.isStarted()).as("premise: the queue's stop stopped both lanes").isFalse();

            subscription.start();

            assertThat(queue.isStarted()).as("the subscription's start marked the queue started").isTrue();
            Awaitility.await().atMost(Duration.ofSeconds(20))
                      .alias("premise: both lanes hold units again")
                      .until(() -> subscription.unorderedShardsHeld() > 0 && subscription.orderedUnitsHeld() > 0);

            queue.close();

            assertThat(queue.isStarted()).as("the queue's close() left it not started").isFalse();
            assertThat(subscription.isStarted()).as("the queue's close() stopped both lanes").isFalse();
            Awaitility.await().during(Duration.ofSeconds(1)).atMost(Duration.ofSeconds(10)).until(
                    () -> !instanceRegistered(instanceId) && unitsOwnedBy("unordered", instanceId) == 0 && unitsOwnedBy("ordered", instanceId) == 0);
        } finally {
            queue.close();
            // What the queue's close() did not reach, so a failure leaves nothing leasing into the next test.
            if (subscribed.get() != null) {
                subscribed.get().stop();
            }
        }
    }

    /**
     * A {@code consume()} or a {@code Subscription.start()} - after the queue's {@code stop()} - escaping with a critical
     * error leaves the queue marked started, as the queue's {@code start()} does, so the queue's {@code close()} stops
     * the lane it left running: an observer throws a {@link LinkageError} for the first acquisition, which escapes the
     * unordered lane's start with that lane running and is not undone. Both left the queue not started, and the
     * {@code consume()} left its pair unregistered too, so {@code close()}, finding the queue not started, stopped
     * nothing, and the unordered lane leased and heartbeated until the process exited.
     */
    @ParameterizedTest
    @ValueSource(strings = {"consume", "subscription"})
    void a_start_escaping_with_a_critical_error_leaves_the_message_queue_started_so_closing_it_stops_the_lane_left_running(String startedBy)
            throws Exception {
        var instanceId   = "spi-critical-" + startedBy;
        var critical     = new LinkageError("simulated critical error from an observer");
        var throwing     = new AtomicBoolean();
        var queue        = new PostgresqlMessageQueue(dataSource, QUEUE_ID, SHARD_COUNT, instanceId);
        var subscription = new AtomicReference<Subscription>();
        queue.addObserver(new QueueObserver() {
            @Override
            public void shardOwnershipChanged(int shard, boolean acquired) {
                if (acquired && throwing.compareAndSet(true, false)) {
                    throw critical;
                }
            }
        });
        try {
            Throwable thrown;
            if ("consume".equals(startedBy)) {
                throwing.set(true);
                thrown = catchThrowable(() -> subscription.set(consumeUnchecked(queue)));
            } else {
                subscription.set(consumeUnchecked(queue));
                Awaitility.await().atMost(Duration.ofSeconds(20))
                          .alias("premise: both lanes hold units before the queue is stopped")
                          .until(() -> subscription.get().unorderedShardsHeld() > 0 && subscription.get().orderedUnitsHeld() > 0);
                queue.stop();
                throwing.set(true);
                thrown = catchThrowable(() -> subscription.get().start());
            }

            assertThat(thrown).as("premise: the %s escaped with the observer's critical error", startedBy).isSameAs(critical);
            assertThat(unitsOwnedBy("unordered", instanceId)).as("premise: the unordered lane was left running, leasing").isPositive();
            assertThat(queue.isStarted()).as("the critical error left the queue started").isTrue();

            queue.close();

            assertThat(queue.isStarted()).as("the queue's close() left it not started").isFalse();
            Awaitility.await().during(Duration.ofSeconds(1)).atMost(Duration.ofSeconds(10)).until(
                    () -> !instanceRegistered(instanceId) && unitsOwnedBy("unordered", instanceId) == 0 && unitsOwnedBy("ordered", instanceId) == 0);
        } finally {
            queue.close();
            // What the queue's close() did not reach, so a failure leaves nothing leasing into the next test. A consume()
            // that escaped returned no subscription to stop.
            if (subscription.get() != null) {
                subscription.get().stop();
            }
        }
    }

    private static Subscription consumeUnchecked(PostgresqlMessageQueue queue) {
        try {
            return queue.consume((messageId, key, payload, payloadType) -> { }, ConsumerOptions.defaults());
        } catch (SQLException e) {
            throw new IllegalStateException(e);
        }
    }

    /**
     * What a failing {@code start()} throws is the failure itself, whatever undoing the start then throws. Every
     * connection after the one that leased unit 0 fails with a new {@link Error}: first the acquire of unit 1, then the
     * release of unit 0 that undoes the start. A critical error ({@code NoClassDefFoundError}, a {@link LinkageError})
     * escapes untouched, with no undo attempted, as everywhere in this module ({@code Exceptions.rethrowIfCriticalError}).
     * A non-critical one ({@link AssertionError}) is undone, and the undo's own failure is attached to it as suppressed.
     * Both used to be undone, and an {@code Error} thrown by the undo replaced the start's failure.
     */
    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    void a_start_failing_with_an_error_throws_that_error_and_not_the_undos(boolean critical) throws Exception {
        var settings   = settingsWithShedGrace(Duration.ofSeconds(1));
        var instanceId = "start-fails-with-error-" + critical;
        var failing    = new ConnectionRefusingDataSource(dataSource);
        try (var runtime = new ShardRuntime(dataSource, settings)) {
            var queue = ShardOwnedQueue.builder().setDataSource(failing).setQueueId(QUEUE_ID).setShardCount(SHARD_COUNT)
                                       .setInstanceId(instanceId).setRuntime(runtime).build();
            try {
                failing.failAfterTheFirstAcquire(critical ? () -> new NoClassDefFoundError("simulated critical error")
                                                          : () -> new AssertionError("simulated non-critical error"));
                var thrown = catchThrowable(() -> startConsuming(queue, "unordered", settings));

                assertThat(failing.errorsThrown()).as("premise: the acquire of unit 1 failed").isNotEmpty();
                assertThat(thrown).as("the start's own failure escapes").isSameAs(failing.errorsThrown().getFirst());
                failing.refuse(false);
                assertThat(unitsOwnedBy("unordered", instanceId)).as("premise: unit 0 was leased, and is still leased").isEqualTo(1);
                if (critical) {
                    assertThat(failing.errorsThrown()).as("nothing was undone after the critical error").hasSize(1);
                    assertThat(thrown.getSuppressed()).isEmpty();
                } else {
                    assertThat(failing.errorsThrown()).as("premise: the release undoing the start failed too").hasSize(2);
                    assertThat(thrown.getSuppressed()).as("the undo's failure is attached to the start's")
                                                      .containsExactly(failing.errorsThrown().get(1));
                    assertThat(queue.isStarted()).as("the failed start was undone").isFalse();
                }
            } finally {
                failing.refuse(false);
                queue.stop();
            }
        }
    }

    /**
     * A {@code stop()} on an interrupted thread ends the run a start left installed when it escaped with a critical error,
     * as it ends a started queue's: it deregisters the instance and leaves the queue stopped, with the thread's interrupt
     * flag still set. No start is running for that run any more, so the stop does its work itself. It used to take the
     * missing heartbeat for a start still running, hand that start the stop's work and return - and nobody did the work:
     * the instance stayed registered, holding every other instance at a stale fair share, the runtime was never handed
     * back, and any unit the run had adopted stayed leased and placed on the runtime, while {@code isStarted()} read
     * false. The interrupted stop does not wait for the lifecycle lock at all, so it reached that hand-over with no start
     * holding the lock.
     */
    @Test
    void a_stop_on_an_interrupted_thread_ends_the_run_a_start_left_behind_with_a_critical_error() throws Exception {
        var settings    = settingsWithShedGrace(Duration.ofSeconds(1));
        var instanceId  = "interrupted-stop-after-critical-start";
        var failing     = new ConnectionRefusingDataSource(dataSource);
        var logged      = new ListAppender<ILoggingEvent>();
        var queueLogger = (ch.qos.logback.classic.Logger) LoggerFactory.getLogger(ShardOwnedQueue.class);
        logged.start();
        queueLogger.addAppender(logged);
        try (var runtime = new ShardRuntime(dataSource, settings)) {
            var queue = ShardOwnedQueue.builder().setDataSource(failing).setQueueId(QUEUE_ID).setShardCount(SHARD_COUNT)
                                       .setInstanceId(instanceId).setRuntime(runtime).build();
            try {
                failing.failAfterTheFirstAcquire(() -> new NoClassDefFoundError("simulated critical error"));
                var thrown = catchThrowable(() -> startConsuming(queue, "unordered", settings));
                failing.refuse(false);
                assertThat(thrown).as("premise: the start escaped with the critical error").isInstanceOf(NoClassDefFoundError.class);
                assertThat(queue.isStarted()).as("premise: the critical error left the queue started").isTrue();
                assertThat(instanceRegistered(instanceId)).as("premise: the start registered the instance").isTrue();

                var stillInterrupted = new AtomicBoolean();
                var stopping = new Thread(() -> {
                    Thread.currentThread().interrupt();
                    queue.stop();
                    stillInterrupted.set(Thread.currentThread().isInterrupted());
                });
                stopping.start();
                stopping.join(10_000);

                assertThat(stopping.isAlive()).as("the interrupted stop returned").isFalse();
                assertThat(stillInterrupted).as("the stop left the thread's interrupt flag set").isTrue();
                assertThat(queue.isStarted()).as("the stop ended the run").isFalse();
                // The deregistration runs on the stop's shutdown-budget thread, which an interrupted stop does not wait for.
                Awaitility.await().atMost(Duration.ofSeconds(5)).until(() -> !instanceRegistered(instanceId));
                assertThat(logged.list).extracting(ILoggingEvent::getFormattedMessage)
                                       .as("the stop did not hand its work to a start, there being none running")
                                       .noneMatch(message -> message.contains("does the stop's work itself"));
            } finally {
                failing.refuse(false);
                queue.stop();
                queueLogger.detachAppender(logged);
            }
        }
    }

    /**
     * A start that does the work of a stop that took its run out without waiting for it returns normally, the queue not
     * started, when the observer throws for the releases that work tells: that stop's caller has returned, so the failure
     * is logged at WARN rather than thrown from {@code start()}. It used to escape {@code start()} - and through
     * {@code PostgresqlMessageQueue.consume()} turn a consume into a failure - while the caller of {@code stop()}, whose
     * stop it was, never saw it.
     * <p>
     * The observer is told only of units the run adopted, so the stop has to take the run out after the start has adopted
     * one and before it schedules the heartbeat, and nothing in that window can be made to hold the start. The ordered lane
     * adopts its units one at a time, each under the run's monitor, and a stop on an interrupted thread - which does not
     * wait for the lifecycle lock - called as soon as the first is adopted waits for that monitor between two adoptions.
     * When it gets the monitor only once the heartbeat is scheduled it stops the queue under the lock instead, after the
     * start, and the observer's failure escapes the stop; the attempt is then made again under a new instance id.
     */
    @Test
    void a_start_doing_the_work_of_a_stop_that_did_not_wait_for_it_logs_the_observers_failure_instead_of_throwing_it() throws Exception {
        var settings    = settingsWithShedGrace(Duration.ofSeconds(1));
        var throwing    = new AtomicBoolean(true);
        var logged      = new ListAppender<ILoggingEvent>();
        var queueLogger = (ch.qos.logback.classic.Logger) LoggerFactory.getLogger(ShardOwnedQueue.class);
        logged.start();
        queueLogger.addAppender(logged);
        ShardRuntime.acquireShared(dataSource, settings);
        try {
            var landed = false;
            for (var attempt = 0; attempt < 20 && !landed; attempt++) {
                var instanceId   = "owed-stop-observer-" + attempt;
                var queue        = new ShardOwnedQueue(dataSource, QUEUE_ID, SHARD_COUNT, instanceId, new ShardOwnerMetrics(throwingForEveryRelease(throwing)));
                var startFailure = new AtomicReference<Throwable>();
                var starting = new Thread(() -> {
                    try {
                        startConsuming(queue, "ordered", settings);
                    } catch (Throwable failure) {
                        startFailure.set(failure);
                    }
                });
                Throwable stopFailure = null;
                try {
                    starting.start();
                    while (queue.owners().isEmpty() && starting.isAlive()) {
                        Thread.onSpinWait();
                    }
                    Thread.currentThread().interrupt();
                    try {
                        queue.stop();
                    } catch (RuntimeException | Error failure) {
                        stopFailure = failure;
                    } finally {
                        Thread.interrupted();
                    }
                    starting.join(30_000);
                    assertThat(starting.isAlive()).as("the start returned").isFalse();
                    // The stop returned without the observer's failure only when it left its work to the start.
                    landed = stopFailure == null;
                    if (landed) {
                        assertThat(startFailure.get()).as("the start, doing the stop's work, returned normally").isNull();
                        assertThat(queue.isStarted()).as("the start left the queue stopped").isFalse();
                        Awaitility.await().atMost(Duration.ofSeconds(5)).until(() -> !instanceRegistered(instanceId)
                                                                                     && unitsOwnedBy("ordered", instanceId) == 0);
                        assertThat(logged.list).filteredOn(event -> event.getFormattedMessage().contains("did the work of a stop()"))
                                               .as("the observer's failure is logged at WARN, with what it threw")
                                               .singleElement()
                                               .satisfies(event -> assertThat(event.getThrowableProxy().getMessage())
                                                       .startsWith("simulated observer failure releasing unit"));
                    } else {
                        assertThat(stopFailure).as("premise: a stop under the lock throws the observer's failure")
                                               .hasMessageStartingWith("simulated observer failure releasing unit");
                        // The release ran on the interrupted stop's budget thread, which it did not wait for.
                        Awaitility.await().atMost(Duration.ofSeconds(10)).until(() -> !instanceRegistered(instanceId)
                                                                                      && unitsOwnedBy("ordered", instanceId) == 0);
                    }
                } finally {
                    Thread.interrupted();
                    starting.join(30_000);
                    throwing.set(false);
                    queue.stop();
                    throwing.set(true);
                }
            }
            assertThat(landed).as("premise: in one of 20 attempts a stop took the run out between an adoption and the heartbeat").isTrue();
        } finally {
            queueLogger.detachAppender(logged);
            ShardRuntime.releaseShared(dataSource);
        }
    }

    private static void startUnchecked(ShardOwnedQueue queue, String lane, ShardOwnerSettings settings) {
        try {
            startConsuming(queue, lane, settings);
        } catch (SQLException e) {
            throw new CompletionException(e);
        }
    }

    /**
     * Make the acquire of {@code unit} of {@code lane} by {@code instanceId} fail, as a database error would, or stop
     * doing so. Every other unit is acquired as usual.
     */
    private void failTheAcquireOf(String lane, String instanceId, int unit, boolean fail) throws Exception {
        try (var connection = dataSource.getConnection(); var statement = connection.createStatement()) {
            if (fail) {
                statement.execute("CREATE OR REPLACE FUNCTION fail_unit_acquire() RETURNS trigger AS $$ BEGIN "
                                          + "RAISE EXCEPTION 'simulated failure acquiring % unit %', NEW.lane, NEW.shard; "
                                          + "END $$ LANGUAGE plpgsql");
                statement.execute("CREATE TRIGGER fail_unit_acquire BEFORE UPDATE ON " + ShardOwnedSchema.LEASE_TABLE
                                          + " FOR EACH ROW WHEN (OLD.owner IS DISTINCT FROM NEW.owner AND NEW.owner = '" + instanceId + "'"
                                          + " AND NEW.lane = '" + lane + "' AND NEW.shard = " + unit + ")"
                                          + " EXECUTE FUNCTION fail_unit_acquire()");
            } else {
                statement.execute("DROP TRIGGER IF EXISTS fail_unit_acquire ON " + ShardOwnedSchema.LEASE_TABLE);
            }
        }
    }

    /**
     * A {@link DataSource} that refuses every connection with an unchecked exception while told to, and otherwise hands
     * out the delegate's.
     */
    private static final class ConnectionRefusingDataSource implements DataSource {
        private final    DataSource                  delegate;
        private final    AtomicBoolean               refusing     = new AtomicBoolean();
        private final    AtomicInteger               refused      = new AtomicInteger();
        private final    AtomicInteger               acquires     = new AtomicInteger();
        private final    List<Error>                 errorsThrown = new CopyOnWriteArrayList<>();
        private volatile Supplier<? extends Error>   errors;
        final            IllegalStateException       failure      = new IllegalStateException("simulated unchecked failure getting a connection");

        ConnectionRefusingDataSource(DataSource delegate) {
            this.delegate = delegate;
        }

        void refuse(boolean refuse) {
            refusing.set(refuse);
            if (!refuse) {
                errors = null;
            }
        }

        int refused() {
            return refused.get();
        }

        /**
         * Let the first lease acquire have its connection, then throw a new error from {@code errors} for every
         * connection asked for after it - the second acquire's first - until {@link #refuse refuse(false)}.
         */
        void failAfterTheFirstAcquire(Supplier<? extends Error> errors) {
            acquires.set(0);
            this.errors = errors;
        }

        List<Error> errorsThrown() {
            return errorsThrown;
        }

        private void refuseIfTold() {
            if (refusing.get()) {
                refused.incrementAndGet();
                throw failure;
            }
            var failWith = errors;
            if (failWith != null) {
                var acquiring = StackWalker.getInstance().walk(frames -> frames.anyMatch(frame -> "acquireLeaseGrant".equals(frame.getMethodName())));
                if (acquiring && acquires.incrementAndGet() == 1) {
                    return;
                }
                if (acquires.get() > 1) {
                    var error = failWith.get();
                    errorsThrown.add(error);
                    throw error;
                }
            }
        }

        @Override
        public Connection getConnection() throws SQLException {
            refuseIfTold();
            return delegate.getConnection();
        }

        @Override
        public Connection getConnection(String username, String password) throws SQLException {
            refuseIfTold();
            return delegate.getConnection(username, password);
        }

        @Override
        public PrintWriter getLogWriter() throws SQLException {
            return delegate.getLogWriter();
        }

        @Override
        public void setLogWriter(PrintWriter out) throws SQLException {
            delegate.setLogWriter(out);
        }

        @Override
        public void setLoginTimeout(int seconds) throws SQLException {
            delegate.setLoginTimeout(seconds);
        }

        @Override
        public int getLoginTimeout() throws SQLException {
            return delegate.getLoginTimeout();
        }

        @Override
        public java.util.logging.Logger getParentLogger() throws SQLFeatureNotSupportedException {
            return delegate.getParentLogger();
        }

        @Override
        public <T> T unwrap(Class<T> iface) throws SQLException {
            return delegate.unwrap(iface);
        }

        @Override
        public boolean isWrapperFor(Class<?> iface) throws SQLException {
            return delegate.isWrapperFor(iface);
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
        slowTheAcquireOf(lane, instanceId, unit, sleepSeconds);
        try (var connection = dataSource.getConnection(); var statement = connection.createStatement()) {
            statement.execute("UPDATE " + ShardOwnedSchema.LEASE_TABLE + " SET owner = 'departed' WHERE queue_id = " + QUEUE_ID
                                      + " AND lane = '" + lane + "' AND shard = " + unit);
        }
        Awaitility.await().atMost(Duration.ofSeconds(10)).until(this::aHeartbeatIsSleeping);
    }

    /**
     * Make every acquire of {@code unit} of {@code lane} by {@code instanceId} from another owner, or from none, sleep
     * {@code sleepSeconds} inside the lease UPDATE, by a trigger. Drop it with {@link #dropSlowAcquire}.
     */
    private void slowTheAcquireOf(String lane, String instanceId, int unit, double sleepSeconds) throws Exception {
        try (var connection = dataSource.getConnection(); var statement = connection.createStatement()) {
            statement.execute("CREATE OR REPLACE FUNCTION slow_acquire() RETURNS trigger AS $$ BEGIN "
                                      + "PERFORM pg_sleep(" + sleepSeconds + "); RETURN NEW; END $$ LANGUAGE plpgsql");
            statement.execute("CREATE TRIGGER slow_acquire BEFORE UPDATE ON " + ShardOwnedSchema.LEASE_TABLE
                                      + " FOR EACH ROW WHEN (OLD.owner IS DISTINCT FROM NEW.owner AND NEW.owner = '" + instanceId + "'"
                                      + " AND NEW.lane = '" + lane + "' AND NEW.shard = " + unit + ")"
                                      + " EXECUTE FUNCTION slow_acquire()");
        }
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
