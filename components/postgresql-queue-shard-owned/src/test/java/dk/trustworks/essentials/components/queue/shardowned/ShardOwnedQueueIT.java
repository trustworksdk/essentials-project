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
 * Semantics of the shard-owned engine, verified before any of its performance is measured.
 * <p>
 * The design's central bet is that a consumer can read a shard with a cursor, write nothing when it
 * consumes, and still never lose a message. Everything else about the design is a consequence of
 * that, so it is what gets tested first — and it is tested by counting what came out, not by
 * inspecting how it works.
 */
@Testcontainers(disabledWithoutDocker = true)
class ShardOwnedQueueIT {

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
    void every_enqueued_message_is_delivered_exactly_once_and_acknowledged() throws Exception {
        var received = new CopyOnWriteArrayList<String>();
        try (var queue = new ShardOwnedQueue(dataSource, QUEUE_ID, SHARD_COUNT, "instance-1")) {
            queue.startConsuming((messageId, payload, payloadType) -> received.add(new String(payload, StandardCharsets.UTF_8)),
                                 ShardOwnerSettings.defaults(),
                                 SHARD_COUNT);

            for (var batch = 0; batch < 20; batch++) {
                var payloads = new ArrayList<byte[]>();
                for (var index = 0; index < 50; index++) {
                    payloads.add(("msg-" + batch + "-" + index).getBytes(StandardCharsets.UTF_8));
                }
                queue.enqueue(payloads, 1);
            }

            Awaitility.await().atMost(Duration.ofSeconds(30))
                      .untilAsserted(() -> assertThat(received).hasSize(1_000));

            // No duplicates: delivery is deduplicated by the owner's in-flight and pending-ack sets,
            // and nothing else may deliver a shard it does not own.
            assertThat(new HashSet<>(received)).hasSize(1_000);

            // The queue drains to empty, which is what proves the batched range-delete
            // acknowledgement actually removes what was handled.
            Awaitility.await().atMost(Duration.ofSeconds(20))
                      .untilAsserted(() -> assertThat(queue.remaining()).isZero());
        }
    }

    /**
     * The hazard the design is built around: concurrent producers allocate sequence values in one
     * order and commit in another, so a cursor-following reader sees a later value before an earlier
     * one exists. Holding transactions open widens that window deliberately.
     */
    @Test
    void nothing_is_lost_when_producers_commit_out_of_sequence_order() throws Exception {
        var received = new CopyOnWriteArrayList<String>();
        var producerCount = 6;
        var perProducer = 150;

        try (var queue = new ShardOwnedQueue(dataSource, QUEUE_ID, SHARD_COUNT, "instance-1")) {
            queue.startConsuming((messageId, payload, payloadType) -> received.add(new String(payload, StandardCharsets.UTF_8)),
                                 ShardOwnerSettings.defaults(),
                                 SHARD_COUNT);

            var executor = Executors.newFixedThreadPool(producerCount);
            var futures = new ArrayList<Future<?>>();
            for (var producer = 0; producer < producerCount; producer++) {
                var id = producer;
                futures.add(executor.submit(() -> {
                    for (var index = 0; index < perProducer; index++) {
                        try (var connection = dataSource.getConnection()) {
                            connection.setAutoCommit(false);
                            new ShardOwnedStorage(dataSource, QUEUE_ID)
                                    .enqueueBatch(connection,
                                                  index % SHARD_COUNT,
                                                  List.of(("p" + id + "-" + index).getBytes(StandardCharsets.UTF_8)),
                                                  1);
                            // Hold times MUST differ, or the hazard never occurs. With every
                            // producer holding the same duration, allocation order and commit order
                            // coincide and no hole forms — an earlier version of this test held a
                            // uniform 2ms and passed only when it got lucky. Producer 0 holds long
                            // and the rest commit immediately, so a later-allocated value reliably
                            // commits before an earlier one.
                            Thread.sleep(id == 0 ? 25L : 0L);
                            connection.commit();
                        } catch (Exception e) {
                            throw new IllegalStateException(e);
                        }
                    }
                    return null;
                }));
            }
            for (var future : futures) {
                future.get(90, TimeUnit.SECONDS);
            }
            executor.shutdown();

            var expected = producerCount * perProducer;
            Awaitility.await().atMost(Duration.ofSeconds(60))
                      .untilAsserted(() -> assertThat(received).hasSize(expected));
            assertThat(new HashSet<>(received)).hasSize(expected);

            // Deliberately NOT asserting that holes occurred here. Whether a concurrent producer
            // race is observed as a hole depends on when the owner happens to read, and Tier 1
            // wake-up changed that: the owner now reads just after a commit rather than every
            // 200 microseconds, so it far more often sees a consistent picture. An earlier version
            // asserted holesObserved > 0 here and became flaky the moment notifications landed.
            // The invariant this test exists for is that nothing is lost; the mechanism gets its own
            // test below, where the hazard is constructed rather than hoped for.
            assertThat((Long) queue.metrics().snapshot().get("holesAbandoned"))
                    .as("no hole should expire unresolved when every transaction commits")
                    .isZero();
        }
    }

    /**
     * The hole mechanism, constructed deterministically rather than raced for.
     * <p>
     * Transaction A inserts and holds; transaction B inserts into the same shard and commits. The
     * owner therefore cannot avoid seeing B's sequence value with A's missing — the exact condition
     * §4.4 exists to handle. Then A commits, and the chase has to find it.
     */
    @Test
    void a_hole_is_observed_and_chased_when_an_earlier_sequence_value_commits_late() throws Exception {
        var received = new CopyOnWriteArrayList<String>();
        try (var queue = new ShardOwnedQueue(dataSource, QUEUE_ID, SHARD_COUNT, "instance-1")) {
            queue.startConsuming((messageId, payload, payloadType) -> received.add(new String(payload, StandardCharsets.UTF_8)),
                                 ShardOwnerSettings.defaults(),
                                 SHARD_COUNT);

            var storage = new ShardOwnedStorage(dataSource, QUEUE_ID);
            try (var held = dataSource.getConnection()) {
                held.setAutoCommit(false);
                // A: allocates the earlier sequence value, stays uncommitted.
                storage.enqueueBatch(held, 0, List.of("early".getBytes(StandardCharsets.UTF_8)), 1);

                // B: allocates a later value in the same shard and commits immediately.
                try (var committed = dataSource.getConnection()) {
                    storage.enqueueBatch(committed, 0, List.of("late".getBytes(StandardCharsets.UTF_8)), 1);
                }

                // The owner must now see the later value with the earlier one missing.
                Awaitility.await().atMost(Duration.ofSeconds(20))
                          .untilAsserted(() -> assertThat(received).contains("late"));
                Awaitility.await().atMost(Duration.ofSeconds(20))
                          .untilAsserted(() -> assertThat((Long) queue.metrics().snapshot().get("holesObserved"))
                                  .as("stepping over an uncommitted sequence value is a hole")
                                  .isPositive());
                assertThat(received).as("the uncommitted message must not have been delivered").doesNotContain("early");

                held.commit();
            }

            // Once it commits, the chase has to find it — without the cursor ever going backwards.
            Awaitility.await().atMost(Duration.ofSeconds(30))
                      .untilAsserted(() -> assertThat(received).contains("early"));

            var metrics = queue.metrics().snapshot();
            assertThat((Long) metrics.get("holesResolved")).as("%s", metrics).isPositive();
            assertThat((Long) metrics.get("holesAbandoned")).as("%s", metrics).isZero();
            Awaitility.await().atMost(Duration.ofSeconds(20))
                      .untilAsserted(() -> assertThat(queue.remaining()).isZero());
        }
    }

    /**
     * At-least-once across an owner crash. The lease expires, another instance takes the shard, and
     * everything the first owner had read but not acknowledged is delivered again — which is the
     * whole reason the fast path is allowed to write nothing when it consumes.
     */
    @Test
    void a_new_owner_redelivers_what_a_crashed_owner_left_unacknowledged() throws Exception {
        var firstReceived = new CopyOnWriteArrayList<String>();
        var payloads = new ArrayList<byte[]>();
        for (var index = 0; index < 400; index++) {
            payloads.add(("msg-" + index).getBytes(StandardCharsets.UTF_8));
        }

        // A handler that blocks forever: messages get read and dispatched but never acknowledged,
        // which is exactly the state a crash leaves behind.
        var blocker = new CountDownLatch(1);
        var first = new ShardOwnedQueue(dataSource, QUEUE_ID, SHARD_COUNT, "instance-1");
        first.enqueue(payloads, 1);
        // A short lease, so the takeover does not have to wait out a production TTL.
        var shortLease = new ShardOwnerSettings(500, 200,
                                                Duration.ofMillis(1), Duration.ofMillis(2),
                                                Duration.ofMillis(300), Duration.ofMillis(100),
                                                1_000, 8, Duration.ofMillis(100), Duration.ofSeconds(30), 2, Duration.ofSeconds(5), Duration.ofMillis(1000), Duration.ofSeconds(60));
        first.startConsuming((messageId, payload, payloadType) -> {
            firstReceived.add(new String(payload, StandardCharsets.UTF_8));
            try {
                blocker.await();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }, shortLease, SHARD_COUNT);

        Awaitility.await().atMost(Duration.ofSeconds(20)).until(() -> !firstReceived.isEmpty());
        first.stopAbruptly();

        // Wait past the lease so the shards are genuinely free.
        Thread.sleep(1_200L);

        var secondReceived = new CopyOnWriteArrayList<String>();
        try (var second = new ShardOwnedQueue(dataSource, QUEUE_ID, SHARD_COUNT, "instance-2")) {
            second.startConsuming((messageId, payload, payloadType) -> secondReceived.add(new String(payload, StandardCharsets.UTF_8)),
                                  ShardOwnerSettings.defaults(),
                                  SHARD_COUNT);

            Awaitility.await().atMost(Duration.ofSeconds(60))
                      .untilAsserted(() -> assertThat(second.remaining()).isZero());

            // Union across both owners must cover everything: at-least-once, nothing lost.
            var union = new HashSet<String>(firstReceived);
            union.addAll(secondReceived);
            assertThat(union).hasSize(400);

            // Poison-message protection. The fast path never writes an attempt count, so the only
            // thing standing between a JVM-killing handler and an infinite redelivery loop is the
            // bulk bump the new owner performs on takeover. Asserting the union above would pass
            // whether or not that happened, so it is checked directly.
            assertThat((Long) second.metrics().snapshot().get("takeoverAttemptBumps"))
                    .as("takeover must have bumped the attempt count of what the crashed owner left: %s",
                        second.metrics().snapshot())
                    .isPositive();
        } finally {
            blocker.countDown();
            first.close();
        }
    }

    /**
     * Competing consumers. Two instances lease disjoint shard subsets, so no message can be
     * delivered by both — ownership, not locking, is what makes that true.
     */
    @Test
    void two_instances_split_the_shards_and_do_not_deliver_the_same_message_twice() throws Exception {
        var firstReceived = new CopyOnWriteArrayList<String>();
        var secondReceived = new CopyOnWriteArrayList<String>();

        try (var first = new ShardOwnedQueue(dataSource, QUEUE_ID, SHARD_COUNT, "instance-1");
             var second = new ShardOwnedQueue(dataSource, QUEUE_ID, SHARD_COUNT, "instance-2")) {

            first.startConsuming((messageId, payload, payloadType) -> firstReceived.add(new String(payload, StandardCharsets.UTF_8)),
                                 ShardOwnerSettings.defaults(),
                                 SHARD_COUNT / 2);
            second.startConsuming((messageId, payload, payloadType) -> secondReceived.add(new String(payload, StandardCharsets.UTF_8)),
                                  ShardOwnerSettings.defaults(),
                                  SHARD_COUNT / 2);

            for (var batch = 0; batch < 16; batch++) {
                var payloads = new ArrayList<byte[]>();
                for (var index = 0; index < 25; index++) {
                    payloads.add(("m-" + batch + "-" + index).getBytes(StandardCharsets.UTF_8));
                }
                first.enqueue(payloads, 1);
            }

            Awaitility.await().atMost(Duration.ofSeconds(45))
                      .untilAsserted(() -> assertThat(firstReceived.size() + secondReceived.size()).isEqualTo(400));

            assertThat(secondReceived)
                    .as("the second instance must have leased shards of its own, or this proves nothing")
                    .isNotEmpty();
            var overlap = new HashSet<>(firstReceived);
            overlap.retainAll(new HashSet<>(secondReceived));
            assertThat(overlap)
                    .as("a message delivered by both instances would mean ownership is not exclusive")
                    .isEmpty();
        }
    }
}
