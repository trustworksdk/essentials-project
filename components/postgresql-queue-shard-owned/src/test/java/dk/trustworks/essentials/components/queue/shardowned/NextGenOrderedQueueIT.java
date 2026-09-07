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
import dk.trustworks.essentials.components.queue.shardowned.*;
import dk.trustworks.essentials.components.queue.shardowned.NextGenStorage.OrderedPayload;
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
 * The ordered lane's semantics — the design's largest claim, that per-key FIFO can be enforced in
 * memory by shard ownership instead of by the correlated head-of-key query a conventional ordered
 * queue runs on every poll.
 * <p>
 * Two properties have to hold together, and they pull against each other: order within a key, and
 * progress across keys. A design that only achieves the first is a shard-serialised queue wearing
 * ordering as an excuse.
 */
@Testcontainers(disabledWithoutDocker = true)
class NextGenOrderedQueueIT {

    private static final short QUEUE_ID    = 1;
    private static final int   SHARD_COUNT = 8;

    @Container
    static PostgreSQLContainer<?> postgres = LabPostgres.create();

    private HikariDataSource dataSource;

    @BeforeEach
    void setUp() throws Exception {
        var config = new HikariConfig();
        config.setJdbcUrl(postgres.getJdbcUrl());
        config.setUsername(postgres.getUsername());
        config.setPassword(postgres.getPassword());
        config.setMaximumPoolSize(40);
        dataSource = new HikariDataSource(config);

        NextGenSchema.create(dataSource, SHARD_COUNT);
        NextGenSchema.registerQueue(dataSource, QUEUE_ID, SHARD_COUNT);
    }

    @AfterEach
    void tearDown() {
        if (dataSource != null) {
            dataSource.close();
        }
    }

    @Test
    void messages_for_a_key_are_delivered_in_key_order() throws Exception {
        var keyCount = 50;
        var perKey = 20;
        var receivedByKey = new ConcurrentHashMap<String, List<Long>>();

        try (var queue = new NextGenQueue(dataSource, QUEUE_ID, SHARD_COUNT, "instance-1")) {
            queue.startConsumingOrdered((key, payload) -> receivedByKey
                                                .computeIfAbsent(key, ignored -> Collections.synchronizedList(new ArrayList<>()))
                                                .add(Long.parseLong(new String(payload, StandardCharsets.UTF_8))),
                                        ShardOwnerSettings.defaults(),
                                        SHARD_COUNT);

            var messages = new ArrayList<OrderedPayload>();
            for (var keyIndex = 0; keyIndex < keyCount; keyIndex++) {
                for (var order = 0; order < perKey; order++) {
                    messages.add(new OrderedPayload("key-" + keyIndex,
                                                    order,
                                                    Long.toString(order).getBytes(StandardCharsets.UTF_8)));
                }
            }
            queue.enqueueOrdered(messages, 1);

            Awaitility.await().atMost(Duration.ofSeconds(60))
                      .untilAsserted(() -> assertThat(receivedByKey.values().stream().mapToInt(List::size).sum())
                              .isEqualTo(keyCount * perKey));

            // The property under test: every key's deliveries are strictly ascending in key_order.
            receivedByKey.forEach((key, orders) -> {
                var snapshot = List.copyOf(orders);
                assertThat(snapshot)
                        .as("key %s must be delivered in key_order", key)
                        .isSorted();
                assertThat(snapshot).as("key %s must not see duplicates", key).doesNotHaveDuplicates();
            });
            assertThat(receivedByKey).hasSize(keyCount);

            var metrics = queue.metrics().snapshot();
            assertThat((Long) metrics.get("orderViolations"))
                    .as("no key_order should arrive after a higher one for the same key: %s", metrics)
                    .isZero();

            Awaitility.await().atMost(Duration.ofSeconds(30))
                      .untilAsserted(() -> assertThat(queue.orderedRemaining()).isZero());
        }
    }

    /**
     * Progress across keys. A key whose handler is slow must delay only that key — which is the
     * difference between per-key ordering and a shard that has been serialised.
     * <p>
     * This is the test that caught the parallelism claim being false: the first implementation ran
     * handlers inline on the owner thread, so exactly one key was ever in flight and a slow key
     * stalled its whole shard while the code read as though it did not.
     */
    @Test
    void a_slow_key_does_not_block_other_keys_in_the_same_shard() throws Exception {
        // Keys chosen so several land on the same shard — otherwise this would prove nothing about
        // parallelism WITHIN a shard, only across shards.
        var targetShard = NextGenSchema.shardForKey("key-0", SHARD_COUNT);
        var sameShardKeys = new ArrayList<String>();
        for (var candidate = 0; sameShardKeys.size() < 6 && candidate < 100_000; candidate++) {
            var key = "key-" + candidate;
            if (NextGenSchema.shardForKey(key, SHARD_COUNT) == targetShard) {
                sameShardKeys.add(key);
            }
        }
        assertThat(sameShardKeys).hasSize(6);

        var slowKey = sameShardKeys.get(0);
        var fastKeys = sameShardKeys.subList(1, sameShardKeys.size());
        var fastHandled = new java.util.concurrent.atomic.AtomicInteger();
        var slowReleased = new CountDownLatch(1);

        try (var queue = new NextGenQueue(dataSource, QUEUE_ID, SHARD_COUNT, "instance-1")) {
            queue.startConsumingOrdered((key, payload) -> {
                if (key.equals(slowKey)) {
                    try {
                        slowReleased.await(30, TimeUnit.SECONDS);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    }
                } else {
                    fastHandled.incrementAndGet();
                }
            }, ShardOwnerSettings.defaults(), SHARD_COUNT);

            var messages = new ArrayList<OrderedPayload>();
            for (var order = 0; order < 5; order++) {
                messages.add(new OrderedPayload(slowKey, order, "slow".getBytes(StandardCharsets.UTF_8)));
            }
            for (var key : fastKeys) {
                for (var order = 0; order < 10; order++) {
                    messages.add(new OrderedPayload(key, order, "fast".getBytes(StandardCharsets.UTF_8)));
                }
            }
            queue.enqueueOrdered(messages, 1);

            // The fast keys must complete while the slow key is still blocked inside its handler.
            Awaitility.await().atMost(Duration.ofSeconds(30))
                      .untilAsserted(() -> assertThat(fastHandled.get())
                              .as("fast keys must progress while a same-shard key is stuck")
                              .isEqualTo(fastKeys.size() * 10));

            var metrics = queue.metrics().snapshot();
            assertThat((Integer) metrics.get("maxConcurrentKeys"))
                    .as("more than one key must have been in flight at once, or ordering has serialised the shard: %s", metrics)
                    .isGreaterThan(1);
            // And the slow key must genuinely still be waiting — otherwise the test proved nothing.
            assertThat(slowReleased.getCount()).isEqualTo(1L);

            slowReleased.countDown();
            Awaitility.await().atMost(Duration.ofSeconds(30))
                      .untilAsserted(() -> assertThat(queue.orderedRemaining()).isZero());
        }
    }

    /**
     * An ordered owner must keep its lease alive, and must be refused once it does not.
     * <p>
     * This is the test whose absence hid a real gap: the ordered lane took a lease at startup and
     * then never renewed it, never checked it, and acknowledged without asserting it. Every existing
     * test finished well inside the lease lifetime, and nothing about a short run can distinguish
     * "the lease is being renewed" from "the lease has not expired yet". This one deliberately runs
     * past it.
     */
    @Test
    void an_ordered_owner_renews_its_lease_and_is_refused_once_superseded() throws Exception {
        var received = Collections.synchronizedList(new ArrayList<Long>());
        // Lease lifetime is holeExpiry x 3 = 900ms, so the idle period below spans several renewals.
        var shortLease = new ShardOwnerSettings(500, 200, Duration.ofMillis(1), Duration.ofMillis(2),
                                                Duration.ofMillis(300), Duration.ofMillis(100),
                                                1_000, 200L, 8, Duration.ofMillis(50), Duration.ofSeconds(30), 512, 2, Duration.ofSeconds(5));

        try (var queue = new NextGenQueue(dataSource, QUEUE_ID, SHARD_COUNT, "instance-1")) {
            queue.startConsumingOrdered((key, payload) -> received.add(Long.parseLong(
                    new String(payload, StandardCharsets.UTF_8))), shortLease, SHARD_COUNT);

            // Idle for several lease lifetimes. Without a heartbeat the leases lapse here, and the
            // shards become free for anyone to take while this owner keeps delivering.
            Thread.sleep(4_000L);

            var metrics = queue.metrics().snapshot();
            assertThat((Long) metrics.get("leaseRenewals"))
                    .as("an ordered owner must renew while idle: %s", metrics)
                    .isPositive();
            assertThat((Long) metrics.get("leasesLost"))
                    .as("nothing is competing, so no lease should be lost: %s", metrics)
                    .isZero();

            // Still working after outliving its original lease many times over.
            var messages = new ArrayList<OrderedPayload>();
            for (var order = 0; order < 5; order++) {
                messages.add(new OrderedPayload("k", order, Long.toString(order).getBytes(StandardCharsets.UTF_8)));
            }
            queue.enqueueOrdered(messages, 1);
            Awaitility.await().atMost(Duration.ofSeconds(30))
                      .untilAsserted(() -> assertThat(received).containsExactly(0L, 1L, 2L, 3L, 4L));
        }
    }

    /**
     * A superseded ordered owner must not be able to delete. For an ordered key this is worse than
     * for an unordered message: deleting work the new owner still owes loses it AND reorders what
     * remains.
     */
    @Test
    void a_superseded_ordered_owner_cannot_acknowledge() throws Exception {
        var storage = new NextGenStorage(dataSource, QUEUE_ID);
        try (var connection = dataSource.getConnection()) {
            storage.enqueueOrderedBatch(connection, 0,
                                        List.of(new OrderedPayload("k", 0, "a".getBytes(StandardCharsets.UTF_8)),
                                                new OrderedPayload("k", 1, "b".getBytes(StandardCharsets.UTF_8))),
                                        1);
        }
        var seqs = new ArrayList<Long>();
        try (var connection = dataSource.getConnection();
             var statement = connection.prepareStatement(
                     "SELECT seq FROM " + NextGenSchema.ORDERED_TABLE + " WHERE queue_id = ? AND shard = 0");) {
            statement.setShort(1, QUEUE_ID);
            try (var resultSet = statement.executeQuery()) {
                while (resultSet.next()) {
                    seqs.add(resultSet.getLong(1));
                }
            }
        }
        assertThat(seqs).hasSize(2);

        var staleFence = storage.acquireLease("ordered", 0, "instance-1", 60_000L).orElseThrow();
        try (var connection = dataSource.getConnection();
             var statement = connection.prepareStatement(
                     "UPDATE " + NextGenSchema.LEASE_TABLE + " SET lease_until = now() - interval '1 second'"
                     + " WHERE queue_id = ? AND lane = 'ordered' AND shard = 0")) {
            statement.setShort(1, QUEUE_ID);
            statement.executeUpdate();
        }
        var newFence = storage.acquireLease("ordered", 0, "instance-2", 60_000L).orElseThrow();
        assertThat(newFence).isGreaterThan(staleFence);

        try (var connection = dataSource.getConnection()) {
            assertThat(storage.acknowledgeOrdered(connection, 0, seqs, "instance-1", staleFence))
                    .as("a superseded ordered owner must delete nothing")
                    .isZero();
            assertThat(storage.countOrderedRemaining(0))
                    .as("the messages must remain for the new owner")
                    .isEqualTo(2L);
            assertThat(storage.acknowledgeOrdered(connection, 0, seqs, "instance-2", newFence))
                    .as("the current owner must still be able to acknowledge")
                    .isEqualTo(2);
        }
    }

    @Test
    void a_key_always_hashes_to_the_same_shard() {
        for (var index = 0; index < 1_000; index++) {
                var key = "key-" + index;
            var first = NextGenSchema.shardForKey(key, SHARD_COUNT);
            assertThat(NextGenSchema.shardForKey(key, SHARD_COUNT))
                    .as("shard routing must be stable, or a key's ordering breaks across restarts")
                    .isEqualTo(first);
            assertThat(first).isBetween(0, SHARD_COUNT - 1);
        }
    }
}
