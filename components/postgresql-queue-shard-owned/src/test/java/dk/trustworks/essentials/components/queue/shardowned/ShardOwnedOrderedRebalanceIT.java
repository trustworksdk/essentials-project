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
import dk.trustworks.essentials.components.queue.shardowned.spi.*;
import org.awaitility.Awaitility;
import org.junit.jupiter.api.*;
import org.slf4j.LoggerFactory;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.*;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Ordered-lane rebalancing — the case the design deliberately left switched off until it had a test.
 * <p>
 * The unordered lane sheds a shard by dropping the lease and letting the new owner redeliver
 * whatever was in flight, which at-least-once permits. The ordered lane cannot: the new owner would
 * start a key the outgoing owner is still running, and two concurrent messages of one key is
 * <em>reordering</em>, not the duplicate the contract allows. So the shed drains first. These tests
 * assert that it drains, that it converges, that a key is never in two handlers at once, and that a
 * shed which cannot drain is abandoned rather than forced.
 */
@Testcontainers(disabledWithoutDocker = true)
class ShardOwnedOrderedRebalanceIT {

    private static final short QUEUE_ID    = 1;
    private static final int   SHARD_COUNT = 8;
    /**
     * How long a held handler waits to be let go ({@link #holdUntil}): far longer than any test here runs, so a held handler
     * returns when its test lets it go, never on its own while the test still counts on it being held.
     */
    private static final Duration HANDLER_HOLD = Duration.ofMinutes(5);

    /** Short lease, so several heartbeat-and-rebalance ticks happen inside the test window. */
    private static ShardOwnerSettings fast(Duration shedGrace) {
        return fast(shedGrace, Duration.ofMillis(1000));
    }

    private static ShardOwnerSettings fast(Duration shedGrace, Duration leaseTtl) {
        return fast(shedGrace, leaseTtl, ShardOwnerSettings.DEFAULT_STUCK_HANDLER_TIMEOUT);
    }

    private static ShardOwnerSettings fast(Duration shedGrace, Duration leaseTtl, Duration stuckHandlerTimeout) {
        return ShardOwnerSettings.builder()
                                 .setHoleExpiry(Duration.ofMillis(300))
                                 .setSweepInterval(Duration.ofMillis(100))
                                 .setPollBackstop(Duration.ofMillis(50))
                                 .setShedGrace(shedGrace)
                                 .setLeaseTtl(leaseTtl)
                                 .setStuckHandlerTimeout(stuckHandlerTimeout)
                                 .build();
    }

    @Container
    static PostgreSQLContainer postgres = LabPostgres.create();

    private HikariDataSource dataSource;

    @BeforeEach
    void setUp() throws Exception {
        var config = new HikariConfig();
        config.setJdbcUrl(postgres.getJdbcUrl());
        config.setUsername(postgres.getUsername());
        config.setPassword(postgres.getPassword());
        config.setMaximumPoolSize(60);
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

    private void enqueueOrdered(ShardOwnedQueue queue, int keys, int perKey) throws Exception {
        var messages = new ArrayList<ShardOwnedStorage.OrderedPayload>();
        for (var order = 0; order < perKey; order++) {
            for (var key = 0; key < keys; key++) {
                messages.add(new ShardOwnedStorage.OrderedPayload("key-" + key, order,
                                                               ("key-" + key + "#" + order).getBytes(StandardCharsets.UTF_8), 1));
            }
        }
        queue.enqueueOrdered(messages);
    }

    /**
     * The convergence test. One instance takes all eight shards; a second joins, and the split must
     * even out — which can only happen if the first instance gives shards up, since nothing dies.
     */
    @Test
    void a_joinishard_queue_instance_gets_its_fair_share_because_the_incumbent_drains_and_releases() throws Exception {
        var first  = new ShardOwnedQueue(dataSource, QUEUE_ID, SHARD_COUNT, "ord-a");
        var second = new ShardOwnedQueue(dataSource, QUEUE_ID, SHARD_COUNT, "ord-b");
        try {
            first.startConsumingOrdered((messageId, key, payload, payloadType) -> {
            }, fast(Duration.ofSeconds(5)), SHARD_COUNT);
            Awaitility.await().atMost(Duration.ofSeconds(10))
                      .untilAsserted(() -> assertThat(first.shardsHeld())
                              .isEqualTo(ShardOwnedSchema.ORDERED_UNITS));

            second.startConsumingOrdered((messageId, key, payload, payloadType) -> {
            }, fast(Duration.ofSeconds(5)), SHARD_COUNT);

            Awaitility.await().atMost(Duration.ofSeconds(30)).untilAsserted(() -> {
                assertThat(first.shardsHeld()).as("incumbent must come down to its fair share")
                        .isEqualTo(ShardOwnedSchema.ORDERED_UNITS / 2);
                assertThat(second.shardsHeld()).as("joiner must reach its fair share")
                        .isEqualTo(ShardOwnedSchema.ORDERED_UNITS / 2);
            });
            // Shedding is what made it possible, so it must actually have happened rather than the
            // split arising some other way.
            assertThat(first.metrics().shedsCompleted.sum()).isPositive();
            assertThat(first.metrics().shedsAbandoned.sum())
                    .as("handlers here return immediately, so nothing should outrun its grace")
                    .isZero();
        } finally {
            first.close();
            second.close();
        }
    }

    /**
     * The guarantee itself. Shards move while messages are flowing, and no key may ever be in two
     * handlers at the same moment — across both instances. A duplicate delivery is legal; an overlap
     * is not.
     */
    @Test
    void no_key_is_ever_in_two_handlers_at_once_while_shards_move() throws Exception {
        // Few messages, each slow. The handler has to outlast the takeover — the window in which an
        // undrained shed would let the new owner start a key the old one is still running. At 20ms a
        // handler it was closed before the successor could even acquire the shard, and the test
        // passed against a deliberately broken engine that released without draining at all.
        // Enough per key that the workload outlasts the drain. A drained shed cannot complete until
        // its in-flight handlers do, so with too little work the queue is empty by the time the
        // shards move and the test proves nothing about a shed under load.
        var keys   = 16;
        var perKey = 4;

        var concurrentPerKey = new ConcurrentHashMap<String, Integer>();
        var overlaps         = new java.util.concurrent.atomic.AtomicInteger();
        var handled          = ConcurrentHashMap.<String>newKeySet();
        var deliveries       = new java.util.concurrent.atomic.AtomicInteger();

        OrderedPayloadHandler handler = (messageId, key, payload, payloadType) -> {
            // A key held by two owners at once shows up here and nowhere else — the count is shared
            // across both instances precisely so a cross-instance overlap is visible.
            if (concurrentPerKey.merge(key, 1, Integer::sum) > 1) {
                overlaps.incrementAndGet();
            }
            try {
                Thread.sleep(3_000L);
                handled.add(new String(payload, StandardCharsets.UTF_8));
                deliveries.incrementAndGet();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            } finally {
                concurrentPerKey.merge(key, -1, Integer::sum);
            }
        };

        var first  = new ShardOwnedQueue(dataSource, QUEUE_ID, SHARD_COUNT, "ord-c");
        var second = new ShardOwnedQueue(dataSource, QUEUE_ID, SHARD_COUNT, "ord-d");
        try {
            // The grace must comfortably exceed the handler, or this test measures the abandon path
            // that `a_shed_that_cannot_drain_is_abandoned...` covers instead of the drain path.
            // And the lease must exceed the handler too. With the one-second lease the other tests use,
            // a one-second heartbeat stall under a loaded build was enough for the joiner to take the
            // incumbent's units as dead while its three-second handlers ran - two keys in two handlers,
            // from a liveness lapse rather than from the rebalance this test is about. That is a real
            // hazard, and a configuration the engine now warns about (LeaseOverrun); here it is noise.
            first.startConsumingOrdered(handler, fast(Duration.ofSeconds(20), Duration.ofSeconds(10)), SHARD_COUNT);
            enqueueOrdered(first, keys, perKey);
            // Join while work is in flight, so the shed happens with handlers running rather than
            // against an idle shard — the case that would reorder if the drain were skipped.
            Awaitility.await().atMost(Duration.ofSeconds(20)).until(() -> deliveries.get() > 4);
            second.startConsumingOrdered(handler, fast(Duration.ofSeconds(20), Duration.ofSeconds(10)), SHARD_COUNT);

            // Wait for the shards to actually move, and record how much work was still outstanding
            // when they did. Both halves matter: a shed that happened after the queue emptied would
            // prove nothing about the hazard this test exists to rule out.
            var remainingAtShed = new java.util.concurrent.atomic.AtomicLong(-1L);
            Awaitility.await().atMost(Duration.ofSeconds(60)).until(() -> {
                if (first.metrics().shedsCompleted.sum() == 0L) {
                    return false;
                }
                if (remainingAtShed.get() < 0L) {
                    remainingAtShed.set(first.orderedRemaining());
                }
                return true;
            });
            assertThat(remainingAtShed.get())
                    .as("the shards must have moved while messages were still flowing")
                    .isPositive();

            Awaitility.await().atMost(Duration.ofSeconds(180))
                      .untilAsserted(() -> assertThat(first.orderedRemaining()).isZero());

            assertThat(overlaps.get())
                    .as("a key in two handlers at once is reordering, which no rebalance may cause")
                    .isZero();
            assertThat(first.metrics().handlersOutlastingLease.sum() + second.metrics().handlersOutlastingLease.sum())
                    .as("the lease must outlast the handlers, or this measures a liveness lapse instead of a rebalance")
                    .isZero();
            assertThat(handled).as("every message must be handled at least once").hasSize(keys * perKey);
            assertThat(second.shardsHeld())
                    .as("the joining instance must have ended up serving some of the shards")
                    .isPositive();
        } finally {
            first.close();
            second.close();
        }
    }

    /**
     * The bounded case. A handler that outlasts the shed grace must cost balance, not ordering: the
     * shed is abandoned and the shard stays put. Constructed rather than hoped for — the handler
     * blocks on a latch the test controls.
     */
    @Test
    void a_shed_that_cannot_drain_is_abandoned_and_the_shard_stays_where_it_is() throws Exception {
        var blockHandlers = new CountDownLatch(1);
        var inHandler     = new CountDownLatch(1);

        OrderedPayloadHandler stuck = (messageId, key, payload, payloadType) -> {
            inHandler.countDown();
            try {
                // Outlasts the 500ms grace by a wide margin, and is released by the test rather than
                // by a timeout, so the abandon path is entered deterministically.
                blockHandlers.await(30, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        };

        var logged      = new ListAppender<ILoggingEvent>();
        var ownerLogger = (ch.qos.logback.classic.Logger) LoggerFactory.getLogger(OrderedShardOwner.class);
        logged.start();
        ownerLogger.addAppender(logged);

        var first  = new ShardOwnedQueue(dataSource, QUEUE_ID, SHARD_COUNT, "ord-e");
        var second = new ShardOwnedQueue(dataSource, QUEUE_ID, SHARD_COUNT, "ord-f");
        try {
            // Enough per-consumer parallelism to hold a stuck handler on every shard at once. With
            // the default of ten across eight shards some shards had none, drained cleanly, and the
            // shed succeeded — which is the opposite of what this test is about.
            first.setParallelConsumers(ShardOwnedSchema.ORDERED_UNITS * 8);
            first.startConsumingOrdered(stuck, fast(Duration.ofMillis(500)), SHARD_COUNT);
            Awaitility.await().atMost(Duration.ofSeconds(10))
                      .untilAsserted(() -> assertThat(first.shardsHeld())
                              .isEqualTo(ShardOwnedSchema.ORDERED_UNITS));

            // One message per shard-worth of keys, so every shard has a handler stuck in it.
            enqueueOrdered(first, 512, 1);
            assertThat(inHandler.await(15, TimeUnit.SECONDS)).as("handlers must be running").isTrue();

            second.startConsumingOrdered((messageId, key, payload, payloadType) -> {
            }, fast(Duration.ofMillis(500)), SHARD_COUNT);

            // The shed is attempted, cannot drain, and is given up on - and, being retried every
            // heartbeat, given up on again, several times per shard.
            Awaitility.await().atMost(Duration.ofSeconds(20))
                      .untilAsserted(() -> assertThat(first.metrics().shedsAbandoned.sum())
                              .isGreaterThanOrEqualTo(3L * ShardOwnedSchema.ORDERED_UNITS / 2));
            // The same stuck keys are reported once per shard, not once per heartbeat for as long as
            // they stay stuck.
            List<ILoggingEvent> events;
            synchronized (logged) { // the monitor AppenderBase appends under; the pumps are still logging
                events = new ArrayList<>(logged.list);
            }
            var abandonWarningsPerShard = events.stream()
                                                     .filter(event -> event.getLevel() == Level.WARN)
                                                     .filter(event -> event.getMessage().contains("shed abandoned"))
                                                     .collect(Collectors.groupingBy(event -> event.getArgumentArray()[0],
                                                                                    Collectors.counting()));
            assertThat(abandonWarningsPerShard).isNotEmpty();
            assertThat(abandonWarningsPerShard.values())
                    .as("an abandoned shed held up by the same keys must WARN once, not on every retry")
                    .allMatch(count -> count == 1L);
            // Correctness over balance: the shard is still here, not handed over mid-key.
            assertThat(first.shardsHeld())
                    .as("a shard whose keys are still in handlers must not be released")
                    .isGreaterThan(ShardOwnedSchema.ORDERED_UNITS / 2);

            // Once the handlers finish, the shed that was retried on a later tick succeeds and the
            // split converges — the abandon is a delay, not a permanent degradation.
            blockHandlers.countDown();
            Awaitility.await().atMost(Duration.ofSeconds(40)).untilAsserted(() -> {
                assertThat(first.shardsHeld()).isEqualTo(ShardOwnedSchema.ORDERED_UNITS / 2);
                assertThat(second.shardsHeld()).isEqualTo(ShardOwnedSchema.ORDERED_UNITS / 2);
            });
            assertThat(first.metrics().shedsCompleted.sum()).isPositive();
        } finally {
            ownerLogger.detachAppender(logged);
            blockHandlers.countDown();
            first.close();
            second.close();
        }
    }

    /**
     * The abandoned shed is bounded by {@code stuckHandlerTimeout}: once the grace runs out with a key in its handler longer
     * than that, the handler counts as stuck and the shed releases the unit with it still running, having charged the message
     * in that handler one attempt first. The joining instance takes the unit under a new fence, charging nothing itself (the
     * row was released), and delivers the stuck key's message, with that attempt counted,
     * again beside the hung handler - the at-least-once the bound trades the stuck unit for - and the release is named in
     * one WARN and counted in {@code keptUnitsInHandler} while the handler runs on. Unbounded, a hung handler kept the
     * unit on the incumbent, the cluster unbalanced and the unit's other keys unserved, until a restart.
     * <p>
     * The stuck key routes to the last unit the incumbent adopted, so it is among those its shed asks to drain.
     */
    @Test
    void a_shed_held_up_by_a_handler_past_the_stuck_handler_timeout_releases_the_unit_with_the_handler_still_running() throws Exception {
        var stuckUnit = ShardOwnedSchema.ORDERED_UNITS - 1;
        var stuckKey  = keyOnUnit(stuckUnit);
        var entered   = new CountDownLatch(1);
        var release   = new CountDownLatch(1);
        var holdsCutShort = new AtomicInteger();
        var entries   = new java.util.concurrent.atomic.AtomicInteger();
        var attemptsOnRedelivery = new CompletableFuture<Integer>();
        var logged    = ShardOwnedRebalanceIT.captureQueueLog();
        var settings  = fast(Duration.ofMillis(500), Duration.ofMillis(1000), Duration.ofSeconds(3));

        var first  = new ShardOwnedQueue(dataSource, QUEUE_ID, SHARD_COUNT, "ord-stuck-a");
        var second = new ShardOwnedQueue(dataSource, QUEUE_ID, SHARD_COUNT, "ord-stuck-b");
        try {
            first.startConsumingOrdered((messageId, key, payload, payloadType) -> {
                if (stuckKey.equals(key) && entries.incrementAndGet() == 1) {
                    entered.countDown();
                    holdUntil(release, holdsCutShort);
                }
            }, settings, SHARD_COUNT);
            Awaitility.await().atMost(Duration.ofSeconds(10))
                      .untilAsserted(() -> assertThat(first.shardsHeld()).isEqualTo(ShardOwnedSchema.ORDERED_UNITS));
            first.enqueueOrdered(List.of(new ShardOwnedStorage.OrderedPayload(stuckKey, 0, "stuck".getBytes(StandardCharsets.UTF_8), 1)));
            assertThat(entered.await(15, TimeUnit.SECONDS)).as("premise: the key is in its handler").isTrue();
            var fenceBefore = fenceOf(stuckUnit);

            second.startConsumingOrdered((messageId, key, payload, payloadType) -> {
                if (stuckKey.equals(key)) {
                    attemptsOnRedelivery.complete(attemptsOf(messageId.sequence()));
                    entries.incrementAndGet();
                }
            }, settings, SHARD_COUNT);

            Awaitility.await().atMost(Duration.ofSeconds(30)).untilAsserted(() -> {
                assertThat(ownerOf(stuckUnit)).as("the unit moved to the joining instance").isEqualTo("ord-stuck-b");
                assertThat(fenceOf(stuckUnit)).as("taken under a new fence").isGreaterThan(fenceBefore);
                assertThat(entries.get()).as("the stuck key's message delivered again beside the hung handler").isEqualTo(2);
            });
            assertThat(holdsCutShort.get()).as("premise: the first handler is still held").isZero();
            assertThat(first.metrics().keptUnitsInHandler()).as("counted while the stuck handler runs on").isEqualTo(1);
            // The delivery in the hung handler is an attempt: the shed charges it before it releases the row, as the joining
            // instance finds the row released and charges nothing. Uncharged, a message whose handler hung on every delivery
            // never reached the dead-letter table.
            assertThat(first.metrics().takeoverAttemptBumps.sum()).as("the shed charged the message in the hung handler once").isEqualTo(1);
            assertThat(second.metrics().takeoverAttemptBumps.sum()).as("released, not taken over: the joining instance charges nothing").isZero();
            assertThat(attemptsOnRedelivery.get(5, TimeUnit.SECONDS)).as("the message is redelivered with that one attempt counted").isEqualTo(1);
            var stuckWarnings = queueWarnings(logged, "so those handlers were not waited for");
            assertThat(stuckWarnings).as("one WARN for the release").hasSize(1);
            assertThat(stuckWarnings.getFirst().getArgumentArray()[1]).as("the unit").isEqualTo(stuckUnit);
            assertThat(stuckWarnings.getFirst().getFormattedMessage()).contains("on queue " + QUEUE_ID + " in a fair-share shed")
                                                                      .contains("1 key(s) [" + stuckKey + "] still in a handler")
                                                                      .contains("stuckHandlerTimeout (PT3S)");
            Awaitility.await().atMost(Duration.ofSeconds(30)).untilAsserted(() -> {
                assertThat(first.shardsHeld()).isEqualTo(ShardOwnedSchema.ORDERED_UNITS / 2);
                assertThat(second.shardsHeld()).isEqualTo(ShardOwnedSchema.ORDERED_UNITS / 2);
            });

            release.countDown();
            // Since the handler returned, no read of the gauge, which prunes the set too: only the heartbeat's prune can empty it here.
            Awaitility.await().atMost(Duration.ofSeconds(10))
                      .untilAsserted(() -> assertThat(first.releasedPastStuckHandlerSize()).as("pruned by the heartbeat once the handler returned").isZero());
            Awaitility.await().atMost(Duration.ofSeconds(10))
                      .untilAsserted(() -> assertThat(first.metrics().keptUnitsInHandler()).as("no longer once the handler returned").isZero());
        } finally {
            release.countDown();
            ShardOwnedRebalanceIT.releaseQueueLog(logged);
            first.close();
            second.close();
        }
    }

    /**
     * A shed released past {@code stuckHandlerTimeout} charges only the messages in a handler longer than that bound, as the
     * unordered lane does: another key of the unit that entered its handler moments before the shed is taken to be slow,
     * not hung, and released uncharged. It used to be charged too, so with that key's message at its last attempt but one -
     * a fresh message with {@code maxAttempts} 1 here - the release dead-lettered it with
     * {@code EXHAUSTED_IN_A_RELEASED_HANDLER} though its handler was healthy, and blocked the key behind the dead letter.
     * <p>
     * Both keys route to the last unit the incumbent adopted, so it is among those its shed asks to drain.
     */
    @Test
    void a_shed_released_past_the_stuck_handler_timeout_does_not_charge_a_key_that_entered_its_handler_just_before() throws Exception {
        var stuckUnit           = ShardOwnedSchema.ORDERED_UNITS - 1;
        var stuckKey            = keyOnUnit(stuckUnit);
        var youngKey            = otherKeyOnUnit(stuckUnit, stuckKey);
        // Room for the young key to stay under the bound through the second instance's join, a heartbeat and the shed grace, on
        // a loaded build too: the wait for the release below is cut to what is left of the bound, so a run too slow for it fails
        // on that premise, never on the young key charged by a shed completing past the bound. The stuck key's wait past the
        // bound grows with it.
        var stuckHandlerTimeout = Duration.ofSeconds(20);
        var stuckEntered        = new CountDownLatch(1);
        var youngEntered        = new CountDownLatch(1);
        var release             = new CountDownLatch(1);
        var holdsCutShort       = new AtomicInteger();
        var youngEntries        = new AtomicInteger();
        var youngAttemptsOnRedelivery = new CompletableFuture<Integer>();
        var logged              = ShardOwnedRebalanceIT.captureQueueLog();
        var settings            = fast(Duration.ofMillis(500), Duration.ofMillis(1000), stuckHandlerTimeout);
        var maxAttemptsOne      = RedeliveryPolicy.fixed(Duration.ofMillis(100), 1);

        var first  = new ShardOwnedQueue(dataSource, QUEUE_ID, SHARD_COUNT, "ord-young-a");
        var second = new ShardOwnedQueue(dataSource, QUEUE_ID, SHARD_COUNT, "ord-young-b");
        try {
            first.startConsumingOrdered((messageId, key, payload, payloadType) -> {
                var held = stuckKey.equals(key) ? stuckEntered : youngKey.equals(key) ? youngEntered : null;
                if (held != null && held.getCount() == 1) {
                    held.countDown();
                    holdUntil(release, holdsCutShort);
                }
            }, settings, SHARD_COUNT, maxAttemptsOne);
            Awaitility.await().atMost(Duration.ofSeconds(10))
                      .untilAsserted(() -> assertThat(first.shardsHeld()).isEqualTo(ShardOwnedSchema.ORDERED_UNITS));
            first.enqueueOrdered(List.of(new ShardOwnedStorage.OrderedPayload(stuckKey, 0, "stuck".getBytes(StandardCharsets.UTF_8), 1)));
            assertThat(stuckEntered.await(15, TimeUnit.SECONDS)).as("premise: the stuck key is in its handler").isTrue();
            var fenceBefore = fenceOf(stuckUnit);
            // The stuck key past the bound before the young key enters its handler, and the shed asked for at once after: it
            // completes once its grace has run out, with the one past the bound and the other in its handler for well under it.
            Thread.sleep(stuckHandlerTimeout.toMillis() + 500);
            first.enqueueOrdered(List.of(new ShardOwnedStorage.OrderedPayload(youngKey, 0, "young".getBytes(StandardCharsets.UTF_8), 1)));
            assertThat(youngEntered.await(15, TimeUnit.SECONDS)).as("premise: the young key is in its handler, in the same unit").isTrue();
            var youngEnteredAtNanos = System.nanoTime();

            second.startConsumingOrdered((messageId, key, payload, payloadType) -> {
                if (youngKey.equals(key)) {
                    youngAttemptsOnRedelivery.complete(attemptsOf(messageId.sequence()));
                    youngEntries.incrementAndGet();
                }
            }, settings, SHARD_COUNT, maxAttemptsOne);

            // The release WARN follows the charge, so seen while the young key is under the bound, the charge was made under it.
            var youngKeyUnderTheBoundFor = stuckHandlerTimeout.minus(Duration.ofNanos(System.nanoTime() - youngEnteredAtNanos));
            assertThat(youngKeyUnderTheBoundFor).as("premise: the young key is still under the bound once the joining instance has started")
                                                .isPositive();
            Awaitility.await("premise: the shed released the unit while the young key is still under the bound")
                      .atMost(youngKeyUnderTheBoundFor)
                      .until(() -> !queueWarnings(logged, "so those handlers were not waited for").isEmpty());
            assertThat(Duration.ofNanos(System.nanoTime() - youngEnteredAtNanos)).as("premise: the young key was still under the bound at the release")
                                                                               .isLessThan(stuckHandlerTimeout);

            Awaitility.await().atMost(Duration.ofSeconds(30)).untilAsserted(() -> {
                assertThat(ownerOf(stuckUnit)).as("the unit moved to the joining instance").isEqualTo("ord-young-b");
                assertThat(fenceOf(stuckUnit)).as("taken under a new fence").isGreaterThan(fenceBefore);
                assertThat(youngEntries.get()).as("the young key's message delivered again by the joining instance").isEqualTo(1);
            });
            var stuckWarnings = queueWarnings(logged, "so those handlers were not waited for");
            assertThat(stuckWarnings).as("one WARN for the release").singleElement().satisfies(warning -> {
                assertThat(warning.getArgumentArray()[1]).as("the unit").isEqualTo(stuckUnit);
                assertThat(warning.getFormattedMessage()).contains("in a fair-share shed with 2 key(s)")
                                                         .contains("1 message(s) in a handler longer than stuckHandlerTimeout were charged an attempt",
                                                                   "1 dead-lettered at their attempt limit [" + stuckKey + "]");
            });
            assertThat(holdsCutShort.get()).as("premise: both handlers are still held").isZero();
            assertThat(first.metrics().takeoverAttemptBumps.sum()).as("only the message in a handler past the bound was charged").isEqualTo(1);
            assertThat(second.metrics().takeoverAttemptBumps.sum()).as("released, not taken over: the joining instance charges nothing").isZero();
            assertThat(first.deadLetters()).as("only that message reached its attempt limit").singleElement()
                                           .satisfies(deadLetter -> {
                                               assertThat(deadLetter.key()).isEqualTo(stuckKey);
                                               assertThat(deadLetter.error()).isEqualTo(ShardOwnedStorage.EXHAUSTED_IN_A_RELEASED_HANDLER);
                                           });
            assertThat(youngAttemptsOnRedelivery.get(5, TimeUnit.SECONDS)).as("the young key's message is redelivered uncharged").isZero();
        } finally {
            release.countDown();
            ShardOwnedRebalanceIT.releaseQueueLog(logged);
            first.close();
            second.close();
        }
    }

    /**
     * One owner, both charge scopes: a fair-share shed past {@code stuckHandlerTimeout} charges the stuck key's message and
     * spares a young key of the same unit, and its release is refused; a stop then keeps the unit, both keys still in their
     * handlers, and a release on request charges the young key's message - every key in a handler is in its scope - but not
     * the stuck key's again, which the shed charged under the other scope. The answer's {@code charged} counts both: what
     * this owner has charged, the shed's charge included ({@link KeptUnitRelease#charged()}).
     * <p>
     * The joining instance stops before the incumbent does, so nothing takes the kept unit once the incumbent's liveness
     * lapses; the young key stays under the bound until the stop has ended the run, so no retried shed charges it first.
     */
    @Test
    void a_kept_unit_released_on_request_after_its_shed_charged_the_stuck_key_charges_only_the_young_key_and_answers_both_charges() throws Exception {
        var stuckUnit           = ShardOwnedSchema.ORDERED_UNITS - 1;
        var stuckKey            = keyOnUnit(stuckUnit);
        var youngKey            = otherKeyOnUnit(stuckUnit, stuckKey);
        // Room for the young key to stay under the bound through the join, the shed, its refused release and the stops, on a
        // loaded build too: the wait for the refusal below is cut to what is left of the bound, so a run too slow for it fails
        // on that premise, never on a retried shed charging the young key. The stuck key's wait past the bound grows with it.
        var stuckHandlerTimeout = Duration.ofSeconds(20);
        var stuckEntered        = new CountDownLatch(1);
        var youngEntered        = new CountDownLatch(1);
        var release             = new CountDownLatch(1);
        var holdsCutShort       = new AtomicInteger();
        var seqByKey            = new ConcurrentHashMap<String, Long>();
        var settings            = fast(Duration.ofMillis(500), Duration.ofMillis(1000), stuckHandlerTimeout);
        var maxAttemptsThree    = RedeliveryPolicy.fixed(Duration.ofMillis(100), 3);
        var queueIds            = new short[]{QUEUE_ID};

        var first  = new ShardOwnedQueue(dataSource, QUEUE_ID, SHARD_COUNT, "ord-mixed-a");
        var second = new ShardOwnedQueue(dataSource, QUEUE_ID, SHARD_COUNT, "ord-mixed-b");
        try {
            first.startConsumingOrdered((messageId, key, payload, payloadType) -> {
                var held = stuckKey.equals(key) ? stuckEntered : youngKey.equals(key) ? youngEntered : null;
                if (held != null && seqByKey.putIfAbsent(key, messageId.sequence()) == null) {
                    held.countDown();
                    holdUntil(release, holdsCutShort);
                }
            }, settings, SHARD_COUNT, maxAttemptsThree);
            Awaitility.await().atMost(Duration.ofSeconds(10))
                      .untilAsserted(() -> assertThat(first.shardsHeld()).isEqualTo(ShardOwnedSchema.ORDERED_UNITS));
            first.enqueueOrdered(List.of(new ShardOwnedStorage.OrderedPayload(stuckKey, 0, "stuck".getBytes(StandardCharsets.UTF_8), 1)));
            assertThat(stuckEntered.await(15, TimeUnit.SECONDS)).as("premise: the stuck key is in its handler").isTrue();
            var fenceBefore = fenceOf(stuckUnit);
            Thread.sleep(stuckHandlerTimeout.toMillis() + 500);
            first.enqueueOrdered(List.of(new ShardOwnedStorage.OrderedPayload(youngKey, 0, "young".getBytes(StandardCharsets.UTF_8), 1)));
            assertThat(youngEntered.await(15, TimeUnit.SECONDS)).as("premise: the young key is in its handler, in the same unit").isTrue();
            var youngEnteredAtNanos = System.nanoTime();

            LeaseReleaseRefusals.failLeaseReleasesOf(dataSource, queueIds, "ordered", stuckUnit, true);
            try {
                second.startConsumingOrdered((messageId, key, payload, payloadType) -> { }, settings, SHARD_COUNT, maxAttemptsThree);

                // Each refusal follows a charge that went through: the trigger refuses the lease row's release, not the charge.
                var youngKeyUnderTheBoundFor = stuckHandlerTimeout.minus(Duration.ofNanos(System.nanoTime() - youngEnteredAtNanos));
                assertThat(youngKeyUnderTheBoundFor).as("premise: the young key is still under the bound once the joining instance has started")
                                                    .isPositive();
                Awaitility.await("premise: the shed's release refused while the young key is still under the bound")
                          .atMost(youngKeyUnderTheBoundFor)
                          .until(() -> LeaseReleaseRefusals.releasesRefused(dataSource, QUEUE_ID, stuckUnit) > 0);
                second.stop();
                first.stop();
                assertThat(Duration.ofNanos(System.nanoTime() - youngEnteredAtNanos))
                        .as("premise: the young key was under the bound until the stop ended the run, so no shed charged it")
                        .isLessThan(stuckHandlerTimeout);
            } finally {
                LeaseReleaseRefusals.failLeaseReleasesOf(dataSource, queueIds, "ordered", stuckUnit, false);
            }
            assertThat(ownerOf(stuckUnit)).as("premise: the stop kept the unit, its release refused").isEqualTo("ord-mixed-a");
            assertThat(fenceOf(stuckUnit)).as("premise: and nothing took it").isEqualTo(fenceBefore);
            assertThat(first.metrics().takeoverAttemptBumps.sum()).as("premise: the shed charged the stuck key's message, and only that").isEqualTo(1);
            assertThat(attemptsOf(seqByKey.get(stuckKey))).as("premise: the stuck key's stored attempts").isEqualTo(1);
            assertThat(attemptsOf(seqByKey.get(youngKey))).as("premise: the young key's stored attempts").isZero();

            // BUSY while the stop's budget thread still flushes the kept unit; a BUSY answer charges nothing.
            var answer = new AtomicReference<KeptUnitRelease>();
            Awaitility.await("the kept unit released on request").atMost(Duration.ofSeconds(10)).until(() -> {
                var released = first.releaseKeptOrderedUnit("ord-mixed-a", stuckUnit);
                released.ifPresent(answer::set);
                return released.isPresent() && released.get().outcome() != KeptUnitRelease.Outcome.BUSY;
            });

            assertThat(answer.get().outcome()).isEqualTo(KeptUnitRelease.Outcome.RELEASED);
            assertThat(answer.get().keysInHandler()).as("premise: both keys still in their handlers").containsExactlyInAnyOrder(stuckKey, youngKey);
            assertThat(holdsCutShort.get()).as("premise: both handlers are still held").isZero();
            assertThat(first.metrics().takeoverAttemptBumps.sum()).as("the request charged the young key's message, not the stuck key's again").isEqualTo(2);
            assertThat(attemptsOf(seqByKey.get(stuckKey))).as("the stuck key's stored attempts, charged once by the shed").isEqualTo(1);
            assertThat(attemptsOf(seqByKey.get(youngKey))).as("the young key's stored attempts, charged once by the request").isEqualTo(1);
            assertThat(answer.get().charged()).as("the answer counts every message this owner charged, the shed's included").isEqualTo(2);
            assertThat(answer.get().deadLetteredKeys()).as("nothing reached its attempt limit").isEmpty();
            assertThat(ownerOf(stuckUnit)).as("released").isNull();
        } finally {
            release.countDown();
            LeaseReleaseRefusals.failLeaseReleasesOf(dataSource, queueIds, "ordered", stuckUnit, false);
            first.close();
            second.close();
        }
    }

    /**
     * The shed loop's charge failure is its own unit's: a shed unit whose charge before its release past
     * {@code stuckHandlerTimeout} fails - refused by the database here - stays leased to the incumbent, with one WARN per
     * attempt naming it, while a unit after it in the run's owners whose shed completes later, while that charge still
     * fails, is released and taken by the joining instance. Rethrown, the failure would end the whole loop, and no unit
     * after it would be released for as long as the charge kept failing. Once the database lets the charge through the held unit is
     * released too, and its message is delivered again with the one attempt counted.
     * <p>
     * Both units are in the incumbent's shed, the failing one first in its owners. The later one's key enters its handler
     * well after the failing one's, so its shed is abandoned while the failing one completes, and its handler is let go
     * only once the failing charge has been refused: its shed completes on a later tick than the failing one's.
     */
    @Test
    void a_shed_unit_whose_charge_before_its_release_fails_is_held_while_a_unit_shed_after_it_is_released() throws Exception {
        var half          = ShardOwnedSchema.ORDERED_UNITS / 2;
        var failingUnit   = half + 8;
        var laterUnit     = ShardOwnedSchema.ORDERED_UNITS - 1;
        var failingKey    = keyOnUnit(failingUnit);
        var laterKey      = keyOnUnit(laterUnit);
        var failingEntered = new CountDownLatch(1);
        var laterEntered  = new CountDownLatch(1);
        var releaseLater  = new CountDownLatch(1);
        var release       = new CountDownLatch(1);
        var holdsCutShort = new AtomicInteger();
        var entries       = new java.util.concurrent.atomic.AtomicInteger();
        var attemptsOnRedelivery = new CompletableFuture<Integer>();
        var logged        = ShardOwnedRebalanceIT.captureQueueLog();
        var settings      = fast(Duration.ofMillis(500), Duration.ofMillis(1000), Duration.ofSeconds(10));

        var first  = new ShardOwnedQueue(dataSource, QUEUE_ID, SHARD_COUNT, "ord-charge-a");
        var second = new ShardOwnedQueue(dataSource, QUEUE_ID, SHARD_COUNT, "ord-charge-b");
        try {
            first.startConsumingOrdered((messageId, key, payload, payloadType) -> {
                if (failingKey.equals(key) && entries.incrementAndGet() == 1) {
                    failingEntered.countDown();
                    holdUntil(release, holdsCutShort);
                } else if (laterKey.equals(key)) {
                    laterEntered.countDown();
                    holdUntil(releaseLater, holdsCutShort);
                }
            }, settings, SHARD_COUNT);
            Awaitility.await().atMost(Duration.ofSeconds(10))
                      .untilAsserted(() -> assertThat(first.shardsHeld()).isEqualTo(ShardOwnedSchema.ORDERED_UNITS));
            first.enqueueOrdered(List.of(new ShardOwnedStorage.OrderedPayload(failingKey, 0, "failing".getBytes(StandardCharsets.UTF_8), 1)));
            assertThat(failingEntered.await(15, TimeUnit.SECONDS)).as("premise: the failing unit's key is in its handler").isTrue();
            // This gap is the window between the failing key passing stuckHandlerTimeout and the later key passing it: the
            // failing charge must be refused, and the later handler let go, inside it. Wide, so a stalled heartbeat on a
            // loaded build does not carry the later key past its bound first (that fails the premise below, not vacuously).
            Thread.sleep(5_000);
            first.enqueueOrdered(List.of(new ShardOwnedStorage.OrderedPayload(laterKey, 0, "later".getBytes(StandardCharsets.UTF_8), 1)));
            assertThat(laterEntered.await(15, TimeUnit.SECONDS)).as("premise: the later unit's key is in its handler").isTrue();
            var failingFence = fenceOf(failingUnit);
            failOrderedAttemptChargesOn(failingUnit, true);
            try {
                second.startConsumingOrdered((messageId, key, payload, payloadType) -> {
                    if (failingKey.equals(key)) {
                        attemptsOnRedelivery.complete(attemptsOf(messageId.sequence()));
                    }
                }, settings, SHARD_COUNT);

                Awaitility.await().atMost(Duration.ofSeconds(30))
                          .untilAsserted(() -> assertThat(chargeFailuresOf(logged, failingUnit)).as("premise: the charge was refused").isNotEmpty());
                assertThat(chargeFailuresOf(logged, failingUnit).getFirst().getThrowableProxy().getMessage()).contains("simulated failure charging");
                assertThat(ownerOf(laterUnit)).as("premise: the later unit was not released before the failing charge").isEqualTo("ord-charge-a");
                var failuresBefore = chargeFailuresOf(logged, failingUnit).size();
                releaseLater.countDown();

                Awaitility.await().atMost(Duration.ofSeconds(20)).untilAsserted(() -> {
                    assertThat(ownerOf(laterUnit)).as("the unit shed after the failing one is released and taken by the joining instance").isEqualTo("ord-charge-b");
                    assertThat(second.shardsHeld()).as("every shed unit but the failing one").isEqualTo(half - 1);
                });
                assertThat(chargeFailuresOf(logged, failingUnit).size()).as("premise: the charge was still failing meanwhile").isGreaterThan(failuresBefore);
                assertThat(ownerOf(failingUnit)).as("the unit whose charge failed is still leased to the incumbent").isEqualTo("ord-charge-a");
                assertThat(fenceOf(failingUnit)).as("and not taken").isEqualTo(failingFence);
                assertThat(first.metrics().takeoverAttemptBumps.sum()).as("nothing charged while the database refuses it").isZero();
                assertThat(holdsCutShort.get()).as("premise: the failing unit's handler is still held, the later one let go by the test").isZero();
            } finally {
                failOrderedAttemptChargesOn(failingUnit, false);
            }

            Awaitility.await().atMost(Duration.ofSeconds(20)).untilAsserted(() -> {
                assertThat(ownerOf(failingUnit)).as("released once the charge goes through").isEqualTo("ord-charge-b");
                assertThat(first.shardsHeld()).isEqualTo(half);
                assertThat(second.shardsHeld()).isEqualTo(half);
            });
            assertThat(attemptsOnRedelivery.get(10, TimeUnit.SECONDS)).as("the message is redelivered with one attempt counted").isEqualTo(1);
        } finally {
            failOrderedAttemptChargesOn(failingUnit, false);
            releaseLater.countDown();
            release.countDown();
            ShardOwnedRebalanceIT.releaseQueueLog(logged);
            first.close();
            second.close();
        }
    }

    /**
     * A shed unit whose charge before its release keeps failing does not keep the incumbent from its acquire step: a unit
     * an earlier stop of the incumbent kept, given up past {@code stuckHandlerTimeout}, is taken over while the charge
     * still fails. Still held, the failing unit used to count towards the fair share, so the incumbent stayed one unit
     * above it: each tick only asked for that shed again and returned before the acquire step, and the given-up kept unit
     * - whose row names a live instance, so no other instance takes it - was served by nobody for as long as the charge
     * failed.
     * <p>
     * The joining instance starts before the kept key passes the bound, so the incumbent is over its share - shedding, and
     * not acquiring - from before the kept unit is given up until its failing unit's shed completes.
     */
    @Test
    void a_shed_unit_whose_charge_before_its_release_keeps_failing_does_not_keep_a_given_up_kept_unit_from_being_taken_over() throws Exception {
        var stuckHandlerTimeout = Duration.ofSeconds(6);
        var keptUnit      = 0;
        var failingUnit   = ShardOwnedSchema.ORDERED_UNITS - 1;
        var keptKey       = keyOnUnit(keptUnit);
        var failingKey    = keyOnUnit(failingUnit);
        var keptEntered   = new CountDownLatch(1);
        var failingEntered = new CountDownLatch(1);
        var release       = new CountDownLatch(1);
        var holdsCutShort = new AtomicInteger();
        var keptEntries   = new java.util.concurrent.atomic.AtomicInteger();
        var failingEntries = new java.util.concurrent.atomic.AtomicInteger();
        var logged        = ShardOwnedRebalanceIT.captureQueueLog();
        var settings      = fast(Duration.ofMillis(500), Duration.ofMillis(1000), stuckHandlerTimeout);

        var first  = new ShardOwnedQueue(dataSource, QUEUE_ID, SHARD_COUNT, "ord-kept-a");
        var second = new ShardOwnedQueue(dataSource, QUEUE_ID, SHARD_COUNT, "ord-kept-b");
        try {
            // The first delivery of each key holds its handler for the whole test; a later one returns at once.
            first.startConsumingOrdered((messageId, key, payload, payloadType) -> {
                if (keptKey.equals(key) && keptEntries.incrementAndGet() == 1) {
                    keptEntered.countDown();
                    holdUntil(release, holdsCutShort);
                } else if (failingKey.equals(key) && failingEntries.incrementAndGet() == 1) {
                    failingEntered.countDown();
                    holdUntil(release, holdsCutShort);
                }
            }, settings, SHARD_COUNT);
            Awaitility.await().atMost(Duration.ofSeconds(10))
                      .untilAsserted(() -> assertThat(first.shardsHeld()).isEqualTo(ShardOwnedSchema.ORDERED_UNITS));
            first.enqueueOrdered(List.of(new ShardOwnedStorage.OrderedPayload(keptKey, 0, "kept".getBytes(StandardCharsets.UTF_8), 1)));
            assertThat(keptEntered.await(15, TimeUnit.SECONDS)).as("premise: the kept unit's key is in its handler").isTrue();
            var keptSince = System.nanoTime();

            first.stop();
            var keptFence = fenceOf(keptUnit);
            assertThat(ownerOf(keptUnit)).as("premise: the stop kept the unit").isEqualTo("ord-kept-a");
            first.start();
            Awaitility.await().atMost(Duration.ofSeconds(10))
                      .untilAsserted(() -> assertThat(first.shardsHeld()).as("premise: every unit but the kept one").isEqualTo(ShardOwnedSchema.ORDERED_UNITS - 1));
            first.enqueueOrdered(List.of(new ShardOwnedStorage.OrderedPayload(failingKey, 0, "failing".getBytes(StandardCharsets.UTF_8), 1)));
            assertThat(failingEntered.await(15, TimeUnit.SECONDS)).as("premise: the failing unit's key is in its handler").isTrue();
            failOrderedAttemptChargesOn(failingUnit, true);
            try {
                var failingFence = fenceOf(failingUnit);
                second.startConsumingOrdered((messageId, key, payload, payloadType) -> { }, settings, SHARD_COUNT);
                assertThat(Duration.ofNanos(System.nanoTime() - keptSince))
                        .as("premise: the joining instance started well before the kept key passed the bound")
                        .isLessThan(stuckHandlerTimeout.minusSeconds(2));
                assertThat(fenceOf(keptUnit)).as("premise: the kept unit not taken over yet").isEqualTo(keptFence);

                Awaitility.await().atMost(Duration.ofSeconds(30)).untilAsserted(() -> {
                    assertThat(chargeFailuresOf(logged, failingUnit)).as("premise: the charge was refused").isNotEmpty();
                    assertThat(fenceOf(keptUnit)).as("the given-up kept unit taken over while the charge fails").isGreaterThan(keptFence);
                });
                assertThat(ownerOf(failingUnit)).as("the unit whose charge failed is still leased to the incumbent").isEqualTo("ord-kept-a");
                assertThat(fenceOf(failingUnit)).as("and not taken").isEqualTo(failingFence);
                assertThat(holdsCutShort.get()).as("premise: both handlers are still held").isZero();
            } finally {
                failOrderedAttemptChargesOn(failingUnit, false);
            }
        } finally {
            failOrderedAttemptChargesOn(failingUnit, false);
            release.countDown();
            ShardOwnedRebalanceIT.releaseQueueLog(logged);
            first.close();
            second.close();
        }
    }

    /**
     * Refuse, in the database, every write raising the attempts of an ordered row on {@code unit} - the charge before a
     * release past {@code stuckHandlerTimeout} - or stop refusing it.
     */
    private void failOrderedAttemptChargesOn(int unit, boolean fail) throws Exception {
        try (var connection = dataSource.getConnection(); var statement = connection.createStatement()) {
            if (fail) {
                statement.execute("CREATE OR REPLACE FUNCTION fail_ordered_attempt_charge() RETURNS trigger AS $$ BEGIN "
                                          + "IF NEW.attempts > OLD.attempts AND NEW.shard = " + unit + " THEN "
                                          + "RAISE EXCEPTION 'simulated failure charging an ordered attempt on unit " + unit + "'; END IF; "
                                          + "RETURN NEW; END $$ LANGUAGE plpgsql");
                statement.execute("CREATE TRIGGER fail_ordered_attempt_charge BEFORE UPDATE ON " + ShardOwnedSchema.ORDERED_TABLE
                                          + " FOR EACH ROW EXECUTE FUNCTION fail_ordered_attempt_charge()");
            } else {
                statement.execute("DROP TRIGGER IF EXISTS fail_ordered_attempt_charge ON " + ShardOwnedSchema.ORDERED_TABLE);
            }
        }
    }

    /**
     * The WARNs saying the charge before the release of {@code unit} failed, so the unit was not released.
     */
    private static List<ILoggingEvent> chargeFailuresOf(ListAppender<ILoggingEvent> logged, int unit) {
        return queueWarnings(logged, "could not charge the messages in a handler of ordered unit").stream()
                                                                                               .filter(event -> Integer.valueOf(unit).equals(event.getArgumentArray()[1]))
                                                                                               .toList();
    }

    /**
     * Hold a handler until {@code release} is counted down, at most {@link #HANDLER_HOLD}, and count in {@code cutShort} a hold
     * that ended without being let go - its wait ran out, or the handler thread was interrupted. Such a handler has returned on its
     * own while {@code release} still reads as not counted down, so a premise that the handlers are still held asserts
     * {@code cutShort} is zero rather than reading {@code release}.
     */
    private static void holdUntil(CountDownLatch release, AtomicInteger cutShort) {
        try {
            if (!release.await(HANDLER_HOLD.toMillis(), TimeUnit.MILLISECONDS)) {
                cutShort.incrementAndGet();
            }
        } catch (InterruptedException e) {
            cutShort.incrementAndGet();
            Thread.currentThread().interrupt();
        }
    }

    /**
     * A key the ordered lane routes to {@code unit}.
     */
    private static String keyOnUnit(int unit) {
        return OrderedUnitKeys.keyInUnit("STUCK-", unit, ShardOwnedSchema.ORDERED_UNITS);
    }

    /**
     * A key other than {@code other} that routes to {@code unit}, so its message is served by the same owner.
     */
    private static String otherKeyOnUnit(int unit, String other) {
        return OrderedUnitKeys.keyInUnit("YOUNG-", unit, ShardOwnedSchema.ORDERED_UNITS, other);
    }

    private long fenceOf(int unit) throws Exception {
        return SingleRowQueries.queryForLong(dataSource, "Reading the fence of ordered unit " + unit,
                                             "SELECT fence FROM " + ShardOwnedSchema.LEASE_TABLE + " WHERE queue_id = ? AND lane = 'ordered' AND shard = ?",
                                             QUEUE_ID, unit);
    }

    /**
     * The stored attempt count of ordered message {@code seq}, read from inside a handler: unchecked, as a handler cannot throw
     * a checked exception.
     */
    private int attemptsOf(long seq) {
        try {
            return SingleRowQueries.queryForInt(dataSource, "Reading the attempts of ordered message " + seq,
                                                "SELECT attempts FROM " + ShardOwnedSchema.ORDERED_TABLE + " WHERE queue_id = ? AND seq = ?",
                                                QUEUE_ID, seq);
        } catch (java.sql.SQLException e) {
            throw new IllegalStateException(e);
        }
    }

    private String ownerOf(int unit) throws Exception {
        return SingleRowQueries.queryForString(dataSource, "Reading the owner of ordered unit " + unit,
                                               "SELECT owner FROM " + ShardOwnedSchema.LEASE_TABLE + " WHERE queue_id = ? AND lane = 'ordered' AND shard = ?",
                                               QUEUE_ID, unit);
    }

    /**
     * The WARNs {@link ShardOwnedQueue} logged so far whose message pattern contains {@code containing}.
     */
    private static List<ILoggingEvent> queueWarnings(ListAppender<ILoggingEvent> logged, String containing) {
        List<ILoggingEvent> events;
        synchronized (logged) { // the monitor AppenderBase appends under; the heartbeat is still logging
            events = new ArrayList<>(logged.list);
        }
        return events.stream()
                     .filter(event -> event.getLevel() == Level.WARN)
                     .filter(event -> event.getMessage().contains(containing))
                     .toList();
    }

    /**
     * The heartbeat tick's ordered acquire loop: an acquire statement that fails after the loop has adopted units is the
     * failure the tick ends with, and the observer's failure for one of those units is added to it as suppressed, not
     * logged in its place. The first instance stops, so the second's next tick acquires every unit in order, and the last
     * one's acquisition is refused. {@code ShardOwnedRebalanceIT} pins the unordered lane's acquire loop and shed.
     */
    @Test
    void an_ordered_acquire_that_fails_is_the_logged_failure_when_the_observer_throws_for_an_earlier_acquisition() throws Exception {
        var observerThrows = new AtomicBoolean(true);
        var toldAcquired   = new CopyOnWriteArrayList<Integer>();
        var observer = new QueueObserver() {
            @Override
            public void shardOwnershipChanged(int shard, boolean acquired) {
                if (acquired && observerThrows.get()) {
                    toldAcquired.add(shard);
                    throw new IllegalStateException("simulated observer failure acquiring ordered unit " + shard);
                }
            }
        };
        var refusedUnit = ShardOwnedSchema.ORDERED_UNITS - 1;
        var logged      = ShardOwnedRebalanceIT.captureQueueLog();
        try (var first = new ShardOwnedQueue(dataSource, QUEUE_ID, SHARD_COUNT, "ord-masked-1")) {
            first.startConsumingOrdered((messageId, key, payload, payloadType) -> { }, fast(Duration.ofSeconds(5)), SHARD_COUNT);
            Awaitility.await().atMost(Duration.ofSeconds(10))
                      .untilAsserted(() -> assertThat(first.shardsHeld()).isEqualTo(ShardOwnedSchema.ORDERED_UNITS));

            LeaseAcquisitionRefusals.failLeaseAcquisitionsOf(dataSource, "ordered", refusedUnit, "ord-masked-2", true);
            try (var second = new ShardOwnedQueue(dataSource, QUEUE_ID, SHARD_COUNT, "ord-masked-2", new ShardOwnerMetrics(observer))) {
                second.startConsumingOrdered((messageId, key, payload, payloadType) -> { }, fast(Duration.ofSeconds(5)), SHARD_COUNT);
                assertThat(second.shardsHeld()).as("premise: nothing was free when the second started").isZero();
                // Every unit free at once: the second's next tick acquires them in order and reaches the refused one with
                // the others adopted.
                first.close();

                Awaitility.await().atMost(Duration.ofSeconds(10)).untilAsserted(
                        () -> assertThat(ShardOwnedRebalanceIT.rebalanceFailures(logged))
                                .as("a tick that told the observer logged the refused acquisition, with the observer's failure added to it")
                                .anyMatch(failure -> ShardOwnedRebalanceIT.carries(failure, "simulated failure acquiring",
                                                                                    "simulated observer failure acquiring")));
                assertThat(LeaseAcquisitionRefusals.acquisitionsRefused(dataSource)).as("premise: the acquisition was refused").isPositive();
                assertThat(toldAcquired).as("premise: the observer threw for a unit the loop adopted before the refused one")
                                        .isNotEmpty()
                                        .doesNotContain(refusedUnit);
            } finally {
                observerThrows.set(false);
                LeaseAcquisitionRefusals.failLeaseAcquisitionsOf(dataSource, "ordered", refusedUnit, "ord-masked-2", false);
            }
        } finally {
            ShardOwnedRebalanceIT.releaseQueueLog(logged);
        }
    }
}
