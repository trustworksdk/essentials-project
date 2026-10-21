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

import static dk.trustworks.essentials.components.queue.shardowned.SingleRowQueries.*;
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
    /**
     * The {@code stuckHandlerTimeout} of the tests whose stop flush never returns. A stop whose budget thread is held takes
     * {@code shedGrace} plus the whole {@code SHUTDOWN_BUDGET}, about 2.3 s, and a restart and a handler release follow it
     * before some premises are read: far past that, each premise read before the bound keeps several seconds of margin on
     * a loaded build. Longer than {@code SHUTDOWN_BUDGET} plus the network bound on the flush's waits at these tests'
     * {@code leaseTtl} (1 s, so 2 s), so it is what a unit still waiting for that flush is given up at
     * ({@code ShardOwnedQueue.stopFlushGiveUpAfter}).
     */
    private static final Duration HUNG_FLUSH_BOUND = Duration.ofSeconds(12);

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

    /**
     * Queues started and closed one after another on one {@link ShardOwnerMetrics} - shared through
     * {@code ShardOwnedQueueBuilder.setMetrics} - leave no source of {@link ShardOwnerMetrics#keptUnitsInHandler()} behind.
     * Every queue's constructor used to register one that nothing removed, so each closed queue stayed reachable from the
     * metrics, and every read of the gauge went over all of them. Now the start of each queue drops the one closed before
     * it, and a read drops the last.
     */
    @Test
    void queues_started_and_closed_on_shared_metrics_leave_no_kept_units_source_behind() throws Exception {
        var settings = settingsWithShedGrace(Duration.ofSeconds(1));
        var metrics  = new ShardOwnerMetrics();
        try (var runtime = new ShardRuntime(dataSource, settings)) {
            for (var i = 0; i < 5; i++) {
                try (var queue = ShardOwnedQueue.builder().setDataSource(dataSource).setQueueId(QUEUE_ID).setShardCount(SHARD_COUNT)
                                                .setInstanceId("shared-metrics-" + i).setRuntime(runtime).setMetrics(metrics).build()) {
                    startConsuming(queue, "unordered", settings);
                    assertThat(metrics.keptUnitsInHandlerSourceCount()).as("the running queue alone, after %d closed before it", i)
                                                                       .isEqualTo(1);
                }
            }
        }

        assertThat(metrics.keptUnitsInHandler()).isZero();
        assertThat(metrics.keptUnitsInHandlerSourceCount()).as("no closed queue stays registered").isZero();
    }

    /**
     * A read of {@link ShardOwnerMetrics#keptUnitsInHandler()} while a stop drains a held ordered handler finds the queue
     * stopped (its run is out of {@code currentRun}) and counting nothing (the unit is not kept yet), so it drops the
     * queue's source. Once the stop has kept the unit, the gauge counts it again, for as long as the handler runs: the stop
     * registers the source again after it has written the units it keeps ({@code endRun}). Without that, the gauge read 0
     * while the kept handler ran.
     */
    @Test
    void a_unit_kept_by_a_stop_is_counted_though_a_read_during_the_drain_dropped_the_queues_source() throws Exception {
        var entries  = new AtomicInteger();
        var entered  = new CountDownLatch(1);
        var release  = new CountDownLatch(1);
        var settings = settingsWithShedGrace(Duration.ofSeconds(2));
        try (var runtime = new ShardRuntime(dataSource, settings)) {
            var queue = ShardOwnedQueue.builder().setDataSource(dataSource).setQueueId(QUEUE_ID).setShardCount(SHARD_COUNT)
                                       .setInstanceId("kept-source-dropped-in-drain").setRuntime(runtime).build();
            var metrics = queue.metrics();
            try {
                queue.startConsumingOrdered((messageId, key, payload, payloadType) -> {
                    // The first delivery of the key holds its handler; a later one returns at once.
                    if (entries.incrementAndGet() == 1) {
                        entered.countDown();
                        awaitQuietly(release);
                    }
                }, settings, SHARD_COUNT);
                queue.enqueueOrdered(List.of(new ShardOwnedStorage.OrderedPayload("ACC-1", 0, "0".getBytes(StandardCharsets.UTF_8), 1)));
                assertThat(entered.await(20, TimeUnit.SECONDS)).isTrue();

                var stopping = CompletableFuture.runAsync(queue::stop);
                Awaitility.await().atMost(Duration.ofSeconds(5)).until(() -> !queue.isStarted());
                assertThat(metrics.keptUnitsInHandler()).as("premise: read during the drain, nothing is kept yet").isZero();
                assertThat(metrics.keptUnitsInHandlerSourceCount()).as("premise: that read dropped the queue's source").isZero();
                assertThat(stopping).as("premise: the stop is still draining the held handler").isNotDone();

                stopping.get(10, TimeUnit.SECONDS);
                assertThat(queue.keptOrderedUnitNotHandedBack()).as("premise: the stop kept the unit, its key in the handler").isTrue();
                assertThat(metrics.keptUnitsInHandler()).as("the kept unit is counted while its handler runs").isEqualTo(1);

                release.countDown();
                Awaitility.await().atMost(Duration.ofSeconds(10))
                          .untilAsserted(() -> assertThat(metrics.keptUnitsInHandler()).as("not once its handler has returned").isZero());
            } finally {
                release.countDown();
                queue.stop();
            }
        } finally {
            release.countDown();
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
     * the handler has returned or, as far as any other instance is concerned, this instance's liveness lapses.
     * <p>
     * This queue restarted meanwhile, under the same instance id, leaves the kept unit alone while the key is in its
     * handler. Once the handler has returned the unit is handed back - the kept owner's acknowledgement flushed under the
     * fence it was kept under, its lease row released - and a heartbeat takes it under a NEW fence, so the old
     * incarnation's writes are refused rather than accepted alongside the new owner's. The restart used to take the kept
     * unit at once ({@code acquireLeaseGrant} accepts a row naming its own owner) and deliver the key again beside the
     * handler; and, once the handler had returned, it took the unit back with the row still naming this instance, as an
     * unclean takeover: an attempt charged and the message the handler had handled delivered again.
     */
    @Test
    void an_ordered_unit_whose_key_outlasts_the_grace_is_kept_rather_than_handed_on() throws Exception {
        var entries  = new AtomicInteger();
        var entered  = new CountDownLatch(1);
        var release  = new CountDownLatch(1);
        var settings = settingsWithShedGrace(Duration.ofMillis(300));
        try (var runtime = new ShardRuntime(dataSource, settings)) {
            var queue = ShardOwnedQueue.builder().setDataSource(dataSource).setQueueId(QUEUE_ID).setShardCount(SHARD_COUNT)
                                       .setInstanceId("drain-ordered").setRuntime(runtime).build();
            try {
                queue.startConsumingOrdered((messageId, key, payload, payloadType) -> {
                    // The first delivery of the key holds its handler; a later one returns at once.
                    if (entries.incrementAndGet() == 1) {
                        entered.countDown();
                        awaitQuietly(release);
                    }
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
                assertThat(queue.keptOrderedUnitNotHandedBack()).as("premise: the key is still in the handler of the kept unit").isTrue();

                // Restarted under the same instance id, while the old handler still runs, past several heartbeats (one a
                // third of the 1 s lease TTL): every unit but the kept one is taken, and the kept one is left alone.
                var keptFence = keptOrderedFence("drain-ordered");
                queue.start();
                Awaitility.await().during(Duration.ofMillis(1_500)).atMost(Duration.ofSeconds(5)).untilAsserted(() -> {
                    assertThat(fenceOf("ordered", keptFence.shard())).as("the kept unit is not taken while its key is in the handler")
                                                                     .isEqualTo(keptFence.fence());
                    assertThat(entries).as("the key is not delivered again beside its handler").hasValue(1);
                    assertThat(queue.shardsHeld()).as("every unit but the kept one").isEqualTo(ShardOwnedSchema.ORDERED_UNITS - 1);
                });

                // Stopped again, keeping nothing of its own: the unit the first stop kept still names the instance, so the
                // instance stays registered - deregistered, the unit would read as ownerless and another instance could
                // start the key beside the handler - and the run after this one leaves the unit alone too.
                queue.stop();
                assertThat(instanceRegistered("drain-ordered")).as("the earlier stop's kept unit keeps the instance registered").isTrue();
                assertThat(orderedUnitsOwnedBy("drain-ordered")).as("only the kept unit names the instance").isEqualTo(1);
                assertThat(queue.keptOrderedUnitNotHandedBack()).as("the queue still knows the key is in the handler").isTrue();
                queue.start();
                Awaitility.await().during(Duration.ofMillis(700)).atMost(Duration.ofSeconds(5)).untilAsserted(() -> {
                    assertThat(fenceOf("ordered", keptFence.shard())).as("the kept unit is still left alone").isEqualTo(keptFence.fence());
                    assertThat(queue.shardsHeld()).isEqualTo(ShardOwnedSchema.ORDERED_UNITS - 1);
                });

                release.countDown();
                Awaitility.await().atMost(Duration.ofSeconds(10)).untilAsserted(() -> {
                    assertThat(queue.keptOrderedUnitNotHandedBack()).isFalse();
                    assertThat(fenceOf("ordered", keptFence.shard())).as("taken once the handler has returned, under a new fence")
                                                                     .isGreaterThan(keptFence.fence());
                    assertThat(queue.shardsHeld()).as("every unit").isEqualTo(ShardOwnedSchema.ORDERED_UNITS);
                });
                // Handed back cleanly before it was taken again: the kept owner's acknowledgement flushed under its fence and
                // its row released, so the new owner found the unit released, charged nothing and redelivered nothing.
                Awaitility.await().during(Duration.ofSeconds(1)).atMost(Duration.ofSeconds(5)).untilAsserted(() -> {
                    assertThat(entries).as("the message the kept handler handled is not delivered again").hasValue(1);
                    assertThat(queue.orderedRemaining()).as("its acknowledgement was flushed as the unit was handed back").isZero();
                });
            } finally {
                release.countDown();
                queue.stop();
            }
        } finally {
            release.countDown();
        }
    }

    /**
     * {@link #an_ordered_unit_whose_key_outlasts_the_grace_is_kept_rather_than_handed_on} with the database refusing to
     * release the kept unit's lease row once its handler has returned: the hand-back as the key leaves the handler fails,
     * and so does every heartbeat's retry while the refusal lasts. The restarted queue leaves the unit alone all that time
     * - its row still names this instance, so taking it would read as a takeover from an owner that never let go of it -
     * and the queue goes on reporting it not handed back. Once the database lets the release through, a heartbeat hands
     * the unit back and it is taken under a newer fence, the handled message not delivered again. Pins the acquire loops
     * skipping a kept unit by its lease row rather than by whether a key of it is in a handler: the hand-back as the key
     * leaves the handler releases the row within milliseconds, so only a hand-back that fails tells the two apart.
     */
    @Test
    void a_unit_a_stop_kept_whose_hand_back_fails_is_left_alone_until_a_heartbeat_hands_it_back() throws Exception {
        var entries    = new AtomicInteger();
        var entered    = new CountDownLatch(1);
        var release    = new CountDownLatch(1);
        var settings   = settingsWithShedGrace(Duration.ofMillis(300));
        var instanceId = "kept-hand-back-refused";
        try (var runtime = new ShardRuntime(dataSource, settings)) {
            var queue = ShardOwnedQueue.builder().setDataSource(dataSource).setQueueId(QUEUE_ID).setShardCount(SHARD_COUNT)
                                       .setInstanceId(instanceId).setRuntime(runtime).build();
            try {
                queue.startConsumingOrdered((messageId, key, payload, payloadType) -> {
                    // The first delivery of the key holds its handler; a later one returns at once.
                    if (entries.incrementAndGet() == 1) {
                        entered.countDown();
                        awaitQuietly(release);
                    }
                }, settings, SHARD_COUNT);
                queue.enqueueOrdered(List.of(new ShardOwnedStorage.OrderedPayload("ACC-1", 0, "0".getBytes(StandardCharsets.UTF_8), 1)));
                assertThat(entered.await(20, TimeUnit.SECONDS)).isTrue();

                queue.stop();
                assertThat(orderedUnitsOwnedBy(instanceId)).as("premise: the busy unit is kept").isEqualTo(1);
                var keptFence = keptOrderedFence(instanceId);
                queue.start();
                Awaitility.await().atMost(Duration.ofSeconds(5))
                          .untilAsserted(() -> assertThat(queue.shardsHeld()).as("premise: the restart takes every unit but the kept one")
                                                                             .isEqualTo(ShardOwnedSchema.ORDERED_UNITS - 1));

                LeaseReleaseRefusals.failLeaseReleasesOf(dataSource, new short[]{QUEUE_ID}, "ordered", keptFence.shard(), true);
                try {
                    release.countDown();
                    // The hand-back as the key left its handler, then at least one heartbeat's retry (one a third of the
                    // 1 s lease TTL): the injection worked, and the retry runs.
                    Awaitility.await().atMost(Duration.ofSeconds(5))
                              .untilAsserted(() -> assertThat(LeaseReleaseRefusals.releasesRefused(dataSource, QUEUE_ID, keptFence.shard()))
                                      .as("premise: the kept unit's release was refused, and retried").isGreaterThanOrEqualTo(2));
                    Awaitility.await().during(Duration.ofMillis(1_500)).atMost(Duration.ofSeconds(5)).untilAsserted(() -> {
                        assertThat(fenceOf("ordered", keptFence.shard())).as("the unit not handed back is not taken, its handler returned")
                                                                         .isEqualTo(keptFence.fence());
                        assertThat(queue.shardsHeld()).as("every unit but the kept one").isEqualTo(ShardOwnedSchema.ORDERED_UNITS - 1);
                        assertThat(queue.keptOrderedUnitNotHandedBack()).as("a failed hand-back is not a hand-back").isTrue();
                        assertThat(entries).hasValue(1);
                    });
                } finally {
                    LeaseReleaseRefusals.failLeaseReleasesOf(dataSource, new short[]{QUEUE_ID}, "ordered", keptFence.shard(), false);
                }

                Awaitility.await().atMost(Duration.ofSeconds(10)).untilAsserted(() -> {
                    assertThat(queue.keptOrderedUnitNotHandedBack()).as("a heartbeat handed the unit back").isFalse();
                    assertThat(fenceOf("ordered", keptFence.shard())).as("taken once handed back, under a new fence")
                                                                     .isGreaterThan(keptFence.fence());
                    assertThat(queue.shardsHeld()).as("every unit").isEqualTo(ShardOwnedSchema.ORDERED_UNITS);
                });
                Awaitility.await().during(Duration.ofSeconds(1)).atMost(Duration.ofSeconds(5)).untilAsserted(() -> {
                    assertThat(entries).as("the message the kept handler handled is not delivered again").hasValue(1);
                    assertThat(queue.orderedRemaining()).as("its acknowledgement was flushed").isZero();
                });
            } finally {
                release.countDown();
                queue.stop();
            }
        } finally {
            release.countDown();
        }
    }

    /**
     * A unit a stop kept whose handler returns while the stop's budget thread, which the stop gave up waiting for, is still
     * flushing the acknowledgements of the owners the stop ended - the kept one among them - and the queue restarted
     * meanwhile. The restart's hand-back of kept units used to flush and release the kept owner beside that flush: the
     * restart then took the unit under a new fence, and the budget thread's flush of the same acknowledgement was refused,
     * counted in {@code fencedOutAcks} and logged as a lost lease. Nothing hands a kept unit back before the stop's flush
     * has passed it; then the unit is handed back, the handled message's acknowledgement flushed once, by the stop.
     */
    @Test
    void a_unit_a_stop_kept_is_not_handed_back_while_the_stop_is_still_flushing_it() throws Exception {
        var entries    = new AtomicInteger();
        var entered    = new CountDownLatch(1);
        var release    = new CountDownLatch(1);
        var settings   = settingsWithShedGrace(Duration.ofMillis(300));
        var instanceId = "kept-while-the-stop-flushes";
        var holding    = new FlushHoldingDataSource(dataSource, FlushHoldingDataSource.STOP_BUDGET_THREAD);
        try (var runtime = new ShardRuntime(dataSource, settings)) {
            var queue = ShardOwnedQueue.builder().setDataSource(holding).setQueueId(QUEUE_ID).setShardCount(SHARD_COUNT)
                                       .setInstanceId(instanceId).setRuntime(runtime).build();
            try {
                queue.startConsumingOrdered((messageId, key, payload, payloadType) -> {
                    // The first delivery of the key holds its handler; a later one returns at once.
                    if (entries.incrementAndGet() == 1) {
                        entered.countDown();
                        awaitQuietly(release);
                    }
                }, settings, SHARD_COUNT);
                queue.enqueueOrdered(List.of(new ShardOwnedStorage.OrderedPayload("ACC-1", 0, "0".getBytes(StandardCharsets.UTF_8), 1)));
                assertThat(entered.await(20, TimeUnit.SECONDS)).isTrue();

                holding.holdConnections();
                queue.stop();
                assertThat(queue.handBackStillRunning()).as("premise: the stop gave up waiting for its budget thread").isTrue();
                assertThat(queue.metrics().keptUnitsInHandler()).as("premise: the busy unit is kept").isEqualTo(1);
                // The budget thread has released nothing yet, so every unit's row still names the instance: the kept one is
                // the key's.
                var keptUnit = ShardOwnedSchema.unitForKey("ACC-1", ShardOwnedSchema.ORDERED_UNITS);
                var kept     = new UnitFence(keptUnit, fenceOf("ordered", keptUnit));

                // The kept handler returns: its acknowledgement waits in the kept owner, which no pump serves any more.
                release.countDown();
                Awaitility.await().atMost(Duration.ofSeconds(5))
                          .untilAsserted(() -> assertThat(queue.metrics().keptUnitsInHandler()).as("premise: the kept handler has returned").isZero());
                // The budget thread gets its connection and flushes the owners the stop ended, the kept one with that
                // acknowledgement: held as it deletes the handled message.
                holding.letConnect();
                assertThat(holding.awaitAcknowledgementHeld(Duration.ofSeconds(10)))
                        .as("premise: the stop's budget thread is flushing the kept owner's acknowledgement").isTrue();

                queue.start();
                assertThat(queue.shardsHeld()).as("the restart leaves the kept unit alone while the stop is still flushing it")
                                              .isEqualTo(ShardOwnedSchema.ORDERED_UNITS - 1);
                Awaitility.await().during(Duration.ofMillis(1_000)).atMost(Duration.ofSeconds(5)).untilAsserted(() -> {
                    assertThat(ownerOf("ordered", kept.shard())).as("the kept unit is not handed back while the stop is still flushing it")
                                                                .isEqualTo(instanceId);
                    assertThat(fenceOf("ordered", kept.shard())).as("nor taken").isEqualTo(kept.fence());
                });

                holding.letAcknowledge();
                Awaitility.await().atMost(Duration.ofSeconds(10)).untilAsserted(() -> {
                    assertThat(queue.handBackStillRunning()).as("premise: the stop's budget thread has finished").isFalse();
                    assertThat(queue.keptOrderedUnitNotHandedBack()).as("handed back once the stop's flush has passed it").isFalse();
                    assertThat(fenceOf("ordered", kept.shard())).as("then taken, under a new fence").isGreaterThan(kept.fence());
                    assertThat(queue.shardsHeld()).as("every unit").isEqualTo(ShardOwnedSchema.ORDERED_UNITS);
                });
                assertThat(queue.metrics().fencedOutAcks.sum()).as("the stop's flush of the kept owner was not refused").isZero();
                Awaitility.await().during(Duration.ofSeconds(1)).atMost(Duration.ofSeconds(5)).untilAsserted(() -> {
                    assertThat(entries).as("the message the kept handler handled is not delivered again").hasValue(1);
                    assertThat(queue.orderedRemaining()).as("its acknowledgement was flushed").isZero();
                });
            } finally {
                release.countDown();
                holding.letConnect();
                holding.letAcknowledge();
                queue.stop();
            }
        }
    }

    /**
     * A unit a stop kept whose handler has returned, asked to be released on request while the stop's budget thread, which
     * the stop gave up waiting for, is still flushing the owners the stop ended - the kept one among them, with the handled
     * message's acknowledgement. The request may neither flush the kept owner nor release its row beside that flush: it
     * answers {@code BUSY} and leaves the unit kept, its row and fence as they were and the message for the stop's flush to
     * delete. Released there, the stop's flush of the same acknowledgement would be refused, counted in
     * {@code fencedOutAcks} and logged as a lost lease. Once that flush has passed the unit, it is handed back.
     */
    @Test
    void a_unit_a_stop_kept_is_not_released_on_request_while_the_stop_is_still_flushing_it() throws Exception {
        var entries    = new AtomicInteger();
        var entered    = new CountDownLatch(1);
        var release    = new CountDownLatch(1);
        var settings   = settingsWithShedGrace(Duration.ofMillis(300));
        var instanceId = "kept-released-on-request-while-the-stop-flushes";
        var holding    = new FlushHoldingDataSource(dataSource, FlushHoldingDataSource.STOP_BUDGET_THREAD);
        try (var runtime = new ShardRuntime(dataSource, settings)) {
            var queue = ShardOwnedQueue.builder().setDataSource(holding).setQueueId(QUEUE_ID).setShardCount(SHARD_COUNT)
                                       .setInstanceId(instanceId).setRuntime(runtime).build();
            try {
                queue.startConsumingOrdered((messageId, key, payload, payloadType) -> {
                    // The first delivery of the key holds its handler; a later one returns at once.
                    if (entries.incrementAndGet() == 1) {
                        entered.countDown();
                        awaitQuietly(release);
                    }
                }, settings, SHARD_COUNT);
                queue.enqueueOrdered(List.of(new ShardOwnedStorage.OrderedPayload("ACC-1", 0, "0".getBytes(StandardCharsets.UTF_8), 1)));
                assertThat(entered.await(20, TimeUnit.SECONDS)).isTrue();

                holding.holdConnections();
                queue.stop();
                assertThat(queue.handBackStillRunning()).as("premise: the stop gave up waiting for its budget thread").isTrue();
                assertThat(queue.metrics().keptUnitsInHandler()).as("premise: the busy unit is kept").isEqualTo(1);
                // The budget thread has released nothing yet, so every unit's row still names the instance: the kept one is
                // the key's.
                var keptUnit = ShardOwnedSchema.unitForKey("ACC-1", ShardOwnedSchema.ORDERED_UNITS);
                var kept     = new UnitFence(keptUnit, fenceOf("ordered", keptUnit));

                // The kept handler returns: its acknowledgement waits in the kept owner, which no pump serves any more.
                release.countDown();
                Awaitility.await().atMost(Duration.ofSeconds(5))
                          .untilAsserted(() -> assertThat(queue.metrics().keptUnitsInHandler()).as("premise: the kept handler has returned").isZero());
                // The budget thread gets its connection and flushes the owners the stop ended, the kept one with that
                // acknowledgement: held as it deletes the handled message.
                holding.letConnect();
                assertThat(holding.awaitAcknowledgementHeld(Duration.ofSeconds(10)))
                        .as("premise: the stop's budget thread is flushing the kept owner's acknowledgement").isTrue();

                assertThat(queue.releaseKeptOrderedUnit(instanceId, keptUnit))
                        .as("a release on request while the stop is still flushing the unit")
                        .hasValueSatisfying(answer -> {
                            assertThat(answer.outcome()).as("waits for that flush").isEqualTo(KeptUnitRelease.Outcome.BUSY);
                            assertThat(answer.unit()).isEqualTo(kept.shard());
                            assertThat(answer.instanceId()).isEqualTo(instanceId);
                            assertThat(answer.keysInHandler()).as("the kept handler has returned").isEmpty();
                        });
                assertThat(ownerOf("ordered", kept.shard())).as("the request released nothing").isEqualTo(instanceId);
                assertThat(fenceOf("ordered", kept.shard())).as("nor moved the fence").isEqualTo(kept.fence());
                assertThat(queue.orderedRemaining()).as("nor flushed the kept owner's acknowledgement beside the stop").isEqualTo(1);
                assertThat(queue.keptOrderedUnitNotHandedBack()).as("the unit is still kept").isTrue();
                assertThat(queue.handBackStillRunning()).as("premise: the stop's budget thread is still held").isTrue();

                holding.letAcknowledge();
                Awaitility.await().atMost(Duration.ofSeconds(10)).untilAsserted(() -> {
                    assertThat(queue.handBackStillRunning()).as("premise: the stop's budget thread has finished").isFalse();
                    assertThat(queue.keptOrderedUnitNotHandedBack()).as("handed back once the stop's flush has passed it").isFalse();
                });
                assertThat(queue.metrics().fencedOutAcks.sum()).as("the stop's flush of the kept owner was not refused").isZero();
                assertThat(queue.orderedRemaining()).as("the handled message's acknowledgement was flushed").isZero();
                assertThat(ownerOf("ordered", kept.shard())).as("the kept unit's row is released").isNull();
                assertThat(fenceOf("ordered", kept.shard())).as("under the fence it was kept under").isEqualTo(kept.fence());
                assertThat(queue.releaseKeptOrderedUnit(instanceId, keptUnit)).as("nothing is left to release on request").isEmpty();
                assertThat(entries).as("the message the kept handler handled is not delivered again").hasValue(1);
            } finally {
                release.countDown();
                holding.letConnect();
                holding.letAcknowledge();
                queue.stop();
            }
        }
    }

    /**
     * A unit a stop kept whose budget thread never finishes flushing the owners the stop ended - its connection hangs, as on
     * a half-open socket with no socket timeout - while the key the unit was kept for stays in its handler. Only that
     * thread lets the unit out of {@code STOP_FLUSHING}, which no hand-back claims, so the unit was never given up past
     * {@code stuckHandlerTimeout}: a release on request answered {@code BUSY} for as long as the thread hung, and nothing
     * logged why. Kept longer than the bound and still there, the unit is given up with a WARN, and a release on request
     * releases it.
     */
    @Test
    void a_unit_a_stop_kept_whose_flush_never_returns_is_released_on_request_past_the_stuck_handler_timeout() throws Exception {
        var entries     = new AtomicInteger();
        var entered     = new CountDownLatch(1);
        var release     = new CountDownLatch(1);
        var settings    = settingsWithShedGrace(Duration.ofMillis(300)).toBuilder().setStuckHandlerTimeout(HUNG_FLUSH_BOUND).build();
        var instanceId  = "kept-behind-a-hung-stop-flush";
        var holding     = new FlushHoldingDataSource(dataSource, FlushHoldingDataSource.STOP_BUDGET_THREAD);
        var logged      = new ListAppender<ILoggingEvent>();
        var queueLogger = (ch.qos.logback.classic.Logger) LoggerFactory.getLogger(ShardOwnedQueue.class);
        logged.start();
        queueLogger.addAppender(logged);
        try (var runtime = new ShardRuntime(dataSource, settings)) {
            var queue = ShardOwnedQueue.builder().setDataSource(holding).setQueueId(QUEUE_ID).setShardCount(SHARD_COUNT)
                                       .setInstanceId(instanceId).setRuntime(runtime).build();
            try {
                queue.startConsumingOrdered((messageId, key, payload, payloadType) -> {
                    // The first delivery of the key holds its handler; a later one returns at once.
                    if (entries.incrementAndGet() == 1) {
                        entered.countDown();
                        awaitQuietly(release);
                    }
                }, settings, SHARD_COUNT);
                queue.enqueueOrdered(List.of(new ShardOwnedStorage.OrderedPayload("ACC-1", 0, "0".getBytes(StandardCharsets.UTF_8), 1)));
                assertThat(entered.await(20, TimeUnit.SECONDS)).isTrue();

                // The stop's budget thread hangs as it asks for its connection, before it has flushed anything.
                holding.holdConnections();
                var stoppedAt = System.nanoTime();
                queue.stop();
                assertThat(holding.awaitConnectionHeld(Duration.ofSeconds(10))).as("premise: the stop's budget thread hangs").isTrue();
                assertThat(queue.handBackStillRunning()).as("premise: the stop gave up waiting for its budget thread").isTrue();
                assertThat(queue.metrics().keptUnitsInHandler()).as("premise: the busy unit is kept").isEqualTo(1);
                var keptUnit = ShardOwnedSchema.unitForKey("ACC-1", ShardOwnedSchema.ORDERED_UNITS);
                var kept     = new UnitFence(keptUnit, fenceOf("ordered", keptUnit));
                assertThat(queue.stopFlushGiveUpAfter(settings)).as("premise: the unit still waiting for the flush is given up at the bound")
                                                                .isEqualTo(settings.stuckHandlerTimeout());
                assertThat(Duration.ofNanos(System.nanoTime() - stoppedAt)).as("premise: well within the bound")
                                                                           .isLessThan(settings.stuckHandlerTimeout().minusSeconds(2));
                assertThat(queue.releaseKeptOrderedUnit(instanceId, keptUnit))
                        .as("premise: within the bound, a release on request waits for the stop's flush")
                        .hasValueSatisfying(answer -> assertThat(answer.outcome()).isEqualTo(KeptUnitRelease.Outcome.BUSY));

                Awaitility.await().atMost(HUNG_FLUSH_BOUND.plusSeconds(20)).pollInterval(Duration.ofMillis(500)).untilAsserted(
                        () -> assertThat(queue.releaseKeptOrderedUnit(instanceId, keptUnit))
                                .as("past the bound, released on request though the stop's flush still hangs")
                                .hasValueSatisfying(answer -> assertThat(answer.outcome()).isEqualTo(KeptUnitRelease.Outcome.RELEASED)));
                assertThat(Duration.ofNanos(System.nanoTime() - stoppedAt)).as("not before the bound")
                                                                           .isGreaterThanOrEqualTo(settings.stuckHandlerTimeout());
                assertThat(queue.handBackStillRunning()).as("premise: the stop's budget thread still hangs").isTrue();
                assertThat(holding.awaitAcknowledgementHeld(Duration.ZERO)).as("premise: before it has flushed anything").isFalse();
                assertThat(ownerOf("ordered", kept.shard())).as("the kept unit's row is released").isNull();
                assertThat(queue.keptOrderedUnitAwaited()).as("nothing waits for the unit any more").isFalse();
                assertThat(logged.list).extracting(ILoggingEvent::getFormattedMessage)
                                       .filteredOn(message -> message.contains("stops waiting for ordered unit " + kept.shard())
                                               && message.contains("still not finished flushing"))
                                       .as("one WARN gives the unit up").hasSize(1);
            } finally {
                release.countDown();
                holding.letConnect();
                holding.letAcknowledge();
                queue.stop();
            }
        } finally {
            queueLogger.detachAppender(logged);
        }
    }

    /**
     * The same hung flush of the stop that kept a unit, with the queue restarted meanwhile: the restart leaves the unit
     * alone while it waits for that flush, and a heartbeat once it has been kept longer than {@code stuckHandlerTimeout}
     * gives it up, and the queue takes it over under a new fence, delivering the key's message again beside its stuck
     * handler - the at-least-once the bound accepts. It used to stay unserved for as long as the flush hung.
     */
    @Test
    void a_unit_a_stop_kept_whose_flush_never_returns_is_taken_over_by_a_restarted_queue_past_the_stuck_handler_timeout() throws Exception {
        var entries    = new AtomicInteger();
        var entered    = new CountDownLatch(1);
        var release    = new CountDownLatch(1);
        var settings   = settingsWithShedGrace(Duration.ofMillis(300)).toBuilder().setStuckHandlerTimeout(HUNG_FLUSH_BOUND).build();
        var instanceId = "kept-behind-a-hung-stop-flush-restarted";
        var holding    = new FlushHoldingDataSource(dataSource, FlushHoldingDataSource.STOP_BUDGET_THREAD);
        try (var runtime = new ShardRuntime(dataSource, settings)) {
            var queue = ShardOwnedQueue.builder().setDataSource(holding).setQueueId(QUEUE_ID).setShardCount(SHARD_COUNT)
                                       .setInstanceId(instanceId).setRuntime(runtime).build();
            try {
                queue.startConsumingOrdered((messageId, key, payload, payloadType) -> {
                    // The first delivery of the key holds its handler; a later one returns at once.
                    if (entries.incrementAndGet() == 1) {
                        entered.countDown();
                        awaitQuietly(release);
                    }
                }, settings, SHARD_COUNT);
                queue.enqueueOrdered(List.of(new ShardOwnedStorage.OrderedPayload("ACC-1", 0, "0".getBytes(StandardCharsets.UTF_8), 1)));
                assertThat(entered.await(20, TimeUnit.SECONDS)).isTrue();

                holding.holdConnections();
                queue.stop();
                assertThat(holding.awaitConnectionHeld(Duration.ofSeconds(10))).as("premise: the stop's budget thread hangs").isTrue();
                assertThat(queue.metrics().keptUnitsInHandler()).as("premise: the busy unit is kept").isEqualTo(1);
                var keptUnit = ShardOwnedSchema.unitForKey("ACC-1", ShardOwnedSchema.ORDERED_UNITS);
                var kept     = new UnitFence(keptUnit, fenceOf("ordered", keptUnit));

                queue.start();
                assertThat(queue.shardsHeld()).as("premise: the restart leaves the kept unit alone").isEqualTo(ShardOwnedSchema.ORDERED_UNITS - 1);

                Awaitility.await().atMost(HUNG_FLUSH_BOUND.plusSeconds(20)).untilAsserted(() -> {
                    assertThat(fenceOf("ordered", kept.shard())).as("taken over past the bound, under a new fence").isGreaterThan(kept.fence());
                    assertThat(ownerOf("ordered", kept.shard())).as("by the restarted queue").isEqualTo(instanceId);
                    assertThat(queue.shardsHeld()).as("every unit").isEqualTo(ShardOwnedSchema.ORDERED_UNITS);
                    assertThat(entries).as("the stuck handler's message delivered again beside it").hasValue(2);
                });
                assertThat(queue.handBackStillRunning()).as("premise: the stop's budget thread still hangs").isTrue();
                assertThat(holding.awaitAcknowledgementHeld(Duration.ZERO)).as("premise: before it has flushed anything").isFalse();
                assertThat(queue.keptOrderedUnitAwaited()).as("nothing waits for the unit any more").isFalse();
            } finally {
                release.countDown();
                holding.letConnect();
                holding.letAcknowledge();
                queue.stop();
            }
        }
    }

    /**
     * The same hung flush of the stop that kept a unit, with the queue restarted meanwhile, but the key's handler returns
     * within the bound. The heartbeat that gives the unit up past {@code stuckHandlerTimeout} reviews before it hands
     * back, so the unit, with no key in a handler, is handed back in that pass: its acknowledgement flushed under the kept
     * fence and its row released, so the restarted queue takes it again with no attempt charged and the handled message is
     * not delivered again. Reviewed after the hand-backs, as it was, the tick passed its hand-back with the unit still
     * waiting for the flush, and its rebalance took the unit over at once: an unclean takeover delivering the message again.
     */
    @Test
    void a_unit_a_stop_kept_whose_flush_never_returns_is_handed_back_past_the_stuck_handler_timeout_once_its_handler_has_returned() throws Exception {
        aUnitWhoseStopFlushNeverReturnsIsHandedBackPastTheBoundOnceItsHandlerHasReturned(false);
    }

    /**
     * As above, but the queue stays stopped until the bound has passed, so the start's own review gives the unit up, and
     * reviewing before it hands back, the start hands the unit back before it leases anything. Reviewed after the
     * hand-backs, as it was, the start took the unit over unclean and delivered the handled message again.
     */
    @Test
    void a_unit_a_stop_kept_whose_flush_never_returns_is_handed_back_by_a_start_past_the_stuck_handler_timeout_once_its_handler_has_returned() throws Exception {
        aUnitWhoseStopFlushNeverReturnsIsHandedBackPastTheBoundOnceItsHandlerHasReturned(true);
    }

    private void aUnitWhoseStopFlushNeverReturnsIsHandedBackPastTheBoundOnceItsHandlerHasReturned(boolean restartPastTheBound) throws Exception {
        var entries     = new AtomicInteger();
        var entered     = new CountDownLatch(1);
        var release     = new CountDownLatch(1);
        var settings    = settingsWithShedGrace(Duration.ofMillis(300)).toBuilder().setStuckHandlerTimeout(HUNG_FLUSH_BOUND).build();
        var instanceId  = "kept-behind-a-hung-stop-flush-handed-back";
        var holding     = new FlushHoldingDataSource(dataSource, FlushHoldingDataSource.STOP_BUDGET_THREAD);
        var logged      = new ListAppender<ILoggingEvent>();
        var queueLogger = (ch.qos.logback.classic.Logger) LoggerFactory.getLogger(ShardOwnedQueue.class);
        logged.start();
        queueLogger.addAppender(logged);
        try (var runtime = new ShardRuntime(dataSource, settings)) {
            var queue = ShardOwnedQueue.builder().setDataSource(holding).setQueueId(QUEUE_ID).setShardCount(SHARD_COUNT)
                                       .setInstanceId(instanceId).setRuntime(runtime).build();
            try {
                queue.startConsumingOrdered((messageId, key, payload, payloadType) -> {
                    // The first delivery of the key holds its handler; a later one returns at once.
                    if (entries.incrementAndGet() == 1) {
                        entered.countDown();
                        awaitQuietly(release);
                    }
                }, settings, SHARD_COUNT);
                queue.enqueueOrdered(List.of(new ShardOwnedStorage.OrderedPayload("ACC-1", 0, "0".getBytes(StandardCharsets.UTF_8), 1)));
                assertThat(entered.await(20, TimeUnit.SECONDS)).isTrue();

                holding.holdConnections();
                var stoppedAt = System.nanoTime();
                queue.stop();
                assertThat(holding.awaitConnectionHeld(Duration.ofSeconds(10))).as("premise: the stop's budget thread hangs").isTrue();
                assertThat(queue.metrics().keptUnitsInHandler()).as("premise: the busy unit is kept").isEqualTo(1);
                var keptUnit = ShardOwnedSchema.unitForKey("ACC-1", ShardOwnedSchema.ORDERED_UNITS);
                var kept     = new UnitFence(keptUnit, fenceOf("ordered", keptUnit));

                if (!restartPastTheBound) {
                    queue.start();
                    assertThat(queue.shardsHeld()).as("premise: the restart leaves the kept unit alone").isEqualTo(ShardOwnedSchema.ORDERED_UNITS - 1);
                }
                release.countDown();
                Awaitility.await().atMost(Duration.ofSeconds(5)).untilAsserted(
                        () -> assertThat(queue.metrics().keptUnitsInHandler()).as("premise: the kept key has left its handler").isZero());
                assertThat(Duration.ofNanos(System.nanoTime() - stoppedAt)).as("premise: the handler returned within the bound")
                                                                           .isLessThan(settings.stuckHandlerTimeout().minusSeconds(1));
                assertThat(ownerOf("ordered", kept.shard())).as("premise: nothing hands the unit back while the flush hangs").isEqualTo(instanceId);
                assertThat(fenceOf("ordered", kept.shard())).as("premise: nor takes it over").isEqualTo(kept.fence());

                if (restartPastTheBound) {
                    // Nothing reviews the kept units of a stopped queue: the start does, once the bound has passed.
                    Awaitility.await().atMost(HUNG_FLUSH_BOUND.plusSeconds(20)).until(
                            () -> Duration.ofNanos(System.nanoTime() - stoppedAt).compareTo(settings.stuckHandlerTimeout().plusMillis(500)) >= 0);
                    queue.start();
                }
                Awaitility.await().atMost(HUNG_FLUSH_BOUND.plusSeconds(20)).untilAsserted(() -> {
                    assertThat(logged.list).extracting(ILoggingEvent::getFormattedMessage)
                                           .filteredOn(message -> message.contains("released ordered unit " + kept.shard() + " on queue")
                                                   && message.contains("under the fence a stop kept it under"))
                                           .as("handed back under the kept fence").hasSize(1);
                    assertThat(ownerOf("ordered", kept.shard())).as("then taken again by the restarted queue").isEqualTo(instanceId);
                    assertThat(fenceOf("ordered", kept.shard())).as("under a new fence").isGreaterThan(kept.fence());
                    assertThat(queue.shardsHeld()).as("every unit").isEqualTo(ShardOwnedSchema.ORDERED_UNITS);
                });
                assertThat(Duration.ofNanos(System.nanoTime() - stoppedAt)).as("not before the bound")
                                                                           .isGreaterThanOrEqualTo(settings.stuckHandlerTimeout());
                assertThat(queue.handBackStillRunning()).as("premise: the stop's budget thread still hangs").isTrue();
                assertThat(holding.awaitAcknowledgementHeld(Duration.ZERO)).as("premise: before it has flushed anything").isFalse();
                assertThat(queue.remaining()).as("the handled message was acknowledged by the hand-back").isZero();
                Awaitility.await().during(Duration.ofMillis(1500)).atMost(Duration.ofSeconds(3)).untilAsserted(
                        () -> assertThat(entries).as("the handled message is not delivered again").hasValue(1));
            } finally {
                release.countDown();
                holding.letConnect();
                holding.letAcknowledge();
                queue.stop();
            }
        } finally {
            queueLogger.detachAppender(logged);
        }
    }

    /**
     * A unit a stop kept whose budget thread is still flushing, with a {@code stuckHandlerTimeout} shorter than that flush
     * is allowed: {@code SHUTDOWN_BUDGET} plus the network bound on each of its waits for the database ({@code leaseTtl}
     * here). {@code stuckHandlerTimeout} bounds how long a handler may run, not how long a flush may take, so past it the
     * unit is not given up while the flush is still within that allowance: a release on request answers {@code BUSY} and
     * the unit is still waited for. Given up at {@code stuckHandlerTimeout}, as it was, a flush that was only slow had the
     * unit handed back or taken over beside it - the double flush {@code STOP_FLUSHING} exists to prevent, the flush's
     * delete then refused and counted as a lost lease. Past the allowance the unit is given up and released on request.
     */
    @Test
    void a_unit_a_stop_kept_whose_flush_outlasts_a_short_stuck_handler_timeout_is_not_given_up_within_the_flush_allowance() throws Exception {
        var entries     = new AtomicInteger();
        var entered     = new CountDownLatch(1);
        var release     = new CountDownLatch(1);
        // leaseTtl 10 s: the flush is allowed SHUTDOWN_BUDGET + 10 s = 12 s, far past the 2 s stuckHandlerTimeout. Nothing
        // heartbeats a stopped queue, so the lease TTL plays no other part here.
        var settings    = settingsWithShedGrace(Duration.ofMillis(300)).toBuilder()
                                                                       .setLeaseTtl(Duration.ofSeconds(10))
                                                                       .setStuckHandlerTimeout(Duration.ofSeconds(2))
                                                                       .build();
        var instanceId  = "kept-behind-a-slow-stop-flush";
        var holding     = new FlushHoldingDataSource(dataSource, FlushHoldingDataSource.STOP_BUDGET_THREAD);
        var logged      = new ListAppender<ILoggingEvent>();
        var queueLogger = (ch.qos.logback.classic.Logger) LoggerFactory.getLogger(ShardOwnedQueue.class);
        logged.start();
        queueLogger.addAppender(logged);
        try (var runtime = new ShardRuntime(dataSource, settings)) {
            var queue = ShardOwnedQueue.builder().setDataSource(holding).setQueueId(QUEUE_ID).setShardCount(SHARD_COUNT)
                                       .setInstanceId(instanceId).setRuntime(runtime).build();
            try {
                queue.startConsumingOrdered((messageId, key, payload, payloadType) -> {
                    // The first delivery of the key holds its handler; a later one returns at once.
                    if (entries.incrementAndGet() == 1) {
                        entered.countDown();
                        awaitQuietly(release);
                    }
                }, settings, SHARD_COUNT);
                var giveUpAfter = queue.stopFlushGiveUpAfter(settings);
                assertThat(giveUpAfter).as("premise: the flush is allowed SHUTDOWN_BUDGET plus leaseTtl, longer than stuckHandlerTimeout")
                                       .isEqualTo(ShardOwnedQueue.SHUTDOWN_BUDGET.plus(settings.leaseTtl()));
                queue.enqueueOrdered(List.of(new ShardOwnedStorage.OrderedPayload("ACC-1", 0, "0".getBytes(StandardCharsets.UTF_8), 1)));
                assertThat(entered.await(20, TimeUnit.SECONDS)).isTrue();

                // The stop's budget thread is held as it asks for its connection: a flush still within what it is allowed.
                holding.holdConnections();
                var stoppedAt = System.nanoTime();
                queue.stop();
                assertThat(holding.awaitConnectionHeld(Duration.ofSeconds(10))).as("premise: the stop's budget thread is held").isTrue();
                assertThat(queue.metrics().keptUnitsInHandler()).as("premise: the busy unit is kept").isEqualTo(1);
                var keptUnit = ShardOwnedSchema.unitForKey("ACC-1", ShardOwnedSchema.ORDERED_UNITS);
                var kept     = new UnitFence(keptUnit, fenceOf("ordered", keptUnit));

                // Past stuckHandlerTimeout from the stop - the unit was kept within the stop - and so past it for the key in
                // its handler too.
                Awaitility.await().atMost(Duration.ofSeconds(10)).until(
                        () -> Duration.ofNanos(System.nanoTime() - stoppedAt).compareTo(settings.stuckHandlerTimeout().plusSeconds(1)) >= 0);
                assertThat(queue.releaseKeptOrderedUnit(instanceId, keptUnit))
                        .as("past stuckHandlerTimeout, a release on request still waits for the stop's flush")
                        .hasValueSatisfying(answer -> assertThat(answer.outcome()).isEqualTo(KeptUnitRelease.Outcome.BUSY));
                assertThat(Duration.ofNanos(System.nanoTime() - stoppedAt)).as("premise: well within what the flush is allowed")
                                                                           .isLessThan(giveUpAfter.minusSeconds(3));
                assertThat(queue.keptOrderedUnitAwaited()).as("the unit is still waited for").isTrue();
                assertThat(ownerOf("ordered", kept.shard())).as("its row still names the instance").isEqualTo(instanceId);
                assertThat(fenceOf("ordered", kept.shard())).as("under the fence it was kept under").isEqualTo(kept.fence());
                assertThat(logged.list).extracting(ILoggingEvent::getFormattedMessage)
                                       .noneMatch(message -> message.contains("stops waiting for ordered unit " + kept.shard()));

                Awaitility.await().atMost(Duration.ofSeconds(30)).pollInterval(Duration.ofMillis(500)).untilAsserted(
                        () -> assertThat(queue.releaseKeptOrderedUnit(instanceId, keptUnit))
                                .as("past what the flush is allowed, released on request though it is still held")
                                .hasValueSatisfying(answer -> assertThat(answer.outcome()).isEqualTo(KeptUnitRelease.Outcome.RELEASED)));
                assertThat(Duration.ofNanos(System.nanoTime() - stoppedAt)).as("not before what the flush is allowed")
                                                                           .isGreaterThanOrEqualTo(giveUpAfter);
                assertThat(queue.handBackStillRunning()).as("premise: the stop's budget thread is still held").isTrue();
                assertThat(logged.list).extracting(ILoggingEvent::getFormattedMessage)
                                       .filteredOn(message -> message.contains("stops waiting for ordered unit " + kept.shard())
                                               && message.contains("still not finished flushing"))
                                       .as("one WARN gives the unit up").hasSize(1);
            } finally {
                release.countDown();
                holding.letConnect();
                holding.letAcknowledge();
                queue.stop();
            }
        } finally {
            queueLogger.detachAppender(logged);
        }
    }

    /**
     * A unit a stop kept, held up because that stop's budget thread has not finished flushing it, with its key still in the
     * handler longer than {@code leaseTtl}. The held-up WARN says what holds the unit - the stop's flush - and that a release
     * on request answers {@code BUSY} until that flush returns or the unit is given up. It used to log the WARN for a unit
     * whose handler outlasts {@code leaseTtl}, which says the unit waits only for that handler and points at
     * {@code releaseKeptOrderedUnit}: an operator following it got {@code BUSY} every time, with nothing saying why.
     */
    @Test
    void a_unit_held_up_for_its_stops_flush_is_reported_as_such_with_its_key_in_the_handler() throws Exception {
        aUnitHeldUpForItsStopsFlushIsReportedAsSuch(false);
    }

    /**
     * As above, but the key the unit was kept for has left its handler: the unit waits for the stop's flush alone. Nothing
     * reported it held up until it was given up.
     */
    @Test
    void a_unit_held_up_for_its_stops_flush_is_reported_as_such_once_its_handler_has_returned() throws Exception {
        aUnitHeldUpForItsStopsFlushIsReportedAsSuch(true);
    }

    private void aUnitHeldUpForItsStopsFlushIsReportedAsSuch(boolean handlerReturned) throws Exception {
        var entries     = new AtomicInteger();
        var entered     = new CountDownLatch(1);
        var release     = new CountDownLatch(1);
        // stuckHandlerTimeout far past the test: nothing gives the unit up while it is watched.
        var settings    = settingsWithShedGrace(Duration.ofMillis(300)).toBuilder().setStuckHandlerTimeout(Duration.ofMinutes(5)).build();
        var instanceId  = "held-up-for-a-stop-flush-" + (handlerReturned ? "handler-returned" : "key-in-handler");
        var holding     = new FlushHoldingDataSource(dataSource, FlushHoldingDataSource.STOP_BUDGET_THREAD);
        var logged      = new ListAppender<ILoggingEvent>();
        var queueLogger = (ch.qos.logback.classic.Logger) LoggerFactory.getLogger(ShardOwnedQueue.class);
        logged.start();
        queueLogger.addAppender(logged);
        try (var runtime = new ShardRuntime(dataSource, settings)) {
            var queue = ShardOwnedQueue.builder().setDataSource(holding).setQueueId(QUEUE_ID).setShardCount(SHARD_COUNT)
                                       .setInstanceId(instanceId).setRuntime(runtime).build();
            try {
                queue.startConsumingOrdered((messageId, key, payload, payloadType) -> {
                    // The first delivery of the key holds its handler; a later one returns at once.
                    if (entries.incrementAndGet() == 1) {
                        entered.countDown();
                        awaitQuietly(release);
                    }
                }, settings, SHARD_COUNT);
                queue.enqueueOrdered(List.of(new ShardOwnedStorage.OrderedPayload("ACC-1", 0, "0".getBytes(StandardCharsets.UTF_8), 1)));
                assertThat(entered.await(20, TimeUnit.SECONDS)).isTrue();

                holding.holdConnections();
                var stoppedAt = System.nanoTime();
                queue.stop();
                assertThat(holding.awaitConnectionHeld(Duration.ofSeconds(10))).as("premise: the stop's budget thread is held").isTrue();
                assertThat(queue.metrics().keptUnitsInHandler()).as("premise: the busy unit is kept").isEqualTo(1);
                var keptUnit = ShardOwnedSchema.unitForKey("ACC-1", ShardOwnedSchema.ORDERED_UNITS);
                if (handlerReturned) {
                    release.countDown();
                    Awaitility.await().atMost(Duration.ofSeconds(5)).untilAsserted(
                            () -> assertThat(queue.metrics().keptUnitsInHandler()).as("premise: the kept key has left its handler").isZero());
                }

                // Kept longer than the network bound on the flush's waits for the database (here SHUTDOWN_BUDGET, the 1 s
                // leaseTtl being shorter): stop() returns only after waiting SHUTDOWN_BUDGET for its budget thread, which it
                // starts after keeping the unit, so the unit has been kept longer than that. Nothing reviews the kept
                // units of a stopped queue on its own; the review is asked here as a heartbeat tick would ask it.
                var networkWaitBound = queue.stopNetworkWaitBound();
                assertThat(networkWaitBound).as("premise").isEqualTo(ShardOwnedQueue.SHUTDOWN_BUDGET);
                Awaitility.await().atMost(Duration.ofSeconds(10)).until(
                        () -> Duration.ofNanos(System.nanoTime() - stoppedAt).compareTo(networkWaitBound.plusMillis(500)) >= 0);
                queue.reviewStuckKeptUnits();
                queue.reviewStuckKeptUnits();

                var heldUp = logged.list.stream()
                                        .filter(event -> event.getLevel() == ch.qos.logback.classic.Level.WARN)
                                        .map(ILoggingEvent::getFormattedMessage)
                                        .filter(message -> message.contains("holds ordered unit " + keptUnit + " on queue " + QUEUE_ID + " unserved"))
                                        .toList();
                assertThat(heldUp).as("one held-up WARN, rate limited").singleElement().satisfies(warning -> {
                    assertThat(warning).as("names the stop's flush as what holds the unit")
                                       .contains("has still not finished flushing the acknowledgements of the owners it ended");
                    assertThat(warning).as("and what a release on request answers meanwhile").contains("answers BUSY");
                    assertThat(warning).as("not the advice for a unit waiting for its handler").doesNotContain("release-kept");
                    if (handlerReturned) {
                        assertThat(warning).contains("No key of it is in a handler");
                    } else {
                        assertThat(warning).contains("1 key(s)").contains("ACC-1");
                    }
                });
                assertThat(queue.releaseKeptOrderedUnit(instanceId, keptUnit)).as("as the WARN says")
                        .hasValueSatisfying(answer -> assertThat(answer.outcome()).isEqualTo(KeptUnitRelease.Outcome.BUSY));
                assertThat(queue.keptOrderedUnitNotHandedBack()).as("premise: the unit is still kept").isTrue();
            } finally {
                release.countDown();
                holding.letConnect();
                holding.letAcknowledge();
                queue.stop();
            }
        } finally {
            queueLogger.detachAppender(logged);
        }
    }

    /**
     * The same kept unit, its handler returned and the budget thread of the stop that kept it still flushing it, when the
     * restarted queue is stopped again. That later stop keeps nothing of its own, and the kept unit is no key in a
     * handler, but its lease row still names this instance and no hand-back may release it until the earlier flush has
     * passed: the later stop leaves the instance registered, as for a kept key still in its handler. Deleted, the
     * membership row made the unit read as ownerless while the earlier stop still flushed it - another instance could take
     * it at once, and that flush was then refused.
     */
    @Test
    void a_later_stop_leaves_the_instance_registered_while_an_earlier_stop_is_still_flushing_a_unit_it_kept() throws Exception {
        var entries    = new AtomicInteger();
        var entered    = new CountDownLatch(1);
        var release    = new CountDownLatch(1);
        var settings   = settingsWithShedGrace(Duration.ofMillis(300));
        var instanceId = "kept-while-an-earlier-stop-flushes";
        var holding    = new FlushHoldingDataSource(dataSource, FlushHoldingDataSource.STOP_BUDGET_THREAD);
        try (var runtime = new ShardRuntime(dataSource, settings)) {
            var queue = ShardOwnedQueue.builder().setDataSource(holding).setQueueId(QUEUE_ID).setShardCount(SHARD_COUNT)
                                       .setInstanceId(instanceId).setRuntime(runtime).build();
            try {
                queue.startConsumingOrdered((messageId, key, payload, payloadType) -> {
                    // The first delivery of the key holds its handler; a later one returns at once.
                    if (entries.incrementAndGet() == 1) {
                        entered.countDown();
                        awaitQuietly(release);
                    }
                }, settings, SHARD_COUNT);
                queue.enqueueOrdered(List.of(new ShardOwnedStorage.OrderedPayload("ACC-1", 0, "0".getBytes(StandardCharsets.UTF_8), 1)));
                assertThat(entered.await(20, TimeUnit.SECONDS)).isTrue();

                holding.holdConnections();
                queue.stop();
                assertThat(queue.handBackStillRunning()).as("premise: the stop gave up waiting for its budget thread").isTrue();
                assertThat(queue.metrics().keptUnitsInHandler()).as("premise: the busy unit is kept").isEqualTo(1);
                var keptUnit = ShardOwnedSchema.unitForKey("ACC-1", ShardOwnedSchema.ORDERED_UNITS);
                var kept     = new UnitFence(keptUnit, fenceOf("ordered", keptUnit));

                release.countDown();
                Awaitility.await().atMost(Duration.ofSeconds(5))
                          .untilAsserted(() -> assertThat(queue.metrics().keptUnitsInHandler()).as("premise: the kept handler has returned").isZero());
                holding.letConnect();
                assertThat(holding.awaitAcknowledgementHeld(Duration.ofSeconds(10)))
                        .as("premise: the stop's budget thread is flushing the kept owner's acknowledgement").isTrue();

                queue.start();
                assertThat(queue.shardsHeld()).as("premise: the restart leaves the kept unit alone")
                                              .isEqualTo(ShardOwnedSchema.ORDERED_UNITS - 1);
                // The later stop: its own budget thread goes through, and finishes, while the earlier one is still held.
                queue.stop();
                assertThat(queue.shardsHeld()).as("premise: the later stop has ended its run").isZero();
                Awaitility.await().atMost(Duration.ofSeconds(10))
                          .untilAsserted(() -> assertThat(orderedUnitsOwnedBy(instanceId))
                                  .as("premise: the later stop has released the units of its own run").isEqualTo(1));
                Awaitility.await().during(Duration.ofMillis(1_000)).atMost(Duration.ofSeconds(5)).untilAsserted(() -> {
                    assertThat(instanceRegistered(instanceId)).as("the later stop leaves the instance registered").isTrue();
                    assertThat(ownerOf("ordered", kept.shard())).as("the kept unit's row still names the instance").isEqualTo(instanceId);
                    assertThat(fenceOf("ordered", kept.shard())).as("under the fence it was kept under").isEqualTo(kept.fence());
                });

                holding.letAcknowledge();
                Awaitility.await().atMost(Duration.ofSeconds(10)).untilAsserted(() -> {
                    assertThat(queue.handBackStillRunning()).as("premise: the earlier stop's budget thread has finished").isFalse();
                    assertThat(queue.keptOrderedUnitNotHandedBack()).as("handed back once that stop's flush has passed it").isFalse();
                    assertThat(ownerOf("ordered", kept.shard())).as("released").isNull();
                });
                assertThat(queue.metrics().fencedOutAcks.sum()).as("the earlier stop's flush of the kept owner was not refused").isZero();
                assertThat(entries).as("the message the kept handler handled is not delivered again").hasValue(1);
                assertThat(queue.orderedRemaining()).as("its acknowledgement was flushed").isZero();
            } finally {
                release.countDown();
                holding.letConnect();
                holding.letAcknowledge();
                queue.stop();
            }
        }
    }

    /**
     * A unit a stop kept, flushed by that stop, whose handler has returned since and whose hand-back - on the thread the
     * key leaving its handler started - is still flushing it when the restarted queue is stopped again. That later stop
     * cannot claim the hand-back the other thread is running, and keeps nothing of its own: it used to file the unit with
     * those whose hand-back it runs itself, find the claim taken, and deregister the instance while the running hand-back
     * had not released the row yet - the unit then read as ownerless, another instance could take it under a new fence,
     * and the hand-back's flush was refused (a lease-lost WARN, {@code fencedOutAcks}, the handled message delivered
     * again). It now stays registered, as for a unit the earlier stop's budget thread is still flushing.
     */
    @Test
    void a_later_stop_leaves_the_instance_registered_while_a_hand_back_of_a_unit_an_earlier_stop_kept_is_still_running() throws Exception {
        var entries    = new AtomicInteger();
        var entered    = new CountDownLatch(1);
        var release    = new CountDownLatch(1);
        // A long lease: a heartbeat tick, every third of it, would otherwise hand the unit back itself, on its own thread,
        // should it claim the unit before the thread the key leaving its handler starts.
        var settings   = settingsWithShedGrace(Duration.ofMillis(300)).toBuilder().setLeaseTtl(Duration.ofSeconds(30)).build();
        var instanceId = "kept-while-its-hand-back-runs";
        var holding    = new FlushHoldingDataSource(dataSource, FlushHoldingDataSource.KEPT_UNIT_HAND_BACK_THREAD);
        try (var runtime = new ShardRuntime(dataSource, settings)) {
            var queue = ShardOwnedQueue.builder().setDataSource(holding).setQueueId(QUEUE_ID).setShardCount(SHARD_COUNT)
                                       .setInstanceId(instanceId).setRuntime(runtime).build();
            try {
                queue.startConsumingOrdered((messageId, key, payload, payloadType) -> {
                    // The first delivery of the key holds its handler; a later one returns at once.
                    if (entries.incrementAndGet() == 1) {
                        entered.countDown();
                        awaitQuietly(release);
                    }
                }, settings, SHARD_COUNT);
                queue.enqueueOrdered(List.of(new ShardOwnedStorage.OrderedPayload("ACC-1", 0, "0".getBytes(StandardCharsets.UTF_8), 1)));
                assertThat(entered.await(20, TimeUnit.SECONDS)).isTrue();

                queue.stop();
                assertThat(queue.metrics().keptUnitsInHandler()).as("premise: the busy unit is kept").isEqualTo(1);
                Awaitility.await().atMost(Duration.ofSeconds(10))
                          .untilAsserted(() -> assertThat(queue.handBackStillRunning()).as("premise: the stop's budget thread has flushed the kept owner")
                                                                                     .isFalse());
                var keptUnit = ShardOwnedSchema.unitForKey("ACC-1", ShardOwnedSchema.ORDERED_UNITS);
                var kept     = new UnitFence(keptUnit, fenceOf("ordered", keptUnit));
                assertThat(ownerOf("ordered", kept.shard())).as("premise: the kept unit's row names the instance").isEqualTo(instanceId);

                queue.start();
                assertThat(queue.shardsHeld()).as("premise: the restart leaves the kept unit alone")
                                              .isEqualTo(ShardOwnedSchema.ORDERED_UNITS - 1);

                // The kept handler returns: its last key leaving the handler hands the unit back on a thread of its own,
                // held as it flushes the acknowledgement the handler left.
                holding.holdConnections();
                release.countDown();
                assertThat(holding.awaitConnectionHeld(Duration.ofSeconds(10)))
                        .as("premise: the unit is being handed back on the thread its key leaving the handler started").isTrue();
                holding.letConnect();
                assertThat(holding.awaitAcknowledgementHeld(Duration.ofSeconds(10)))
                        .as("premise: that hand-back is flushing the kept owner's acknowledgement").isTrue();

                // The later stop: it keeps nothing of its own, and cannot claim the hand-back running on the other thread.
                queue.stop();
                assertThat(queue.shardsHeld()).as("premise: the later stop has ended its run").isZero();
                Awaitility.await().atMost(Duration.ofSeconds(10))
                          .untilAsserted(() -> assertThat(orderedUnitsOwnedBy(instanceId))
                                  .as("premise: the later stop has released the units of its own run").isEqualTo(1));
                Awaitility.await().atMost(Duration.ofSeconds(10))
                          .untilAsserted(() -> assertThat(queue.handBackStillRunning()).as("premise: the later stop's budget thread has finished")
                                                                                     .isFalse());
                Awaitility.await().during(Duration.ofMillis(1_000)).atMost(Duration.ofSeconds(5)).untilAsserted(() -> {
                    assertThat(instanceRegistered(instanceId)).as("the later stop leaves the instance registered").isTrue();
                    assertThat(ownerOf("ordered", kept.shard())).as("the kept unit's row still names the instance").isEqualTo(instanceId);
                    assertThat(fenceOf("ordered", kept.shard())).as("under the fence it was kept under").isEqualTo(kept.fence());
                });

                holding.letAcknowledge();
                Awaitility.await().atMost(Duration.ofSeconds(10)).untilAsserted(() -> {
                    assertThat(queue.keptOrderedUnitNotHandedBack()).as("handed back once that hand-back has run").isFalse();
                    assertThat(ownerOf("ordered", kept.shard())).as("released").isNull();
                });
                assertThat(queue.metrics().fencedOutAcks.sum()).as("the hand-back's flush of the kept owner was not refused").isZero();
                assertThat(entries).as("the message the kept handler handled is not delivered again").hasValue(1);
                assertThat(queue.orderedRemaining()).as("its acknowledgement was flushed").isZero();
            } finally {
                release.countDown();
                holding.letConnect();
                holding.letAcknowledge();
                queue.stop();
            }
        }
    }

    /**
     * A unit a stop kept whose handler has returned and whose hand-back failed, so that the restarted queue's later stop
     * finds it awaiting a hand-back and takes on handing it back itself, on its budget thread. Before that thread gets to
     * it, another thread claims the unit - here a release on request - and is still flushing it when the budget thread's
     * claim is refused. The budget thread used to deregister the instance then, the unit's row still naming it: the unit
     * read as ownerless, another instance could take it under a new fence, and the running hand-back's flush was refused.
     * It now reads the unit's phase again after its own hand-backs and stays registered, as for a unit another thread was
     * already handing back when the stop ended its run.
     */
    @Test
    void a_later_stop_leaves_the_instance_registered_while_a_unit_it_was_to_hand_back_is_released_on_request() throws Exception {
        var entries       = new AtomicInteger();
        var entered       = new CountDownLatch(1);
        var release       = new CountDownLatch(1);
        // A long lease: a heartbeat tick, every third of it, would otherwise retry the failed hand-back itself.
        var settings      = settingsWithShedGrace(Duration.ofMillis(300)).toBuilder().setLeaseTtl(Duration.ofSeconds(30)).build();
        var instanceId    = "kept-released-on-request-under-a-later-stop";
        var requesterName = "release-kept-unit-on-request";
        var stopHolding   = new FlushHoldingDataSource(dataSource, FlushHoldingDataSource.STOP_BUDGET_THREAD);
        var request       = new FlushHoldingDataSource(stopHolding, requesterName);
        try (var runtime = new ShardRuntime(dataSource, settings)) {
            var queue = ShardOwnedQueue.builder().setDataSource(request).setQueueId(QUEUE_ID).setShardCount(SHARD_COUNT)
                                       .setInstanceId(instanceId).setRuntime(runtime).build();
            var releaseOnRequest = new FutureTask<Optional<KeptUnitRelease>>(
                    () -> queue.releaseKeptOrderedUnit(instanceId, ShardOwnedSchema.unitForKey("ACC-1", ShardOwnedSchema.ORDERED_UNITS)));
            try {
                queue.startConsumingOrdered((messageId, key, payload, payloadType) -> {
                    // The first delivery of the key holds its handler; a later one returns at once.
                    if (entries.incrementAndGet() == 1) {
                        entered.countDown();
                        awaitQuietly(release);
                    }
                }, settings, SHARD_COUNT);
                queue.enqueueOrdered(List.of(new ShardOwnedStorage.OrderedPayload("ACC-1", 0, "0".getBytes(StandardCharsets.UTF_8), 1)));
                assertThat(entered.await(20, TimeUnit.SECONDS)).isTrue();

                queue.stop();
                assertThat(queue.metrics().keptUnitsInHandler()).as("premise: the busy unit is kept").isEqualTo(1);
                Awaitility.await().atMost(Duration.ofSeconds(10))
                          .untilAsserted(() -> assertThat(queue.handBackStillRunning()).as("premise: the stop's budget thread has flushed the kept owner")
                                                                                     .isFalse());
                var keptUnit = ShardOwnedSchema.unitForKey("ACC-1", ShardOwnedSchema.ORDERED_UNITS);
                var kept     = new UnitFence(keptUnit, fenceOf("ordered", keptUnit));
                assertThat(ownerOf("ordered", kept.shard())).as("premise: the kept unit's row names the instance").isEqualTo(instanceId);

                queue.start();
                assertThat(queue.shardsHeld()).as("premise: the restart leaves the kept unit alone")
                                              .isEqualTo(ShardOwnedSchema.ORDERED_UNITS - 1);

                // The kept handler returns, and the hand-back its key leaving the handler starts fails to release the row.
                LeaseReleaseRefusals.failLeaseReleasesOf(dataSource, new short[]{QUEUE_ID}, "ordered", kept.shard(), true);
                try {
                    release.countDown();
                    Awaitility.await().atMost(Duration.ofSeconds(10))
                              .untilAsserted(() -> assertThat(LeaseReleaseRefusals.releasesRefused(dataSource, QUEUE_ID, kept.shard()))
                                      .as("premise: the kept unit's hand-back failed to release its row").isGreaterThanOrEqualTo(1));
                    Awaitility.await().atMost(Duration.ofSeconds(10))
                              .untilAsserted(() -> assertThat(threadsNamed(FlushHoldingDataSource.KEPT_UNIT_HAND_BACK_THREAD + QUEUE_ID + "-" + kept.shard()))
                                      .as("premise: that hand-back has ended, leaving the unit awaiting a hand-back").isZero());
                    assertThat(queue.orderedRemaining()).as("premise: its acknowledgement was flushed").isZero();

                    // The later stop keeps nothing of its own and finds the unit awaiting a hand-back, which its budget
                    // thread is to run; that thread is held as it asks for its first connection.
                    stopHolding.holdConnections();
                    queue.stop();
                    assertThat(queue.shardsHeld()).as("premise: the later stop has ended its run").isZero();
                    assertThat(stopHolding.awaitConnectionHeld(Duration.ofSeconds(10)))
                            .as("premise: the later stop's budget thread is held before its hand-backs").isTrue();
                } finally {
                    LeaseReleaseRefusals.failLeaseReleasesOf(dataSource, new short[]{QUEUE_ID}, "ordered", kept.shard(), false);
                }

                // Another thread claims the unit first: a release on request, held as it asks for a connection to flush it.
                request.holdConnections();
                var requester = new Thread(releaseOnRequest, requesterName);
                requester.setDaemon(true);
                requester.start();
                assertThat(request.awaitConnectionHeld(Duration.ofSeconds(10)))
                        .as("premise: the release on request has claimed the unit and is about to flush it").isTrue();

                // The later stop's budget thread goes on: its claim of the unit is refused.
                stopHolding.letAcknowledge();
                stopHolding.letConnect();
                Awaitility.await().atMost(Duration.ofSeconds(10))
                          .untilAsserted(() -> assertThat(queue.handBackStillRunning()).as("premise: the later stop's budget thread has finished")
                                                                                     .isFalse());
                assertThat(orderedUnitsOwnedBy(instanceId)).as("premise: the later stop has released the units of its own run").isEqualTo(1);
                Awaitility.await().during(Duration.ofMillis(1_000)).atMost(Duration.ofSeconds(5)).untilAsserted(() -> {
                    assertThat(instanceRegistered(instanceId)).as("the later stop leaves the instance registered").isTrue();
                    assertThat(ownerOf("ordered", kept.shard())).as("the kept unit's row still names the instance").isEqualTo(instanceId);
                    assertThat(fenceOf("ordered", kept.shard())).as("under the fence it was kept under").isEqualTo(kept.fence());
                });

                request.letAcknowledge();
                request.letConnect();
                assertThat(releaseOnRequest.get(10, TimeUnit.SECONDS))
                        .as("the release on request released the unit")
                        .hasValueSatisfying(answer -> assertThat(answer.outcome()).isEqualTo(KeptUnitRelease.Outcome.RELEASED));
                assertThat(ownerOf("ordered", kept.shard())).as("released").isNull();
                assertThat(queue.keptOrderedUnitNotHandedBack()).as("handed back").isFalse();
                assertThat(queue.metrics().fencedOutAcks.sum()).as("no flush of the kept owner was refused").isZero();
                assertThat(entries).as("the message the kept handler handled is not delivered again").hasValue(1);
            } finally {
                release.countDown();
                stopHolding.letConnect();
                stopHolding.letAcknowledge();
                request.letConnect();
                request.letAcknowledge();
                queue.stop();
            }
        }
    }

    /**
     * The same claim as {@link #a_later_stop_leaves_the_instance_registered_while_a_unit_it_was_to_hand_back_is_released_on_request},
     * seen from the other lane of a {@link PostgresqlMessageQueue} subscription, which shares the instance's membership
     * row ({@link ShardOwnedQueue#shareMembershipWith}). The ordered lane's later stop ended its run with the unit awaiting
     * a hand-back, so it wrote its need for the row false, and a release on request claimed the unit after that. The
     * unordered lane's stop, reading the ordered lane's need while that release still held the unit's row, used to read
     * that snapshot, find the row needed by nobody and delete it: the unit read as ownerless, another instance could take
     * it under a new fence, and the release's flush was refused. The ordered lane's need now counts a running hand-back
     * live, and the unordered lane's stop leaves the row. {@code PostgresqlMessageQueue} stops the unordered lane first;
     * its hand-back reads the ordered lane's need this late when it runs past {@link ShardOwnedQueue#SHUTDOWN_BUDGET} on a
     * thread the stop gave up waiting for, which this test stands in for by stopping the unordered lane last.
     */
    @Test
    void the_other_lane_of_a_subscription_leaves_the_instance_registered_while_a_unit_the_ordered_lane_kept_is_released_on_request() throws Exception {
        var entries       = new AtomicInteger();
        var entered       = new CountDownLatch(1);
        var release       = new CountDownLatch(1);
        // A long lease: a heartbeat tick, every third of it, would otherwise retry the failed hand-back itself.
        var settings      = settingsWithShedGrace(Duration.ofMillis(300)).toBuilder().setLeaseTtl(Duration.ofSeconds(30)).build();
        var instanceId    = "kept-released-on-request-under-the-other-lanes-stop";
        var requesterName = "release-kept-unit-on-request";
        var stopHolding   = new FlushHoldingDataSource(dataSource, FlushHoldingDataSource.STOP_BUDGET_THREAD);
        var request       = new FlushHoldingDataSource(stopHolding, requesterName);
        try (var runtime = new ShardRuntime(dataSource, settings)) {
            var ordered = ShardOwnedQueue.builder().setDataSource(request).setQueueId(QUEUE_ID).setShardCount(SHARD_COUNT)
                                         .setInstanceId(instanceId).setRuntime(runtime).build();
            // Not through the holding DataSources: its stop's budget thread bears the same name as the ordered lane's.
            var unordered = ShardOwnedQueue.builder().setDataSource(dataSource).setQueueId(QUEUE_ID).setShardCount(SHARD_COUNT)
                                           .setInstanceId(instanceId).setRuntime(runtime).build();
            // As PostgresqlMessageQueue.consume() pairs a subscription's two lanes.
            ordered.shareMembershipWith(unordered);
            unordered.shareMembershipWith(ordered);
            var releaseOnRequest = new FutureTask<Optional<KeptUnitRelease>>(
                    () -> ordered.releaseKeptOrderedUnit(instanceId, ShardOwnedSchema.unitForKey("ACC-1", ShardOwnedSchema.ORDERED_UNITS)));
            try {
                unordered.startConsuming((messageId, payload, payloadType) -> { }, settings, SHARD_COUNT);
                ordered.startConsumingOrdered((messageId, key, payload, payloadType) -> {
                    // The first delivery of the key holds its handler; a later one returns at once.
                    if (entries.incrementAndGet() == 1) {
                        entered.countDown();
                        awaitQuietly(release);
                    }
                }, settings, SHARD_COUNT);
                ordered.enqueueOrdered(List.of(new ShardOwnedStorage.OrderedPayload("ACC-1", 0, "0".getBytes(StandardCharsets.UTF_8), 1)));
                assertThat(entered.await(20, TimeUnit.SECONDS)).isTrue();

                ordered.stop();
                assertThat(ordered.metrics().keptUnitsInHandler()).as("premise: the busy unit is kept").isEqualTo(1);
                Awaitility.await().atMost(Duration.ofSeconds(10))
                          .untilAsserted(() -> assertThat(ordered.handBackStillRunning()).as("premise: the stop's budget thread has flushed the kept owner")
                                                                                       .isFalse());
                var keptUnit = ShardOwnedSchema.unitForKey("ACC-1", ShardOwnedSchema.ORDERED_UNITS);
                var kept     = new UnitFence(keptUnit, fenceOf("ordered", keptUnit));
                assertThat(ownerOf("ordered", kept.shard())).as("premise: the kept unit's row names the instance").isEqualTo(instanceId);

                ordered.start();
                assertThat(ordered.shardsHeld()).as("premise: the restart leaves the kept unit alone")
                                                .isEqualTo(ShardOwnedSchema.ORDERED_UNITS - 1);

                // The kept handler returns, and the hand-back its key leaving the handler starts fails to release the row.
                LeaseReleaseRefusals.failLeaseReleasesOf(dataSource, new short[]{QUEUE_ID}, "ordered", kept.shard(), true);
                try {
                    release.countDown();
                    Awaitility.await().atMost(Duration.ofSeconds(10))
                              .untilAsserted(() -> assertThat(LeaseReleaseRefusals.releasesRefused(dataSource, QUEUE_ID, kept.shard()))
                                      .as("premise: the kept unit's hand-back failed to release its row").isGreaterThanOrEqualTo(1));
                    Awaitility.await().atMost(Duration.ofSeconds(10))
                              .untilAsserted(() -> assertThat(threadsNamed(FlushHoldingDataSource.KEPT_UNIT_HAND_BACK_THREAD + QUEUE_ID + "-" + kept.shard()))
                                      .as("premise: that hand-back has ended, leaving the unit awaiting a hand-back").isZero());
                    assertThat(ordered.orderedRemaining()).as("premise: its acknowledgement was flushed").isZero();

                    // The ordered lane's later stop keeps nothing of its own and finds the unit awaiting a hand-back, so it
                    // writes its need for the row false; its budget thread, which is to hand the unit back, is held as it
                    // asks for its first connection.
                    stopHolding.holdConnections();
                    ordered.stop();
                    assertThat(ordered.shardsHeld()).as("premise: the ordered lane's later stop has ended its run").isZero();
                    assertThat(stopHolding.awaitConnectionHeld(Duration.ofSeconds(10)))
                            .as("premise: that stop's budget thread is held before its hand-backs").isTrue();
                } finally {
                    LeaseReleaseRefusals.failLeaseReleasesOf(dataSource, new short[]{QUEUE_ID}, "ordered", kept.shard(), false);
                }

                // A release on request claims the unit after that stop ended its run, and is held as it asks for a
                // connection to flush it.
                request.holdConnections();
                var requester = new Thread(releaseOnRequest, requesterName);
                requester.setDaemon(true);
                requester.start();
                assertThat(request.awaitConnectionHeld(Duration.ofSeconds(10)))
                        .as("premise: the release on request has claimed the unit and is about to flush it").isTrue();

                stopHolding.letAcknowledge();
                stopHolding.letConnect();
                Awaitility.await().atMost(Duration.ofSeconds(10))
                          .untilAsserted(() -> assertThat(ordered.handBackStillRunning()).as("premise: the ordered lane's budget thread has finished")
                                                                                       .isFalse());
                assertThat(orderedUnitsOwnedBy(instanceId)).as("premise: the ordered lane's stop has released the units of its own run").isEqualTo(1);

                // The other lane stops now, with that release still holding the unit's row.
                unordered.stop();
                Awaitility.await().atMost(Duration.ofSeconds(10))
                          .untilAsserted(() -> {
                              assertThat(unordered.handBackStillRunning()).as("premise: the unordered lane's budget thread has finished").isFalse();
                              assertThat(unorderedShardsOwnedBy(instanceId)).as("premise: the unordered lane's stop has released its shards").isZero();
                          });
                Awaitility.await().during(Duration.ofMillis(1_000)).atMost(Duration.ofSeconds(5)).untilAsserted(() -> {
                    assertThat(instanceRegistered(instanceId)).as("the other lane's stop leaves the instance registered").isTrue();
                    assertThat(ownerOf("ordered", kept.shard())).as("the kept unit's row still names the instance").isEqualTo(instanceId);
                    assertThat(fenceOf("ordered", kept.shard())).as("under the fence it was kept under").isEqualTo(kept.fence());
                });

                request.letAcknowledge();
                request.letConnect();
                assertThat(releaseOnRequest.get(10, TimeUnit.SECONDS))
                        .as("the release on request released the unit")
                        .hasValueSatisfying(answer -> assertThat(answer.outcome()).isEqualTo(KeptUnitRelease.Outcome.RELEASED));
                assertThat(ownerOf("ordered", kept.shard())).as("released").isNull();
                assertThat(ordered.keptOrderedUnitNotHandedBack()).as("handed back").isFalse();
                assertThat(ordered.metrics().fencedOutAcks.sum()).as("no flush of the kept owner was refused").isZero();
                assertThat(entries).as("the message the kept handler handled is not delivered again").hasValue(1);
            } finally {
                release.countDown();
                stopHolding.letConnect();
                stopHolding.letAcknowledge();
                request.letConnect();
                request.letAcknowledge();
                unordered.stop();
                ordered.stop();
            }
        }
    }

    /**
     * How many live threads have a name starting with {@code prefix}.
     */
    private static long threadsNamed(String prefix) {
        return Thread.getAllStackTraces().keySet().stream().filter(thread -> thread.isAlive() && thread.getName().startsWith(prefix)).count();
    }

    /**
     * Every unit {@code stop()} gives up is reported released, the one it keeps included. An ordered unit with a key still
     * in a handler after the grace keeps its lease row until that handler has returned and the unit is handed back, or this
     * instance's liveness lapses, but the stopped queue lists it no more, so nothing would report it later: it used to be
     * left reported acquired for good, and the observer's - and the Micrometer counter's - acquired count ran one ahead of
     * its released count after every such stop.
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

    /**
     * A unit a stop kept, on a cluster of two. The queue, restarted while the key was still in its handler and while the
     * other instance was down, took every unit but the kept one, then shed back to its fair share once the other instance
     * came back. Its acquire loop, holding that share, never reached the kept unit again once the handler had returned,
     * and no other instance could take a unit whose lease row named a live instance: every key routed to it stalled for as
     * long as both ran. The kept unit is now handed back once its handler has returned - its acknowledgement flushed under
     * the fence it was kept under, its lease row released - so the instance below its fair share takes it, finds it
     * released, and neither charges an attempt nor delivers the handled message again.
     */
    @Test
    void a_unit_a_stop_kept_is_handed_back_once_its_handler_returns_though_the_restarted_queue_holds_its_fair_share() throws Exception {
        var entries   = new AtomicInteger();
        var entered   = new CountDownLatch(1);
        var release   = new CountDownLatch(1);
        var settings  = settingsWithShedGrace(Duration.ofMillis(300));
        var fairShare = ShardOwnedSchema.ORDERED_UNITS / 2;
        // Both instances' handler: the first delivery of the key holds it; a later one, on either instance, returns at once.
        OrderedPayloadHandler handler = (messageId, key, payload, payloadType) -> {
            if (entries.incrementAndGet() == 1) {
                entered.countDown();
                awaitQuietly(release);
            }
        };
        try (var runtime = new ShardRuntime(dataSource, settings)) {
            var restarted = ShardOwnedQueue.builder().setDataSource(dataSource).setQueueId(QUEUE_ID).setShardCount(SHARD_COUNT)
                                           .setInstanceId("kept-restarted").setRuntime(runtime).build();
            var other = ShardOwnedQueue.builder().setDataSource(dataSource).setQueueId(QUEUE_ID).setShardCount(SHARD_COUNT)
                                       .setInstanceId("kept-other").setRuntime(runtime).build();
            try {
                restarted.startConsumingOrdered(handler, settings, SHARD_COUNT);
                restarted.enqueueOrdered(List.of(new ShardOwnedStorage.OrderedPayload("ACC-1", 0, "0".getBytes(StandardCharsets.UTF_8), 1)));
                assertThat(entered.await(20, TimeUnit.SECONDS)).isTrue();

                restarted.stop();
                var kept = keptOrderedFence("kept-restarted");
                assertThat(kept.shard()).as("premise: the stop kept the unit of the key in its handler")
                                        .isEqualTo(ShardOwnedSchema.unitForKey("ACC-1", ShardOwnedSchema.ORDERED_UNITS));
                restarted.start();
                assertThat(restarted.shardsHeld()).as("premise: restarted alone, it takes every unit but the kept one")
                                                  .isEqualTo(ShardOwnedSchema.ORDERED_UNITS - 1);

                other.startConsumingOrdered(handler, settings, SHARD_COUNT);
                Awaitility.await().atMost(Duration.ofSeconds(20)).untilAsserted(() -> {
                    assertThat(restarted.shardsHeld()).as("premise: the restarted queue shed back to its fair share").isEqualTo(fairShare);
                    assertThat(other.shardsHeld()).as("premise: the other instance holds every free unit").isEqualTo(fairShare - 1);
                });
                assertThat(ownerOf("ordered", kept.shard())).as("premise: the kept unit still names the restarted queue's instance")
                                                            .isEqualTo("kept-restarted");
                assertThat(fenceOf("ordered", kept.shard())).as("premise: under the fence it was kept under").isEqualTo(kept.fence());

                release.countDown();
                Awaitility.await().atMost(Duration.ofSeconds(10)).untilAsserted(() -> {
                    assertThat(ownerOf("ordered", kept.shard())).as("the instance below its fair share takes the unit once its handler has returned")
                                                                .isEqualTo("kept-other");
                    assertThat(other.shardsHeld()).isEqualTo(fairShare);
                });
                Awaitility.await().during(Duration.ofSeconds(1)).atMost(Duration.ofSeconds(5)).untilAsserted(() -> {
                    assertThat(entries).as("the message the kept handler handled is not delivered again").hasValue(1);
                    assertThat(restarted.orderedRemaining()).as("its acknowledgement was flushed as the unit was handed back").isZero();
                });
                assertThat(restarted.shardsHeld()).as("the restarted queue still holds its fair share").isEqualTo(fairShare);
            } finally {
                release.countDown();
                other.stop();
                restarted.stop();
            }
        }
    }

    private static ShardOwnerSettings settingsWithShedGrace(Duration shedGrace) {
        return ShardOwnerSettings.builder()
                                 .setHoleExpiry(Duration.ofMillis(300))
                                 .setSweepInterval(Duration.ofMillis(100))
                                 .setPollBackstop(Duration.ofMillis(50))
                                 .setShedGrace(shedGrace)
                                 .setLeaseTtl(Duration.ofMillis(1000))
                                 .build();
    }

    private int orderedUnitsOwnedBy(String instanceId) throws Exception {
        return queryForInt(dataSource, "Counting the ordered lease rows held by " + instanceId,
                           "SELECT count(*) FROM " + ShardOwnedSchema.LEASE_TABLE + " WHERE queue_id = ? AND lane = 'ordered' AND owner = ?",
                           QUEUE_ID, instanceId);
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
        return queryForLong(dataSource, "Reading the fence of the lease row of queue " + QUEUE_ID + " " + lane + " unit " + shard,
                            "SELECT fence FROM " + ShardOwnedSchema.LEASE_TABLE + " WHERE queue_id = ? AND lane = ? AND shard = ?",
                            QUEUE_ID, lane, shard);
    }

    private boolean instanceRegistered(String instanceId) throws Exception {
        return queryForInt(dataSource, "Counting the instance rows of " + instanceId,
                           "SELECT count(*) FROM " + ShardOwnedSchema.INSTANCE_TABLE + " WHERE queue_id = ? AND instance_id = ?",
                           QUEUE_ID, instanceId) > 0;
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
        return base.toBuilder()
                   .setSweepInterval(Duration.ofSeconds(30))
                   .setPollBackstop(Duration.ofSeconds(30))
                   .setMaxSweepInterval(Duration.ofSeconds(30))
                   .build();
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
        return queryForString(dataSource, "Reading the owner of the lease row of queue " + QUEUE_ID + " " + lane + " unit " + shard,
                              "SELECT owner FROM " + ShardOwnedSchema.LEASE_TABLE + " WHERE queue_id = ? AND lane = ? AND shard = ?",
                              QUEUE_ID, lane, shard);
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
     * so the test makes the build of unit 1 throw ({@code AdoptionHook.beforeBuildingOwner}), after unit 0 has been adopted.
     */
    @ParameterizedTest
    @ValueSource(strings = {"unordered", "ordered"})
    void a_start_whose_owner_for_a_later_unit_cannot_be_built_releases_every_unit_and_leaves_no_wake_up_behind(String lane) throws Exception {
        var settings   = settingsWithShedGrace(Duration.ofSeconds(1));
        var instanceId = "owner-build-fails-" + lane;
        var reported   = new RecordedOwnership();
        var metrics    = new ShardOwnerMetrics(reported);
        var failure    = new IllegalStateException("simulated failure building the owner of unit 1");
        var buildsAttempted = new CopyOnWriteArrayList<Integer>();
        try (var runtime = new ShardRuntime(dataSource, settings)) {
            var queue = ShardOwnedQueue.builder().setDataSource(dataSource).setQueueId(QUEUE_ID).setShardCount(SHARD_COUNT)
                                       .setInstanceId(instanceId).setRuntime(runtime).setMetrics(metrics)
                                       .setAdoptionHook(new ShardOwnedQueue.AdoptionHook() {
                                           @Override
                                           public void beforeBuildingOwner(String builtLane, int unit) {
                                               buildsAttempted.add(unit);
                                               if (unit == 1) {
                                                   throw failure;
                                               }
                                           }
                                       })
                                       .build();
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

    /**
     * A {@code start()} after a {@code stop()} that gave up waiting for its hand-back to the database keeps the membership
     * row it registers, though that hand-back gets through after it. A test transaction holds unit 0's lease row, so the
     * stop's release - and the deregistration after it - waits past the 2 s budget, as behind a database that does not
     * answer; the restart registers the instance, then waits behind the same row to lease unit 0. The abandoned hand-back
     * used to delete the restarted run's membership row once its release got through, and other instances then read the
     * instance's units as ownerless until its next heartbeat. A queue built on its own: no other lane of a subscription
     * was asked, and nothing else stood between the late hand-back and the row.
     */
    @Test
    void a_restart_keeps_its_membership_row_when_the_hand_back_of_the_stop_before_it_gets_through_late() throws Exception {
        var instanceId = "restart-after-late-hand-back";
        // A heartbeat every 20 s: none writes the membership row again while the test runs.
        var settings = settingsWith(Duration.ofMillis(300), Duration.ofSeconds(60));
        var queue    = new ShardOwnedQueue(dataSource, QUEUE_ID, SHARD_COUNT, instanceId);
        try (var locking = dataSource.getConnection()) {
            startConsuming(queue, "unordered", settings);
            Awaitility.await().atMost(Duration.ofSeconds(10)).until(() -> unorderedShardsOwnedBy(instanceId) == SHARD_COUNT);

            locking.setAutoCommit(false);
            CompletableFuture<Void> restarting;
            try {
                assertThat(lockLeaseRowOf(locking, "unordered", 0)).as("premise: the test holds unit 0's lease row").isTrue();
                var registeredBefore = lastSeenMicrosOf(instanceId);
                queue.stop();
                assertThat(queue.handBackStillRunning()).as("premise: the stop gave up waiting for its hand-back").isTrue();
                assertThat(instanceRegistered(instanceId)).as("premise: the hand-back has not deregistered the instance yet").isTrue();

                restarting = CompletableFuture.runAsync(queue::start);
                Awaitility.await().atMost(Duration.ofSeconds(10)).until(() -> lastSeenMicrosOf(instanceId) > registeredBefore);
                assertThat(restarting).as("premise: the restart registered the instance and waits behind the held row").isNotDone();
                assertThat(queue.handBackStillRunning()).as("premise: the hand-back is still waiting to release unit 0").isTrue();
            } finally {
                locking.rollback();
            }
            restarting.get(20, TimeUnit.SECONDS);
            Awaitility.await().atMost(Duration.ofSeconds(20)).until(() -> !queue.handBackStillRunning());

            assertThat(queue.isStarted()).as("premise: the restart started the queue").isTrue();
            assertThat(unorderedShardsOwnedBy(instanceId)).as("premise: the restart leased every shard").isEqualTo(SHARD_COUNT);
            assertThat(instanceRegistered(instanceId)).as("the late hand-back left the restarted run's membership row").isTrue();
        } finally {
            queue.stop();
        }
    }

    /**
     * The hand-back of a {@code stop()} - on the thread the stop gives up waiting for after the 2 s budget - waits for each
     * answer from the database no longer than the queue's {@code leaseTtl}. A test transaction holds unit 0's lease row and
     * never lets go while the test watches, so the stop's release waits on it as on a connection that never answers: the
     * release throws once it has waited that long, the thread goes on to deregister the instance, and ends. It used to wait
     * until the row was let go - on a half-open socket with no {@code socketTimeout}, for the life of the process - holding
     * its pool connection, with {@code handBackStillRunning()} true all along.
     */
    @Test
    void a_stop_whose_hand_back_the_database_never_answers_ends_that_hand_back_once_a_statement_has_waited_the_lease_ttl() throws Exception {
        var instanceId = "stop-hand-back-never-answered";
        var leaseTtl   = Duration.ofSeconds(4);
        var settings   = settingsWith(Duration.ofMillis(300), leaseTtl);
        var queue      = new ShardOwnedQueue(dataSource, QUEUE_ID, SHARD_COUNT, instanceId);
        try (var locking = dataSource.getConnection()) {
            startConsuming(queue, "unordered", settings);
            Awaitility.await().atMost(Duration.ofSeconds(10)).until(() -> unorderedShardsOwnedBy(instanceId) == SHARD_COUNT);

            locking.setAutoCommit(false);
            try {
                assertThat(lockLeaseRowOf(locking, "unordered", 0)).as("premise: the test holds unit 0's lease row").isTrue();
                var stopping = System.nanoTime();
                queue.stop();
                assertThat(queue.handBackStillRunning()).as("premise: the stop gave up waiting for its hand-back").isTrue();

                Awaitility.await().atMost(leaseTtl.plusSeconds(10)).until(() -> !queue.handBackStillRunning());
                var endedAfter = Duration.ofNanos(System.nanoTime() - stopping);

                assertThat(endedAfter).as("the release waited for its answer up to the bound, not less")
                                      .isGreaterThanOrEqualTo(leaseTtl.minusMillis(500));
                assertThat(unorderedShardsOwnedBy(instanceId)).as("premise: the release behind the held row never got through")
                                                             .isPositive();
                assertThat(instanceRegistered(instanceId)).as("the thread went on past the failed release and deregistered the instance")
                                                          .isFalse();
            } finally {
                locking.rollback();
            }
        } finally {
            queue.stop();
        }
    }

    /**
     * A {@code start()} made while the hand-back of the {@code stop()} before it is deleting the instance's membership row -
     * the deletion under way when the start began, here behind a row lock a test transaction holds on the row - waits for
     * that deletion before it registers the instance, at most the 2 s budget, and then says once that it goes on without
     * it. Registering at once, it could write the row before that deletion removed it. Once the lock is released the
     * deletion goes first and the restart's registration writes the row again.
     */
    @Test
    void a_restart_while_the_stop_before_it_is_deleting_the_membership_row_waits_for_that_deletion_and_says_so() throws Exception {
        var instanceId  = "restart-during-deregistration";
        var settings    = settingsWith(Duration.ofMillis(300), Duration.ofSeconds(60));
        var logged      = new ListAppender<ILoggingEvent>();
        var queueLogger = (ch.qos.logback.classic.Logger) LoggerFactory.getLogger(ShardOwnedQueue.class);
        logged.start();
        queueLogger.addAppender(logged);
        var queue = new ShardOwnedQueue(dataSource, QUEUE_ID, SHARD_COUNT, instanceId);
        try (var locking = dataSource.getConnection()) {
            startConsuming(queue, "unordered", settings);
            Awaitility.await().atMost(Duration.ofSeconds(10)).until(() -> unorderedShardsOwnedBy(instanceId) == SHARD_COUNT);

            locking.setAutoCommit(false);
            CompletableFuture<Void> restarting;
            try {
                assertThat(lockMembershipRowOf(locking, instanceId)).as("premise: the test holds the instance's membership row").isTrue();
                queue.stop();
                assertThat(queue.handBackStillRunning()).as("premise: the stop gave up waiting for its hand-back").isTrue();
                assertThat(unorderedShardsOwnedBy(instanceId)).as("premise: the hand-back released the shards and is deleting the row")
                                                             .isZero();

                restarting = CompletableFuture.runAsync(queue::start);
                Awaitility.await().atMost(Duration.ofSeconds(10)).until(() -> !waitingForTheDeregistration(logged).isEmpty());
                assertThat(waitingForTheDeregistration(logged).getFirst()).as("the WARN names the instance and the queue")
                                                                          .contains("Instance " + instanceId)
                                                                          .contains("queue " + QUEUE_ID);
                assertThat(restarting).as("premise: the restart's registration waits behind the held row").isNotDone();
            } finally {
                locking.rollback();
            }
            restarting.get(20, TimeUnit.SECONDS);
            Awaitility.await().atMost(Duration.ofSeconds(20)).until(() -> !queue.handBackStillRunning());

            assertThat(waitingForTheDeregistration(logged)).as("one WARN").hasSize(1);
            assertThat(queue.isStarted()).as("premise: the restart started the queue").isTrue();
            assertThat(instanceRegistered(instanceId)).as("the restarted run is registered once the held row is released").isTrue();
        } finally {
            queue.stop();
            queueLogger.detachAppender(logged);
        }
    }

    private static List<String> waitingForTheDeregistration(ListAppender<ILoggingEvent> logged) {
        return List.copyOf(logged.list).stream()
                   .map(ILoggingEvent::getFormattedMessage)
                   .filter(message -> message.contains("to finish deleting the instance's membership row"))
                   .toList();
    }

    private static ShardOwnerSettings settingsWith(Duration shedGrace, Duration leaseTtl) {
        return ShardOwnerSettings.builder()
                                 .setHoleExpiry(Duration.ofMillis(300))
                                 .setSweepInterval(Duration.ofMillis(100))
                                 .setPollBackstop(Duration.ofMillis(50))
                                 .setShedGrace(shedGrace)
                                 .setLeaseTtl(leaseTtl)
                                 .build();
    }

    private boolean lockLeaseRowOf(Connection connection, String lane, int unit) throws SQLException {
        try (var statement = connection.prepareStatement("SELECT 1 FROM " + ShardOwnedSchema.LEASE_TABLE
                                                                 + " WHERE queue_id = ? AND lane = ? AND shard = ? FOR UPDATE")) {
            statement.setShort(1, QUEUE_ID);
            statement.setString(2, lane);
            statement.setInt(3, unit);
            try (var resultSet = statement.executeQuery()) {
                return resultSet.next();
            }
        }
    }

    private boolean lockMembershipRowOf(Connection connection, String instanceId) throws SQLException {
        try (var statement = connection.prepareStatement("SELECT 1 FROM " + ShardOwnedSchema.INSTANCE_TABLE
                                                                 + " WHERE queue_id = ? AND instance_id = ? FOR UPDATE")) {
            statement.setShort(1, QUEUE_ID);
            statement.setString(2, instanceId);
            try (var resultSet = statement.executeQuery()) {
                return resultSet.next();
            }
        }
    }

    private long lastSeenMicrosOf(String instanceId) throws Exception {
        return queryForLong(dataSource, "Reading when " + instanceId + " last registered",
                            "SELECT (extract(epoch FROM last_seen) * 1000000)::bigint FROM " + ShardOwnedSchema.INSTANCE_TABLE
                                    + " WHERE queue_id = ? AND instance_id = ?",
                            QUEUE_ID, instanceId);
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
                    } else if (!acquired && Thread.currentThread().equals(holderThread.get()) && holdTheRelease.compareAndSet(true, false)) {
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
     * A {@code MessageQueue.stop()} on another thread that gives up waiting for a {@code consume()} stops that
     * {@code consume()}'s pair also after a {@code consume()} made by a {@link QueueObserver} on its thread - told of the
     * first acquisition of its unordered lane - has returned: when the stop returns, no unordered lease row names the
     * outer pair's instance and a message enqueued then is delivered to no handler, while the outer {@code consume()} is
     * still starting its ordered lane. The nested {@code consume()} overwrote the pair being started with its own and
     * never set it back, so the stop stopped the nested pair only, and the outer pair's unordered lane went on leasing and
     * dispatching until the outer {@code consume()} stopped every consumer before it let go. Once the nested
     * {@code consume()} has returned, the observer arms a trigger that holds the next membership upsert of the outer
     * pair's instance for 12 s: the outer ordered lane's start registering the instance, before the outer pair is
     * registered with the queue. The nested pair's ordered lane, started first, holds every ordered unit by then.
     */
    @Test
    void a_message_queue_stop_giving_up_on_a_consume_stops_its_pair_after_an_observers_nested_consume_returned() throws Exception {
        var instanceId    = "spi-nested-consume-stop";
        var nestedId      = instanceId + "-1";
        var consumeNested = new AtomicBoolean(true);
        var nested        = new AtomicReference<Subscription>();
        var outer         = new AtomicReference<Subscription>();
        var delivered     = ConcurrentHashMap.<String>newKeySet();
        MessageHandler recording = (messageId, key, payload, payloadType) -> delivered.add(new String(payload, StandardCharsets.UTF_8));
        try (var queue = new PostgresqlMessageQueue(dataSource, QUEUE_ID, SHARD_COUNT, instanceId)) {
            queue.addObserver(new QueueObserver() {
                @Override
                public void shardOwnershipChanged(int shard, boolean acquired) {
                    if (acquired && consumeNested.compareAndSet(true, false)) {
                        var subscription = consumeUnchecked(queue, recording);
                        try {
                            slowTheRegistrationOf(instanceId, 12);
                        } catch (Exception e) {
                            throw new IllegalStateException(e);
                        }
                        nested.set(subscription);
                    }
                }
            });
            var consuming = new Thread(() -> outer.set(consumeUnchecked(queue, recording)));
            try {
                consuming.start();
                Awaitility.await().atMost(Duration.ofSeconds(20))
                          .alias("premise: the nested consume() returned and the outer one is starting its ordered lane")
                          .until(() -> nested.get() != null && aHeartbeatIsSleeping());
                assertThat(unitsOwnedBy("unordered", instanceId)).as("premise: the outer pair's unordered lane holds shards").isPositive();

                var stopping = CompletableFuture.runAsync(queue::stop);

                assertThat(stopping).as("the stop returned").succeedsWithin(Duration.ofSeconds(8));
                assertThat(consuming.isAlive()).as("premise: the outer consume() was still starting its ordered lane when the stop returned")
                                               .isTrue();
                queue.enqueue(List.of(Message.of("after-the-stop".getBytes(StandardCharsets.UTF_8), 1)));
                // Held for the whole window, so a lease the outer unordered lane takes again after the stop is caught too.
                Awaitility.await().during(Duration.ofSeconds(1)).atMost(Duration.ofSeconds(5))
                          .alias("the stop stopped the outer pair's unordered lane: no unordered lease row names its instance and no lane dispatches after the stop returned")
                          .until(() -> unitsOwnedBy("unordered", instanceId) == 0 && delivered.isEmpty());

                dropSlowRegistration();
                consuming.join(30_000);

                assertThat(consuming.isAlive()).as("the outer consume() returned").isFalse();
                assertThat(outer.get()).as("the outer consume() still returns its subscription").isNotNull();
                assertThat(queue.isStarted()).as("the outer consume() left the queue not started").isFalse();
                assertThat(outer.get().isStarted()).as("neither lane of the outer pair is started").isFalse();
                assertThat(nested.get().isStarted()).as("neither lane of the nested pair is started").isFalse();
                Awaitility.await().during(Duration.ofSeconds(1)).atMost(Duration.ofSeconds(10)).until(
                        () -> !instanceRegistered(instanceId) && unitsOwnedBy("unordered", instanceId) == 0 && unitsOwnedBy("ordered", instanceId) == 0
                                && !instanceRegistered(nestedId) && unitsOwnedBy("unordered", nestedId) == 0 && unitsOwnedBy("ordered", nestedId) == 0);
                assertThat(delivered).as("nothing was delivered").isEmpty();
            } finally {
                dropSlowRegistration();
                // A failed assertion must not leave the outer consume() running against the DataSource tearDown closes.
                consuming.join(30_000);
                // What the queue's stop did not reach, so a failure leaves nothing leasing into the next test.
                if (outer.get() != null) {
                    outer.get().stop();
                }
                if (nested.get() != null) {
                    nested.get().stop();
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
     * thread when it lands between the start taking the lock and marking the queue started:
     * {@link #a_message_queue_stop_from_another_thread_landing_before_start_checks_for_it_leaves_the_queue_not_started}.
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
     * A {@code MessageQueue.stop()} on another thread that lands while a {@code start()} holds the queue's lifecycle lock
     * and has not yet looked for a stop owed leaves the queue not started. The stop gives up waiting for the lock after
     * its 2 s budget and marks the queue not started; the start, finding the stop owed, does not mark it started, neither
     * while it waits for that stop to finish stopping nor after, and no stop is left counted as stopping and no lease or
     * membership row names the instance. The start used to mark the queue started before it looked for the stop owed, so
     * {@code isStarted()} read true after that stop had marked it not started, until the start had waited for the stop
     * and stopped every consumer again. The queue's {@code LifecycleHook} holds the start once it has taken the lock, and
     * the stop is held in its WARN, still counted as stopping without the lock, so the start waits for it with the queue's
     * flag as the start left it.
     */
    @Test
    void a_message_queue_stop_from_another_thread_landing_before_start_checks_for_it_leaves_the_queue_not_started() throws Exception {
        var instanceId      = "spi-stop-before-start-checks";
        var holdTheStart    = new AtomicBoolean();
        var startHeld       = new CountDownLatch(1);
        var releaseTheStart = new CountDownLatch(1);
        var startLetGo      = new AtomicBoolean();
        var holdTheStop     = new AtomicBoolean();
        var stopHeld        = new CountDownLatch(1);
        var releaseTheStop  = new CountDownLatch(1);
        var stopCalledAt    = new AtomicLong();
        var stopGaveUpAfter = new AtomicLong();
        var holdingAppender = new UnsynchronizedAppenderBase<ILoggingEvent>() {
            @Override
            protected void append(ILoggingEvent event) {
                if (event.getFormattedMessage().contains("gave up waiting") && holdTheStop.compareAndSet(true, false)) {
                    stopGaveUpAfter.set(System.nanoTime() - stopCalledAt.get());
                    stopHeld.countDown();
                    awaitQuietly(releaseTheStop);
                }
            }
        };
        var queueLogger = (ch.qos.logback.classic.Logger) LoggerFactory.getLogger(PostgresqlMessageQueue.class);
        holdingAppender.start();
        queueLogger.addAppender(holdingAppender);
        var queue = PostgresqlMessageQueue.builder()
                                          .setDataSource(dataSource)
                                          .setQueueId(QUEUE_ID)
                                          .setShardCount(SHARD_COUNT)
                                          .setInstanceId(instanceId)
                                          .setLifecycleHook(new PostgresqlMessageQueue.LifecycleHook() {
                                              @Override
                                              public void afterLockTaken(String operation) {
                                                  if ("start".equals(operation) && holdTheStart.compareAndSet(true, false)) {
                                                      startHeld.countDown();
                                                      awaitQuietly(releaseTheStart);
                                                      startLetGo.set(true);
                                                  }
                                              }
                                          })
                                          .build();
        Subscription subscription = null;
        try {
            subscription = consumeUnchecked(queue);
            var subscribed = subscription;
            Awaitility.await().atMost(Duration.ofSeconds(20))
                      .alias("premise: both lanes hold units before the queue is stopped")
                      .until(() -> subscribed.unorderedShardsHeld() > 0 && subscribed.orderedUnitsHeld() > 0);
            queue.stop();
            assertThat(queue.isStarted()).as("premise: the queue is stopped before it is started again").isFalse();

            holdTheStart.set(true);
            var starting = new Thread(queue::start);
            starting.start();
            assertThat(startHeld.await(10, TimeUnit.SECONDS))
                    .as("premise: the start holds the lock, held before it looks for a stop owed").isTrue();

            holdTheStop.set(true);
            stopCalledAt.set(System.nanoTime());
            var stopping = new Thread(queue::stop);
            stopping.start();
            assertThat(stopHeld.await(10, TimeUnit.SECONDS))
                    .as("premise: the stop gave up waiting for the lock and is held in its WARN").isTrue();
            assertThat(Duration.ofNanos(stopGaveUpAfter.get())).as("the stop waits 2 s for the queue's lock, and a margin")
                                                               .isLessThan(ShardOwnedQueue.SHUTDOWN_BUDGET.plusMillis(1_500));
            assertThat(queue.stopsWithoutLock()).as("premise: the stop is counted as stopping without the lock").isEqualTo(1);
            assertThat(queue.isStarted()).as("the stop marked the queue not started").isFalse();

            releaseTheStart.countDown();
            // The start's one untimed wait once the hook has let it go is the wait for the held stop (letGoOfLifecycle).
            Awaitility.await().atMost(Duration.ofSeconds(10)).during(Duration.ofMillis(500))
                      .alias("premise: the start has looked for the stop owed and waits for that stop to finish stopping")
                      .until(() -> startLetGo.get() && starting.getState() == Thread.State.WAITING && queue.stopsWithoutLock() == 1);
            assertThat(queue.isStarted()).as("the start, finding the stop owed, did not mark the queue started").isFalse();

            releaseTheStop.countDown();
            stopping.join(20_000);
            starting.join(20_000);

            assertThat(stopping.isAlive()).as("the stop returned").isFalse();
            assertThat(starting.isAlive()).as("the start returned").isFalse();
            assertThat(queue.isStarted()).as("the start left the queue not started").isFalse();
            assertThat(queue.stopsWithoutLock()).as("no stop is left counted as stopping without the lock").isZero();
            assertThat(subscription.isStarted()).as("neither lane is started").isFalse();
            Awaitility.await().during(Duration.ofSeconds(1)).atMost(Duration.ofSeconds(10)).until(
                    () -> !instanceRegistered(instanceId) && unitsOwnedBy("unordered", instanceId) == 0 && unitsOwnedBy("ordered", instanceId) == 0);
        } finally {
            releaseTheStart.countDown();
            releaseTheStop.countDown();
            queue.close();
            queueLogger.detachAppender(holdingAppender);
            // What the queue's close() did not reach, so a failure leaves nothing leasing into the next test.
            if (subscription != null) {
                subscription.stop();
            }
        }
    }

    /**
     * A {@code MessageQueue.start()} called by an observer told of a release, on the thread of a holder stopping every
     * consumer - a {@code consume()} doing the stop it owes, after an observer stopped the queue on its first acquisition,
     * or a plain {@code stop()} - does nothing: the queue ends not started, neither lane is started, no lease or
     * membership row names the instance, and a later {@code start()} starts both lanes again. The start found no stop
     * owed - the holder was doing the stop it owed, or was a stop - so it marked the queue started and started every
     * consumer while that holder was still stopping them; the holder then stopped the ordered lane, after the
     * observer's, and let go, leaving the queue marked started with the unordered lane running.
     */
    @ParameterizedTest
    @ValueSource(strings = {"owed-stop", "stop"})
    void a_message_queue_start_called_by_an_observer_while_the_queue_is_being_stopped_does_not_mark_the_queue_started(String stoppedBy)
            throws Exception {
        var instanceId        = "spi-start-inside-" + stoppedBy;
        var stopOnAcquire     = new AtomicBoolean("owed-stop".equals(stoppedBy));
        var stoppingThread    = new AtomicReference<Thread>();
        var startOnRelease    = new AtomicBoolean();
        var startReturned     = new AtomicBoolean();
        var startedAfterStart = new AtomicReference<Boolean>();
        var subscription      = new AtomicReference<Subscription>();
        try (var queue = new PostgresqlMessageQueue(dataSource, QUEUE_ID, SHARD_COUNT, instanceId)) {
            queue.addObserver(new QueueObserver() {
                @Override
                public void shardOwnershipChanged(int shard, boolean acquired) {
                    if (acquired && stopOnAcquire.compareAndSet(true, false)) {
                        stoppingThread.set(Thread.currentThread());
                        startOnRelease.set(true);
                        queue.stop();
                    } else if (!acquired && Thread.currentThread().equals(stoppingThread.get()) && startOnRelease.compareAndSet(true, false)) {
                        queue.start();
                        startReturned.set(true);
                        startedAfterStart.set(queue.isStarted());
                    }
                }
            });
            try {
                subscription.set(consumeUnchecked(queue));
                if ("stop".equals(stoppedBy)) {
                    Awaitility.await().atMost(Duration.ofSeconds(20))
                              .alias("premise: both lanes hold units before the queue is stopped")
                              .until(() -> subscription.get().unorderedShardsHeld() > 0 && subscription.get().orderedUnitsHeld() > 0);
                    stoppingThread.set(Thread.currentThread());
                    startOnRelease.set(true);
                    queue.stop();
                }

                assertThat(startReturned).as("premise: the observer's start was called inside the %s, told of a release, and returned", stoppedBy).isTrue();
                assertThat(startedAfterStart.get()).as("the start, called while the queue was being stopped, left it not started").isFalse();
                assertThat(queue.isStarted()).as("the queue ends not started").isFalse();
                assertThat(subscription.get().isStarted()).as("neither lane is started").isFalse();
                Awaitility.await().during(Duration.ofSeconds(1)).atMost(Duration.ofSeconds(10)).until(
                        () -> !instanceRegistered(instanceId) && unitsOwnedBy("unordered", instanceId) == 0 && unitsOwnedBy("ordered", instanceId) == 0);

                queue.start();

                assertThat(queue.isStarted()).as("a start after the stop starts the queue again").isTrue();
                Awaitility.await().atMost(Duration.ofSeconds(20))
                          .alias("both lanes hold units again")
                          .until(() -> subscription.get().unorderedShardsHeld() > 0 && subscription.get().orderedUnitsHeld() > 0);
            } finally {
                // What the queue's stop did not reach, so a failure leaves nothing leasing into the next test.
                if (subscription.get() != null) {
                    subscription.get().stop();
                }
            }
        }
    }

    /**
     * A {@code consume()}, a {@code Subscription.start()} or a {@code MessageQueue.start()} called by an observer on the
     * thread of a holder stopping every consumer starts nothing and logs one WARN saying so, naming the queue, the
     * subscription's instance id where there is one, and what holds the lock. The holder is a {@code consume()} doing the
     * stop it owes, after an observer stopped the queue on its first acquisition, told of a release ({@code owed-stop});
     * that {@code consume()} still owing the stop, told of its next acquisition ({@code owing-a-stop}); or a plain
     * {@code stop()} told of a release ({@code stop}). The {@code consume()} still returns a subscription, registered and
     * not started, which the queue's {@code start()} starts once the stop has returned. Nothing said so: that subscription
     * read {@code isStarted()} false, with no exception and no log line, and the observer's author could not tell it from
     * one that runs.
     * <p>
     * A {@code Subscription.start()} of a second subscription with both lanes running, called there
     * ({@code running-subscription}), warns too: the stop reaches that subscription after the observer's call and stops
     * it, so it ends stopped, which a start from any other thread, waiting for the stop, would not have left it. It used to
     * return with no WARN, as though it would have started nothing from any thread.
     */
    @ParameterizedTest
    @CsvSource({"owed-stop, consume", "owing-a-stop, consume", "stop, consume", "stop, subscription", "stop, running-subscription", "owed-stop, queue",
                "owing-a-stop, queue", "stop, queue"})
    void a_start_called_by_an_observer_while_the_queue_is_being_stopped_starts_nothing_and_says_so(String stoppedBy, String calledBy) throws Exception {
        var instanceId     = "spi-" + calledBy + "-on-release-" + stoppedBy;
        var stopOnAcquire  = new AtomicBoolean(!"stop".equals(stoppedBy));
        // The call is made from the next acquisition after the observer's stop(), on the same thread, for owing-a-stop:
        // the unordered lane's start goes on telling the units it adopted.
        var callAcquiring  = "owing-a-stop".equals(stoppedBy);
        var stoppingThread = new AtomicReference<Thread>();
        var callPending    = new AtomicBoolean();
        var callReturned   = new AtomicBoolean();
        var startedByCall  = new AtomicReference<Boolean>();
        var subscription   = new AtomicReference<Subscription>();
        var nested         = new AtomicReference<Subscription>();
        // running-subscription: a second subscription, registered after the first, so the stop reaches it after the first's releases.
        var other          = new AtomicReference<Subscription>();
        var otherRunning   = new AtomicReference<Boolean>();
        var logged         = new ListAppender<ILoggingEvent>();
        var queueLogger    = (ch.qos.logback.classic.Logger) LoggerFactory.getLogger(PostgresqlMessageQueue.class);
        logged.start();
        queueLogger.addAppender(logged);
        try (var queue = new PostgresqlMessageQueue(dataSource, QUEUE_ID, SHARD_COUNT, instanceId)) {
            queue.addObserver(new QueueObserver() {
                @Override
                public void shardOwnershipChanged(int shard, boolean acquired) {
                    if (acquired && stopOnAcquire.compareAndSet(true, false)) {
                        stoppingThread.set(Thread.currentThread());
                        callPending.set(true);
                        queue.stop();
                    } else if (acquired == callAcquiring && Thread.currentThread().equals(stoppingThread.get()) && callPending.compareAndSet(true, false)) {
                        switch (calledBy) {
                            case "consume" -> {
                                nested.set(consumeUnchecked(queue));
                                startedByCall.set(nested.get().isStarted());
                            }
                            // Its ordered lane is still running here, stopped after this release: whether the start
                            // started anything shows once the stop has returned.
                            case "subscription" -> subscription.get().start();
                            case "running-subscription" -> {
                                otherRunning.set(other.get().isStarted());
                                other.get().start();
                            }
                            default -> {
                                queue.start();
                                startedByCall.set(queue.isStarted());
                            }
                        }
                        callReturned.set(true);
                    }
                }
            });
            try {
                subscription.set(consumeUnchecked(queue));
                if ("stop".equals(stoppedBy)) {
                    Awaitility.await().atMost(Duration.ofSeconds(20))
                              .alias("premise: both lanes hold units before the queue is stopped")
                              .until(() -> subscription.get().unorderedShardsHeld() > 0 && subscription.get().orderedUnitsHeld() > 0);
                    if ("running-subscription".equals(calledBy)) {
                        other.set(consumeUnchecked(queue));
                    }
                    stoppingThread.set(Thread.currentThread());
                    callPending.set(true);
                    queue.stop();
                }

                assertThat(callReturned).as("premise: the observer's %s was called inside the %s, told of %s, and returned", calledBy, stoppedBy,
                                            callAcquiring ? "an acquisition" : "a release")
                                        .isTrue();
                if ("consume".equals(calledBy) || "queue".equals(calledBy)) {
                    assertThat(startedByCall.get()).as("the observer's %s started nothing", calledBy).isFalse();
                }
                assertThat(queue.isStarted()).as("the queue ends not started").isFalse();
                assertThat(subscription.get().isStarted()).as("neither lane of the subscription is started").isFalse();
                if ("consume".equals(calledBy)) {
                    assertThat(nested.get().isStarted()).as("the observer's consume() returned a subscription it did not start").isFalse();
                }
                if ("running-subscription".equals(calledBy)) {
                    assertThat(otherRunning.get()).as("premise: the second subscription was running when the observer started it").isTrue();
                    assertThat(other.get().isStarted()).as("the stop reached the second subscription after the observer's start() and stopped it").isFalse();
                }
                var holderNamed = switch (stoppedBy) {
                    case "stop" -> "a stop() of the queue";
                    case "owing-a-stop" -> "owing a stop";
                    default -> "doing a stop it owed";
                };
                var warned = switch (calledBy) {
                    case "consume" -> "registered a subscription of queue " + QUEUE_ID + " under instance id '" + instanceId + "-1' WITHOUT starting it";
                    case "subscription" -> "started neither lane of the subscription of queue " + QUEUE_ID + " under instance id '" + instanceId + "'";
                    case "running-subscription" -> "started neither lane of the subscription of queue " + QUEUE_ID + " under instance id '" + instanceId + "-1'";
                    default -> "did not start queue " + QUEUE_ID + ":";
                };
                assertThat(logged.list).filteredOn(event -> event.getFormattedMessage().contains(warned))
                                       .as("the %s that started nothing is logged once, at WARN, naming %s", calledBy, holderNamed)
                                       .singleElement()
                                       .satisfies(event -> {
                                           assertThat(event.getLevel()).isEqualTo(ch.qos.logback.classic.Level.WARN);
                                           assertThat(event.getFormattedMessage()).contains(holderNamed).contains("by a QueueObserver");
                                       });

                queue.start();

                assertThat(queue.isStarted()).as("a start after the stop starts the queue again").isTrue();
                assertThat(subscription.get().isStarted()).as("it starts the subscription again").isTrue();
                if ("consume".equals(calledBy)) {
                    assertThat(nested.get().isStarted()).as("it starts the subscription the observer's consume() registered").isTrue();
                }
                if ("running-subscription".equals(calledBy)) {
                    assertThat(other.get().isStarted()).as("it starts the second subscription again").isTrue();
                }
            } finally {
                // What the queue's stop did not reach, so a failure leaves nothing leasing into the next test.
                if (nested.get() != null) {
                    nested.get().unsubscribe();
                }
                if (other.get() != null) {
                    other.get().unsubscribe();
                }
                if (subscription.get() != null) {
                    subscription.get().stop();
                }
                queueLogger.detachAppender(logged);
            }
        }
    }

    /**
     * A {@code consume()} on a queue marked started, failing because the observer throws for its unordered lane's first
     * acquisition, stops that lane and tells the observer of each release on its own thread, where nothing starts. A
     * {@code MessageQueue.start()} called there returns at once with no WARN, as from any thread: the queue is marked
     * started and stays so, as a failed {@code consume()} leaves it. A WARN saying the queue stayed not started, while
     * {@code isStarted()} read true, was a false alarm. (Inside a failed queue {@code start()} it warns: see
     * {@link #a_start_called_by_an_observer_while_a_failed_message_queue_start_is_undone_starts_nothing_and_says_so}.) A {@code consume()} there, or a {@code Subscription.start()} of a stopped subscription, starts nothing and
     * logs one WARN naming the undo; what it left unstarted starts once the failed {@code consume()} has returned.
     */
    @ParameterizedTest
    @ValueSource(strings = {"queue", "consume", "subscription"})
    void a_start_called_by_an_observer_while_a_failed_consume_on_a_started_queue_is_undone_warns_only_when_it_starts_nothing(String calledBy) throws Exception {
        var instanceId    = "spi-" + calledBy + "-inside-consume-undo";
        var failOnAcquire = new AtomicBoolean();
        var undoingThread = new AtomicReference<Thread>();
        var callPending   = new AtomicBoolean();
        var callReturned  = new AtomicBoolean();
        var startedByCall = new AtomicReference<Boolean>();
        var first         = new AtomicReference<Subscription>();
        var nested        = new AtomicReference<Subscription>();
        var logged        = new ListAppender<ILoggingEvent>();
        var queueLogger   = (ch.qos.logback.classic.Logger) LoggerFactory.getLogger(PostgresqlMessageQueue.class);
        logged.start();
        queueLogger.addAppender(logged);
        try (var queue = new PostgresqlMessageQueue(dataSource, QUEUE_ID, SHARD_COUNT, instanceId)) {
            queue.addObserver(new QueueObserver() {
                @Override
                public void shardOwnershipChanged(int shard, boolean acquired) {
                    if (acquired && failOnAcquire.compareAndSet(true, false)) {
                        undoingThread.set(Thread.currentThread());
                        callPending.set(true);
                        throw new IllegalStateException("simulated observer failure acquiring unit " + shard);
                    } else if (!acquired && Thread.currentThread().equals(undoingThread.get()) && callPending.compareAndSet(true, false)) {
                        switch (calledBy) {
                            case "consume" -> {
                                nested.set(consumeUnchecked(queue));
                                startedByCall.set(nested.get().isStarted());
                            }
                            case "subscription" -> {
                                first.get().start();
                                startedByCall.set(first.get().isStarted());
                            }
                            default -> {
                                queue.start();
                                startedByCall.set(queue.isStarted());
                            }
                        }
                        callReturned.set(true);
                    }
                }
            });
            try {
                first.set(consumeUnchecked(queue));
                Awaitility.await().atMost(Duration.ofSeconds(20))
                          .alias("premise: both lanes hold units before the subscription is stopped")
                          .until(() -> first.get().unorderedShardsHeld() > 0 && first.get().orderedUnitsHeld() > 0);
                // Its units are released, for the failing consume() to adopt on its own thread; the queue stays marked started.
                first.get().stop();
                assertThat(queue.isStarted()).as("premise: the queue is marked started").isTrue();
                assertThat(first.get().isStarted()).as("premise: the first subscription is stopped").isFalse();
                failOnAcquire.set(true);

                var thrown = catchThrowable(() -> consumeUnchecked(queue));

                assertThat(thrown).as("premise: the observer's failure escaped the consume()")
                                  .isInstanceOf(IllegalStateException.class)
                                  .hasMessageStartingWith("simulated observer failure acquiring");
                assertThat(callReturned).as("premise: the observer's %s was called while the failed consume() stopped its lane, and returned", calledBy)
                                        .isTrue();
                assertThat(queue.isStarted()).as("the queue stays marked started").isTrue();
                if ("queue".equals(calledBy)) {
                    assertThat(startedByCall.get()).as("the observer's start() found the queue marked started").isTrue();
                    assertThat(logged.list).as("a start() of a queue already marked started says nothing")
                                           .noneMatch(event -> event.getFormattedMessage().contains("did not start queue"));
                    return;
                }
                assertThat(startedByCall.get()).as("the observer's %s started nothing", calledBy).isFalse();
                var warned = "consume".equals(calledBy)
                             ? "WITHOUT starting it"
                             : "started neither lane of the subscription of queue " + QUEUE_ID + " under instance id '" + instanceId + "'";
                assertThat(logged.list).filteredOn(event -> event.getFormattedMessage().contains(warned))
                                       .as("the %s that started nothing is logged once, at WARN, naming the undo", calledBy)
                                       .singleElement()
                                       .satisfies(event -> {
                                           assertThat(event.getLevel()).isEqualTo(ch.qos.logback.classic.Level.WARN);
                                           assertThat(event.getFormattedMessage()).contains("a consume() of the queue, or a start() of one of its "
                                                                                            + "subscriptions, stopping the consumers its failed start started")
                                                                                  .contains("by a QueueObserver");
                                       });

                var unstarted = "consume".equals(calledBy) ? nested.get() : first.get();
                unstarted.start();

                assertThat(unstarted.isStarted()).as("a start once the failed consume() has returned starts it").isTrue();
            } finally {
                failOnAcquire.set(false);
                // Beside the queue's close(), which stops both, so a failure leaves nothing leasing into the next test.
                if (nested.get() != null) {
                    nested.get().unsubscribe();
                }
                if (first.get() != null) {
                    first.get().stop();
                }
                queueLogger.detachAppender(logged);
            }
        }
    }

    /**
     * A {@code MessageQueue.start()} after a {@code stop()}, failing because the observer throws for the unordered lane's
     * first acquisition, has marked the queue started; it stops that lane and tells the observer of each release on its
     * own thread, where nothing starts, and only then marks the queue not started as the failure escapes. A
     * {@code MessageQueue.start()} called there finds the queue marked started, starts nothing, and logs one WARN naming
     * the failing start: the queue ends not started. It used to return at once with no WARN, as on a queue a failed
     * {@code consume()} leaves started, though here a start from another thread, waiting for the lock, would have started
     * the queue once the failed start had unmarked it. A {@code Subscription.start()} there starts neither lane and logs
     * one WARN naming the undo. Either is started once the failed start has returned.
     */
    @ParameterizedTest
    @ValueSource(strings = {"queue", "subscription"})
    void a_start_called_by_an_observer_while_a_failed_message_queue_start_is_undone_starts_nothing_and_says_so(String calledBy) throws Exception {
        var instanceId    = "spi-" + calledBy + "-inside-queue-start-undo";
        var failOnAcquire = new AtomicBoolean();
        var undoingThread = new AtomicReference<Thread>();
        var callPending   = new AtomicBoolean();
        var callReturned  = new AtomicBoolean();
        var markedAtCall  = new AtomicReference<Boolean>();
        var startedByCall = new AtomicReference<Boolean>();
        var subscription  = new AtomicReference<Subscription>();
        var logged        = new ListAppender<ILoggingEvent>();
        var queueLogger   = (ch.qos.logback.classic.Logger) LoggerFactory.getLogger(PostgresqlMessageQueue.class);
        logged.start();
        queueLogger.addAppender(logged);
        try (var queue = new PostgresqlMessageQueue(dataSource, QUEUE_ID, SHARD_COUNT, instanceId)) {
            queue.addObserver(new QueueObserver() {
                @Override
                public void shardOwnershipChanged(int shard, boolean acquired) {
                    if (acquired && failOnAcquire.compareAndSet(true, false)) {
                        undoingThread.set(Thread.currentThread());
                        callPending.set(true);
                        throw new IllegalStateException("simulated observer failure acquiring unit " + shard);
                    } else if (!acquired && Thread.currentThread().equals(undoingThread.get()) && callPending.compareAndSet(true, false)) {
                        markedAtCall.set(queue.isStarted());
                        if ("subscription".equals(calledBy)) {
                            subscription.get().start();
                            startedByCall.set(subscription.get().isStarted());
                        } else {
                            queue.start();
                        }
                        callReturned.set(true);
                    }
                }
            });
            try {
                subscription.set(consumeUnchecked(queue));
                Awaitility.await().atMost(Duration.ofSeconds(20))
                          .alias("premise: both lanes hold units before the queue is stopped")
                          .until(() -> subscription.get().unorderedShardsHeld() > 0 && subscription.get().orderedUnitsHeld() > 0);
                // Its units are released, for the failing start() to adopt again on its own thread.
                queue.stop();
                assertThat(queue.isStarted()).as("premise: the queue is not started").isFalse();
                assertThat(subscription.get().isStarted()).as("premise: the subscription is stopped").isFalse();
                failOnAcquire.set(true);

                var thrown = catchThrowable(queue::start);

                assertThat(thrown).as("premise: the observer's failure escaped the queue's start()")
                                  .isInstanceOf(IllegalStateException.class)
                                  .hasMessageStartingWith("simulated observer failure acquiring");
                assertThat(callReturned).as("premise: the observer's %s was called while the failed start() stopped its lane, and returned", calledBy)
                                        .isTrue();
                assertThat(markedAtCall.get()).as("premise: the queue read started at the call, marked by the failing start()").isTrue();
                assertThat(queue.isStarted()).as("the queue ends not started").isFalse();
                assertThat(subscription.get().isStarted()).as("neither lane of the subscription is started").isFalse();
                var warned = "subscription".equals(calledBy)
                             ? "started neither lane of the subscription of queue " + QUEUE_ID + " under instance id '" + instanceId + "'"
                             : "did not start queue " + QUEUE_ID + ":";
                // Both WARNs name the queue start() whose failure is undone, the Subscription.start()'s through the undoing holder.
                var holderNamed = "a start() of the queue stopping the consumers its failed start started";
                if ("subscription".equals(calledBy)) {
                    assertThat(startedByCall.get()).as("the observer's Subscription.start() started neither lane").isFalse();
                }
                assertThat(logged.list).filteredOn(event -> event.getFormattedMessage().contains(warned))
                                       .as("the %s that started nothing is logged once, at WARN, naming the failing start", calledBy)
                                       .singleElement()
                                       .satisfies(event -> {
                                           assertThat(event.getLevel()).isEqualTo(ch.qos.logback.classic.Level.WARN);
                                           assertThat(event.getFormattedMessage()).contains(holderNamed).contains("by a QueueObserver");
                                       });

                queue.start();

                assertThat(queue.isStarted()).as("a start once the failed start() has returned starts the queue").isTrue();
                assertThat(subscription.get().isStarted()).as("it starts the subscription again").isTrue();
            } finally {
                failOnAcquire.set(false);
                // Beside the queue's close(), which stops it, so a failure leaves nothing leasing into the next test.
                if (subscription.get() != null) {
                    subscription.get().stop();
                }
                queueLogger.detachAppender(logged);
            }
        }
    }

    /**
     * A start that fails inside another start on the same thread - an observer told of the outer call's first acquisition
     * makes it, and the observer throws for the first acquisition the inner one tells - is undone by the INNER call, and a
     * {@code MessageQueue.start()} the observer calls from that undo's first release is judged by the inner call:
     * <ul>
     *     <li>{@code consume} and {@code subscription}: the outer call is a {@code consume()}, or a
     *         {@code Subscription.start()} of the second of two subscriptions, on a queue a {@code stop()} left not
     *         started, and the inner call is the queue's {@code start()}. It marked the queue started and unmarks it as it
     *         fails, so the {@code start()} from its undo starts nothing, logs one WARN naming a failing {@code start()} of
     *         the queue, and the queue ends not started. It returned with no WARN, the queue still ending not started:
     *         whether a queue {@code start()} was undoing was read from the call that took the lifecycle lock first, here
     *         the {@code consume()} or {@code Subscription.start()}.</li>
     *     <li>{@code queue}: the outer call is the queue's {@code start()} after a {@code stop()}, and the inner call a
     *         {@code consume()}. Its undo leaves the queue as it found it, marked started, so the {@code start()} from it
     *         returns at once with no WARN; the outer start, the observer swallowing the inner failure, starts the queue.
     *         It logged the WARN about a failing queue {@code start()}, though the queue ended started.</li>
     * </ul>
     */
    @ParameterizedTest
    @ValueSource(strings = {"consume", "subscription", "queue"})
    void a_message_queue_start_called_from_the_undo_of_a_start_failing_inside_another_start_is_judged_by_the_failing_start(String outerCall)
            throws Exception {
        var instanceId     = "spi-nested-undo-in-" + outerCall;
        var phase          = new AtomicReference<String>("idle");
        var outerThread    = new AtomicReference<Thread>();
        var innerFailure   = new AtomicReference<Throwable>();
        var markedAtCall   = new AtomicReference<Boolean>();
        var callReturned   = new AtomicBoolean();
        var subscriptions  = new CopyOnWriteArrayList<Subscription>();
        var logged         = new ListAppender<ILoggingEvent>();
        var queueLogger    = (ch.qos.logback.classic.Logger) LoggerFactory.getLogger(PostgresqlMessageQueue.class);
        logged.start();
        queueLogger.addAppender(logged);
        try (var queue = new PostgresqlMessageQueue(dataSource, QUEUE_ID, SHARD_COUNT, instanceId)) {
            queue.addObserver(new QueueObserver() {
                @Override
                public void shardOwnershipChanged(int shard, boolean acquired) {
                    if (!Thread.currentThread().equals(outerThread.get())) {
                        return;
                    }
                    if (acquired && phase.compareAndSet("armed", "inner")) {
                        // The outer call's first acquisition: the inner call, whose failure the observer swallows.
                        try {
                            if ("queue".equals(outerCall)) {
                                subscriptions.add(consumeUnchecked(queue));
                            } else {
                                queue.start();
                            }
                        } catch (RuntimeException e) {
                            innerFailure.set(e);
                        }
                    } else if (acquired && phase.compareAndSet("inner", "failing")) {
                        throw new IllegalStateException("simulated observer failure acquiring unit " + shard);
                    } else if (!acquired && phase.compareAndSet("failing", "called")) {
                        markedAtCall.set(queue.isStarted());
                        queue.start();
                        callReturned.set(true);
                    }
                }
            });
            try {
                subscriptions.add(consumeUnchecked(queue));
                Awaitility.await().atMost(Duration.ofSeconds(20))
                          .alias("premise: both lanes hold units before the queue is stopped")
                          .until(() -> subscriptions.getFirst().unorderedShardsHeld() > 0 && subscriptions.getFirst().orderedUnitsHeld() > 0);
                if ("subscription".equals(outerCall)) {
                    // Registered after the first, so a queue start() goes over the first's lanes before this one's.
                    subscriptions.add(consumeUnchecked(queue));
                }
                queue.stop();
                assertThat(queue.isStarted()).as("premise: the queue is not started").isFalse();
                outerThread.set(Thread.currentThread());
                phase.set("armed");

                // The queue start() going over the registered subscriptions inside a consume() or a Subscription.start()
                // fails on the first's lanes, before reaching the second's, which the Subscription.start() is starting.
                switch (outerCall) {
                    case "consume" -> subscriptions.add(consumeUnchecked(queue));
                    case "subscription" -> subscriptions.get(1).start();
                    default -> queue.start();
                }

                assertThat(innerFailure.get()).as("premise: the inner call failed on the observer's failure")
                                              .isInstanceOf(IllegalStateException.class)
                                              .hasMessageStartingWith("simulated observer failure acquiring");
                assertThat(callReturned).as("premise: the observer's start() was called from the inner call's undo, and returned").isTrue();
                assertThat(markedAtCall.get()).as("premise: the queue read started at the call").isTrue();
                if ("queue".equals(outerCall)) {
                    assertThat(logged.list).as("a start() inside the undo of a consume() on a queue marked started says nothing")
                                           .noneMatch(event -> event.getFormattedMessage().contains("did not start queue"));
                    assertThat(queue.isStarted()).as("the outer start() started the queue").isTrue();
                    return;
                }
                assertThat(logged.list).filteredOn(event -> event.getFormattedMessage().contains("did not start queue " + QUEUE_ID + ":"))
                                       .as("the start() from the undo of the failing queue start() is logged once, at WARN, naming it")
                                       .singleElement()
                                       .satisfies(event -> {
                                           assertThat(event.getLevel()).isEqualTo(ch.qos.logback.classic.Level.WARN);
                                           assertThat(event.getFormattedMessage()).contains("a start() of the queue stopping the consumers its failed start started")
                                                                                  .contains("by a QueueObserver");
                                       });
                assertThat(queue.isStarted()).as("the queue ends not started, as the WARN says").isFalse();

                queue.start();

                assertThat(queue.isStarted()).as("a start once the outer call has returned starts the queue").isTrue();
            } finally {
                phase.set("idle");
                // Beside the queue's close(), which stops them, so a failure leaves nothing leasing into the next test.
                for (var subscription : subscriptions) {
                    subscription.unsubscribe();
                }
                queueLogger.detachAppender(logged);
            }
        }
    }

    /**
     * A {@code Subscription.start()} after the queue's {@code stop()} leaves the queue not started, and the queue's
     * {@code close()} still stops both lanes again: no lease or membership row names the instance afterwards. The queue's
     * {@code close()} (its {@code stop()}) used to stop consumers only when the queue was marked started, so after such a
     * start it stopped nothing, and the lanes leased, heartbeated and dispatched until the process exited.
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

            assertThat(queue.isStarted()).as("the subscription's start left the queue not started").isFalse();
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
     * After the queue's {@code stop()}, a {@code Subscription.start()} of one of two subscriptions - or a {@code consume()}
     * of a third - starts its own lanes only and leaves the queue not started, so the queue's {@code start()} then starts
     * every subscription the stop stopped, and the queue's {@code close()} stops all of them again. Both used to mark the
     * queue started, and the queue's {@code start()}, finding it started, returned at once: the other subscription stayed
     * stopped, consuming nothing, while {@code isStarted()} read true.
     */
    @ParameterizedTest
    @ValueSource(strings = {"subscription", "consume"})
    void a_message_queue_start_after_one_subscription_started_on_its_own_starts_every_subscription(String startedBy) throws Exception {
        var instanceId    = "spi-start-after-" + startedBy;
        var queue         = new PostgresqlMessageQueue(dataSource, QUEUE_ID, SHARD_COUNT, instanceId);
        var subscriptions = new CopyOnWriteArrayList<Subscription>();
        try {
            var first  = consumeUnchecked(queue);
            subscriptions.add(first);
            var second = consumeUnchecked(queue);
            subscriptions.add(second);
            assertThat(first.isStarted() && second.isStarted()).as("premise: both subscriptions are started").isTrue();
            queue.stop();
            assertThat(first.isStarted() || second.isStarted()).as("premise: the queue's stop stopped both subscriptions").isFalse();

            Subscription startedOnItsOwn;
            if ("subscription".equals(startedBy)) {
                first.start();
                startedOnItsOwn = first;
            } else {
                startedOnItsOwn = consumeUnchecked(queue);
                subscriptions.add(startedOnItsOwn);
            }

            assertThat(startedOnItsOwn.isStarted()).as("premise: the %s started its own lanes", startedBy).isTrue();
            assertThat(second.isStarted()).as("premise: the %s left the other subscription stopped", startedBy).isFalse();
            assertThat(queue.isStarted()).as("the %s left the queue not started", startedBy).isFalse();

            queue.start();

            assertThat(subscriptions).as("the queue's start started every subscription").allMatch(Subscription::isStarted);
            assertThat(queue.isStarted()).as("the queue's start marked the queue started").isTrue();

            queue.close();

            assertThat(queue.isStarted()).as("the queue's close() left it not started").isFalse();
            assertThat(subscriptions).as("the queue's close() stopped every subscription").noneMatch(Subscription::isStarted);
        } finally {
            queue.close();
            // What the queue's close() did not reach, so a failure leaves nothing leasing into the next test.
            subscriptions.forEach(Subscription::stop);
        }
    }

    /**
     * The limit of {@code isStarted()} for a generic {@code Lifecycle} manager, pinned as it is: after the queue's
     * {@code stop()}, a {@code Subscription.start()} - or a {@code consume()} of a second subscription - runs its own lanes
     * while the queue's {@code isStarted()} reads false, so a manager that calls {@code stop()} only on a queue reporting
     * itself started, as {@code DefaultLifecycleManager} does at shutdown, leaves those lanes leasing and registered. A
     * {@code stop()} called whatever {@code isStarted()} reads, as {@code ShardOwnedQueueFactory.close()} does, stops them.
     * {@code MessageQueue}'s class javadoc and the module's docs name this state; a change that makes the gated stop reach
     * the lanes must update them too.
     */
    @ParameterizedTest
    @ValueSource(strings = {"subscription", "consume"})
    void a_stop_called_only_when_the_message_queue_reports_itself_started_leaves_a_subscription_started_on_its_own_running(String startedBy) throws Exception {
        var instanceId    = "spi-gated-stop-" + startedBy;
        var queue         = new PostgresqlMessageQueue(dataSource, QUEUE_ID, SHARD_COUNT, instanceId);
        var subscriptions = new CopyOnWriteArrayList<Subscription>();
        try {
            var first = consumeUnchecked(queue);
            subscriptions.add(first);
            Awaitility.await().atMost(Duration.ofSeconds(20))
                      .alias("premise: both lanes hold units before the queue is stopped")
                      .until(() -> first.unorderedShardsHeld() > 0 && first.orderedUnitsHeld() > 0);
            queue.stop();
            assertThat(first.isStarted()).as("premise: the queue's stop stopped the subscription").isFalse();

            Subscription startedOnItsOwn;
            String       runningInstanceId;
            if ("subscription".equals(startedBy)) {
                first.start();
                startedOnItsOwn = first;
                runningInstanceId = instanceId;
            } else {
                startedOnItsOwn = consumeUnchecked(queue);
                subscriptions.add(startedOnItsOwn);
                // The second subscription registered with the queue competes under an instance id of its own.
                runningInstanceId = instanceId + "-1";
            }
            Awaitility.await().atMost(Duration.ofSeconds(20))
                      .alias("premise: the " + startedBy + "'s lanes hold units")
                      .until(() -> startedOnItsOwn.unorderedShardsHeld() > 0 && startedOnItsOwn.orderedUnitsHeld() > 0);
            assertThat(queue.isStarted()).as("premise: the %s left the queue not started", startedBy).isFalse();

            // DefaultLifecycleManager.stopWithinShutdownTimeout: a bean that reports itself not started is not stopped.
            if (queue.isStarted()) {
                queue.stop();
            }

            Awaitility.await().during(Duration.ofSeconds(1)).atMost(Duration.ofSeconds(10))
                      .alias("the stop gated on isStarted() left the " + startedBy + "'s lanes running")
                      .until(() -> startedOnItsOwn.isStarted() && instanceRegistered(runningInstanceId)
                              && unitsOwnedBy("unordered", runningInstanceId) > 0 && unitsOwnedBy("ordered", runningInstanceId) > 0);

            // ShardOwnedQueueFactory.close(): stopped whatever isStarted() reads.
            queue.stop();

            assertThat(startedOnItsOwn.isStarted()).as("the queue's stop, not gated on isStarted(), stopped the %s's lanes", startedBy).isFalse();
            Awaitility.await().during(Duration.ofSeconds(1)).atMost(Duration.ofSeconds(10)).until(
                    () -> !instanceRegistered(runningInstanceId) && unitsOwnedBy("unordered", runningInstanceId) == 0
                            && unitsOwnedBy("ordered", runningInstanceId) == 0);
        } finally {
            queue.close();
            // What the queue's close() did not reach, so a failure leaves nothing leasing into the next test.
            subscriptions.forEach(Subscription::stop);
        }
    }

    /**
     * A {@code MessageQueue.start()} that fails on one subscription leaves running the other, which the caller started
     * on its own after the queue's {@code stop()} and which is consuming: registered before the failing subscription, the
     * start finds its lanes started and leaves them be; registered after it, the start never reaches it. The start stops
     * only the lanes it started, throws, and leaves the queue not started, while the subscription started on its own
     * stays started, keeps its units and still delivers. The start used to stop every registered consumer on such a
     * failure, so that subscription read not started and consumed nothing, and nothing started it again. A trigger fails
     * the membership row the failing subscription registers, under its own instance id, as a database failure would.
     */
    @ParameterizedTest
    @ValueSource(strings = {"first", "second"})
    void a_failed_message_queue_start_leaves_a_subscription_started_on_its_own_running(String startedOnItsOwn) throws Exception {
        var instanceId        = "spi-failed-start-keeps-" + startedOnItsOwn;
        var firstStarted      = "first".equals(startedOnItsOwn);
        var failingInstanceId = firstStarted ? instanceId + "-1" : instanceId;
        var runningInstanceId = firstStarted ? instanceId : instanceId + "-1";
        var delivered         = ConcurrentHashMap.<String>newKeySet();
        MessageHandler handler = (messageId, key, payload, payloadType) -> delivered.add(new String(payload, StandardCharsets.UTF_8));
        var queue         = new PostgresqlMessageQueue(dataSource, QUEUE_ID, SHARD_COUNT, instanceId);
        var subscriptions = new CopyOnWriteArrayList<Subscription>();
        try {
            var first = queue.consume(handler, ConsumerOptions.defaults());
            subscriptions.add(first);
            var second = queue.consume(handler, ConsumerOptions.defaults());
            subscriptions.add(second);
            queue.stop();
            assertThat(first.isStarted() || second.isStarted()).as("premise: the queue's stop stopped both subscriptions").isFalse();

            var running = firstStarted ? first : second;
            var failing = firstStarted ? second : first;
            running.start();
            Awaitility.await().atMost(Duration.ofSeconds(20))
                      .alias("premise: the subscription started on its own holds units in both lanes")
                      .until(() -> running.unorderedShardsHeld() > 0 && running.orderedUnitsHeld() > 0);
            assertThat(queue.isStarted()).as("premise: the subscription's start left the queue not started").isFalse();
            failTheRegistrationOf(failingInstanceId, true);

            var thrown = catchThrowable(queue::start);

            assertThat(thrown).as("premise: the queue's start failed registering the other subscription")
                              .isInstanceOf(IllegalStateException.class)
                              .hasStackTraceContaining("simulated failure registering instance " + failingInstanceId);
            assertThat(queue.isStarted()).as("the failed start left the queue not started").isFalse();
            assertThat(failing.isStarted()).as("the subscription the start failed on is not started").isFalse();
            assertThat(running.isStarted()).as("the subscription started on its own is still started").isTrue();
            assertThat(instanceRegistered(runningInstanceId)).as("its instance is still registered").isTrue();
            assertThat(running.unorderedShardsHeld() > 0 && running.orderedUnitsHeld() > 0).as("it still holds units in both lanes").isTrue();

            queue.enqueue(List.of(Message.of("after-the-failed-start".getBytes(StandardCharsets.UTF_8), 1)));
            Awaitility.await().atMost(Duration.ofSeconds(20))
                      .untilAsserted(() -> assertThat(delivered).as("it still delivers").contains("after-the-failed-start"));
        } finally {
            failTheRegistrationOf(failingInstanceId, false);
            queue.close();
            // What the queue's close() did not reach, so a failure leaves nothing leasing into the next test.
            subscriptions.forEach(Subscription::stop);
        }
    }

    /**
     * A {@code MessageQueue.start()} called by a {@code QueueObserver} told of a release while a failed
     * {@code Subscription.start()} stops the lane it started does nothing: the observer throws for the unordered lane's
     * first acquisition, which escapes that lane's start with the lane running, and the subscription's start stops it,
     * telling the observer of each release on the starting thread. The queue ends not started, neither lane is started,
     * no lease or membership row names the instance, and a later {@code start()} starts both lanes again. While it
     * stopped the lane, the subscription's start still read as a start under way, so the observer's {@code start()}
     * marked the queue started and started every consumer, the lane being stopped included, for a
     * {@code Subscription.start()} that then failed.
     */
    @Test
    void a_message_queue_start_called_by_an_observer_while_a_failed_subscription_start_is_undone_starts_nothing() throws Exception {
        var instanceId        = "spi-start-inside-undo";
        var failOnAcquire     = new AtomicBoolean();
        var undoingThread     = new AtomicReference<Thread>();
        var startOnRelease    = new AtomicBoolean();
        var startReturned     = new AtomicBoolean();
        var startedAfterStart = new AtomicReference<Boolean>();
        try (var queue = new PostgresqlMessageQueue(dataSource, QUEUE_ID, SHARD_COUNT, instanceId)) {
            queue.addObserver(new QueueObserver() {
                @Override
                public void shardOwnershipChanged(int shard, boolean acquired) {
                    if (acquired && failOnAcquire.compareAndSet(true, false)) {
                        undoingThread.set(Thread.currentThread());
                        startOnRelease.set(true);
                        throw new IllegalStateException("simulated observer failure acquiring unit " + shard);
                    } else if (!acquired && Thread.currentThread().equals(undoingThread.get()) && startOnRelease.compareAndSet(true, false)) {
                        queue.start();
                        startReturned.set(true);
                        startedAfterStart.set(queue.isStarted());
                    }
                }
            });
            var subscription = consumeUnchecked(queue);
            try {
                Awaitility.await().atMost(Duration.ofSeconds(20))
                          .alias("premise: both lanes hold units before the queue is stopped")
                          .until(() -> subscription.unorderedShardsHeld() > 0 && subscription.orderedUnitsHeld() > 0);
                queue.stop();
                assertThat(queue.isStarted() || subscription.isStarted()).as("premise: the queue and both lanes are stopped").isFalse();
                failOnAcquire.set(true);

                var thrown = catchThrowable(subscription::start);

                assertThat(thrown).as("premise: the observer's failure escaped the subscription's start")
                                  .isInstanceOf(IllegalStateException.class)
                                  .hasMessageStartingWith("simulated observer failure acquiring");
                assertThat(startReturned).as("premise: the observer's start was called while the lane was being stopped, and returned").isTrue();
                assertThat(startedAfterStart.get()).as("the observer's start left the queue not started").isFalse();
                assertThat(queue.isStarted()).as("the queue ends not started").isFalse();
                assertThat(subscription.isStarted()).as("neither lane is started").isFalse();
                Awaitility.await().during(Duration.ofSeconds(1)).atMost(Duration.ofSeconds(10)).until(
                        () -> !instanceRegistered(instanceId) && unitsOwnedBy("unordered", instanceId) == 0 && unitsOwnedBy("ordered", instanceId) == 0);

                queue.start();

                assertThat(queue.isStarted()).as("a start afterwards starts the queue").isTrue();
                Awaitility.await().atMost(Duration.ofSeconds(20))
                          .alias("both lanes hold units again")
                          .until(() -> subscription.unorderedShardsHeld() > 0 && subscription.orderedUnitsHeld() > 0);
            } finally {
                failOnAcquire.set(false);
                // What the queue's stop did not reach, so a failure leaves nothing leasing into the next test.
                subscription.stop();
            }
        }
    }

    /**
     * Make every registration of {@code instanceId} in the membership table - the first, and each heartbeat after it -
     * fail, as a database error would, or stop doing so.
     */
    private void failTheRegistrationOf(String instanceId, boolean fail) throws Exception {
        try (var connection = dataSource.getConnection(); var statement = connection.createStatement()) {
            if (fail) {
                statement.execute("CREATE OR REPLACE FUNCTION fail_instance_registration() RETURNS trigger AS $$ BEGIN "
                                          + "RAISE EXCEPTION 'simulated failure registering instance %', NEW.instance_id; "
                                          + "END $$ LANGUAGE plpgsql");
                statement.execute("CREATE TRIGGER fail_instance_registration BEFORE INSERT OR UPDATE ON " + ShardOwnedSchema.INSTANCE_TABLE
                                          + " FOR EACH ROW WHEN (NEW.instance_id = '" + instanceId + "')"
                                          + " EXECUTE FUNCTION fail_instance_registration()");
            } else {
                statement.execute("DROP TRIGGER IF EXISTS fail_instance_registration ON " + ShardOwnedSchema.INSTANCE_TABLE);
                statement.execute("DROP FUNCTION IF EXISTS fail_instance_registration()");
            }
        }
    }

    /**
     * A {@code consume()} - the queue's first - or a {@code Subscription.start()} after the queue's {@code stop()}
     * escaping with a critical error leaves the lane it left running for the queue's {@code close()} to stop: an observer
     * throws a {@link LinkageError} for the first acquisition, which escapes the unordered lane's start with that lane
     * running and is not undone. The {@code consume()} registers its pair and marks the queue started, as on success; the
     * {@code Subscription.start()} leaves the queue not started, as on success, and {@code close()} stops every registered
     * consumer either way. The {@code consume()} used to leave its pair unregistered, and {@code close()} stopped
     * consumers only when the queue was marked started, which neither left it: it stopped nothing, and the unordered lane
     * leased and heartbeated until the process exited.
     */
    @ParameterizedTest
    @ValueSource(strings = {"consume", "subscription"})
    void a_start_escaping_with_a_critical_error_leaves_the_lane_it_left_running_for_closing_the_message_queue_to_stop(String startedBy)
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
            assertThat(queue.isStarted()).as("the %s marked the queue as on success", startedBy).isEqualTo("consume".equals(startedBy));

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
        return consumeUnchecked(queue, (messageId, key, payload, payloadType) -> { });
    }

    private static Subscription consumeUnchecked(PostgresqlMessageQueue queue, MessageHandler handler) {
        try {
            return queue.consume(handler, ConsumerOptions.defaults());
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
     * one and before it schedules the heartbeat. The test holds the start there: its {@code AdoptionHook} blocks the
     * second adoption before the run's monitor is taken, so a stop on an interrupted thread - which does not wait for the
     * lifecycle lock - takes the run out at once, with one unit adopted. Released, the start finds its run ended, adopts
     * nothing more and does the stop's work.
     */
    @ParameterizedTest
    @ValueSource(strings = {"unordered", "ordered"})
    void a_start_doing_the_work_of_a_stop_that_did_not_wait_for_it_logs_the_observers_failure_instead_of_throwing_it(String lane) throws Exception {
        var settings      = settingsWithShedGrace(Duration.ofSeconds(1));
        var instanceId    = "owed-stop-observer-" + lane;
        var throwing      = new AtomicBoolean(true);
        var adoptions     = new CopyOnWriteArrayList<Integer>();
        var secondHeld    = new CountDownLatch(1);
        var letTheStartGo = new CountDownLatch(1);
        var logged        = new ListAppender<ILoggingEvent>();
        var queueLogger   = (ch.qos.logback.classic.Logger) LoggerFactory.getLogger(ShardOwnedQueue.class);
        logged.start();
        queueLogger.addAppender(logged);
        var queue = ShardOwnedQueue.builder().setDataSource(dataSource).setQueueId(QUEUE_ID).setShardCount(SHARD_COUNT)
                                   .setInstanceId(instanceId).setMetrics(new ShardOwnerMetrics(throwingForEveryRelease(throwing)))
                                   .setAdoptionHook(new ShardOwnedQueue.AdoptionHook() {
                                       @Override
                                       public void beforeAdopting(String adoptedLane, int unit) {
                                           adoptions.add(unit);
                                           if (adoptions.size() == 2) {
                                               secondHeld.countDown();
                                               awaitQuietly(letTheStartGo);
                                           }
                                       }
                                   })
                                   .build();
        var startFailure = new AtomicReference<Throwable>();
        var starting = new Thread(() -> {
            try {
                startConsuming(queue, lane, settings);
            } catch (Throwable failure) {
                startFailure.set(failure);
            }
        });
        try {
            starting.start();
            assertThat(secondHeld.await(20, TimeUnit.SECONDS)).as("premise: the start is held before its second adoption").isTrue();
            assertThat(queue.owners()).as("premise: the start has adopted one unit").hasSize(1);

            Thread.currentThread().interrupt();
            try {
                queue.stop();
            } finally {
                Thread.interrupted();
            }
            assertThat(queue.isStarted()).as("premise: the interrupted stop took the run out and returned").isFalse();
            assertThat(starting.isAlive()).as("premise: the start is still running, owing the stop's work").isTrue();

            letTheStartGo.countDown();
            starting.join(30_000);

            assertThat(starting.isAlive()).as("the start returned").isFalse();
            assertThat(startFailure.get()).as("the start, doing the stop's work, returned normally").isNull();
            assertThat(adoptions).as("premise: the start tried no unit after the held one, which found its run ended").hasSize(2);
            assertThat(queue.isStarted()).as("the start left the queue stopped").isFalse();
            Awaitility.await().atMost(Duration.ofSeconds(5)).until(() -> !instanceRegistered(instanceId) && unitsOwnedBy(lane, instanceId) == 0);
            assertThat(logged.list).filteredOn(event -> event.getFormattedMessage().contains("did the work of a stop()"))
                                   .as("the observer's failure is logged at WARN, with what it threw")
                                   .singleElement()
                                   .satisfies(event -> assertThat(event.getThrowableProxy().getMessage())
                                           .startsWith("simulated observer failure releasing unit"));
        } finally {
            letTheStartGo.countDown();
            starting.join(30_000);
            throwing.set(false);
            queue.stop();
            queueLogger.detachAppender(logged);
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
     * A {@link DataSource} that holds one thread of a {@code ShardOwnedQueue} whose name starts with a given prefix - the
     * budget thread of a stop ({@link #STOP_BUDGET_THREAD}), or the thread a kept unit is handed back on as its key leaves
     * the handler ({@link #KEPT_UNIT_HAND_BACK_THREAD}), or a thread the test starts under a name of its own - at two
     * points while told to: as it asks for a connection
     * ({@link #holdConnections}), and, on that connection, as it prepares a delete from the ordered lane - an owner's
     * acknowledgement flush, its batch already taken - until {@link #letAcknowledge}. Only the first such thread that asks
     * for a connection once told to hold is held, so a later one goes through. Every other thread, and the held one once
     * let through, get the delegate's connections as they are. Package-private for {@code ShardOwnedUnsubscribeIT}.
     */
    static final class FlushHoldingDataSource implements DataSource {
        static final  String                  STOP_BUDGET_THREAD         = "shard-queue-stop-";
        static final  String                  KEPT_UNIT_HAND_BACK_THREAD = "shard-queue-kept-unit-";
        private final DataSource              delegate;
        private final String                  threadNamePrefix;
        private final CountDownLatch          connecting                 = new CountDownLatch(1);
        private final CountDownLatch          connectionHeld             = new CountDownLatch(1);
        private final CountDownLatch          acknowledging              = new CountDownLatch(1);
        private final CountDownLatch          acknowledgeHeld            = new CountDownLatch(1);
        private final AtomicBoolean           holdConnections            = new AtomicBoolean();
        private final AtomicReference<Thread> heldThread                 = new AtomicReference<>();

        FlushHoldingDataSource(DataSource delegate, String threadNamePrefix) {
            this.delegate = delegate;
            this.threadNamePrefix = threadNamePrefix;
        }

        /**
         * Hold the first thread named with the prefix as it asks for a connection, until {@link #letConnect}.
         */
        void holdConnections() {
            holdConnections.set(true);
        }

        void letConnect() {
            holdConnections.set(false);
            connecting.countDown();
        }

        void letAcknowledge() {
            acknowledging.countDown();
        }

        /**
         * Whether the held thread has asked for a connection, and is held there, within {@code timeout}.
         */
        boolean awaitConnectionHeld(Duration timeout) throws InterruptedException {
            return connectionHeld.await(timeout.toMillis(), TimeUnit.MILLISECONDS);
        }

        /**
         * Whether the held thread has reached an ordered acknowledgement, and is held there, within {@code timeout}.
         */
        boolean awaitAcknowledgementHeld(Duration timeout) throws InterruptedException {
            return acknowledgeHeld.await(timeout.toMillis(), TimeUnit.MILLISECONDS);
        }

        /**
         * Whether this is the thread held here: the first one named with the prefix to ask for a connection once told to
         * hold.
         */
        private boolean onTheHeldThread() {
            var current = Thread.currentThread();
            if (!current.getName().startsWith(threadNamePrefix)) {
                return false;
            }
            if (holdConnections.get()) {
                heldThread.compareAndSet(null, current);
            }
            // Thread does not override equals(): this is the identity check, written the way the rest of this class compares threads.
            return current.equals(heldThread.get());
        }

        // Connection's own loader, not the thread's context loader: a proxy class must be defined in a loader that sees
        // its interface, which the context loader need not.
        @SuppressWarnings("PMD.UseProperClassLoader")
        private Connection holding(Connection connection) {
            if (!onTheHeldThread()) {
                return connection;
            }
            return (Connection) java.lang.reflect.Proxy.newProxyInstance(
                    Connection.class.getClassLoader(), new Class<?>[]{Connection.class}, (proxy, method, args) -> {
                        if ("prepareStatement".equals(method.getName()) && args != null && args[0] instanceof String sql
                                && sql.startsWith("DELETE FROM " + ShardOwnedSchema.ORDERED_TABLE)) {
                            acknowledgeHeld.countDown();
                            awaitQuietly(acknowledging);
                        }
                        try {
                            return method.invoke(connection, args);
                        } catch (java.lang.reflect.InvocationTargetException e) {
                            throw e.getCause();
                        }
                    });
        }

        @Override
        public Connection getConnection() throws SQLException {
            if (holdConnections.get() && onTheHeldThread()) {
                connectionHeld.countDown();
                awaitQuietly(connecting);
            }
            return holding(delegate.getConnection());
        }

        @Override
        public Connection getConnection(String username, String password) throws SQLException {
            if (holdConnections.get() && onTheHeldThread()) {
                connectionHeld.countDown();
                awaitQuietly(connecting);
            }
            return holding(delegate.getConnection(username, password));
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

    /**
     * Make every membership upsert of {@code instanceId} - a lane's start registering it, a heartbeat refreshing it - sleep
     * {@code sleepSeconds}, by a trigger. Drop it with {@link #dropSlowRegistration}.
     */
    private void slowTheRegistrationOf(String instanceId, double sleepSeconds) throws Exception {
        try (var connection = dataSource.getConnection(); var statement = connection.createStatement()) {
            statement.execute("CREATE OR REPLACE FUNCTION slow_registration() RETURNS trigger AS $$ BEGIN "
                                      + "PERFORM pg_sleep(" + sleepSeconds + "); RETURN NEW; END $$ LANGUAGE plpgsql");
            statement.execute("CREATE TRIGGER slow_registration BEFORE INSERT OR UPDATE ON " + ShardOwnedSchema.INSTANCE_TABLE
                                      + " FOR EACH ROW WHEN (NEW.instance_id = '" + instanceId + "')"
                                      + " EXECUTE FUNCTION slow_registration()");
        }
    }

    private void dropSlowRegistration() throws Exception {
        try (var connection = dataSource.getConnection(); var statement = connection.createStatement()) {
            statement.execute("DROP TRIGGER IF EXISTS slow_registration ON " + ShardOwnedSchema.INSTANCE_TABLE);
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
        return queryForInt(dataSource, "Counting the unordered lease rows held by " + instanceId,
                           "SELECT count(*) FROM " + ShardOwnedSchema.LEASE_TABLE + " WHERE queue_id = ? AND lane = 'unordered' AND owner = ?",
                           QUEUE_ID, instanceId);
    }

    private boolean aHeartbeatIsSleeping() throws Exception {
        return queryForInt(dataSource, "Counting the backends sleeping in pg_sleep",
                           "SELECT count(*) FROM pg_stat_activity WHERE wait_event = 'PgSleep'") > 0;
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
