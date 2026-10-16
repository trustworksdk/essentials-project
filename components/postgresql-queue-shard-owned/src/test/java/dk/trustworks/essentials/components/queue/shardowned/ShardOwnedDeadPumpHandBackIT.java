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

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.zaxxer.hikari.*;
import org.awaitility.Awaitility;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.*;
import org.slf4j.LoggerFactory;
import org.testcontainers.junit.jupiter.*;
import org.testcontainers.postgresql.PostgreSQLContainer;

import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * A pump that dies on a critical error hands its shards back to the cluster, through its queues' heartbeat.
 * <p>
 * They used to stay leased to this instance - an instance-owned lease has no expiry, and the instance kept heartbeating -
 * and were served by nobody until the {@code ShardRuntime} or the application was restarted, which is worse than a crash:
 * a crashed instance's shards are taken over a lease lifetime later. Now the pump ends its owners as it dies and the
 * queue releases their leases: an unordered shard at once, an ordered unit only once no key is in one of its handlers,
 * so no successor starts a key beside it. A re-acquired shard lands on a live pump; with none left the instance acquires
 * nothing, and its shards stay free for the cluster rather than bouncing between it and its dead pump.
 * <p>
 * The pump is killed the way a critical error kills it: an owner it serves throws a {@code LinkageError}, which the
 * per-owner guards rethrow. That owner belongs to no queue here, so the real queue's owners are only its bystanders - the
 * shape of a death the queue did not cause and cannot see coming.
 */
@Testcontainers(disabledWithoutDocker = true)
class ShardOwnedDeadPumpHandBackIT {

    private static final short QUEUE_ID    = 1;
    private static final int   SHARD_COUNT = 4;
    /**
     * A second queue, for the tests whose pump serves more than one ({@link Consumers#TWO_QUEUES}).
     */
    private static final short OTHER_QUEUE_ID = 2;
    /**
     * Every queue registered here, so {@link #failLeaseReleases} can count refusals per queue and unit.
     */
    private static final short[] QUEUE_IDS = {QUEUE_ID, OTHER_QUEUE_ID};
    /**
     * Not {@link #QUEUE_ID}: the owner that kills the pump is no shard of the queue under test.
     */
    private static final short KILLER_QUEUE_ID = 99;
    /**
     * Refusals of one unit's release that show the heartbeat has run a whole tick with that release failing.
     * <p>
     * The heartbeat runs one tick at a time and hands back before it rebalances. So a unit's second refused release comes
     * from a later tick than its first, after the first tick's rebalance, which is where a failed release used to let a unit
     * be taken again. A fixed sleep proved neither that the hand-back had been attempted nor, on a loaded build, that a
     * tick had run at all.
     */
    private static final int REFUSALS_SPANNING_A_TICK = 2;

    @Container
    static PostgreSQLContainer postgres = LabPostgres.create();

    private HikariDataSource dataSource;

    @BeforeEach
    void setUp() throws Exception {
        var config = new HikariConfig();
        config.setJdbcUrl(postgres.getJdbcUrl());
        config.setUsername(postgres.getUsername());
        config.setPassword(postgres.getPassword());
        config.setMaximumPoolSize(20);
        dataSource = new HikariDataSource(config);
        ShardOwnedSchema.recreate(dataSource);
        for (var queueId : QUEUE_IDS) {
            ShardOwnedSchema.registerQueue(dataSource, queueId, SHARD_COUNT);
        }
    }

    @AfterEach
    void tearDown() {
        if (dataSource != null) {
            dataSource.close();
        }
    }

    @Test
    void an_unordered_shard_on_a_dead_pump_is_handed_back_and_served_again_on_a_live_pump() throws Exception {
        var settings  = settings(2);
        var delivered = ConcurrentHashMap.<String>newKeySet();
        try (var runtime = new ShardRuntime(dataSource, settings)) {
            var queue = queue(runtime, "dead-pump-u");
            try {
                queue.startConsuming((messageId, payload, payloadType) -> delivered.add(new String(payload, StandardCharsets.UTF_8)),
                                     settings, SHARD_COUNT);
                assertThat(queue.shardsHeld()).as("premise: one instance holds every shard").isEqualTo(SHARD_COUNT);
                var doomed       = runtime.pumpFor(QUEUE_ID, "unordered", 0);
                var doomedShards = unitsOn(runtime, doomed, "unordered", SHARD_COUNT);
                var fencesBefore = fences("unordered", doomedShards);

                kill(doomed);

                Awaitility.await().atMost(Duration.ofSeconds(20)).untilAsserted(() -> {
                    for (var shard : doomedShards) {
                        assertThat(fenceOf("unordered", shard)).as("shard %d was handed back and taken again under a new fence", shard)
                                                               .isGreaterThan(fencesBefore.get(shard));
                        assertThat(ownerOf("unordered", shard)).isEqualTo("dead-pump-u");
                    }
                    assertThat(queue.metrics().shardsOnDeadPumps.get()).isZero();
                    assertThat(queue.shardsHeld()).isEqualTo(SHARD_COUNT);
                });
                for (var shard : doomedShards) {
                    assertThat(runtime.pumpFor(QUEUE_ID, "unordered", shard).isDead()).as("re-acquired on a live pump").isFalse();
                }

                var payloads = new ArrayList<byte[]>();
                for (var index = 0; index < SHARD_COUNT * 2; index++) {
                    payloads.add(("m" + index).getBytes(StandardCharsets.UTF_8));
                }
                queue.enqueue(payloads, 1);
                Awaitility.await().atMost(Duration.ofSeconds(20)).untilAsserted(
                        () -> assertThat(delivered).as("every shard delivers again, the dead pump's included").hasSize(SHARD_COUNT * 2));
            } finally {
                queue.stop();
            }
        }
    }

    /**
     * The ordered lane never hands a unit over with a key in a handler, a dead pump's included: the handlers run on the
     * runtime's shared executor, not on the pump, so one may outlive it. The unit is kept until that handler returns, and
     * handed back then; the dead pump's other units are handed back at once.
     * <p>
     * Nothing bounds that wait, so once it outlasts {@code shedGrace} the queue names the key holding it up in one WARN, as an
     * abandoned shed does - not on every heartbeat it goes on waiting. It used to log the wait at DEBUG alone, so the pump's
     * ERROR and a {@code shardsOnDeadPumps} that never fell were all an operator had, with nothing naming the key.
     */
    @Test
    void an_ordered_unit_on_a_dead_pump_is_handed_back_only_once_no_key_is_in_its_handler() throws Exception {
        var settings  = settings(2, Duration.ofMillis(300), Duration.ofSeconds(1));
        var entered   = new CountDownLatch(1);
        var release   = new CountDownLatch(1);
        var heldUnit  = new CompletableFuture<Integer>();
        var delivered = ConcurrentHashMap.<String>newKeySet();
        var logged    = captureQueueLog();
        try (var runtime = new ShardRuntime(dataSource, settings)) {
            var queue = queue(runtime, "dead-pump-o");
            try {
                queue.startConsumingOrdered((messageId, key, payload, payloadType) -> {
                    var text = new String(payload, StandardCharsets.UTF_8);
                    if ("HOLD-0".equals(text)) {
                        heldUnit.complete(messageId.shard());
                        entered.countDown();
                        awaitQuietly(release);
                    }
                    delivered.add(text);
                }, settings, SHARD_COUNT);
                queue.enqueueOrdered(List.of(new ShardOwnedStorage.OrderedPayload("HOLD", 0, "HOLD-0".getBytes(StandardCharsets.UTF_8), 1)));
                assertThat(entered.await(20, TimeUnit.SECONDS)).as("premise: the key is in its handler").isTrue();
                var unit         = heldUnit.get();
                var doomed       = runtime.pumpFor(QUEUE_ID, "ordered", unit);
                var doomedUnits  = unitsOn(runtime, doomed, "ordered", ShardOwnedSchema.ORDERED_UNITS);
                var idleUnits    = doomedUnits.stream().filter(candidate -> candidate != unit).toList();
                var fencesBefore = fences("ordered", doomedUnits);
                assertThat(idleUnits).as("premise: the dead pump serves other units too").isNotEmpty();

                kill(doomed);

                Awaitility.await().atMost(Duration.ofSeconds(20)).untilAsserted(() -> {
                    for (var idle : idleUnits) {
                        assertThat(fenceOf("ordered", idle)).as("idle unit %d was handed back and taken again", idle)
                                                            .isGreaterThan(fencesBefore.get(idle));
                    }
                });
                // Several heartbeats (a third of the 1 s lease each) with the key still in its handler.
                Thread.sleep(1_500);
                assertThat(fenceOf("ordered", unit)).as("the unit with a key in its handler is not handed over").isEqualTo(fencesBefore.get(unit));
                assertThat(ownerOf("ordered", unit)).isEqualTo("dead-pump-o");
                assertThat(queue.metrics().shardsOnDeadPumps.get()).as("it is still on the dead pump").isEqualTo(1);
                // 1.5 s against a 300 ms grace: past it, with several heartbeats after the first report.
                var stuckWarnings = warnings(logged, "to hand back ordered unit");
                assertThat(stuckWarnings).as("the wait past shedGrace is named once, not on every heartbeat").hasSize(1);
                assertThat(stuckWarnings.getFirst().getArgumentArray()[2]).as("the unit").isEqualTo(unit);
                assertThat(stuckWarnings.getFirst().getFormattedMessage()).contains("1 key(s) still in a handler [HOLD]")
                                                                          .contains("stays leased and unserved until the handler returns or the runtime is restarted");
                assertThat(warnings(logged, "whose pump had died")).as("the idle units were handed back with a live pump left")
                                                           .isNotEmpty()
                                                           .allMatch(event -> event.getFormattedMessage().contains("or a live pump here, takes them over"));

                release.countDown();

                Awaitility.await().atMost(Duration.ofSeconds(20)).untilAsserted(() -> {
                    assertThat(fenceOf("ordered", unit)).as("handed back once the handler returned").isGreaterThan(fencesBefore.get(unit));
                    assertThat(queue.metrics().shardsOnDeadPumps.get()).isZero();
                });
                assertThat(runtime.pumpFor(QUEUE_ID, "ordered", unit).isDead()).isFalse();
                queue.enqueueOrdered(List.of(new ShardOwnedStorage.OrderedPayload("HOLD", 1, "HOLD-1".getBytes(StandardCharsets.UTF_8), 1)));
                Awaitility.await().atMost(Duration.ofSeconds(20)).untilAsserted(
                        () -> assertThat(delivered).as("the key is served again on a live pump").contains("HOLD-1"));
            } finally {
                release.countDown();
                queue.stop();
            }
        } finally {
            releaseQueueLog(logged);
        }
    }

    /**
     * With no live pump left the shards are handed back and left free, not taken again: a shard taken now would land on
     * the dead pump, be ended and be handed back on every heartbeat. Each lane has its own acquire loop and its own guard.
     * <p>
     * The WARN reporting the hand-back says so. It used to promise that "another instance, or a live pump here" takes the
     * shards over, which with no live pump is half false, and in a single-instance deployment wholly so.
     */
    @ParameterizedTest
    @ValueSource(strings = {"unordered", "ordered"})
    void with_no_live_pump_left_the_shards_are_handed_back_and_not_taken_again(String lane) throws Exception {
        var settings = settings(1);
        var units    = "ordered".equals(lane) ? ShardOwnedSchema.ORDERED_UNITS : SHARD_COUNT;
        var logged   = captureQueueLog();
        try (var runtime = new ShardRuntime(dataSource, settings)) {
            var queue = queue(runtime, "dead-pump-all");
            try {
                if ("ordered".equals(lane)) {
                    queue.startConsumingOrdered((messageId, key, payload, payloadType) -> { }, settings, SHARD_COUNT);
                } else {
                    queue.startConsuming((messageId, payload, payloadType) -> { }, settings, SHARD_COUNT);
                }
                Awaitility.await().atMost(Duration.ofSeconds(20)).untilAsserted(() -> assertThat(queue.shardsHeld()).as("premise").isEqualTo(units));
                var acquiredBefore = queue.metrics().shardsAcquired.sum();

                kill(runtime.pumpFor(QUEUE_ID, lane, 0));

                Awaitility.await().atMost(Duration.ofSeconds(20)).untilAsserted(() -> {
                    assertThat(unitsOwnedBy(lane, "dead-pump-all")).as("every lease was handed back").isZero();
                    assertThat(queue.metrics().shardsOnDeadPumps.get()).isZero();
                });
                // Several heartbeats, each of which would have taken them again.
                Thread.sleep(1_500);
                assertThat(unitsOwnedBy(lane, "dead-pump-all")).as("nothing is taken back onto a dead pump").isZero();
                assertThat(queue.metrics().shardsAcquired.sum()).isEqualTo(acquiredBefore);
                assertThat(queue.shardsHeld()).isZero();
                assertThat(warnings(logged, "whose pump had died"))
                        .as("the hand-back WARN says no pump here takes them, and what it takes to get one back")
                        .isNotEmpty()
                        .allSatisfy(event -> assertThat(event.getFormattedMessage())
                                .contains("for other instances only")
                                .contains("acquires nothing until the runtime (or the application) is restarted")
                                .doesNotContain("a live pump here"));
            } finally {
                queue.stop();
            }
        } finally {
            releaseQueueLog(logged);
        }
    }

    /**
     * A queue started on a runtime whose every pump has died takes nothing, not even for a heartbeat: the start's acquire
     * loop is guarded like the rebalance's.
     * <p>
     * It used to lease every free unit (all 64 on the ordered lane), place each on the dead pump, which ended it on
     * arrival, report each as owned, and hand them all back a heartbeat later: the churn the rebalance's guard prevents,
     * plus a third of a lease in which no other instance could take them. The fences show it: acquiring bumps a unit's
     * fence and releasing does not, so an unchanged fence means the unit was never taken.
     */
    @ParameterizedTest
    @ValueSource(strings = {"unordered", "ordered"})
    void a_queue_started_on_a_runtime_whose_every_pump_has_died_takes_nothing(String lane) throws Exception {
        var settings = settings(1);
        var units    = "ordered".equals(lane) ? ShardOwnedSchema.ORDERED_UNITS : SHARD_COUNT;
        var all      = new ArrayList<Integer>();
        for (var unit = 0; unit < units; unit++) {
            all.add(unit);
        }
        try (var runtime = new ShardRuntime(dataSource, settings)) {
            kill(runtime.pumpFor(QUEUE_ID, lane, 0));
            assertThat(runtime.hasLivePump()).as("premise: every pump of the runtime has died").isFalse();
            var fencesBefore = fences(lane, all);
            var queue        = queue(runtime, "dead-runtime-start");
            try {
                if ("ordered".equals(lane)) {
                    queue.startConsumingOrdered((messageId, key, payload, payloadType) -> { }, settings, SHARD_COUNT);
                } else {
                    queue.startConsuming((messageId, payload, payloadType) -> { }, settings, SHARD_COUNT);
                }
                assertThat(queue.isStarted()).as("the queue still starts").isTrue();
                assertThat(membershipRows("dead-runtime-start")).as("the start registers the instance as a member").isOne();
                assertThat(queue.shardsHeld()).isZero();
                assertThat(queue.metrics().shardsAcquired.sum()).as("nothing acquired at start").isZero();
                assertThat(unitsOwnedBy(lane, "dead-runtime-start")).isZero();
                // Several heartbeats (a third of the 1 s lease each), each of which would have handed back what the start took.
                Thread.sleep(1_500);
                for (var unit : all) {
                    assertThat(fenceOf(lane, unit)).as("unit %d was never taken, not even briefly", unit).isEqualTo(fencesBefore.get(unit));
                }
                assertThat(unitsOwnedBy(lane, "dead-runtime-start")).as("every unit is free for the cluster").isZero();
                assertThat(queue.metrics().shardsAcquired.sum()).isZero();
                assertThat(queue.metrics().shardsReleased.sum()).as("so nothing was handed back either").isZero();
                assertThat(queue.metrics().shardsOnDeadPumps.get()).isZero();
                assertThat(membershipRows("dead-runtime-start")).as("and the instance keeps its membership row through the heartbeats").isOne();
            } finally {
                queue.stop();
            }
        }
    }

    /**
     * A hand-back whose release fails leaves the dead pump's units held, and the next heartbeat retries it: none is taken
     * again meanwhile, by the acquire loop on a live pump, not even once its key has left the handler.
     * <p>
     * The owner was ended before the release, and the ordered acquire loop counted only owners whose lease was held, so a
     * failed release made the unit look free to the same tick's rebalance; {@code acquireLeaseGrant} accepts a unit this
     * instance already owns and took it again on a live pump. The next hand-back then released that new owner's row, the
     * lease check marked the new owner taken while its handlers could still run a key, and the unit was taken a third
     * time: the same key in two handlers. The release is made to fail by a trigger, so it fails the way a database error
     * would, every time, until the trigger is dropped.
     */
    @Test
    void an_ordered_unit_whose_hand_back_fails_is_not_taken_again_until_its_release_succeeds() throws Exception {
        var settings  = settings(2);
        var entered   = new CountDownLatch(1);
        var release   = new CountDownLatch(1);
        var heldUnit  = new CompletableFuture<Integer>();
        var delivered = new CopyOnWriteArrayList<String>();
        try (var runtime = new ShardRuntime(dataSource, settings)) {
            var queue = queue(runtime, "dead-pump-fail");
            try {
                queue.startConsumingOrdered((messageId, key, payload, payloadType) -> {
                    var text = new String(payload, StandardCharsets.UTF_8);
                    if ("HOLD-0".equals(text)) {
                        heldUnit.complete(messageId.shard());
                        entered.countDown();
                        awaitQuietly(release);
                    }
                    delivered.add(text);
                }, settings, SHARD_COUNT);
                queue.enqueueOrdered(List.of(new ShardOwnedStorage.OrderedPayload("HOLD", 0, "HOLD-0".getBytes(StandardCharsets.UTF_8), 1)));
                assertThat(entered.await(20, TimeUnit.SECONDS)).as("premise: the key is in its handler").isTrue();
                var unit         = heldUnit.get();
                var doomed       = runtime.pumpFor(QUEUE_ID, "ordered", unit);
                var doomedUnits  = unitsOn(runtime, doomed, "ordered", ShardOwnedSchema.ORDERED_UNITS);
                var fencesBefore = fences("ordered", doomedUnits);
                assertThat(doomedUnits.size()).as("premise: the dead pump serves other units too").isGreaterThan(1);

                var idleUnits = doomedUnits.stream().filter(candidate -> candidate != unit).toList();

                failLeaseReleases("ordered", true);
                try {
                    kill(doomed);

                    awaitReleasesRefused(QUEUE_ID, idleUnits, "while the key is in its handler");
                    assertThat(releasesRefused(QUEUE_ID, unit)).as("premise: the unit with a key in its handler is not handed back yet").isZero();
                    assertNoneTakenAgain(queue, doomedUnits, fencesBefore, "while the key is in its handler");
                    assertThat(queue.metrics().shardsReleased.sum()).as("premise: no hand-back has succeeded").isZero();

                    release.countDown();
                    Awaitility.await().atMost(Duration.ofSeconds(10)).until(() -> delivered.contains("HOLD-0"));
                    // The held unit is now ready to be handed back too, and its release is refused like the others'.
                    awaitReleasesRefused(QUEUE_ID, List.of(unit), "once the key has left its handler");
                    assertNoneTakenAgain(queue, doomedUnits, fencesBefore, "once the key has left its handler");
                } finally {
                    failLeaseReleases("ordered", false);
                }

                Awaitility.await().atMost(Duration.ofSeconds(20)).untilAsserted(() -> {
                    for (var doomedUnit : doomedUnits) {
                        assertThat(fenceOf("ordered", doomedUnit)).as("unit %d handed back and taken again exactly once", doomedUnit)
                                                                  .isEqualTo(fencesBefore.get(doomedUnit) + 1);
                        assertThat(ownerOf("ordered", doomedUnit)).isEqualTo("dead-pump-fail");
                    }
                    assertThat(queue.metrics().shardsOnDeadPumps.get()).isZero();
                });
                assertThat(runtime.pumpFor(QUEUE_ID, "ordered", unit).isDead()).isFalse();
                queue.enqueueOrdered(List.of(new ShardOwnedStorage.OrderedPayload("HOLD", 1, "HOLD-1".getBytes(StandardCharsets.UTF_8), 1)));
                Awaitility.await().atMost(Duration.ofSeconds(20)).untilAsserted(
                        () -> assertThat(delivered).as("the key is served again on a live pump").contains("HOLD-1"));
            } finally {
                release.countDown();
                failLeaseReleases("ordered", false);
                queue.stop();
            }
        }
    }

    /**
     * A queue stopped while an ordered unit on a dead pump still has a key in its handler keeps that unit, as any stop
     * does, and hands back the rest - and a runtime that outlives the queue stops counting all of them in
     * {@code shardsOnDeadPumps}. The heartbeat never hands that unit back, so the stop is the only thing that uncounts it.
     */
    @Test
    void a_queue_stopped_with_a_key_in_a_dead_pumps_handler_keeps_that_unit_and_stops_counting_it() throws Exception {
        var settings = settings(2, Duration.ofMillis(300), Duration.ofSeconds(1));
        var entered  = new CountDownLatch(1);
        var release  = new CountDownLatch(1);
        var heldUnit = new CompletableFuture<Integer>();
        try (var runtime = new ShardRuntime(dataSource, settings)) {
            var queue = queue(runtime, "dead-pump-stop-o");
            try {
                queue.startConsumingOrdered((messageId, key, payload, payloadType) -> {
                    if ("HOLD-0".equals(new String(payload, StandardCharsets.UTF_8))) {
                        heldUnit.complete(messageId.shard());
                        entered.countDown();
                        awaitQuietly(release);
                    }
                }, settings, SHARD_COUNT);
                queue.enqueueOrdered(List.of(new ShardOwnedStorage.OrderedPayload("HOLD", 0, "HOLD-0".getBytes(StandardCharsets.UTF_8), 1)));
                assertThat(entered.await(20, TimeUnit.SECONDS)).as("premise: the key is in its handler").isTrue();
                var unit         = heldUnit.get();
                var doomed       = runtime.pumpFor(QUEUE_ID, "ordered", unit);
                var doomedUnits  = unitsOn(runtime, doomed, "ordered", ShardOwnedSchema.ORDERED_UNITS);
                var idleUnits    = doomedUnits.stream().filter(candidate -> candidate != unit).toList();
                var fencesBefore = fences("ordered", doomedUnits);
                assertThat(idleUnits).as("premise: the dead pump serves other units too").isNotEmpty();

                kill(doomed);
                Awaitility.await().atMost(Duration.ofSeconds(20)).untilAsserted(() -> {
                    for (var idle : idleUnits) {
                        assertThat(fenceOf("ordered", idle)).isGreaterThan(fencesBefore.get(idle));
                    }
                });
                assertThat(queue.metrics().shardsOnDeadPumps.get()).as("premise: the busy unit is still on the dead pump").isEqualTo(1);

                queue.stop();

                assertThat(runtime.isStarted()).as("premise: the runtime outlives the queue").isTrue();
                assertThat(queue.metrics().shardsOnDeadPumps.get()).as("the stopped queue counts nothing on the dead pump").isZero();
                for (var idle : idleUnits) {
                    assertThat(ownerOf("ordered", idle)).as("idle unit %d, re-taken on a live pump, released on stop", idle).isNull();
                }
                assertThat(ownerOf("ordered", unit)).as("the unit with a key in its handler is kept, not handed over mid-key")
                                                    .isEqualTo("dead-pump-stop-o");
                assertThat(fenceOf("ordered", unit)).isEqualTo(fencesBefore.get(unit));
            } finally {
                release.countDown();
                queue.stop();
            }
        }
    }

    /**
     * A queue stopped right after a pump died, before a heartbeat could hand its unordered shards back, releases them
     * itself: the death moved their owners to {@code ENDED_ROW_HELD}, so stop()'s {@code holdsLeaseRow()} selection still
     * puts them among the leases it gives back. A long lease keeps the heartbeat (a third of it) from running first.
     */
    @Test
    void a_queue_stopped_before_its_heartbeat_hands_back_a_dead_pumps_shards_releases_them_itself() throws Exception {
        var settings = settings(2, ShardOwnerSettings.defaults().shedGrace(), Duration.ofSeconds(60));
        try (var runtime = new ShardRuntime(dataSource, settings)) {
            var queue = queue(runtime, "dead-pump-stop-u");
            try {
                queue.startConsuming((messageId, payload, payloadType) -> { }, settings, SHARD_COUNT);
                assertThat(queue.shardsHeld()).as("premise: one instance holds every shard").isEqualTo(SHARD_COUNT);
                var doomed       = runtime.pumpFor(QUEUE_ID, "unordered", 0);
                var doomedShards = unitsOn(runtime, doomed, "unordered", SHARD_COUNT);
                var releasedBefore = queue.metrics().shardsReleased.sum();

                kill(doomed);
                assertThat(queue.metrics().shardsOnDeadPumps.get()).as("premise: on the dead pump").isEqualTo(doomedShards.size());
                assertThat(queue.metrics().shardsReleased.sum()).as("premise: no heartbeat has handed them back").isEqualTo(releasedBefore);
                for (var shard : doomedShards) {
                    assertThat(ownerOf("unordered", shard)).as("premise: shard %d still leased", shard).isEqualTo("dead-pump-stop-u");
                }

                queue.stop();

                assertThat(runtime.isStarted()).as("premise: the runtime outlives the queue").isTrue();
                assertThat(unitsOwnedBy("unordered", "dead-pump-stop-u")).as("every lease, the dead pump's included, released").isZero();
                assertThat(queue.metrics().shardsOnDeadPumps.get()).isZero();
            } finally {
                queue.stop();
            }
        }
    }

    /**
     * An unordered shard whose hand-back keeps failing still counts towards its instance's fair share, and is retried
     * until its release succeeds.
     * <p>
     * Its owner's lease ended in memory as the pump died, and the unordered acquire loop counted only owners whose lease
     * was held. So while the release kept failing, the instance took one free shard more per dead row, on top of its fair
     * share. A single instance cannot show it, because its fair share is every shard. Here there are two instances, and the
     * second is capped at one shard, so a shard stays free for the first to over-take. The release is made to fail by a
     * trigger, every time, until the trigger is dropped.
     */
    @Test
    void an_unordered_shard_whose_hand_back_fails_is_retried_without_exceeding_the_fair_share() throws Exception {
        var settings  = settings(2);
        var fairShare = SHARD_COUNT / 2;
        try (var firstRuntime = new ShardRuntime(dataSource, settings); var secondRuntime = new ShardRuntime(dataSource, settings)) {
            var first  = queue(firstRuntime, "fair-first");
            var second = queue(secondRuntime, "fair-second");
            try {
                first.startConsuming((messageId, payload, payloadType) -> { }, settings, SHARD_COUNT);
                second.startConsuming((messageId, payload, payloadType) -> { }, settings, 1);
                Awaitility.await().atMost(Duration.ofSeconds(20)).untilAsserted(() -> {
                    assertThat(unitsLeasedTo("unordered", "fair-first")).as("premise: the first holds its fair share").hasSize(fairShare);
                    assertThat(unitsLeasedTo("unordered", "fair-second")).as("premise: the second holds its cap").hasSize(1);
                });
                assertThat(unitsLeasedTo("unordered", null)).as("premise: a shard is free to over-take").isNotEmpty();
                var firstShards  = unitsLeasedTo("unordered", "fair-first");
                var doomed       = firstRuntime.pumpFor(QUEUE_ID, "unordered", firstShards.getFirst());
                var deadShards   = firstShards.stream().filter(shard -> firstRuntime.pumpFor(QUEUE_ID, "unordered", shard) == doomed).toList();
                var fencesBefore = fences("unordered", deadShards);

                failLeaseReleases("unordered", true);
                try {
                    kill(doomed);

                    // Sampled throughout, until every dead shard's hand-back has been refused often enough to have been followed by
                    // a rebalance (awaitReleasesRefused says why), and once more after.
                    var deadline = System.nanoTime() + Duration.ofSeconds(20).toNanos();
                    while (!releasesRefusedOftenEnough(QUEUE_ID, deadShards)) {
                        assertThat(System.nanoTime() < deadline).as("every dead shard's release refused %d times within 20 s; refused so far: %s",
                                                                    REFUSALS_SPANNING_A_TICK, refusals(QUEUE_ID, deadShards))
                                                                .isTrue();
                        assertThat(unitsLeasedTo("unordered", "fair-first")).as("never more lease rows than the fair share").hasSizeLessThanOrEqualTo(fairShare);
                        Thread.sleep(50);
                    }
                    assertThat(unitsLeasedTo("unordered", "fair-first")).as("never more lease rows than the fair share").hasSizeLessThanOrEqualTo(fairShare);
                    for (var shard : deadShards) {
                        assertThat(ownerOf("unordered", shard)).as("shard %d still leased: its release failed", shard).isEqualTo("fair-first");
                        assertThat(fenceOf("unordered", shard)).as("shard %d not taken again", shard).isEqualTo(fencesBefore.get(shard));
                    }
                    assertThat(first.metrics().shardsOnDeadPumps.get()).as("still on the dead pump: no hand-back has succeeded").isEqualTo(deadShards.size());
                } finally {
                    failLeaseReleases("unordered", false);
                }

                Awaitility.await().atMost(Duration.ofSeconds(20)).untilAsserted(() -> {
                    for (var shard : deadShards) {
                        assertThat(fenceOf("unordered", shard)).as("shard %d handed back and taken again exactly once", shard)
                                                               .isEqualTo(fencesBefore.get(shard) + 1);
                        assertThat(ownerOf("unordered", shard)).isEqualTo("fair-first");
                    }
                    assertThat(first.metrics().shardsOnDeadPumps.get()).isZero();
                });
                assertThat(unitsLeasedTo("unordered", "fair-first")).as("back at its fair share, on live pumps").hasSize(fairShare);
            } finally {
                failLeaseReleases("unordered", false);
                second.stop();
                first.stop();
            }
        }
    }

    /**
     * A dead pump's shard that another instance takes before the hand-back succeeds is dropped by the rebalance and no
     * longer counted on the dead pump, while the dead pump's other shards still wait for their release.
     * <p>
     * The heartbeat's lease check finds the row naming another instance and moves the owner from {@code ENDED_ROW_HELD} to
     * {@code RELEASED}. The hand-back then has no row of its own to release and leaves it out, so only the rebalance drops it.
     * That drop must tell the runtime, or the shard stays counted in {@code shardsOnDeadPumps} until the runtime stops.
     * The take is written straight into the lease row, as an instance that found this one's liveness lapsed would write it.
     * The taker is not registered, so it reads as gone at once and this instance takes the shard back on a live pump. That
     * re-take is also the proof the dead owner was dropped: the unordered acquire loop skips a shard it still maps to an
     * owner. Every release is refused throughout, so a hand-back cannot uncount the shard instead.
     */
    @Test
    void a_dead_pumps_shard_taken_by_another_instance_before_its_hand_back_is_dropped_and_no_longer_counted() throws Exception {
        var settings = settings(2);
        try (var runtime = new ShardRuntime(dataSource, settings)) {
            var queue = queue(runtime, "dead-pump-taken");
            try {
                queue.startConsuming((messageId, payload, payloadType) -> { }, settings, SHARD_COUNT);
                assertThat(queue.shardsHeld()).as("premise: one instance holds every shard").isEqualTo(SHARD_COUNT);
                var doomed       = runtime.pumpFor(QUEUE_ID, "unordered", 0);
                var deadShards   = unitsOn(runtime, doomed, "unordered", SHARD_COUNT);
                var fencesBefore = fences("unordered", deadShards);
                var taken        = (int) deadShards.getFirst();
                var stillDead    = deadShards.stream().filter(shard -> shard != taken).toList();

                failLeaseReleases("unordered", true);
                try {
                    kill(doomed);
                    awaitReleasesRefused(QUEUE_ID, deadShards, "before the take");
                    assertThat(queue.metrics().shardsOnDeadPumps.get()).as("premise: no hand-back has succeeded").isEqualTo(deadShards.size());

                    takeAs("unordered", taken, "dead-pump-taker");

                    Awaitility.await().atMost(Duration.ofSeconds(20)).untilAsserted(() -> {
                        assertThat(queue.metrics().shardsOnDeadPumps.get()).as("the taken shard is no longer counted on the dead pump")
                                                                          .isEqualTo(stillDead.size());
                        assertThat(ownerOf("unordered", taken)).as("taken back from the gone taker: the dead owner was dropped")
                                                               .isEqualTo("dead-pump-taken");
                        assertThat(fenceOf("unordered", taken)).as("taken, then taken back").isEqualTo(fencesBefore.get(taken) + 2);
                    });
                    assertThat(runtime.pumpFor(QUEUE_ID, "unordered", taken).isDead()).as("taken back on a live pump").isFalse();
                    for (var shard : stillDead) {
                        assertThat(ownerOf("unordered", shard)).as("shard %d still leased: its release failed", shard).isEqualTo("dead-pump-taken");
                        assertThat(fenceOf("unordered", shard)).as("shard %d not taken again", shard).isEqualTo(fencesBefore.get(shard));
                    }
                } finally {
                    failLeaseReleases("unordered", false);
                }

                Awaitility.await().atMost(Duration.ofSeconds(20)).untilAsserted(
                        () -> assertThat(queue.metrics().shardsOnDeadPumps.get()).as("the rest handed back once releases succeed").isZero());
            } finally {
                failLeaseReleases("unordered", false);
                queue.stop();
            }
        }
    }

    /**
     * An ordered unit that finished its shed before its pump died, and whose hand-back then fails, is released by the shed
     * instead, and is no longer counted on the dead pump.
     * <p>
     * The shed's release dropped the unit from the queue without telling the runtime, so it stayed counted in
     * {@code shardsOnDeadPumps} until the runtime stopped, with nothing left that would hand it back. Here a second live
     * instance that holds nothing halves the fair share, so this instance sheds its upper half. Every ordered release is
     * refused at first, so the units that finished shedding are still held when the pump dies. Then only a release of
     * several units at once is refused. That is the dead-pump hand-back's, which fails, while the same tick's shed releases
     * its units one at a time and succeeds.
     */
    @Test
    void an_ordered_unit_that_finished_shedding_before_its_pump_died_is_no_longer_counted_once_the_shed_releases_it() throws Exception {
        var settings = settings(2);
        var units    = ShardOwnedSchema.ORDERED_UNITS;
        var phantom  = Executors.newSingleThreadScheduledExecutor();
        try (var runtime = new ShardRuntime(dataSource, settings)) {
            var queue = queue(runtime, "dead-pump-shed");
            try {
                queue.startConsumingOrdered((messageId, key, payload, payloadType) -> { }, settings, SHARD_COUNT);
                assertThat(queue.shardsHeld()).as("premise: one instance holds every unit").isEqualTo(units);
                var shed         = new ArrayList<Integer>();
                for (var unit = units / 2; unit < units; unit++) {
                    shed.add(unit);
                }
                var doomed       = runtime.pumpFor(QUEUE_ID, "ordered", units - 1);
                var doomedUnits  = unitsOn(runtime, doomed, "ordered", units);
                var shedOnDoomed = doomedUnits.stream().filter(shed::contains).toList();
                var keptOnDoomed = doomedUnits.stream().filter(unit -> !shed.contains(unit)).toList();
                assertThat(keptOnDoomed).as("premise: the hand-back releases several units at once").hasSizeGreaterThan(1);
                var fencesBefore = fences("ordered", keptOnDoomed);

                failLeaseReleases("ordered", true);
                try {
                    phantom.scheduleAtFixedRate(() -> {
                        try {
                            queue.storage().heartbeatInstance("phantom");
                        } catch (Exception ignored) {
                            // The next beat retries; a missed one only delays the shed.
                        }
                    }, 0, 100, TimeUnit.MILLISECONDS);
                    Awaitility.await().atMost(Duration.ofSeconds(20))
                              .until(() -> queue.metrics().shedsCompleted.sum() >= shed.size());
                    assertThat(unitsLeasedTo("ordered", "dead-pump-shed")).as("premise: no shed unit released yet").hasSize(units);

                    kill(doomed);
                    assertThat(queue.metrics().shardsOnDeadPumps.get()).as("premise: on the dead pump").isEqualTo(doomedUnits.size());

                    // The narrow refusal first: a heartbeat tick between the two would otherwise find no refusal in place
                    // and hand the kept units back in one statement.
                    failMultiUnitOrderedReleases(true);
                    failLeaseReleases("ordered", false);
                    Awaitility.await().atMost(Duration.ofSeconds(20)).untilAsserted(() -> {
                        assertThat(unitsLeasedTo("ordered", null)).as("the shed released every unit it had finished with").containsAll(shed);
                        assertThat(queue.metrics().shardsOnDeadPumps.get()).as("those on the dead pump are no longer counted there")
                                                                           .isEqualTo(keptOnDoomed.size());
                    });
                    for (var unit : keptOnDoomed) {
                        assertThat(ownerOf("ordered", unit)).as("unit %d still leased: the hand-back keeps failing", unit).isEqualTo("dead-pump-shed");
                    }
                } finally {
                    failLeaseReleases("ordered", false);
                    failMultiUnitOrderedReleases(false);
                }

                Awaitility.await().atMost(Duration.ofSeconds(20)).untilAsserted(() -> {
                    for (var unit : keptOnDoomed) {
                        assertThat(fenceOf("ordered", unit)).as("unit %d handed back and taken again exactly once", unit)
                                                            .isEqualTo(fencesBefore.get(unit) + 1);
                    }
                    assertThat(queue.metrics().shardsOnDeadPumps.get()).isZero();
                });
            } finally {
                phantom.shutdownNow();
                failLeaseReleases("ordered", false);
                failMultiUnitOrderedReleases(false);
                queue.stop();
            }
        }
    }

    /**
     * A pump serving several queues holds owners of each - the default deployment's shape, where every queue borrows the
     * runtime shared per {@code DataSource}, and a {@code PostgresqlMessageQueue}'s, whose unordered and ordered consumers
     * of one queue share it - and each queue hands its own owners back through its own heartbeat and counts only its own in
     * {@code shardsOnDeadPumps}. The unordered queue's dead shards are handed back and taken again on a live pump while the
     * ordered queue keeps its unit with a key in a handler; stopping the unordered queue leaves that unit, and its count,
     * to the ordered queue until the key leaves the handler.
     * <p>
     * Each consumer has its own metrics here, so the two counts can be told apart; a {@code PostgresqlMessageQueue}
     * subscription gives its two consumers one, which sums them.
     */
    @ParameterizedTest
    @EnumSource(Consumers.class)
    void a_pump_serving_two_queues_hands_each_queues_shards_back_through_that_queue(Consumers consumers) throws Exception {
        var settings  = settings(2);
        var entered   = new CountDownLatch(1);
        var release   = new CountDownLatch(1);
        var heldUnit  = new CompletableFuture<Integer>();
        var delivered = ConcurrentHashMap.<String>newKeySet();
        try (var runtime = new ShardRuntime(dataSource, settings)) {
            var unordered = queue(runtime, consumers.unorderedQueueId, "shared-pump");
            var ordered   = queue(runtime, consumers.orderedQueueId, "shared-pump");
            try {
                unordered.startConsuming((messageId, payload, payloadType) -> { }, settings, SHARD_COUNT);
                ordered.startConsumingOrdered(holding("HOLD-0", heldUnit, entered, release, delivered), settings, SHARD_COUNT);
                ordered.enqueueOrdered(List.of(new ShardOwnedStorage.OrderedPayload("HOLD", 0, "HOLD-0".getBytes(StandardCharsets.UTF_8), 1)));
                assertThat(entered.await(20, TimeUnit.SECONDS)).as("premise: the key is in its handler").isTrue();
                assertThat(unordered.shardsHeld()).as("premise: the instance holds every unordered shard").isEqualTo(SHARD_COUNT);
                int  unit       = heldUnit.get();
                var  doomed     = runtime.pumpFor(consumers.orderedQueueId, "ordered", unit);
                var  deadShards = unitsOn(runtime, doomed, consumers.unorderedQueueId, "unordered", SHARD_COUNT);
                var  idleUnits  = unitsOn(runtime, doomed, consumers.orderedQueueId, "ordered", ShardOwnedSchema.ORDERED_UNITS)
                        .stream().filter(candidate -> candidate != unit).toList();
                assertThat(deadShards).as("premise: the pump serves the unordered queue too").isNotEmpty();
                assertThat(idleUnits).as("premise: and other units of the ordered queue").isNotEmpty();
                var  shardFences = fences(consumers.unorderedQueueId, "unordered", deadShards);
                var  unitFences  = fences(consumers.orderedQueueId, "ordered", idleUnits);
                long heldFence   = fenceOf(consumers.orderedQueueId, "ordered", unit);

                kill(doomed);

                Awaitility.await().atMost(Duration.ofSeconds(20)).untilAsserted(() -> {
                    for (var shard : deadShards) {
                        assertThat(fenceOf(consumers.unorderedQueueId, "unordered", shard)).as("unordered shard %d handed back and taken again", shard)
                                                                                         .isGreaterThan(shardFences.get(shard));
                    }
                    for (var idle : idleUnits) {
                        assertThat(fenceOf(consumers.orderedQueueId, "ordered", idle)).as("idle ordered unit %d handed back and taken again", idle)
                                                                                     .isGreaterThan(unitFences.get(idle));
                    }
                    assertThat(unordered.metrics().shardsOnDeadPumps.get()).isZero();
                });
                // Several heartbeats of both queues (a third of the 1 s lease each) with the key still in its handler.
                Thread.sleep(1_000);
                for (var shard : deadShards) {
                    assertThat(ownerOf(consumers.unorderedQueueId, "unordered", shard)).isEqualTo("shared-pump");
                    assertThat(runtime.pumpFor(consumers.unorderedQueueId, "unordered", shard).isDead()).as("re-acquired on a live pump").isFalse();
                }
                assertThat(unordered.metrics().shardsOnDeadPumps.get()).as("the unordered queue counts nothing on the dead pump").isZero();
                assertThat(ordered.metrics().shardsOnDeadPumps.get()).as("the ordered queue counts its busy unit, and nothing of the unordered queue's")
                                                                     .isEqualTo(1);
                assertThat(fenceOf(consumers.orderedQueueId, "ordered", unit)).as("the unit with a key in its handler is not handed over")
                                                                              .isEqualTo(heldFence);
                assertThat(ownerOf(consumers.orderedQueueId, "ordered", unit)).isEqualTo("shared-pump");

                unordered.stop();

                assertThat(runtime.isStarted()).as("premise: the runtime outlives the unordered queue").isTrue();
                // Several heartbeats of the ordered queue alone.
                Thread.sleep(1_000);
                assertThat(ordered.metrics().shardsOnDeadPumps.get()).as("the unordered queue's stop leaves the ordered queue's count alone")
                                                                     .isEqualTo(1);
                assertThat(fenceOf(consumers.orderedQueueId, "ordered", unit)).as("and its busy unit").isEqualTo(heldFence);
                assertThat(ownerOf(consumers.orderedQueueId, "ordered", unit)).isEqualTo("shared-pump");

                release.countDown();

                Awaitility.await().atMost(Duration.ofSeconds(20)).untilAsserted(() -> {
                    assertThat(fenceOf(consumers.orderedQueueId, "ordered", unit)).as("handed back once the handler returned").isGreaterThan(heldFence);
                    assertThat(ordered.metrics().shardsOnDeadPumps.get()).isZero();
                });
                assertThat(runtime.pumpFor(consumers.orderedQueueId, "ordered", unit).isDead()).isFalse();
                ordered.enqueueOrdered(List.of(new ShardOwnedStorage.OrderedPayload("HOLD", 1, "HOLD-1".getBytes(StandardCharsets.UTF_8), 1)));
                Awaitility.await().atMost(Duration.ofSeconds(20)).untilAsserted(
                        () -> assertThat(delivered).as("the key is served again on a live pump").contains("HOLD-1"));
            } finally {
                release.countDown();
                ordered.stop();
                unordered.stop();
            }
        }
    }

    /**
     * One queue's failing hand-back, and its stop, leave the units another queue has on the same dead pump alone, and
     * that queue's count: each queue releases, retries and un-counts only the owners in its own list. Here every unordered
     * release is refused while the ordered queue hands its idle units back and keeps its busy one; the unordered queue then
     * stops with its dead shards still unreleased, which un-counts them, and the ordered queue goes on counting its unit
     * until the key leaves the handler and the unit is handed back.
     */
    @ParameterizedTest
    @EnumSource(Consumers.class)
    void a_queue_whose_hand_back_fails_and_which_then_stops_leaves_another_queues_dead_pump_units_alone(Consumers consumers) throws Exception {
        var settings  = settings(2);
        var entered   = new CountDownLatch(1);
        var release   = new CountDownLatch(1);
        var heldUnit  = new CompletableFuture<Integer>();
        var delivered = ConcurrentHashMap.<String>newKeySet();
        try (var runtime = new ShardRuntime(dataSource, settings)) {
            var unordered = queue(runtime, consumers.unorderedQueueId, "shared-pump-fail");
            var ordered   = queue(runtime, consumers.orderedQueueId, "shared-pump-fail");
            try {
                unordered.startConsuming((messageId, payload, payloadType) -> { }, settings, SHARD_COUNT);
                ordered.startConsumingOrdered(holding("HOLD-0", heldUnit, entered, release, delivered), settings, SHARD_COUNT);
                ordered.enqueueOrdered(List.of(new ShardOwnedStorage.OrderedPayload("HOLD", 0, "HOLD-0".getBytes(StandardCharsets.UTF_8), 1)));
                assertThat(entered.await(20, TimeUnit.SECONDS)).as("premise: the key is in its handler").isTrue();
                assertThat(unordered.shardsHeld()).as("premise: the instance holds every unordered shard").isEqualTo(SHARD_COUNT);
                int  unit       = heldUnit.get();
                var  doomed     = runtime.pumpFor(consumers.orderedQueueId, "ordered", unit);
                var  deadShards = unitsOn(runtime, doomed, consumers.unorderedQueueId, "unordered", SHARD_COUNT);
                var  idleUnits  = unitsOn(runtime, doomed, consumers.orderedQueueId, "ordered", ShardOwnedSchema.ORDERED_UNITS)
                        .stream().filter(candidate -> candidate != unit).toList();
                assertThat(deadShards).as("premise: the pump serves the unordered queue too").isNotEmpty();
                assertThat(idleUnits).as("premise: and other units of the ordered queue").isNotEmpty();
                var  shardFences = fences(consumers.unorderedQueueId, "unordered", deadShards);
                var  unitFences  = fences(consumers.orderedQueueId, "ordered", idleUnits);
                long heldFence   = fenceOf(consumers.orderedQueueId, "ordered", unit);

                failLeaseReleases("unordered", true);
                try {
                    kill(doomed);

                    awaitReleasesRefused(consumers.unorderedQueueId, deadShards, "while the ordered queue hands its idle units back");
                    Awaitility.await().atMost(Duration.ofSeconds(20)).untilAsserted(() -> {
                        for (var idle : idleUnits) {
                            assertThat(fenceOf(consumers.orderedQueueId, "ordered", idle)).as("idle ordered unit %d handed back and taken again", idle)
                                                                                         .isGreaterThan(unitFences.get(idle));
                        }
                    });
                    for (var shard : deadShards) {
                        assertThat(ownerOf(consumers.unorderedQueueId, "unordered", shard)).as("unordered shard %d still leased: its release failed", shard)
                                                                                         .isEqualTo("shared-pump-fail");
                        assertThat(fenceOf(consumers.unorderedQueueId, "unordered", shard)).as("unordered shard %d not taken again", shard)
                                                                                         .isEqualTo(shardFences.get(shard));
                    }
                    assertThat(unordered.metrics().shardsOnDeadPumps.get()).as("the unordered queue counts its unreleased dead shards, and nothing else")
                                                                           .isEqualTo(deadShards.size());
                    assertThat(ordered.metrics().shardsOnDeadPumps.get()).as("the ordered queue counts its busy unit, and nothing of the unordered queue's")
                                                                         .isEqualTo(1);
                    assertThat(fenceOf(consumers.orderedQueueId, "ordered", unit)).as("the unit with a key in its handler is not handed over")
                                                                                  .isEqualTo(heldFence);

                    unordered.stop();

                    assertThat(runtime.isStarted()).as("premise: the runtime outlives the unordered queue").isTrue();
                    assertThat(unordered.metrics().shardsOnDeadPumps.get()).as("the stopped queue counts nothing on the dead pump").isZero();
                    assertThat(ordered.metrics().shardsOnDeadPumps.get()).as("and un-counted nothing of the ordered queue's").isEqualTo(1);
                } finally {
                    failLeaseReleases("unordered", false);
                }
                // Several heartbeats of the ordered queue alone.
                Thread.sleep(1_000);
                assertThat(ordered.metrics().shardsOnDeadPumps.get()).as("the ordered queue still counts its busy unit").isEqualTo(1);
                assertThat(fenceOf(consumers.orderedQueueId, "ordered", unit)).as("and keeps it").isEqualTo(heldFence);
                assertThat(ownerOf(consumers.orderedQueueId, "ordered", unit)).isEqualTo("shared-pump-fail");

                release.countDown();

                Awaitility.await().atMost(Duration.ofSeconds(20)).untilAsserted(() -> {
                    assertThat(fenceOf(consumers.orderedQueueId, "ordered", unit)).as("handed back once the handler returned").isGreaterThan(heldFence);
                    assertThat(ordered.metrics().shardsOnDeadPumps.get()).isZero();
                });
            } finally {
                release.countDown();
                failLeaseReleases("unordered", false);
                ordered.stop();
                unordered.stop();
            }
        }
    }

    /**
     * An ordered handler that keeps the message {@code held} in itself until {@code release} opens, and reports the unit it
     * arrived on; every message it handles is recorded in {@code delivered}.
     */
    private static OrderedPayloadHandler holding(String held, CompletableFuture<Integer> heldUnit, CountDownLatch entered, CountDownLatch release,
                                                 Set<String> delivered) {
        return (messageId, key, payload, payloadType) -> {
            var text = new String(payload, StandardCharsets.UTF_8);
            if (held.equals(text)) {
                heldUnit.complete(messageId.shard());
                entered.countDown();
                awaitQuietly(release);
            }
            delivered.add(text);
        };
    }

    private ShardOwnedQueue queue(ShardRuntime runtime, String instanceId) {
        return queue(runtime, QUEUE_ID, instanceId);
    }

    private ShardOwnedQueue queue(ShardRuntime runtime, short queueId, String instanceId) {
        return ShardOwnedQueue.builder().setDataSource(dataSource).setQueueId(queueId).setShardCount(SHARD_COUNT)
                              .setInstanceId(instanceId).setRuntime(runtime).build();
    }

    /**
     * Two consumers on one runtime, one per lane: the two lanes of one queue, as {@code PostgresqlMessageQueue.consume}
     * builds them, or two queues. Either way they share one instance id, as one process's consumers do.
     */
    enum Consumers {
        TWO_LANES_OF_ONE_QUEUE(QUEUE_ID, QUEUE_ID),
        TWO_QUEUES(OTHER_QUEUE_ID, QUEUE_ID);

        final short unorderedQueueId;
        final short orderedQueueId;

        Consumers(short unorderedQueueId, short orderedQueueId) {
            this.unorderedQueueId = unorderedQueueId;
            this.orderedQueueId = orderedQueueId;
        }
    }

    /**
     * A one-second lease, so the heartbeat that hands units back runs every third of a second.
     */
    private static ShardOwnerSettings settings(int pumpThreads) {
        return settings(pumpThreads, ShardOwnerSettings.defaults().shedGrace(), Duration.ofSeconds(1));
    }

    private static ShardOwnerSettings settings(int pumpThreads, Duration shedGrace, Duration leaseTtl) {
        var d = ShardOwnerSettings.defaults();
        return new ShardOwnerSettings(d.readBatchSize(), d.ackBatchSize(), d.ackFlushInterval(), d.chaseDelay(), d.holeExpiry(),
                                      d.sweepInterval(), d.maxHolesPerChase(), d.keyConcurrency(), d.pollBackstop(),
                                      d.maxSweepInterval(), pumpThreads, shedGrace, leaseTtl, d.watermarkCap());
    }

    /**
     * Make every release of a lease row on {@code lane} fail, as a database error would, or stop doing so. A trigger rather
     * than a revoked grant: the test's role owns the table, and an owner keeps every privilege.
     * <p>
     * Each refusal is counted per queue and unit ({@link #releasesRefused}), so a test can show the injection worked: that a
     * release was attempted and refused, not merely that nothing was released. Per queue as well as per unit, because unit
     * numbers repeat across queues, and a test with two queues on one lane would otherwise count one queue's refusals as the
     * other's. The counters are sequences because the refusal rolls back its transaction, and a {@code nextval} is the one
     * write a rollback does not undo. The trigger runs once per statement, after its rows, so it counts every unit of a
     * release of several, not just the first it reached.
     */
    private void failLeaseReleases(String lane, boolean fail) throws Exception {
        try (var connection = dataSource.getConnection(); var statement = connection.createStatement()) {
            if (fail) {
                statement.execute(refusalCounters("DROP SEQUENCE IF EXISTS lease_release_refusals_q%s_%s"));
                statement.execute(refusalCounters("CREATE SEQUENCE lease_release_refusals_q%s_%s"));
                statement.execute("CREATE OR REPLACE FUNCTION fail_lease_release() RETURNS trigger AS $$ "
                                          + "DECLARE released record; refused int := 0; BEGIN "
                                          + "FOR released IN SELECT n.queue_id, n.shard FROM refused_new n JOIN refused_old o USING (queue_id, lane, shard) "
                                          + "WHERE n.lane = TG_ARGV[0] AND n.owner IS NULL AND o.owner IS NOT NULL LOOP "
                                          + "PERFORM nextval(format('lease_release_refusals_q%s_%s', released.queue_id, released.shard)::regclass); "
                                          + "refused := refused + 1; END LOOP; "
                                          + "IF refused > 0 THEN RAISE EXCEPTION 'simulated failure releasing % % unit(s)', refused, TG_ARGV[0]; END IF; "
                                          + "RETURN NULL; END $$ LANGUAGE plpgsql");
                statement.execute("CREATE TRIGGER fail_lease_release AFTER UPDATE ON " + ShardOwnedSchema.LEASE_TABLE
                                          + " REFERENCING OLD TABLE AS refused_old NEW TABLE AS refused_new"
                                          + " FOR EACH STATEMENT EXECUTE FUNCTION fail_lease_release('" + lane + "')");
            } else {
                // The trigger first: dropping it waits for a release still running it, so no refusal counts on a dropped sequence.
                statement.execute("DROP TRIGGER IF EXISTS fail_lease_release ON " + ShardOwnedSchema.LEASE_TABLE);
                statement.execute(refusalCounters("DROP SEQUENCE IF EXISTS lease_release_refusals_q%s_%s"));
            }
        }
    }

    /**
     * {@code statementPerUnit}, formatted with a queue id and a unit, run for every unit either lane can have here (the
     * ordered lane's being the larger space) of every queue registered here ({@link #QUEUE_IDS}).
     */
    private static String refusalCounters(String statementPerUnit) {
        var perQueue = new StringBuilder();
        for (var queueId : QUEUE_IDS) {
            perQueue.append("EXECUTE format('").append(statementPerUnit).append("', ").append(queueId).append(", unit); ");
        }
        return "DO $$ BEGIN FOR unit IN 0.." + (ShardOwnedSchema.ORDERED_UNITS - 1) + " LOOP " + perQueue + "END LOOP; END $$";
    }

    /**
     * How many releases of {@code unit} of queue {@code queueId} {@link #failLeaseReleases} has refused since it was last
     * turned on.
     */
    private long releasesRefused(short queueId, int unit) throws Exception {
        try (var connection = dataSource.getConnection(); var statement = connection.createStatement();
             var resultSet = statement.executeQuery("SELECT CASE WHEN is_called THEN last_value ELSE 0 END FROM lease_release_refusals_q"
                                                            + queueId + "_" + unit)) {
            resultSet.next();
            return resultSet.getLong(1);
        }
    }

    /**
     * {@link #releasesRefused} for each of {@code units} of queue {@code queueId}, by unit.
     */
    private Map<Integer, Long> refusals(short queueId, List<Integer> units) throws Exception {
        var refusals = new TreeMap<Integer, Long>();
        for (var unit : units) {
            refusals.put(unit, releasesRefused(queueId, unit));
        }
        return refusals;
    }

    private boolean releasesRefusedOftenEnough(short queueId, List<Integer> units) throws Exception {
        return refusals(queueId, units).values().stream().allMatch(refused -> refused >= REFUSALS_SPANNING_A_TICK);
    }

    /**
     * Wait until the release of every unit in {@code units} of queue {@code queueId} has been attempted and refused often
     * enough that a whole heartbeat tick has run with it failing ({@link #REFUSALS_SPANNING_A_TICK}): the evidence that the
     * injection worked.
     */
    private void awaitReleasesRefused(short queueId, List<Integer> units, String when) {
        Awaitility.await("the release of units " + units + " of queue " + queueId + " refused " + REFUSALS_SPANNING_A_TICK + " times " + when)
                  .atMost(Duration.ofSeconds(20))
                  .until(() -> releasesRefusedOftenEnough(queueId, units));
    }

    /**
     * Make a statement that releases more than one ordered lease row fail, or stop doing so: the dead-pump hand-back,
     * which releases a pump's units in one statement, fails while a release of one unit at a time succeeds.
     */
    private void failMultiUnitOrderedReleases(boolean fail) throws Exception {
        try (var connection = dataSource.getConnection(); var statement = connection.createStatement()) {
            if (fail) {
                statement.execute("CREATE OR REPLACE FUNCTION fail_multi_unit_release() RETURNS trigger AS $$ BEGIN "
                                          + "IF (SELECT count(*) FROM released_new n JOIN released_old o USING (queue_id, lane, shard) "
                                          + "WHERE n.lane = 'ordered' AND n.owner IS NULL AND o.owner IS NOT NULL) > 1 THEN "
                                          + "RAISE EXCEPTION 'simulated failure releasing several ordered units'; END IF; "
                                          + "RETURN NULL; END $$ LANGUAGE plpgsql");
                statement.execute("CREATE TRIGGER fail_multi_unit_release AFTER UPDATE ON " + ShardOwnedSchema.LEASE_TABLE
                                          + " REFERENCING OLD TABLE AS released_old NEW TABLE AS released_new"
                                          + " FOR EACH STATEMENT EXECUTE FUNCTION fail_multi_unit_release()");
            } else {
                statement.execute("DROP TRIGGER IF EXISTS fail_multi_unit_release ON " + ShardOwnedSchema.LEASE_TABLE);
            }
        }
    }

    /**
     * Every unit of the dead pump still leased to the instance under the fence it had at the death, and still counted on
     * the dead pump: nothing took it again. On its own this does not show a hand-back was attempted - a unit nobody tried
     * to release looks the same - so call it after {@link #awaitReleasesRefused}, which does.
     */
    private void assertNoneTakenAgain(ShardOwnedQueue queue, List<Integer> units, Map<Integer, Long> fencesBefore, String when) throws Exception {
        for (var unit : units) {
            assertThat(ownerOf("ordered", unit)).as("unit %d still leased %s: its release failed", unit, when).isEqualTo("dead-pump-fail");
            assertThat(fenceOf("ordered", unit)).as("unit %d not taken again %s", unit, when).isEqualTo(fencesBefore.get(unit));
        }
        assertThat(queue.metrics().shardsOnDeadPumps.get()).as("still on the dead pump %s", when).isEqualTo(units.size());
    }

    /**
     * End {@code pump}'s thread the way a critical error does: an owner it serves throws a {@code LinkageError}.
     */
    private static void kill(ShardPump pump) {
        pump.add(new DyingOwner());
        Awaitility.await().atMost(Duration.ofSeconds(10)).until(pump::isDead);
    }

    private static List<Integer> unitsOn(ShardRuntime runtime, ShardPump pump, String lane, int units) {
        return unitsOn(runtime, pump, QUEUE_ID, lane, units);
    }

    private static List<Integer> unitsOn(ShardRuntime runtime, ShardPump pump, short queueId, String lane, int units) {
        var on = new ArrayList<Integer>();
        for (var unit = 0; unit < units; unit++) {
            if (runtime.pumpFor(queueId, lane, unit) == pump) {
                on.add(unit);
            }
        }
        return on;
    }

    private Map<Integer, Long> fences(String lane, List<Integer> units) throws Exception {
        return fences(QUEUE_ID, lane, units);
    }

    private Map<Integer, Long> fences(short queueId, String lane, List<Integer> units) throws Exception {
        var fences = new HashMap<Integer, Long>();
        for (var unit : units) {
            fences.put(unit, fenceOf(queueId, lane, unit));
        }
        return fences;
    }

    private long fenceOf(String lane, int shard) throws Exception {
        return fenceOf(QUEUE_ID, lane, shard);
    }

    private long fenceOf(short queueId, String lane, int shard) throws Exception {
        try (var connection = dataSource.getConnection();
             var statement = connection.prepareStatement("SELECT fence FROM " + ShardOwnedSchema.LEASE_TABLE
                                                                 + " WHERE queue_id = ? AND lane = ? AND shard = ?")) {
            statement.setShort(1, queueId);
            statement.setString(2, lane);
            statement.setInt(3, shard);
            try (var resultSet = statement.executeQuery()) {
                resultSet.next();
                return resultSet.getLong(1);
            }
        }
    }

    private String ownerOf(String lane, int shard) throws Exception {
        return ownerOf(QUEUE_ID, lane, shard);
    }

    private String ownerOf(short queueId, String lane, int shard) throws Exception {
        try (var connection = dataSource.getConnection();
             var statement = connection.prepareStatement("SELECT owner FROM " + ShardOwnedSchema.LEASE_TABLE
                                                                 + " WHERE queue_id = ? AND lane = ? AND shard = ?")) {
            statement.setShort(1, queueId);
            statement.setString(2, lane);
            statement.setInt(3, shard);
            try (var resultSet = statement.executeQuery()) {
                resultSet.next();
                return resultSet.getString(1);
            }
        }
    }

    /**
     * The units of {@code lane} whose lease row names {@code instanceId}, or no instance when it is {@code null}.
     */
    private List<Integer> unitsLeasedTo(String lane, String instanceId) throws Exception {
        try (var connection = dataSource.getConnection();
             var statement = connection.prepareStatement("SELECT shard FROM " + ShardOwnedSchema.LEASE_TABLE
                                                                 + " WHERE queue_id = ? AND lane = ? AND owner IS NOT DISTINCT FROM CAST(? AS text)"
                                                                 + " ORDER BY shard")) {
            statement.setShort(1, QUEUE_ID);
            statement.setString(2, lane);
            statement.setString(3, instanceId);
            try (var resultSet = statement.executeQuery()) {
                var units = new ArrayList<Integer>();
                while (resultSet.next()) {
                    units.add(resultSet.getInt(1));
                }
                return units;
            }
        }
    }

    /**
     * The rows {@code instanceId} has in {@link ShardOwnedSchema#INSTANCE_TABLE} for {@link #QUEUE_ID}: its cluster membership.
     */
    private int membershipRows(String instanceId) throws Exception {
        try (var connection = dataSource.getConnection();
             var statement = connection.prepareStatement("SELECT count(*) FROM " + ShardOwnedSchema.INSTANCE_TABLE
                                                                 + " WHERE queue_id = ? AND instance_id = ?")) {
            statement.setShort(1, QUEUE_ID);
            statement.setString(2, instanceId);
            try (var resultSet = statement.executeQuery()) {
                resultSet.next();
                return resultSet.getInt(1);
            }
        }
    }

    /**
     * Write a take of {@code shard} by {@code instanceId} into its lease row, as {@code ShardOwnedStorage.acquireLeaseGrant}
     * writes one: the row names the taker under a new fence.
     */
    private void takeAs(String lane, int shard, String instanceId) throws Exception {
        try (var connection = dataSource.getConnection();
             var statement = connection.prepareStatement("UPDATE " + ShardOwnedSchema.LEASE_TABLE
                                                                 + " SET owner = ?, lease_until = NULL, fence = fence + 1"
                                                                 + " WHERE queue_id = ? AND lane = ? AND shard = ?")) {
            statement.setString(1, instanceId);
            statement.setShort(2, QUEUE_ID);
            statement.setString(3, lane);
            statement.setInt(4, shard);
            assertThat(statement.executeUpdate()).as("premise: shard %d has a lease row", shard).isEqualTo(1);
        }
    }

    private int unitsOwnedBy(String lane, String instanceId) throws Exception {
        try (var connection = dataSource.getConnection();
             var statement = connection.prepareStatement("SELECT count(*) FROM " + ShardOwnedSchema.LEASE_TABLE
                                                                 + " WHERE queue_id = ? AND lane = ? AND owner = ?")) {
            statement.setShort(1, QUEUE_ID);
            statement.setString(2, lane);
            statement.setString(3, instanceId);
            try (var resultSet = statement.executeQuery()) {
                resultSet.next();
                return resultSet.getInt(1);
            }
        }
    }

    /**
     * Record what {@link ShardOwnedQueue} logs, from now until {@link #releaseQueueLog}.
     */
    private static ListAppender<ILoggingEvent> captureQueueLog() {
        var logged = new ListAppender<ILoggingEvent>();
        logged.start();
        ((ch.qos.logback.classic.Logger) LoggerFactory.getLogger(ShardOwnedQueue.class)).addAppender(logged);
        return logged;
    }

    private static void releaseQueueLog(ListAppender<ILoggingEvent> logged) {
        ((ch.qos.logback.classic.Logger) LoggerFactory.getLogger(ShardOwnedQueue.class)).detachAppender(logged);
        logged.stop();
    }

    /**
     * The WARNs logged so far whose message pattern contains {@code containing}.
     */
    private static List<ILoggingEvent> warnings(ListAppender<ILoggingEvent> logged, String containing) {
        List<ILoggingEvent> events;
        synchronized (logged) { // the monitor AppenderBase appends under; the heartbeat is still logging
            events = new ArrayList<>(logged.list);
        }
        return events.stream()
                     .filter(event -> event.getLevel() == Level.WARN)
                     .filter(event -> event.getMessage().contains(containing))
                     .toList();
    }

    private static void awaitQuietly(CountDownLatch latch) {
        try {
            latch.await(30, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    /**
     * Taken over, then throws a critical error the first time the pump asks whether it needs attention.
     */
    private static final class DyingOwner implements LeasedOwner {
        private final ShardOwnerMetrics metrics = new ShardOwnerMetrics();

        @Override
        public int pumpOnce(Connection connection) {
            return 0;
        }

        @Override
        public boolean needsAttention() {
            throw new LinkageError("simulated critical error on the pump thread");
        }

        @Override
        public void onTakeover(Connection connection) {
        }

        @Override
        public void flushOnStop(Connection connection) {
        }

        @Override
        public long parkDeadlineMillis() {
            return Long.MAX_VALUE;
        }

        @Override
        public int shard() {
            return 0;
        }

        @Override
        public short queueId() {
            return KILLER_QUEUE_ID;
        }

        @Override
        public ShardOwnerMetrics ownerMetrics() {
            return metrics;
        }

        @Override
        public long fence() {
            return 1;
        }

        @Override
        public String lane() {
            return "unordered";
        }

        @Override
        public LeaseRow leaseRow() {
            return LeaseRow.HELD;
        }

        @Override
        public void endKeepingLeaseRow() {
        }

        @Override
        public void beginDraining() {
        }

        @Override
        public int inFlight() {
            return 0;
        }

        @Override
        public void onLeaseEnded(LeaseEnd reason) {
        }
    }
}
