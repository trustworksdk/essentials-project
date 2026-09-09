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
import org.awaitility.Awaitility;
import org.junit.jupiter.api.*;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.*;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;

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

    /** Short lease, so several heartbeat-and-rebalance ticks happen inside the test window. */
    private static ShardOwnerSettings fast(Duration shedGrace) {
        return new ShardOwnerSettings(500, 200, Duration.ofMillis(1), Duration.ofMillis(2),
                                      Duration.ofMillis(300), Duration.ofMillis(100), 1_000, 8,
                                      Duration.ofMillis(50), Duration.ofSeconds(30), 2, shedGrace, Duration.ofMillis(1000), Duration.ofSeconds(60));
    }

    @Container
    static PostgreSQLContainer<?> postgres = LabPostgres.create();

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
            first.startConsumingOrdered((key, payload, payloadType) -> {
            }, fast(Duration.ofSeconds(5)), SHARD_COUNT);
            Awaitility.await().atMost(Duration.ofSeconds(10))
                      .untilAsserted(() -> assertThat(first.shardsHeld())
                              .isEqualTo(ShardOwnedSchema.ORDERED_UNITS));

            second.startConsumingOrdered((key, payload, payloadType) -> {
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

        OrderedPayloadHandler handler = (key, payload, payloadType) -> {
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
            first.startConsumingOrdered(handler, fast(Duration.ofSeconds(20)), SHARD_COUNT);
            enqueueOrdered(first, keys, perKey);
            // Join while work is in flight, so the shed happens with handlers running rather than
            // against an idle shard — the case that would reorder if the drain were skipped.
            Awaitility.await().atMost(Duration.ofSeconds(20)).until(() -> deliveries.get() > 4);
            second.startConsumingOrdered(handler, fast(Duration.ofSeconds(20)), SHARD_COUNT);

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

        OrderedPayloadHandler stuck = (key, payload, payloadType) -> {
            inHandler.countDown();
            try {
                // Outlasts the 500ms grace by a wide margin, and is released by the test rather than
                // by a timeout, so the abandon path is entered deterministically.
                blockHandlers.await(30, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        };

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

            second.startConsumingOrdered((key, payload, payloadType) -> {
            }, fast(Duration.ofMillis(500)), SHARD_COUNT);

            // The shed is attempted, cannot drain, and is given up on.
            Awaitility.await().atMost(Duration.ofSeconds(20))
                      .untilAsserted(() -> assertThat(first.metrics().shedsAbandoned.sum()).isPositive());
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
            blockHandlers.countDown();
            first.close();
            second.close();
        }
    }
}
