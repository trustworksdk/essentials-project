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
import java.util.concurrent.atomic.*;

import static dk.trustworks.essentials.components.queue.shardowned.SingleRowQueries.*;
import static org.assertj.core.api.Assertions.*;

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
     * Once that wait outlasts {@code shedGrace} the queue names the key holding it up in one WARN, as an abandoned shed does
     * - not on every heartbeat it goes on waiting. It used to log the wait at DEBUG alone, so the pump's ERROR and a
     * {@code shardsOnDeadPumps} that never fell were all an operator had, with nothing naming the key. The wait is bounded
     * by {@code stuckHandlerTimeout}, at its 15 minute default here (the next test).
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
                int unit         = heldUnit.get();
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
                                                                          .contains("stays leased and unserved until the handler returns, or until a "
                                                                                    + "key has been in its handler longer than stuckHandlerTimeout (PT15M)");
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
     * The wait for a dead pump's ordered unit is bounded by {@code stuckHandlerTimeout}: once the key has been in its handler
     * longer, the handler counts as stuck and the unit is handed back with it still running, named in one WARN and counted
     * in {@code keptUnitsInHandler} while the handler runs on; the message in that handler is charged one attempt before the
     * release. A live pump here takes the unit again under a new fence, charging nothing itself (the row was released), and
     * delivers the stuck key's message, with that attempt counted, again beside the hung handler -
     * the at-least-once the bound trades the stuck unit for. Unbounded, a handler that never returned kept the unit
     * leased and unserved until the runtime was restarted.
     */
    @Test
    void an_ordered_unit_on_a_dead_pump_is_handed_back_once_its_key_has_been_in_its_handler_past_the_stuck_handler_timeout() throws Exception {
        var settings  = settings(2, Duration.ofMillis(300), Duration.ofSeconds(1), Duration.ofSeconds(3));
        var entered   = new CountDownLatch(1);
        var release   = new CountDownLatch(1);
        var entries   = new AtomicInteger();
        var heldUnit  = new CompletableFuture<Integer>();
        var attemptsOnRedelivery = new CompletableFuture<Integer>();
        var logged    = captureQueueLog();
        try (var runtime = new ShardRuntime(dataSource, settings)) {
            var queue = queue(runtime, "dead-pump-stuck");
            try {
                queue.startConsumingOrdered((messageId, key, payload, payloadType) -> {
                    if (!"HOLD".equals(key)) {
                        return;
                    }
                    if (entries.incrementAndGet() == 1) {
                        heldUnit.complete(messageId.shard());
                        entered.countDown();
                        awaitQuietly(release);
                    } else {
                        attemptsOnRedelivery.complete(attemptsOf(messageId.sequence()));
                    }
                }, settings, SHARD_COUNT);
                queue.enqueueOrdered(List.of(new ShardOwnedStorage.OrderedPayload("HOLD", 0, "HOLD-0".getBytes(StandardCharsets.UTF_8), 1)));
                assertThat(entered.await(20, TimeUnit.SECONDS)).as("premise: the key is in its handler").isTrue();
                var unit        = heldUnit.get();
                var doomed      = runtime.pumpFor(QUEUE_ID, "ordered", unit);
                var fenceBefore = fenceOf("ordered", unit);

                kill(doomed);

                Awaitility.await().atMost(Duration.ofSeconds(20)).untilAsserted(() -> {
                    assertThat(fenceOf("ordered", unit)).as("handed back past the bound and taken again").isGreaterThan(fenceBefore);
                    assertThat(entries.get()).as("the stuck key's message delivered again beside the hung handler").isEqualTo(2);
                });
                assertThat(release.getCount()).as("premise: the first handler is still held").isEqualTo(1);
                assertThat(runtime.pumpFor(QUEUE_ID, "ordered", unit).isDead()).as("taken again on a live pump").isFalse();
                assertThat(queue.metrics().shardsOnDeadPumps.get()).isZero();
                assertThat(queue.metrics().keptUnitsInHandler()).as("counted while the stuck handler runs on").isEqualTo(1);
                // The delivery in the hung handler is an attempt: the hand-back charges it before it releases the row, and the
                // takeover after it, finding the row released, charges nothing. Uncharged, a message whose handler hung on every
                // delivery never reached the dead-letter table.
                assertThat(queue.metrics().takeoverAttemptBumps.sum()).as("the hand-back charged the message in the hung handler once")
                                                                     .isEqualTo(1);
                assertThat(attemptsOnRedelivery.get(5, TimeUnit.SECONDS)).as("the message is redelivered with that one attempt counted")
                                                                         .isEqualTo(1);
                var stuckWarnings = warnings(logged, "so those handlers were not waited for");
                assertThat(stuckWarnings).as("one WARN for the hand-back").hasSize(1);
                assertThat(stuckWarnings.getFirst().getArgumentArray()[1]).as("the unit").isEqualTo(unit);
                assertThat(stuckWarnings.getFirst().getFormattedMessage()).contains("on queue " + QUEUE_ID + " from its dead pump")
                                                                          .contains("1 key(s) [HOLD] still in a handler")
                                                                          .contains("stuckHandlerTimeout (PT3S)");

                release.countDown();
                // Since the handler returned, no read of the gauge, which prunes the set too: only the heartbeat's prune can empty it here.
                Awaitility.await().atMost(Duration.ofSeconds(10)).untilAsserted(
                        () -> assertThat(queue.releasedPastStuckHandlerSize()).as("pruned by the heartbeat once the handler returned").isZero());
                Awaitility.await().atMost(Duration.ofSeconds(10)).untilAsserted(
                        () -> assertThat(queue.metrics().keptUnitsInHandler()).as("no longer once the handler returned").isZero());
            } finally {
                release.countDown();
                queue.stop();
            }
        } finally {
            releaseQueueLog(logged);
        }
    }

    /**
     * The hand-back past {@code stuckHandlerTimeout} charges only the messages in a handler longer than that bound, as the
     * unordered lane does: another key of the unit that entered its handler moments before the hand-back is taken to be
     * slow, not hung, and released uncharged. It used to be charged too, so with that key's message at its last attempt but
     * one - a fresh message with {@code maxAttempts} 1 here - the release dead-lettered it with
     * {@code EXHAUSTED_IN_A_RELEASED_HANDLER} though its handler was healthy, and blocked the key behind the dead letter.
     */
    @Test
    void a_dead_pumps_unit_handed_back_past_the_stuck_handler_timeout_does_not_charge_a_key_that_entered_its_handler_just_before() throws Exception {
        // Room for the young key to stay under the bound through the pump's death and the heartbeat tick that hands the unit back.
        var stuckHandlerTimeout = Duration.ofSeconds(5);
        var settings            = settings(2, Duration.ofMillis(300), Duration.ofSeconds(1), stuckHandlerTimeout);
        var stuckEntered        = new CountDownLatch(1);
        var youngEntered        = new CountDownLatch(1);
        var release             = new CountDownLatch(1);
        var heldUnit            = new CompletableFuture<Integer>();
        var youngKey            = new CompletableFuture<String>();
        var youngEntries        = new AtomicInteger();
        var youngAttemptsOnRedelivery = new CompletableFuture<Integer>();
        var logged              = captureQueueLog();
        try (var runtime = new ShardRuntime(dataSource, settings)) {
            var queue = queue(runtime, "dead-pump-young");
            try {
                queue.startConsumingOrdered((messageId, key, payload, payloadType) -> {
                    if ("HOLD".equals(key) && heldUnit.complete(messageId.shard())) {
                        stuckEntered.countDown();
                        awaitQuietly(release);
                    } else if (youngKey.isDone() && youngKey.getNow(null).equals(key)) {
                        if (youngEntries.incrementAndGet() == 1) {
                            youngEntered.countDown();
                            awaitQuietly(release);
                        } else {
                            youngAttemptsOnRedelivery.complete(attemptsOf(messageId.sequence()));
                        }
                    }
                }, settings, SHARD_COUNT, RedeliveryPolicy.fixed(Duration.ofMillis(100), 1));
                queue.enqueueOrdered(List.of(new ShardOwnedStorage.OrderedPayload("HOLD", 0, "HOLD-0".getBytes(StandardCharsets.UTF_8), 1)));
                assertThat(stuckEntered.await(20, TimeUnit.SECONDS)).as("premise: the stuck key is in its handler").isTrue();
                int unit        = heldUnit.get();
                var doomed      = runtime.pumpFor(QUEUE_ID, "ordered", unit);
                var fenceBefore = fenceOf("ordered", unit);
                // The stuck key past the bound before the young key enters its handler, and the pump killed at once after:
                // the first hand-back after the death finds the one past the bound and the other in its handler for well under it.
                Thread.sleep(stuckHandlerTimeout.toMillis() + 500);
                youngKey.complete(keyInUnit(unit, "HOLD"));
                queue.enqueueOrdered(List.of(new ShardOwnedStorage.OrderedPayload(youngKey.get(), 0, "YOUNG-0".getBytes(StandardCharsets.UTF_8), 1)));
                assertThat(youngEntered.await(20, TimeUnit.SECONDS)).as("premise: the young key is in its handler, in the same unit").isTrue();
                var youngEnteredAtNanos = System.nanoTime();

                kill(doomed);

                Awaitility.await().atMost(Duration.ofSeconds(20)).untilAsserted(() -> {
                    assertThat(fenceOf("ordered", unit)).as("handed back past the bound and taken again").isGreaterThan(fenceBefore);
                    assertThat(youngEntries.get()).as("the young key's message delivered again by the next owner").isEqualTo(2);
                });
                var stuckWarnings = warnings(logged, "so those handlers were not waited for");
                assertThat(stuckWarnings).as("one WARN for the hand-back").singleElement().satisfies(warning -> {
                    assertThat(warning.getArgumentArray()[1]).as("the unit").isEqualTo(unit);
                    assertThat(warning.getFormattedMessage()).contains("from its dead pump with 2 key(s)")
                                                             .contains("1 message(s) in a handler longer than stuckHandlerTimeout were charged an attempt",
                                                                       "1 dead-lettered at their attempt limit [HOLD]");
                });
                assertThat(Duration.ofNanos(System.nanoTime() - youngEnteredAtNanos)).as("premise: the young key was still under the bound at the hand-back")
                                                                                   .isLessThan(stuckHandlerTimeout);
                assertThat(release.getCount()).as("premise: both handlers are still held").isEqualTo(1);
                assertThat(queue.metrics().takeoverAttemptBumps.sum()).as("only the message in a handler past the bound was charged").isEqualTo(1);
                assertThat(queue.deadLetters()).as("only that message reached its attempt limit").singleElement()
                                               .satisfies(deadLetter -> {
                                                   assertThat(deadLetter.key()).isEqualTo("HOLD");
                                                   assertThat(deadLetter.error()).isEqualTo(ShardOwnedStorage.EXHAUSTED_IN_A_RELEASED_HANDLER);
                                               });
                assertThat(youngAttemptsOnRedelivery.get(5, TimeUnit.SECONDS)).as("the young key's message is redelivered uncharged").isZero();
            } finally {
                release.countDown();
                queue.stop();
            }
        } finally {
            releaseQueueLog(logged);
        }
    }

    /**
     * A dead pump's hand-back past {@code stuckHandlerTimeout} whose release is refused, and retried on every heartbeat,
     * charges each message in a handler once per owner: the retries do not charge the stuck key's message again, and the
     * one that runs after the young key has passed the bound too charges that key's message, which the first hand-back
     * left uncharged. Both rows reach the next owner with one attempt each. With a charge made only once per owner, the
     * young key's message was released uncharged however long its handler had then run; with no memo, every retry
     * charged the stuck key's message again.
     */
    @Test
    void a_dead_pumps_unit_whose_release_is_refused_charges_on_a_retry_a_key_that_has_passed_the_stuck_handler_timeout_since_and_not_the_one_charged_before() throws Exception {
        var stuckHandlerTimeout = Duration.ofSeconds(5);
        var settings            = settings(2, Duration.ofMillis(300), Duration.ofSeconds(1), stuckHandlerTimeout);
        var stuckEntered        = new CountDownLatch(1);
        var youngEntered        = new CountDownLatch(1);
        var release             = new CountDownLatch(1);
        var heldUnit            = new CompletableFuture<Integer>();
        var youngKey            = new CompletableFuture<String>();
        var seqByKey            = new ConcurrentHashMap<String, Long>();
        var attemptsOnRedelivery = new ConcurrentHashMap<String, Integer>();
        var logged              = captureQueueLog();
        try (var runtime = new ShardRuntime(dataSource, settings)) {
            var queue = queue(runtime, "dead-pump-retry-charge");
            try {
                queue.startConsumingOrdered((messageId, key, payload, payloadType) -> {
                    var young = youngKey.isDone() && youngKey.getNow(null).equals(key);
                    if (!"HOLD".equals(key) && !young) {
                        return;
                    }
                    if (seqByKey.putIfAbsent(key, messageId.sequence()) == null) {
                        if (young) {
                            youngEntered.countDown();
                        } else {
                            heldUnit.complete(messageId.shard());
                            stuckEntered.countDown();
                        }
                        awaitQuietly(release);
                    } else {
                        attemptsOnRedelivery.putIfAbsent(key, attemptsOf(messageId.sequence()));
                    }
                }, settings, SHARD_COUNT, RedeliveryPolicy.fixed(Duration.ofMillis(100), 3));
                queue.enqueueOrdered(List.of(new ShardOwnedStorage.OrderedPayload("HOLD", 0, "HOLD-0".getBytes(StandardCharsets.UTF_8), 1)));
                assertThat(stuckEntered.await(20, TimeUnit.SECONDS)).as("premise: the stuck key is in its handler").isTrue();
                int unit        = heldUnit.get();
                var doomed      = runtime.pumpFor(QUEUE_ID, "ordered", unit);
                var fenceBefore = fenceOf("ordered", unit);
                // The stuck key past the bound before the young key enters its handler, as in the test above.
                Thread.sleep(stuckHandlerTimeout.toMillis() + 500);
                youngKey.complete(keyInUnit(unit, "HOLD"));
                queue.enqueueOrdered(List.of(new ShardOwnedStorage.OrderedPayload(youngKey.get(), 0, "YOUNG-0".getBytes(StandardCharsets.UTF_8), 1)));
                assertThat(youngEntered.await(20, TimeUnit.SECONDS)).as("premise: the young key is in its handler, in the same unit").isTrue();
                var youngEnteredAtNanos = System.nanoTime();

                failLeaseReleases("ordered", true);
                try {
                    kill(doomed);

                    // Each refusal follows a charge that went through: the trigger refuses the lease row's release, not the charge.
                    awaitReleasesRefused(QUEUE_ID, List.of(unit), "while the young key is under the bound");
                    assertThat(Duration.ofNanos(System.nanoTime() - youngEnteredAtNanos)).as("premise: the young key was still under the bound at those hand-backs")
                                                                                       .isLessThan(stuckHandlerTimeout);
                    assertThat(queue.metrics().takeoverAttemptBumps.sum()).as("the stuck key's message charged once, not on every retry, the young one's not yet")
                                                                         .isEqualTo(1);
                    assertThat(attemptsOf(seqByKey.get("HOLD"))).as("the stuck key's stored attempts").isEqualTo(1);
                    assertThat(attemptsOf(seqByKey.get(youngKey.get()))).as("the young key's stored attempts").isZero();

                    Awaitility.await("the young key's message charged by a retry once past the bound").atMost(Duration.ofSeconds(20))
                              .until(() -> queue.metrics().takeoverAttemptBumps.sum() >= 2);
                    assertThat(fenceOf("ordered", unit)).as("premise: still held, every release refused").isEqualTo(fenceBefore);
                } finally {
                    failLeaseReleases("ordered", false);
                }

                Awaitility.await().atMost(Duration.ofSeconds(20)).untilAsserted(() -> {
                    assertThat(fenceOf("ordered", unit)).as("handed back once the release went through, and taken again").isGreaterThan(fenceBefore);
                    assertThat(attemptsOnRedelivery).as("both messages delivered again by the next owner").containsOnlyKeys("HOLD", youngKey.get());
                });
                assertThat(release.getCount()).as("premise: both handlers are still held").isEqualTo(1);
                assertThat(queue.metrics().takeoverAttemptBumps.sum()).as("each message charged once across the refused releases").isEqualTo(2);
                assertThat(attemptsOnRedelivery).as("each row reaches the next owner with one attempt, the stuck key's not two")
                                                .containsEntry("HOLD", 1)
                                                .containsEntry(youngKey.get(), 1);
                assertThat(queue.deadLetters()).as("nothing reached its attempt limit").isEmpty();
                assertThat(warnings(logged, "so those handlers were not waited for")).as("one WARN, for the release that went through").singleElement()
                                                                                    .satisfies(warning -> assertThat(warning.getFormattedMessage())
                                                                                            .contains("from its dead pump with 2 key(s)")
                                                                                            .contains("2 message(s) in a handler longer than stuckHandlerTimeout were charged an attempt"));
            } finally {
                release.countDown();
                failLeaseReleases("ordered", false);
                queue.stop();
            }
        } finally {
            releaseQueueLog(logged);
        }
    }

    /**
     * A dead pump's ordered unit whose charge before its release past {@code stuckHandlerTimeout} fails - refused by the
     * database here - is left held, with one WARN per attempt naming it, and retried on the next heartbeat, while the dead
     * pump's other units are handed back all the same. A failed charge used to end the whole hand-back: no unit of the dead
     * pump was released, the ones with no key in a handler included, for as long as that charge kept failing. Once the
     * database lets the charge through, the held unit is handed back with the message in its handler charged once.
     */
    @Test
    void a_dead_pumps_unit_whose_charge_before_its_release_fails_is_held_while_the_pumps_other_units_are_handed_back() throws Exception {
        var stuckHandlerTimeout = Duration.ofSeconds(1);
        var settings            = settings(2, Duration.ofMillis(300), Duration.ofSeconds(1), stuckHandlerTimeout);
        var entered             = new CountDownLatch(1);
        var release             = new CountDownLatch(1);
        var entries             = new AtomicInteger();
        var heldUnit            = new CompletableFuture<Integer>();
        var logged              = captureQueueLog();
        try (var runtime = new ShardRuntime(dataSource, settings)) {
            var queue = queue(runtime, "dead-pump-charge");
            try {
                queue.startConsumingOrdered((messageId, key, payload, payloadType) -> {
                    if ("HOLD".equals(key) && entries.incrementAndGet() == 1) {
                        heldUnit.complete(messageId.shard());
                        entered.countDown();
                        awaitQuietly(release);
                    }
                }, settings, SHARD_COUNT);
                queue.enqueueOrdered(List.of(new ShardOwnedStorage.OrderedPayload("HOLD", 0, "HOLD-0".getBytes(StandardCharsets.UTF_8), 1)));
                assertThat(entered.await(20, TimeUnit.SECONDS)).as("premise: the key is in its handler").isTrue();
                int unit         = heldUnit.get();
                var doomed       = runtime.pumpFor(QUEUE_ID, "ordered", unit);
                var doomedUnits  = unitsOn(runtime, doomed, "ordered", ShardOwnedSchema.ORDERED_UNITS);
                var idleUnits    = doomedUnits.stream().filter(candidate -> candidate != unit).toList();
                var fencesBefore = fences("ordered", doomedUnits);
                assertThat(idleUnits).as("premise: the dead pump serves other units too").isNotEmpty();
                // Past the bound before the pump dies, so the first hand-back after the death finds the held unit stuck and
                // the idle units ready together.
                Thread.sleep(stuckHandlerTimeout.toMillis() + 500);

                failOrderedAttemptCharges(true);
                try {
                    kill(doomed);

                    Awaitility.await().atMost(Duration.ofSeconds(20)).untilAsserted(() -> {
                        for (var idle : idleUnits) {
                            assertThat(fenceOf("ordered", idle)).as("idle unit %d was handed back and taken again while the charge fails", idle)
                                                                .isGreaterThan(fencesBefore.get(idle));
                        }
                    });
                    var chargeFailures = warnings(logged, "could not charge the messages in a handler of ordered unit");
                    assertThat(chargeFailures).as("premise: the charge was attempted and refused by the database").isNotEmpty();
                    assertThat(chargeFailures.getFirst().getArgumentArray()[1]).as("the unit named").isEqualTo(unit);
                    assertThat(chargeFailures.getFirst().getThrowableProxy().getMessage()).contains("simulated failure charging");
                    assertThat(ownerOf("ordered", unit)).as("the unit whose charge failed is still leased").isEqualTo("dead-pump-charge");
                    assertThat(fenceOf("ordered", unit)).as("and not taken again").isEqualTo(fencesBefore.get(unit));
                    assertThat(queue.metrics().shardsOnDeadPumps.get()).as("it alone is still on the dead pump").isEqualTo(1);
                    assertThat(queue.metrics().takeoverAttemptBumps.sum()).as("nothing charged while the database refuses it").isZero();
                } finally {
                    failOrderedAttemptCharges(false);
                }

                Awaitility.await().atMost(Duration.ofSeconds(20)).untilAsserted(() -> {
                    assertThat(fenceOf("ordered", unit)).as("handed back once the charge goes through, and taken again").isGreaterThan(fencesBefore.get(unit));
                    assertThat(queue.metrics().shardsOnDeadPumps.get()).isZero();
                });
                assertThat(queue.metrics().takeoverAttemptBumps.sum()).as("the message in the hung handler charged once").isEqualTo(1);
            } finally {
                release.countDown();
                queue.stop();
            }
        } finally {
            releaseQueueLog(logged);
        }
    }

    /**
     * A {@code QueueObserver} that throws when told of the message the charge before a dead pump's hand-back dead-lettered
     * is not a failed charge: the charge and the move are made by then, so the unit is handed back all the same, with one
     * WARN naming what failed. It used to be reported as a charge that could not be made - "could not charge the messages
     * in a handler", unit held and the release retried on the next heartbeat - though the attempt was counted and the
     * message moved.
     */
    @Test
    void a_dead_pumps_unit_whose_charge_dead_lettered_its_message_is_handed_back_even_when_the_observer_told_of_it_throws() throws Exception {
        var stuckHandlerTimeout = Duration.ofSeconds(1);
        var settings            = settings(2, Duration.ofMillis(300), Duration.ofSeconds(1), stuckHandlerTimeout);
        var entered             = new CountDownLatch(1);
        var release             = new CountDownLatch(1);
        var heldUnit            = new CompletableFuture<Integer>();
        var told                = new AtomicInteger();
        var logged              = captureQueueLog();
        var observer = new dk.trustworks.essentials.components.queue.shardowned.spi.QueueObserver() {
            @Override
            public void deadLettered(dk.trustworks.essentials.components.queue.shardowned.spi.MessageId id, int attempts, Throwable cause) {
                told.incrementAndGet();
                throw new IllegalStateException("observer failure");
            }
        };
        try (var runtime = new ShardRuntime(dataSource, settings)) {
            var queue = ShardOwnedQueue.builder().setDataSource(dataSource).setQueueId(QUEUE_ID).setShardCount(SHARD_COUNT)
                                       .setInstanceId("dead-pump-observer").setRuntime(runtime)
                                       .setMetrics(new ShardOwnerMetrics(observer)).build();
            try {
                queue.startConsumingOrdered((messageId, key, payload, payloadType) -> {
                    if ("HOLD".equals(key) && heldUnit.complete(messageId.shard())) {
                        entered.countDown();
                        awaitQuietly(release);
                    }
                }, settings, SHARD_COUNT, RedeliveryPolicy.fixed(Duration.ofMillis(100), 1));
                queue.enqueueOrdered(List.of(new ShardOwnedStorage.OrderedPayload("HOLD", 0, "HOLD-0".getBytes(StandardCharsets.UTF_8), 1)));
                assertThat(entered.await(20, TimeUnit.SECONDS)).as("premise: the key is in its handler").isTrue();
                var unit        = heldUnit.get();
                var doomed      = runtime.pumpFor(QUEUE_ID, "ordered", unit);
                var fenceBefore = fenceOf("ordered", unit);
                // Past the bound before the pump dies, so the first hand-back after the death charges the message.
                Thread.sleep(stuckHandlerTimeout.toMillis() + 500);

                kill(doomed);

                Awaitility.await().atMost(Duration.ofSeconds(20)).untilAsserted(() -> {
                    assertThat(fenceOf("ordered", unit)).as("handed back and taken again").isGreaterThan(fenceBefore);
                    assertThat(queue.metrics().shardsOnDeadPumps.get()).isZero();
                });
                assertThat(told).as("premise: the observer was told of the dead letter").hasValue(1);
                assertThat(queue.deadLetters()).as("the charge dead-lettered the message").singleElement()
                                               .satisfies(deadLetter -> assertThat(deadLetter.error()).isEqualTo(ShardOwnedStorage.EXHAUSTED_IN_A_RELEASED_HANDLER));
                assertThat(queue.metrics().deadLettered.sum()).isEqualTo(1);
                assertThat(queue.metrics().takeoverAttemptBumps.sum()).as("charged once").isEqualTo(1);
                assertThat(warnings(logged, "could not charge the messages in a handler of ordered unit")).as("not reported as a failed charge")
                                                                                                         .isEmpty();
                assertThat(warnings(logged, "has nothing left to charge before releasing ordered unit")).singleElement().satisfies(warning -> {
                    assertThat(warning.getArgumentArray()[1]).as("the unit").isEqualTo(unit);
                    assertThat(warning.getFormattedMessage()).contains("on queue " + QUEUE_ID + " from its dead pump");
                    assertThat(warning.getThrowableProxy().getMessage()).isEqualTo("observer failure");
                });
                assertThat(warnings(logged, "so those handlers were not waited for")).as("one WARN for the hand-back").singleElement()
                                                                                    .satisfies(warning -> assertThat(warning.getFormattedMessage())
                                                                                            .contains("1 message(s) in a handler longer than stuckHandlerTimeout were charged an attempt",
                                                                                                      "1 dead-lettered at their attempt limit [HOLD]"));
            } finally {
                release.countDown();
                queue.stop();
            }
        } finally {
            releaseQueueLog(logged);
        }
    }

    /**
     * A dead pump's unordered shard is handed back at once, with whatever is in its handlers; a message in one longer than
     * {@code stuckHandlerTimeout} is charged its attempt first, once. A live pump here takes the shard again under a new
     * fence, charging nothing itself (the row was released), and delivers the message with that attempt counted. Released
     * uncharged, a message whose handler hung on every delivery never reached the dead-letter table.
     */
    @Test
    void an_unordered_shard_on_a_dead_pump_is_handed_back_with_the_message_in_its_handler_past_the_stuck_handler_timeout_charged_once() throws Exception {
        var stuckHandlerTimeout  = Duration.ofSeconds(1);
        var settings             = settings(2, Duration.ofMillis(300), Duration.ofSeconds(1), stuckHandlerTimeout);
        var entered              = new CountDownLatch(1);
        var release              = new CountDownLatch(1);
        var entries              = new AtomicInteger();
        var heldShard            = new CompletableFuture<Integer>();
        var attemptsOnRedelivery = new CompletableFuture<Integer>();
        var logged               = captureQueueLog();
        try (var runtime = new ShardRuntime(dataSource, settings)) {
            var queue = queue(runtime, "dead-pump-stuck-u");
            try {
                queue.startConsuming((messageId, payload, payloadType) -> {
                    if (entries.incrementAndGet() == 1) {
                        heldShard.complete(messageId.shard());
                        entered.countDown();
                        awaitQuietly(release);
                    } else {
                        attemptsOnRedelivery.complete(unorderedAttemptsOf(messageId.shard(), messageId.sequence()));
                    }
                }, settings, SHARD_COUNT);
                queue.enqueue(List.of("HOLD".getBytes(StandardCharsets.UTF_8)), 1);
                assertThat(entered.await(20, TimeUnit.SECONDS)).as("premise: the message is in its handler").isTrue();
                var shard       = heldShard.get();
                var fenceBefore = fenceOf("unordered", shard);
                Thread.sleep(stuckHandlerTimeout.toMillis() + 300);

                kill(runtime.pumpFor(QUEUE_ID, "unordered", shard));

                Awaitility.await().atMost(Duration.ofSeconds(20)).untilAsserted(() -> {
                    assertThat(fenceOf("unordered", shard)).as("handed back and taken again").isGreaterThan(fenceBefore);
                    assertThat(entries.get()).as("the message delivered again beside the hung handler").isEqualTo(2);
                });
                assertThat(release.getCount()).as("premise: the first handler is still held").isEqualTo(1);
                assertThat(queue.metrics().takeoverAttemptBumps.sum()).as("the hand-back charged the message in the hung handler once")
                                                                     .isEqualTo(1);
                assertThat(attemptsOnRedelivery.get(5, TimeUnit.SECONDS)).as("the message is redelivered with that one attempt counted")
                                                                         .isEqualTo(1);
                assertThat(warnings(logged, "released unordered shard")).as("one WARN for the hand-back").singleElement().satisfies(warning -> {
                    assertThat(warning.getArgumentArray()[1]).as("the shard").isEqualTo(shard);
                    assertThat(warning.getFormattedMessage()).contains("on queue " + QUEUE_ID + " from its dead pump having charged 1 message(s) that "
                                                                       + "were in a handler longer than stuckHandlerTimeout (PT1S) an attempt");
                });
            } finally {
                release.countDown();
                queue.stop();
            }
        } finally {
            releaseQueueLog(logged);
        }
    }

    /**
     * A dead pump's unordered shard whose charge before its release fails - refused by the database here - is left held, with
     * a WARN naming it, and retried on the next heartbeat, while the dead pump's other shards are handed back all the same.
     * Once the database lets the charge through, the held shard is handed back with the message in its handler charged once.
     */
    @Test
    void a_dead_pumps_unordered_shard_whose_charge_before_its_release_fails_is_held_while_the_pumps_other_shards_are_handed_back() throws Exception {
        var stuckHandlerTimeout = Duration.ofSeconds(1);
        var settings            = settings(2, Duration.ofMillis(300), Duration.ofSeconds(1), stuckHandlerTimeout);
        var entered             = new CountDownLatch(1);
        var release             = new CountDownLatch(1);
        var entries             = new AtomicInteger();
        var heldShard           = new CompletableFuture<Integer>();
        var logged              = captureQueueLog();
        try (var runtime = new ShardRuntime(dataSource, settings)) {
            var queue = queue(runtime, "dead-pump-charge-u");
            try {
                queue.startConsuming((messageId, payload, payloadType) -> {
                    if (entries.incrementAndGet() == 1) {
                        heldShard.complete(messageId.shard());
                        entered.countDown();
                        awaitQuietly(release);
                    }
                }, settings, SHARD_COUNT);
                queue.enqueue(List.of("HOLD".getBytes(StandardCharsets.UTF_8)), 1);
                assertThat(entered.await(20, TimeUnit.SECONDS)).as("premise: the message is in its handler").isTrue();
                int shard         = heldShard.get();
                var doomed        = runtime.pumpFor(QUEUE_ID, "unordered", shard);
                var doomedShards  = unitsOn(runtime, doomed, "unordered", SHARD_COUNT);
                var idleShards    = doomedShards.stream().filter(candidate -> candidate != shard).toList();
                var fencesBefore  = fences("unordered", doomedShards);
                assertThat(idleShards).as("premise: the dead pump serves other shards too").isNotEmpty();
                // Past the bound before the pump dies, so the first hand-back after the death finds the message stuck and the
                // idle shards ready together.
                Thread.sleep(stuckHandlerTimeout.toMillis() + 300);

                failUnorderedAttemptCharges(true);
                try {
                    kill(doomed);

                    Awaitility.await().atMost(Duration.ofSeconds(20)).untilAsserted(() -> {
                        for (var idle : idleShards) {
                            assertThat(fenceOf("unordered", idle)).as("idle shard %d was handed back and taken again while the charge fails", idle)
                                                                  .isGreaterThan(fencesBefore.get(idle));
                        }
                    });
                    var chargeFailures = warnings(logged, "could not charge the messages in a handler of unordered shard");
                    assertThat(chargeFailures).as("premise: the charge was attempted and refused by the database").isNotEmpty();
                    assertThat(chargeFailures.getFirst().getArgumentArray()[1]).as("the shard named").isEqualTo(shard);
                    assertThat(chargeFailures.getFirst().getThrowableProxy().getMessage()).contains("simulated failure charging");
                    assertThat(ownerOf("unordered", shard)).as("the shard whose charge failed is still leased").isEqualTo("dead-pump-charge-u");
                    assertThat(fenceOf("unordered", shard)).as("and not taken again").isEqualTo(fencesBefore.get(shard));
                    assertThat(queue.metrics().shardsOnDeadPumps.get()).as("it alone is still on the dead pump").isEqualTo(1);
                    assertThat(queue.metrics().takeoverAttemptBumps.sum()).as("nothing charged while the database refuses it").isZero();
                } finally {
                    failUnorderedAttemptCharges(false);
                }

                Awaitility.await().atMost(Duration.ofSeconds(20)).untilAsserted(() -> {
                    assertThat(fenceOf("unordered", shard)).as("handed back once the charge goes through, and taken again").isGreaterThan(fencesBefore.get(shard));
                    assertThat(queue.metrics().shardsOnDeadPumps.get()).isZero();
                });
                assertThat(queue.metrics().takeoverAttemptBumps.sum()).as("the message in the hung handler charged once").isEqualTo(1);
            } finally {
                release.countDown();
                failUnorderedAttemptCharges(false);
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
                int unit         = heldUnit.get();
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
                int unit         = heldUnit.get();
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
     * A queue stopped while one of its owners on a dead pump is left {@code HELD}, because ending it as the pump died threw,
     * flushes no acknowledgement for that owner: its state was kept by the thread a critical error ended, and is not
     * trusted to say what was handled.
     * <p>
     * A throw from ending an owner on a dying pump leaves the owner counted on the dead pump, awaiting hand-back, in the
     * state it was in ({@code ShardPump.endOnDeadPump}): {@code HELD}, which reads as serving. stop() picked the owners
     * whose acknowledgements it flushes by that state alone, so it flushed this one. A real owner's ending cannot be made
     * to throw, so a stand-in is handed to the dead pump and adopted by the queue with the bookkeeping a heartbeat tick's
     * acquisition does - listed, its acquisition counted ({@code ShardOwnedQueue.adoptPlacedOwner}).
     * Its unit is outside the queue's shard range, so it has no lease row to collide with a real one. A long lease keeps
     * the heartbeat from handing it back first.
     */
    @Test
    void a_queue_stopped_flushes_nothing_for_a_dead_pumps_owner_whose_ending_threw() throws Exception {
        var settings = settings(2, ShardOwnerSettings.defaults().shedGrace(), Duration.ofSeconds(60));
        try (var runtime = new ShardRuntime(dataSource, settings)) {
            var queue = queue(runtime, "dead-pump-stop-untrusted");
            try {
                queue.startConsuming((messageId, payload, payloadType) -> { }, settings, SHARD_COUNT);
                var doomed = runtime.pumpFor(QUEUE_ID, "unordered", 0);
                kill(doomed);
                var untrusted = new EndingThrowsOwner(SHARD_COUNT, 1);
                doomed.add(untrusted);
                assertThat(runtime.onDeadPump(untrusted)).as("premise: counted on the dead pump, awaiting hand-back").isTrue();
                assertThat(untrusted.leaseHeld()).as("premise: ending it threw, so it is still HELD").isTrue();
                queue.adoptPlacedOwner(untrusted, false);

                queue.stop();

                assertThat(runtime.isStarted()).as("premise: the runtime outlives the queue").isTrue();
                assertThat(untrusted.flushesOnStop.get()).as("no acknowledgement flushed for an owner on a dead pump").isZero();
                assertThat(queue.metrics().shardsOnDeadPumps.get()).as("the stopped queue counts nothing on the dead pump").isZero();
            } finally {
                queue.stop();
            }
        }
    }

    /**
     * A queue stopped while ending one of its owners throws every time still ends and releases every other owner, and
     * deregisters the instance. A dead pump's owner can be left {@code HELD} because ending it threw as the pump died
     * ({@code ShardPump.endOnDeadPump}), and ending it again on stop can throw again. Unguarded, that throw escaped stop()
     * and skipped everything after it: the owners after it in the list went on being served by the shared runtime, no
     * lease was released and the instance stayed registered. The stand-in is listed first, so it is the first owner the
     * stop ends; as in the test above, its unit is outside the queue's shard range.
     */
    @Test
    void a_queue_stopped_while_ending_a_dead_pumps_owner_keeps_throwing_still_ends_and_releases_the_others_and_deregisters() throws Exception {
        var settings = settings(2, ShardOwnerSettings.defaults().shedGrace(), Duration.ofSeconds(60));
        try (var runtime = new ShardRuntime(dataSource, settings)) {
            var queue = queue(runtime, "dead-pump-stop-throwing");
            try {
                queue.startConsuming((messageId, payload, payloadType) -> { }, settings, SHARD_COUNT);
                var others = queue.owners();
                assertThat(others).as("premise: one instance holds every shard").hasSize(SHARD_COUNT);
                var doomed = runtime.pumpFor(QUEUE_ID, "unordered", 0);
                kill(doomed);
                var throwing = new EndingThrowsOwner(SHARD_COUNT, Integer.MAX_VALUE);
                doomed.add(throwing);
                assertThat(runtime.onDeadPump(throwing)).as("premise: counted on the dead pump, awaiting hand-back").isTrue();
                assertThat(throwing.leaseHeld()).as("premise: ending it threw, so it is still HELD").isTrue();
                queue.adoptPlacedOwner(throwing, true);

                assertThatCode(queue::stop).as("stop() returns although ending one owner throws").doesNotThrowAnyException();

                assertThat(others).as("every other owner ended, and its lease row released")
                                  .allMatch(owner -> owner.leaseRow() == LeasedOwner.LeaseRow.RELEASED);
                assertThat(throwing.leaseRow()).as("the throwing owner's row is released with the others, which ends it")
                                               .isEqualTo(LeasedOwner.LeaseRow.RELEASED);
                assertThat(unitsOwnedBy("unordered", "dead-pump-stop-throwing")).as("every lease released").isZero();
                assertThat(membershipRows("dead-pump-stop-throwing")).as("the instance deregistered").isZero();
                assertThat(queue.metrics().shardsOnDeadPumps.get()).as("the stopped queue counts nothing on the dead pump").isZero();
                Awaitility.await().atMost(Duration.ofSeconds(5)).untilAsserted(() -> {
                    for (var owner : others) {
                        assertThat(runtime.pumpsServing(owner)).as("shard %d's owner is on no pump", owner.shard()).isEmpty();
                    }
                });
            } finally {
                queue.stop();
            }
        }
    }

    /**
     * A queue stopped after another instance took one of its dead pump's shards, before a rebalance dropped that shard,
     * stops counting it in {@code shardsOnDeadPumps} on a runtime that outlives the queue.
     * <p>
     * The heartbeat's lease check finds the shard taken and moves its owner to {@code RELEASED}, so it is not among the
     * owners whose rows the stop gives back, and only the rebalance's {@code removeIf} uncounted it. A stop that ran first,
     * or a rebalance that failed, left it counted until the runtime stopped: the stop told the runtime only about the owners
     * whose rows it gave back. Here every rebalance fails - the membership table is renamed away, and a rebalance first
     * counts the live instances in it - and every release is refused, so no hand-back uncounts the dead pump's shards
     * either.
     */
    @Test
    void a_queue_stopped_before_a_rebalance_drops_a_dead_pumps_taken_shard_stops_counting_it() throws Exception {
        var settings = settings(2);
        try (var runtime = new ShardRuntime(dataSource, settings)) {
            var queue = queue(runtime, "dead-pump-taken-stop");
            try {
                queue.startConsuming((messageId, payload, payloadType) -> { }, settings, SHARD_COUNT);
                assertThat(queue.shardsHeld()).as("premise: one instance holds every shard").isEqualTo(SHARD_COUNT);
                var doomed     = runtime.pumpFor(QUEUE_ID, "unordered", 0);
                var deadShards = unitsOn(runtime, doomed, "unordered", SHARD_COUNT);
                var taken      = (int) deadShards.getFirst();

                failLeaseReleases("unordered", true);
                kill(doomed);
                awaitReleasesRefused(QUEUE_ID, deadShards, "before the take");
                failRebalances(true);
                var lostBefore = queue.metrics().leasesLost.sum();

                takeAs("unordered", taken, "dead-pump-taker");

                Awaitility.await("the lease check finds shard " + taken + " taken").atMost(Duration.ofSeconds(20))
                          .until(() -> queue.metrics().leasesLost.sum() > lostBefore);
                assertThat(queue.metrics().shardsOnDeadPumps.get()).as("premise: no rebalance has dropped the taken shard")
                                                                  .isEqualTo(deadShards.size());
                assertThat(ownerOf("unordered", taken)).as("premise: not taken back").isEqualTo("dead-pump-taker");

                queue.stop();

                assertThat(runtime.isStarted()).as("premise: the runtime outlives the queue").isTrue();
                assertThat(queue.metrics().shardsOnDeadPumps.get()).as("the stopped queue counts nothing on the dead pump, the taken shard included")
                                                                  .isZero();
            } finally {
                failRebalances(false);
                failLeaseReleases("unordered", false);
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
                        var retaken = queue.owners().stream()
                                            .filter(owner -> owner.queueId() == QUEUE_ID && "unordered".equals(owner.lane()) && owner.shard() == taken)
                                            .toList();
                        assertThat(retaken).as("one owner of shard %d: the dead one was dropped", taken).hasSize(1);
                        assertThat(retaken.getFirst().leaseHeld()).as("the re-taken shard is served").isTrue();
                        assertThat(runtime.onDeadPump(retaken.getFirst())).as("the re-taken shard is not counted on the dead pump").isFalse();
                        assertThat(runtime.pumpsServing(retaken.getFirst())).as("taken back on a live pump")
                                                                             .singleElement()
                                                                             .satisfies(pump -> assertThat(pump.isDead()).isFalse());
                    });
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
        return settings(pumpThreads, shedGrace, leaseTtl, ShardOwnerSettings.DEFAULT_STUCK_HANDLER_TIMEOUT);
    }

    private static ShardOwnerSettings settings(int pumpThreads, Duration shedGrace, Duration leaseTtl, Duration stuckHandlerTimeout) {
        return ShardOwnerSettings.builder()
                                 .setPumpThreads(pumpThreads)
                                 .setShedGrace(shedGrace)
                                 .setLeaseTtl(leaseTtl)
                                 .setStuckHandlerTimeout(stuckHandlerTimeout)
                                 .build();
    }

    /**
     * Make every release of a lease row on {@code lane} fail, as a database error would, or stop doing so, counting each
     * refusal per queue and unit ({@link #releasesRefused}): {@link LeaseReleaseRefusals#failLeaseReleases} over every queue
     * registered here.
     */
    private void failLeaseReleases(String lane, boolean fail) throws Exception {
        LeaseReleaseRefusals.failLeaseReleases(dataSource, QUEUE_IDS, lane, fail);
    }

    /**
     * How many releases of {@code unit} of queue {@code queueId} {@link #failLeaseReleases} has refused since it was last
     * turned on.
     */
    private long releasesRefused(short queueId, int unit) throws Exception {
        return LeaseReleaseRefusals.releasesRefused(dataSource, queueId, unit);
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
     * Make every write that raises the attempts of an ordered row fail, as a database error would, or stop doing so: the
     * charge a release past {@code stuckHandlerTimeout} makes before it releases ({@code ShardOwnedStorage.chargeRowsInHandler}).
     */
    private void failOrderedAttemptCharges(boolean fail) throws Exception {
        try (var connection = dataSource.getConnection(); var statement = connection.createStatement()) {
            if (fail) {
                statement.execute("CREATE OR REPLACE FUNCTION fail_ordered_attempt_charge() RETURNS trigger AS $$ BEGIN "
                                          + "IF NEW.attempts > OLD.attempts THEN RAISE EXCEPTION 'simulated failure charging an ordered attempt'; END IF; "
                                          + "RETURN NEW; END $$ LANGUAGE plpgsql");
                statement.execute("CREATE TRIGGER fail_ordered_attempt_charge BEFORE UPDATE ON " + ShardOwnedSchema.ORDERED_TABLE
                                          + " FOR EACH ROW EXECUTE FUNCTION fail_ordered_attempt_charge()");
            } else {
                statement.execute("DROP TRIGGER IF EXISTS fail_ordered_attempt_charge ON " + ShardOwnedSchema.ORDERED_TABLE);
            }
        }
    }

    /**
     * Make every charge of an unordered message's attempt count fail, as a database error would, or stop doing so: the
     * unordered twin of {@link #failOrderedAttemptCharges}.
     */
    private void failUnorderedAttemptCharges(boolean fail) throws Exception {
        try (var connection = dataSource.getConnection(); var statement = connection.createStatement()) {
            if (fail) {
                statement.execute("CREATE OR REPLACE FUNCTION fail_unordered_attempt_charge() RETURNS trigger AS $$ BEGIN "
                                          + "IF NEW.attempts > OLD.attempts THEN RAISE EXCEPTION 'simulated failure charging an unordered attempt'; END IF; "
                                          + "RETURN NEW; END $$ LANGUAGE plpgsql");
                statement.execute("CREATE TRIGGER fail_unordered_attempt_charge BEFORE UPDATE ON " + ShardOwnedSchema.UNORDERED_TABLE
                                          + " FOR EACH ROW EXECUTE FUNCTION fail_unordered_attempt_charge()");
            } else {
                statement.execute("DROP TRIGGER IF EXISTS fail_unordered_attempt_charge ON " + ShardOwnedSchema.UNORDERED_TABLE);
            }
        }
    }

    /**
     * The stored attempt count of unordered message {@code seq} of {@code shard}, read from inside a handler: unchecked, as a
     * handler cannot throw a checked exception.
     */
    private int unorderedAttemptsOf(int shard, long seq) {
        try {
            return queryForInt(dataSource, "Reading the attempts of unordered message " + shard + "/" + seq,
                               "SELECT attempts FROM " + ShardOwnedSchema.UNORDERED_TABLE + " WHERE queue_id = ? AND shard = ? AND seq = ?",
                               QUEUE_ID, shard, seq);
        } catch (java.sql.SQLException e) {
            throw new IllegalStateException(e);
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

    /**
     * The stored attempt count of ordered message {@code seq}, read from inside a handler: unchecked, as a handler cannot throw
     * a checked exception.
     */
    private int attemptsOf(long seq) {
        try {
            return queryForInt(dataSource, "Reading the attempts of ordered message " + seq,
                               "SELECT attempts FROM " + ShardOwnedSchema.ORDERED_TABLE + " WHERE queue_id = ? AND seq = ?", QUEUE_ID, seq);
        } catch (java.sql.SQLException e) {
            throw new IllegalStateException(e);
        }
    }

    /**
     * A key other than {@code other} that routes to ordered {@code unit}, so its message is served by the same owner.
     */
    private static String keyInUnit(int unit, String other) {
        for (var index = 0; ; index++) {
            var candidate = "YOUNG-" + index;
            if (!candidate.equals(other) && ShardOwnedSchema.unitForKey(candidate, ShardOwnedSchema.ORDERED_UNITS) == unit) {
                return candidate;
            }
        }
    }

    private long fenceOf(short queueId, String lane, int shard) throws Exception {
        return queryForLong(dataSource, "Reading the fence of the lease row of queue " + queueId + " " + lane + " unit " + shard,
                            "SELECT fence FROM " + ShardOwnedSchema.LEASE_TABLE + " WHERE queue_id = ? AND lane = ? AND shard = ?",
                            queueId, lane, shard);
    }

    private String ownerOf(String lane, int shard) throws Exception {
        return ownerOf(QUEUE_ID, lane, shard);
    }

    private String ownerOf(short queueId, String lane, int shard) throws Exception {
        return queryForString(dataSource, "Reading the owner of the lease row of queue " + queueId + " " + lane + " unit " + shard,
                              "SELECT owner FROM " + ShardOwnedSchema.LEASE_TABLE + " WHERE queue_id = ? AND lane = ? AND shard = ?",
                              queueId, lane, shard);
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
        return queryForInt(dataSource, "Counting the instance rows of " + instanceId,
                           "SELECT count(*) FROM " + ShardOwnedSchema.INSTANCE_TABLE + " WHERE queue_id = ? AND instance_id = ?",
                           QUEUE_ID, instanceId);
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
        return queryForInt(dataSource, "Counting the " + lane + " lease rows held by " + instanceId,
                           "SELECT count(*) FROM " + ShardOwnedSchema.LEASE_TABLE + " WHERE queue_id = ? AND lane = ? AND owner = ?",
                           QUEUE_ID, lane, instanceId);
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
     * Make every rebalance of every queue fail, or stop doing so: the membership table is renamed away, and a rebalance
     * counts the live instances in it before anything else. The instance heartbeat and the stop's deregistration fail too,
     * and are logged and carried on from. Idempotent either way.
     */
    private void failRebalances(boolean fail) throws Exception {
        try (var connection = dataSource.getConnection(); var statement = connection.createStatement()) {
            if (fail) {
                statement.execute("ALTER TABLE " + ShardOwnedSchema.INSTANCE_TABLE + " RENAME TO " + ShardOwnedSchema.INSTANCE_TABLE + "_away");
            } else {
                statement.execute("ALTER TABLE IF EXISTS " + ShardOwnedSchema.INSTANCE_TABLE + "_away RENAME TO " + ShardOwnedSchema.INSTANCE_TABLE);
            }
        }
    }

    /**
     * An unordered owner of {@link #QUEUE_ID} whose first {@code throwsLeft} calls of {@code endKeepingLeaseRow} throw,
     * leaving it {@code HELD}, as a throw ending an owner on a dying pump leaves it; later calls end it. Counts its
     * {@code flushOnStop} calls.
     */
    private static final class EndingThrowsOwner implements LeasedOwner {
        private final    ShardOwnerMetrics metrics       = new ShardOwnerMetrics();
        private final    int               shard;
        private final    AtomicInteger     throwsLeft;
        private final    AtomicInteger     flushesOnStop = new AtomicInteger();
        private final    LeaseRowState     leaseRow      = new LeaseRowState();

        EndingThrowsOwner(int shard, int throwsLeft) {
            this.shard = shard;
            this.throwsLeft = new AtomicInteger(throwsLeft);
        }

        @Override
        public int pumpOnce(Connection connection) {
            return 0;
        }

        @Override
        public boolean needsAttention() {
            return false;
        }

        @Override
        public void onTakeover(Connection connection) {
        }

        @Override
        public void flushOnStop(Connection connection) {
            flushesOnStop.incrementAndGet();
        }

        @Override
        public long parkDeadlineMillis() {
            return Long.MAX_VALUE;
        }

        @Override
        public int shard() {
            return shard;
        }

        @Override
        public short queueId() {
            return QUEUE_ID;
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
            return leaseRow.get();
        }

        @Override
        public void endKeepingLeaseRow() {
            if (throwsLeft.getAndUpdate(left -> Math.max(0, left - 1)) > 0) {
                throw new IllegalStateException("simulated failure ending the owner");
            }
            leaseRow.advanceTo(LeaseRow.ENDED_ROW_HELD);
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
            leaseRow.advanceTo(LeaseRow.RELEASED);
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
