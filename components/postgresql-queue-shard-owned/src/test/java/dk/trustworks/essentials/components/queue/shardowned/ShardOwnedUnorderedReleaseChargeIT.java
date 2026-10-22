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
import org.slf4j.LoggerFactory;
import org.testcontainers.junit.jupiter.*;
import org.testcontainers.postgresql.PostgreSQLContainer;

import java.nio.charset.StandardCharsets;
import java.sql.SQLException;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

import static dk.trustworks.essentials.components.queue.shardowned.SingleRowQueries.*;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * An unordered shard released while a message is still in its handler - by {@code stop()} once its drain gives up, or by a
 * fair-share shed, which does not wait at all - charges that message an attempt first when it has been in its handler
 * longer than {@code stuckHandlerTimeout}, and dead-letters it at the redelivery limit
 * ({@code ShardOwner.chargeInHandlerBeforeRelease}). The dead-pump hand-back does the same; its tests are in
 * {@code ShardOwnedDeadPumpHandBackIT}.
 * <p>
 * Whoever takes a released shard next finds it released and charges nothing, and a handler that hangs never returns to
 * record its attempt. So released uncharged, a message whose handler hung on every delivery was delivered again after
 * every rebalance or restart with its count unchanged, never reached the dead-letter table, and each delivery left one more
 * handler thread hung, holding a handler permit. A message in a handler for less than the bound is released uncharged: its
 * handler is taken to be slow, not hung.
 */
@Testcontainers(disabledWithoutDocker = true)
class ShardOwnedUnorderedReleaseChargeIT {

    private static final short    QUEUE_ID              = 1;
    private static final int      SHARD_COUNT           = 4;
    private static final Duration STUCK_HANDLER_TIMEOUT = Duration.ofSeconds(1);

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
        ShardOwnedSchema.registerQueue(dataSource, QUEUE_ID, SHARD_COUNT);
    }

    @AfterEach
    void tearDown() {
        if (dataSource != null) {
            dataSource.close();
        }
    }

    /**
     * Each stop that releases the shard with the message in its hung handler past {@code stuckHandlerTimeout} charges it
     * one attempt; the stop that brings it to {@code maxAttempts} (2 here) dead-letters it, and the restart after it
     * delivers it no more.
     */
    @Test
    void a_message_whose_handler_hangs_on_every_delivery_is_dead_lettered_once_stops_past_the_stuck_handler_timeout_have_charged_its_attempts() throws Exception {
        var settings = settings(STUCK_HANDLER_TIMEOUT);
        var entries  = new AtomicInteger();
        var entered  = new Semaphore(0);
        var release  = new CountDownLatch(1);
        var seq      = new CompletableFuture<Long>();
        var logged   = captureQueueLog();
        try (var runtime = new ShardRuntime(dataSource, settings)) {
            var queue = queue(runtime, "hangs-on-stop");
            try {
                queue.startConsuming((messageId, payload, payloadType) -> {
                    // Every delivery hangs, until the test ends.
                    seq.complete(messageId.sequence());
                    entries.incrementAndGet();
                    entered.release();
                    awaitQuietly(release);
                }, settings, SHARD_COUNT, RedeliveryPolicy.fixed(Duration.ofMillis(100), 2));
                queue.enqueue(List.of("hangs".getBytes(StandardCharsets.UTF_8)), 1);

                for (var delivery = 1; delivery <= 2; delivery++) {
                    assertThat(entered.tryAcquire(20, TimeUnit.SECONDS)).as("premise: delivery " + delivery + " is in its handler").isTrue();
                    Thread.sleep(STUCK_HANDLER_TIMEOUT.toMillis() + 300);
                    queue.stop();
                    assertThat(queue.metrics().takeoverAttemptBumps.sum()).as("each stop charges the delivery in the hung handler once, "
                                                                              + "and the restart, finding the shard released, nothing")
                                                                          .isEqualTo(delivery);
                    queue.start();
                }

                Awaitility.await().atMost(Duration.ofSeconds(10)).untilAsserted(() -> {
                    assertThat(queue.deadLetters()).as("dead-lettered by the stop that brought it to maxAttempts").singleElement().satisfies(deadLetter -> {
                        assertThat(deadLetter.lane()).isEqualTo("unordered");
                        assertThat(deadLetter.seq()).isEqualTo(seq.get());
                        assertThat(deadLetter.attempts()).isEqualTo(2);
                        assertThat(deadLetter.error()).isEqualTo(ShardOwnedStorage.EXHAUSTED_IN_A_RELEASED_HANDLER);
                    });
                    assertThat(unorderedMessagesLeft()).as("nothing left to deliver").isZero();
                });
                Awaitility.await().during(Duration.ofMillis(1_500)).atMost(Duration.ofSeconds(5))
                          .untilAsserted(() -> assertThat(entries).as("not delivered a third time").hasValue(2));
                assertThat(queue.metrics().deadLettered.sum()).as("counted once").isEqualTo(1);
                assertThat(warnings(logged, "released unordered shard")).as("one WARN per stop that charged")
                                                                        .hasSize(2)
                                                                        .last()
                                                                        .satisfies(warning -> assertThat(warning.getFormattedMessage())
                                                                                .contains("as the queue stopped having charged 1 message(s) that were in a handler longer than stuckHandlerTimeout (PT1S) an attempt",
                                                                                          "1 dead-lettered at their attempt limit (seq [" + seq.get() + "])"));
            } finally {
                release.countDown();
                queue.stop();
            }
        } finally {
            releaseQueueLog(logged);
        }
    }

    /**
     * A message in its handler for less than {@code stuckHandlerTimeout} when a stop releases the shard is released
     * uncharged: the handler is taken to be slow, not hung, and the next delivery counts from the attempts the row had.
     */
    @Test
    void a_message_in_its_handler_for_less_than_the_stuck_handler_timeout_is_released_uncharged() throws Exception {
        var settings  = settings(Duration.ofSeconds(30));
        var entries   = new AtomicInteger();
        var entered   = new CountDownLatch(1);
        var release   = new CountDownLatch(1);
        var redelivered = new CompletableFuture<Integer>();
        var logged    = captureQueueLog();
        try (var runtime = new ShardRuntime(dataSource, settings)) {
            var queue = queue(runtime, "slow-on-stop");
            try {
                queue.startConsuming((messageId, payload, payloadType) -> {
                    if (entries.incrementAndGet() == 1) {
                        entered.countDown();
                        awaitQuietly(release);
                    } else {
                        redelivered.complete(attemptsOf(messageId.shard(), messageId.sequence()));
                    }
                }, settings, SHARD_COUNT, RedeliveryPolicy.fixed(Duration.ofMillis(100), 2));
                queue.enqueue(List.of("slow".getBytes(StandardCharsets.UTF_8)), 1);
                assertThat(entered.await(20, TimeUnit.SECONDS)).as("premise: the message is in its handler").isTrue();

                // Its drain gives up after shedGrace with the message still in its handler, and releases the shard.
                queue.stop();

                assertThat(queue.metrics().takeoverAttemptBumps.sum()).as("nothing charged: in its handler for less than the bound").isZero();
                queue.start();
                assertThat(redelivered.get(20, TimeUnit.SECONDS)).as("delivered again with no attempt counted").isZero();
                assertThat(queue.metrics().takeoverAttemptBumps.sum()).as("nor by the restart, which found the shard released").isZero();
                assertThat(warnings(logged, "released unordered shard")).as("no charge to report").isEmpty();
            } finally {
                release.countDown();
                queue.stop();
            }
        } finally {
            releaseQueueLog(logged);
        }
    }

    /**
     * A fair-share shed releases a shard at once, with whatever is in its handlers; a message there longer than
     * {@code stuckHandlerTimeout} is charged its attempt first, once, by the shedding instance. The instance taking the
     * shard finds it released, charges nothing, and delivers the message with that attempt counted.
     */
    @Test
    void a_fair_share_shed_charges_each_message_in_a_handler_past_the_stuck_handler_timeout_once_before_it_releases_the_shard() throws Exception {
        var settings    = settings(STUCK_HANDLER_TIMEOUT);
        var entered     = new CountDownLatch(SHARD_COUNT);
        var release     = new CountDownLatch(1);
        // By shard: sequence values are per shard, so each shard's one message has the same.
        var redelivered = new ConcurrentHashMap<Integer, Integer>();
        var logged      = captureQueueLog();
        try (var firstRuntime = new ShardRuntime(dataSource, settings); var secondRuntime = new ShardRuntime(dataSource, settings)) {
            var first  = queue(firstRuntime, "shed-first");
            var second = queue(secondRuntime, "shed-second");
            try {
                first.startConsuming((messageId, payload, payloadType) -> {
                    entered.countDown();
                    awaitQuietly(release);
                }, settings, SHARD_COUNT);
                assertThat(first.shardsHeld()).as("premise: alone, the first instance holds every shard").isEqualTo(SHARD_COUNT);
                // One message per shard: each enqueue call goes to the next shard.
                for (var message = 0; message < SHARD_COUNT; message++) {
                    first.enqueue(List.of(("hangs-" + message).getBytes(StandardCharsets.UTF_8)), 1);
                }
                assertThat(entered.await(20, TimeUnit.SECONDS)).as("premise: a message of every shard is in its handler").isTrue();
                Thread.sleep(STUCK_HANDLER_TIMEOUT.toMillis() + 300);

                second.startConsuming((messageId, payload, payloadType) -> redelivered.put(messageId.shard(),
                                                                                           attemptsOf(messageId.shard(), messageId.sequence())),
                                      settings, SHARD_COUNT);

                Awaitility.await().atMost(Duration.ofSeconds(30)).untilAsserted(() -> {
                    assertThat(first.shardsHeld()).as("the first sheds down to its fair share").isEqualTo(SHARD_COUNT / 2);
                    assertThat(second.shardsHeld()).isEqualTo(SHARD_COUNT / 2);
                    assertThat(redelivered).as("the shed shards' messages delivered again by the second").hasSize(SHARD_COUNT / 2);
                });
                assertThat(first.metrics().takeoverAttemptBumps.sum()).as("the shed charged each message in a hung handler of a shed shard once")
                                                                      .isEqualTo(SHARD_COUNT / 2);
                assertThat(second.metrics().takeoverAttemptBumps.sum()).as("released, not taken over: the second charges nothing").isZero();
                assertThat(redelivered.values()).as("each delivered again with that one attempt counted").containsOnly(1);
                assertThat(warnings(logged, "released unordered shard")).as("one WARN per shed shard")
                                                                        .hasSize(SHARD_COUNT / 2)
                                                                        .allSatisfy(warning -> assertThat(warning.getFormattedMessage())
                                                                                .contains("in a fair-share shed having charged 1 message(s) that were in a handler longer than stuckHandlerTimeout",
                                                                                          "0 dead-lettered at their attempt limit (seq [])"));
            } finally {
                release.countDown();
                second.stop();
                first.stop();
            }
        } finally {
            releaseQueueLog(logged);
        }
    }

    /**
     * The shed loop's charge failure is its own shard's: a shed shard whose charge before its release fails - refused by the
     * database here - stays leased to the incumbent, with a WARN naming it on every attempt, while the shard after it in the
     * shed is charged, released and taken by the joining instance. Rethrown, the failure would end the whole loop, and no
     * shard after it would be released for as long as the charge kept failing. Once the database lets the charge through,
     * the held shard is released too, and its message is delivered again with the one attempt counted.
     * <p>
     * The incumbent acquired the shards in order, so it sheds the upper half, the failing shard first.
     */
    @Test
    void a_shed_shard_whose_charge_before_its_release_fails_is_held_while_a_shard_shed_after_it_is_released() throws Exception {
        var settings     = settings(STUCK_HANDLER_TIMEOUT);
        var failingShard = SHARD_COUNT / 2;
        var laterShard   = SHARD_COUNT - 1;
        var entered      = new CountDownLatch(SHARD_COUNT);
        var release      = new CountDownLatch(1);
        var redelivered  = new ConcurrentHashMap<Integer, Integer>();
        var logged       = captureQueueLog();
        try (var firstRuntime = new ShardRuntime(dataSource, settings); var secondRuntime = new ShardRuntime(dataSource, settings)) {
            var first  = queue(firstRuntime, "shed-fail-first");
            var second = queue(secondRuntime, "shed-fail-second");
            try {
                first.startConsuming((messageId, payload, payloadType) -> {
                    entered.countDown();
                    awaitQuietly(release);
                }, settings, SHARD_COUNT);
                assertThat(first.shardsHeld()).as("premise: alone, the first instance holds every shard").isEqualTo(SHARD_COUNT);
                for (var message = 0; message < SHARD_COUNT; message++) {
                    first.enqueue(List.of(("hangs-" + message).getBytes(StandardCharsets.UTF_8)), 1);
                }
                assertThat(entered.await(20, TimeUnit.SECONDS)).as("premise: a message of every shard is in its handler").isTrue();
                Thread.sleep(STUCK_HANDLER_TIMEOUT.toMillis() + 300);
                var failingFence = fenceOf(failingShard);

                failUnorderedAttemptChargesOn(failingShard, true);
                try {
                    second.startConsuming((messageId, payload, payloadType) -> redelivered.put(messageId.shard(),
                                                                                               attemptsOf(messageId.shard(), messageId.sequence())),
                                          settings, SHARD_COUNT);

                    Awaitility.await().atMost(Duration.ofSeconds(30)).untilAsserted(() -> {
                        assertThat(chargeFailuresOf(logged, failingShard, "in a fair-share shed")).as("premise: the charge was refused").isNotEmpty();
                        assertThat(ownerOf(laterShard)).as("the shard shed after the failing one is released and taken by the joining instance")
                                                       .isEqualTo("shed-fail-second");
                        assertThat(redelivered).as("and its message delivered again").containsKey(laterShard);
                    });
                    assertThat(chargeFailuresOf(logged, failingShard, "in a fair-share shed").getFirst().getThrowableProxy().getMessage())
                            .contains("simulated failure charging");
                    assertThat(ownerOf(failingShard)).as("the shard whose charge failed is still leased to the incumbent").isEqualTo("shed-fail-first");
                    assertThat(fenceOf(failingShard)).as("and not taken").isEqualTo(failingFence);
                    assertThat(first.metrics().takeoverAttemptBumps.sum()).as("only the shard shed after it charged").isEqualTo(1);
                    assertThat(redelivered.get(laterShard)).as("with that attempt counted").isEqualTo(1);
                } finally {
                    failUnorderedAttemptChargesOn(failingShard, false);
                }

                Awaitility.await().atMost(Duration.ofSeconds(20)).untilAsserted(() -> {
                    assertThat(ownerOf(failingShard)).as("released once the charge goes through").isEqualTo("shed-fail-second");
                    assertThat(first.shardsHeld()).isEqualTo(SHARD_COUNT / 2);
                    assertThat(second.shardsHeld()).isEqualTo(SHARD_COUNT / 2);
                    assertThat(redelivered).containsKey(failingShard);
                });
                assertThat(redelivered.get(failingShard)).as("its message delivered again with the one attempt counted").isEqualTo(1);
                assertThat(first.metrics().takeoverAttemptBumps.sum()).isEqualTo(SHARD_COUNT / 2);
            } finally {
                failUnorderedAttemptChargesOn(failingShard, false);
                release.countDown();
                second.stop();
                first.stop();
            }
        } finally {
            releaseQueueLog(logged);
        }
    }

    /**
     * A shed whose charge brings a message to the redelivery limit and whose dead-letter move of it then fails - refused by
     * the database here - still owes that move, so the shard is held, with a WARN naming it on every attempt: released, the
     * message would be left queued, charged to its limit. The retries on later heartbeats make the move and do not charge the
     * message again, so once the database lets the move through the shard is released with the message dead-lettered at the
     * one attempt the shed charged.
     * <p>
     * Only the message of the first shard shed hangs; the other messages are handled at once.
     */
    @Test
    void a_shed_shard_whose_dead_letter_move_after_the_charge_fails_is_held_until_the_move_is_made_and_its_message_is_charged_once() throws Exception {
        var settings   = settings(STUCK_HANDLER_TIMEOUT);
        var hungShard  = SHARD_COUNT / 2;
        var laterShard = SHARD_COUNT - 1;
        var handled    = new CountDownLatch(SHARD_COUNT);
        var release    = new CountDownLatch(1);
        var hungSeq    = new CompletableFuture<Long>();
        var logged     = captureQueueLog();
        try (var firstRuntime = new ShardRuntime(dataSource, settings); var secondRuntime = new ShardRuntime(dataSource, settings)) {
            var first  = queue(firstRuntime, "move-fail-first");
            var second = queue(secondRuntime, "move-fail-second");
            try {
                first.startConsuming((messageId, payload, payloadType) -> {
                    if (messageId.shard() == hungShard) {
                        hungSeq.complete(messageId.sequence());
                        handled.countDown();
                        awaitQuietly(release);
                    } else {
                        handled.countDown();
                    }
                }, settings, SHARD_COUNT, RedeliveryPolicy.fixed(Duration.ofMillis(100), 1));
                assertThat(first.shardsHeld()).as("premise: alone, the first instance holds every shard").isEqualTo(SHARD_COUNT);
                for (var message = 0; message < SHARD_COUNT; message++) {
                    first.enqueue(List.of(("message-" + message).getBytes(StandardCharsets.UTF_8)), 1);
                }
                assertThat(handled.await(20, TimeUnit.SECONDS)).as("premise: every message reached its handler").isTrue();
                assertThat(hungSeq).as("premise: a message of the first shard shed hangs").isDone();
                Thread.sleep(STUCK_HANDLER_TIMEOUT.toMillis() + 300);
                var hungFence = fenceOf(hungShard);

                failDeadLetterMoves(true);
                try {
                    second.startConsuming((messageId, payload, payloadType) -> { }, settings, SHARD_COUNT);

                    Awaitility.await().atMost(Duration.ofSeconds(30)).untilAsserted(() -> {
                        assertThat(chargeFailuresOf(logged, hungShard, "in a fair-share shed")).as("the move was refused, and retried on a later heartbeat")
                                                                                             .hasSizeGreaterThanOrEqualTo(2);
                        assertThat(ownerOf(laterShard)).as("premise: the shed ran").isEqualTo("move-fail-second");
                    });
                    assertThat(chargeFailuresOf(logged, hungShard, "in a fair-share shed").getFirst().getThrowableProxy().getMessage())
                            .contains("simulated failure moving to the dead-letter table");
                    assertThat(ownerOf(hungShard)).as("the shard whose dead-letter move is still owed is still leased to the incumbent")
                                                  .isEqualTo("move-fail-first");
                    assertThat(fenceOf(hungShard)).as("and not taken").isEqualTo(hungFence);
                    assertThat(first.metrics().takeoverAttemptBumps.sum()).as("charged once, however often the move is retried").isEqualTo(1);
                    assertThat(first.deadLetters()).as("premise: nothing moved yet").isEmpty();
                } finally {
                    failDeadLetterMoves(false);
                }

                Awaitility.await().atMost(Duration.ofSeconds(20))
                          .untilAsserted(() -> assertThat(ownerOf(hungShard)).as("released once the move is made").isEqualTo("move-fail-second"));
                assertThat(first.deadLetters()).as("dead-lettered by the retried move").singleElement().satisfies(deadLetter -> {
                    assertThat(deadLetter.shard()).isEqualTo(hungShard);
                    assertThat(deadLetter.seq()).isEqualTo(hungSeq.get());
                    assertThat(deadLetter.attempts()).as("the one attempt the shed charged").isEqualTo(1);
                    assertThat(deadLetter.error()).isEqualTo(ShardOwnedStorage.EXHAUSTED_IN_A_RELEASED_HANDLER);
                });
                assertThat(first.metrics().takeoverAttemptBumps.sum()).as("only the shed shard with a hung message charged, once").isEqualTo(1);
                assertThat(first.metrics().deadLettered.sum()).isEqualTo(1);
                assertThat(warnings(logged, "released unordered shard")).as("one WARN for the release that charged").singleElement()
                                                                        .satisfies(warning -> assertThat(warning.getFormattedMessage())
                                                                                .contains("1 dead-lettered at their attempt limit (seq [" + hungSeq.get() + "])"));
            } finally {
                failDeadLetterMoves(false);
                release.countDown();
                second.stop();
                first.stop();
            }
        } finally {
            releaseQueueLog(logged);
        }
    }

    /**
     * A stop whose charge before releasing a shard fails - refused by the database here - leaves that shard unreleased, with
     * one WARN naming it, and releases the rest: no heartbeat follows a stop to retry it, and released uncharged its message
     * would be delivered again with its count unchanged. Whoever takes the shard next finds it not released, so its takeover
     * charges the message, as after a crash.
     */
    @Test
    void a_stop_whose_charge_before_releasing_a_shard_fails_leaves_that_shard_unreleased_and_releases_the_others() throws Exception {
        var settings    = settings(STUCK_HANDLER_TIMEOUT);
        var entered     = new CountDownLatch(1);
        var release     = new CountDownLatch(1);
        var heldShard   = new CompletableFuture<Integer>();
        var redelivered = new CompletableFuture<Integer>();
        var logged      = captureQueueLog();
        try (var firstRuntime = new ShardRuntime(dataSource, settings); var secondRuntime = new ShardRuntime(dataSource, settings)) {
            var first  = queue(firstRuntime, "stop-fail-first");
            var second = queue(secondRuntime, "stop-fail-second");
            Integer failingShard = null;
            try {
                first.startConsuming((messageId, payload, payloadType) -> {
                    heldShard.complete(messageId.shard());
                    entered.countDown();
                    awaitQuietly(release);
                }, settings, SHARD_COUNT);
                assertThat(first.shardsHeld()).as("premise: alone, the first instance holds every shard").isEqualTo(SHARD_COUNT);
                first.enqueue(List.of("hangs".getBytes(StandardCharsets.UTF_8)), 1);
                assertThat(entered.await(20, TimeUnit.SECONDS)).as("premise: the message is in its handler").isTrue();
                failingShard = heldShard.get();
                var shard = failingShard;
                Thread.sleep(STUCK_HANDLER_TIMEOUT.toMillis() + 300);
                var fenceBefore = fenceOf(shard);

                failUnorderedAttemptChargesOn(shard, true);
                try {
                    first.stop();

                    assertThat(chargeFailuresOf(logged, shard, "as the queue stopped")).as("one WARN naming the shard").singleElement()
                                                                                    .satisfies(warning -> assertThat(warning.getThrowableProxy().getMessage())
                                                                                            .contains("simulated failure charging"));
                    assertThat(ownerOf(shard)).as("the shard whose charge failed is left unreleased").isEqualTo("stop-fail-first");
                    assertThat(fenceOf(shard)).as("under its fence").isEqualTo(fenceBefore);
                    for (var other = 0; other < SHARD_COUNT; other++) {
                        if (other != shard) {
                            assertThat(ownerOf(other)).as("shard %d released", other).isNull();
                        }
                    }
                    assertThat(first.metrics().takeoverAttemptBumps.sum()).as("nothing charged while the database refuses it").isZero();
                } finally {
                    failUnorderedAttemptChargesOn(shard, false);
                }

                second.startConsuming((messageId, payload, payloadType) -> {
                    if (messageId.shard() == shard) {
                        redelivered.complete(attemptsOf(messageId.shard(), messageId.sequence()));
                    }
                }, settings, SHARD_COUNT);
                assertThat(redelivered.get(30, TimeUnit.SECONDS)).as("delivered again with the attempt the takeover charged").isEqualTo(1);
                assertThat(second.metrics().takeoverAttemptBumps.sum()).as("charged by the unclean takeover").isEqualTo(1);
            } finally {
                if (failingShard != null) {
                    failUnorderedAttemptChargesOn(failingShard, false);
                }
                release.countDown();
                second.stop();
                first.stop();
            }
        } finally {
            releaseQueueLog(logged);
        }
    }

    /**
     * A {@code QueueObserver} that throws when told of a message the charge before a stop's release dead-lettered does not
     * keep the shard: the charge and the move are made by then, so it is released all the same, with a WARN saying what
     * failed. Held, it would wait for an unclean takeover that charges again what this charge charged.
     */
    @Test
    void a_stop_releases_a_shard_whose_charge_dead_lettered_a_message_even_when_the_observer_told_of_it_throws() throws Exception {
        var settings  = settings(STUCK_HANDLER_TIMEOUT);
        var entered   = new CountDownLatch(1);
        var release   = new CountDownLatch(1);
        var heldShard = new CompletableFuture<Integer>();
        var told      = new AtomicInteger();
        var logged    = captureQueueLog();
        var observer = new dk.trustworks.essentials.components.queue.shardowned.spi.QueueObserver() {
            @Override
            public void deadLettered(dk.trustworks.essentials.components.queue.shardowned.spi.MessageId id, int attempts, Throwable cause) {
                told.incrementAndGet();
                throw new IllegalStateException("observer failure");
            }
        };
        try (var runtime = new ShardRuntime(dataSource, settings)) {
            var queue = ShardOwnedQueue.builder().setDataSource(dataSource).setQueueId(QUEUE_ID).setShardCount(SHARD_COUNT)
                                       .setInstanceId("observer-throws").setRuntime(runtime)
                                       .setMetrics(new ShardOwnerMetrics(observer)).build();
            try {
                queue.startConsuming((messageId, payload, payloadType) -> {
                    heldShard.complete(messageId.shard());
                    entered.countDown();
                    awaitQuietly(release);
                }, settings, SHARD_COUNT, RedeliveryPolicy.fixed(Duration.ofMillis(100), 1));
                queue.enqueue(List.of("hangs".getBytes(StandardCharsets.UTF_8)), 1);
                assertThat(entered.await(20, TimeUnit.SECONDS)).as("premise: the message is in its handler").isTrue();
                var shard = heldShard.get();
                Thread.sleep(STUCK_HANDLER_TIMEOUT.toMillis() + 300);

                queue.stop();

                assertThat(told).as("premise: the observer was told of the dead letter").hasValue(1);
                assertThat(queue.deadLetters()).as("the charge dead-lettered the message").singleElement()
                                               .satisfies(deadLetter -> assertThat(deadLetter.error()).isEqualTo(ShardOwnedStorage.EXHAUSTED_IN_A_RELEASED_HANDLER));
                assertThat(ownerOf(shard)).as("the shard is released all the same").isNull();
                assertThat(queue.metrics().deadLettered.sum()).isEqualTo(1);
                assertThat(chargeFailuresOf(logged, shard, "as the queue stopped")).as("not reported as a failed charge").isEmpty();
                assertThat(warnings(logged, "has nothing left to charge before releasing unordered shard")).singleElement()
                                                                                                             .satisfies(warning -> assertThat(warning.getThrowableProxy().getMessage())
                                                                                                                     .isEqualTo("observer failure"));
            } finally {
                release.countDown();
                queue.stop();
            }
        } finally {
            releaseQueueLog(logged);
        }
    }

    /**
     * A message deleted while in its handler - as the admin API's delete does - has nothing left to charge: the stop's charge
     * does not find its row, settles it and charges the other message in a handler of that shard alone, so a
     * {@code QueueObserver} that throws when told of that other message's dead letter still finds nothing owed and the shard
     * is released. The message not found used to stay owed for as long as its handler ran, so the stop read the observer's
     * failure as a failed charge and left the shard unreleased, for an unclean takeover to charge again.
     */
    @Test
    void a_stop_releases_a_shard_whose_message_was_deleted_while_in_its_handler_even_when_the_observer_told_of_the_other_dead_letter_throws() throws Exception {
        var settings  = settings(STUCK_HANDLER_TIMEOUT);
        var messages  = SHARD_COUNT + 1;
        var entered   = new CountDownLatch(messages);
        var release   = new CountDownLatch(1);
        var seqsBy    = new ConcurrentHashMap<Integer, Set<Long>>();
        var told      = new AtomicInteger();
        var logged    = captureQueueLog();
        var observer = new dk.trustworks.essentials.components.queue.shardowned.spi.QueueObserver() {
            @Override
            public void deadLettered(dk.trustworks.essentials.components.queue.shardowned.spi.MessageId id, int attempts, Throwable cause) {
                told.incrementAndGet();
                throw new IllegalStateException("observer failure");
            }
        };
        try (var runtime = new ShardRuntime(dataSource, settings)) {
            var queue = ShardOwnedQueue.builder().setDataSource(dataSource).setQueueId(QUEUE_ID).setShardCount(SHARD_COUNT)
                                       .setInstanceId("deleted-in-handler").setRuntime(runtime)
                                       .setMetrics(new ShardOwnerMetrics(observer)).build();
            try {
                queue.startConsuming((messageId, payload, payloadType) -> {
                    seqsBy.computeIfAbsent(messageId.shard(), shard -> new ConcurrentSkipListSet<>()).add(messageId.sequence());
                    entered.countDown();
                    awaitQuietly(release);
                }, settings, SHARD_COUNT, RedeliveryPolicy.fixed(Duration.ofMillis(100), 1));
                for (var message = 0; message < messages; message++) {
                    queue.enqueue(List.of(("hangs-" + message).getBytes(StandardCharsets.UTF_8)), 1);
                }
                assertThat(entered.await(20, TimeUnit.SECONDS)).as("premise: every message is in its handler").isTrue();
                // More messages than shards: a shard has two or more in a handler.
                var shard = seqsBy.entrySet().stream().filter(entry -> entry.getValue().size() >= 2).map(Map.Entry::getKey).findFirst().orElseThrow();
                var deletedSeq = seqsBy.get(shard).iterator().next();
                deleteUnorderedMessage(shard, deletedSeq);
                Thread.sleep(STUCK_HANDLER_TIMEOUT.toMillis() + 300);

                queue.stop();

                assertThat(told).as("premise: the observer was told of every dead letter but the deleted message's").hasValue(messages - 1);
                assertThat(queue.metrics().takeoverAttemptBumps.sum()).as("only the messages still in the table are charged").isEqualTo(messages - 1);
                assertThat(ownerOf(shard)).as("the shard whose message was deleted is released all the same").isNull();
                assertThat(chargeFailuresOf(logged, shard, "as the queue stopped")).as("not reported as a failed charge").isEmpty();
            } finally {
                release.countDown();
                queue.stop();
            }
        } finally {
            releaseQueueLog(logged);
        }
    }

    /**
     * Each message is charged once per owner, however often the release after the charge is retried: a shed shard whose
     * release the database refuses is shed again on every heartbeat, and its message - charged by the first try - is not
     * charged again by the retries, so it is delivered again with one attempt counted once the release goes through.
     * <p>
     * The refused shard is the last of the shed, so the shard shed before it is released on the first try.
     */
    @Test
    void a_shed_shard_whose_release_the_database_refuses_charges_its_message_once_however_often_the_release_is_retried() throws Exception {
        var settings     = settings(STUCK_HANDLER_TIMEOUT);
        var refusedShard = SHARD_COUNT - 1;
        var entered      = new CountDownLatch(SHARD_COUNT);
        var release      = new CountDownLatch(1);
        var redelivered  = new ConcurrentHashMap<Integer, Integer>();
        try (var firstRuntime = new ShardRuntime(dataSource, settings); var secondRuntime = new ShardRuntime(dataSource, settings)) {
            var first  = queue(firstRuntime, "refused-first");
            var second = queue(secondRuntime, "refused-second");
            try {
                first.startConsuming((messageId, payload, payloadType) -> {
                    entered.countDown();
                    awaitQuietly(release);
                }, settings, SHARD_COUNT);
                assertThat(first.shardsHeld()).as("premise: alone, the first instance holds every shard").isEqualTo(SHARD_COUNT);
                for (var message = 0; message < SHARD_COUNT; message++) {
                    first.enqueue(List.of(("hangs-" + message).getBytes(StandardCharsets.UTF_8)), 1);
                }
                assertThat(entered.await(20, TimeUnit.SECONDS)).as("premise: a message of every shard is in its handler").isTrue();
                Thread.sleep(STUCK_HANDLER_TIMEOUT.toMillis() + 300);

                LeaseReleaseRefusals.failLeaseReleasesOf(dataSource, new short[]{QUEUE_ID}, "unordered", refusedShard, true);
                try {
                    second.startConsuming((messageId, payload, payloadType) -> redelivered.put(messageId.shard(),
                                                                                               attemptsOf(messageId.shard(), messageId.sequence())),
                                          settings, SHARD_COUNT);
                    Awaitility.await().atMost(Duration.ofSeconds(30))
                              .untilAsserted(() -> assertThat(LeaseReleaseRefusals.releasesRefused(dataSource, QUEUE_ID, refusedShard))
                                      .as("premise: the release was refused and retried").isGreaterThanOrEqualTo(2));
                    assertThat(ownerOf(refusedShard)).as("premise: still the incumbent's").isEqualTo("refused-first");
                } finally {
                    LeaseReleaseRefusals.failLeaseReleasesOf(dataSource, new short[]{QUEUE_ID}, "unordered", refusedShard, false);
                }

                Awaitility.await().atMost(Duration.ofSeconds(20))
                          .untilAsserted(() -> assertThat(ownerOf(refusedShard)).as("released once the database lets it").isEqualTo("refused-second"));
                assertThat(first.metrics().takeoverAttemptBumps.sum()).as("each shed shard's message charged once").isEqualTo(SHARD_COUNT / 2);
                Awaitility.await().atMost(Duration.ofSeconds(20)).untilAsserted(() -> assertThat(redelivered).containsKey(refusedShard));
                assertThat(redelivered.get(refusedShard)).as("delivered again with that one attempt counted").isEqualTo(1);
            } finally {
                LeaseReleaseRefusals.failLeaseReleasesOf(dataSource, new short[]{QUEUE_ID}, "unordered", refusedShard, false);
                release.countDown();
                second.stop();
                first.stop();
            }
        }
    }

    /**
     * A message the shed charged whose release the database refuses stays with the incumbent, which serves the shard on; a
     * failure its handler then reports counts on from the charge. The owner keeps its own count for a message that has
     * failed under it, and the failure write sets the count it is given, so a count that left the charge out undid it.
     */
    @Test
    void a_failure_reported_after_a_refused_release_counts_on_from_the_attempt_the_release_charged() throws Exception {
        var outcome = failAfterARefusedRelease(5, true);
        assertThat(outcome.attemptsOnNextDelivery().get(20, TimeUnit.SECONDS))
                .as("the first failure, the charge and the second failure").isEqualTo(3);
    }

    /**
     * A message the shed charge dead-lettered at the redelivery limit, whose release the database then refuses, stays in its
     * handler under the incumbent; a failure that handler reports later records nothing against the row - it is gone - and
     * is not reported as a dead letter that could not be moved. What it threw is logged at WARN with the exception and told
     * to the {@code QueueObserver} as the attempt the charge counted, as the dead letter carries the release's text, not it:
     * it used to be dropped without a trace.
     */
    @Test
    void a_failure_reported_by_the_handler_of_a_message_dead_lettered_before_a_refused_release_records_nothing_but_reports_the_exception() throws Exception {
        var outcome = failAfterARefusedRelease(2, true);
        assertThat(outcome.queue().deadLetters()).as("dead-lettered by the charge").singleElement().satisfies(deadLetter -> {
            assertThat(deadLetter.attempts()).isEqualTo(2);
            assertThat(deadLetter.error()).isEqualTo(ShardOwnedStorage.EXHAUSTED_IN_A_RELEASED_HANDLER);
        });
        assertThat(outcome.failedAttempts()).as("the first failure, then the second as the attempt the charge counted").containsExactly(1, 2);
        assertThat(outcome.queue().metrics().deadLettered.sum()).isEqualTo(1);
        assertThat(outcome.ownerWarnings().stream().map(ILoggingEvent::getFormattedMessage))
                .as("no dead-letter move attempted for it").noneMatch(message -> message.contains("was not dead-lettered"));
        assertThat(outcome.ownerWarnings()).as("what the handler threw is logged, with the exception")
                                           .filteredOn(event -> event.getFormattedMessage().contains("threw after the charge before a release"))
                                           .singleElement()
                                           .satisfies(warning -> assertThat(warning.getThrowableProxy().getMessage()).isEqualTo("second failure"));
    }

    /**
     * A message the shed charge dead-lettered at the redelivery limit, whose release the database then refuses, stays in its
     * handler under the incumbent; when that handler then completes, the message is not acknowledged and not counted
     * delivered - the row is gone, and its dead letter stays - and a WARN says so: resurrecting it delivers the message
     * again. Before, the message was counted as delivered and as dead-lettered.
     */
    @Test
    void a_handler_completing_a_message_dead_lettered_before_a_refused_release_is_not_counted_delivered() throws Exception {
        var outcome = failAfterARefusedRelease(2, false);
        assertThat(outcome.queue().deadLetters()).as("the dead letter stays").singleElement().satisfies(deadLetter -> {
            assertThat(deadLetter.attempts()).isEqualTo(2);
            assertThat(deadLetter.error()).isEqualTo(ShardOwnedStorage.EXHAUSTED_IN_A_RELEASED_HANDLER);
        });
        assertThat(outcome.queue().metrics().delivered.sum()).as("every other shard's message delivered; this one not counted")
                                                           .isEqualTo(SHARD_COUNT - 1);
        assertThat(outcome.queue().metrics().deadLettered.sum()).isEqualTo(1);
        assertThat(outcome.failedAttempts()).as("only the first delivery failed").containsExactly(1);
        assertThat(outcome.ownerWarnings().stream().map(ILoggingEvent::getFormattedMessage))
                .as("the handler completing a dead-lettered message is logged")
                .filteredOn(message -> message.contains("completed after the charge before a release"))
                .singleElement()
                .satisfies(message -> assertThat(message).contains("resurrecting that dead letter delivers it again"));
    }

    private record RefusedReleaseOutcome(ShardOwnedQueue queue, CompletableFuture<Integer> attemptsOnNextDelivery, List<Integer> failedAttempts,
                                         List<ILoggingEvent> ownerWarnings) {
    }

    /**
     * The message of the last shed shard fails its first delivery and hangs in its second past {@code stuckHandlerTimeout};
     * a second instance joins, the shed charges the message, and its release is refused. Its handler is then let go with a
     * failure ({@code fails}) or returns, and the delivery after it under the incumbent, if any, reads the stored count.
     * The release is refused until the incumbent has delivered again, or for two seconds after the handler was let go.
     */
    private RefusedReleaseOutcome failAfterARefusedRelease(int maxAttempts, boolean fails) throws Exception {
        var settings         = settings(STUCK_HANDLER_TIMEOUT);
        var refusedShard     = SHARD_COUNT - 1;
        var deliveries       = new AtomicInteger();
        var entered          = new CountDownLatch(1);
        var letGo            = new CountDownLatch(1);
        var nextDelivery     = new CompletableFuture<Integer>();
        var failedAttempts   = new CopyOnWriteArrayList<Integer>();
        var secondFailureTold = new CountDownLatch(1);
        var observer = new dk.trustworks.essentials.components.queue.shardowned.spi.QueueObserver() {
            @Override
            public void deliveryFailed(String key, int attempt, Throwable cause) {
                failedAttempts.add(attempt);
            }
        };
        var ownerLog = new ListAppender<ILoggingEvent>();
        ownerLog.start();
        var ownerLogger = (ch.qos.logback.classic.Logger) LoggerFactory.getLogger(ShardOwner.class);
        ownerLogger.addAppender(ownerLog);
        try (var firstRuntime = new ShardRuntime(dataSource, settings); var secondRuntime = new ShardRuntime(dataSource, settings)) {
            var first  = ShardOwnedQueue.builder().setDataSource(dataSource).setQueueId(QUEUE_ID).setShardCount(SHARD_COUNT)
                                        .setInstanceId("fails-first").setRuntime(firstRuntime)
                                        .setMetrics(new ShardOwnerMetrics(observer)).build();
            var second = queue(secondRuntime, "fails-second");
            try {
                first.startConsuming((messageId, payload, payloadType) -> {
                    if (messageId.shard() != refusedShard) {
                        return;
                    }
                    switch (deliveries.incrementAndGet()) {
                        case 1 -> throw new IllegalStateException("first failure");
                        case 2 -> {
                            entered.countDown();
                            awaitQuietly(letGo);
                            secondFailureTold.countDown();
                            if (fails) {
                                throw new IllegalStateException("second failure");
                            }
                        }
                        default -> nextDelivery.complete(attemptsOf(messageId.shard(), messageId.sequence()));
                    }
                }, settings, SHARD_COUNT, RedeliveryPolicy.fixed(Duration.ofMillis(100), maxAttempts));
                assertThat(first.shardsHeld()).as("premise: alone, the first instance holds every shard").isEqualTo(SHARD_COUNT);
                for (var message = 0; message < SHARD_COUNT; message++) {
                    first.enqueue(List.of(("message-" + message).getBytes(StandardCharsets.UTF_8)), 1);
                }
                assertThat(entered.await(20, TimeUnit.SECONDS)).as("premise: the second delivery is in its handler").isTrue();
                Thread.sleep(STUCK_HANDLER_TIMEOUT.toMillis() + 300);

                LeaseReleaseRefusals.failLeaseReleasesOf(dataSource, new short[]{QUEUE_ID}, "unordered", refusedShard, true);
                try {
                    second.startConsuming((messageId, payload, payloadType) -> { }, settings, SHARD_COUNT);
                    Awaitility.await().atMost(Duration.ofSeconds(30))
                              .untilAsserted(() -> assertThat(LeaseReleaseRefusals.releasesRefused(dataSource, QUEUE_ID, refusedShard))
                                      .as("premise: the release after the charge was refused").isGreaterThanOrEqualTo(1));
                    assertThat(first.metrics().takeoverAttemptBumps.sum()).as("premise: the shed charged the hung message").isEqualTo(1);
                    letGo.countDown();
                    assertThat(secondFailureTold.await(10, TimeUnit.SECONDS)).isTrue();
                    try {
                        nextDelivery.get(2, TimeUnit.SECONDS);
                    } catch (TimeoutException ignored) {
                        // Nothing to deliver again: the charge dead-lettered it.
                    }
                } finally {
                    LeaseReleaseRefusals.failLeaseReleasesOf(dataSource, new short[]{QUEUE_ID}, "unordered", refusedShard, false);
                }
                List<ILoggingEvent> ownerWarnings;
                synchronized (ownerLog) {
                    ownerWarnings = ownerLog.list.stream().filter(event -> event.getLevel() == Level.WARN).toList();
                }
                return new RefusedReleaseOutcome(first, nextDelivery, List.copyOf(failedAttempts), ownerWarnings);
            } finally {
                letGo.countDown();
                second.stop();
                first.stop();
            }
        } finally {
            ownerLogger.detachAppender(ownerLog);
            ownerLog.stop();
        }
    }

    private ShardOwnedQueue queue(ShardRuntime runtime, String instanceId) {
        return ShardOwnedQueue.builder().setDataSource(dataSource).setQueueId(QUEUE_ID).setShardCount(SHARD_COUNT)
                              .setInstanceId(instanceId).setRuntime(runtime).build();
    }

    /**
     * Refuse, in the database, every write raising the attempts of an unordered row on {@code shard} - the charge before a
     * release - or stop refusing it.
     */
    private void failUnorderedAttemptChargesOn(int shard, boolean fail) throws Exception {
        try (var connection = dataSource.getConnection(); var statement = connection.createStatement()) {
            if (fail) {
                statement.execute("CREATE OR REPLACE FUNCTION fail_unordered_attempt_charge() RETURNS trigger AS $$ BEGIN "
                                          + "IF NEW.attempts > OLD.attempts AND NEW.shard = " + shard + " THEN "
                                          + "RAISE EXCEPTION 'simulated failure charging an unordered attempt on shard " + shard + "'; END IF; "
                                          + "RETURN NEW; END $$ LANGUAGE plpgsql");
                statement.execute("CREATE TRIGGER fail_unordered_attempt_charge BEFORE UPDATE ON " + ShardOwnedSchema.UNORDERED_TABLE
                                          + " FOR EACH ROW EXECUTE FUNCTION fail_unordered_attempt_charge()");
            } else {
                statement.execute("DROP TRIGGER IF EXISTS fail_unordered_attempt_charge ON " + ShardOwnedSchema.UNORDERED_TABLE);
            }
        }
    }

    /**
     * Refuse, in the database, every insert into the dead-letter table - the move a charge makes for a message it brings to
     * the redelivery limit - or stop refusing it.
     */
    private void failDeadLetterMoves(boolean fail) throws Exception {
        try (var connection = dataSource.getConnection(); var statement = connection.createStatement()) {
            if (fail) {
                statement.execute("CREATE OR REPLACE FUNCTION fail_dead_letter_move() RETURNS trigger AS $$ BEGIN "
                                          + "RAISE EXCEPTION 'simulated failure moving to the dead-letter table'; END $$ LANGUAGE plpgsql");
                statement.execute("CREATE TRIGGER fail_dead_letter_move BEFORE INSERT ON " + ShardOwnedSchema.DLQ_TABLE
                                          + " FOR EACH ROW EXECUTE FUNCTION fail_dead_letter_move()");
            } else {
                statement.execute("DROP TRIGGER IF EXISTS fail_dead_letter_move ON " + ShardOwnedSchema.DLQ_TABLE);
            }
        }
    }

    /**
     * The WARNs saying the charge before releasing unordered {@code shard} {@code from} a shed or a stop failed, so the shard
     * was not released.
     */
    private static List<ILoggingEvent> chargeFailuresOf(ListAppender<ILoggingEvent> logged, int shard, String from) {
        return warnings(logged, "could not charge the messages in a handler of unordered shard").stream()
                                                                                             .filter(event -> Integer.valueOf(shard).equals(event.getArgumentArray()[1]))
                                                                                             .filter(event -> event.getFormattedMessage().contains(from))
                                                                                             .toList();
    }

    /**
     * Delete unordered message {@code seq} of {@code shard} with {@link ShardOwnedStorage#deleteMessage}, the delete the
     * admin API's message delete runs on a message still in its lane ({@code PostgresqlMessageQueue.deleteMessage}).
     */
    private void deleteUnorderedMessage(int shard, long seq) throws SQLException {
        assertThat(new ShardOwnedStorage(dataSource, QUEUE_ID).deleteMessage(shard, seq, false))
                .as("premise: the message in its handler is deleted").isTrue();
    }

    private String ownerOf(int shard) throws SQLException {
        return queryForString(dataSource, "Reading the owner of unordered shard " + shard,
                              "SELECT owner FROM " + ShardOwnedSchema.LEASE_TABLE + " WHERE queue_id = ? AND lane = 'unordered' AND shard = ?",
                              QUEUE_ID, shard);
    }

    private long fenceOf(int shard) throws SQLException {
        return queryForLong(dataSource, "Reading the fence of unordered shard " + shard,
                            "SELECT fence FROM " + ShardOwnedSchema.LEASE_TABLE + " WHERE queue_id = ? AND lane = 'unordered' AND shard = ?",
                            QUEUE_ID, shard);
    }

    /**
     * A heartbeat every second, a short drain on stop, and the given bound.
     */
    private static ShardOwnerSettings settings(Duration stuckHandlerTimeout) {
        return ShardOwnerSettings.builder()
                                 .setPumpThreads(2)
                                 .setShedGrace(Duration.ofMillis(300))
                                 .setLeaseTtl(Duration.ofSeconds(3))
                                 .setStuckHandlerTimeout(stuckHandlerTimeout)
                                 .build();
    }

    /**
     * The stored attempt count of unordered message {@code seq} of {@code shard}, read from inside a handler: unchecked, as a
     * handler cannot throw a checked exception.
     */
    private int attemptsOf(int shard, long seq) {
        try {
            return queryForInt(dataSource, "Reading the attempts of unordered message " + shard + "/" + seq,
                               "SELECT attempts FROM " + ShardOwnedSchema.UNORDERED_TABLE + " WHERE queue_id = ? AND shard = ? AND seq = ?",
                               QUEUE_ID, shard, seq);
        } catch (SQLException e) {
            throw new IllegalStateException(e);
        }
    }

    private int unorderedMessagesLeft() throws SQLException {
        return queryForInt(dataSource, "Counting the unordered messages left",
                           "SELECT count(*) FROM " + ShardOwnedSchema.UNORDERED_TABLE + " WHERE queue_id = ?", QUEUE_ID);
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
            latch.await(60, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
